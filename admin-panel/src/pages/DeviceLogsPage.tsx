import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDeviceLogs, getDevices } from '../api/client'
import { Device } from '../types'
import { RefreshCw, Search, X, Smartphone } from 'lucide-react'
import { format, formatDistanceToNow } from 'date-fns'

interface DeviceLog {
  id: number
  device?: Device
  level: string
  event: string
  detail?: string
  createdAt: string
}

const levelColor: Record<string, string> = {
  INFO:  'text-emerald-400',
  WARN:  'text-yellow-400',
  ERROR: 'text-red-400',
}

export default function DeviceLogsPage() {
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState({ deviceId: '', level: '' })
  const [appliedFilters, setAppliedFilters] = useState(filters)

  const { data: devices = [] } = useQuery({ queryKey: ['devices'], queryFn: getDevices })
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['deviceLogs', page, appliedFilters],
    queryFn: () => getDeviceLogs(page, appliedFilters),
    refetchInterval: 10000,
    placeholderData: (prev: any) => prev,
  })

  const logs: DeviceLog[] = data?.content ?? []
  const totalPages: number = data?.totalPages ?? 0

  const applyFilters = () => {
    setPage(0)
    setAppliedFilters({ ...filters })
  }

  const clearFilters = () => {
    const empty = { deviceId: '', level: '' }
    setFilters(empty)
    setAppliedFilters(empty)
    setPage(0)
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white mb-1">Device Logs</h1>
          <p className="text-slate-400 text-sm">Review error stack traces and warnings uploaded from Android devices.</p>
        </div>
        <button className="btn-secondary" onClick={() => refetch()}>
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {/* Filters Toolbar */}
      <div className="glass p-4 flex flex-wrap gap-4 items-end">
        <div className="flex-1 min-w-[200px]">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Device</label>
          <select className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                  value={filters.deviceId} onChange={e => setFilters({ ...filters, deviceId: e.target.value })}>
            <option value="">All Devices</option>
            {devices.map((d: Device) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>
        <div className="flex-1 min-w-[150px]">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Log Level</label>
          <select className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                  value={filters.level} onChange={e => setFilters({ ...filters, level: e.target.value })}>
            <option value="">All Levels</option>
            <option value="INFO">INFO</option>
            <option value="WARN">WARN</option>
            <option value="ERROR">ERROR</option>
          </select>
        </div>
        <div className="flex gap-2">
          <button onClick={applyFilters} className="bg-brand-600 hover:bg-brand-500 text-white px-4 rounded text-sm py-1.5 font-medium transition flex items-center gap-2">
            <Search size={14} /> Search
          </button>
          <button onClick={clearFilters} className="bg-slate-800 hover:bg-slate-700 text-slate-300 px-3 rounded text-sm font-medium transition flex justify-center items-center" title="Clear Filters">
            <X size={14} />
          </button>
        </div>
      </div>

      {/* Main Table */}
      <div className="glass overflow-hidden">
        <div className="overflow-x-auto">
          <table className="tbl w-full">
            <thead>
              <tr>
                <th className="px-4 pt-4 pb-3">Time</th>
                <th className="px-4">Level</th>
                <th className="px-4">Device</th>
                <th className="px-4">Event</th>
                <th className="px-4">Detail</th>
              </tr>
            </thead>
            <tbody>
              {isLoading && (
                <tr><td colSpan={5} className="px-4 py-8 text-center text-slate-500">Loading…</td></tr>
              )}
              {logs.map(l => (
                <tr key={l.id} className="hover:bg-white/[0.02] transition">
                  <td className="px-4 py-3 text-xs font-mono text-slate-500 whitespace-nowrap">
                    {format(new Date(l.createdAt), 'MMM d, HH:mm:ss')}
                    <div className="text-[10px] text-slate-600 space-y-1 mt-0.5">
                       {formatDistanceToNow(new Date(l.createdAt), { addSuffix: true })}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-bold font-mono ${levelColor[l.level] ?? 'text-slate-400'}`}>
                      {l.level}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-300">
                    <div className="flex items-center gap-2">
                      <Smartphone size={14} className="text-slate-500" />
                      {l.device?.name || 'Unknown'}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-200">{l.event}</td>
                  <td className="px-4 py-3 text-xs text-slate-500 max-w-[400px] truncate" title={l.detail}>
                    {l.detail ?? '—'}
                  </td>
                </tr>
              ))}
              {!isLoading && logs.length === 0 && (
                <tr><td colSpan={5} className="px-4 py-12 text-center text-slate-500">No device logs found</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center gap-2 justify-center">
          <button className="btn-secondary !px-3" disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
          <span className="text-slate-400 text-sm">Page {page + 1} of {totalPages}</span>
          <button className="btn-secondary !px-3" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next →</button>
        </div>
      )}
    </div>
  )
}
