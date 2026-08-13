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

// In-memory store for deployment sessions (to avoid passing passwords in GET EventSource URLs)
const deploySessions = new Map();

// Helper to execute SSH commands and stream back to the SSE response
async function runSshCommandStream(ssh, cmd, res, onExit) {
  res.write(`data: ${JSON.stringify({ log: `> Executing SSH Task...` })}\n\n`);

  const keepAlive = setInterval(() => {
    res.write(':\n\n'); // SSE comment
  }, 15000);

  try {
    await ssh.execCommand(cmd, {
      onStdout(chunk) {
        res.write(`data: ${JSON.stringify({ log: chunk.toString('utf8').trim() })}\n\n`);
      },
      onStderr(chunk) {
        res.write(`data: ${JSON.stringify({ log: chunk.toString('utf8').trim(), error: true })}\n\n`);
      }
    });
    clearInterval(keepAlive);
    res.write(`data: ${JSON.stringify({ log: `Command completed successfully.` })}\n\n`);
    if (onExit) onExit(0);
  } catch (err) {
    clearInterval(keepAlive);
    res.write(`data: ${JSON.stringify({ log: `Command failed: ${err.message}`, error: true })}\n\n`);
    if (onExit) onExit(1);
  }
}

// 1. Init Deployment Session
app.post('/api/deploy/init', (req, res) => {
  const { ip, username, password } = req.body;
  if (!ip || !username) {
    return res.status(400).json({ error: 'IP and username are required' });
  }

  const token = crypto.randomUUID();
  deploySessions.set(token, { ip, username, password, createdAt: Date.now() });

  // Cleanup old sessions after 5 minutes
  setTimeout(() => deploySessions.delete(token), 300000);

  res.json({ token });
});

// 2. Execute Deployment (SSE)
app.get('/api/deploy/production', async (req, res) => {
  const token = req.query.token;
  const session = deploySessions.get(token);

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');

  if (!session) {
    res.write(`data: ${JSON.stringify({ log: 'Invalid or expired deployment token.', error: true })}\n\n`);
    res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
    return res.end();
  }

  // 1. Auto-Push Local Changes First
  res.write(`data: ${JSON.stringify({ log: 'Committing and pushing local changes to GitHub...' })}\n\n`);
  try {
    const gitCmds = `
      git config --global --add safe.directory /repo &&
      git config --global user.email "deploy-agent@messaging-agent.local" &&
      git config --global user.name "Deploy Agent" &&
      git add . &&
      (git commit -m "Auto-Deploy: Pushed from Admin Panel" || true) &&
      git push origin main
    `;
    await execPromise(gitCmds, { cwd: '/repo' });
    res.write(`data: ${JSON.stringify({ log: 'Successfully pushed local changes.' })}\n\n`);
  } catch (err) {
    res.write(`data: ${JSON.stringify({ log: `Failed to push local changes: ${err.message}`, error: true })}\n\n`);
    res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
    return res.end();
  }

  // 2. SSH into target and Deploy
  res.write(`data: ${JSON.stringify({ log: `Connecting to ${session.ip} via SSH...` })}\n\n`);
  
  const ssh = new NodeSSH();
  try {
    await ssh.connect({
      host: session.ip,
      username: session.username,
      password: session.password || undefined,
      privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'),
      tryKeyboard: true,
      readyTimeout: 10000
    });
    res.write(`data: ${JSON.stringify({ log: `SSH Connected successfully.` })}\n\n`);

    const cmd = `
      echo "Triggering deployment on Target Node..."
      if [ ! -d ~/messaging-agent ]; then git clone https://github.com/nikomaxos/messaging-agent.git ~/messaging-agent; fi 
      cd ~/messaging-agent 
      ./deploy-agent/deploy-k8s.sh
    `;

    await runSshCommandStream(ssh, cmd, res, (code) => {
      ssh.dispose();
      res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
      res.end();
    });

  } catch (err) {
    res.write(`data: ${JSON.stringify({ log: `SSH Connection Failed: ${err.message}`, error: true })}\n\n`);
    res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
    res.end();
  }
});

// 3. Get Deploy Info (Changed to POST to accept credentials safely)
app.post('/api/deploy/info', async (req, res) => {
  const { ip, username, password } = req.body;
  if (!ip || !username) {
    return res.status(400).json({ error: 'IP and username are required' });
  }

  const ssh = new NodeSSH();
  try {
    await ssh.connect({ host: ip, username, password: password || undefined, privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'), tryKeyboard: true, readyTimeout: 5000 });
    
    // Get Prod version
    const result = await ssh.execCommand("cat ~/messaging-agent/admin-panel/package.json 2>/dev/null || echo '{\"version\":\"Unknown\"}'");
    let prodVersion = "Unknown";
    try {
      prodVersion = "v" + JSON.parse(result.stdout).version;
    } catch(e) {}

    ssh.dispose();
    res.json({ productionVersion: prodVersion, rollbackTarget: "Kubernetes Native Rollback (Previous ReplicaSet)" });

  } catch (err) {
    res.status(500).json({ error: `SSH Connection Failed: ${err.message}` });
  }
});

// 4. Rollback Production (SSE)
app.get('/api/rollback/production', async (req, res) => {
  const token = req.query.token;
  const session = deploySessions.get(token);

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');

  if (!session) {
    res.write(`data: ${JSON.stringify({ log: 'Invalid or expired rollback token.', error: true })}\n\n`);
    res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
    return res.end();
  }

  res.write(`data: ${JSON.stringify({ log: `Connecting to ${session.ip} via SSH for Rollback...` })}\n\n`);
  
  const ssh = new NodeSSH();
  try {
    await ssh.connect({
      host: session.ip,
      username: session.username,
      password: session.password || undefined,
      privateKey: fs.readFileSync('/root/.ssh/id_rsa', 'utf8'),
      tryKeyboard: true,
      readyTimeout: 10000
    });
    
    res.write(`data: ${JSON.stringify({ log: 'Rolling back K3s deployments natively via kubectl...' })}\n\n`);
    const rollbackCmd = `kubectl rollout undo deployment --all`;
    
    await runSshCommandStream(ssh, rollbackCmd, res, (code) => {
      ssh.dispose();
      res.write(`data: ${JSON.stringify({ done: true, code })}\n\n`);
      res.end();
    });

  } catch (err) {
    res.write(`data: ${JSON.stringify({ log: `SSH Connection Failed: ${err.message}`, error: true })}\n\n`);
    res.write(`data: ${JSON.stringify({ done: true, code: 1 })}\n\n`);
    res.end();
  }
});

const PORT = process.env.PORT || 8082;
app.listen(PORT, () => {
  console.log(`Deploy Agent listening on port ${PORT}`);
});
