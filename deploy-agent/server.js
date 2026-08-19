const express = require('express');
const cors = require('cors');
const { NodeSSH } = require('node-ssh');
const crypto = require('crypto');
const fs = require('fs');
const { exec } = require('child_process');
const util = require('util');
const execPromise = util.promisify(exec);

const app = express();
app.use(cors());
app.use(express.json());

// Global Deployment State
const activeDeploy = {
  isRunning: false,
  env: null, // 'production', 'rollback_prod', 'upgrade'
  targetIp: null,
  logs: [],
  currentStep: 0,
  vmWarnings: [],
  containerErrors: [],
  clients: [], // SSE connections
  code: null,
  done: false
};

// Helper to broadcast to all clients
function broadcast(event) {
  const dataString = `data: ${JSON.stringify(event)}\n\n`;
  activeDeploy.clients.forEach(res => res.write(dataString));
}

function appendLog(logStr, error = false) {
  activeDeploy.logs.push(logStr);
  
  // Parse progress logic from the deploy script output
  const stepMatch = logStr.match(/--- Step (\d+):/);
  if (stepMatch) {
    activeDeploy.currentStep = parseInt(stepMatch[1], 10);
  }
  const vmMatch = logStr.match(/\[VM_UPDATE_NEEDED\] (.*)/);
  if (vmMatch) {
    activeDeploy.vmWarnings.push(vmMatch[1]);
  }
  const containerMatch = logStr.match(/\[CONTAINER_ERROR\] (.*)/);
  if (containerMatch) {
    activeDeploy.containerErrors.push(containerMatch[1]);
  }

  broadcast({ log: logStr, error });
}

// Helper to execute SSH commands and append to global logs
async function runSshCommandBackground(ssh, cmd, onExit) {
  appendLog(`> Executing SSH Task...`);
  try {
    const result = await ssh.execCommand(cmd, {
      onStdout(chunk) {
        appendLog(chunk.toString('utf8').trim());
      },
      onStderr(chunk) {
        appendLog(chunk.toString('utf8').trim(), true);
      }
    });
    
    if (result.code !== 0) {
      appendLog(`Command failed with exit code ${result.code}`, true);
      if (onExit) onExit(result.code);
    } else {
      appendLog(`Command completed successfully.`);
      if (onExit) onExit(0);
    }
  } catch (err) {
    appendLog(`Command failed: ${err.message}`, true);
    if (onExit) onExit(1);
  }
}

function finishDeploy(code) {
  activeDeploy.isRunning = false;
  activeDeploy.done = true;
  activeDeploy.code = code;
  broadcast({ done: true, code });
}

// 1. Trigger Deployment (POST)
app.post('/api/deploy/trigger', async (req, res) => {
  const { ip, username, password, env } = req.body;
  if (!ip || !username || !env) {
    return res.status(400).json({ error: 'IP, username, and env are required' });
  }

  if (activeDeploy.isRunning) {
    return res.status(409).json({ error: 'A deployment is already in progress.' });
  }

  // Initialize state
  activeDeploy.isRunning = true;
  activeDeploy.done = false;
  activeDeploy.code = null;
  activeDeploy.env = env;
  activeDeploy.targetIp = ip;
  activeDeploy.logs = [];
  activeDeploy.currentStep = 0;
  activeDeploy.vmWarnings = [];
  activeDeploy.containerErrors = [];
  
  res.json({ message: 'Deployment started' });

  // Start background process
  const isRollback = env === 'rollback_prod';
  const isUpgrade = env === 'upgrade';
  
  const actionName = isRollback ? 'Rollback' : isUpgrade ? 'Package Upgrades' : 'Deployment';
  appendLog(`>>> Initiating ${actionName} to ${ip}...`);

  // Ensure SSH keys exist
  try {
    await execPromise('mkdir -p /root/.ssh && cp -r /app/.ssh_host/* /root/.ssh/ && chown -R root:root /root/.ssh && chmod -R 600 /root/.ssh/*');
  } catch(e) {}

  if (env === 'production') {
    // 1. Auto-Push Local Changes
    appendLog('Committing and pushing local changes to GitHub...');
    try {
      const gitCmds = `
        git config --global --add safe.directory /repo &&
        git config --global user.email "deploy-agent@messaging-agent.local" &&
        git config --global user.name "Deploy Agent" &&
        git remote set-url origin git@github.com:nikomaxos/messaging-agent.git &&
        CURRENT_VERSION=\$(grep '"version"' admin-panel/package.json | head -1 | awk -F'"' '{print $4}') &&
        V_MAJOR=\$(echo \$CURRENT_VERSION | cut -d. -f1) &&
        V_MINOR=\$(echo \$CURRENT_VERSION | cut -d. -f2) &&
        V_PATCH=\$(echo \$CURRENT_VERSION | cut -d. -f3) &&
        NEXT_PATCH=\$((\$V_PATCH + 1)) &&
        NEXT_VERSION="\$V_MAJOR.\$V_MINOR.\$NEXT_PATCH" &&
        ./bump-version.sh \$NEXT_VERSION &&
        git add . &&
        (git commit -m "Auto-Deploy: Version \$NEXT_VERSION - Pushed from Admin Panel" || true) &&
        GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no" git push origin main
      `;
      await execPromise(gitCmds, { cwd: '/repo' });
      appendLog('Successfully pushed local changes.');
    } catch (err) {
      appendLog(`Failed to push local changes: ${err.message}`, true);
      return finishDeploy(1);
    }
  }

  // 2. SSH into target
  appendLog(`Connecting to ${ip} via SSH...`);
  const ssh = new NodeSSH();
  try {
    await ssh.connect({
      host: ip,
      username: username,
      password: password || undefined,
      privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'),
      tryKeyboard: true,
      readyTimeout: 10000
    });
    appendLog(`SSH Connected successfully.`);

    let cmd = '';
    if (env === 'production') {
      cmd = `
        echo "Triggering deployment on Target Node..."
        if [ ! -d ~/messaging-agent ]; then git clone https://github.com/nikomaxos/messaging-agent.git ~/messaging-agent; fi 
        cd ~/messaging-agent 
        git fetch origin main && git reset --hard origin/main
        chmod +x ./deploy-agent/deploy-k8s.sh
        ./deploy-agent/deploy-k8s.sh
      `;
    } else if (env === 'rollback_prod') {
      appendLog('Rolling back K3s deployments natively via kubectl...');
      cmd = `kubectl rollout undo deployment --all`;
    } else if (env === 'upgrade') {
      appendLog('Running system upgrades on Kubernetes nodes...');
      cmd = `
        for node in 10.10.10.193 10.10.10.194 10.10.10.195; do
          echo "Updating node \$node..."
          ssh -o StrictHostKeyChecking=no ubuntu@\$node "sudo DEBIAN_FRONTEND=noninteractive apt-get update && sudo DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
        done
        echo "All nodes upgraded successfully."
      `;
    }

    await runSshCommandBackground(ssh, cmd, (code) => {
      ssh.dispose();
      finishDeploy(code);
    });

  } catch (err) {
    appendLog(`SSH Connection Failed: ${err.message}`, true);
    finishDeploy(1);
  }
});

// 2. Global Stream Endpoint (GET)
app.get('/api/deploy/stream', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');

  // Immediately send current global state to sync the UI
  res.write(`data: ${JSON.stringify({ 
    sync: true, 
    isRunning: activeDeploy.isRunning,
    env: activeDeploy.env,
    targetIp: activeDeploy.targetIp,
    currentStep: activeDeploy.currentStep,
    vmWarnings: activeDeploy.vmWarnings,
    containerErrors: activeDeploy.containerErrors,
    done: activeDeploy.done,
    code: activeDeploy.code,
    logs: activeDeploy.logs 
  })}\n\n`);

  activeDeploy.clients.push(res);

  // Send a heartbeat every 15s to keep the connection open
  const keepAlive = setInterval(() => {
    res.write(':\n\n');
  }, 15000);

  req.on('close', () => {
    clearInterval(keepAlive);
    activeDeploy.clients = activeDeploy.clients.filter(client => client !== res);
  });
});

// 3. Get Deploy Info
app.post('/api/deploy/info', async (req, res) => {
  const { ip, username, password } = req.body;
  if (!ip || !username) {
    return res.status(400).json({ error: 'IP and username are required' });
  }

  try {
    await execPromise('mkdir -p /root/.ssh && cp -r /app/.ssh_host/* /root/.ssh/ && chown -R root:root /root/.ssh && chmod -R 600 /root/.ssh/*');
  } catch(e) {}

  const ssh = new NodeSSH();
  try {
    await ssh.connect({ host: ip, username, password: password || undefined, privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), tryKeyboard: true, readyTimeout: 5000 });
    
    const versionResult = await ssh.execCommand("cat ~/messaging-agent/.last-deploy 2>/dev/null || echo 'Never Deployed'");
    const commitHash = versionResult.stdout.trim();
    
    const pkgResult = await ssh.execCommand(`cd ~/messaging-agent && git show ${commitHash}:admin-panel/package.json 2>/dev/null || echo '{"version":"Unknown"}'`);
    let prodVersion = "Unknown";
    try {
      prodVersion = "v" + JSON.parse(pkgResult.stdout).version;
    } catch(e) {}

    ssh.dispose();
    res.json({ productionVersion: prodVersion, lastCommit: commitHash, rollbackTarget: "Kubernetes Native Rollback (Previous ReplicaSet)" });

  } catch (err) {
    res.status(500).json({ error: `SSH Connection Failed: ${err.message}` });
  }
});

const PORT = process.env.PORT || 8082;
app.listen(PORT, () => {
  console.log(`Deploy Agent listening on port ${PORT}`);
});
