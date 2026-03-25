import { useState, useRef, useEffect, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getThroughput, getLiveTps } from '../api/client'
import { BarChart3, Activity, Zap, Timer, Clock } from 'lucide-react'

/* ─── Stat card ────────────────────────────────────────────────────────────── */
function TpsCard({ label, value, icon, color }: {
  label: string; value: number | string; icon: React.ReactNode; color: string
}) {
  return (
    <div className="glass p-4 flex items-center gap-4">
      <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
        {icon}
      </div>
      <div>
        <div className="text-2xl font-bold text-white font-mono">{value}</div>
        <div className="text-[11px] text-slate-500">{label}</div>
      </div>
    </div>
  )
}

/* ─── Mini sparkline chart (canvas-based) ──────────────────────────────────── */
function TpsChart({ timeSeries }: { timeSeries: { ts: string; count: number }[] }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  const draw = useCallback(() => {
    const canvas = canvasRef.current
    if (!canvas || timeSeries.length === 0) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = window.devicePixelRatio || 1
    const rect = canvas.getBoundingClientRect()
    canvas.width = rect.width * dpr
    canvas.height = rect.height * dpr
    ctx.scale(dpr, dpr)

    const w = rect.width
    const h = rect.height
    const pad = { top: 30, right: 16, bottom: 40, left: 50 }
    const chartW = w - pad.left - pad.right
    const chartH = h - pad.top - pad.bottom

    ctx.clearRect(0, 0, w, h)

    // Parse data — aggregate into 10-second buckets for better visualization
    const bucketSize = 10_000 // 10 seconds
    const bucketMap = new Map<number, number>()
    const now = Date.now()
    const windowMs = 5 * 60_000 // 5 minutes

    for (const pt of timeSeries) {
      const t = new Date(pt.ts).getTime()
      const bucket = Math.floor(t / bucketSize) * bucketSize
      bucketMap.set(bucket, (bucketMap.get(bucket) ?? 0) + pt.count)
    }

    // Fill in empty buckets
    const startBucket = Math.floor((now - windowMs) / bucketSize) * bucketSize
    const endBucket = Math.floor(now / bucketSize) * bucketSize
    const points: { t: number; v: number }[] = []
    for (let b = startBucket; b <= endBucket; b += bucketSize) {
      points.push({ t: b, v: bucketMap.get(b) ?? 0 })
    }

    const maxV = Math.max(...points.map(p => p.v), 1)
    const minT = points[0]?.t ?? now - windowMs
    const maxT = points[points.length - 1]?.t ?? now

    const x = (t: number) => pad.left + ((t - minT) / (maxT - minT || 1)) * chartW
    const y = (v: number) => pad.top + chartH - (v / maxV) * chartH

    // Grid lines
    ctx.strokeStyle = 'rgba(255,255,255,0.05)'
    ctx.lineWidth = 1
    const gridLines = 4
    for (let i = 0; i <= gridLines; i++) {
      const gy = pad.top + (chartH / gridLines) * i
      ctx.beginPath()
      ctx.moveTo(pad.left, gy)
      ctx.lineTo(w - pad.right, gy)
      ctx.stroke()

      // Y-axis labels
      const val = Math.round(maxV * (1 - i / gridLines))
      ctx.fillStyle = 'rgba(255,255,255,0.3)'
      ctx.font = '10px monospace'
      ctx.textAlign = 'right'
      ctx.fillText(val.toString(), pad.left - 8, gy + 3)
    }

    // X-axis time labels
    ctx.textAlign = 'center'
    ctx.fillStyle = 'rgba(255,255,255,0.3)'
    const labelCount = Math.min(6, points.length)
    const step = Math.max(1, Math.floor(points.length / labelCount))
    for (let i = 0; i < points.length; i += step) {
      const px = x(points[i].t)
      const date = new Date(points[i].t)
      const lbl = `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}:${date.getSeconds().toString().padStart(2, '0')}`
      ctx.fillText(lbl, px, h - pad.bottom + 18)
    }

    // Area fill
    const gradient = ctx.createLinearGradient(0, pad.top, 0, pad.top + chartH)
    gradient.addColorStop(0, 'rgba(124, 58, 237, 0.3)')
    gradient.addColorStop(1, 'rgba(124, 58, 237, 0.0)')

    ctx.beginPath()
    ctx.moveTo(x(points[0].t), pad.top + chartH)
    for (const p of points) {
      ctx.lineTo(x(p.t), y(p.v))
    }
    ctx.lineTo(x(points[points.length - 1].t), pad.top + chartH)
    ctx.closePath()
    ctx.fillStyle = gradient
    ctx.fill()

    // Line
    ctx.beginPath()
    for (let i = 0; i < points.length; i++) {
      const px = x(points[i].t)
      const py = y(points[i].v)
      if (i === 0) ctx.moveTo(px, py)
      else ctx.lineTo(px, py)
    }
    ctx.strokeStyle = '#7c3aed'
    ctx.lineWidth = 2
    ctx.stroke()

    // Dots on data points with values
    for (const p of points) {
      if (p.v > 0) {
        const px = x(p.t)
        const py = y(p.v)
        ctx.beginPath()
        ctx.arc(px, py, 3, 0, Math.PI * 2)
        ctx.fillStyle = '#a78bfa'
        ctx.fill()
        ctx.strokeStyle = '#7c3aed'
        ctx.lineWidth = 1.5
        ctx.stroke()
      }
    }

    // Title
    ctx.fillStyle = 'rgba(255,255,255,0.5)'
    ctx.font = '11px sans-serif'
    ctx.textAlign = 'left'
    ctx.fillText('Messages per 10s (last 5 minutes)', pad.left, 18)
  }, [timeSeries])

  useEffect(() => { draw() }, [draw])

  useEffect(() => {
    const handleResize = () => draw()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [draw])

  return (
    <canvas
      ref={canvasRef}
      className="w-full rounded-lg"
      style={{ height: '280px', background: 'rgba(255,255,255,0.02)' }}
    />
  )
}

/* ─── Horizontal bar ───────────────────────────────────────────────────────── */
function Bar({ label, count, max }: { label: string; count: number; max: number }) {
  const pct = max > 0 ? (count / max) * 100 : 0
  return (
    <div className="flex items-center gap-3">
      <div className="w-32 text-xs text-slate-400 truncate text-right">{label}</div>
      <div className="flex-1 h-6 bg-white/[0.03] rounded-full overflow-hidden relative">
        <div
          className="h-full rounded-full bg-gradient-to-r from-brand-600 to-brand-400 transition-all duration-500"
          style={{ width: `${Math.max(pct, 1)}%` }} />
        <span className="absolute inset-0 flex items-center justify-center text-[10px] text-white/80 font-mono">
          {count.toLocaleString()}
        </span>
      </div>
    </div>
  )
}

/* ─── Window selector buttons ──────────────────────────────────────────────── */
const WINDOWS = [
  { value: '1h', label: 'Last 1 hour' },
  { value: '24h', label: 'Last 24 hours' },
  { value: '7d', label: 'Last 7 days' },
]

/* ─── Main Page ────────────────────────────────────────────────────────────── */
export default function ThroughputPage() {
  const [window, setWindow] = useState('1h')

  // Live TPS data — refreshes every 3 seconds
  const { data: live } = useQuery({
    queryKey: ['throughput-live'],
    queryFn: () => getLiveTps(5),
    refetchInterval: 3000,
  })

  // Volume breakdown by window
  const { data: vol, isLoading } = useQuery({
    queryKey: ['throughput', window],
    queryFn: () => getThroughput(window),
    refetchInterval: 30000,
  })

  const tps = live?.tps ?? {}
  const timeSeries = live?.timeSeries ?? []
  const smsc = vol?.smsc ?? []
  const devices = vol?.devices ?? []
  const maxSmsc = Math.max(...smsc.map((s: any) => s.count), 1)
  const maxDevice = Math.max(...devices.map((d: any) => d.count), 1)

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-white flex items-center gap-2">
          <BarChart3 size={22} className="text-orange-400" /> Throughput
        </h1>
        <p className="text-slate-500 text-xs mt-0.5">
          Live transactions per second and message volume breakdown.
        </p>
      </div>

      {/* ── Live TPS Cards ──────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <TpsCard
          label="Current TPS (1s)"
          value={tps.last1s ?? 0}
          icon={<Zap size={20} className="text-yellow-300" />}
          color="bg-yellow-500/10"
        />
        <TpsCard
          label="Avg TPS (10s)"
          value={tps.last10s ?? '0.00'}
          icon={<Activity size={20} className="text-emerald-400" />}
          color="bg-emerald-500/10"
        />
        <TpsCard
          label="Avg TPS (60s)"
          value={tps.last60s ?? '0.00'}
          icon={<Timer size={20} className="text-blue-400" />}
          color="bg-blue-500/10"
        />
        <TpsCard
          label="Total (5 min)"
          value={(tps.total5m ?? 0).toLocaleString()}
          icon={<Clock size={20} className="text-purple-400" />}
          color="bg-purple-500/10"
        />
      </div>

      {/* ── Live Chart ──────────────────────────────────────────────────── */}
      <div className="glass p-5">
        <TpsChart timeSeries={timeSeries} />
      </div>

      {/* ── Volume Breakdown ────────────────────────────────────────────── */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-white">Volume Breakdown</h2>
        <div className="flex gap-1">
          {WINDOWS.map(w => (
            <button key={w.value}
              className={`px-3 py-1.5 rounded text-xs font-medium transition ${window === w.value ? 'bg-brand-600/20 text-brand-400 border border-brand-500/30' : 'text-slate-400 hover:text-white bg-white/5'}`}
              onClick={() => setWindow(w.value)}>
              {w.label}
            </button>
          ))}
        </div>
      </div>

      {isLoading ? <div className="text-slate-500 text-sm">Loading…</div> : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* SMSC Throughput */}
          <div className="glass p-5 space-y-3">
            <h2 className="text-sm font-bold text-white">SMSC Suppliers</h2>
            {smsc.length === 0 ? (
              <div className="text-slate-600 text-xs py-4 text-center">No SMSC suppliers configured</div>
            ) : (
              <div className="space-y-2">
                {smsc.map((s: any) => <Bar key={s.id} label={s.name} count={s.count} max={maxSmsc} />)}
              </div>
            )}
          </div>

          {/* Device Throughput */}
          <div className="glass p-5 space-y-3">
            <h2 className="text-sm font-bold text-white">Devices</h2>
            {devices.length === 0 ? (
              <div className="text-slate-600 text-xs py-4 text-center">No devices registered</div>
            ) : (
              <div className="space-y-2">
                {devices.filter((d: any) => d.count > 0).map((d: any) => (
                  <Bar key={d.id} label={d.name} count={d.count} max={maxDevice} />
                ))}
                {devices.every((d: any) => d.count === 0) && (
                  <div className="text-slate-600 text-xs py-4 text-center">No messages in this window</div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
