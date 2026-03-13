import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getServerConfig, updateServerConfig, restartServer } from '../api/client'
import { Server, Activity, ArrowRightLeft, Clock, Save, RefreshCw } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'

function LiveUptime({ since }: { since: string }) {
  const [uptime, setUptime] = useState('')
  useEffect(() => {
    const calc = () => setUptime(formatDistanceToNow(new Date(since)))
    calc()
    const timer = setInterval(calc, 60000)
    return () => clearInterval(timer)
  }, [since])
  return <span>{uptime}</span>
}

export default function SmppServerPage() {
  const qc = useQueryClient()
  const { data: config, isLoading } = useQuery({ 
    queryKey: ['smppServerConfig'], 
    queryFn: getServerConfig 
  })

  const [formData, setFormData] = useState<any>(null)
  
  useEffect(() => {
    if (config && !formData) setFormData(config)
  }, [config])

  const updateMut = useMutation({
    mutationFn: updateServerConfig,
    onSuccess: (data: any) => {
      qc.setQueryData(['smppServerConfig'], data)
      setFormData(data)
    }
  })

  const restartMut = useMutation({
    mutationFn: restartServer,
    onSuccess: (data: any) => {
      qc.setQueryData(['smppServerConfig'], data)
    }
  })

  const handleSave = () => {
    if (formData) updateMut.mutate(formData)
  }

  return (
    <div className="p-8">
      <div className="flex justify-between items-start mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">SMPP Server Configuration</h1>
          <p className="text-slate-400 text-sm">Global properties for the embedded SMPP Server</p>
        </div>
        <button
          onClick={() => restartMut.mutate()}
          disabled={restartMut.isPending}
          className="flex items-center gap-2 bg-slate-800 hover:bg-slate-700 text-white px-4 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
        >
          <RefreshCw size={16} className={restartMut.isPending ? "animate-spin" : ""} />
          Restart SMPP
        </button>
      </div>

      {isLoading || !formData ? (
        <div className="text-slate-400 animate-pulse">Loading server settings...</div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 max-w-5xl">
          <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-6 shadow-sm">
            <div className="flex items-center gap-3 mb-6">
              <div className="p-2.5 bg-brand-500/10 text-brand-400 rounded-lg">
                <Server size={20} />
              </div>
              <h2 className="text-lg font-semibold text-white">Network & Bind Profile</h2>
            </div>
            <dl className="space-y-4">
              <div>
                <dt className="text-sm font-medium text-slate-400 mb-1">Bind Host (IP)</dt>
                <input 
                  type="text" 
                  value={formData.host} 
                  onChange={(e: any) => setFormData({ ...formData, host: e.target.value })}
                  className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white font-mono text-sm"
                />
              </div>
              <div>
                <dt className="text-sm font-medium text-slate-400 mb-1">Bind Port</dt>
                <input 
                  type="number" 
                  value={formData.port} 
                  onChange={(e: any) => setFormData({ ...formData, port: parseInt(e.target.value) || 2775 })}
                  className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white font-mono text-sm"
                />
              </div>
              <div className="pt-4 border-t border-white/5">
                <dt className="text-sm font-medium text-slate-400">Operating Status</dt>
                <dd className="mt-2 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className={`w-2 h-2 rounded-full ${config?.status === 'RUNNING' ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`}></span>
                    <span className={config?.status === 'RUNNING' ? 'text-green-400 font-medium text-sm' : 'text-red-400 font-medium text-sm'}>
                      {config?.status || 'UNKNOWN'}
                    </span>
                  </div>
                  <div className="text-xs text-slate-400">
                    Uptime: {config?.uptimeStartedAt ? <LiveUptime since={config.uptimeStartedAt} /> : 'Offline'}
                  </div>
                </dd>
              </div>
            </dl>
          </div>

          <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-6 shadow-sm flex flex-col">
            <div className="flex items-center gap-3 mb-6">
              <div className="p-2.5 bg-blue-500/10 text-blue-400 rounded-lg">
                <Activity size={20} />
              </div>
              <h2 className="text-lg font-semibold text-white">Connection Limits</h2>
            </div>
            <dl className="space-y-4 flex-grow">
              <div>
                <dt className="text-sm font-medium text-slate-400 flex items-center gap-2 mb-1">
                  <ArrowRightLeft size={14} /> Max Connections
                </dt>
                <input 
                  type="number" 
                  value={formData.maxConnections} 
                  onChange={(e: any) => setFormData({ ...formData, maxConnections: parseInt(e.target.value) || 50 })}
                  className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white font-mono text-sm"
                />
                <p className="text-xs text-slate-500 mt-1">Maximum allowed active SMPP binds across all users.</p>
              </div>
              <div className="pt-2">
                <dt className="text-sm font-medium text-slate-400 flex items-center gap-2 mb-1">
                  <Clock size={14} /> Enquire Link Timeout (ms)
                </dt>
                <input 
                  type="number" 
                  step="1000"
                  value={formData.enquireLinkTimeout} 
                  onChange={(e: any) => setFormData({ ...formData, enquireLinkTimeout: parseInt(e.target.value) || 30000 })}
                  className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white font-mono text-sm"
                />
                <p className="text-xs text-slate-500 mt-1">Maximum wait time for an enquire_link_resp before dropping.</p>
              </div>
            </dl>
            <div className="mt-8 pt-6 border-t border-white/5 flex justify-end">
              <button
                onClick={handleSave}
                disabled={updateMut.isPending}
                className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
              >
                <Save size={16} />
                {updateMut.isPending ? 'Saving...' : 'Save Settings'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
