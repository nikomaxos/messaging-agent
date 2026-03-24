import { useState, useEffect, useRef, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getDevices, getGroups } from '../api/client'
import { Device, DeviceGroup } from '../types'
import { Monitor, Smartphone, Wifi, WifiOff, Battery, BatteryCharging, ArrowLeft, RefreshCw, MousePointer, X } from 'lucide-react'

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

// ─── Detail View (Placeholder until WebSocket streaming is implemented) ───────

function DeviceDetailView({ device, onBack }: { device: Device; onBack: () => void }) {
  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-white/[0.06] hover:bg-white/[0.1] text-slate-300 text-sm transition"
          >
            <ArrowLeft size={14} /> Back to devices
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
      </div>

      {/* Screen Area */}
      <div className="flex items-center justify-center rounded-xl border border-white/[0.08] bg-[#0a0a14] min-h-[500px]">
        <div className="text-center space-y-4 py-16">
          <div className="relative mx-auto w-20 h-32 rounded-xl border-2 border-slate-700 bg-slate-900 flex items-center justify-center">
            <Monitor size={28} className="text-slate-700" />
            <div className="absolute inset-0 rounded-xl bg-brand-500/5 animate-pulse" />
          </div>
          <div>
            <p className="text-slate-400 text-sm font-medium">Remote Desktop</p>
            <p className="text-slate-600 text-xs mt-1 max-w-xs">
              WebSocket-based screen streaming will be available once the Android app is updated with MediaProjection support.
            </p>
          </div>
          <div className="flex items-center justify-center gap-6 text-[10px] text-slate-600">
            <span className="flex items-center gap-1"><MousePointer size={10} /> Tap</span>
            <span className="flex items-center gap-1"><RefreshCw size={10} /> Swipe</span>
            <span>Keyboard</span>
          </div>
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
