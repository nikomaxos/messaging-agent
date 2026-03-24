import { useState, useEffect, useRef, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDevices, getDeviceScreenshot, sendTap, sendSwipe, sendKeyEvent, connectDeviceAdb, wakeDevice } from '../api/client'
import { Device } from '../types'
import { Monitor, Maximize2, Minimize2, Home, ArrowLeft, LayoutGrid, RefreshCw, Wifi, WifiOff, ChevronDown, ChevronUp, Smartphone, Power, Sun } from 'lucide-react'

/**
 * Given an <img> with object-fit: contain, calculate the actual rendered image
 * rectangle within the element (excluding letterbox padding).
 */
function getRenderedImageRect(img: HTMLImageElement) {
  const natW = img.naturalWidth
  const natH = img.naturalHeight
  if (!natW || !natH) return null

  const elemW = img.clientWidth
  const elemH = img.clientHeight

  const scale = Math.min(elemW / natW, elemH / natH)
  const renderedW = natW * scale
  const renderedH = natH * scale

  // object-fit: contain centers the image
  const offsetX = (elemW - renderedW) / 2
  const offsetY = (elemH - renderedH) / 2

  return { offsetX, offsetY, width: renderedW, height: renderedH }
}

/**
 * Convert a mouse event position to image-relative coordinates (0-1 normalized),
 * accounting for object-fit: contain letterboxing.
 * Returns null if the click is outside the actual image area.
 */
function mouseToImageCoords(e: React.MouseEvent<HTMLImageElement>, img: HTMLImageElement) {
  const rect = img.getBoundingClientRect()
  const clickX = e.clientX - rect.left
  const clickY = e.clientY - rect.top

  const rendered = getRenderedImageRect(img)
  if (!rendered) return null

  // Translate to image-local coordinates
  const imgX = clickX - rendered.offsetX
  const imgY = clickY - rendered.offsetY

  // Check if click is within the actual image area
  if (imgX < 0 || imgY < 0 || imgX > rendered.width || imgY > rendered.height) {
    return null // clicked on letterbox padding
  }

  // Normalize to 0-1 range
  return {
    nx: imgX / rendered.width,
    ny: imgY / rendered.height,
  }
}

/**
 * Individual device screen viewer with live screenshot polling and touch interaction.
 */
function DeviceScreen({ device, expanded, onToggleExpand, refreshInterval }: {
  device: Device
  expanded: boolean
  onToggleExpand: () => void
  refreshInterval: number
}) {
  const [imgSrc, setImgSrc] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [connected, setConnected] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [tapIndicator, setTapIndicator] = useState<{x: number, y: number} | null>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [swipeStart, setSwipeStart] = useState<{ x: number; y: number; clientX: number; clientY: number } | null>(null)

  // Track whether component is mounted to avoid state updates after unmount
  const mountedRef = useRef(true)
  const pollingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  // Connect ADB on mount
  useEffect(() => {
    mountedRef.current = true
    const connect = async () => {
      setConnecting(true)
      try {
        const res = await connectDeviceAdb(device.id)
        if (mountedRef.current) setConnected(res.connected)
      } catch {
        if (mountedRef.current) setConnected(false)
      }
      if (mountedRef.current) setConnecting(false)
    }
    connect()
    return () => { mountedRef.current = false }
  }, [device.id])

  // Sequential screenshot fetch — only starts next poll AFTER current one completes
  const fetchScreenshot = useCallback(async () => {
    if (!connected || !mountedRef.current) return
    // Cancel any in-flight request
    if (abortRef.current) abortRef.current.abort()
    abortRef.current = new AbortController()
    try {
      const blob = await getDeviceScreenshot(device.id)
      if (!mountedRef.current) return
      const url = URL.createObjectURL(blob)
      setImgSrc(prev => {
        if (prev) URL.revokeObjectURL(prev)
        return url
      })
      setLoading(false)
      setError(null)
    } catch (e: any) {
      if (!mountedRef.current) return
      setError(e.message || 'Failed')
      setLoading(false)
    }
  }, [device.id, connected])

  // Sequential polling: fetch → wait → fetch → wait (no overlapping requests)
  useEffect(() => {
    if (!connected) return

    let stopped = false

    const poll = async () => {
      if (stopped) return
      await fetchScreenshot()
      if (stopped) return
      // Schedule next poll AFTER this one finishes
      pollingTimerRef.current = setTimeout(poll, refreshInterval)
    }

    poll()

    return () => {
      stopped = true
      if (pollingTimerRef.current) clearTimeout(pollingTimerRef.current)
      if (abortRef.current) abortRef.current.abort()
    }
  }, [connected, refreshInterval, fetchScreenshot])

  // Cleanup URL on unmount
  useEffect(() => {
    return () => {
      if (imgSrc) URL.revokeObjectURL(imgSrc)
    }
  }, [])

  // Handle click/tap on image — uses normalized coordinates (accounts for object-contain)
  const doTap = async (e: React.MouseEvent<HTMLImageElement>) => {
    const img = imgRef.current
    if (!img) return
    const coords = mouseToImageCoords(e, img)
    if (!coords) return // clicked on letterbox padding

    // Show visual tap indicator
    const rect = img.getBoundingClientRect()
    setTapIndicator({ x: e.clientX - rect.left, y: e.clientY - rect.top })
    setTimeout(() => setTapIndicator(null), 400)

    // Send normalized coords — backend will multiply by device resolution
    try {
      await sendTap(device.id, coords.nx, coords.ny, 1, 1)
      setTimeout(fetchScreenshot, 300)
    } catch (err) {
      console.error('Tap failed:', err)
    }
  }

  // Handle swipe via mouse drag
  const handleMouseDown = (e: React.MouseEvent<HTMLImageElement>) => {
    const img = imgRef.current
    if (!img) return
    const rect = img.getBoundingClientRect()
    setSwipeStart({
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
      clientX: e.clientX,
      clientY: e.clientY,
    })
  }

  const handleMouseUp = async (e: React.MouseEvent<HTMLImageElement>) => {
    if (!swipeStart) return
    const img = imgRef.current
    if (!img) return

    const dx = Math.abs(e.clientX - swipeStart.clientX)
    const dy = Math.abs(e.clientY - swipeStart.clientY)

    // Only count as swipe if moved more than 20px
    if (dx > 20 || dy > 20) {
      const startCoords = mouseToImageCoords(
        { clientX: swipeStart.clientX, clientY: swipeStart.clientY } as any,
        img
      )
      const endCoords = mouseToImageCoords(e, img)

      if (startCoords && endCoords) {
        try {
          await sendSwipe(
            device.id,
            startCoords.nx, startCoords.ny,
            endCoords.nx, endCoords.ny,
            1, 1, // normalized
            300
          )
          setTimeout(fetchScreenshot, 300)
        } catch (err) {
          console.error('Swipe failed:', err)
        }
      }
    } else {
      // It's a click, not a swipe
      doTap(e)
    }
    setSwipeStart(null)
  }

  // Key events
  const handleKey = async (keycode: number) => {
    try {
      await sendKeyEvent(device.id, keycode)
      setTimeout(fetchScreenshot, 500)
    } catch (err) {
      console.error('KeyEvent failed:', err)
    }
  }

  // Wake + unlock
  const handleWake = async () => {
    try {
      await wakeDevice(device.id)
      setTimeout(fetchScreenshot, 1200)
    } catch (err) {
      console.error('Wake failed:', err)
    }
  }

  const statusColor = device.status === 'ONLINE' ? 'bg-emerald-500' : device.status === 'BUSY' ? 'bg-amber-500' : 'bg-slate-600'

  return (
    <div className={`flex flex-col rounded-xl border border-white/[0.08] bg-[#1a1a2e] overflow-hidden transition-all ${expanded ? 'col-span-full row-span-2' : ''}`}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-white/[0.06] bg-[#14142a]">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className={`w-2.5 h-2.5 rounded-full ${statusColor} shrink-0`} />
          <Smartphone size={14} className="text-slate-400 shrink-0" />
          <span className="text-sm font-medium text-slate-200 truncate">{device.name || `Device ${device.id}`}</span>
        </div>
        <div className="flex items-center gap-1.5">
          {connected ? (
            <Wifi size={14} className="text-emerald-400" />
          ) : (
            <WifiOff size={14} className="text-red-400" />
          )}
          <button
            onClick={onToggleExpand}
            className="p-1 rounded hover:bg-white/[0.08] text-slate-400 hover:text-white transition"
            title={expanded ? 'Minimize' : 'Maximize'}
          >
            {expanded ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
        </div>
      </div>

      {/* Screen area */}
      <div
        ref={containerRef}
        className={`flex-1 flex items-center justify-center bg-black/50 overflow-hidden relative ${expanded ? 'min-h-[600px]' : 'min-h-[300px]'}`}
      >
        {connecting && (
          <div className="flex flex-col items-center gap-2 text-slate-400">
            <RefreshCw size={20} className="animate-spin" />
            <span className="text-xs">Connecting ADB...</span>
          </div>
        )}
        {!connecting && !connected && (
          <div className="flex flex-col items-center gap-2 text-slate-500">
            <WifiOff size={24} />
            <span className="text-xs">ADB not connected</span>
            <button
              onClick={async () => {
                setConnecting(true)
                try {
                  const res = await connectDeviceAdb(device.id)
                  setConnected(res.connected)
                } catch { /* ignore */ }
                setConnecting(false)
              }}
              className="mt-1 px-3 py-1 text-xs bg-brand-600/30 text-brand-400 rounded-lg hover:bg-brand-600/50 transition"
            >
              Retry
            </button>
          </div>
        )}
        {connected && loading && !imgSrc && (
          <div className="flex flex-col items-center gap-2 text-slate-400">
            <Monitor size={20} className="animate-pulse" />
            <span className="text-xs">Loading screen...</span>
          </div>
        )}
        {connected && error && !imgSrc && (
          <div className="flex flex-col items-center gap-2 text-red-400">
            <Monitor size={20} />
            <span className="text-xs">{error}</span>
          </div>
        )}
        {imgSrc && (
          <>
            <img
              ref={imgRef}
              src={imgSrc}
              alt={`${device.name} screen`}
              className={`object-contain cursor-crosshair select-none ${expanded ? 'max-h-[600px]' : 'max-h-[300px]'} w-auto`}
              draggable={false}
              onMouseDown={handleMouseDown}
              onMouseUp={handleMouseUp}
            />
            {/* Tap ripple indicator */}
            {tapIndicator && (
              <div
                className="absolute pointer-events-none"
                style={{
                  left: tapIndicator.x - 15,
                  top: tapIndicator.y - 15,
                  width: 30,
                  height: 30,
                  borderRadius: '50%',
                  border: '2px solid rgba(139, 92, 246, 0.8)',
                  background: 'rgba(139, 92, 246, 0.3)',
                  animation: 'ping 0.4s ease-out forwards',
                }}
              />
            )}
          </>
        )}
      </div>

      {/* Controls */}
      <div className="flex items-center justify-center gap-2 px-3 py-2 border-t border-white/[0.06] bg-[#14142a]">
        <button onClick={handleWake} className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-emerald-600/20 hover:bg-emerald-600/40 text-emerald-300 hover:text-white text-xs transition" title="Wake screen & unlock">
          <Sun size={12} /> Wake
        </button>
        <button onClick={() => handleKey(4)} className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-white/[0.06] hover:bg-white/[0.12] text-slate-300 hover:text-white text-xs transition" title="Back">
          <ArrowLeft size={12} /> Back
        </button>
        <button onClick={() => handleKey(3)} className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-white/[0.06] hover:bg-white/[0.12] text-slate-300 hover:text-white text-xs transition" title="Home">
          <Home size={12} /> Home
        </button>
        <button onClick={() => handleKey(187)} className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-white/[0.06] hover:bg-white/[0.12] text-slate-300 hover:text-white text-xs transition" title="Recent Apps">
          <LayoutGrid size={12} /> Recent
        </button>
        <button onClick={fetchScreenshot} className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-white/[0.06] hover:bg-white/[0.12] text-slate-300 hover:text-white text-xs transition" title="Refresh">
          <RefreshCw size={12} />
        </button>
      </div>
    </div>
  )
}

/**
 * Remote Desktop page — grid of live device screens.
 */
export default function RemoteDesktopPage() {
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [refreshInterval, setRefreshInterval] = useState(1000)
  const [showSettings, setShowSettings] = useState(false)

  const { data: devices = [] } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: 15_000,
  })

  // Filter to only devices with an ADB WiFi address
  const adbDevices = (devices as Device[]).filter((d: Device) => d.adbWifiAddress && d.adbWifiAddress.trim() !== '')

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
            Live screen mirroring and remote control for {adbDevices.length} device{adbDevices.length !== 1 ? 's' : ''}
          </p>
        </div>

        {/* Settings toggle */}
        <button
          onClick={() => setShowSettings(!showSettings)}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-300 text-sm transition"
        >
          Settings {showSettings ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
      </div>

      {/* Settings panel */}
      {showSettings && (
        <div className="rounded-xl border border-white/[0.08] bg-[#1a1a2e] p-4">
          <div className="flex items-center gap-4">
            <label className="text-sm text-slate-300">Refresh rate:</label>
            <input
              type="range"
              min={300}
              max={3000}
              step={100}
              value={refreshInterval}
              onChange={e => setRefreshInterval(Number(e.target.value))}
              className="w-48 accent-brand-500"
            />
            <span className="text-sm text-slate-400 w-16">{refreshInterval}ms</span>
          </div>
        </div>
      )}

      {/* No devices */}
      {adbDevices.length === 0 && (
        <div className="flex flex-col items-center justify-center py-20 text-slate-500">
          <Monitor size={48} className="mb-4 opacity-30" />
          <p className="text-lg font-medium">No devices with ADB WiFi address</p>
          <p className="text-sm mt-1">Configure WiFi ADB addresses on the Devices page first.</p>
        </div>
      )}

      {/* Device grid */}
      <div className={`grid gap-4 ${expandedId
        ? 'grid-cols-1'
        : adbDevices.length <= 2
          ? 'grid-cols-1 sm:grid-cols-2'
          : adbDevices.length <= 4
            ? 'grid-cols-2 lg:grid-cols-2'
            : 'grid-cols-2 lg:grid-cols-3 xl:grid-cols-4'
      }`}>
        {adbDevices.map((device: Device) => {
          if (expandedId !== null && expandedId !== device.id) return null
          return (
            <DeviceScreen
              key={device.id}
              device={device}
              expanded={expandedId === device.id}
              onToggleExpand={() => setExpandedId(expandedId === device.id ? null : device.id)}
              refreshInterval={refreshInterval}
            />
          )
        })}
      </div>

      {/* Tap animation keyframes */}
      <style>{`
        @keyframes ping {
          0% { transform: scale(0.5); opacity: 1; }
          100% { transform: scale(2); opacity: 0; }
        }
      `}</style>
    </div>
  )
}
