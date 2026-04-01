import { useState, useEffect } from 'react'
import { getDeployConfig, updateDeployConfig, getDeployStatus, triggerDeploy } from '../api/client'
import { Settings, GitBranch, Clock, RefreshCw, Rocket, CheckCircle, XCircle, Loader2, Server } from 'lucide-react'

interface DeployEntry {
  timestamp: string
  commit: string
  pusher: string
  success: boolean
  duration: number
}

export default function DeployPage() {
  const [config, setConfig] = useState<any>({})
  const [status, setStatus] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [deploying, setDeploying] = useState(false)
  const [statusLoading, setStatusLoading] = useState(false)
  const [toast, setToast] = useState<{ msg: string; type: 'ok' | 'err' } | null>(null)

  const showToast = (msg: string, type: 'ok' | 'err' = 'ok') => {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 4000)
  }

  const loadConfig = async () => {
    try {
      const data = await getDeployConfig()
      setConfig(data)
    } catch (e: any) {
      console.error('Failed to load deploy config', e)
    }
  }

  const loadStatus = async () => {
    setStatusLoading(true)
    try {
      const data = await getDeployStatus()
      setStatus(data)
    } catch (e: any) {
      setStatus({ connected: false, error: 'Cannot reach deploy agent' })
    } finally {
      setStatusLoading(false)
    }
  }

  useEffect(() => {
    Promise.all([loadConfig(), loadStatus()]).finally(() => setLoading(false))
    const interval = setInterval(loadStatus, 15000)
    return () => clearInterval(interval)
  }, [])

  const handleSave = async () => {
    setSaving(true)
    try {
      const data = await updateDeployConfig(config)
      setConfig(data)
      showToast('Configuration saved')
    } catch (e: any) {
      showToast('Failed to save: ' + (e.response?.data?.message || e.message), 'err')
    } finally {
      setSaving(false)
    }
  }

  const handleTriggerDeploy = async () => {
    if (!confirm('Trigger a manual deploy now? This will pull latest code and restart services.')) return
    setDeploying(true)
    try {
      await triggerDeploy()
      showToast('Deploy triggered! Check status below.')
      setTimeout(loadStatus, 5000)
    } catch (e: any) {
      showToast('Failed to trigger deploy: ' + (e.response?.data?.error || e.message), 'err')
    } finally {
      setDeploying(false)
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="animate-spin text-brand-400" size={32} />
      </div>
    )
  }

  return (
    <div className="p-6 max-w-5xl mx-auto space-y-6">
      {/* Toast */}
      {toast && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded-lg shadow-lg text-sm font-medium border ${
          toast.type === 'ok'
            ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
            : 'bg-red-500/20 text-red-300 border-red-500/30'
        }`}>
          {toast.msg}
        </div>
      )}

      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white flex items-center gap-3">
            <Rocket size={28} className="text-brand-400" />
            Auto-Deploy
          </h1>
          <p className="text-slate-400 text-sm mt-1">
            Configure automatic deployment from GitHub pushes
          </p>
        </div>
        <button
          onClick={handleTriggerDeploy}
          disabled={deploying || !config.enabled}
          className="flex items-center gap-2 px-4 py-2.5 bg-brand-600 hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-lg text-sm font-medium transition"
        >
          {deploying ? <Loader2 size={16} className="animate-spin" /> : <Rocket size={16} />}
          {deploying ? 'Deploying...' : 'Deploy Now'}
        </button>
      </div>

      {/* Configuration Card */}
      <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e] p-6 space-y-5">
        <h2 className="text-lg font-semibold text-white flex items-center gap-2">
          <Settings size={20} className="text-slate-400" />
          Configuration
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          {/* Enable Switch */}
          <div className="flex items-center justify-between p-4 rounded-lg bg-white/[0.03] border border-white/[0.05]">
            <div>
              <div className="text-sm font-medium text-white">Auto-Deploy Enabled</div>
              <div className="text-xs text-slate-500 mt-0.5">Receive and process GitHub webhooks</div>
            </div>
            <button
              onClick={() => setConfig({ ...config, enabled: !config.enabled })}
              className={`relative w-12 h-6 rounded-full transition-colors ${
                config.enabled ? 'bg-brand-600' : 'bg-slate-700'
              }`}
            >
              <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${
                config.enabled ? 'translate-x-6' : ''
              }`} />
            </button>
          </div>

          {/* Auto Deploy on Push */}
          <div className="flex items-center justify-between p-4 rounded-lg bg-white/[0.03] border border-white/[0.05]">
            <div>
              <div className="text-sm font-medium text-white">Auto-Deploy on Push</div>
              <div className="text-xs text-slate-500 mt-0.5">Deploy automatically when code is pushed</div>
            </div>
            <button
              onClick={() => setConfig({ ...config, autoDeploy: !config.autoDeploy })}
              className={`relative w-12 h-6 rounded-full transition-colors ${
                config.autoDeploy ? 'bg-brand-600' : 'bg-slate-700'
              }`}
            >
              <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${
                config.autoDeploy ? 'translate-x-6' : ''
              }`} />
            </button>
          </div>

          {/* Branch */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-slate-300 flex items-center gap-1.5">
              <GitBranch size={14} />
              Deploy Branch
            </label>
            <input
              type="text"
              value={config.branch || ''}
              onChange={e => setConfig({ ...config, branch: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-[#0f0f1a] border border-white/[0.07] text-white text-sm focus:outline-none focus:border-brand-500"
              placeholder="main"
            />
          </div>

          {/* Cooldown */}
          <div className="space-y-1.5">
            <label className="text-sm font-medium text-slate-300 flex items-center gap-1.5">
              <Clock size={14} />
              Cooldown (seconds)
            </label>
            <input
              type="number"
              value={config.cooldown || 60}
              onChange={e => setConfig({ ...config, cooldown: parseInt(e.target.value) || 60 })}
              className="w-full px-3 py-2 rounded-lg bg-[#0f0f1a] border border-white/[0.07] text-white text-sm focus:outline-none focus:border-brand-500"
              min={10}
              max={600}
            />
          </div>

          {/* Services */}
          <div className="space-y-1.5 md:col-span-2">
            <label className="text-sm font-medium text-slate-300 flex items-center gap-1.5">
              <Server size={14} />
              Services to rebuild
            </label>
            <input
              type="text"
              value={config.services || ''}
              onChange={e => setConfig({ ...config, services: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-[#0f0f1a] border border-white/[0.07] text-white text-sm focus:outline-none focus:border-brand-500"
              placeholder="backend admin-panel"
            />
            <p className="text-xs text-slate-500">Space-separated Docker Compose service names</p>
          </div>

          {/* Webhook Secret */}
          <div className="space-y-1.5 md:col-span-2">
            <label className="text-sm font-medium text-slate-300">Webhook Secret</label>
            <input
              type="password"
              value={config.webhookSecret || ''}
              onChange={e => setConfig({ ...config, webhookSecret: e.target.value })}
              className="w-full px-3 py-2 rounded-lg bg-[#0f0f1a] border border-white/[0.07] text-white text-sm focus:outline-none focus:border-brand-500"
              placeholder="GitHub webhook HMAC secret"
            />
            <p className="text-xs text-slate-500">Must match the secret configured in your GitHub webhook settings</p>
          </div>
        </div>

        {/* Save */}
        <div className="flex items-center justify-between pt-3 border-t border-white/[0.05]">
          <div className="flex items-center gap-2 text-sm">
            {config.agentReachable
              ? <><CheckCircle size={14} className="text-emerald-400" /> <span className="text-emerald-400">Deploy agent connected</span></>
              : <><XCircle size={14} className="text-slate-500" /> <span className="text-slate-500">Deploy agent not running</span></>
            }
          </div>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-500 disabled:opacity-50 text-white rounded-lg text-sm font-medium transition"
          >
            {saving ? 'Saving...' : 'Save Configuration'}
          </button>
        </div>
      </div>

      {/* Deploy Agent Status */}
      <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e] p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white flex items-center gap-2">
            <Server size={20} className="text-cyan-400" />
            Deploy Agent Status
          </h2>
          <button
            onClick={loadStatus}
            disabled={statusLoading}
            className="text-slate-400 hover:text-white transition p-1.5 rounded hover:bg-white/[0.05]"
          >
            <RefreshCw size={16} className={statusLoading ? 'animate-spin' : ''} />
          </button>
        </div>

        {status?.connected ? (
          <div className="space-y-3">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
                <div className="text-xs text-slate-500">Status</div>
                <div className="text-sm font-medium text-emerald-400 mt-0.5">Connected</div>
              </div>
              <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
                <div className="text-xs text-slate-500">Branch</div>
                <div className="text-sm font-medium text-white mt-0.5">{status.branch}</div>
              </div>
              <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
                <div className="text-xs text-slate-500">Total Deploys</div>
                <div className="text-sm font-medium text-white mt-0.5">{status.deployCount}</div>
              </div>
              <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
                <div className="text-xs text-slate-500">Last Deploy</div>
                <div className="text-sm font-medium text-white mt-0.5">
                  {status.lastDeploy?.timestamp || 'Never'}
                </div>
              </div>
            </div>

            {/* Recent Deploys Table */}
            {status.recentDeploys && status.recentDeploys.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-xs text-slate-500 uppercase border-b border-white/[0.05]">
                      <th className="text-left py-2 px-3">Time</th>
                      <th className="text-left py-2 px-3">Commit</th>
                      <th className="text-left py-2 px-3">Pusher</th>
                      <th className="text-left py-2 px-3">Duration</th>
                      <th className="text-left py-2 px-3">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(status.recentDeploys as DeployEntry[]).slice().reverse().map((d, i) => (
                      <tr key={i} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                        <td className="py-2 px-3 text-slate-300">{d.timestamp}</td>
                        <td className="py-2 px-3 font-mono text-xs text-brand-400">{d.commit}</td>
                        <td className="py-2 px-3 text-slate-300">{d.pusher}</td>
                        <td className="py-2 px-3 text-slate-400">{d.duration}s</td>
                        <td className="py-2 px-3">
                          {d.success
                            ? <span className="inline-flex items-center gap-1 text-emerald-400"><CheckCircle size={12} /> Success</span>
                            : <span className="inline-flex items-center gap-1 text-red-400"><XCircle size={12} /> Failed</span>
                          }
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        ) : (
          <div className="p-6 text-center rounded-lg bg-white/[0.02] border border-white/[0.05]">
            <Server size={40} className="text-slate-600 mx-auto mb-3" />
            <p className="text-slate-400 text-sm">Deploy agent is not running</p>
            <p className="text-slate-500 text-xs mt-1">
              {status?.error || 'Start with: docker compose --profile production up -d'}
            </p>
          </div>
        )}
      </div>

      {/* Setup Instructions */}
      <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e] p-6 space-y-3">
        <h2 className="text-lg font-semibold text-white">Quick Setup Guide</h2>
        <div className="text-sm text-slate-400 space-y-2">
          <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
            <div className="font-medium text-white mb-1">1. On the remote server</div>
            <code className="text-xs text-brand-300 bg-black/30 px-2 py-1 rounded">
              curl -sL https://raw.githubusercontent.com/nikomaxos/messaging-agent/main/deploy-agent/setup-remote.sh | bash
            </code>
          </div>
          <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
            <div className="font-medium text-white mb-1">2. On GitHub</div>
            <p>Go to <span className="text-brand-300">Settings → Webhooks → Add webhook</span></p>
            <p>URL: <code className="text-xs text-brand-300 bg-black/30 px-1 rounded">http://YOUR_SERVER:9000/webhook</code></p>
            <p>Content type: <code className="text-xs text-brand-300 bg-black/30 px-1 rounded">application/json</code></p>
            <p>Events: Just <code className="text-xs text-brand-300 bg-black/30 px-1 rounded">push</code></p>
          </div>
          <div className="p-3 rounded-lg bg-white/[0.03] border border-white/[0.05]">
            <div className="font-medium text-white mb-1">3. Push to main</div>
            <p>After committing locally, push to the configured branch — all remote servers auto-deploy.</p>
          </div>
        </div>
      </div>
    </div>
  )
}
