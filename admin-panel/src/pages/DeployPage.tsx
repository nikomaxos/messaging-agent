import { useState, useRef, useEffect } from 'react'
import { Rocket, Server, RefreshCw, Terminal, Undo2, PlayCircle } from 'lucide-react'
import packageJson from '../../package.json'

export default function DeployPage() {
  const [logs, setLogs] = useState<string[]>([])
  const [isDeploying, setIsDeploying] = useState(false)
  const [activeEnv, setActiveEnv] = useState<'production' | 'rollback_prod' | null>(null)
  const [prodVersion, setProdVersion] = useState<string>('Loading...')
  const [rollbackTarget, setRollbackTarget] = useState<string>('Unknown Version')
  
  const isProductionEnv = typeof window !== 'undefined' && window.location.hostname === 'messaging-agent.globalnetservices.net'

  const logsEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  useEffect(() => {
    fetch('/api/deploy/info')
      .then(res => res.json())
      .then(data => {
        setProdVersion(data.productionVersion || 'Unknown')
        setRollbackTarget(data.rollbackTarget || 'Unknown Version')
      })
      .catch(err => {
        setProdVersion('Error Fetching')
        setRollbackTarget('Unknown Version')
      })
  }, [])

  const triggerDeploy = async (env: 'production' | 'rollback_prod') => {
    if (isDeploying) return
    const isRollback = env === 'rollback_prod'
    const endpoint = `/api/${isRollback ? 'rollback/production' : 'deploy/production'}`
    
    if (!confirm(`Are you sure you want to ${isRollback ? 'ROLLBACK' : 'DEPLOY'} messaging-agent to PRODUCTION?`)) return
    
    setLogs([`>>> Initiating ${isRollback ? 'Rollback' : 'Deployment'}...`])
    setIsDeploying(true)
    setActiveEnv(env)

    try {
      const response = await fetch(`${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({})
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
            Messaging Agent Deployments
          </h1>
          <p className="text-slate-400 text-sm mt-1">
            Manage Enterprise deployments for the Messaging Agent
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Controls Column */}
        <div className="space-y-6 lg:col-span-1">
          
          {!isProductionEnv && (
            /* Staging Info Card (DevBox) */
            <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden">
              <h2 className="text-xl font-semibold text-white flex items-center gap-2 mb-2">
                <Server size={20} className="text-emerald-400" />
                Staging (DevBox)
                <span className="ml-auto text-sm font-normal text-emerald-400/80 bg-emerald-400/10 px-2 py-0.5 rounded">v{packageJson.version}</span>
              </h2>
              <p className="text-sm text-slate-400 mb-2">
                This environment acts as the primary Staging and Development server. 
                Code is tested locally here before being pushed to Production.
              </p>
              <p className="text-xs text-emerald-400/80 font-mono">
                IP: 10.10.10.96 (Local)
              </p>
            </div>
          )}

          {/* Production Card */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden group hover:border-brand-500/50 transition-colors">
            <h2 className="text-xl font-semibold text-white flex items-center gap-2 mb-2">
              <Rocket size={20} className="text-brand-400" />
              Production
              <span className="ml-auto text-sm font-normal text-brand-400/80 bg-brand-400/10 px-2 py-0.5 rounded">{prodVersion}</span>
            </h2>
            <p className="text-xs text-brand-400/80 mb-6 font-mono">
              Ubuntu Server VM: 10.10.10.192
            </p>
            
            {!isProductionEnv && (
              <button
                onClick={() => triggerDeploy('production')}
                disabled={isDeploying}
                className="w-full flex items-center justify-center gap-2 py-3 bg-brand-500/20 hover:bg-brand-500/30 text-white border border-brand-500/50 rounded-lg font-medium transition disabled:opacity-50 disabled:cursor-not-allowed shadow-[0_0_15px_rgba(var(--brand-500),0.3)]"
              >
                {isDeploying && activeEnv === 'production' ? <RefreshCw className="animate-spin" size={18} /> : <PlayCircle size={18} />}
                Deploy to Production
              </button>
            )}

            <div className={!isProductionEnv ? "mt-4 pt-4 border-t border-white/[0.05]" : ""}>
              <button
                onClick={() => triggerDeploy('rollback_prod')}
                disabled={isDeploying}
                className="w-full flex items-center justify-center gap-2 py-2.5 bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 rounded-lg text-sm font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Undo2 size={16} />
                Rollback Production to {rollbackTarget}
              </button>
            </div>
          </div>
          
        </div>

        {/* Live Terminal Column */}
        <div className="lg:col-span-2 flex flex-col h-[600px] rounded-xl border border-white/[0.07] bg-[#0a0a0f] overflow-hidden shadow-2xl relative">
          <div className="flex items-center justify-between px-4 py-3 bg-white/[0.02] border-b border-white/[0.05]">
            <div className="flex items-center gap-2 text-slate-300 text-sm font-mono">
              <Terminal size={16} className="text-slate-500" />
              deployment_console.log [messaging-agent]
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
