import { useState, useEffect, useRef } from 'react'
import { Wifi, WifiOff, Activity } from 'lucide-react'
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

const levelColor: Record<string, string> = {
  INFO:  'text-emerald-400',
  WARN:  'text-yellow-400',
  ERROR: 'text-red-400',
}

export default function SystemLogsPage() {
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
          <h1 className="text-xl font-bold text-white">System Logs</h1>
          <p className="text-slate-400 text-sm mt-0.5 flex items-center gap-1.5">
            {wsConnected
              ? <><Wifi size={12} className="text-emerald-400" /> Live — WebSocket connected</>
              : <><WifiOff size={12} className="text-amber-400" /> WebSocket reconnecting…</>}
          </p>
        </div>
        <button className="btn-secondary" onClick={() => setConnEvents([])}>
          Clear
        </button>
      </div>

      <div className="glass overflow-hidden">
        {connEvents.length === 0 ? (
          <div className="p-8 text-center text-slate-500 flex flex-col items-center justify-center min-h-[400px]">
            <Activity size={32} className="text-slate-700 mb-4" />
            No connection events yet — waiting for device activity…
          </div>
        ) : (
          <div className="overflow-y-auto max-h-[calc(100vh-200px)]">
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
                      {format(e.time, 'HH:mm:ss.SSS')}
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
    </div>
  )
}
