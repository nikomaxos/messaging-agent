import { useState, useEffect, useRef, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDevices, getGroups, getScreenshot, sendTap, sendSwipe, sendKeyEvent, wakeDevice, connectAdb } from '../api/client'
import { Device, DeviceGroup } from '../types'
import { Monitor, Smartphone, Battery, BatteryCharging, ArrowLeft, RefreshCw, MousePointer, Home, ArrowDown, Square, Sun, Loader2, AlertCircle, ZapOff } from 'lucide-react'

// ─── Device List View ─────────────────────────────────────────────────────────

function DeviceCard({ device, onClick }: { device: Device; onClick: () => void }) {
  const statusColor = device.status === 'ONLINE' ? 'bg-emerald-500' : device.status === 'BUSY' ? 'bg-amber-500' : 'bg-slate-600'
  const statusGlow = device.status === 'ONLINE' ? 'shadow-emerald-500/30 shadow-lg' : ''

  return (
    <button
      onClick={onClick}
      disabled={device.status !== 'ONLINE'}
      className={`group flex flex-col items-center gap-3 p-5 rounded-xl border transition-all duration-200 ${
        device.status === 'ONLINE'
          ? 'border-white/10 bg-[#1a1a2e] hover:border-brand-500/40 hover:bg-[#1e1e38] cursor-pointer'
          : 'border-white/5 bg-[#14142a] opacity-50 cursor-not-allowed'
      }`}
    >
      {/* Phone Icon */}
      <div className={`relative w-16 h-24 rounded-xl border-2 ${
        device.status === 'ONLINE' ? 'border-slate-500 bg-slate-800' : 'border-slate-700 bg-slate-900'
      } flex items-center justify-center ${statusGlow}`}>
        <Smartphone size={28} className={device.status === 'ONLINE' ? 'text-slate-400 group-hover:text-brand-400 transition' : 'text-slate-700'} />
        {/* Status dot */}
        <div className={`absolute -top-1.5 -right-1.5 w-3.5 h-3.5 rounded-full ${statusColor} border-2 border-[#14142a]`} />
      </div>

      {/* Device Info */}
      <div className="text-center min-w-0 w-full">
        <div className="text-sm font-medium text-slate-200 truncate">{device.name}</div>
        {device.phoneNumber && <div className="text-[10px] text-brand-400 font-medium mt-0.5">{device.phoneNumber}</div>}
        <div className="text-[10px] text-slate-500 mt-0.5">{device.group?.name || 'No Group'}</div>
      </div>

      {/* Status Info */}
      <div className="flex items-center gap-3 text-[10px]">
        <span className={`pill ${device.status === 'ONLINE' ? 'pill-green' : 'pill-gray'}`}>{device.status}</span>
        {device.batteryPercent != null && (
          <span className={`flex items-center gap-0.5 ${device.isCharging ? 'text-green-400' : 'text-slate-400'}`}>
            {device.isCharging ? <BatteryCharging size={10} /> : <Battery size={10} />}
            {device.batteryPercent}%
          </span>
        )}
      </div>

      {device.status === 'ONLINE' && (
        <span className="text-[10px] text-slate-600 group-hover:text-brand-400/60 transition">Click to connect</span>
      )}
    </button>
  )
}

// ─── Interactive Remote Desktop View ──────────────────────────────────────────

function DeviceDetailView({ device, onBack }: { device: Device; onBack: () => void }) {
  const [screenshotUrl, setScreenshotUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshInterval, setRefreshInterval] = useState(1000)
  const [isPaused, setIsPaused] = useState(false)
  const [actionFeedback, setActionFeedback] = useState<string | null>(null)
  const [tapIndicator, setTapIndicator] = useState<{ x: number; y: number } | null>(null)
  const [isConnecting, setIsConnecting] = useState(false)

  // Swipe tracking
  const [dragStart, setDragStart] = useState<{ x: number; y: number } | null>(null)
  const [dragCurrent, setDragCurrent] = useState<{ x: number; y: number } | null>(null)
  const [isDragging, setIsDragging] = useState(false)

  const imgRef = useRef<HTMLImageElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMounted = useRef(true)

  // ── ADB Connect on mount ────────────────────────────────────────────────
  useEffect(() => {
    setIsConnecting(true)
    connectAdb(device.id)
      .then(() => { if (isMounted.current) setIsConnecting(false) })
      .catch(() => { if (isMounted.current) setIsConnecting(false) })
  }, [device.id])

  // ── Screenshot polling ──────────────────────────────────────────────────
  const fetchScreenshot = useCallback(async () => {
    if (!isMounted.current || isPaused) return
    try {
      const blob = await getScreenshot(device.id)
      if (!isMounted.current) return
      const url = URL.createObjectURL(blob)
      setScreenshotUrl(prev => {
        if (prev) URL.revokeObjectURL(prev)
        return url
      })
      setLoading(false)
      setError(null)
    } catch (e: any) {
      if (!isMounted.current) return
      setError(e?.response?.status === 503 ? 'Device screen unavailable — try Wake' : 'Connection error')
      setLoading(false)
    }
    if (isMounted.current && !isPaused) {
      timerRef.current = setTimeout(fetchScreenshot, refreshInterval)
    }
  }, [device.id, refreshInterval, isPaused])

  useEffect(() => {
    isMounted.current = true
    fetchScreenshot()
    return () => {
      isMounted.current = false
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [fetchScreenshot])

  // Cleanup blob URL on unmount
  useEffect(() => {
    return () => {
      if (screenshotUrl) URL.revokeObjectURL(screenshotUrl)
    }
  }, [])

  // ── Action feedback ─────────────────────────────────────────────────────
  const showFeedback = (msg: string) => {
    setActionFeedback(msg)
    setTimeout(() => setActionFeedback(null), 1500)
  }

  // ── Tap handler ─────────────────────────────────────────────────────────
  const handleClick = async (e: React.MouseEvent<HTMLImageElement>) => {
    if (isDragging) return
    const img = imgRef.current
    if (!img) return

    const rect = img.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top

    // Show tap indicator
    setTapIndicator({ x, y })
    setTimeout(() => setTapIndicator(null), 400)

    try {
      await sendTap(device.id, x, y, rect.width, rect.height)
      // Force immediate refresh after tap
      if (timerRef.current) clearTimeout(timerRef.current)
      setTimeout(fetchScreenshot, 300)
    } catch {
      showFeedback('Tap failed')
    }
  }

  // ── Swipe handlers ──────────────────────────────────────────────────────
  const handleMouseDown = (e: React.MouseEvent<HTMLImageElement>) => {
    const img = imgRef.current
    if (!img) return
    const rect = img.getBoundingClientRect()
    setDragStart({ x: e.clientX - rect.left, y: e.clientY - rect.top })
    setIsDragging(false)
  }

  const handleMouseMove = (e: React.MouseEvent<HTMLImageElement>) => {
    if (!dragStart) return
    const img = imgRef.current
    if (!img) return
    const rect = img.getBoundingClientRect()
    const current = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    const dist = Math.sqrt(Math.pow(current.x - dragStart.x, 2) + Math.pow(current.y - dragStart.y, 2))
    if (dist > 15) {
      setIsDragging(true)
      setDragCurrent(current)
    }
  }

  const handleMouseUp = async (e: React.MouseEvent<HTMLImageElement>) => {
    if (!dragStart || !isDragging) {
      setDragStart(null)
      setDragCurrent(null)
      setIsDragging(false)
      return
    }
    const img = imgRef.current
    if (!img) return
    const rect = img.getBoundingClientRect()
    const endX = e.clientX - rect.left
    const endY = e.clientY - rect.top

    try {
      await sendSwipe(device.id, dragStart.x, dragStart.y, endX, endY, rect.width, rect.height)
      if (timerRef.current) clearTimeout(timerRef.current)
      setTimeout(fetchScreenshot, 400)
    } catch {
      showFeedback('Swipe failed')
    }

    setDragStart(null)
    setDragCurrent(null)
    setTimeout(() => setIsDragging(false), 50)
  }

  // ── Key event helpers ───────────────────────────────────────────────────
  const handleKey = async (keycode: number, label: string) => {
    try {
      await sendKeyEvent(device.id, keycode)
      showFeedback(label)
      if (timerRef.current) clearTimeout(timerRef.current)
      setTimeout(fetchScreenshot, 400)
    } catch {
      showFeedback(`${label} failed`)
    }
  }

  const handleWake = async () => {
    try {
      await wakeDevice(device.id)
      showFeedback('Wake sent')
      if (timerRef.current) clearTimeout(timerRef.current)
      setTimeout(fetchScreenshot, 1000)
    } catch {
      showFeedback('Wake failed')
    }
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-300 text-sm transition"
          >
            <ArrowLeft size={14} /> Back
          </button>
          <div>
            <h2 className="text-lg font-bold text-white flex items-center gap-2">
              <Smartphone size={18} className="text-brand-400" />
              {device.name}
            </h2>
            <div className="flex items-center gap-2 mt-0.5">
              {device.phoneNumber && <span className="text-xs text-brand-400">{device.phoneNumber}</span>}
              <span className={`pill text-[10px] ${device.status === 'ONLINE' ? 'pill-green' : 'pill-gray'}`}>{device.status}</span>
            </div>
          </div>
        </div>

        {/* Controls */}
        <div className="flex items-center gap-2">
          {/* Refresh rate */}
          <select
            value={refreshInterval}
            onChange={e => setRefreshInterval(Number(e.target.value))}
            className="bg-[#12121f] text-xs text-white border border-white/5 rounded px-2 py-1.5"
          >
            <option value={500}>0.5s</option>
            <option value={1000}>1s</option>
            <option value={2000}>2s</option>
            <option value={5000}>5s</option>
          </select>

          {/* Pause/Resume */}
          <button
            onClick={() => {
              setIsPaused(p => !p)
              if (isPaused) {
                // Resume
                if (timerRef.current) clearTimeout(timerRef.current)
                setTimeout(fetchScreenshot, 100)
              }
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition ${
              isPaused
                ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                : 'bg-white/[0.06] text-slate-400 hover:text-white border border-white/5'
            }`}
          >
            {isPaused ? 'Resume' : 'Pause'}
          </button>

          {/* Manual refresh */}
          <button
            onClick={() => {
              if (timerRef.current) clearTimeout(timerRef.current)
              fetchScreenshot()
            }}
            className="p-1.5 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Force refresh"
          >
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {/* Action Feedback Toast */}
      {actionFeedback && (
        <div className="fixed top-4 right-4 z-50 px-4 py-2 rounded-xl bg-brand-500/90 text-white text-sm font-medium shadow-xl animate-fade-in">
          {actionFeedback}
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex gap-4 items-start">
        {/* Screen Area */}
        <div
          ref={containerRef}
          className="relative flex-1 flex items-center justify-center rounded-xl border border-white/[0.08] bg-[#0a0a14] min-h-[500px] overflow-hidden select-none"
        >
          {isConnecting && (
            <div className="absolute inset-0 flex items-center justify-center z-20 bg-black/60">
              <div className="flex items-center gap-3 text-slate-400">
                <Loader2 size={20} className="animate-spin" />
                <span className="text-sm">Connecting ADB...</span>
              </div>
            </div>
          )}

          {loading && !screenshotUrl && !error && (
            <div className="flex flex-col items-center justify-center gap-3 py-16">
              <Loader2 size={32} className="animate-spin text-brand-400" />
              <p className="text-sm text-slate-500">Loading screen...</p>
            </div>
          )}

          {error && !screenshotUrl && (
            <div className="flex flex-col items-center justify-center gap-3 py-16">
              <AlertCircle size={32} className="text-amber-500" />
              <p className="text-sm text-slate-400">{error}</p>
              <button
                onClick={handleWake}
                className="flex items-center gap-2 px-4 py-2 rounded-lg bg-brand-500/20 text-brand-400 hover:bg-brand-500/30 text-sm transition"
              >
                <Sun size={14} /> Wake Screen
              </button>
            </div>
          )}

          {screenshotUrl && (
            <>
              <img
                ref={imgRef}
                src={screenshotUrl}
                alt="Device screen"
                className="max-h-[70vh] w-auto rounded-lg cursor-crosshair"
                onClick={handleClick}
                onMouseDown={handleMouseDown}
                onMouseMove={handleMouseMove}
                onMouseUp={handleMouseUp}
                onMouseLeave={() => {
                  setDragStart(null)
                  setDragCurrent(null)
                  setIsDragging(false)
                }}
                draggable={false}
                style={{ imageRendering: 'auto' }}
              />

              {/* Tap indicator */}
              {tapIndicator && imgRef.current && (
                <div
                  className="absolute w-8 h-8 rounded-full border-2 border-brand-400 bg-brand-400/20 animate-ping pointer-events-none"
                  style={{
                    left: imgRef.current.offsetLeft + tapIndicator.x - 16,
                    top: imgRef.current.offsetTop + tapIndicator.y - 16,
                  }}
                />
              )}

              {/* Swipe trail */}
              {isDragging && dragStart && dragCurrent && imgRef.current && (
                <svg
                  className="absolute inset-0 pointer-events-none"
                  style={{ left: imgRef.current.offsetLeft, top: imgRef.current.offsetTop, width: imgRef.current.width, height: imgRef.current.height }}
                >
                  <line
                    x1={dragStart.x} y1={dragStart.y}
                    x2={dragCurrent.x} y2={dragCurrent.y}
                    stroke="rgba(139, 92, 246, 0.7)"
                    strokeWidth="3"
                    strokeLinecap="round"
                    strokeDasharray="8,4"
                  />
                  <circle cx={dragStart.x} cy={dragStart.y} r="5" fill="rgba(139, 92, 246, 0.5)" />
                  <circle cx={dragCurrent.x} cy={dragCurrent.y} r="5" fill="rgba(139, 92, 246, 0.8)" />
                </svg>
              )}
            </>
          )}

          {/* Status bar */}
          <div className="absolute bottom-0 left-0 right-0 flex items-center justify-between px-3 py-1.5 bg-black/60 text-[10px] text-slate-500">
            <span className="flex items-center gap-1">
              <MousePointer size={9} /> Click=Tap, Drag=Swipe
            </span>
            <span>{isPaused ? '⏸ Paused' : `⟳ ${refreshInterval / 1000}s`}</span>
          </div>
        </div>

        {/* Sidebar Controls */}
        <div className="flex flex-col gap-2 w-14">
          {/* Navigation buttons */}
          <button
            onClick={() => handleKey(3, 'Home')}
            className="flex flex-col items-center gap-1 py-3 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Home"
          >
            <Home size={16} />
            <span className="text-[9px]">Home</span>
          </button>

          <button
            onClick={() => handleKey(4, 'Back')}
            className="flex flex-col items-center gap-1 py-3 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Back"
          >
            <ArrowDown size={16} className="rotate-90" />
            <span className="text-[9px]">Back</span>
          </button>

          <button
            onClick={() => handleKey(187, 'Recents')}
            className="flex flex-col items-center gap-1 py-3 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Recent Apps"
          >
            <Square size={16} />
            <span className="text-[9px]">Recents</span>
          </button>

          <div className="border-t border-white/5 my-1" />

          <button
            onClick={handleWake}
            className="flex flex-col items-center gap-1 py-3 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Wake & Unlock"
          >
            <Sun size={16} />
            <span className="text-[9px]">Wake</span>
          </button>

          <button
            onClick={() => handleKey(26, 'Power')}
            className="flex flex-col items-center gap-1 py-3 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-400 hover:text-white transition"
            title="Power button"
          >
            <ZapOff size={16} />
            <span className="text-[9px]">Power</span>
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function RemoteDesktopPage() {
  const [selectedDeviceId, setSelectedDeviceId] = useState<number | null>(null)
  const [filterGroup, setFilterGroup] = useState('')

  const { data: devices = [] } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: 15000,
  })

  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })

  const selectedDevice = (devices as Device[]).find((d: Device) => d.id === selectedDeviceId) || null

  // Filter devices
  const filteredDevices = (devices as Device[]).filter((d: Device) => {
    if (filterGroup && String(d.group?.id ?? '') !== filterGroup) return false
    return true
  })

  // Sort: ONLINE first, then by name
  const sortedDevices = [...filteredDevices].sort((a, b) => {
    if (a.status === 'ONLINE' && b.status !== 'ONLINE') return -1
    if (a.status !== 'ONLINE' && b.status === 'ONLINE') return 1
    return a.name.localeCompare(b.name)
  })

  // If viewing a device detail
  if (selectedDevice) {
    return (
      <div className="space-y-5">
        <DeviceDetailView device={selectedDevice} onBack={() => setSelectedDeviceId(null)} />
      </div>
    )
  }

  const onlineCount = (devices as Device[]).filter((d: Device) => d.status === 'ONLINE').length

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white flex items-center gap-2.5">
            <Monitor size={24} className="text-brand-400" />
            Remote Desktop
          </h1>
          <p className="text-sm text-slate-400 mt-1">
            {onlineCount} of {(devices as Device[]).length} device{(devices as Device[]).length !== 1 ? 's' : ''} online
          </p>
        </div>
      </div>

      {/* Filter */}
      <div className="flex items-center gap-3">
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Device Group</label>
          <select
            className="bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5 min-w-[160px]"
            value={filterGroup}
            onChange={e => setFilterGroup(e.target.value)}
          >
            <option value="">All Groups</option>
            {(groups as DeviceGroup[]).map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
        </div>
        {filterGroup && (
          <button className="text-xs text-slate-400 hover:text-white mt-4" onClick={() => setFilterGroup('')}>Clear</button>
        )}
        <span className="text-xs text-slate-600 mt-4 ml-auto">{sortedDevices.length} devices</span>
      </div>

      {/* Device Grid */}
      {sortedDevices.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-slate-500">
          <Monitor size={48} className="mb-4 opacity-30" />
          <p className="text-lg font-medium">No devices found</p>
          <p className="text-sm mt-1">Register devices on the Devices tab first.</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
          {sortedDevices.map((device: Device) => (
            <DeviceCard
              key={device.id}
              device={device}
              onClick={() => setSelectedDeviceId(device.id)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
