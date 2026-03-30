import { useState, useEffect, useRef, useCallback } from 'react'
import { Wifi, WifiOff, Activity, RefreshCw, Filter, ToggleLeft, ToggleRight } from 'lucide-react'
import { format, formatDistanceToNow } from 'date-fns'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

interface ConnEvent {
  id: number
  time: Date
  level: 'INFO' | 'WARN' | 'ERROR'
  device: string
  event: string
  detail?: string
}

const levelColor: Record<string, string> = {
  INFO:  'text-emerald-400',
  WARN:  'text-yellow-400',
  ERROR: 'text-red-400',
}

let eventIdSeq = 0

export default function SystemLogsPage() {
  const [connEvents, setConnEvents] = useState<ConnEvent[]>([])
  const [wsConnected, setWsConnected] = useState(false)
  
  // Filters
  const [autoUpdate, setAutoUpdate] = useState(false)
  const [levelFilter, setLevelFilter] = useState('ALL')
  const [fromTime, setFromTime] = useState('')
  const [toTime, setToTime] = useState('')
  
  // Pagination
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const stompRef = useRef<Client | null>(null)
  const refreshingRef = useRef(false)

  const addEvent = useCallback((level: ConnEvent['level'], device: string, event: string, detail?: string, time: Date = new Date(), forceAppend = false) => {
    // Determine if we should append. If forceAppend is false, respect autoUpdate.
    if (!forceAppend && (!autoUpdate || toTime || currentPage !== 0)) return;
    
    // Respect level filter
    if (!forceAppend && levelFilter !== 'ALL' && levelFilter !== level) return;

    setConnEvents(prev => [
      { id: ++eventIdSeq, time, level, device, event, detail },
      ...prev.slice(0, 199),
    ])
  }, [autoUpdate, levelFilter, toTime, currentPage])

  const fetchLogs = async () => {
    try {
      refreshingRef.current = true
      const token = localStorage.getItem('jwt')
      const url = new URL('/api/admin/system-logs', window.location.origin)
      if (levelFilter !== 'ALL') url.searchParams.append('level', levelFilter)
      
      // Convert local datetime-local to ISO for backend (assumes standard behavior)
      if (fromTime) url.searchParams.append('startTime', new Date(fromTime).toISOString())
      if (toTime) url.searchParams.append('endTime', new Date(toTime).toISOString())
      url.searchParams.append('page', currentPage.toString())
      url.searchParams.append('size', '100')

      const res = await fetch(url.toString(), {
        headers: { Authorization: `Bearer ${token}` }
      })
      if (!res.ok) throw new Error('Failed to fetch logs')
      
      const data = await res.json()
      const fetched: ConnEvent[] = data.content.map((log: any) => ({
        id: log.id,
        time: new Date(log.createdAt),
        level: log.level,
        device: log.device,
        event: log.event,
        detail: log.detail
      }))
      setConnEvents(fetched)
      setTotalPages(data.totalPages || 1)
    } catch (e) {
      console.error(e)
    } finally {
      refreshingRef.current = false
    }
  }

  // Initial fetch and dependency fetch
  useEffect(() => {
    fetchLogs()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  // STOMP connection
  useEffect(() => {
    const token = localStorage.getItem('jwt')
    if (!token) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws-admin'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        setWsConnected(true)
        // Device status events
        client.subscribe('/topic/devices', msg => {
          try {
            const d = JSON.parse(msg.body)
            if (d.status === 'ONLINE' || d.status === 'OFFLINE') return
            addEvent('INFO', d.name ?? 'Unknown', `Device update`, JSON.stringify(d), new Date(), false)
          } catch (_) {}
        })

        // Structured log events
        client.subscribe('/topic/logs', msg => {
          try {
            const e = JSON.parse(msg.body)
            addEvent(e.level ?? 'INFO', e.device ?? 'System', e.event ?? '', e.detail, new Date(), false)
          } catch (_) {}
        })
      },
      onDisconnect: () => setWsConnected(false),
      onStompError: frame => {
        setWsConnected(false)
        console.error('STOMP Error:', frame.headers['message'])
      },
    })

    client.activate()
    stompRef.current = client
    return () => { client.deactivate() }
  }, [addEvent])

  const handleApplyFilters = () => {
    if (currentPage !== 0) {
      setCurrentPage(0) // this will trigger fetch
    } else {
      fetchLogs()
    }
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-white">System Logs</h1>
            <p className="text-slate-400 text-sm mt-0.5 flex items-center gap-1.5">
              {wsConnected
                ? <><Wifi size={12} className="text-emerald-400" /> WebSocket is connected</>
                : <><WifiOff size={12} className="text-amber-400" /> WebSocket reconnecting…</>}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <button
              className={`flex items-center gap-2 px-3 py-1.5 rounded text-sm transition-colors ${
                autoUpdate ? 'bg-indigo-500/20 text-indigo-300' : 'bg-slate-800 text-slate-400 hover:text-white'
              }`}
              onClick={() => setAutoUpdate(!autoUpdate)}
            >
              {autoUpdate ? <ToggleRight size={16} /> : <ToggleLeft size={16} />}
              Auto Refresh
            </button>
            <button className="btn-secondary" onClick={handleApplyFilters}>
              <RefreshCw size={14} className={refreshingRef.current ? 'animate-spin' : ''} /> Refresh
            </button>
            <button className="btn-secondary" onClick={() => setConnEvents([])}>
              Clear
            </button>
          </div>
        </div>

        {/* Filters Bar */}
        <div className="glass p-3 flex flex-wrap items-center gap-4 text-sm">
          <div className="flex items-center gap-2 text-slate-300">
            <Filter size={14} className="text-slate-500" /> Filters:
          </div>
          
          <div className="flex items-center gap-2">
            <label className="text-slate-500">Level</label>
            <select
              className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-slate-200 outline-none focus:border-indigo-500"
              value={levelFilter}
              onChange={e => setLevelFilter(e.target.value)}
            >
              <option value="ALL">ALL</option>
              <option value="INFO">INFO</option>
              <option value="WARN">WARN</option>
              <option value="ERROR">ERROR</option>
            </select>
          </div>

          <div className="flex items-center gap-2">
            <label className="text-slate-500">From</label>
            <input
              type="datetime-local"
              className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-slate-200 outline-none focus:border-indigo-500"
              value={fromTime}
              onChange={e => setFromTime(e.target.value)}
            />
          </div>

          <div className="flex items-center gap-2">
            <label className="text-slate-500">To</label>
            <input
              type="datetime-local"
              className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-slate-200 outline-none focus:border-indigo-500"
              value={toTime}
              onChange={e => setToTime(e.target.value)}
            />
          </div>
          
          <button className="btn-primary py-1 px-3 text-sm ml-auto" onClick={handleApplyFilters}>
            Apply Filters
          </button>
        </div>
      </div>

      <div className="glass overflow-hidden">
        {connEvents.length === 0 ? (
          <div className="p-8 text-center text-slate-500 flex flex-col items-center justify-center min-h-[400px]">
            <Activity size={32} className="text-slate-700 mb-4" />
            No logs found matching your criteria.
          </div>
        ) : (
          <div className="overflow-y-auto max-h-[calc(100vh-250px)]">
            <table className="tbl w-full">
              <thead>
                <tr>
                  <th className="px-4 pt-4 pb-3 text-left">Time</th>
                  <th className="px-4 text-left">Level</th>
                  <th className="px-4 text-left">Device</th>
                  <th className="px-4 text-left">Event</th>
                  <th className="px-4 text-left">Detail</th>
                </tr>
              </thead>
              <tbody>
                {connEvents.map(e => (
                  <tr key={e.id} className="border-t border-slate-800/50 hover:bg-white/[0.02] transition">
                    <td className="px-4 py-3 text-xs font-mono text-slate-500 whitespace-nowrap">
                      {format(e.time, 'MMM d, HH:mm:ss')}
                      <div className="text-[10px] text-slate-600">{formatDistanceToNow(e.time, { addSuffix: true })}</div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs font-bold font-mono ${levelColor[e.level] ?? 'text-slate-400'}`}>
                        {e.level}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm text-slate-200 font-medium">{e.device}</td>
                    <td className="px-4 py-3 text-sm text-slate-300">{e.event}</td>
                    <td className="px-4 py-3 text-xs text-slate-500 max-w-[400px] truncate" title={e.detail}>
                      {e.detail ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      
      {/* Pagination Controls */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between mt-4 text-sm text-slate-400">
          <div>
            Page {currentPage + 1} of {totalPages}
          </div>
          <div className="flex items-center gap-2">
            <button
              className="btn-secondary py-1 px-3"
              disabled={currentPage === 0}
              onClick={() => setCurrentPage((p: number) => Math.max(0, p - 1))}
            >
              Previous
            </button>
            <button
              className="btn-secondary py-1 px-3"
              disabled={currentPage >= totalPages - 1}
              onClick={() => setCurrentPage((p: number) => Math.min(totalPages - 1, p + 1))}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
