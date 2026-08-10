import { useState, useRef, useEffect } from 'react'
import { Rocket, Server, RefreshCw, Terminal, Undo2, PlayCircle, Plus, Box } from 'lucide-react'

export default function DeployPage() {
  const [logs, setLogs] = useState<string[]>([])
  const [isDeploying, setIsDeploying] = useState(false)
  const [activeEnv, setActiveEnv] = useState<'staging' | 'production' | 'rollback_prod' | 'provision' | null>(null)
  
  const [apps, setApps] = useState<Record<string, { prodVmId: number, stagingVmId: number }>>({})
  const [selectedApp, setSelectedApp] = useState<string>('messaging-agent')
  const [newAppName, setNewAppName] = useState('')
  const [showProvision, setShowProvision] = useState(false)

  const logsEndRef = useRef<HTMLDivElement>(null)

  const fetchApps = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/apps')
      const data = await res.json()
      setApps(data)
      if (!data[selectedApp] && Object.keys(data).length > 0) {
        setSelectedApp(Object.keys(data)[0])
      }
    } catch (e) {
      console.error("Failed to load apps registry", e)
    }
  }

  useEffect(() => {
    fetchApps()
  }, [])

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  const triggerDeploy = async (env: 'staging' | 'production' | 'rollback_prod' | 'provision') => {
    if (isDeploying) return
    const isProvision = env === 'provision'
    const isRollback = env === 'rollback_prod'
    const actualEnv = isRollback ? 'production' : env
    const endpoint = `/api/${isProvision ? 'provision' : (isRollback ? 'rollback/production' : `deploy/${env}`)}`
    
    if (isProvision) {
      if (!newAppName.trim()) return
      if (!confirm(`Provision new infrastructure for ${newAppName}? This will create 2 new VMs.`)) return
    } else {
      if (!confirm(`Are you sure you want to ${isRollback ? 'ROLLBACK' : 'DEPLOY'} ${selectedApp} to ${actualEnv.toUpperCase()}?`)) return
    }
    
    setLogs([`>>> Initiating ${isProvision ? 'Provisioning' : (isRollback ? 'Rollback' : 'Deployment')}...`])
    setIsDeploying(true)
    setActiveEnv(env)

    try {
      const response = await fetch(`http://localhost:8080${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ app_name: isProvision ? newAppName.trim() : selectedApp })
      })

      if (!response.body) throw new Error('No readable stream')

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let done = false

      while (!done) {
        const { value, done: readerDone } = await reader.read()
        done = readerDone
        if (value) {
          const chunk = decoder.decode(value)
          const lines = chunk.split('\n\n')
          lines.forEach(line => {
            if (line.startsWith('data: ')) {
              try {
                const data = JSON.parse(line.replace('data: ', ''))
                if (data.log) {
                  setLogs(prev => [...prev, data.log])
                }
                if (data.done) {
                  setLogs(prev => [...prev, `>>> Process completed with code ${data.code}`])
                  setIsDeploying(false)
                  setActiveEnv(null)
                  if (isProvision) {
                    setShowProvision(false)
                    setNewAppName('')
                    fetchApps() // Refresh registry
                  }
                }
              } catch (e) {}
            }
          })
        }
      }
    } catch (e: any) {
      setLogs(prev => [...prev, `>>> Error: ${e.message}`])
      setIsDeploying(false)
      setActiveEnv(null)
    }
  }

  return (
    <div className="p-6 max-w-6xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold text-white flex items-center gap-3 tracking-tight">
            <Rocket size={32} className="text-brand-400" />
            DevOps Deployments
          </h1>
          <p className="text-slate-400 text-sm mt-1">
            Manage multi-app Proxmox VM deployments for Staging and Production
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Controls Column */}
        <div className="space-y-6 lg:col-span-1">
          
          {/* Apps Registry Card */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden">
            <h2 className="text-lg font-semibold text-white flex items-center gap-2 mb-4">
              <Box size={20} className="text-indigo-400" />
              Application Registry
            </h2>
            
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 uppercase mb-2">Select Active App</label>
                <select
                  value={selectedApp}
                  onChange={(e) => setSelectedApp(e.target.value)}
                  className="w-full bg-[#0a0a0f] border border-white/[0.1] rounded-lg px-3 py-2 text-white focus:outline-none focus:border-indigo-500"
                >
                  {Object.keys(apps).map(app => (
                    <option key={app} value={app}>{app} (Prod: {apps[app]?.prodVmId})</option>
                  ))}
                </select>
              </div>

              {!showProvision ? (
                <button
                  onClick={() => setShowProvision(true)}
                  className="w-full flex items-center justify-center gap-2 py-2 bg-white/[0.03] hover:bg-white/[0.08] text-slate-300 border border-dashed border-white/[0.1] rounded-lg text-sm transition"
                >
                  <Plus size={16} /> Provision New App
                </button>
              ) : (
                <div className="p-4 bg-indigo-500/10 border border-indigo-500/20 rounded-lg space-y-3">
                  <input
                    type="text"
                    value={newAppName}
                    onChange={(e) => setNewAppName(e.target.value)}
                    placeholder="e.g. auth-service"
                    className="w-full bg-[#0a0a0f] border border-white/[0.1] rounded px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500"
                  />
                  <div className="flex gap-2">
                    <button
                      onClick={() => triggerDeploy('provision')}
                      disabled={isDeploying || !newAppName}
                      className="flex-1 bg-indigo-500 hover:bg-indigo-600 text-white py-2 rounded text-sm font-medium transition disabled:opacity-50"
                    >
                      {isDeploying && activeEnv === 'provision' ? <RefreshCw className="animate-spin inline mr-2" size={14}/> : null}
                      Provision VMs
                    </button>
                    <button
                      onClick={() => setShowProvision(false)}
                      disabled={isDeploying}
                      className="px-3 bg-white/[0.05] hover:bg-white/[0.1] text-white rounded text-sm transition"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Staging Card */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden group hover:border-emerald-500/50 transition-colors">
            <h2 className="text-xl font-semibold text-white flex items-center gap-2 mb-2">
              <Server size={20} className="text-emerald-400" />
              Staging
            </h2>
            <p className="text-xs text-emerald-400/80 mb-6 font-mono">
              Target VM: {apps[selectedApp]?.stagingVmId || 'N/A'}
            </p>
            <button
              onClick={() => triggerDeploy('staging')}
              disabled={isDeploying}
              className="w-full flex items-center justify-center gap-2 py-3 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 rounded-lg font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isDeploying && activeEnv === 'staging' ? <RefreshCw className="animate-spin" size={18} /> : <PlayCircle size={18} />}
              Clone Prod &rarr; Staging
            </button>
          </div>

          {/* Production Card */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden group hover:border-brand-500/50 transition-colors">
            <h2 className="text-xl font-semibold text-white flex items-center gap-2 mb-2">
              <Rocket size={20} className="text-brand-400" />
              Production
            </h2>
            <p className="text-xs text-brand-400/80 mb-6 font-mono">
              Target VM: {apps[selectedApp]?.prodVmId || 'N/A'}
            </p>
            <button
              onClick={() => triggerDeploy('production')}
              disabled={isDeploying}
              className="w-full flex items-center justify-center gap-2 py-3 bg-brand-500/20 hover:bg-brand-500/30 text-white border border-brand-500/50 rounded-lg font-medium transition disabled:opacity-50 disabled:cursor-not-allowed shadow-[0_0_15px_rgba(var(--brand-500),0.3)]"
            >
              {isDeploying && activeEnv === 'production' ? <RefreshCw className="animate-spin" size={18} /> : <PlayCircle size={18} />}
              Deploy to Production
            </button>

            <div className="mt-4 pt-4 border-t border-white/[0.05]">
              <button
                onClick={() => triggerDeploy('rollback_prod')}
                disabled={isDeploying}
                className="w-full flex items-center justify-center gap-2 py-2.5 bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 rounded-lg text-sm font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Undo2 size={16} />
                Rollback Production
              </button>
            </div>
          </div>
          
        </div>

        {/* Live Terminal Column */}
        <div className="lg:col-span-2 flex flex-col h-[600px] rounded-xl border border-white/[0.07] bg-[#0a0a0f] overflow-hidden shadow-2xl relative">
          <div className="flex items-center justify-between px-4 py-3 bg-white/[0.02] border-b border-white/[0.05]">
            <div className="flex items-center gap-2 text-slate-300 text-sm font-mono">
              <Terminal size={16} className="text-slate-500" />
              deployment_console.log [{selectedApp}]
            </div>
            {isDeploying && (
              <div className="flex items-center gap-2 text-xs font-medium text-brand-400 bg-brand-400/10 px-2 py-1 rounded animate-pulse">
                <span className="w-2 h-2 rounded-full bg-brand-400"></span>
                RUNNING
              </div>
            )}
          </div>
          
          <div className="flex-1 p-4 overflow-y-auto font-mono text-sm leading-relaxed text-slate-300 space-y-1">
            {logs.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-slate-600 space-y-3">
                <Terminal size={48} className="opacity-20" />
                <p>Waiting for deployment tasks...</p>
              </div>
            ) : (
              logs.map((log, i) => (
                <div key={i} className="whitespace-pre-wrap break-words">
                  {log.includes('Error') || log.includes('error') ? (
                    <span className="text-red-400">{log}</span>
                  ) : log.startsWith('>>>') ? (
                    <span className="text-brand-400 font-bold">{log}</span>
                  ) : log.startsWith('> ssh') ? (
                    <span className="text-emerald-400">{log}</span>
                  ) : (
                    log
                  )}
                </div>
              ))
            )}
            <div ref={logsEndRef} />
          </div>
        </div>
      </div>
    </div>
  )
}
