import { useState, useRef, useEffect } from 'react'
import { Rocket, Server, RefreshCw, Terminal, Undo2, PlayCircle, Key, User, Network } from 'lucide-react'
import packageJson from '../../package.json'

export default function DeployPage() {
  const [logs, setLogs] = useState<string[]>([])
  const [isDeploying, setIsDeploying] = useState(false)
  const [activeEnv, setActiveEnv] = useState<'production' | 'rollback_prod' | null>(null)
  
  const [currentStep, setCurrentStep] = useState(0)
  const totalSteps = 7
  const [vmWarnings, setVmWarnings] = useState<string[]>([])
  
  const [targetIp, setTargetIp] = useState('10.10.10.193')
  
  const [prodVersion, setProdVersion] = useState<string>('Unknown')
  const [rollbackTarget, setRollbackTarget] = useState<string>('Unknown Version')
  const [isFetchingInfo, setIsFetchingInfo] = useState(false)

  const isProductionEnv = typeof window !== 'undefined' && window.location.hostname === 'messaging-agent.globalnetservices.net'

  const logsEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  const fetchDeployInfo = async () => {
    if (!targetIp) {
      alert("Please fill in Target IP to fetch info.")
      return
    }
    
    setIsFetchingInfo(true)
    try {
      const res = await fetch('/api/deploy/info', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ip: targetIp, username: 'ubuntu', password: '' })
      })
      const data = await res.json()
      if (data.error) throw new Error(data.error)
      setProdVersion(data.productionVersion || 'Unknown')
      setRollbackTarget(data.rollbackTarget || 'Unknown Version')
    } catch (err: any) {
      alert("Failed to fetch info: " + err.message)
      setProdVersion('Error Fetching')
      setRollbackTarget('Unknown Version')
    } finally {
      setIsFetchingInfo(false)
    }
  }

  const triggerDeploy = async (env: 'production' | 'rollback_prod') => {
    if (isDeploying) return
    if (!targetIp) {
      alert("Please fill in Target IP before deploying.")
      return
    }

    const isRollback = env === 'rollback_prod'
    if (!confirm(`Are you sure you want to ${isRollback ? 'ROLLBACK' : 'DEPLOY'} messaging-agent on ${targetIp}?`)) return
    
    setLogs([`>>> Initiating ${isRollback ? 'Rollback' : 'Deployment'} to ${targetIp}...`])
    setIsDeploying(true)
    setActiveEnv(env)
    setCurrentStep(0)
    setVmWarnings([])

    try {
      // Step 1: Initialize session to get token
      const initRes = await fetch('/api/deploy/init', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ip: targetIp, username: 'ubuntu', password: '' })
      })
      const initData = await initRes.json()
      if (initData.error) throw new Error(initData.error)

      // Step 2: Open EventSource with token
      const endpoint = `/api/${isRollback ? 'rollback/production' : 'deploy/production'}?token=${initData.token}`
      const eventSource = new EventSource(endpoint)

      eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.log) {
            setLogs(prev => [...prev, data.log])
            
            const stepMatch = data.log.match(/--- Step (\d+):/)
            if (stepMatch) {
              setCurrentStep(parseInt(stepMatch[1], 10))
            }
            
            const vmMatch = data.log.match(/\[VM_UPDATE_NEEDED\] (.*)/)
            if (vmMatch) {
              setVmWarnings(prev => [...prev, vmMatch[1]])
            }
          }
          if (data.done) {
            setLogs(prev => [...prev, `>>> Process completed with code ${data.code}`])
            if (data.code === 0 && !isRollback) {
              setCurrentStep(totalSteps)
            }
            setIsDeploying(false)
            setActiveEnv(null)
            eventSource.close()
          }
        } catch (e) {}
      }

      eventSource.onerror = () => {
        setLogs(prev => [...prev, `>>> Connection lost to deployment stream.`])
        setIsDeploying(false)
        setActiveEnv(null)
        eventSource.close()
      }
      
    } catch (e: any) {
      setLogs(prev => [...prev, `>>> Error: ${e.message}`])
      setIsDeploying(false)
      setActiveEnv(null)
    }
  }

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold text-white flex items-center gap-3 tracking-tight">
            <Rocket size={32} className="text-brand-400" />
            Messaging Agent Deployments
            <span className="ml-4 text-xs font-normal text-emerald-400 bg-emerald-400/10 px-3 py-1 rounded-full border border-emerald-400/20 shadow-sm whitespace-nowrap">
              Staging Version: {packageJson.version}
            </span>
          </h1>
          <p className="text-slate-400 text-sm mt-1">
            Dynamic SSH Deployments to Kubernetes Clusters
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Controls Column */}
        <div className="space-y-6 lg:col-span-1">
          
          {/* Target Configuration */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden">
            <h2 className="text-lg font-semibold text-white flex items-center gap-2 mb-4">
              <Server size={18} className="text-emerald-400" />
              Target Configuration
            </h2>
            
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1 flex items-center gap-1"><Network size={12}/> Target IP</label>
                <input 
                  type="text" 
                  value={targetIp} 
                  onChange={e => setTargetIp(e.target.value)}
                  className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none transition"
                />
              </div>
              
              <button
                onClick={fetchDeployInfo}
                disabled={isFetchingInfo}
                className="w-full mt-2 flex items-center justify-center gap-2 py-2 bg-slate-800 hover:bg-slate-700 text-white border border-white/10 rounded-lg text-sm transition disabled:opacity-50"
              >
                {isFetchingInfo ? <RefreshCw className="animate-spin" size={14} /> : <RefreshCw size={14} />}
                Fetch Target Info
              </button>
            </div>
          </div>

          {/* Deployment Actions */}
          <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden group hover:border-brand-500/50 transition-colors">
            <h2 className="text-xl font-semibold text-white flex items-center gap-2 mb-2">
              <Rocket size={20} className="text-brand-400" />
              Production
              <span className="ml-auto text-sm font-normal text-brand-400/80 bg-brand-400/10 px-2 py-0.5 rounded">{prodVersion}</span>
            </h2>
            <p className="text-xs text-brand-400/80 mb-6 font-mono">
              Target: {targetIp}
            </p>
            
            {!isProductionEnv && (
              <button
                onClick={() => triggerDeploy('production')}
                disabled={isDeploying}
                className="w-full flex items-center justify-center gap-2 py-3 bg-brand-500/20 hover:bg-brand-500/30 text-white border border-brand-500/50 rounded-lg font-medium transition disabled:opacity-50 disabled:cursor-not-allowed shadow-[0_0_15px_rgba(var(--brand-500),0.3)]"
              >
                {isDeploying && activeEnv === 'production' ? <RefreshCw className="animate-spin" size={18} /> : <PlayCircle size={18} />}
                Deploy to Target
              </button>
            )}

            <div className={!isProductionEnv ? "mt-4 pt-4 border-t border-white/[0.05]" : ""}>
              <button
                onClick={() => triggerDeploy('rollback_prod')}
                disabled={isDeploying}
                className="w-full flex items-center justify-center gap-2 py-2.5 bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 rounded-lg text-sm font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Undo2 size={16} />
                Rollback Target ({rollbackTarget})
              </button>
            </div>
          </div>
          
          {/* Progress Overview (Only show during or after deployment) */}
          {(currentStep > 0 || logs.length > 0) && (
            <div className="rounded-xl border border-white/[0.07] bg-[#1a1a2e]/80 backdrop-blur p-6 relative overflow-hidden">
              <h2 className="text-lg font-semibold text-white flex items-center gap-2 mb-4">
                <RefreshCw size={18} className={isDeploying ? "animate-spin text-brand-400" : "text-emerald-400"} />
                Deployment Progress
              </h2>
              
              <div className="mb-4">
                <div className="flex justify-between text-xs text-slate-400 mb-1">
                  <span>Step {Math.min(currentStep, totalSteps)} of {totalSteps}</span>
                  <span>{Math.round((Math.min(currentStep, totalSteps) / totalSteps) * 100)}%</span>
                </div>
                <div className="w-full bg-[#12121f] rounded-full h-2.5 border border-white/5 overflow-hidden">
                  <div 
                    className={`h-2.5 rounded-full transition-all duration-500 ease-out ${isDeploying ? 'bg-brand-500' : 'bg-emerald-500'}`}
                    style={{ width: `${(Math.min(currentStep, totalSteps) / totalSteps) * 100}%` }}
                  ></div>
                </div>
              </div>
              
              {!isDeploying && currentStep === totalSteps && (
                <div className="mt-4 p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-sm flex flex-col gap-2">
                  <div className="font-bold">✅ Deployment Successful</div>
                  {vmWarnings.length > 0 ? (
                    <div className="mt-2 p-3 bg-amber-500/10 border border-amber-500/20 rounded text-amber-300 text-xs">
                      <div className="font-bold mb-1">⚠️ VM Updates Recommended:</div>
                      <ul className="list-disc pl-4 space-y-1">
                        {vmWarnings.map((w, idx) => <li key={idx}>{w}</li>)}
                      </ul>
                    </div>
                  ) : (
                    <div className="text-xs text-emerald-400/80">All nodes are up to date.</div>
                  )}
                </div>
              )}
            </div>
          )}
          
        </div>

        {/* Live Terminal Column */}
        <div className="lg:col-span-2 flex flex-col h-[700px] rounded-xl border border-white/[0.07] bg-[#0a0a0f] overflow-hidden shadow-2xl relative">
          <div className="flex items-center justify-between px-4 py-3 bg-white/[0.02] border-b border-white/[0.05]">
            <div className="flex items-center gap-2 text-slate-300 text-sm font-mono">
              <Terminal size={16} className="text-slate-500" />
              deployment_console.log [ssh-agent]
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
                <p>Provide credentials and trigger a deployment...</p>
              </div>
            ) : (
              logs.map((log, i) => (
                <div key={i} className="whitespace-pre-wrap break-words">
                  {log.includes('Error') || log.includes('error') ? (
                    <span className="text-red-400">{log}</span>
                  ) : log.startsWith('>>>') ? (
                    <span className="text-brand-400 font-bold">{log}</span>
                  ) : log.startsWith('> ssh') || log.startsWith('> Executing SSH') ? (
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
