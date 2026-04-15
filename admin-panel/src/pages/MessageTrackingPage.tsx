import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getLogs, getLogIds, getGroups, getDevices, resubmitMessages, getSmscSuppliers } from '../api/client'
import { MessageLog, DeviceGroup, Device, SmscSupplier } from '../types'
import { RefreshCw, Search, X, Info, ChevronUp, ChevronDown, ChevronsUpDown, Send, CheckSquare, Layers } from 'lucide-react'
import { format } from 'date-fns'

const statusClass = (s: MessageLog['status']) => ({
  RECEIVED:  'pill-gray',
  DISPATCHED:'pill-yellow',
  DELIVERED: 'pill-green',
  RCS_FAILED:'pill-red',
  FAILED:    'pill-red',
}[s])

export default function MessageTrackingPage() {
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState({
    status: '', senderId: '', destinationNumber: '', deviceId: '', deviceGroupId: '', clientMessageId: '', supplierMessageId: '', startDate: '', endDate: ''
  })
  const [appliedFilters, setAppliedFilters] = useState(filters)
  
  const [selectedLog, setSelectedLog] = useState<MessageLog | null>(null)
  const [sortBy, setSortBy] = useState('createdAt')
  const [sortDir, setSortDir] = useState<'ASC' | 'DESC'>('DESC')
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [intervalSec, setIntervalSec] = useState(5)
  const [perPage, setPerPage] = useState(50)

  // Mass selection state
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [allPagesSelected, setAllPagesSelected] = useState(false)
  const [loadingAllIds, setLoadingAllIds] = useState(false)
  const [showResubmitModal, setShowResubmitModal] = useState(false)
  const [resubmitSmscId, setResubmitSmscId] = useState<number | null>(null)
  const [resubmitResults, setResubmitResults] = useState<any[] | null>(null)
  const queryClient = useQueryClient()

  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: devices = [] } = useQuery({ queryKey: ['devices'], queryFn: getDevices })
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['logs', page, appliedFilters, sortBy, sortDir, perPage],
    queryFn: () => getLogs(page, appliedFilters, sortBy, sortDir, perPage),
    refetchInterval: autoRefresh ? Math.max(intervalSec, 1) * 1000 : false,
    placeholderData: (prev: any) => prev,
  })

  const { data: smscSuppliers = [] } = useQuery({
    queryKey: ['smsc-suppliers'],
    queryFn: getSmscSuppliers,
  })

  const logs: MessageLog[] = data?.content ?? []
  const totalPages: number = data?.totalPages ?? 0
  const totalElements: number = data?.totalElements ?? 0

  const toggleSort = (field: string) => {
    if (sortBy === field) {
      setSortDir(d => d === 'DESC' ? 'ASC' : 'DESC')
    } else {
      setSortBy(field)
      setSortDir('DESC')
    }
    setPage(0)
  }

  const SortIcon = ({ field }: { field: string }) => {
    if (sortBy !== field) return <ChevronsUpDown size={12} className="inline ml-0.5 text-slate-600" />
    return sortDir === 'ASC' ? <ChevronUp size={12} className="inline ml-0.5 text-brand-400" /> : <ChevronDown size={12} className="inline ml-0.5 text-brand-400" />
  }

  const applyFilters = () => {
    setPage(0)
    const processedFilters = { ...filters }
    if (processedFilters.startDate) processedFilters.startDate = new Date(processedFilters.startDate).toISOString()
    if (processedFilters.endDate) processedFilters.endDate = new Date(processedFilters.endDate).toISOString()
    setAppliedFilters(processedFilters)
  }

  const clearFilters = () => {
    const empty = { status: '', senderId: '', destinationNumber: '', deviceId: '', deviceGroupId: '', clientMessageId: '', supplierMessageId: '', startDate: '', endDate: '' }
    setFilters(empty)
    setAppliedFilters(empty)
    setPage(0)
  }

  // Mass selection helpers
  const toggleSelect = (id: number) => {
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }
  const toggleSelectAll = () => {
    if (selectedIds.size === logs.length && !allPagesSelected) {
      // All on page selected -> deselect all
      setSelectedIds(new Set())
      setAllPagesSelected(false)
    } else if (allPagesSelected) {
      // Global selected -> deselect all
      setSelectedIds(new Set())
      setAllPagesSelected(false)
    } else {
      // Select current page
      setSelectedIds(new Set(logs.map((l: MessageLog) => l.id)))
      setAllPagesSelected(false)
    }
  }

  const selectAllPages = async () => {
    setLoadingAllIds(true)
    try {
      const allIds = await getLogIds(appliedFilters)
      setSelectedIds(new Set(allIds))
      setAllPagesSelected(true)
    } catch (e) {
      console.error('Failed to fetch all IDs', e)
    } finally {
      setLoadingAllIds(false)
    }
  }

  const clearSelection = () => {
    setSelectedIds(new Set())
    setAllPagesSelected(false)
  }

  const resubmitMutation = useMutation({
    mutationFn: ({ messageIds, fallbackSmscId }: { messageIds: number[], fallbackSmscId: number }) =>
      resubmitMessages(messageIds, fallbackSmscId),
    onSuccess: (data: any) => {
      setResubmitResults(data)
      queryClient.invalidateQueries({ queryKey: ['logs'] })
    },
  })

  const handleResubmit = () => {
    if (!resubmitSmscId || selectedIds.size === 0) return
    resubmitMutation.mutate({ messageIds: Array.from(selectedIds), fallbackSmscId: resubmitSmscId })
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white mb-1">Message Tracking</h1>
          <p className="text-slate-400 text-sm">Monitor and filter all SMS/RCS inbound and outbound traffic.</p>
        </div>
        <div className="flex items-center gap-3">
          <label className="relative inline-flex items-center cursor-pointer">
            <input type="checkbox" className="sr-only peer" checked={autoRefresh} onChange={e => setAutoRefresh(e.target.checked)} />
            <div className="w-9 h-5 bg-slate-700 peer-checked:bg-brand-600 rounded-full transition-colors after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:after:translate-x-full"></div>
          </label>
          <span className="text-xs text-slate-400 whitespace-nowrap">Auto</span>
          <input
            type="number"
            min={1}
            max={999}
            value={intervalSec}
            onChange={e => setIntervalSec(Math.max(1, parseInt(e.target.value) || 1))}
            className="w-14 bg-[#12121f] text-sm text-white border border-white/10 rounded px-2 py-1 text-center"
            title="Refresh interval (seconds)"
          />
          <span className="text-xs text-slate-500">s</span>
          <button className="btn-secondary" onClick={() => refetch()}>
            <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      {/* Mass Selection Toolbar */}
      {selectedIds.size > 0 && (
        <div className="glass p-3 space-y-2 border border-brand-500/20">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <CheckSquare size={16} className="text-brand-400" />
              <span className="text-sm text-white font-medium">
                {allPagesSelected
                  ? `All ${selectedIds.size.toLocaleString()} matching results selected`
                  : `${selectedIds.size} message${selectedIds.size > 1 ? 's' : ''} selected (this page)`}
              </span>
              <button className="text-xs text-slate-400 hover:text-white" onClick={clearSelection}>Clear</button>
            </div>
            <button
              className="bg-amber-600 hover:bg-amber-500 text-white rounded px-4 py-1.5 text-sm font-medium transition flex items-center gap-2"
              onClick={() => { setShowResubmitModal(true); setResubmitResults(null) }}
            >
              <Send size={14} /> Resubmit via Fallback SMSC
            </button>
          </div>
          {/* Gmail-style "Select all results" banner */}
          {!allPagesSelected && selectedIds.size === logs.length && totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 py-1.5 bg-brand-500/5 rounded border border-brand-500/10">
              <span className="text-xs text-slate-400">
                All {logs.length} messages on this page are selected.
              </span>
              <button
                className="text-xs font-semibold text-brand-400 hover:text-brand-300 underline underline-offset-2 flex items-center gap-1"
                onClick={selectAllPages}
                disabled={loadingAllIds}
              >
                <Layers size={12} />
                {loadingAllIds ? 'Loading...' : `Select all ${totalElements.toLocaleString()} matching results`}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Filters Toolbar */}
      <form onSubmit={e => { e.preventDefault(); applyFilters() }} className="glass p-4 flex flex-wrap gap-3 items-end">
        <div className="min-w-[140px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Status</label>
          <select className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                  value={filters.status} onChange={e => setFilters({ ...filters, status: e.target.value })}>
            <option value="">All</option>
            <option value="RECEIVED">RECEIVED</option>
            <option value="DISPATCHED">DISPATCHED</option>
            <option value="DISPATCHED_TO_RCS">DISPATCHED TO RCS</option>
            <option value="DELIVERED">DELIVERED</option>
            <option value="RCS_FAILED">RCS FAILED</option>
            <option value="FAILED">FAILED</option>
          </select>
        </div>
        <div className="min-w-[210px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">From Time</label>
          <input type="datetime-local" className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 style={{ colorScheme: 'dark' }}
                 value={filters.startDate} onChange={e => setFilters({ ...filters, startDate: e.target.value })} />
        </div>
        <div className="min-w-[210px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">To Time</label>
          <input type="datetime-local" className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 style={{ colorScheme: 'dark' }}
                 value={filters.endDate} onChange={e => setFilters({ ...filters, endDate: e.target.value })} />
        </div>
        <div className="min-w-[140px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">From (Sender)</label>
          <input className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 placeholder="e.g. Info" value={filters.senderId} onChange={e => setFilters({ ...filters, senderId: e.target.value })} />
        </div>
        <div className="min-w-[140px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Destination</label>
          <input className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 placeholder="e.g. +3069..." value={filters.destinationNumber} onChange={e => setFilters({ ...filters, destinationNumber: e.target.value })} />
        </div>
        <div className="min-w-[140px] flex-1">
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Device Group</label>
          <select className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                  value={filters.deviceGroupId} onChange={e => setFilters({ ...filters, deviceGroupId: e.target.value })}>
            <option value="">All Groups</option>
            {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Device</label>
          <select className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                  value={filters.deviceId} onChange={e => setFilters({ ...filters, deviceId: e.target.value })}>
            <option value="">All Devices</option>
            {devices.map((d: Device) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Client Msg ID</label>
          <input className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 placeholder="Correlation ID" value={filters.clientMessageId} onChange={e => setFilters({ ...filters, clientMessageId: e.target.value })} />
        </div>
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Supplier Msg ID</label>
          <input className="w-full bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5"
                 placeholder="Provider ID" value={filters.supplierMessageId} onChange={e => setFilters({ ...filters, supplierMessageId: e.target.value })} />
        </div>
        <div className="flex gap-2 min-w-[240px]">
          <button type="submit" className="bg-brand-600 hover:bg-brand-500 text-white flex-1 rounded text-sm py-1.5 px-6 font-medium transition flex justify-center items-center gap-2">
            <Search size={14} /> Search
          </button>
          <button type="button" onClick={clearFilters} className="bg-slate-800 hover:bg-slate-700 text-slate-300 px-4 rounded text-sm font-medium transition flex justify-center items-center gap-2" title="Clear Filters">
            <X size={14} /> Clear
          </button>
        </div>
      </form>

      {/* Main Table */}
      <div className="glass overflow-x-auto">
        {/* Top pagination */}
        {totalPages > 1 ? (
          <div className="flex items-center gap-1.5 justify-end px-4 pt-3 pb-1">
            <button className="text-[10px] font-medium text-slate-400 hover:text-white bg-slate-800/60 hover:bg-slate-700 rounded px-2 py-0.5 transition disabled:opacity-30" disabled={page === 0} onClick={() => setPage(0)}>«</button>
            <button className="text-[10px] font-medium text-slate-400 hover:text-white bg-slate-800/60 hover:bg-slate-700 rounded px-2 py-0.5 transition disabled:opacity-30" disabled={page === 0} onClick={() => setPage(p => p - 1)}>←</button>
            <span className="text-[10px] text-slate-500">{page + 1}/{totalPages}</span>
            <button className="text-[10px] font-medium text-slate-400 hover:text-white bg-slate-800/60 hover:bg-slate-700 rounded px-2 py-0.5 transition disabled:opacity-30" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>→</button>
            <button className="text-[10px] font-medium text-slate-400 hover:text-white bg-slate-800/60 hover:bg-slate-700 rounded px-2 py-0.5 transition disabled:opacity-30" disabled={page >= totalPages - 1} onClick={() => setPage(totalPages - 1)}>»</button>
            <span className="text-[10px] text-slate-600 ml-1">({totalElements.toLocaleString()})</span>
            <select className="text-[10px] bg-slate-800/60 text-slate-400 border border-white/5 rounded px-1 py-0.5 cursor-pointer ml-1" value={perPage} onChange={e => { setPerPage(Number(e.target.value)); setPage(0) }}>
              <option value={10}>10/pg</option>
              <option value={50}>50/pg</option>
              <option value={100}>100/pg</option>
              <option value={500}>500/pg</option>
            </select>
          </div>
        ) : totalElements > 0 && (
          <div className="flex items-center justify-end gap-1.5 px-4 pt-3 pb-1">
            <span className="text-[10px] text-slate-600">({totalElements.toLocaleString()} results)</span>
            <select className="text-[10px] bg-slate-800/60 text-slate-400 border border-white/5 rounded px-1 py-0.5 cursor-pointer ml-1" value={perPage} onChange={e => { setPerPage(Number(e.target.value)); setPage(0) }}>
              <option value={10}>10/pg</option>
              <option value={50}>50/pg</option>
              <option value={100}>100/pg</option>
              <option value={500}>500/pg</option>
            </select>
          </div>
        )}
        <table className="tbl">
          <thead className="sticky top-0 z-10 bg-[#12121f] shadow-[0_1px_0_0_rgba(255,255,255,0.05)]">
          <tr>
            <th className="px-2 pt-4 pb-3 w-8">
              <input type="checkbox" className="accent-brand-500 cursor-pointer"
                checked={logs.length > 0 && selectedIds.size === logs.length}
                onChange={toggleSelectAll} />
            </th>
            <th className="px-4 cursor-pointer select-none hover:text-brand-400 transition" onClick={() => toggleSort('id')}># <SortIcon field="id" /></th>
            <th className="px-4 cursor-pointer select-none hover:text-brand-400 transition" onClick={() => toggleSort('createdAt')}>Timestamp <SortIcon field="createdAt" /></th>
            <th className="px-2">Time</th>
            <th className="px-4 cursor-pointer select-none hover:text-brand-400 transition" onClick={() => toggleSort('sourceAddress')}>From <SortIcon field="sourceAddress" /></th>
            <th className="px-4 cursor-pointer select-none hover:text-brand-400 transition" onClick={() => toggleSort('destinationAddress')}>To <SortIcon field="destinationAddress" /></th>
            <th className="px-4">Message</th>
            <th className="px-4">Status</th>
            <th className="px-4">Route</th>
            <th className="px-4">Status Details</th>
          </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={10} className="px-4 py-8 text-center text-slate-500">Loading…</td></tr>
            )}
            {logs.map(l => (
              <tr key={l.id} className={`cursor-pointer hover:bg-white/[0.02] ${selectedIds.has(l.id) ? 'bg-brand-500/5' : ''}`} onClick={() => setSelectedLog(l)}>
                <td className="px-2 py-3" onClick={e => e.stopPropagation()}>
                  <input type="checkbox" className="accent-brand-500 cursor-pointer"
                    checked={selectedIds.has(l.id)}
                    onChange={() => toggleSelect(l.id)} />
                </td>
                <td className="px-4 py-3 text-xs font-mono text-slate-500">{l.id}</td>
                <td className="px-4 py-3 text-xs text-slate-500">
                  <div><span className="text-[10px] uppercase text-slate-600 font-bold inline-block mr-1 w-5">In</span> {format(new Date(l.createdAt), 'MMM d, HH:mm:ss')}</div>
                  {l.fallbackStartedAt && (
                    <div className="mt-0.5"><span className="text-[10px] uppercase text-amber-600/70 font-bold inline-block mr-1 w-5">Out</span> {format(new Date(l.fallbackStartedAt), 'MMM d, HH:mm:ss')}</div>
                  )}
                </td>
                <td className="px-2 py-3">
                  {(() => {
                    const startTime = l.createdAt;
                    const endTime = l.rcsDlrReceivedAt || (l.status === 'DELIVERED' && l.fallbackStartedAt ? l.fallbackStartedAt : null);
                    if (!endTime || !startTime) return null;
                    const diffSec = (new Date(endTime).getTime() - new Date(startTime).getTime()) / 1000;
                    if (diffSec < 0) return null;
                    const label = diffSec >= 60 ? `${Math.floor(diffSec / 60)}m ${Math.round(diffSec % 60)}s` : `${diffSec.toFixed(1)}s`;
                    const color = diffSec <= 30 ? 'text-emerald-400 bg-emerald-500/10 border-emerald-500/15' : diffSec <= 60 ? 'text-orange-400 bg-orange-500/10 border-orange-500/15' : 'text-red-400 bg-red-500/10 border-red-500/15';
                    return <span className={`inline-flex items-center gap-1 text-[10px] font-semibold rounded px-1.5 py-0.5 border ${color}`}>⏱ {label}</span>;
                  })()}
                </td>
                <td className="px-4 py-3 text-sm font-mono text-slate-200">{l.sourceAddress ?? '—'}</td>
                <td className="px-4 py-3 text-sm font-mono text-slate-200">{l.destinationAddress ?? '—'}</td>
                <td className="px-4 py-3 text-sm text-slate-400 max-w-[200px] truncate" title={l.messageText}>
                  {l.messageText ?? '—'}
                </td>
                <td className="px-4 py-3"><span className={`pill ${l.status === 'DELIVERED' && l.fallbackStartedAt ? 'pill-green border-amber-500/30 object-contained' : l.status === 'DISPATCHED' && l.rcsSentAt ? 'bg-amber-500/15 text-amber-400 border border-amber-500/30' : statusClass(l.status)}`}>{l.status === 'DELIVERED' && l.fallbackStartedAt ? 'DELIVERED (FALLBACK)' : l.status === 'DISPATCHED' && l.rcsSentAt ? 'DISPATCHED TO RCS' : l.status}</span></td>
                <td className="px-4 py-3 text-xs text-slate-400">
                  {l.fallbackStartedAt && l.deviceGroup && l.fallbackSmsc ? (
                    <div className="flex flex-col items-start gap-1">
                      <div className="flex items-center gap-1.5">
                        <span className="text-slate-300">{l.deviceGroup.name}</span>
                        <span className="text-slate-600">→</span>
                        <span className="font-bold text-amber-500">{l.fallbackSmsc.name}</span>
                      </div>
                      <span className="text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-400 border border-amber-500/30 uppercase">SMPP Fallback</span>
                    </div>
                  ) : l.device || l.deviceGroup ? (
                    <div className="flex flex-col items-start gap-1">
                      <div className="font-medium text-slate-300">{l.device?.name || l.deviceGroup?.name}</div>
                      {l.device && <div className="text-[10px] text-slate-500 leading-none">
                        {l.device.simCards && l.device.simCards.length > 0 ? (l.device.simCards[0].phoneNumber || `SIM: ${l.device.simCards[0].iccid?.slice(0,8)}`) : (l.device.hardwareId ?? 'No ID')}
                      </div>}
                      <span className={`text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded uppercase border ${l.routingMode === 'MATRIX' ? 'bg-purple-500/20 text-purple-400 border-purple-500/30' : 'bg-brand-500/20 text-brand-400 border-brand-500/30'}`}>
                        {l.routingMode || 'WEBSOCKET'}
                        {l.routingMode !== 'MATRIX' && l.device?.simCards && l.device.simCards.length > 0 ? (
                           l.device.simCards[0].slotIndex !== undefined && l.device.simCards[0].slotIndex !== -1 
                           ? ` (SIM ${l.device.simCards[0].slotIndex + 1})` : ` (eSIM)`
                        ) : ''}
                      </span>
                    </div>
                  ) : l.fallbackSmsc ? (
                    <div className="flex flex-col items-start gap-1">
                      <div className="font-bold text-amber-500">{l.fallbackSmsc.name} (Direct)</div>
                      <span className="text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-400 border border-amber-500/30 uppercase">DIRECT SMPP</span>
                    </div>
                  ) : (
                    <span className="text-slate-600">Pending</span>
                  )}
                </td>
                <td className={`px-4 py-3 text-xs max-w-[150px] truncate ${l.errorDetail && (l.status === 'FAILED' || l.status === 'RCS_FAILED') ? 'text-red-400' : l.errorDetail === 'SEEN/READ' ? 'text-emerald-400' : 'text-slate-400'}`} title={l.errorDetail}>
                  {l.errorDetail ?? ''}
                </td>
              </tr>
            ))}
            {!isLoading && logs.length === 0 && (
              <tr><td colSpan={10} className="px-4 py-12 text-center text-slate-500">No matching messages found</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 ? (
        <div className="flex items-center gap-2 justify-center">
          <button className="btn-secondary !px-3" disabled={page === 0} onClick={() => setPage(0)}>«</button>
          <button className="btn-secondary !px-3" disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
          <span className="text-slate-400 text-sm">Page {page + 1} of {totalPages}</span>
          <button className="btn-secondary !px-3" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next →</button>
          <button className="btn-secondary !px-3" disabled={page >= totalPages - 1} onClick={() => setPage(totalPages - 1)}>»</button>
          <span className="text-slate-500 text-xs ml-2">({totalElements.toLocaleString()} results)</span>
          <select className="text-xs bg-[#12121f] text-slate-400 border border-white/10 rounded px-2 py-1 cursor-pointer ml-1" value={perPage} onChange={e => { setPerPage(Number(e.target.value)); setPage(0) }}>
            <option value={10}>10 / page</option>
            <option value={50}>50 / page</option>
            <option value={100}>100 / page</option>
            <option value={500}>500 / page</option>
          </select>
        </div>
      ) : totalElements > 0 && (
        <div className="flex items-center justify-center gap-2">
          <span className="text-slate-500 text-xs">({totalElements.toLocaleString()} results)</span>
          <select className="text-xs bg-[#12121f] text-slate-400 border border-white/10 rounded px-2 py-1 cursor-pointer" value={perPage} onChange={e => { setPerPage(Number(e.target.value)); setPage(0) }}>
            <option value={10}>10 / page</option>
            <option value={50}>50 / page</option>
            <option value={100}>100 / page</option>
            <option value={500}>500 / page</option>
          </select>
        </div>
      )}

      {/* Modal View for Detailed Message */}
      {selectedLog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" onClick={() => setSelectedLog(null)}>
          <div className="bg-[#12121f] border border-white/10 rounded-xl shadow-2xl max-w-2xl w-full flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>
            <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between">
              <h2 className="text-lg font-bold text-white flex items-center gap-2"><Info size={18} className="text-brand-400" /> Message Details</h2>
              <button onClick={() => setSelectedLog(null)} className="text-slate-500 hover:text-white transition"><X size={20} /></button>
            </div>
            <div className="p-6 overflow-y-auto max-h-[70vh] space-y-6">
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-1">
                  <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Log ID</div>
                  <div className="text-sm font-mono text-slate-300">{selectedLog.id}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Status</div>
                  <div className={`text-sm font-bold ${selectedLog.status === 'FAILED' || selectedLog.status === 'RCS_FAILED' ? 'text-red-400' : 'text-emerald-400'}`}>{selectedLog.status}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">From</div>
                  <div className="text-sm font-mono text-slate-300">{selectedLog.sourceAddress}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Destination</div>
                  <div className="text-sm font-mono text-slate-300">{selectedLog.destinationAddress}</div>
                </div>
              </div>

              <div className="space-y-1">
                 <div className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Message Content</div>
                 <div className="text-sm text-slate-300 bg-white/5 p-3 rounded border border-white/5 whitespace-pre-wrap">{selectedLog.messageText}</div>
              </div>

              <div className={`grid grid-cols-1 md:grid-cols-${selectedLog.fallbackMessageId ? '3' : '2'} gap-4`}>
                <div className="space-y-1 bg-brand-900/10 p-3 rounded border border-brand-500/10">
                  <div className="text-[10px] font-bold text-brand-500 uppercase tracking-wider">Customer Message ID (Correlation)</div>
                  <div className="text-xs font-mono text-brand-300 break-all">{selectedLog.customerMessageId || selectedLog.smppMessageId || 'N/A'}</div>
                </div>
                <div className="space-y-1 bg-teal-900/10 p-3 rounded border border-teal-500/10">
                  <div className="flex items-center justify-between pb-1 border-b border-white/5 mb-1.5">
                    <div className="text-[10px] font-bold text-teal-500 uppercase tracking-wider">Primary Matrix/Supplier ID</div>
                    <div className="text-[10px] font-bold text-slate-400">Matrix Server / Main</div>
                  </div>
                  <div className="text-xs font-mono text-teal-300 break-all">{selectedLog.supplierMessageId || 'N/A'}</div>
                </div>
                {selectedLog.fallbackMessageId && (
                  <div className="space-y-1 bg-amber-900/10 p-3 rounded border border-amber-500/10">
                    <div className="flex items-center justify-between pb-1 border-b border-white/5 mb-1.5">
                      <div className="text-[10px] font-bold text-amber-500 uppercase tracking-wider">Fallback SMSC Msg ID</div>
                      <div className="text-[10px] font-bold text-slate-400">{selectedLog.fallbackSmsc?.name || 'Unknown Provider'}</div>
                    </div>
                    <div className="text-xs font-mono text-amber-300 break-all">{selectedLog.fallbackMessageId}</div>
                  </div>
                )}
              </div>

              {/* Timestamps Section */}
              <div className="space-y-2">
                <div className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Event Timeline</div>
                <div className="bg-[#1a1a2e] border border-white/5 rounded overflow-hidden">
                   <div className="grid grid-cols-2 text-xs divide-x divide-y divide-white/5">
                      <div className="p-2 text-slate-400">1. Received by Platform</div>
                      <div className="p-2 font-mono text-slate-300">{format(new Date(selectedLog.createdAt), 'MMM d, yyyy HH:mm:ss')}</div>

                      <div className="p-2 text-slate-400">2. Dispatched to RCS/Device Group</div>
                      <div className="p-2 font-mono text-slate-300">{selectedLog.dispatchedAt ? format(new Date(selectedLog.dispatchedAt), 'MMM d, yyyy HH:mm:ss') : '—'}</div>

                      <div className="p-2 text-teal-400">3. Dispatched to RCS Network</div>
                      <div className="p-2 font-mono text-teal-300">{selectedLog.rcsSentAt ? format(new Date(selectedLog.rcsSentAt), 'MMM d, yyyy HH:mm:ss') : '—'}</div>

                      <div className="p-2 text-slate-400">4. Final DLR from RCS Network</div>
                      <div className="p-2 font-mono text-slate-300">{selectedLog.rcsDlrReceivedAt ? format(new Date(selectedLog.rcsDlrReceivedAt), 'MMM d, yyyy HH:mm:ss') : '—'}</div>

                      <div className="p-2 text-amber-500/80">5. Fallback Dispatched to SMSC</div>
                      <div className="p-2 font-mono text-amber-400/80">{selectedLog.fallbackStartedAt ? format(new Date(selectedLog.fallbackStartedAt), 'MMM d, yyyy HH:mm:ss') : '—'}</div>
                      
                      <div className="p-2 text-amber-500/80">6. Final DLR from Fallback SMSC</div>
                      <div className="p-2 font-mono text-amber-400/80">{selectedLog.fallbackDlrReceivedAt ? format(new Date(selectedLog.fallbackDlrReceivedAt), 'MMM d, yyyy HH:mm:ss') : '—'}</div>
                   </div>
                </div>
              </div>

              {(selectedLog.errorDetail || selectedLog.status === 'FAILED' || selectedLog.status === 'RCS_FAILED') && (
                <div className={`space-y-1 p-3 rounded border ${selectedLog.status === 'FAILED' || selectedLog.status === 'RCS_FAILED' ? 'bg-red-900/10 border-red-500/10' : selectedLog.errorDetail === 'SEEN/READ' ? 'bg-emerald-900/10 border-emerald-500/10' : 'bg-slate-900/30 border-white/5'}`}>
                  <div className={`text-[10px] font-bold uppercase tracking-wider ${selectedLog.status === 'FAILED' || selectedLog.status === 'RCS_FAILED' ? 'text-red-500' : selectedLog.errorDetail === 'SEEN/READ' ? 'text-emerald-500' : 'text-slate-400'}`}>Status Details</div>
                  <div className={`text-sm font-mono whitespace-pre-wrap leading-relaxed ${selectedLog.status === 'FAILED' || selectedLog.status === 'RCS_FAILED' ? 'text-red-300' : selectedLog.errorDetail === 'SEEN/READ' ? 'text-emerald-300' : 'text-slate-300'}`}>{selectedLog.errorDetail || 'Silent failure (No error details captured)'}</div>
                </div>
              )}
            </div>
            <div className="px-6 py-4 border-t border-white/5 bg-black/20 flex justify-end">
              <button onClick={() => setSelectedLog(null)} className="btn-secondary">Close</button>
            </div>
          </div>
        </div>
      )}

      {/* Resubmit Modal */}
      {showResubmitModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" onClick={() => setShowResubmitModal(false)}>
          <div className="bg-[#12121f] border border-white/10 rounded-xl shadow-2xl max-w-lg w-full" onClick={e => e.stopPropagation()}>
            <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between">
              <h2 className="text-lg font-bold text-white flex items-center gap-2"><Send size={18} className="text-amber-400" /> Resubmit Messages</h2>
              <button onClick={() => setShowResubmitModal(false)} className="text-slate-500 hover:text-white transition"><X size={20} /></button>
            </div>
            <div className="p-6 space-y-4">
              {!resubmitResults ? (
                <>
                  <p className="text-sm text-slate-400">
                    Resubmit <strong className="text-white">{selectedIds.size}</strong> message{selectedIds.size > 1 ? 's' : ''} through a fallback SMSC supplier.
                    Original customer message IDs will be preserved.
                  </p>
                  <div>
                    <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Fallback SMSC Supplier</label>
                    <select
                      className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                      value={resubmitSmscId ?? ''}
                      onChange={e => setResubmitSmscId(Number(e.target.value))}
                    >
                      <option value="">Select SMSC…</option>
                      {smscSuppliers.map((s: SmscSupplier) => (
                        <option key={s.supplier.id} value={s.supplier.id}>
                          {s.supplier.name} ({s.connected ? '🟢 Connected' : '🔴 Disconnected'})
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="flex justify-end gap-3 pt-2">
                    <button className="btn-secondary" onClick={() => setShowResubmitModal(false)}>Cancel</button>
                    <button
                      className="bg-amber-600 hover:bg-amber-500 text-white rounded px-4 py-1.5 text-sm font-medium transition disabled:opacity-40"
                      disabled={!resubmitSmscId || resubmitMutation.isPending}
                      onClick={handleResubmit}
                    >
                      {resubmitMutation.isPending ? 'Submitting…' : `Resubmit ${selectedIds.size} Messages`}
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <p className="text-sm text-slate-400 mb-3">Resubmission Results:</p>
                  <div className="max-h-60 overflow-y-auto space-y-1">
                    {resubmitResults.map((r: any, i: number) => (
                      <div key={i} className={`text-xs font-mono px-3 py-1.5 rounded ${r.status === 'OK' ? 'bg-emerald-900/20 text-emerald-400' : 'bg-red-900/20 text-red-400'}`}>
                        #{r.originalId} → {r.status === 'OK' ? `✅ New ID: ${r.newId}` : `❌ ${r.error}`}
                      </div>
                    ))}
                  </div>
                  <div className="flex justify-end pt-2">
                    <button className="btn-secondary" onClick={() => { setShowResubmitModal(false); clearSelection(); setResubmitResults(null) }}>Done</button>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
