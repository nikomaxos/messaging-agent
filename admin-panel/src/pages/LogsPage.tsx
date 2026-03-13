import { useState, useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getLogs } from '../api/client'
import { MessageLog } from '../types'
import { RefreshCw, Wifi, WifiOff, Activity, MessageSquare } from 'lucide-react'
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

let eventIdSeq = 0

const statusClass = (s: MessageLog['status']) => ({
  RECEIVED:  'pill-gray',
  DISPATCHED:'pill-yellow',
  DELIVERED: 'pill-green',
  RCS_FAILED:'pill-red',
  FAILED:    'pill-red',
}[s])

const levelColor: Record<string, string> = {
  INFO:  'text-emerald-400',
  WARN:  'text-yellow-400',
  ERROR: 'text-red-400',
}

export default function LogsPage() {
  const [tab, setTab] = useState<'messages' | 'connection'>('connection')
  const [page, setPage] = useState(0)
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['logs', page],
    queryFn: () => getLogs(page),
    refetchInterval: 10000,
    placeholderData: (prev: any) => prev,
  })
  const logs: MessageLog[] = data?.content ?? []
  const totalPages: number = data?.totalPages ?? 0

  // Connection Events via WebSocket /topic/devices + /topic/logs
  const [connEvents, setConnEvents] = useState<ConnEvent[]>([])
  const [wsConnected, setWsConnected] = useState(false)
  const stompRef = useRef<Client | null>(null)

  const addEvent = (level: ConnEvent['level'], device: string, event: string, detail?: string) => {
    setConnEvents(prev => [
      { id: ++eventIdSeq, time: new Date(), level, device, event, detail },
      ...prev.slice(0, 199),   // keep last 200
    ])
  }

  useEffect(() => {
    const token = localStorage.getItem('jwt')
    if (!token) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws-admin'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        setWsConnected(true)
        addEvent('INFO', 'System', 'Admin WebSocket connected')

        // Device status events
        client.subscribe('/topic/devices', msg => {
          try {
            const d = JSON.parse(msg.body)
            const level = d.status === 'ONLINE' ? 'INFO' : 'WARN'
            addEvent(level, d.name ?? 'Unknown', `Device ${d.status}`,
              d.lastHeartbeat ? `last heartbeat: ${d.lastHeartbeat}` : undefined)
          } catch (_) {}
        })

        // Structured log events from backend
        client.subscribe('/topic/logs', msg => {
          try {
            const e = JSON.parse(msg.body)
            addEvent(e.level ?? 'INFO', e.device ?? 'System', e.event ?? '', e.detail)
          } catch (_) {}
        })
      },
      onDisconnect: () => {
        setWsConnected(false)
        addEvent('WARN', 'System', 'Admin WebSocket disconnected')
      },
      onStompError: frame => {
        setWsConnected(false)
        addEvent('ERROR', 'System', 'STOMP error', frame.headers['message'])
      },
    })

    client.activate()
    stompRef.current = client
    return () => { client.deactivate() }
  }, [])

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Logs</h1>
          <p className="text-slate-400 text-sm mt-0.5 flex items-center gap-1.5">
            {wsConnected
              ? <><Wifi size={12} className="text-emerald-400" /> Live — WebSocket connected</>
              : <><WifiOff size={12} className="text-amber-400" /> WebSocket reconnecting…</>}
          </p>
        </div>
        {tab === 'messages' && (
          <button className="btn-secondary" onClick={() => refetch()}>
            <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
          </button>
        )}
        {tab === 'connection' && (
          <button className="btn-secondary" onClick={() => setConnEvents([])}>
            Clear
          </button>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-slate-900/60 p-1 rounded-lg w-fit">
        <button
          className={`flex items-center gap-2 px-4 py-1.5 rounded-md text-sm transition-all ${tab === 'connection' ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-slate-200'}`}
          onClick={() => setTab('connection')}
        >
          <Activity size={14} /> Connection Events
          {connEvents.length > 0 && (
            <span className="text-xs bg-emerald-900 text-emerald-400 px-1.5 rounded-full">{connEvents.length}</span>
          )}
        </button>
        <button
          className={`flex items-center gap-2 px-4 py-1.5 rounded-md text-sm transition-all ${tab === 'messages' ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-slate-200'}`}
          onClick={() => setTab('messages')}
        >
          <MessageSquare size={14} /> Message Logs
        </button>
      </div>

      {/* Connection Events tab */}
      {tab === 'connection' && (
        <div className="glass overflow-hidden">
          {connEvents.length === 0 ? (
            <div className="p-8 text-center text-slate-500">
              No connection events yet — waiting for device activity…
            </div>
          ) : (
            <div className="overflow-y-auto max-h-[calc(100vh-280px)]">
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
                    <tr key={e.id} className="border-t border-slate-800/50">
                      <td className="px-4 py-2 text-xs font-mono text-slate-500 whitespace-nowrap">
                        {format(e.time, 'HH:mm:ss.SSS')}
                        <div className="text-[10px] text-slate-600">{formatDistanceToNow(e.time, { addSuffix: true })}</div>
                      </td>
                      <td className="px-4 py-2">
                        <span className={`text-xs font-bold font-mono ${levelColor[e.level] ?? 'text-slate-400'}`}>
                          {e.level}
                        </span>
                      </td>
                      <td className="px-4 py-2 text-sm text-slate-200 font-medium">{e.device}</td>
                      <td className="px-4 py-2 text-sm text-slate-300">{e.event}</td>
                      <td className="px-4 py-2 text-xs text-slate-500 max-w-[300px] truncate" title={e.detail}>
                        {e.detail ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Message Logs tab */}
      {tab === 'messages' && (
        <>
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

          {totalPages > 1 && (
            <div className="flex items-center gap-2 justify-center">
              <button className="btn-secondary !px-3" disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
              <span className="text-slate-400 text-sm">Page {page + 1} of {totalPages}</span>
              <button className="btn-secondary !px-3" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Next →</button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
