import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getLogs } from '../api/client'
import { MessageLog } from '../types'
import { RefreshCw } from 'lucide-react'
import { format } from 'date-fns'

const statusClass = (s: MessageLog['status']) => ({
  RECEIVED:  'pill-gray',
  DISPATCHED:'pill-yellow',
  DELIVERED: 'pill-green',
  RCS_FAILED:'pill-red',
  FAILED:    'pill-red',
}[s])

export default function LogsPage() {
  const [page, setPage] = useState(0)
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['logs', page],
    queryFn: () => getLogs(page),
    refetchInterval: 10000,
  })
  const logs: MessageLog[] = data?.content ?? []
  const totalPages: number = data?.totalPages ?? 0

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Message Logs</h1>
          <p className="text-slate-400 text-sm mt-0.5">Full audit trail of all SMPP → RCS routing events</p>
        </div>
        <button className="btn-secondary" onClick={() => refetch()}>
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      <div className="glass overflow-x-auto">
        <table className="tbl">
          <thead><tr>
            <th className="px-4 pt-4 pb-3">#</th>
            <th className="px-4">From</th>
            <th className="px-4">To</th>
            <th className="px-4">Message</th>
            <th className="px-4">Status</th>
            <th className="px-4">Device</th>
            <th className="px-4">Error</th>
            <th className="px-4">Time</th>
          </tr></thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={8} className="px-4 py-8 text-center text-slate-500">Loading…</td></tr>
            )}
            {logs.map(l => (
              <tr key={l.id}>
                <td className="px-4 text-xs font-mono text-slate-500">{l.id}</td>
                <td className="px-4 text-xs font-mono text-slate-300">{l.sourceAddress ?? '—'}</td>
                <td className="px-4 text-xs font-mono text-slate-300">{l.destinationAddress ?? '—'}</td>
                <td className="px-4 text-xs text-slate-400 max-w-[200px] truncate" title={l.messageText}>
                  {l.messageText ?? '—'}
                </td>
                <td className="px-4"><span className={`pill ${statusClass(l.status)}`}>{l.status}</span></td>
                <td className="px-4 text-xs text-slate-400">{l.device?.name ?? '—'}</td>
                <td className="px-4 text-xs text-red-400 max-w-[150px] truncate" title={l.errorDetail}>
                  {l.errorDetail ?? ''}
                </td>
                <td className="px-4 text-xs text-slate-500">
                  {format(new Date(l.createdAt), 'MMM d, HH:mm:ss')}
                </td>
              </tr>
            ))}
            {!isLoading && logs.length === 0 && (
              <tr><td colSpan={8} className="px-4 py-8 text-center text-slate-500">No messages yet</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
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
