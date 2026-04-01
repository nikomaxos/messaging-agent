import { useState, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getDevices, getGroups, createDevice, updateDevice, deleteDevice, getDevicePerformance, bulkDeviceCommand } from '../api/client'
import { Device, DeviceGroup } from '../types'
import { Plus, Pencil, Trash2, X, Check, RefreshCw, Wifi, WifiOff, Power, RefreshCcw, Upload, DownloadCloud, BatteryCharging, Battery, Info, ShieldCheck, VolumeX, PhoneOff, Activity, HeartPulse, Layers, MapPin, FileText, Smartphone, Monitor, QrCode } from 'lucide-react'
import { FormatDistanceToNowOptions, formatDistanceToNow, format } from 'date-fns'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { ConfirmModal } from '../components/ConfirmModal'
import GroupsPage from './GroupsPage'
import DeviceMapPage from './DeviceMapPage'
import DeviceLogsPage from './DeviceLogsPage'
import RemoteDesktopPage from './RemoteDesktopPage'
import { MatrixSetupModal } from '../components/MatrixSetupModal'
import { useSearchParams } from 'react-router-dom'

type Tab = 'devices' | 'groups' | 'map' | 'logs' | 'remote'
const TABS: { key: Tab; label: string; icon: React.ReactNode }[] = [
  { key: 'devices', label: 'Devices', icon: <Smartphone size={15} /> },
  { key: 'groups', label: 'Groups', icon: <Layers size={15} /> },
  { key: 'map', label: 'Map', icon: <MapPin size={15} /> },
  { key: 'logs', label: 'Device Logs', icon: <FileText size={15} /> },
  { key: 'remote', label: 'Remote Desktop', icon: <Monitor size={15} /> },
]

function LiveUptime({ connectedAt }: { connectedAt?: string }) {
  const [val, setVal] = useState('')
  useEffect(() => {
    if (!connectedAt) { setVal('—'); return }
    const update = () => setVal(formatDistanceToNow(new Date(connectedAt)))
    update()
    const int = setInterval(update, 60000)
    return () => clearInterval(int)
  }, [connectedAt])
  return <span>{val}</span>
}

export default function DevicesPage() {
  const qc = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = (searchParams.get('tab') as Tab) || 'devices'
  const setActiveTab = (tab: Tab) => setSearchParams(tab === 'devices' ? {} : { tab })
  const [wsConnected, setWsConnected] = useState(false)
  const [refreshToast, setRefreshToast] = useState<string | null>(null)
  const [autoFetch, setAutoFetch] = useState(false)
  const [filterGroup, setFilterGroup] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [uploadingApk, setUploadingApk] = useState(false)
  const [serverApkName, setServerApkName] = useState<string | null>(null)

  const fetchApkInfo = async () => {
    try {
      const token = localStorage.getItem('jwt')
      const res = await fetch('/api/apk/info', { headers: { 'Authorization': `Bearer ${token}` } })
      if (res.ok) {
        const info = await res.json()
        if (info.exists) setServerApkName(info.filename)
      }
    } catch {}
  }

  useEffect(() => { fetchApkInfo() }, [])
  const [uploadSuccess, setUploadSuccess] = useState(false)
  const [autostartToast, setAutostartToast] = useState<string | null>(null)
  const [otaPushing, setOtaPushing] = useState(false)
  const [otaToast, setOtaToast] = useState<string | null>(null)
  
  const [confirmAction, setConfirmAction] = useState<{ title: string, message: string, onConfirm: () => void } | null>(null)
  const stompRef = useRef<Client | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const [setupMatrixForDevice, setSetupMatrixForDevice] = useState<Device | null>(null)

  const { data: devices = [], refetch, isFetching } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: autoFetch ? 10_000 : false,
    staleTime: Infinity,
    placeholderData: (prev: any) => prev,
  })

  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: perfScores = {} } = useQuery({
    queryKey: ['device-performance'],
    queryFn: getDevicePerformance,
    refetchInterval: 30_000,
  })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState({ name: '', imei: '', groupId: '', phoneNumber: '' })

  const handleRefresh = async () => {
    await refetch()
    const time = new Date().toLocaleTimeString()
    setRefreshToast(`✓ Refreshed at ${time}`)
    setTimeout(() => setRefreshToast(null), 3000)
  }

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploadingApk(true)
    const formData = new FormData()
    formData.append('file', file)
    const token = localStorage.getItem('jwt')
    try {
      const res = await fetch('/api/apk/upload', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      })
      if (res.ok) {
        setUploadSuccess(true)
        setTimeout(() => setUploadSuccess(false), 20000)
        fetchApkInfo()
      }
      else alert('Failed to upload APK.')
    } catch (err) {
      alert('Error uploading APK')
    } finally {
      setUploadingApk(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  // ── WebSocket subscription to /topic/devices for live status updates ──────
  useEffect(() => {
    const token = localStorage.getItem('jwt')
    if (!token) return

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws-admin'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        setWsConnected(true)
        // Subscribe to live device status events
        client.subscribe('/topic/devices', msg => {
          try {
            const event = JSON.parse(msg.body)
            // Patch the specific device's status in the react-query cache
            qc.setQueryData<Device[]>(['devices'], prev =>
              (prev ?? []).map(d =>
                d.id === event.id
                  ? { ...d, status: event.status as Device['status'],
                      lastHeartbeat: event.lastHeartbeat || d.lastHeartbeat,
                      connectedAt: event.connectedAt || d.connectedAt,
                      batteryPercent: event.batteryPercent !== undefined && event.batteryPercent !== "" ? event.batteryPercent : d.batteryPercent,
                      isCharging: event.isCharging !== undefined && event.isCharging !== "" ? event.isCharging : d.isCharging,
                      wifiSignalDbm: event.wifiSignalDbm !== undefined && event.wifiSignalDbm !== "" ? event.wifiSignalDbm : d.wifiSignalDbm,
                      gsmSignalDbm: event.gsmSignalDbm !== undefined && event.gsmSignalDbm !== "" ? event.gsmSignalDbm : d.gsmSignalDbm,
                      activeNetworkType: event.activeNetworkType !== undefined && event.activeNetworkType !== "" ? event.activeNetworkType : d.activeNetworkType,
                      apkVersion: event.apkVersion !== undefined && event.apkVersion !== "" ? event.apkVersion : d.apkVersion,
                      apkUpdateStatus: event.apkUpdateStatus !== undefined ? event.apkUpdateStatus : d.apkUpdateStatus,
                      autostartPinned: event.autostartPinned !== undefined ? event.autostartPinned : d.autostartPinned }
                  : d
              )
            )
          } catch { /* ignore parse errors */ }
        })
      },
      onDisconnect: () => setWsConnected(false),
      onStompError: () => setWsConnected(false),
    })

    client.activate()
    stompRef.current = client

    return () => { client.deactivate() }
  }, [qc])

  const createMut = useMutation({ mutationFn: createDevice,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['devices'] }); reset() } })
  const updateMut = useMutation({ mutationFn: ({ id, d }: any) => updateDevice(id, d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['devices'] }); reset() } })
  const deleteMut = useMutation({ mutationFn: deleteDevice,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['devices'] }),
    onError: (err: any) => alert('Failed to delete device: ' + (err.response?.data?.message || err.message))
  })

  const reset = () => { setShowForm(false); setEditing(null); setForm({ name: '', imei: '', groupId: '', phoneNumber: '' }) }
  const openEdit = (d: Device) => {
    setEditing(d); setForm({ name: d.name, imei: d.imei ?? '', groupId: String(d.group?.id ?? ''), phoneNumber: d.phoneNumber ?? '' }); setShowForm(true)
  }
  const save = () => {
    const payload = { ...form, groupId: form.groupId ? Number(form.groupId) : null, phoneNumber: form.phoneNumber || null }
    if (editing) updateMut.mutate({ id: editing.id, d: payload })
    else createMut.mutate(payload)
  }

  const confirmAndSendCommand = (id: number, command: string) => {
    setConfirmAction({
      title: 'Send Command',
      message: `Send ${command} to device?`,
      onConfirm: () => sendCommand(id, command)
    })
  }

  const sendCommand = async (id: number, command: string) => {
    const token = localStorage.getItem('jwt')
    const res = await fetch(`/api/devices/${id}/command`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body: JSON.stringify({ command })
    })
    if (res.ok && command === 'PIN_AUTOSTART') {
      setAutostartToast('🛡️ Autostart protection applied')
      setTimeout(() => setAutostartToast(null), 3000)
    }
  }

  const toggleAutoReboot = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, autoRebootEnabled: !d.autoRebootEnabled } })
  }

  const toggleSilentMode = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, silentMode: !d.silentMode } })
  }

  const toggleCallBlock = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, callBlockEnabled: !d.callBlockEnabled } })
  }

  const setAutoPurge = (d: Device, value: string) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, autoPurge: value } })
  }

  const setSendInterval = (d: Device, value: number) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, sendIntervalSeconds: value } })
  }

  const toggleSelfHealing = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, phoneNumber: d.phoneNumber, groupId: d.group?.id, selfHealingEnabled: !d.selfHealingEnabled } })
  }

  const statusClass = (s: Device['status']) =>
    ({ ONLINE: 'pill-green', OFFLINE: 'pill-gray', BUSY: 'pill-yellow', MAINTENANCE: 'pill-yellow' }[s])

  // Filtered devices for the table
  const filteredDevices = devices.filter((d: Device) => {
    if (filterGroup && String(d.group?.id ?? '') !== filterGroup) return false
    if (filterStatus && d.status !== filterStatus) return false
    return true
  })

  return (
    <div className="p-6 space-y-6">
      <MatrixSetupModal
        isOpen={setupMatrixForDevice !== null}
        device={setupMatrixForDevice}
        onClose={() => setSetupMatrixForDevice(null)}
      />
      <ConfirmModal
        isOpen={confirmAction !== null}
        title={confirmAction?.title || ''}
        message={confirmAction?.message || ''}
        onConfirm={() => confirmAction?.onConfirm()}
        onCancel={() => setConfirmAction(null)}
      />

      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Devices</h1>
          <p className="text-slate-400 text-sm mt-0.5 flex items-center gap-1.5">
            {wsConnected
              ? <><Wifi size={12} className="text-emerald-400" /> Live — updates via WebSocket</>
              : <><WifiOff size={12} className="text-slate-500" /> Reconnecting…</>}
            {refreshToast && (
              <span className="ml-2 text-emerald-400 text-xs font-medium">{refreshToast}</span>
            )}
            {autostartToast && (
              <span className="ml-2 text-emerald-400 text-xs font-medium animate-pulse">{autostartToast}</span>
            )}
          </p>
        </div>
        {activeTab === 'devices' && (
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-1 cursor-pointer text-sm text-slate-300 mr-2 border border-slate-700 px-3 py-1.5 rounded-lg bg-slate-800/50 hover:bg-slate-700/50 transition">
              <input type="checkbox" className="accent-brand-500" checked={autoFetch} onChange={e => setAutoFetch(e.target.checked)} />
              Auto Fetch (10s)
            </label>
            <input type="file" accept=".apk" ref={fileRef} className="hidden" onChange={handleFileUpload} />
            <div className="flex flex-col items-end">
              <button className={`btn-secondary whitespace-nowrap ${uploadSuccess ? '!bg-green-600/20 !border-green-500/50 !text-green-400' : ''}`} onClick={() => fileRef.current?.click()} disabled={uploadingApk} title="Upload a new APK to the server">
                {uploadingApk ? <RefreshCw size={14} className="animate-spin" /> : uploadSuccess ? <Check size={14} /> : <Upload size={14} />} {uploadSuccess ? 'APK Uploaded' : 'Upload APK'}
              </button>
              {serverApkName && <span className="text-[10px] text-slate-500 mt-0.5">{serverApkName}</span>}
            </div>
            <button
              className={`btn-secondary whitespace-nowrap ${otaToast ? '!bg-green-600/20 !border-green-500/50 !text-green-400' : ''}`}
              disabled={otaPushing}
              title="Push OTA update to all online filtered devices"
              onClick={() => {
                const onlineDeviceIds = filteredDevices.filter((d: Device) => d.status === 'ONLINE' || d.status === 'BUSY').map((d: Device) => d.id)
                if (onlineDeviceIds.length === 0) { alert('No online devices to update.'); return }
                setConfirmAction({
                  title: 'Push OTA Update',
                  message: `Send UPDATE_APK command to ${onlineDeviceIds.length} online device(s)?\nDevices will download and install the latest APK from the server.`,
                  onConfirm: async () => {
                    setOtaPushing(true)
                    try {
                      const result = await bulkDeviceCommand(onlineDeviceIds, 'UPDATE_APK')
                      setOtaToast(`✓ OTA pushed to ${result.sent} device(s)`)
                      setTimeout(() => setOtaToast(null), 5000)
                    } catch { alert('Failed to push OTA update') }
                    finally { setOtaPushing(false) }
                  }
                })
              }}
            >
              {otaPushing ? <RefreshCw size={14} className="animate-spin" /> : otaToast ? <Check size={14} /> : <DownloadCloud size={14} />} {otaToast || 'Push OTA Update'}
            </button>
            <button
              className="btn-secondary"
              onClick={handleRefresh}
              disabled={isFetching}
              title="Re-fetch all device statuses"
            >
              <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
            </button>
            <button id="add-device-btn" className="btn-primary" onClick={() => setShowForm(true)}>
              <Plus size={16} /> Add Device
            </button>
          </div>
        )}
      </div>

      {/* Tab Bar */}
      <div className="flex items-center gap-1 border-b border-white/10 -mb-3">
        {TABS.map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-[1px] ${
              activeTab === tab.key
                ? 'text-brand-400 border-brand-500'
                : 'text-slate-500 border-transparent hover:text-slate-300 hover:border-slate-600'
            }`}
          >
            {tab.icon} {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'devices' && (<>

      {showForm && (
        <div className="glass p-5 space-y-4">
          <h2 className="text-sm font-semibold text-slate-300">{editing ? 'Edit' : 'New'} Device</h2>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Device Name *</label>
              <input id="device-name" className="inp" placeholder="Pixel 8 #1"
                value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Phone Number</label>
              <input className="inp" placeholder="+30 690 123 4567"
                value={form.phoneNumber} onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">IMEI</label>
              <input className="inp" placeholder="15-digit IMEI"
                value={form.imei} onChange={e => setForm(f => ({ ...f, imei: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Group</label>
              <select className="inp bg-slate-800/70"
                value={form.groupId} onChange={e => setForm(f => ({ ...f, groupId: e.target.value }))}>
                <option value="">— No group —</option>
                {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </div>
          </div>
          <div className="flex gap-2 justify-end">
            <button className="btn-secondary" onClick={reset}><X size={15} /> Cancel</button>
            <button id="save-device-btn" className="btn-primary" onClick={save}><Check size={15} /> Save</button>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="flex items-center gap-3">
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Device Group</label>
          <select
            className="bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5 min-w-[140px]"
            value={filterGroup}
            onChange={e => setFilterGroup(e.target.value)}
          >
            <option value="">All Groups</option>
            {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Status</label>
          <select
            className="bg-[#12121f] text-sm text-white border border-white/5 rounded px-2 py-1.5 min-w-[120px]"
            value={filterStatus}
            onChange={e => setFilterStatus(e.target.value)}
          >
            <option value="">All</option>
            <option value="ONLINE">ONLINE</option>
            <option value="OFFLINE">OFFLINE</option>
            <option value="BUSY">BUSY</option>
            <option value="MAINTENANCE">MAINTENANCE</option>
          </select>
        </div>
        {(filterGroup || filterStatus) && (
          <button className="text-xs text-slate-400 hover:text-white mt-4" onClick={() => { setFilterGroup(''); setFilterStatus('') }}>Clear Filters</button>
        )}
        <span className="text-xs text-slate-600 mt-4 ml-auto">{filteredDevices.length} of {devices.length} devices</span>
      </div>

      <div className="glass">
        <table className="tbl">
          <thead><tr>
            <th className="px-4 pt-4 pb-3">Name</th>
            <th className="px-4">Status</th>
            <th className="px-4">Uptime</th>
            <th className="px-4">Group</th>
            <th className="px-4">Battery</th>
            <th className="px-4">Interface</th>
            <th className="px-4">Wi-Fi</th>
            <th className="px-4">GSM</th>
            <th className="px-4">APK (v)</th>
            <th className="px-4">RCS</th>
            <th className="px-4">Last Seen</th>
            <th className="px-4">Score</th>
            <th className="px-4 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {filteredDevices.map((d: Device) => (
              <tr key={d.id}>
                <td className="px-4">
                  <div className="font-medium text-slate-200">{d.name}</div>
                  {d.phoneNumber && <div className="text-[10px] text-brand-400 font-medium">{d.phoneNumber}</div>}
                  {d.adbWifiAddress && <div className="text-[10px] text-teal-400 font-mono cursor-pointer hover:text-teal-300 transition" onClick={() => navigator.clipboard.writeText(`adb connect ${d.adbWifiAddress}`)} title="Click to copy adb connect command">🔌 {d.adbWifiAddress}</div>}
                  <div className="text-[10px] text-slate-500">{d.imei ? d.imei : `Device ID: #${d.id}`}</div>
                </td>
                <td className="px-4">
                  <span className={`pill ${statusClass(d.status)}`}>{d.status}</span>
                </td>
                <td className="px-4 text-xs text-slate-300 font-medium">
                  {d.status === 'ONLINE' ? <LiveUptime connectedAt={d.connectedAt} /> : '—'}
                </td>
                <td className="px-4 text-slate-400 text-xs">{d.group?.name ?? '—'}</td>
                <td className="px-4 text-xs">
                  {d.batteryPercent != null ? (
                    <div className={`flex items-center gap-1.5 font-medium ${d.isCharging ? 'text-green-400' : 'text-red-400'}`}>
                      {d.batteryPercent}%
                      {d.isCharging ? <BatteryCharging size={13} className="animate-pulse" /> : <Battery size={13} />}
                    </div>
                  ) : <span className="text-slate-300">—</span>}
                </td>
                <td className="px-4 text-xs font-semibold text-brand-300">{d.activeNetworkType || '—'}</td>
                <td className={`px-4 text-xs ${d.activeNetworkType === 'WIFI' ? 'text-emerald-400 font-bold' : 'text-slate-300'}`}>{d.wifiSignalDbm != null ? `${d.wifiSignalDbm} dBm` : '—'}</td>
                <td className={`px-4 text-xs ${d.activeNetworkType === 'GSM' || d.activeNetworkType === 'CELLULAR' ? 'text-emerald-400 font-bold' : 'text-slate-300'}`}>{d.gsmSignalDbm != null ? `${d.gsmSignalDbm} dBm` : '—'}</td>
                <td className="px-4 text-xs font-medium text-slate-300">
                  {d.apkUpdateStatus ? (
                    <div className="flex items-center gap-1.5 text-brand-400 animate-pulse">
                      <RefreshCw size={12} className="animate-spin text-brand-400" /> {d.apkUpdateStatus}
                    </div>
                  ) : (
                    d.apkVersion || '—'
                  )}
                </td>
                <td className="px-4">
                  <span className={`pill ${d.rcsCapable ? 'pill-green' : 'pill-gray'}`}>
                    {d.rcsCapable ? 'Yes' : 'No'}
                  </span>
                </td>
                <td className="px-4 text-xs text-slate-500 whitespace-nowrap">
                  {d.lastHeartbeat ? format(new Date(d.lastHeartbeat), 'h:mm a').toLowerCase() : 'never'}
                </td>
                <td className="px-4">
                  {(() => {
                    const perf = (perfScores as any)[d.id]
                    if (!perf) return <span className="text-slate-500 text-xs">—</span>
                    const s2h = perf.score2h
                    const s7d = perf.score7d
                    if (!s2h && !s7d) return <span className="text-slate-500 text-xs">—</span>
                    const scoreColor = (s: number) => s >= 80 ? 'text-emerald-400' : s >= 50 ? 'text-amber-400' : 'text-red-400'
                    const barColor = (s: number) => s >= 80 ? 'bg-emerald-500' : s >= 50 ? 'bg-amber-500' : 'bg-red-500'
                    return (
                      <div className="relative group cursor-help">
                        <div className="space-y-0.5">
                          {s2h && (
                            <div className="flex items-center gap-1">
                              <span className="text-[8px] text-slate-500 w-4">2h</span>
                              <div className="w-10 h-1.5 bg-slate-700 rounded-full overflow-hidden">
                                <div className={`h-full ${barColor(s2h.score)} rounded-full transition-all`} style={{ width: `${s2h.score}%` }} />
                              </div>
                              <span className={`text-[9px] font-bold ${scoreColor(s2h.score)}`}>{s2h.score}</span>
                            </div>
                          )}
                          {s7d && (
                            <div className="flex items-center gap-1">
                              <span className="text-[8px] text-slate-500 w-4">7d</span>
                              <div className="w-10 h-1.5 bg-slate-700 rounded-full overflow-hidden">
                                <div className={`h-full ${barColor(s7d.score)} rounded-full transition-all`} style={{ width: `${s7d.score}%` }} />
                              </div>
                              <span className={`text-[9px] font-bold ${scoreColor(s7d.score)}`}>{s7d.score}</span>
                            </div>
                          )}
                        </div>
                        <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-3 py-2 whitespace-nowrap z-50 shadow-xl">
                          <b>Last 2 Hours</b><br/>
                          ⚡ Latency: <b>{s2h?.avgLatencySeconds ?? 0}s</b> avg<br/>
                          ✅ Delivery: <b>{Math.round((s2h?.successRate ?? 1) * 100)}%</b><br/>
                          📨 Dispatched: <b>{s2h?.totalDispatched ?? 0}</b><br/>
                          <br/>
                          <b>Last 7 Days</b><br/>
                          ⚡ Latency: <b>{s7d?.avgLatencySeconds ?? 0}s</b> avg<br/>
                          ✅ Delivery: <b>{Math.round((s7d?.successRate ?? 1) * 100)}%</b><br/>
                          📨 Dispatched: <b>{s7d?.totalDispatched ?? 0}</b>
                        </span>
                      </div>
                    )
                  })()}
                </td>
                <td className="px-4 text-right overflow-visible">
                  <div className="flex items-center justify-end gap-2">
                    <div className="flex flex-col items-start mr-2">
                      <label className="text-[10px] text-slate-500 leading-none mb-0.5 flex items-center gap-0.5">
                        Auto Purge
                        <span className="relative group">
                          <Info size={14} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                          <span className="absolute top-full left-1/2 -translate-x-1/2 mt-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-3 py-2 whitespace-nowrap z-50 shadow-xl">
                            Automatically purges device data every 5 days.<br/>
                            <b>Messages</b> — clears Google Messages DB &amp; SMS<br/>
                            <b>System Logs</b> — clears Android logcat buffer<br/>
                            <b>All</b> — clears both messages and logs
                          </span>
                        </span>
                      </label>
                      <select
                        className="bg-[#12121f] text-[10px] text-slate-300 border border-white/5 rounded px-1 py-0.5 cursor-pointer"
                        value={d.autoPurge ?? 'OFF'}
                        onChange={e => setAutoPurge(d, e.target.value)}
                      >
                        <option value="OFF">Off</option>
                        <option value="MESSAGES">Messages</option>
                        <option value="SYSTEM_LOGS">System Logs</option>
                        <option value="ALL">All</option>
                      </select>
                    </div>
                    <div className="flex flex-col items-start mr-2">
                      <label className="text-[10px] text-slate-500 leading-none mb-0.5 flex items-center gap-0.5">
                        Submitting Interval
                        <span className="relative group">
                          <Info size={14} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                          <span className="absolute top-full left-1/2 -translate-x-1/2 mt-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-3 py-2 whitespace-nowrap z-50 shadow-xl">
                            Throttle: wait X seconds between dispatches.<br/>
                            Set to <b>0</b> for instant delivery (no delay).<br/>
                            E.g. <b>5</b> = max 12 msg/min, <b>2.5</b> = 24 msg/min<br/>
                            Creates a queue during burst sends.
                          </span>
                        </span>
                      </label>
                      <input
                        type="number"
                        step="0.5"
                        min="0"
                        className="bg-[#12121f] text-[10px] text-slate-300 border border-white/5 rounded px-1 py-0.5 w-12 text-center"
                        value={d.sendIntervalSeconds ?? 0}
                        onChange={e => setSendInterval(d, parseFloat(e.target.value) || 0)}
                      />
                    </div>
                    <label className="flex items-center gap-1 cursor-pointer text-[10px] text-slate-500 mr-2 leading-none">
                      <input type="checkbox" className="accent-brand-500" checked={d.autoRebootEnabled ?? false} onChange={() => toggleAutoReboot(d)} />
                      <span className="flex items-center gap-0.5">
                        Auto<br/>Reboot
                        <span className="relative group">
                          <Info size={14} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                          <span className="absolute top-full left-1/2 -translate-x-1/2 mt-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-3 py-2 whitespace-nowrap z-50 shadow-xl">
                            Automatically reboots the device if it has<br/>
                            been disconnected for more than 5 minutes.<br/>
                            Requires root access on the device.
                          </span>
                        </span>
                      </span>
                    </label>
                    <label className="flex items-center gap-1 cursor-pointer text-[10px] text-slate-500 mr-2 leading-none">
                      <input type="checkbox" className="accent-cyan-500" checked={d.selfHealingEnabled ?? false} onChange={() => toggleSelfHealing(d)} />
                      <span className="flex items-center gap-0.5">
                        Self<br/>Healing
                        <span className="relative group">
                          <Info size={14} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                          <span className="absolute top-full left-1/2 -translate-x-1/2 mt-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-3 py-2 whitespace-nowrap z-50 shadow-xl">
                            Reboots device when (last 2h):<br/>
                            • Delivery rate &lt; 50%, OR<br/>
                            • Avg latency &gt; 30 seconds<br/>
                            Restarts Google Messages bot:<br/>
                            • After 3 consecutive send-button failures<br/>
                            Checked every 5 minutes.
                          </span>
                        </span>
                      </span>
                    </label>
                    <label className="flex items-center gap-1 cursor-pointer text-[10px] text-slate-500 mr-2 leading-none">
                      <input type="checkbox" className="accent-amber-500" checked={d.silentMode ?? false} onChange={() => toggleSilentMode(d)} />
                      <span className="flex items-center gap-0.5">
                        <VolumeX size={11} className={d.silentMode ? 'text-amber-400' : ''} />
                        Silent
                      </span>
                    </label>
                    <label className="flex items-center gap-1 cursor-pointer text-[10px] text-slate-500 mr-2 leading-none">
                      <input type="checkbox" className="accent-red-500" checked={d.callBlockEnabled ?? false} onChange={() => toggleCallBlock(d)} />
                      <span className="flex items-center gap-0.5">
                        <PhoneOff size={11} className={d.callBlockEnabled ? 'text-red-400' : ''} />
                        Block<br/>Calls
                      </span>
                    </label>
                    <button className="btn-primary !px-2 !py-1 !text-brand-300 !bg-brand-500/10 !border-brand-500/20" title="Matrix Setup Guide" onClick={() => setSetupMatrixForDevice(d)}><QrCode size={13} /></button>

                    <button className={`btn-secondary !px-2 !py-1 ${d.autostartPinned ? '!text-emerald-400' : '!text-amber-400'}`} title="Pin Autostart (MIUI protection)" onClick={() => confirmAndSendCommand(d.id, 'PIN_AUTOSTART')}><ShieldCheck size={13} /></button>
                    <button className="btn-secondary !px-2 !py-1 text-brand-400" title="Push APK Update" onClick={() => confirmAndSendCommand(d.id, 'UPDATE_APK')}><DownloadCloud size={13} /></button>
                    <button className="btn-secondary !px-2 !py-1 text-emerald-400" title="Reconnect" onClick={() => confirmAndSendCommand(d.id, 'RECONNECT')}><RefreshCcw size={13} /></button>
                    <button className="btn-danger !px-2 !py-1" title="Reboot" onClick={() => confirmAndSendCommand(d.id, 'REBOOT')}><Power size={13} /></button>
                    <button className="btn-secondary !px-2 !py-1" onClick={() => openEdit(d)}><Pencil size={13} /></button>
                    <button className="btn-danger !px-2 !py-1" title="Delete"
                      onClick={() => setConfirmAction({
                        title: 'Delete Device',
                        message: `Delete ${d.name}?`,
                        onConfirm: () => deleteMut.mutate(d.id)
                      })}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {filteredDevices.length === 0 && (
              <tr><td colSpan={13} className="px-4 py-8 text-center text-slate-500">{devices.length === 0 ? 'No devices registered' : 'No devices match the current filters'}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      </>)}

      {activeTab === 'groups' && <GroupsPage />}
      {activeTab === 'map' && <DeviceMapPage />}
      {activeTab === 'logs' && <DeviceLogsPage />}
      {activeTab === 'remote' && <RemoteDesktopPage />}
      
      <MatrixSetupModal 
        isOpen={setupMatrixForDevice !== null} 
        device={setupMatrixForDevice} 
        onClose={() => setSetupMatrixForDevice(null)} 
      />
    </div>
  )
}
