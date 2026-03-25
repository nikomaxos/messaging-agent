import { useState, useEffect, useRef, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDevices, startScreenStream, stopScreenStream, sendRemoteInput, sendRemoteWake, sendShellCommand } from '../api/client'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

/* ─── types ─────────────────────────────────────────────────── */
interface Device { id: number; name: string; status: string }
interface ShellEntry { type: 'cmd' | 'out'; text: string; ts: number }

/* ─── main page ─────────────────────────────────────────────── */
export default function RemoteDesktopPage() {
  const { data: devices = [] } = useQuery<Device[]>({ queryKey: ['devices'], queryFn: getDevices })
  const [selected, setSelected] = useState<Device | null>(null)

  return (
    <div style={{ display: 'flex', gap: 24, height: 'calc(100vh - 80px)', padding: 24 }}>
      {/* sidebar */}
      <div style={{
        width: 260, background: 'var(--card)', borderRadius: 12, padding: 16,
        border: '1px solid var(--border)', overflow: 'auto',
      }}>
        <h3 style={{ margin: '0 0 16px', fontSize: 15, color: 'var(--text-secondary)' }}>Devices</h3>
        {devices.map((d: Device) => (
          <button
            key={d.id}
            onClick={() => setSelected(d)}
            style={{
              display: 'flex', alignItems: 'center', gap: 10, width: '100%',
              padding: '10px 12px', borderRadius: 8, border: 'none', cursor: 'pointer',
              background: selected?.id === d.id ? 'var(--primary)' : 'transparent',
              color: selected?.id === d.id ? '#fff' : 'var(--text)',
              marginBottom: 4, textAlign: 'left', fontSize: 14,
            }}
          >
            <span style={{
              width: 8, height: 8, borderRadius: '50%',
              background: d.status === 'ONLINE' ? '#22c55e' : '#ef4444',
            }} />
            {d.name}
          </button>
        ))}
      </div>

      {/* main area */}
      {selected ? (
        <DeviceView device={selected} key={selected.id} />
      ) : (
        <div style={{
          flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: 'var(--text-secondary)', fontSize: 16,
        }}>
          Select a device to start remote desktop
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
  const imgRef = useRef<HTMLImageElement>(null)
  const shellEndRef = useRef<HTMLDivElement>(null)
  const stompRef = useRef<Client | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval>>()
  const frameCount = useRef(0)
  const fpsInterval = useRef<ReturnType<typeof setInterval>>()

  // drag state
  const [dragStart, setDragStart] = useState<{ x: number; y: number } | null>(null)
  const [dragEnd, setDragEnd] = useState<{ x: number; y: number } | null>(null)
  const [tapIndicator, setTapIndicator] = useState<{ x: number; y: number } | null>(null)

  // ── Frame polling via HTTP (guaranteed delivery, no STOMP issues) ──
  useEffect(() => {
    // FPS counter
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
      let running = true
      const poll = async () => {
        if (!running) return
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
      pollRef.current = setInterval(poll, 300)
      poll() // immediate first fetch
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

  // ── STOMP for shell output only (small text payloads — works fine) ──
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

  // scroll shell to bottom
  useEffect(() => {
    shellEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [shellEntries])

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
    if (c) setDragStart({ x: c.x, y: c.y })
  }

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!dragStart) return
    const c = getImgCoords(e)
    if (c) setDragEnd({ x: c.x, y: c.y })
  }

  const handleMouseUp = (e: React.MouseEvent) => {
    const c = getImgCoords(e)
    if (!c || !dragStart) { setDragStart(null); setDragEnd(null); return }
    const img = imgRef.current!
    const rect = img.getBoundingClientRect()

    const dx = c.x - dragStart.x
    const dy = c.y - dragStart.y
    const dist = Math.sqrt(dx * dx + dy * dy)

    if (dist < 10) {
      // tap
      sendRemoteInput(device.id, {
        type: 'tap', x: dragStart.x, y: dragStart.y,
        screenWidth: rect.width, screenHeight: rect.height,
      })
      setTapIndicator({ x: dragStart.x, y: dragStart.y })
      setTimeout(() => setTapIndicator(null), 400)
    } else {
      // swipe
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

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 12, minWidth: 0 }}>
      {/* toolbar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
        background: 'var(--card)', borderRadius: 12, padding: '10px 16px',
        border: '1px solid var(--border)',
      }}>
        <h3 style={{ margin: 0, fontSize: 15, flex: 1, minWidth: 200 }}>
          📱 {device.name}
          <span style={{
            marginLeft: 8, fontSize: 12, color: 'var(--text-secondary)',
            background: 'var(--bg)', padding: '2px 8px', borderRadius: 6,
          }}>
            {streaming ? `${fps} FPS` : 'Stopped'}
          </span>
        </h3>
        <ToolBtn onClick={toggleStream} active={streaming}>
          {streaming ? '⏸ Pause' : '▶ Stream'}
        </ToolBtn>
        <ToolBtn onClick={handleWake}>☀ Wake</ToolBtn>
        <ToolBtn onClick={() => handleKey(3)}>🏠 Home</ToolBtn>
        <ToolBtn onClick={() => handleKey(4)}>◀ Back</ToolBtn>
        <ToolBtn onClick={() => handleKey(187)}>⧉ Recent</ToolBtn>
        <ToolBtn onClick={() => setShowShell(!showShell)} active={showShell}>
          &gt;_ Shell
        </ToolBtn>
      </div>

      <div style={{ flex: 1, display: 'flex', gap: 12, overflow: 'hidden', minHeight: 0 }}>
        {/* screen */}
        <div style={{
          flex: showShell ? '0 0 50%' : '1',
          display: 'flex', justifyContent: 'center', alignItems: 'flex-start',
          overflow: 'auto', position: 'relative',
          background: '#000', borderRadius: 12,
        }}>
          {frame ? (
            <div style={{ position: 'relative', display: 'inline-block' }}>
              <img
                ref={imgRef}
                src={frame}
                alt="screen"
                draggable={false}
                onMouseDown={handleMouseDown}
                onMouseMove={handleMouseMove}
                onMouseUp={handleMouseUp}
                style={{
                  maxHeight: 'calc(100vh - 200px)', maxWidth: '100%',
                  objectFit: 'contain', cursor: 'crosshair', userSelect: 'none',
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
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              height: '100%', width: '100%', color: '#888', fontSize: 15,
            }}>
              {streaming ? 'Waiting for frames…' : 'Click ▶ Stream to start'}
            </div>
          )}
        </div>

        {/* shell */}
        {showShell && (
          <div style={{
            flex: '0 0 50%', display: 'flex', flexDirection: 'column',
            background: '#0d1117', borderRadius: 12, border: '1px solid #30363d',
            overflow: 'hidden',
          }}>
            <div style={{
              padding: '8px 14px', borderBottom: '1px solid #30363d',
              color: '#8b949e', fontSize: 13, fontFamily: 'monospace',
            }}>
              🔧 Remote Shell — {device.name}
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
function ToolBtn({ children, onClick, active }: {
  children: React.ReactNode; onClick?: () => void; active?: boolean
}) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: '6px 12px', borderRadius: 8, fontSize: 13, cursor: 'pointer',
        border: '1px solid var(--border)',
        background: active ? 'var(--primary)' : 'var(--bg)',
        color: active ? '#fff' : 'var(--text)',
      }}
    >
      {children}
    </button>
  )
}
