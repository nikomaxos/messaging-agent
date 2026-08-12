const express = require('express');
const cors = require('cors');
const { spawn } = require('child_process');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

const PROXMOX_IP = '65.108.8.252';
const PROD_VM_IDS = [301, 302, 303]; // K3s Master and Workers
const PROD_IP = '10.10.10.193'; // K3s Master IP

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
  res.write(`data: ${JSON.stringify({ log: `Starting Kubernetes Production Deployment to ${PROD_IP}...` })}\n\n`);
  
  const versionStripped = require('/repo/admin-panel/package.json').version.replace(/\./g, '_');
  const snapshotName = `pre_deploy_v${versionStripped}_${Date.now()}`;
  
  const cmd = `
    echo "Creating Proxmox snapshots ${snapshotName} for K3s nodes..."
    for vmid in ${PROD_VM_IDS.join(' ')}; do
      ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm snapshot $vmid ${snapshotName} --description 'Auto-snapshot before K8s deployment'"
    done
    echo "Snapshots created. Triggering deployment on Kubernetes Master Node..."
    ssh -o StrictHostKeyChecking=no ubuntu@${PROD_IP} "if [ ! -d ~/messaging-agent ]; then git clone https://github.com/nikomaxos/messaging-agent.git ~/messaging-agent; fi && cd ~/messaging-agent && ./deploy-agent/deploy-k8s.sh"
  `;

  runCommand(cmd, res, (code) => {
    res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
    res.end();
  });
});

// Get Deploy Info
app.get('/api/deploy/info', (req, res) => {
  const { exec } = require('child_process');
  
  // 1. Get Prod version
  exec(`ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 ubuntu@10.10.10.193 "cat ~/messaging-agent/admin-panel/package.json 2>/dev/null || echo '{\\"version\\":\\"Unknown\\"}'"`, { timeout: 5000 }, (err, stdout) => {
    let prodVersion = "Unknown";
    try {
      prodVersion = "v" + JSON.parse(stdout).version;
    } catch(e) {}

    // 2. Get Snapshot version
    exec(`ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@${PROXMOX_IP} "qm listsnapshot ${PROD_VM_IDS[0]}"`, { timeout: 5000 }, (err2, stdout2) => {
      let rollbackTarget = "Unknown Version";
      const lines = (stdout2 || "").split('\n');
      for (let i = lines.length - 1; i >= 0; i--) {
        if (lines[i].includes('pre_deploy_')) {
          const vMatch = lines[i].match(/pre_deploy_v([0-9_]+)_(\d+)/);
          if (vMatch) {
            const d = new Date(parseInt(vMatch[2]));
            rollbackTarget = `v${vMatch[1]} (snapshot from ${d.toLocaleString()})`;
          } else {
            const tsMatch = lines[i].match(/pre_deploy_(\d+)/);
            if (tsMatch) {
              const d = new Date(parseInt(tsMatch[1]));
              rollbackTarget = `v1.0.0 (snapshot from ${d.toLocaleString()})`;
            } else {
              rollbackTarget = "previous snapshot";
            }
          }
          break;
        }
      }
      
      res.json({ productionVersion: prodVersion, rollbackTarget });
    });
  });
});

// Rollback Production
app.post('/api/rollback/production', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.write(`data: ${JSON.stringify({ log: 'Fetching available snapshots from K3s Master...' })}\n\n`);
  
  const getSnapshotsCmd = `ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm listsnapshot ${PROD_VM_IDS[0]}"`;
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

    res.write(`data: ${JSON.stringify({ log: 'Rolling back K3s VMs ' + PROD_VM_IDS.join(', ') + ' to snapshot ' + targetSnapshot + '...' })}\n\n`);
    const rollbackCmd = `
      for vmid in ${PROD_VM_IDS.join(' ')}; do
        ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm stop $vmid || true"
        ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm rollback $vmid ${targetSnapshot}"
        ssh -o StrictHostKeyChecking=no root@${PROXMOX_IP} "qm start $vmid"
      done
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
