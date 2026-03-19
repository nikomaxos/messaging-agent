import { useQuery } from '@tanstack/react-query'
import { getDevices, getGroups } from '../api/client'
import { Device } from '../types'
import { Battery, Wifi, Signal, Activity, Smartphone, CheckCircle, XCircle, BatteryCharging } from 'lucide-react'
import { formatDistanceToNow, format } from 'date-fns'

function statusPill(status: Device['status']) {
  const map = {
    ONLINE: 'pill-green',
    OFFLINE: 'pill-gray',
    BUSY: 'pill-yellow',
    MAINTENANCE: 'pill-yellow',
  }
  return <span className={`pill ${map[status]}`}>{status}</span>
}

function BatteryIcon({ pct }: { pct?: number }) {
  if (pct == null) return <span className="text-slate-500">—</span>
  const color = pct > 50 ? 'text-emerald-400' : pct > 20 ? 'text-yellow-400' : 'text-red-400'
  return <span className={`flex items-center gap-1 ${color}`}><Battery size={14} />{pct}%</span>
}

function DeviceCard({ d }: { d: Device }) {
  return (
    <div className="glass p-4 hover:border-brand-600/30 transition-all cursor-default group">
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-2">
          <div className={`w-7 h-7 rounded-lg flex items-center justify-center
            ${d.status === 'ONLINE' ? 'bg-emerald-900/50' : 'bg-slate-800'}`}>
            <Smartphone size={14} className={d.status === 'ONLINE' ? 'text-emerald-400' : 'text-slate-500'} />
          </div>
          <div>
            <div className="text-sm font-medium text-slate-200">{d.name}</div>
            <div className="text-[10px] text-slate-500">{d.imei ?? 'No IMEI'}</div>
          </div>
        </div>
        {statusPill(d.status)}
      </div>

      {/* Metrics */}
      <div className="grid grid-cols-3 gap-2 text-xs">
        <div className="flex flex-col items-center bg-slate-800/50 rounded-lg p-2">
          {d.isCharging ? (
            <BatteryCharging size={12} className="text-emerald-400 mb-1 animate-pulse" />
          ) : (
            <Battery size={12} className={d.batteryPercent != null && d.batteryPercent < 20 ? 'text-red-400 mb-1' : 'text-slate-400 mb-1'} />
          )}
          <span className={
            d.isCharging 
              ? 'text-emerald-400 font-medium' 
              : (d.batteryPercent != null && d.batteryPercent < 20 ? 'text-red-400' : 'text-slate-300')
          }>
            {d.batteryPercent != null ? `${d.batteryPercent}%` : '—'}
          </span>
          <span className="text-slate-600 text-[9px]">Battery</span>
        </div>
        <div className="flex flex-col items-center bg-slate-800/50 rounded-lg p-2">
          <Wifi size={12} className="text-slate-400 mb-1" />
          <span className="text-slate-300">{d.wifiSignalDbm != null ? `${d.wifiSignalDbm} dBm` : '—'}</span>
          <span className="text-slate-600 text-[9px]">Wi-Fi</span>
        </div>
        <div className="flex flex-col items-center bg-slate-800/50 rounded-lg p-2">
          <Signal size={12} className="text-slate-400 mb-1" />
          <span className="text-slate-300">{d.gsmSignalDbm != null ? `${d.gsmSignalDbm} dBm` : '—'}</span>
          <span className="text-slate-600 text-[9px]">GSM</span>
        </div>
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between mt-3 text-[10px] text-slate-500">
        <span className="flex items-center gap-1">
          {d.rcsCapable
            ? <><CheckCircle size={10} className="text-emerald-500" /> RCS ready</>
            : <><XCircle size={10} className="text-slate-600" /> No RCS</>}
        </span>
        <span>
          {d.lastHeartbeat
            ? format(new Date(d.lastHeartbeat), 'h:mm a').toLowerCase()
            : 'never'}
        </span>
      </div>
    </div>
  )
}

function StatCard({ label, value, icon, sub }: { label: string; value: number | string; icon: React.ReactNode; sub?: string }) {
  return (
    <div className="glass p-5">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs font-medium text-slate-400 uppercase tracking-wider">{label}</span>
        <div className="w-8 h-8 rounded-lg bg-brand-600/20 flex items-center justify-center text-brand-400">
          {icon}
        </div>
      </div>
      <div className="text-3xl font-bold text-white">{value}</div>
      {sub && <div className="text-xs text-slate-500 mt-1">{sub}</div>}
    </div>
  )
}

export default function DashboardPage() {
  const { data: devices = [] } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: 10000,
    placeholderData: (prev: any) => prev,   // keep last data while refetching — prevents blank on reload
  })
  const { data: groups = [] } = useQuery({
    queryKey: ['groups'],
    queryFn: getGroups,
    placeholderData: (prev: any) => prev,
  })

  const online = devices.filter((d: Device) => d.status === 'ONLINE').length
  const rcsReady = devices.filter((d: Device) => d.rcsCapable).length

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-xl font-bold text-white">Dashboard</h1>
        <p className="text-slate-400 text-sm mt-0.5 dot-live">Live — auto-refreshes every 5s</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="Total Devices" value={devices.length} icon={<Smartphone size={16} />} />
        <StatCard label="Online" value={online} icon={<Activity size={16} />} sub={`${devices.length - online} offline`} />
        <StatCard label="Groups" value={groups.length} icon={<CheckCircle size={16} />} sub="virtual SMSCs" />
        <StatCard label="RCS Capable" value={rcsReady} icon={<Signal size={16} />} sub={`of ${devices.length} devices`} />
      </div>

      {/* Device Grid */}
      <div>
        <h2 className="text-sm font-semibold text-slate-300 mb-3">Device Pool</h2>
        {devices.length === 0 ? (
          <div className="glass p-8 text-center text-slate-500">
            No devices registered yet. Add devices in the <strong>Devices</strong> section.
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
            {devices.map((d: Device) => <DeviceCard key={d.id} d={d} />)}
          </div>
        )}
      </div>
    </div>
  )
}
