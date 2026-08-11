const express = require('express');
const cors = require('cors');
const { spawn } = require('child_process');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

const PROXMOX_IP = '65.108.8.252';
const PROD_VM_ID = 200;
const PROD_IP = '10.10.10.192';

// Helper to spawn commands and stream back to the SSE response
function runCommand(cmd, res, onExit) {
  res.write(`data: ${JSON.stringify({ log: `> Executing Task...` })}\n\n`);

  // Keep-alive heartbeat every 15 seconds to prevent Nginx/Caddy from dropping the SSE stream
  const keepAlive = setInterval(() => {
    res.write(':\n\n'); // SSE comment
  }, 15000);

  const child = spawn('bash', ['-c', cmd]);

  child.stdout.on('data', (data) => {
    res.write(`data: ${JSON.stringify({ log: data.toString().trim() })}\n\n`);
  });

  child.stderr.on('data', (data) => {
    res.write(`data: ${JSON.stringify({ log: data.toString().trim(), error: true })}\n\n`);
  });

  child.on('close', (code) => {
    clearInterval(keepAlive);
    res.write(`data: ${JSON.stringify({ log: `Command exited with code ${code}` })}\n\n`);
    if (onExit) onExit(code);
  });
}

// Deploy to Production
app.post('/api/deploy/production', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: `Starting Production Deployment to ${PROD_IP}...` })}\n\n`);
  
  const snapshotName = `pre_deploy_${Date.now()}`;
  // 1. SSH to Proxmox to snapshot VM 200
  // 2. SSH to Production VM (10.10.10.192) to run deploy script
  const cmd = `
    echo "Creating Proxmox snapshot ${snapshotName} for Rollback capability..."
    ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm snapshot ${PROD_VM_ID} ${snapshotName} --description 'Auto-snapshot before deployment'"
    echo "Snapshot created. Triggering deployment inside Production VM..."
    ssh -o StrictHostKeyChecking=no nick@${PROD_IP} "cd ~/messaging-agent && git pull origin main && REPO_DIR=~/messaging-agent ./deploy-agent/deploy.sh"
  `;

  runCommand(cmd, res, (code) => {
    res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
    res.end();
  });
});

// Rollback Production
app.post('/api/rollback/production', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: 'Fetching available snapshots...' })}\n\n`);
  
  const getSnapshotsCmd = `ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm listsnapshot ${PROD_VM_ID}"`;
  const child = spawn('bash', ['-c', getSnapshotsCmd]);
  
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

    res.write(`data: ${JSON.stringify({ log: 'Rolling back VM ' + PROD_VM_ID + ' to snapshot ' + targetSnapshot + '...' })}\n\n`);
    const rollbackCmd = `
      ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm stop ${PROD_VM_ID} || true"
      ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm rollback ${PROD_VM_ID} ${targetSnapshot}"
      ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm start ${PROD_VM_ID}"
    `;

    runCommand(rollbackCmd, res, (code) => {
      res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
      res.end();
    });
  });
});

const PORT = process.env.PORT || 8082;
app.listen(PORT, () => {
  console.log(`Deploy Agent listening on port ${PORT}`);
});
