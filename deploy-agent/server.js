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
const BACKUP_CONFIG_PATH = '/app/backup-data/backup-config.json';
const RCLONE_CONF_DIR = '/root/.config/rclone';
const RCLONE_CONF_PATH = `${RCLONE_CONF_DIR}/rclone.conf`;

// Setup rclone conf if backup config exists
function setupRclone() {
  if (fs.existsSync(BACKUP_CONFIG_PATH)) {
    try {
      const config = JSON.parse(fs.readFileSync(BACKUP_CONFIG_PATH, 'utf8'));
      fs.mkdirSync(RCLONE_CONF_DIR, { recursive: true });
      
      // Prefer OAuth2 token (user's personal account with storage quota)
      if (config.oauthToken) {
        let rcloneConf = `[gdrive]\ntype = drive\nscope = drive\ntoken = ${JSON.stringify(config.oauthToken)}\n`;
        if (config.oauthClientId) rcloneConf += `client_id = ${config.oauthClientId}\n`;
        if (config.oauthClientSecret) rcloneConf += `client_secret = ${config.oauthClientSecret}\n`;
        if (config.driveFolderId) rcloneConf += `root_folder_id = ${config.driveFolderId}\n`;
        fs.writeFileSync(RCLONE_CONF_PATH, rcloneConf);
        return config;
      }
      
      // Fallback to service account (only works for reading/listing, not uploading)
      if (config.serviceAccountJson) {
        const saPath = '/app/backup-data/gdrive-sa.json';
        fs.writeFileSync(saPath, config.serviceAccountJson);
        let rcloneConf = `[gdrive]\ntype = drive\nscope = drive\nservice_account_file = ${saPath}\n`;
        if (config.driveFolderId) rcloneConf += `root_folder_id = ${config.driveFolderId}\n`;
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
  if (config && (config.serviceAccountJson || config.oauthToken)) {
    res.json({ 
      configured: true, 
      gdrivePath: config.gdrivePath || '', 
      driveFolderId: config.driveFolderId || '',
      hasOAuth: !!config.oauthToken,
      hasSA: !!config.serviceAccountJson
    });
  } else {
    res.json({ configured: false });
  }
});

// --- Google OAuth2 Flow (for personal Gmail accounts) ---
app.post('/api/backup/auth-url', (req, res) => {
  const { clientId, clientSecret } = req.body;
  if (!clientId || !clientSecret) return res.status(400).json({ error: 'clientId and clientSecret required' });
  // Save client credentials
  const config = readBackupConfig() || {};
  config.oauthClientId = clientId;
  config.oauthClientSecret = clientSecret;
  writeBackupConfig(config);
  
  const redirectUri = 'urn:ietf:wg:oauth:2.0:oob';
  const scope = 'https://www.googleapis.com/auth/drive';
  const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(redirectUri)}&response_type=code&scope=${encodeURIComponent(scope)}&access_type=offline&prompt=consent`;
  res.json({ authUrl });
});

app.post('/api/backup/auth-callback', async (req, res) => {
  const { code } = req.body;
  if (!code) return res.status(400).json({ error: 'Authorization code is required' });
  const config = readBackupConfig();
  if (!config || !config.oauthClientId || !config.oauthClientSecret) {
    return res.status(400).json({ error: 'OAuth client credentials not configured' });
  }
  
  try {
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `code=${encodeURIComponent(code)}&client_id=${encodeURIComponent(config.oauthClientId)}&client_secret=${encodeURIComponent(config.oauthClientSecret)}&redirect_uri=${encodeURIComponent('urn:ietf:wg:oauth:2.0:oob')}&grant_type=authorization_code`
    });
    const tokenData = await tokenRes.json();
    if (tokenData.error) {
      return res.status(400).json({ error: `Token exchange failed: ${tokenData.error_description || tokenData.error}` });
    }
    
    // Store token in rclone format
    config.oauthToken = {
      access_token: tokenData.access_token,
      token_type: tokenData.token_type || 'Bearer',
      refresh_token: tokenData.refresh_token,
      expiry: new Date(Date.now() + (tokenData.expires_in || 3600) * 1000).toISOString()
    };
    writeBackupConfig(config);
    setupRclone();
    
    // Test the connection
    try {
      await execPromise('rclone lsd gdrive: 2>&1');
      res.json({ message: 'Google Drive authorized successfully! Ready to backup.' });
    } catch(err) {
      res.json({ message: 'Token saved, but connection test failed.', testError: err.stderr || err.message });
    }
  } catch(err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/backup/config', async (req, res) => {
  const { gdrivePath, serviceAccountJson, driveFolderId } = req.body;
  const existing = readBackupConfig() || {};
  
  // If SA JSON is provided, validate and update it. Otherwise keep existing.
  let saJson = existing.serviceAccountJson || '';
  if (serviceAccountJson) {
    try {
      JSON.parse(serviceAccountJson);
      saJson = serviceAccountJson;
    } catch(e) {
      return res.status(400).json({ error: 'serviceAccountJson must be a valid JSON string' });
    }
  }
  if (!saJson) {
    return res.status(400).json({ error: 'serviceAccountJson is required for initial setup' });
  }
  
  writeBackupConfig({ 
    ...existing, 
    gdrivePath: gdrivePath !== undefined ? gdrivePath : (existing.gdrivePath || ''), 
    serviceAccountJson: saJson, 
    driveFolderId: driveFolderId !== undefined ? driveFolderId : (existing.driveFolderId || '') 
  });
  setupRclone();

  // Test the connection immediately
  try {
    const { stdout, stderr } = await execPromise('rclone lsd gdrive: 2>&1');
    res.json({ message: 'Configuration saved and connection verified!', testOutput: stdout });
  } catch(err) {
    res.json({ message: 'Configuration saved, but connection test failed. Check your Service Account and Folder ID.', testError: err.stderr || err.message });
  }
});

// Dedicated endpoint for folder selection (doesn't require re-sending SA JSON)
app.post('/api/backup/select-folder', (req, res) => {
  const { folderId, folderPath } = req.body;
  if (!folderId) return res.status(400).json({ error: 'folderId is required' });
  const config = readBackupConfig();
  if (!config || (!config.serviceAccountJson && !config.oauthToken)) return res.status(400).json({ error: 'Backup not configured yet' });
  config.driveFolderId = folderId;
  config.gdrivePath = folderPath || '';
  writeBackupConfig(config);
  setupRclone();
  res.json({ message: `Folder selected: ${folderPath || folderId}` });
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

// --- Google Drive Folder Browser (uses Drive API directly via SA JWT) ---
async function getDriveAccessToken(saJsonStr) {
  const sa = JSON.parse(saJsonStr);
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/drive',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600
  })).toString('base64url');
  const sign = crypto.createSign('RSA-SHA256');
  sign.update(`${header}.${payload}`);
  const signature = sign.sign(sa.private_key, 'base64url');
  const jwt = `${header}.${payload}.${signature}`;

  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  });
  const tokenData = await tokenRes.json();
  if (!tokenData.access_token) throw new Error(tokenData.error_description || 'Failed to get access token');
  return tokenData.access_token;
}

app.get('/api/backup/browse', async (req, res) => {
  const config = readBackupConfig();
  if (!config) return res.status(400).json({ error: 'Not configured' });

  const folderId = req.query.folderId;
  try {
    let token;
    if (config.oauthToken) {
      // Use OAuth token - may need refresh
      if (new Date(config.oauthToken.expiry) < new Date()) {
        // Refresh the token
        const refreshRes = await fetch('https://oauth2.googleapis.com/token', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: `refresh_token=${encodeURIComponent(config.oauthToken.refresh_token)}&client_id=${encodeURIComponent(config.oauthClientId)}&client_secret=${encodeURIComponent(config.oauthClientSecret)}&grant_type=refresh_token`
        });
        const refreshData = await refreshRes.json();
        if (refreshData.access_token) {
          config.oauthToken.access_token = refreshData.access_token;
          config.oauthToken.expiry = new Date(Date.now() + (refreshData.expires_in || 3600) * 1000).toISOString();
          writeBackupConfig(config);
        }
      }
      token = config.oauthToken.access_token;
    } else if (config.serviceAccountJson) {
      token = await getDriveAccessToken(config.serviceAccountJson);
    } else {
      return res.status(400).json({ error: 'No authentication configured' });
    }

    let query = `mimeType='application/vnd.google-apps.folder' and trashed=false`;
    if (folderId) {
      query += ` and '${folderId}' in parents`;
    } else {
      query += ` and 'root' in parents`;
    }
    const url = `https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(query)}&fields=files(id,name)&orderBy=name&pageSize=100`;
    const driveRes = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    const data = await driveRes.json();
    if (data.error) return res.status(data.error.code || 500).json({ error: data.error.message });
    res.json({ folders: data.files || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Helper: get rclone destination. When root_folder_id is set, gdrive: IS the target folder
function rcloneTarget(config) {
  if (config.driveFolderId) return 'gdrive:';
  return `gdrive:"${config.gdrivePath}"`;
}

app.get('/api/backup/list', async (req, res) => {
  const config = setupRclone();
  if (!config) return res.status(400).json({ error: 'Backup not configured' });
  
  try {
    const { stdout } = await execPromise(`rclone lsjson ${rcloneTarget(config)} 2>/dev/null`);
    const files = JSON.parse(stdout || '[]').filter(f => !f.IsDir && f.Name.endsWith('.dump'));
    files.sort((a, b) => new Date(b.ModTime).getTime() - new Date(a.ModTime).getTime());
    res.json({ files });
  } catch (err) {
    res.status(500).json({ error: 'Failed to list backups from Google Drive. Verify Service Account and Folder.' });
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

// --- Retention Policy: 7 daily + 4 weekly + monthly forever ---
async function pruneOldBackups() {
  const config = setupRclone();
  if (!config) return;
  
  try {
    const { stdout } = await execPromise(`rclone lsjson ${rcloneTarget(config)} 2>/dev/null`);
    const allFiles = JSON.parse(stdout || '[]').filter(f => !f.IsDir && f.Name.endsWith('.dump'));
    if (allFiles.length <= 1) return; // Nothing to prune
    
    // Sort newest first
    allFiles.sort((a, b) => new Date(b.ModTime).getTime() - new Date(a.ModTime).getTime());
    
    const now = new Date();
    const DAY_MS = 86400000;
    const keepers = new Set();
    const weeklyKept = {};  // "YYYY-WW" -> newest file
    const monthlyKept = {}; // "YYYY-MM" -> newest file
    
    for (const file of allFiles) {
      const fileDate = new Date(file.ModTime);
      const ageDays = (now.getTime() - fileDate.getTime()) / DAY_MS;
      
      if (ageDays <= 7) {
        // Keep ALL backups from the last 7 days
        keepers.add(file.Name);
      } else if (ageDays <= 30) {
        // Keep one per week (the newest in that week)
        const weekStart = new Date(fileDate);
        weekStart.setDate(weekStart.getDate() - weekStart.getDay());
        const weekKey = weekStart.toISOString().slice(0, 10);
        if (!weeklyKept[weekKey]) {
          weeklyKept[weekKey] = file.Name;
          keepers.add(file.Name);
        }
      } else {
        // Keep one per month (the newest in that month) — forever
        const monthKey = fileDate.toISOString().slice(0, 7); // YYYY-MM
        if (!monthlyKept[monthKey]) {
          monthlyKept[monthKey] = file.Name;
          keepers.add(file.Name);
        }
      }
    }
    
    const toDelete = allFiles.filter(f => !keepers.has(f.Name));
    if (toDelete.length > 0) {
      bLog(`[Retention] Pruning ${toDelete.length} old backup(s)...`);
      for (const file of toDelete) {
        bLog(`[Retention] Deleting ${file.Name}`);
        await execPromise(`rclone deletefile ${rcloneTarget(config)}${file.Name}`).catch(() => {});
      }
      bLog(`[Retention] Cleanup complete. ${keepers.size} backup(s) retained.`);
    } else {
      bLog(`[Retention] All ${allFiles.length} backup(s) within retention policy.`);
    }
  } catch (err) {
    bLog(`[Retention] Warning: cleanup failed: ${err.message}`, true);
  }
}

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
    await execPromise(`rclone copyto /tmp/${filename} ${rcloneTarget(config)}${filename}`);
    
    bLog(`Cleaning up temporary files...`);
    await execPromise(`rm -f /tmp/${filename}`);
    const sshCleanup = new NodeSSH();
    await sshCleanup.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
    await sshCleanup.execCommand(`rm -f /tmp/${filename}`);
    sshCleanup.dispose();

    bLog(`Backup completed successfully!`);
    await pruneOldBackups();
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
    await execPromise(`rclone copyto ${rcloneTarget(config)}${filename} /tmp/${filename}`);

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
      await execPromise(`rclone copyto /tmp/${filename} ${rcloneTarget(config)}${filename}`);
      
      bLog(`[Auto-Backup] Cleaning up temporary files...`);
      await execPromise(`rm -f /tmp/${filename}`);
      const sshCleanup = new NodeSSH();
      await sshCleanup.connect({ host: ip, username: 'ubuntu', privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), readyTimeout: 10000 });
      await sshCleanup.execCommand(`rm -f /tmp/${filename}`);
      sshCleanup.dispose();
      
      bLog(`[Auto-Backup] Backup completed successfully!`);
      await pruneOldBackups();
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
