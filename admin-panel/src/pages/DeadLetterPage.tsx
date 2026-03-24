import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getDeadLetters, retryDeadLetter, discardDeadLetter } from '../api/client'
import { Skull, RotateCcw, Trash2, AlertTriangle } from 'lucide-react'

export default function DeadLetterPage() {
  const qc = useQueryClient()
  const [page, setPage] = useState(0)

  const { data, isLoading } = useQuery({
    queryKey: ['dlq', page],
    queryFn: () => getDeadLetters(page),
    refetchInterval: 30000,
  })

  const retryMut = useMutation({
    mutationFn: (id: number) => retryDeadLetter(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dlq'] }),
  })

  const discardMut = useMutation({
    mutationFn: (id: number) => discardDeadLetter(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dlq'] }),
  })

  const items = data?.content ?? []
  const totalPages = data?.totalPages ?? 0

  return (
    <div className="p-6 space-y-4">
      <div>
        <h1 className="text-xl font-bold text-white flex items-center gap-2">
          <Skull size={22} className="text-red-400" /> Dead-Letter Queue
        </h1>
        <p className="text-slate-500 text-xs mt-0.5">
          Messages that failed delivery permanently. Retry or discard them.
        </p>
      </div>

      {isLoading ? (
        <div className="text-slate-500 text-sm">Loading…</div>
      ) : items.length === 0 ? (
        <div className="glass p-10 text-center">
          <AlertTriangle size={32} className="text-slate-600 mx-auto mb-3" />
          <div className="text-slate-500 text-sm">No dead-letter messages</div>
          <div className="text-slate-700 text-xs mt-1">Failed messages will appear here after 10 minutes</div>
        </div>
      ) : (
        <>
          <div className="glass overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                  <th className="px-4 py-2 text-left">ID</th>
                  <th className="px-4 py-2 text-left">From → To</th>
                  <th className="px-4 py-2 text-left">Message</th>
                  <th className="px-4 py-2 text-left">Failure Reason</th>
                  <th className="px-4 py-2 text-left">Failed At</th>
                  <th className="px-4 py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {items.map((m: any) => (
                  <tr key={m.id} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                    <td className="px-4 py-2 text-slate-400 font-mono text-xs">#{m.originalMessageId}</td>
                    <td className="px-4 py-2 text-white text-xs">
                      {m.sourceAddress} → {m.destinationAddress}
                    </td>
                    <td className="px-4 py-2 text-slate-400 text-xs max-w-[200px] truncate">{m.messageText}</td>
                    <td className="px-4 py-2 text-red-400/80 text-xs max-w-[200px] truncate">{m.failureReason || '—'}</td>
                    <td className="px-4 py-2 text-slate-500 text-xs">
                      {new Date(m.createdAt).toLocaleString()}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="flex gap-1 justify-end">
                        <button
                          className="p-1.5 rounded text-brand-400 hover:bg-brand-600/20 transition"
                          title="Retry"
                          onClick={() => retryMut.mutate(m.id)}>
                          <RotateCcw size={14} />
                        </button>
                        <button
                          className="p-1.5 rounded text-red-400 hover:bg-red-900/20 transition"
                          title="Discard"
                          onClick={() => discardMut.mutate(m.id)}>
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="flex justify-center gap-2 pt-2">
              <button
                className="px-3 py-1 rounded text-xs text-slate-400 hover:text-white bg-white/5 disabled:opacity-30"
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}>← Prev</button>
              <span className="text-xs text-slate-500 py-1">Page {page + 1} of {totalPages}</span>
              <button
                className="px-3 py-1 rounded text-xs text-slate-400 hover:text-white bg-white/5 disabled:opacity-30"
                disabled={page >= totalPages - 1}
                onClick={() => setPage(p => p + 1)}>Next →</button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
