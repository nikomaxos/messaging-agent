import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getThroughput } from '../api/client'
import { BarChart3 } from 'lucide-react'

const WINDOWS = [
  { value: '1h', label: 'Last 1 hour' },
  { value: '24h', label: 'Last 24 hours' },
  { value: '7d', label: 'Last 7 days' },
]

function Bar({ label, count, max }: { label: string; count: number; max: number }) {
  const pct = max > 0 ? (count / max) * 100 : 0
  return (
    <div className="flex items-center gap-3">
      <div className="w-32 text-xs text-slate-400 truncate text-right">{label}</div>
      <div className="flex-1 h-6 bg-white/[0.03] rounded-full overflow-hidden relative">
        <div
          className="h-full rounded-full bg-gradient-to-r from-brand-600 to-brand-400 transition-all duration-500"
          style={{ width: `${Math.max(pct, 1)}%` }} />
        <span className="absolute inset-0 flex items-center justify-center text-[10px] text-white/80 font-mono">
          {count.toLocaleString()}
        </span>
      </div>
    </div>
  )
}

export default function ThroughputPage() {
  const [window, setWindow] = useState('1h')

  const { data, isLoading } = useQuery({
    queryKey: ['throughput', window],
    queryFn: () => getThroughput(window),
    refetchInterval: 30000,
  })

  const smsc = data?.smsc ?? []
  const devices = data?.devices ?? []
  const maxSmsc = Math.max(...smsc.map((s: any) => s.count), 1)
  const maxDevice = Math.max(...devices.map((d: any) => d.count), 1)

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <BarChart3 size={22} className="text-orange-400" /> Throughput
          </h1>
          <p className="text-slate-500 text-xs mt-0.5">Per-SMSC and per-device message volume.</p>
        </div>
        <div className="flex gap-1">
          {WINDOWS.map(w => (
            <button key={w.value}
              className={`px-3 py-1.5 rounded text-xs font-medium transition ${window === w.value ? 'bg-brand-600/20 text-brand-400 border border-brand-500/30' : 'text-slate-400 hover:text-white bg-white/5'}`}
              onClick={() => setWindow(w.value)}>
              {w.label}
            </button>
          ))}
        </div>
      </div>

      {isLoading ? <div className="text-slate-500 text-sm">Loading…</div> : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* SMSC Throughput */}
          <div className="glass p-5 space-y-3">
            <h2 className="text-sm font-bold text-white">SMSC Suppliers</h2>
            {smsc.length === 0 ? (
              <div className="text-slate-600 text-xs py-4 text-center">No SMSC suppliers configured</div>
            ) : (
              <div className="space-y-2">
                {smsc.map((s: any) => <Bar key={s.id} label={s.name} count={s.count} max={maxSmsc} />)}
              </div>
            )}
          </div>

          {/* Device Throughput */}
          <div className="glass p-5 space-y-3">
            <h2 className="text-sm font-bold text-white">Devices</h2>
            {devices.length === 0 ? (
              <div className="text-slate-600 text-xs py-4 text-center">No devices registered</div>
            ) : (
              <div className="space-y-2">
                {devices.filter((d: any) => d.count > 0).map((d: any) => (
                  <Bar key={d.id} label={d.name} count={d.count} max={maxDevice} />
                ))}
                {devices.every((d: any) => d.count === 0) && (
                  <div className="text-slate-600 text-xs py-4 text-center">No messages in this window</div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
