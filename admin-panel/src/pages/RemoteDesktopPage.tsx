import { useState, useEffect, useRef, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDevices, startScreenStream, stopScreenStream, sendRemoteInput, sendRemoteWake, sendShellCommand } from '../api/client'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

/* ─── types ─────────────────────────────────────────────────── */
interface DeviceGroup { id: number; name: string }
interface Device {
  id: number; name: string; status: string
  group?: DeviceGroup | null
  batteryPercent?: number; isCharging?: boolean
  apkVersion?: string; phoneNumber?: string
  networkOperator?: string
}
interface ShellEntry { type: 'cmd' | 'out'; text: string; ts: number }

/* ─── phone icon SVG ────────────────────────────────────────── */
function PhoneIcon({ online, battery, charging }: {
  online: boolean; battery?: number; charging?: boolean
}) {
  const batteryColor = !battery ? '#666'
    : battery > 60 ? '#22c55e'
    : battery > 20 ? '#f59e0b'
    : '#ef4444'

  return (
    <div style={{
      width: 48, height: 72, borderRadius: 8,
      border: `2px solid ${online ? '#22c55e' : '#555'}`,
      background: online
        ? 'linear-gradient(145deg, rgba(34,197,94,0.12), rgba(34,197,94,0.04))'
        : 'rgba(255,255,255,0.03)',
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', position: 'relative', flexShrink: 0,
      transition: 'all 0.2s ease',
    }}>
      {/* Notch */}
      <div style={{
        position: 'absolute', top: 4, width: 16, height: 3,
        borderRadius: 2, background: online ? '#22c55e40' : '#ffffff15',
      }} />
      {/* Battery indicator */}
      <div style={{
        width: 18, height: 8, borderRadius: 2, marginTop: 8,
        background: '#ffffff10', border: '1px solid #ffffff15',
        position: 'relative', overflow: 'hidden',
      }}>
        <div style={{
          position: 'absolute', left: 0, top: 0, bottom: 0,
          width: `${battery ?? 0}%`, background: batteryColor,
          transition: 'width 0.3s ease',
        }} />
      </div>
      {/* Battery % */}
      <span style={{
        fontSize: 9, color: 'var(--text-secondary)', marginTop: 2,
        fontWeight: 600,
      }}>
        {battery != null ? `${battery}%` : '—'}
        {charging ? '⚡' : ''}
      </span>
      {/* Status dot */}
      <div style={{
        width: 8, height: 8, borderRadius: '50%', marginTop: 4,
        background: online ? '#22c55e' : '#ef4444',
        boxShadow: online ? '0 0 8px #22c55e80' : 'none',
      }} />
      {/* Home button */}
      <div style={{
        position: 'absolute', bottom: 4, width: 10, height: 10,
        borderRadius: '50%', border: '1px solid #ffffff20',
      }} />
    </div>
  )
}

/* ─── main page ─────────────────────────────────────────────── */
export default function RemoteDesktopPage() {
  const { data: devices = [] } = useQuery<Device[]>({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: 5000,
  })
  const [selected, setSelected] = useState<Device | null>(null)
  const [groupFilter, setGroupFilter] = useState<string>('all')

  // Extract unique group names
  const groups = Array.from(
    new Map(
      devices
        .filter((d: Device) => d.group)
        .map((d: Device) => [d.group!.id, d.group!.name])
    ).entries()
  ).map(([id, name]) => ({ id, name }))

  // Filter devices
  const filteredDevices = groupFilter === 'all'
    ? devices
    : groupFilter === 'ungrouped'
      ? devices.filter((d: Device) => !d.group)
      : devices.filter((d: Device) => d.group?.id === Number(groupFilter))

  // Sort: online first, then alphabetical
  const sortedDevices = [...filteredDevices].sort((a: Device, b: Device) => {
    if (a.status === 'ONLINE' && b.status !== 'ONLINE') return -1
    if (a.status !== 'ONLINE' && b.status === 'ONLINE') return 1
    return a.name.localeCompare(b.name)
  })

  return (
    <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 80px)', padding: '16px 24px' }}>
      {/* sidebar */}
      <div style={{
        width: 240, background: 'var(--card)', borderRadius: 14, padding: 14,
        border: '1px solid var(--border)', display: 'flex', flexDirection: 'column',
        overflow: 'hidden', flexShrink: 0,
      }}>
        {/* Group filter */}
        <select
          value={groupFilter}
          onChange={e => setGroupFilter(e.target.value)}
          style={{
            width: '100%', padding: '8px 10px', borderRadius: 8,
            background: 'var(--bg)', color: 'var(--text)',
            border: '1px solid var(--border)', fontSize: 13,
            marginBottom: 12, cursor: 'pointer', outline: 'none',
          }}
        >
          <option value="all">All Groups ({devices.length})</option>
          {groups.map(g => {
            const count = devices.filter((d: Device) => d.group?.id === g.id).length
            return <option key={g.id} value={g.id}>{g.name} ({count})</option>
          })}
          {devices.some((d: Device) => !d.group) && (
            <option value="ungrouped">
              Ungrouped ({devices.filter((d: Device) => !d.group).length})
            </option>
          )}
        </select>

        {/* Device list */}
        <div style={{ flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {sortedDevices.map((d: Device) => {
            const isSelected = selected?.id === d.id
            const shortName = d.name.split(' - ')[0]  // e.g. "Redmi note 9 pro"
            return (
              <button
                key={d.id}
                onClick={() => setSelected(d)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 12, width: '100%',
                  padding: '10px 10px', borderRadius: 10, border: 'none', cursor: 'pointer',
                  background: isSelected
                    ? 'linear-gradient(135deg, var(--primary), #6366f1)'
                    : 'transparent',
                  color: isSelected ? '#fff' : 'var(--text)',
                  textAlign: 'left', fontSize: 13, transition: 'all 0.15s ease',
                }}
                onMouseEnter={e => {
                  if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.05)'
                }}
                onMouseLeave={e => {
                  if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'transparent'
                }}
              >
                <PhoneIcon
                  online={d.status === 'ONLINE'}
                  battery={d.batteryPercent}
                  charging={d.isCharging}
                />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{
                    fontWeight: 600, fontSize: 13,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>
                    {shortName}
                  </div>
                  <div style={{
                    fontSize: 11, opacity: 0.6, marginTop: 2,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>
                    {d.group?.name ?? 'No group'} • v{d.apkVersion ?? '?'}
                  </div>
                  {d.phoneNumber && (
                    <div style={{
                      fontSize: 10, opacity: 0.4, marginTop: 1,
                      fontFamily: 'monospace',
                    }}>
                      {d.phoneNumber}
                    </div>
                  )}
                </div>
              </button>
            )
          })}
          {sortedDevices.length === 0 && (
            <div style={{
              color: 'var(--text-secondary)', fontSize: 13, textAlign: 'center',
              padding: 24, opacity: 0.6,
            }}>
              No devices in this group
            </div>
          )}
        </div>

        {/* Stats footer */}
        <div style={{
          borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 10,
          display: 'flex', justifyContent: 'space-between', fontSize: 11,
          color: 'var(--text-secondary)',
        }}>
          <span>🟢 {devices.filter((d: Device) => d.status === 'ONLINE').length} online</span>
          <span>🔴 {devices.filter((d: Device) => d.status !== 'ONLINE').length} offline</span>
        </div>
      </div>

      {/* main area */}
      {selected ? (
        <DeviceView device={selected} key={selected.id} />
      ) : (
        <div style={{
          flex: 1, display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center',
          color: 'var(--text-secondary)', gap: 12,
        }}>
          <div style={{ fontSize: 48, opacity: 0.3 }}>📱</div>
          <div style={{ fontSize: 16 }}>Select a device to start remote desktop</div>
          <div style={{ fontSize: 13, opacity: 0.5 }}>
            Choose a device from the sidebar to stream its screen
          </div>
        </div>
      )}
    </div>
  )
}

/* ─── device view ───────────────────────────────────────────── */
function DeviceView({ device }: { device: Device }) {
  const [frame, setFrame] = useState<string | null>(null)
  const [streaming, setStreaming] = useState(false)
  const [fps, setFps] = useState(0)
  const [shellEntries, setShellEntries] = useState<ShellEntry[]>([])
  const [shellCmd, setShellCmd] = useState('')
  const [showShell, setShowShell] = useState(false)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const imgRef = useRef<HTMLImageElement>(null)
  const screenContainerRef = useRef<HTMLDivElement>(null)
  const shellEndRef = useRef<HTMLDivElement>(null)
  const stompRef = useRef<Client | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval>>()
  const frameCount = useRef(0)
  const fpsInterval = useRef<ReturnType<typeof setInterval>>()

  // drag state
  const [dragStart, setDragStart] = useState<{ x: number; y: number } | null>(null)
  const [dragEnd, setDragEnd] = useState<{ x: number; y: number } | null>(null)
  const [tapIndicator, setTapIndicator] = useState<{ x: number; y: number } | null>(null)
  const longPressTimer = useRef<ReturnType<typeof setTimeout>>()
  const longPressTriggered = useRef(false)

  // ── Frame polling via HTTP ──
  useEffect(() => {
    fpsInterval.current = setInterval(() => {
      setFps(frameCount.current)
      frameCount.current = 0
    }, 1000)

    return () => {
      stopScreenStream(device.id).catch(() => {})
      if (pollRef.current) clearInterval(pollRef.current)
      clearInterval(fpsInterval.current)
    }
  }, [device.id])

  // Start/stop HTTP polling when streaming state changes
  useEffect(() => {
    if (streaming) {
      const token = localStorage.getItem('jwt')
      const poll = async () => {
        try {
          const res = await fetch(`/api/devices/${device.id}/screen-frame`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          if (res.ok) {
            const blob = await res.blob()
            if (blob.size > 0) {
              const url = URL.createObjectURL(blob)
              setFrame(prev => {
                if (prev) URL.revokeObjectURL(prev)
                return url
              })
              frameCount.current++
            }
          }
        } catch { /* ignore fetch errors */ }
      }
      // Poll fast: every 80ms (~12 FPS max on local network)
      pollRef.current = setInterval(poll, 80)
      poll()
    } else {
      if (pollRef.current) {
        clearInterval(pollRef.current)
        pollRef.current = undefined
      }
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current)
        pollRef.current = undefined
      }
    }
  }, [streaming, device.id])

  // ── STOMP for shell output ──
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws-admin'),
      reconnectDelay: 3000,
      debug: () => {},
    })

    client.onConnect = () => {
      client.subscribe(`/topic/shell/${device.id}`, (msg) => {
        try {
          const data = JSON.parse(msg.body)
          const output = (data.output || '').replace(/\\n/g, '\n')
          setShellEntries(prev => [...prev, { type: 'out', text: output, ts: Date.now() }])
        } catch {
          setShellEntries(prev => [...prev, { type: 'out', text: msg.body, ts: Date.now() }])
        }
      })
    }

    client.activate()
    stompRef.current = client
    return () => { client.deactivate() }
  }, [device.id])

  useEffect(() => {
    shellEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [shellEntries])

  // Fullscreen handling
  useEffect(() => {
    const handler = () => {
      if (!document.fullscreenElement) setIsFullscreen(false)
    }
    document.addEventListener('fullscreenchange', handler)
    return () => document.removeEventListener('fullscreenchange', handler)
  }, [])

  const toggleFullscreen = useCallback(() => {
    const el = screenContainerRef.current
    if (!el) return
    if (!document.fullscreenElement) {
      el.requestFullscreen().then(() => setIsFullscreen(true)).catch(() => {})
    } else {
      document.exitFullscreen().then(() => setIsFullscreen(false)).catch(() => {})
    }
  }, [])

  // ── actions ──
  const toggleStream = async () => {
    if (streaming) {
      await stopScreenStream(device.id)
      setStreaming(false)
    } else {
      await startScreenStream(device.id)
      setStreaming(true)
    }
  }

  const handleWake = () => sendRemoteWake(device.id)

  const getImgCoords = (e: React.MouseEvent) => {
    const img = imgRef.current
    if (!img) return null
    const rect = img.getBoundingClientRect()
    return {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
      w: rect.width,
      h: rect.height,
    }
  }

  const handleMouseDown = (e: React.MouseEvent) => {
    const c = getImgCoords(e)
    if (!c) return
    setDragStart({ x: c.x, y: c.y })
    longPressTriggered.current = false
    // Start long press timer (500ms)
    clearTimeout(longPressTimer.current)
    longPressTimer.current = setTimeout(() => {
      const img = imgRef.current
      if (!img) return
      const rect = img.getBoundingClientRect()
      longPressTriggered.current = true
      sendRemoteInput(device.id, {
        type: 'long_press', x: c.x, y: c.y,
        screenWidth: rect.width, screenHeight: rect.height,
      })
      setTapIndicator({ x: c.x, y: c.y })
      setTimeout(() => setTapIndicator(null), 800)
    }, 500)
  }

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!dragStart) return
    const c = getImgCoords(e)
    if (!c) return
    const dx = c.x - dragStart.x
    const dy = c.y - dragStart.y
    // Cancel long press if moved more than 10px
    if (Math.sqrt(dx * dx + dy * dy) > 10) {
      clearTimeout(longPressTimer.current)
    }
    setDragEnd({ x: c.x, y: c.y })
  }

  const handleMouseUp = (e: React.MouseEvent) => {
    clearTimeout(longPressTimer.current)
    const c = getImgCoords(e)
    if (!c || !dragStart) { setDragStart(null); setDragEnd(null); return }
    // If long press already fired, skip tap/swipe
    if (longPressTriggered.current) {
      setDragStart(null); setDragEnd(null); return
    }
    const img = imgRef.current!
    const rect = img.getBoundingClientRect()

    const dx = c.x - dragStart.x
    const dy = c.y - dragStart.y
    const dist = Math.sqrt(dx * dx + dy * dy)

    if (dist < 10) {
      sendRemoteInput(device.id, {
        type: 'tap', x: dragStart.x, y: dragStart.y,
        screenWidth: rect.width, screenHeight: rect.height,
      })
      setTapIndicator({ x: dragStart.x, y: dragStart.y })
      setTimeout(() => setTapIndicator(null), 400)
    } else {
      sendRemoteInput(device.id, {
        type: 'swipe',
        x1: dragStart.x, y1: dragStart.y,
        x2: c.x, y2: c.y,
        screenWidth: rect.width, screenHeight: rect.height,
      })
    }
    setDragStart(null)
    setDragEnd(null)
  }

  const handleKey = (keycode: number) => {
    sendRemoteInput(device.id, { type: 'key', keycode })
  }

  const handleShellSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!shellCmd.trim()) return
    setShellEntries(prev => [...prev, { type: 'cmd', text: shellCmd, ts: Date.now() }])
    sendShellCommand(device.id, shellCmd)
    setShellCmd('')
  }

  const shortName = device.name.split(' - ')[0]

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
      {/* toolbar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
        background: 'var(--card)', borderRadius: 12, padding: '8px 14px',
        border: '1px solid var(--border)',
      }}>
        <div style={{ flex: 1, minWidth: 180, display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 18 }}>📱</span>
          <div>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{shortName}</div>
            <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
              {streaming ? (
                <span style={{ color: '#22c55e' }}>● {fps} FPS</span>
              ) : (
                <span style={{ color: '#888' }}>● Stopped</span>
              )}
              {device.group && <span> • {device.group.name}</span>}
            </div>
          </div>
        </div>
        <ToolBtn onClick={toggleStream} active={streaming}>
          {streaming ? '⏸ Pause' : '▶ Stream'}
        </ToolBtn>
        <ToolBtn onClick={handleWake}>☀ Wake</ToolBtn>
        <ToolBtn onClick={() => handleKey(3)}>🏠</ToolBtn>
        <ToolBtn onClick={() => handleKey(4)}>◀</ToolBtn>
        <ToolBtn onClick={() => handleKey(187)}>⧉</ToolBtn>
        <ToolBtn onClick={toggleFullscreen} title="Toggle fullscreen">
          {isFullscreen ? '⊡' : '⛶'}
        </ToolBtn>
        <ToolBtn onClick={() => setShowShell(!showShell)} active={showShell}>
          &gt;_
        </ToolBtn>
      </div>

      <div style={{ flex: 1, display: 'flex', gap: 10, overflow: 'hidden', minHeight: 0 }}>
        {/* screen — elastic fit */}
        <div
          ref={screenContainerRef}
          style={{
            flex: showShell ? '0 0 55%' : '1',
            display: 'flex', justifyContent: 'center', alignItems: 'center',
            overflow: 'hidden', position: 'relative',
            background: '#0a0a0a', borderRadius: 12,
          }}
        >
          {frame ? (
            <div style={{
              position: 'relative', display: 'inline-block',
              maxWidth: '100%', maxHeight: '100%',
            }}>
              <img
                ref={imgRef}
                src={frame}
                alt="screen"
                draggable={false}
                onMouseDown={handleMouseDown}
                onMouseMove={handleMouseMove}
                onMouseUp={handleMouseUp}
                style={{
                  width: '100%', height: '100%',
                  maxHeight: isFullscreen ? '100vh' : 'calc(100vh - 170px)',
                  maxWidth: '100%',
                  objectFit: 'contain', cursor: 'crosshair', userSelect: 'none',
                  borderRadius: isFullscreen ? 0 : 8,
                }}
              />
              {/* tap indicator */}
              {tapIndicator && (
                <div style={{
                  position: 'absolute',
                  left: tapIndicator.x - 16, top: tapIndicator.y - 16,
                  width: 32, height: 32, borderRadius: '50%',
                  border: '3px solid #3b82f6', background: 'rgba(59,130,246,0.3)',
                  pointerEvents: 'none', animation: 'tapPulse 0.4s ease-out',
                }} />
              )}
              {/* drag trail */}
              {dragStart && dragEnd && (
                <svg style={{
                  position: 'absolute', top: 0, left: 0, width: '100%', height: '100%',
                  pointerEvents: 'none',
                }}>
                  <line
                    x1={dragStart.x} y1={dragStart.y}
                    x2={dragEnd.x} y2={dragEnd.y}
                    stroke="#3b82f6" strokeWidth={3} strokeLinecap="round" opacity={0.7}
                  />
                </svg>
              )}
            </div>
          ) : (
            <div style={{
              display: 'flex', flexDirection: 'column',
              alignItems: 'center', justifyContent: 'center',
              height: '100%', width: '100%', color: '#666', gap: 12,
            }}>
              <div style={{ fontSize: 40, opacity: 0.3 }}>
                {streaming ? '📡' : '🖥️'}
              </div>
              <div style={{ fontSize: 14 }}>
                {streaming ? 'Waiting for frames…' : 'Click ▶ Stream to start'}
              </div>
            </div>
          )}
        </div>

        {/* shell */}
        {showShell && (
          <div style={{
            flex: '0 0 45%', display: 'flex', flexDirection: 'column',
            background: '#0d1117', borderRadius: 12, border: '1px solid #30363d',
            overflow: 'hidden',
          }}>
            <div style={{
              padding: '8px 14px', borderBottom: '1px solid #30363d',
              color: '#8b949e', fontSize: 13, fontFamily: 'monospace',
            }}>
              🔧 Remote Shell — {shortName}
            </div>
            <div style={{
              flex: 1, overflow: 'auto', padding: '8px 14px',
              fontFamily: '"Cascadia Code", "Fira Code", monospace', fontSize: 13,
              lineHeight: 1.6,
            }}>
              {shellEntries.map((e, i) => (
                <div key={i} style={{
                  color: e.type === 'cmd' ? '#58a6ff' : '#c9d1d9',
                  marginBottom: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                }}>
                  {e.type === 'cmd' ? `$ ${e.text}` : e.text}
                </div>
              ))}
              <div ref={shellEndRef} />
            </div>
            <form onSubmit={handleShellSubmit} style={{
              display: 'flex', borderTop: '1px solid #30363d',
            }}>
              <span style={{
                padding: '10px 8px 10px 14px', color: '#58a6ff', fontFamily: 'monospace',
                fontSize: 13, userSelect: 'none',
              }}>$</span>
              <input
                value={shellCmd}
                onChange={e => setShellCmd(e.target.value)}
                placeholder="Type a command…"
                style={{
                  flex: 1, background: 'transparent', border: 'none', outline: 'none',
                  color: '#c9d1d9', fontFamily: '"Cascadia Code", "Fira Code", monospace',
                  fontSize: 13, padding: '10px 14px 10px 4px',
                }}
              />
            </form>
          </div>
        )}
      </div>
    </div>
  )
}

/* ─── button component ──────────────────────────────────────── */
function ToolBtn({ children, onClick, active, title }: {
  children: React.ReactNode; onClick?: () => void; active?: boolean; title?: string
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      style={{
        padding: '6px 12px', borderRadius: 8, fontSize: 13, cursor: 'pointer',
        border: '1px solid var(--border)',
        background: active ? 'var(--primary)' : 'var(--bg)',
        color: active ? '#fff' : 'var(--text)',
        transition: 'all 0.15s ease',
      }}
    >
      {children}
    </button>
  )
}
