import { useQuery } from '@tanstack/react-query'
import { getSystemHealth } from '../api/client'
import { RefreshCw, Cpu, MemoryStick, HardDrive, Activity, Smartphone, MailCheck, MailX } from 'lucide-react'

const formatBytes = (bytes: number) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`
}

const formatUptime = (ms: number) => {
  const h = Math.floor(ms / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

const GaugeRing = ({ value, label, color, icon: Icon, detail }: {
  value: number, label: string, color: string, icon: any, detail?: string
}) => {
  const pct = Math.min(Math.max(value, 0), 100)
  const radius = 52
  const circumference = 2 * Math.PI * radius
  const offset = circumference - (pct / 100) * circumference
  const gaugeColor = pct > 90 ? '#ef4444' : pct > 70 ? '#f59e0b' : color

  return (
    <div className="flex flex-col items-center gap-2">
      <div className="relative w-32 h-32">
        <svg className="w-full h-full -rotate-90" viewBox="0 0 120 120">
          <circle cx="60" cy="60" r={radius} strokeWidth="8" fill="none" className="stroke-white/5" />
          <circle cx="60" cy="60" r={radius} strokeWidth="8" fill="none" stroke={gaugeColor}
            strokeDasharray={circumference} strokeDashoffset={offset}
            strokeLinecap="round" className="transition-all duration-700" />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <Icon size={18} style={{ color: gaugeColor }} />
          <span className="text-xl font-bold text-white mt-0.5">{pct.toFixed(0)}%</span>
        </div>
      </div>
      <div className="text-center">
        <div className="text-sm font-medium text-white">{label}</div>
        {detail && <div className="text-[10px] text-slate-500 mt-0.5">{detail}</div>}
      </div>
    </div>
  )
}

const MetricCard = ({ label, value, sub, color = 'text-white' }: {
  label: string, value: string | number, sub?: string, color?: string
}) => (
  <div className="glass p-4">
    <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1">{label}</div>
    <div className={`text-2xl font-bold ${color}`}>{value}</div>
    {sub && <div className="text-xs text-slate-500 mt-0.5">{sub}</div>}
  </div>
)

export default function InfraMonitoringPage() {
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['system-health'],
    queryFn: getSystemHealth,
    refetchInterval: 5000,
  })

  const os = data?.os ?? {}
  const jvm = data?.jvm ?? {}
  const disks: any[] = data?.disks ?? []
  const fleet = data?.fleet ?? {}
  const pipeline = data?.pipeline ?? {}

  const cpuPct = os.cpuUsage ?? 0
  const ramPct = os.totalPhysicalMemory ? ((os.usedPhysicalMemory / os.totalPhysicalMemory) * 100) : 0
  const heapPct = jvm.heapMax > 0 ? ((jvm.heapUsed / jvm.heapMax) * 100) : 0
  const mainDisk = disks[0] ?? {}
  const diskPct = mainDisk.totalSpace > 0 ? ((mainDisk.usedSpace / mainDisk.totalSpace) * 100) : 0

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white mb-1">Infrastructure Monitoring</h1>
          <p className="text-slate-400 text-sm">Real-time system health • Auto-refreshes every 5s</p>
        </div>
        <button className="btn-secondary" onClick={() => refetch()}>
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {isLoading && <div className="text-slate-500 text-center py-12">Loading system metrics…</div>}

      {data && (
        <>
          {/* ── Gauge Row ────────────────────────────────────────────── */}
          <div className="glass p-6 grid grid-cols-2 md:grid-cols-4 gap-6 place-items-center">
            <GaugeRing value={cpuPct} label="CPU" color="#3b82f6" icon={Cpu}
              detail={`${os.processors ?? '?'} cores • Load: ${(os.loadAverage ?? 0).toFixed(2)}`} />
            <GaugeRing value={ramPct} label="RAM" color="#8b5cf6" icon={MemoryStick}
              detail={`${formatBytes(os.usedPhysicalMemory ?? 0)} / ${formatBytes(os.totalPhysicalMemory ?? 0)}`} />
            <GaugeRing value={diskPct} label="Disk" color="#f59e0b" icon={HardDrive}
              detail={`${formatBytes(mainDisk.usedSpace ?? 0)} / ${formatBytes(mainDisk.totalSpace ?? 0)}`} />
            <GaugeRing value={heapPct} label="JVM Heap" color="#10b981" icon={Activity}
              detail={`${formatBytes(jvm.heapUsed ?? 0)} / ${formatBytes(jvm.heapMax ?? 0)}`} />
          </div>

          {/* ── JVM & OS Details ──────────────────────────────────────── */}
          <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-4">
            <MetricCard label="JVM Uptime" value={formatUptime(jvm.uptimeMs ?? 0)} />
            <MetricCard label="Threads" value={jvm.threadCount ?? 0} sub={`Peak: ${jvm.peakThreadCount ?? 0}`} />
            <MetricCard label="Non-Heap" value={formatBytes(jvm.nonHeapUsed ?? 0)} />
            <MetricCard label="Process CPU" value={`${os.processCpuUsage ?? 0}%`} color={os.processCpuUsage > 50 ? 'text-amber-400' : 'text-emerald-400'} />
            <MetricCard label="OS" value={os.name ?? '—'} sub={os.arch ?? ''} />
            <MetricCard label="Processors" value={os.processors ?? 0} />
          </div>

          {/* ── Device Fleet ──────────────────────────────────────────── */}
          <div className="glass p-5">
            <h2 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
              <Smartphone size={14} className="text-brand-400" /> Device Fleet
            </h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div className="bg-white/5 rounded-lg p-3 text-center">
                <div className="text-2xl font-bold text-white">{fleet.total ?? 0}</div>
                <div className="text-[10px] text-slate-500 uppercase font-bold">Total</div>
              </div>
              <div className="bg-emerald-500/10 rounded-lg p-3 text-center border border-emerald-500/20">
                <div className="text-2xl font-bold text-emerald-400">{fleet.online ?? 0}</div>
                <div className="text-[10px] text-emerald-600 uppercase font-bold">Online</div>
              </div>
              <div className="bg-slate-500/10 rounded-lg p-3 text-center">
                <div className="text-2xl font-bold text-slate-400">{fleet.offline ?? 0}</div>
                <div className="text-[10px] text-slate-500 uppercase font-bold">Offline</div>
              </div>
              <div className="bg-amber-500/10 rounded-lg p-3 text-center border border-amber-500/20">
                <div className="text-2xl font-bold text-amber-400">{fleet.busy ?? 0}</div>
                <div className="text-[10px] text-amber-600 uppercase font-bold">Busy</div>
              </div>
            </div>
          </div>

          {/* ── Message Pipeline ───────────────────────────────────────── */}
          <div className="glass p-5">
            <h2 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
              <MailCheck size={14} className="text-emerald-400" /> Message Pipeline
            </h2>
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
              <div className="bg-slate-500/10 rounded-lg p-3 text-center">
                <div className="text-xl font-bold text-white">{pipeline.receivedLastHour ?? 0}</div>
                <div className="text-[10px] text-slate-500 uppercase font-bold">Received (1h)</div>
              </div>
              <div className="bg-amber-500/10 rounded-lg p-3 text-center">
                <div className="text-xl font-bold text-amber-400">{pipeline.dispatchedLastHour ?? 0}</div>
                <div className="text-[10px] text-amber-600 uppercase font-bold">Dispatched (1h)</div>
              </div>
              <div className="bg-emerald-500/10 rounded-lg p-3 text-center border border-emerald-500/20">
                <div className="text-xl font-bold text-emerald-400">{pipeline.deliveredLastHour ?? 0}</div>
                <div className="text-[10px] text-emerald-600 uppercase font-bold">Delivered (1h)</div>
              </div>
              <div className="bg-red-500/10 rounded-lg p-3 text-center border border-red-500/20">
                <div className="text-xl font-bold text-red-400">{pipeline.failedLastHour ?? 0}</div>
                <div className="text-[10px] text-red-600 uppercase font-bold">Failed (1h)</div>
              </div>
              <div className="bg-emerald-500/5 rounded-lg p-3 text-center">
                <div className="text-xl font-bold text-emerald-300">{pipeline.deliveredToday ?? 0}</div>
                <div className="text-[10px] text-slate-500 uppercase font-bold">Delivered Today</div>
              </div>
              <div className="bg-red-500/5 rounded-lg p-3 text-center">
                <div className="text-xl font-bold text-red-300">{pipeline.failedToday ?? 0}</div>
                <div className="text-[10px] text-slate-500 uppercase font-bold">Failed Today</div>
              </div>
            </div>
          </div>

          {/* ── Disk Details ──────────────────────────────────────────── */}
          {disks.length > 1 && (
            <div className="glass p-5">
              <h2 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
                <HardDrive size={14} className="text-amber-400" /> Disk Partitions
              </h2>
              <div className="space-y-2">
                {disks.map((d: any, i: number) => {
                  const pct = d.totalSpace > 0 ? (d.usedSpace / d.totalSpace) * 100 : 0
                  return (
                    <div key={i} className="flex items-center gap-3">
                      <span className="text-xs font-mono text-slate-400 w-16">{d.path}</span>
                      <div className="flex-1 h-3 bg-white/5 rounded-full overflow-hidden">
                        <div className={`h-full rounded-full transition-all duration-500 ${pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-brand-500'}`}
                          style={{ width: `${pct}%` }} />
                      </div>
                      <span className="text-xs text-slate-500 w-32 text-right">
                        {formatBytes(d.usedSpace)} / {formatBytes(d.totalSpace)}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
