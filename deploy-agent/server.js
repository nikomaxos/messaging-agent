const express = require('express');
const cors = require('cors');
const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

const PROXMOX_IP = '65.108.8.252';
const REPO_PATH = '/opt/messaging-agent'; // Adjust per app in the future
const APPS_FILE = path.join(__dirname, 'apps.json');
const DEFAULT_TEMPLATE_VM_ID = 9000; // The Proxmox Template to clone for new Apps

// Load or initialize Apps Registry
function getAppsRegistry() {
  if (!fs.existsSync(APPS_FILE)) {
    const initial = {
      "messaging-agent": { prodVmId: 100, stagingVmId: 101 }
    };
    fs.writeFileSync(APPS_FILE, JSON.stringify(initial, null, 2));
    return initial;
  }
  return JSON.parse(fs.readFileSync(APPS_FILE, 'utf-8'));
}

function saveAppsRegistry(data) {
  fs.writeFileSync(APPS_FILE, JSON.stringify(data, null, 2));
}

// Helper to spawn SSH commands to Proxmox and stream back to the SSE response
function runProxmoxCommand(cmd, res, onExit) {
  const sshCmd = `ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "${cmd.replace(/"/g, '\\"')}"`;
  res.write(`data: ${JSON.stringify({ log: `> Executing Remote Task...` })}\n\n`);

  const child = spawn('bash', ['-c', sshCmd]);

  child.stdout.on('data', (data) => {
    res.write(`data: ${JSON.stringify({ log: data.toString().trim() })}\n\n`);
  });

  child.stderr.on('data', (data) => {
    res.write(`data: ${JSON.stringify({ log: data.toString().trim(), error: true })}\n\n`);
  });

  child.on('close', (code) => {
    res.write(`data: ${JSON.stringify({ log: `Command exited with code ${code}` })}\n\n`);
    if (onExit) onExit(code);
  });
}

// Get All Apps Endpoint for UI
app.get('/api/apps', (req, res) => {
  res.json(getAppsRegistry());
});

// Provision New App
app.post('/api/provision', (req, res) => {
  const { app_name } = req.body;
  if (!app_name) return res.status(400).json({ error: "app_name is required" });
  
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');

  const apps = getAppsRegistry();
  if (apps[app_name]) {
    res.write(`data: ${JSON.stringify({ log: 'App already exists!', error: true })}\n\n`);
    return res.end();
  }

  res.write(`data: ${JSON.stringify({ log: `Starting Provisioning for ${app_name}...` })}\n\n`);

  // Get next 2 VM IDs
  const cmd = `
    PROD_ID=$(pvesh get /cluster/nextid)
    echo "Found Next ID for Prod: $PROD_ID"
    qm clone ${DEFAULT_TEMPLATE_VM_ID} $PROD_ID --name pve-${app_name}-prod --full 1
    
    STAGING_ID=$(pvesh get /cluster/nextid)
    echo "Found Next ID for Staging: $STAGING_ID"
    qm clone ${DEFAULT_TEMPLATE_VM_ID} $STAGING_ID --name pve-${app_name}-staging --full 1
    
    echo "PROD_ID=$PROD_ID,STAGING_ID=$STAGING_ID"
  `;

  runProxmoxCommand(cmd, res, (code) => {
    // In a real scenario, we'd parse the output to get the IDs securely.
    // For simplicity, let's fetch them directly via another exec.
    try {
      const pveshCmd = `ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "pvesh get /cluster/resources --type vm --output-format json"`;
      const output = execSync(pveshCmd).toString();
      const vms = JSON.parse(output);
      const prodVm = vms.find(v => v.name === `pve-${app_name}-prod`);
      const stagingVm = vms.find(v => v.name === `pve-${app_name}-staging`);
      
      if (prodVm && stagingVm) {
        apps[app_name] = { prodVmId: prodVm.vmid, stagingVmId: stagingVm.vmid };
        saveAppsRegistry(apps);
        res.write(`data: ${JSON.stringify({ log: `Provisioned successfully! Prod: ${prodVm.vmid}, Staging: ${stagingVm.vmid}` })}\n\n`);
      } else {
        res.write(`data: ${JSON.stringify({ log: `Failed to find newly provisioned VMs in Proxmox.`, error: true })}\n\n`);
      }
    } catch (e) {
      res.write(`data: ${JSON.stringify({ log: `Registry update failed: ${e.message}`, error: true })}\n\n`);
    }
    res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
    res.end();
  });
});

// Deploy to Production
app.post('/api/deploy/production', (req, res) => {
  const { app_name } = req.body || {};
  const apps = getAppsRegistry();
  const appConfig = apps[app_name || 'messaging-agent'];
  if (!appConfig) return res.status(404).send('App not found');

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: `Starting Production Deployment for ${app_name || 'messaging-agent'}...` })}\n\n`);
  
  const snapshotName = `pre_deploy_${Date.now()}`;
  const cmd = `
    echo "Creating snapshot ${snapshotName}..."
    qm snapshot ${appConfig.prodVmId} ${snapshotName} --description "Auto-snapshot before deployment"
    echo "Snapshot created. Triggering deployment inside VM..."
    qm guest exec ${appConfig.prodVmId} -- /bin/bash -c "cd ${REPO_PATH} && git pull origin main && ./deploy-agent/deploy.sh"
  `;

  runProxmoxCommand(cmd, res, (code) => {
    res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
    res.end();
  });
});

// Deploy to Staging (Clone from Prod)
app.post('/api/deploy/staging', (req, res) => {
  const { app_name } = req.body || {};
  const apps = getAppsRegistry();
  const appConfig = apps[app_name || 'messaging-agent'];
  if (!appConfig) return res.status(404).send('App not found');

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: `Starting Staging Deployment for ${app_name || 'messaging-agent'}...` })}\n\n`);
  
  const cmd = `
    echo "Checking if Staging VM (${appConfig.stagingVmId}) exists..."
    if qm status ${appConfig.stagingVmId} >/dev/null 2>&1; then
      echo "Stopping Staging VM..."
      qm stop ${appConfig.stagingVmId} || true
      sleep 5
      echo "Destroying old Staging VM..."
      qm destroy ${appConfig.stagingVmId} --purge 1 || true
    fi
    echo "Cloning Prod VM (${appConfig.prodVmId}) to Staging VM (${appConfig.stagingVmId})..."
    qm clone ${appConfig.prodVmId} ${appConfig.stagingVmId} --name pve-${app_name || 'staging'}-vm --full 1
    echo "Starting new Staging VM..."
    qm start ${appConfig.stagingVmId}
    echo "Staging VM is now booting! 100% parity with Production achieved."
  `;

  runProxmoxCommand(cmd, res, (code) => {
    res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
    res.end();
  });
});

// Rollback Production
app.post('/api/rollback/production', (req, res) => {
  const { app_name } = req.body || {};
  const apps = getAppsRegistry();
  const appConfig = apps[app_name || 'messaging-agent'];
  if (!appConfig) return res.status(404).send('App not found');

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: 'Fetching available snapshots...' })}\n\n`);
  
  const getSnapshotsCmd = `qm listsnapshot ${appConfig.prodVmId}`;
  const child = spawn('bash', ['-c', `ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "${getSnapshotsCmd}"`]);
  
  let output = '';
  child.stdout.on('data', data => output += data.toString());
  
  child.on('close', () => {
    const lines = output.split('\\n');
    let targetSnapshot = null;
    for (let i = lines.length - 1; i >= 0; i--) {
      if (lines[i].includes('pre_deploy_')) {
        targetSnapshot = lines[i].trim().split(' ')[0].replace('\`->', '').trim();
        break;
      }
    }

    if (!targetSnapshot) {
      res.write(`data: ${JSON.stringify({ log: 'No pre_deploy snapshot found!', error: true })}\n\n`);
      res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
      return res.end();
    }

    res.write(`data: ${JSON.stringify({ log: \`Rolling back VM ${appConfig.prodVmId} to snapshot \${targetSnapshot}...\` })}\n\n`);
    const rollbackCmd = `
      qm stop ${appConfig.prodVmId} || true
      qm rollback ${appConfig.prodVmId} ${targetSnapshot}
      qm start ${appConfig.prodVmId}
    `;

    runProxmoxCommand(rollbackCmd, res, (code) => {
      res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
      res.end();
    });
  });
});

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
  console.log(\`Deploy Agent listening on port \${PORT}\`);
});
