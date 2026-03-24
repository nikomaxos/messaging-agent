import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getAuditLogs } from '../api/client'
import { Shield, Filter } from 'lucide-react'

const ACTION_COLORS: Record<string, string> = {
  CREATE: 'text-emerald-400', UPDATE: 'text-amber-400', DELETE: 'text-red-400', LOGIN: 'text-blue-400',
}

export default function AuditLogPage() {
  const [page, setPage] = useState(0)
  const [userFilter, setUserFilter] = useState('')
  const [actionFilter, setActionFilter] = useState('')

  const { data, isLoading } = useQuery({
    queryKey: ['audit', page, userFilter, actionFilter],
    queryFn: () => getAuditLogs(page, 50, userFilter || undefined, actionFilter || undefined),
    refetchInterval: 15000,
  })

  const items = data?.content ?? []
  const totalPages = data?.totalPages ?? 0

  return (
    <div className="p-6 space-y-4">
      <div>
        <h1 className="text-xl font-bold text-white flex items-center gap-2">
          <Shield size={22} className="text-violet-400" /> Audit Log
        </h1>
        <p className="text-slate-500 text-xs mt-0.5">All admin actions are automatically logged.</p>
      </div>

      {/* Filters */}
      <div className="flex gap-2 items-center">
        <Filter size={14} className="text-slate-500" />
        <input
          className="bg-white/[0.04] border border-white/10 rounded px-3 py-1.5 text-xs text-white w-40 placeholder:text-slate-600"
          placeholder="Filter by username…"
          value={userFilter}
          onChange={e => { setUserFilter(e.target.value); setPage(0) }}
        />
        <select
          className="bg-white/[0.04] border border-white/10 rounded px-3 py-1.5 text-xs text-white"
          value={actionFilter}
          onChange={e => { setActionFilter(e.target.value); setPage(0) }}>
          <option value="">All actions</option>
          <option value="CREATE">CREATE</option>
          <option value="UPDATE">UPDATE</option>
          <option value="DELETE">DELETE</option>
        </select>
      </div>

      {isLoading ? (
        <div className="text-slate-500 text-sm">Loading…</div>
      ) : (
        <div className="glass overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                <th className="px-4 py-2 text-left">Time</th>
                <th className="px-4 py-2 text-left">User</th>
                <th className="px-4 py-2 text-left">Action</th>
                <th className="px-4 py-2 text-left">Entity</th>
                <th className="px-4 py-2 text-left">Details</th>
                <th className="px-4 py-2 text-left">IP</th>
              </tr>
            </thead>
            <tbody>
              {items.map((a: any) => (
                <tr key={a.id} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                  <td className="px-4 py-2 text-slate-500 text-xs whitespace-nowrap">
                    {new Date(a.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-white text-xs">{a.username}</td>
                  <td className="px-4 py-2">
                    <span className={`text-xs font-mono ${ACTION_COLORS[a.action] || 'text-slate-400'}`}>
                      {a.action}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-slate-400 text-xs">
                    {a.entityType}{a.entityId ? ` #${a.entityId}` : ''}
                  </td>
                  <td className="px-4 py-2 text-slate-500 text-xs max-w-[300px] truncate">{a.details}</td>
                  <td className="px-4 py-2 text-slate-600 text-xs font-mono">{a.ipAddress}</td>
                </tr>
              ))}
              {items.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-8 text-center text-slate-600 text-sm">No audit entries found</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex justify-center gap-2 pt-2">
          <button className="px-3 py-1 rounded text-xs text-slate-400 hover:text-white bg-white/5 disabled:opacity-30"
            disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
          <span className="text-xs text-slate-500 py-1">Page {page + 1} of {totalPages}</span>
          <button className="px-3 py-1 rounded text-xs text-slate-400 hover:text-white bg-white/5 disabled:opacity-30"
            disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next →</button>
        </div>
      )}
    </div>
  )
}
