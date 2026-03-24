import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getReports, generateReport } from '../api/client'
import { FileText, RefreshCw, TrendingUp, Users, AlertTriangle } from 'lucide-react'

export default function ReportsPage() {
  const qc = useQueryClient()
  const [selectedReport, setSelectedReport] = useState<any>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['reports'],
    queryFn: () => getReports(),
  })

  const genMut = useMutation({
    mutationFn: generateReport,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reports'] }),
  })

  const reports = data?.content ?? []

  const parseReport = (r: any) => {
    try { return JSON.parse(r.reportJson) } catch { return {} }
  }

  return (
    <div className="p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <FileText size={22} className="text-sky-400" /> Reports
          </h1>
          <p className="text-slate-500 text-xs mt-0.5">Daily platform performance reports (auto-generated at 06:00 UTC).</p>
        </div>
        <button
          className="bg-brand-600 hover:bg-brand-500 text-white rounded px-4 py-2 text-xs font-medium flex items-center gap-2 disabled:opacity-40 transition"
          disabled={genMut.isPending}
          onClick={() => genMut.mutate()}>
          <RefreshCw size={13} className={genMut.isPending ? 'animate-spin' : ''} />
          {genMut.isPending ? 'Generating…' : 'Generate Now'}
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Report List */}
        <div className="glass p-4 space-y-2 lg:col-span-1">
          <h2 className="text-xs font-bold text-slate-500 uppercase">History</h2>
          {isLoading ? <div className="text-slate-500 text-xs">Loading…</div> : (
            <div className="space-y-1">
              {reports.map((r: any) => (
                <button key={r.id}
                  className={`w-full text-left px-3 py-2 rounded text-xs transition ${selectedReport?.id === r.id ? 'bg-brand-600/20 border border-brand-500/30 text-brand-400' : 'bg-white/[0.02] text-slate-400 hover:bg-white/5 hover:text-white'}`}
                  onClick={() => setSelectedReport(r)}>
                  <div className="font-medium">{r.period} Report</div>
                  <div className="text-[10px] text-slate-600">{new Date(r.generatedAt).toLocaleString()}</div>
                </button>
              ))}
              {reports.length === 0 && (
                <div className="text-slate-600 text-xs py-4 text-center">No reports yet. Click "Generate Now" to create one.</div>
              )}
            </div>
          )}
        </div>

        {/* Report Detail */}
        <div className="glass p-6 lg:col-span-2">
          {selectedReport ? (() => {
            const d = parseReport(selectedReport)
            return (
              <div className="space-y-4">
                <div className="text-sm font-bold text-white mb-4">
                  {selectedReport.period} Report — {new Date(selectedReport.generatedAt).toLocaleDateString()}
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase">Total Messages</div>
                    <div className="text-xl font-bold text-white">{d.totalMessages?.toLocaleString() ?? '—'}</div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase flex items-center gap-1"><TrendingUp size={10} /> Delivery Rate</div>
                    <div className={`text-xl font-bold ${(d.deliveryRate ?? 0) > 90 ? 'text-emerald-400' : (d.deliveryRate ?? 0) > 70 ? 'text-amber-400' : 'text-red-400'}`}>
                      {d.deliveryRate ?? '—'}%
                    </div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase">Delivered</div>
                    <div className="text-xl font-bold text-emerald-400">{d.delivered?.toLocaleString() ?? '—'}</div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase flex items-center gap-1"><AlertTriangle size={10} /> Failed</div>
                    <div className="text-xl font-bold text-red-400">{d.failed?.toLocaleString() ?? '—'}</div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase">Queued</div>
                    <div className="text-xl font-bold text-amber-400">{d.queued?.toLocaleString() ?? '—'}</div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase flex items-center gap-1"><Users size={10} /> Devices Online</div>
                    <div className="text-xl font-bold text-white">{d.onlineDevices ?? '—'} / {d.totalDevices ?? '—'}</div>
                  </div>
                  <div className="bg-white/[0.03] rounded-lg p-3 border border-white/5">
                    <div className="text-[10px] text-slate-500 uppercase">Devices Offline</div>
                    <div className="text-xl font-bold text-red-400">{d.offlineDevices ?? '—'}</div>
                  </div>
                </div>
              </div>
            )
          })() : (
            <div className="text-center py-10 text-slate-600 text-sm">
              Select a report from the list to view details
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
