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
  done: false,
  totalSteps: 9,
  steps: [],
  sshClient: null
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
  const totalMatch = logStr.match(/\[TOTAL_STEPS\] (\d+)/);
  if (totalMatch) {
    activeDeploy.totalSteps = parseInt(totalMatch[1], 10);
    broadcast({ totalSteps: activeDeploy.totalSteps });
  }
  const defMatch = logStr.match(/\[STEP_DEF\] (\d+)\|(.*?)\|(.*)/);
  if (defMatch) {
    const newStep = {
      step: parseInt(defMatch[1], 10),
      title: defMatch[2].trim(),
      desc: defMatch[3].trim()
    };
    activeDeploy.steps.push(newStep);
    broadcast({ newStep });
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
  activeDeploy.totalSteps = 9;
  activeDeploy.steps = [];
  activeDeploy.sshClient = null;
  activeDeploy.vmWarnings = [];
  activeDeploy.containerErrors = [];
  
  // Notify all connected clients to sync state (which clears logs)
  activeDeploy.clients.forEach(client => {
    client.write(`data: ${JSON.stringify({ 
      sync: true, 
      isRunning: activeDeploy.isRunning, 
      env: activeDeploy.env,
      targetIp: activeDeploy.targetIp,
      currentStep: activeDeploy.currentStep,
      totalSteps: activeDeploy.totalSteps,
      steps: activeDeploy.steps,
      vmWarnings: activeDeploy.vmWarnings,
      containerErrors: activeDeploy.containerErrors,
      done: activeDeploy.done,
      code: activeDeploy.code,
      logs: activeDeploy.logs 
    })}\n\n`);
  });
  
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
  activeDeploy.sshClient = ssh;
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
    totalSteps: activeDeploy.totalSteps,
    steps: activeDeploy.steps,
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

// 3. Cancel Deployment Endpoint
app.post('/api/deploy/cancel', async (req, res) => {
  if (!activeDeploy.isRunning) {
    return res.status(400).json({ error: 'No deployment is currently running.' });
  }

  appendLog('>>> CANCELLING DEPLOYMENT...', true);
  if (activeDeploy.sshClient) {
    activeDeploy.sshClient.dispose();
    activeDeploy.sshClient = null;
  }
  
  // Perform Native K3s Rollback immediately
  appendLog('>>> TRIGGERING ROLLBACK...', true);
  
  const rollbackSsh = new NodeSSH();
  try {
    await rollbackSsh.connect({
      host: activeDeploy.targetIp,
      username: 'ubuntu',
      privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'),
      tryKeyboard: true,
      readyTimeout: 10000
    });
    
    appendLog('Rolling back K3s deployments natively via kubectl...');
    await runSshCommandBackground(rollbackSsh, 'kubectl rollout undo deployment --all', (code) => {
      rollbackSsh.dispose();
      finishDeploy(code);
    });
    
    res.json({ message: 'Deployment cancelled and rollback started.' });
  } catch (err) {
    appendLog(`Rollback connection failed: ${err.message}`, true);
    finishDeploy(1);
    res.status(500).json({ error: 'Failed to start rollback.' });
  }
});

// 4. Get Deploy Info
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

// 5. Backup & Restore Endpoints
const BACKUP_CONFIG_PATH = '/app/backup-config.json';
const RCLONE_CONF_DIR = '/root/.config/rclone';
const RCLONE_CONF_PATH = `${RCLONE_CONF_DIR}/rclone.conf`;

// Setup rclone conf if backup config exists
function setupRclone() {
  if (fs.existsSync(BACKUP_CONFIG_PATH)) {
    try {
      const config = JSON.parse(fs.readFileSync(BACKUP_CONFIG_PATH, 'utf8'));
      if (config.serviceAccountJson) {
        fs.mkdirSync(RCLONE_CONF_DIR, { recursive: true });
        const saPath = '/app/gdrive-sa.json';
        fs.writeFileSync(saPath, config.serviceAccountJson);
        const rcloneConf = `[gdrive]\ntype = drive\nscope = drive\nservice_account_file = ${saPath}\n`;
        fs.writeFileSync(RCLONE_CONF_PATH, rcloneConf);
        return config;
      }
    } catch(e) {
      console.error("Failed to setup rclone", e);
    }
  }
  return null;
}
setupRclone();

function readBackupConfig() {
  if (fs.existsSync(BACKUP_CONFIG_PATH)) {
    return JSON.parse(fs.readFileSync(BACKUP_CONFIG_PATH, 'utf8'));
  }
  return null;
}

function writeBackupConfig(config) {
  fs.writeFileSync(BACKUP_CONFIG_PATH, JSON.stringify(config));
}

app.get('/api/backup/config', (req, res) => {
  const config = readBackupConfig();
  if (config && config.serviceAccountJson) {
    res.json({ configured: true, gdrivePath: config.gdrivePath });
  } else {
    res.json({ configured: false });
  }
});

app.post('/api/backup/config', (req, res) => {
  const { gdrivePath, serviceAccountJson } = req.body;
  if (!gdrivePath || !serviceAccountJson) {
    return res.status(400).json({ error: 'gdrivePath and serviceAccountJson are required' });
  }
  try {
    JSON.parse(serviceAccountJson);
  } catch(e) {
    return res.status(400).json({ error: 'serviceAccountJson must be a valid JSON string' });
  }
  const existing = readBackupConfig() || {};
  writeBackupConfig({ ...existing, gdrivePath, serviceAccountJson });
  setupRclone();
  res.json({ message: 'Configuration saved successfully' });
});

// --- Auto-Backup Schedule ---
app.get('/api/backup/schedule', (req, res) => {
  const config = readBackupConfig();
  if (!config) return res.json({ enabled: false, hour: 3 });
  res.json({ enabled: !!config.autoBackupEnabled, hour: config.autoBackupHour ?? 3 });
});

app.post('/api/backup/schedule', (req, res) => {
  const { enabled, hour } = req.body;
  if (typeof enabled !== 'boolean' || typeof hour !== 'number' || hour < 0 || hour > 23) {
    return res.status(400).json({ error: 'enabled (boolean) and hour (0-23) are required' });
  }
  const config = readBackupConfig();
  if (!config) return res.status(400).json({ error: 'Backup not configured yet' });
  config.autoBackupEnabled = enabled;
  config.autoBackupHour = hour;
  writeBackupConfig(config);
  res.json({ message: `Auto-backup ${enabled ? 'enabled' : 'disabled'} at ${String(hour).padStart(2, '0')}:00 daily` });
});

app.get('/api/backup/list', async (req, res) => {
  const config = setupRclone();
  if (!config) return res.status(400).json({ error: 'Backup not configured' });
  
  try {
    // List files in gdrive using rclone
    const { stdout } = await execPromise(`rclone lsjson gdrive:"${config.gdrivePath}" 2>/dev/null`);
    const files = JSON.parse(stdout || '[]').filter(f => !f.IsDir && f.Name.endsWith('.dump'));
    // Sort by modification time descending
    files.sort((a, b) => new Date(b.ModTime).getTime() - new Date(a.ModTime).getTime());
    res.json({ files });
  } catch (err) {
    res.status(500).json({ error: 'Failed to list backups from Google Drive. Verify Service Account and Path.' });
  }
});

// We'll use a simple global state for backup streaming to avoid browser timeouts
let activeBackup = {
  isRunning: false,
  logs: [],
  clients: []
};

function bBroadcast(event) {
  const dataString = `data: ${JSON.stringify(event)}\n\n`;
  activeBackup.clients.forEach(c => c.write(dataString));
}
function bLog(str, error = false) {
  activeBackup.logs.push(str);
  bBroadcast({ log: str, error });
}

app.get('/api/backup/stream', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ sync: true, isRunning: activeBackup.isRunning, logs: activeBackup.logs })}\n\n`);
  activeBackup.clients.push(res);
  req.on('close', () => { activeBackup.clients = activeBackup.clients.filter(c => c !== res); });
});

app.post('/api/backup/trigger', async (req, res) => {
  const { ip } = req.body;
  if (!ip) return res.status(400).json({ error: 'Target IP is required' });
  const config = setupRclone();
  if (!config) return res.status(400).json({ error: 'Backup not configured' });
  if (activeBackup.isRunning) return res.status(409).json({ error: 'A backup/restore is already running' });

  activeBackup.isRunning = true;
  activeBackup.logs = [];
  bBroadcast({ sync: true, isRunning: true, logs: [] });
  res.json({ message: 'Backup started' });

  try {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filename = `messagingagent_backup_${timestamp}.dump`;
    
    bLog(`Connecting to ${ip} to run pg_dump...`);
    // Ensure SSH keys exist
    await execPromise('mkdir -p /root/.ssh && cp -r /app/.ssh_host/* /root/.ssh/ && chown -R root:root /root/.ssh && chmod -R 600 /root/.ssh/*').catch(()=>{});

    const ssh = new NodeSSH();
    await ssh.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
    
    bLog(`Executing pg_dump in ma-postgres-0 pod...`);
    await ssh.execCommand(`sudo kubectl exec ma-postgres-0 -- pg_dump -U msgagent -d messagingagent -F c > /tmp/${filename}`);
    ssh.dispose();

    bLog(`SCP pulling backup from production node...`);
    await execPromise(`scp -o StrictHostKeyChecking=no ubuntu@${ip}:/tmp/${filename} /tmp/${filename}`);
    
    bLog(`Uploading ${filename} to Google Drive...`);
    await execPromise(`rclone copyto /tmp/${filename} gdrive:"${config.gdrivePath}/${filename}"`);
    
    bLog(`Cleaning up temporary files...`);
    await execPromise(`rm -f /tmp/${filename}`);
    const sshCleanup = new NodeSSH();
    await sshCleanup.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
    await sshCleanup.execCommand(`rm -f /tmp/${filename}`);
    sshCleanup.dispose();

    bLog(`Backup completed successfully!`);
    bBroadcast({ done: true, success: true });
  } catch (err) {
    bLog(`Backup failed: ${err.message}`, true);
    bBroadcast({ done: true, success: false });
  } finally {
    activeBackup.isRunning = false;
  }
});

app.post('/api/backup/restore', async (req, res) => {
  const { ip, filename } = req.body;
  if (!ip || !filename) return res.status(400).json({ error: 'Target IP and filename are required' });
  const config = setupRclone();
  if (!config) return res.status(400).json({ error: 'Backup not configured' });
  if (activeBackup.isRunning) return res.status(409).json({ error: 'A backup/restore is already running' });

  activeBackup.isRunning = true;
  activeBackup.logs = [];
  bBroadcast({ sync: true, isRunning: true, logs: [] });
  res.json({ message: 'Restore started' });

  try {
    bLog(`Downloading ${filename} from Google Drive...`);
    await execPromise(`rclone copyto gdrive:"${config.gdrivePath}/${filename}" /tmp/${filename}`);

    bLog(`SCP pushing backup to production node (${ip})...`);
    await execPromise('mkdir -p /root/.ssh && cp -r /app/.ssh_host/* /root/.ssh/ && chown -R root:root /root/.ssh && chmod -R 600 /root/.ssh/*').catch(()=>{});
    await execPromise(`scp -o StrictHostKeyChecking=no /tmp/${filename} ubuntu@${ip}:/tmp/${filename}`);

    bLog(`Connecting to ${ip} to run pg_restore...`);
    const ssh = new NodeSSH();
    await ssh.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
    
    bLog(`Executing pg_restore in ma-postgres-0 pod...`);
    // Drop existing connections and restore
    const dropConns = `sudo kubectl exec ma-postgres-0 -- psql -U msgagent -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'messagingagent' AND pid <> pg_backend_pid();"`;
    await ssh.execCommand(dropConns);
    
    // We use --clean --if-exists to drop and recreate objects
    const restoreCmd = `sudo kubectl exec ma-postgres-0 -i -- pg_restore -U msgagent -d messagingagent --clean --if-exists < /tmp/${filename}`;
    const result = await ssh.execCommand(restoreCmd);
    if (result.stderr && result.stderr.includes('FATAL')) {
       bLog(`Warning during restore: ${result.stderr}`, true);
    }
    
    bLog(`Cleaning up temporary files...`);
    await ssh.execCommand(`rm -f /tmp/${filename}`);
    ssh.dispose();
    await execPromise(`rm -f /tmp/${filename}`);

    bLog(`Restore completed successfully! System is now rolled back to ${filename}.`);
    bBroadcast({ done: true, success: true });
  } catch (err) {
    bLog(`Restore failed: ${err.message}`, true);
    bBroadcast({ done: true, success: false });
  } finally {
    activeBackup.isRunning = false;
  }
});

// --- Daily Auto-Backup Scheduler ---
let lastAutoBackupDate = null;

async function runAutoBackup() {
  const config = readBackupConfig();
  if (!config || !config.autoBackupEnabled || !config.serviceAccountJson) return;
  
  const now = new Date();
  const todayStr = now.toISOString().slice(0, 10); // YYYY-MM-DD
  const currentHour = now.getUTCHours();
  
  if (currentHour === (config.autoBackupHour ?? 3) && lastAutoBackupDate !== todayStr && !activeBackup.isRunning) {
    lastAutoBackupDate = todayStr;
    console.log(`[Auto-Backup] Triggering scheduled backup at ${now.toISOString()}`);
    
    setupRclone();
    activeBackup.isRunning = true;
    activeBackup.logs = [];
    bBroadcast({ sync: true, isRunning: true, logs: [] });
    
    const ip = '10.10.10.193'; // Production master
    try {
      const timestamp = now.toISOString().replace(/[:.]/g, '-');
      const filename = `messagingagent_autobackup_${timestamp}.dump`;
      
      bLog(`[Auto-Backup] Connecting to ${ip} to run pg_dump...`);
      await execPromise('mkdir -p /root/.ssh && cp -r /app/.ssh_host/* /root/.ssh/ && chown -R root:root /root/.ssh && chmod -R 600 /root/.ssh/*').catch(()=>{});
      
      const ssh = new NodeSSH();
      await ssh.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
      
      bLog(`[Auto-Backup] Executing pg_dump in ma-postgres-0 pod...`);
      await ssh.execCommand(`sudo kubectl exec ma-postgres-0 -- pg_dump -U msgagent -d messagingagent -F c > /tmp/${filename}`);
      ssh.dispose();
      
      bLog(`[Auto-Backup] SCP pulling backup from production node...`);
      await execPromise(`scp -o StrictHostKeyChecking=no ubuntu@${ip}:/tmp/${filename} /tmp/${filename}`);
      
      bLog(`[Auto-Backup] Uploading ${filename} to Google Drive...`);
      await execPromise(`rclone copyto /tmp/${filename} gdrive:"${config.gdrivePath}/${filename}"`);
      
      bLog(`[Auto-Backup] Cleaning up temporary files...`);
      await execPromise(`rm -f /tmp/${filename}`);
      const sshCleanup = new NodeSSH();
      await sshCleanup.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
      await sshCleanup.execCommand(`rm -f /tmp/${filename}`);
      sshCleanup.dispose();
      
      bLog(`[Auto-Backup] Backup completed successfully!`);
      bBroadcast({ done: true, success: true });
    } catch (err) {
      bLog(`[Auto-Backup] Backup failed: ${err.message}`, true);
      bBroadcast({ done: true, success: false });
    } finally {
      activeBackup.isRunning = false;
    }
  }
}

// Check every 60 seconds if it's time to auto-backup
setInterval(runAutoBackup, 60 * 1000);

const PORT = process.env.PORT || 8082;
app.listen(PORT, () => {
  console.log(`Deploy Agent listening on port ${PORT}`);
});
