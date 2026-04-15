import { useState, useEffect, useRef, Fragment } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getDevices, getGroups, createDevice, updateDevice, deleteDevice, getDevicePerformance, bulkDeviceCommand, getSimCards, assignSimCard } from '../api/client'
import { Device, DeviceGroup, SimCard } from '../types'
import { Plus, Pencil, Trash2, X, Check, RefreshCw, Wifi, WifiOff, Power, RefreshCcw, Upload, DownloadCloud, BatteryCharging, Battery, Info, ShieldCheck, VolumeX, PhoneOff, Activity, HeartPulse, Layers, MapPin, FileText, Smartphone, Monitor, QrCode, Settings, ChevronDown, Cpu } from 'lucide-react'
import { formatDistanceToNow, format } from 'date-fns'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { ConfirmModal } from '../components/ConfirmModal'
import GroupsPage from './GroupsPage'
import DeviceMapPage from './DeviceMapPage'
import DeviceLogsPage from './DeviceLogsPage'
import RemoteDesktopPage from './RemoteDesktopPage'
import SimCardsPage from './SimCardsPage'
import { MatrixSetupModal } from '../components/MatrixSetupModal'
import { useSearchParams } from 'react-router-dom'
import { QRCodeSVG } from 'qrcode.react'
type Tab = 'devices' | 'simCards' | 'groups' | 'map' | 'logs' | 'remote'
const TABS: { key: Tab; label: string; icon: React.ReactNode }[] = [
  { key: 'devices', label: 'Devices', icon: <Smartphone size={15} /> },
  { key: 'simCards', label: 'SIM Cards', icon: <Cpu size={15} /> },
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

  const [filterGroup, setFilterGroup] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [uploadingApk, setUploadingApk] = useState(false)
  const [serverApkName, setServerApkName] = useState<string | null>(null)
  const [serverGuardianApkName, setServerGuardianApkName] = useState<string | null>(null)

  const fetchApkInfo = async () => {
    try {
      const [resApk, resGuardian] = await Promise.all([
        fetch('/api/public/apk/version'),
        fetch('/api/public/guardian/version')
      ])
      
      if (resApk.ok) {
        const info = await resApk.json()
        if (info.available) setServerApkName(info.versionName)
      }
      
      if (resGuardian.ok) {
        const info = await resGuardian.json()
        if (info.available) setServerGuardianApkName(info.versionName)
      }
    } catch {}
  }

  useEffect(() => { fetchApkInfo() }, [])
  const [expandedRows, setExpandedRows] = useState<number[]>([])
  const toggleRow = (id: number) => setExpandedRows(prev => prev.includes(id) ? prev.filter(r => r !== id) : [...prev, id])
  const [uploadSuccess, setUploadSuccess] = useState(false)
  const [autostartToast, setAutostartToast] = useState<string | null>(null)
  const [otaPushing, setOtaPushing] = useState(false)
  const [otaToast, setOtaToast] = useState<string | null>(null)
  const [otaGuardianPushing, setOtaGuardianPushing] = useState(false)
  const [otaGuardianToast, setOtaGuardianToast] = useState<string | null>(null)
  
  const [confirmAction, setConfirmAction] = useState<{ title: string, message: string, onConfirm: () => void } | null>(null)
  const stompRef = useRef<Client | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const [setupMatrixForDevice, setSetupMatrixForDevice] = useState<Device | null>(null)

  const { data: devices = [], refetch, isFetching } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,

    staleTime: Infinity,
    placeholderData: (prev: any) => prev,
  })

  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: perfScores = {} } = useQuery({
    queryKey: ['device-performance'],
    queryFn: getDevicePerformance,
    refetchInterval: 30_000,
  })
  const { data: allSims = [] } = useQuery({ queryKey: ['sim-cards'], queryFn: getSimCards })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState({ name: '', hardwareId: '', groupId: '', sim1Id: '', sim2Id: '' })

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

  const fileGuardianRef = useRef<HTMLInputElement>(null)
  const [uploadingGuardianApk, setUploadingGuardianApk] = useState(false)
  const [uploadGuardianSuccess, setUploadGuardianSuccess] = useState(false)

  const handleGuardianFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploadingGuardianApk(true)
    const formData = new FormData()
    formData.append('file', file)
    const token = localStorage.getItem('jwt')
    try {
      const res = await fetch('/api/apk/upload-guardian', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${token}` },
        body: formData
      })
      if (res.ok) {
        setUploadGuardianSuccess(true)
        setTimeout(() => setUploadGuardianSuccess(false), 20000)
        fetchApkInfo()
      }
      else alert('Failed to upload Guardian APK.')
    } catch (err) {
      alert('Error uploading Guardian APK')
    } finally {
      setUploadingGuardianApk(false)
      if (fileGuardianRef.current) fileGuardianRef.current.value = ''
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

  const reset = () => { setShowForm(false); setEditing(null); setForm({ name: '', hardwareId: '', groupId: '', sim1Id: '', sim2Id: '' }) }
  const openEdit = (d: Device) => {
    const sim1 = d.simCards?.find(s => s.slotIndex === 0)
    const sim2 = d.simCards?.find(s => s.slotIndex === 1)
    setEditing(d)
    setForm({ 
      name: d.name, 
      hardwareId: d.hardwareId ?? '', 
      groupId: String(d.group?.id ?? ''),
      sim1Id: sim1 ? String(sim1.id) : '',
      sim2Id: sim2 ? String(sim2.id) : ''
    })
    setShowForm(true)
    setExpandedRows(prev => prev.includes(d.id) ? prev : [...prev, d.id])
  }
  const save = async () => {
    const payload = { name: form.name, hardwareId: form.hardwareId, groupId: form.groupId ? Number(form.groupId) : null }
    let savedDevice: Device;
    
    if (editing) {
       savedDevice = await updateMut.mutateAsync({ id: editing.id, d: payload })
       // Also sync SIM assignments
       await assignSimCard(Number(form.sim1Id) || 0, form.sim1Id ? editing.id : null).catch(() => {})
       await assignSimCard(Number(form.sim2Id) || 0, form.sim2Id ? editing.id : null).catch(() => {})
       qc.invalidateQueries({ queryKey: ['sim-cards'] })
       qc.invalidateQueries({ queryKey: ['devices'] })
    }
    else {
      try {
        const result = await createMut.mutateAsync(payload)
        qc.invalidateQueries({ queryKey: ['devices'] })
      } catch (e) {
        // Handle error
      }
    }
    reset()
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
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, autoRebootEnabled: !d.autoRebootEnabled } })
  }

  const toggleSilentMode = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, silentMode: !d.silentMode } })
  }

  const toggleCallBlock = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, callBlockEnabled: !d.callBlockEnabled } })
  }

  const setAutoPurge = (d: Device, value: string) => {
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, autoPurge: value } })
  }

  const setSendInterval = (d: Device, value: number) => {
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, sendIntervalSeconds: value } })
  }

  const toggleSelfHealing = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { ...d, groupId: d.group?.id, selfHealingEnabled: !d.selfHealingEnabled } })
  }

  const statusClass = (s: Device['status']) =>
    ({ ONLINE: 'pill-green', OFFLINE: 'pill-gray', BUSY: 'pill-yellow', MAINTENANCE: 'pill-yellow' }[s])

  // Filtered devices for the table
  const filteredDevices = devices.filter((d: Device) => {
    if (filterGroup && String(d.group?.id ?? '') !== filterGroup) return false
    if (filterStatus && d.status !== filterStatus) return false
    return true
  })

  // Pagination logic
  const [currentPage, setCurrentPage] = useState(1)
  const pageSize = 20
  const totalPages = Math.max(1, Math.ceil(filteredDevices.length / pageSize))
  let validPage = currentPage
  if (validPage > totalPages) validPage = totalPages
  if (validPage < 1) validPage = 1

  const startIndex = (validPage - 1) * pageSize
  const paginatedDevices = filteredDevices.slice(startIndex, startIndex + pageSize)

  const handleExpandAll = () => setExpandedRows((paginatedDevices as Device[]).map(d => d.id))
  const handleCollapseAll = () => setExpandedRows([])

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
          <h1 className="text-xl font-bold text-white flex items-center gap-3">
            Devices
          </h1>
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
          <div className="flex flex-wrap items-end justify-end gap-3 mt-2 md:mt-0">

            <input type="file" accept=".apk" ref={fileRef} className="hidden" onChange={handleFileUpload} />
            <input type="file" accept=".apk" ref={fileGuardianRef} className="hidden" onChange={handleGuardianFileUpload} />
            
            {/* Upload Group */}
            <div className="flex items-center rounded-lg border border-slate-700 bg-slate-800/50 p-0.5 shadow-sm">
              <button 
                className={`flex items-center gap-1.5 text-xs font-medium py-1.5 px-3 rounded-md transition-colors ${uploadSuccess ? 'bg-emerald-500/20 text-emerald-400' : 'text-slate-300 hover:bg-slate-700 hover:text-white'}`} 
                onClick={() => fileRef.current?.click()} 
                disabled={uploadingApk} 
                title="Upload Agent APK">
                {uploadingApk ? <RefreshCw size={12} className="animate-spin" /> : uploadSuccess ? <Check size={12} /> : <Upload size={12} />}
                Agent
              </button>
              <div className="w-px h-4 bg-slate-700 mx-0.5"></div>
              <button 
                className={`flex items-center gap-1.5 text-xs font-medium py-1.5 px-3 rounded-md transition-colors ${uploadGuardianSuccess ? 'bg-amber-500/20 text-amber-400' : 'text-slate-300 hover:bg-slate-700 hover:text-white'}`} 
                onClick={() => fileGuardianRef.current?.click()} 
                disabled={uploadingGuardianApk} 
                title="Upload Guardian APK">
                {uploadingGuardianApk ? <RefreshCw size={12} className="animate-spin" /> : uploadGuardianSuccess ? <Check size={12} /> : <ShieldCheck size={12} />}
                Guardian
              </button>
            </div>

            {/* OTA Push Group */}
            <div className="flex items-end gap-3">
              <div className="flex flex-col items-center gap-1.5">
                {serverApkName && (
                  <span className="text-[10px] text-emerald-300 font-medium px-2 py-px bg-emerald-500/10 rounded-md border border-emerald-500/20 shadow-sm whitespace-nowrap tracking-wide uppercase">
                    Agent v{serverApkName}
                  </span>
                )}
                <button
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium transition-all shadow-sm ${
                  otaToast 
                    ? 'bg-emerald-500/20 border-emerald-500/50 text-emerald-400' 
                    : 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400 hover:bg-emerald-500/20 hover:border-emerald-500/50'
                }`}
                disabled={otaPushing}
                title="Push Agent OTA to all online devices"
                onClick={() => {
                  const onlineDeviceIds = filteredDevices.filter((d: Device) => d.status === 'ONLINE' || d.status === 'BUSY').map((d: Device) => d.id)
                  if (onlineDeviceIds.length === 0) { alert('No online devices to update.'); return }
                  setConfirmAction({
                    title: 'Push Agent OTA',
                    message: `Send UPDATE_APK command to ${onlineDeviceIds.length} online device(s)?\nDevices will download and install the latest Agent APK from the server.`,
                    onConfirm: async () => {
                      setOtaPushing(true)
                      try {
                        const result = await bulkDeviceCommand(onlineDeviceIds, 'UPDATE_APK')
                        setOtaToast(`✓ OTA pushed (${result.sent})`)
                        setTimeout(() => setOtaToast(null), 5000)
                      } catch { alert('Failed to push OTA update') }
                      finally { setOtaPushing(false) }
                    }
                  })
                }}
              >
                {otaPushing ? <RefreshCw size={14} className="animate-spin" /> : otaToast ? <Check size={14} /> : <DownloadCloud size={14} />} 
                {otaToast || 'Push Agent OTA'}
              </button>
              </div>
              
              <div className="flex flex-col items-center gap-1.5">
                {serverGuardianApkName && (
                  <span className="text-[10px] text-amber-300 font-medium px-2 py-px bg-amber-500/10 rounded-md border border-amber-500/20 shadow-sm whitespace-nowrap tracking-wide uppercase">
                    Guardian v{serverGuardianApkName}
                  </span>
                )}
                <button
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium transition-all shadow-sm ${
                  otaGuardianToast 
                    ? 'bg-amber-500/20 border-amber-500/50 text-amber-400' 
                    : 'bg-amber-500/10 border-amber-500/30 text-amber-400 hover:bg-amber-500/20 hover:border-amber-500/50'
                }`}
                disabled={otaGuardianPushing}
                title="Push Guardian OTA to all online devices"
                onClick={() => {
                  const onlineDeviceIds = filteredDevices.filter((d: Device) => d.status === 'ONLINE' || d.status === 'BUSY').map((d: Device) => d.id)
                  if (onlineDeviceIds.length === 0) { alert('No online devices to update.'); return }
                  setConfirmAction({
                    title: 'Push Guardian OTA',
                    message: `Send UPDATE_GUARDIAN command to ${onlineDeviceIds.length} online device(s)?\nDevices will download and silently install the latest Guardian APK.`,
                    onConfirm: async () => {
                      setOtaGuardianPushing(true)
                      try {
                        const result = await bulkDeviceCommand(onlineDeviceIds, 'UPDATE_GUARDIAN')
                        setOtaGuardianToast(`✓ Guardian OTA pushed (${result.sent})`)
                        setTimeout(() => setOtaGuardianToast(null), 5000)
                      } catch { alert('Failed to push Guardian update') }
                      finally { setOtaGuardianPushing(false) }
                    }
                  })
                }}
              >
                {otaGuardianPushing ? <RefreshCw size={14} className="animate-spin" /> : otaGuardianToast ? <Check size={14} /> : <DownloadCloud size={14} />} 
                {otaGuardianToast || 'Push Guardian OTA'}
              </button>
              </div>
            </div>

            <div className="w-px h-6 bg-slate-700/50 mx-1 hidden sm:block"></div>

            <button
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-600 bg-slate-800 text-slate-300 text-xs font-medium hover:bg-slate-700 hover:text-white transition-colors"
              onClick={handleRefresh}
              disabled={isFetching}
              title="Refresh device statuses"
            >
              <RefreshCw size={13} className={isFetching ? 'animate-spin' : ''} /> Refresh
            </button>
            <button id="add-device-btn" className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-brand-500 bg-brand-600 text-white text-xs font-medium hover:bg-brand-500 transition-colors shadow-[0_0_10px_rgba(59,130,246,0.3)]" onClick={() => setShowForm(true)}>
              <Plus size={14} /> Add Device
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

      {showForm && !editing && (
        <div className="glass p-5 flex flex-col items-center justify-center space-y-4 mb-6">
          <h2 className="text-lg font-semibold text-slate-300">Add New Device</h2>
          <p className="text-sm text-slate-400 text-center max-w-sm">
            Scan this QR code with the target device to automatically download and install the Guardian system.
          </p>
          <div className="bg-white p-4 rounded-xl shadow-lg border-4 border-slate-200">
            <QRCodeSVG value={`${window.location.protocol}//${window.location.host}/api/public/guardian/download`} size={200} />
          </div>
          <a href="/api/public/guardian/download" className="text-brand-400 text-xs hover:underline mt-2">
            Or click here for direct download link
          </a>
          <div className="flex gap-2 justify-center w-full mt-4">
            <button className="btn-secondary" onClick={reset}><X size={15} /> Close</button>
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
        <div className="flex items-center gap-3 ml-auto mt-4">
          <div className="flex items-center bg-slate-800/50 rounded-lg p-1 mr-2 border border-slate-700/50">
            <button className="px-2 py-1 text-[10px] uppercase font-bold text-slate-400 hover:text-emerald-400 hover:bg-slate-700/50 rounded transition" onClick={handleExpandAll}>Expand All</button>
            <div className="w-px h-3 bg-slate-700 mx-1"></div>
            <button className="px-2 py-1 text-[10px] uppercase font-bold text-slate-400 hover:text-red-400 hover:bg-slate-700/50 rounded transition" onClick={handleCollapseAll}>Collapse All</button>
          </div>
          <span className="text-xs text-slate-500">{filteredDevices.length} of {devices.length} devices</span>
          
          <div className="h-4 w-px bg-slate-700/50"></div>
          
          <div className="flex items-center gap-2">
            <button 
              className="p-1 px-2 rounded-md hover:bg-slate-700 text-slate-400 disabled:opacity-30 disabled:hover:bg-transparent"
              disabled={validPage <= 1}
              onClick={() => setCurrentPage(p => p - 1)}
            >
              &lt; Prev
            </button>
            <span className="text-xs text-slate-300 font-medium">Page {validPage} of {totalPages}</span>
            <button 
              className="p-1 px-2 rounded-md hover:bg-slate-700 text-slate-400 disabled:opacity-30 disabled:hover:bg-transparent"
              disabled={validPage >= totalPages}
              onClick={() => setCurrentPage(p => p + 1)}
            >
              Next &gt;
            </button>
          </div>
        </div>
      </div>

      <div className="glass">
        <table className="tbl">
          <thead><tr>
            <th className="px-4 pt-4 pb-3">Name</th>
            <th className="px-4">Status</th>
            <th className="px-4">Uptime</th>
            <th className="px-4">Last Seen</th>
            <th className="px-4">Device Group</th>
            <th className="px-4">Battery</th>
            <th className="px-4">Interface</th>
            <th className="px-4">Wi-Fi</th>
            <th className="px-4">GSM</th>
            <th className="px-4">SIMs</th>
            <th className="px-4">APK (v)</th>
            <th className="px-4">Guardian (v)</th>
            <th className="px-4">Auto OTA</th>
            <th className="px-4 pb-3">RCS</th>
            <th className="px-4 pb-3">Score</th>
            <th className="px-4 pb-3 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {paginatedDevices.map((d: Device) => (
              <Fragment key={d.id}>
                <tr className={`hover:bg-slate-800/20 transition-colors ${expandedRows.includes(d.id) ? 'bg-slate-800/40' : 'border-b border-slate-700/50'}`}>
                <td className="px-3 py-2 align-top min-w-[200px] max-w-[280px]">
                  <div className="flex flex-wrap items-center gap-2 mb-1">
                    <span className="font-medium text-slate-200">{d.name}</span>
                    <span className="text-[9px] text-slate-500 font-mono tracking-tight bg-slate-800/50 px-1 rounded truncate max-w-[150px] inline-block">{d.hardwareId ? d.hardwareId : `ID: #${d.id}`}</span>
                  </div>
                  <div className="flex items-center flex-wrap gap-2">
                    {d.adbWifiAddress && <span className="text-[10px] text-teal-400 border border-teal-500/20 bg-teal-500/10 px-1 rounded font-mono hover:text-teal-300 transition cursor-pointer" onClick={() => navigator.clipboard.writeText(`adb connect ${d.adbWifiAddress}`)} title="Click to copy">🔌 {d.adbWifiAddress}</span>}
                  </div>
                </td>
                
                <td className="px-3 py-2 align-top">
                  <div className="flex flex-col gap-2">
                    <span className={`pill ${statusClass(d.status)} w-fit scale-90 origin-top-left`}>{d.status}</span>

                  </div>
                </td>
                
                <td className="px-3 py-2 text-xs text-slate-300 font-medium whitespace-nowrap align-top">
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.status === 'ONLINE' ? <LiveUptime connectedAt={d.connectedAt} /> : '—'}</div>

                  </div>
                </td>

                <td className="px-3 py-2 text-[10px] text-slate-500 whitespace-nowrap align-top">
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.lastHeartbeat ? format(new Date(d.lastHeartbeat), 'h:mm a').toLowerCase() : 'never'}</div>
                  </div>
                </td>
                
                <td className="px-3 py-2 text-xs align-top">
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.group?.name ? <span className="bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 px-1.5 py-0.5 rounded-full font-medium whitespace-nowrap text-[10px]">{d.group.name}</span> : <span className="text-slate-500 italic">—</span>}</div>

                  </div>
                </td>
                
                <td className="px-3 py-2 text-xs align-top whitespace-nowrap">
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.batteryPercent != null ? <div className={`flex items-center gap-1 font-medium ${d.isCharging ? 'text-green-400' : 'text-red-400'}`}>{d.batteryPercent}%{d.isCharging ? <BatteryCharging size={11} className="animate-pulse" /> : <Battery size={11} />}</div> : <span className="text-slate-300">—</span>}</div>

                  </div>
                </td>
                
                <td className="px-3 py-2 text-xs font-semibold text-brand-300 align-top">
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.activeNetworkType || '—'}</div>

                  </div>
                </td>
                
                <td className={`px-3 py-2 text-xs align-top whitespace-nowrap ${d.activeNetworkType === 'WIFI' ? 'text-emerald-400 font-bold' : 'text-slate-300'}`}>
                  <div className="flex flex-col gap-2">
                    <div className="h-4 flex items-center">{d.wifiSignalDbm != null ? `${d.wifiSignalDbm} dBm` : '—'}</div>

                  </div>
                </td>

                <td className={`px-3 py-2 text-xs align-top whitespace-nowrap ${d.activeNetworkType === 'GSM' || d.activeNetworkType === 'CELLULAR' ? 'text-emerald-400 font-bold' : 'text-slate-300'}`}>
                  <div className="h-4 flex items-center">{d.gsmSignalDbm != null ? `${d.gsmSignalDbm} dBm` : '—'}</div>
                </td>
                
                <td className="px-3 py-2 text-xs align-top whitespace-nowrap">
                   {d.simCards && d.simCards.length > 0 ? (
                       <div className="flex flex-col gap-1">
                          <span className="text-brand-400 font-medium text-[10px] bg-brand-500/10 border border-brand-500/20 px-1.5 py-0.5 rounded w-fit">{d.simCards.length} SIM{d.simCards.length > 1 ? 's' : ''}</span>
                          {d.simCards.slice(0, 2).map((s: any) => (
                              <span key={s.id} className="text-[10px] text-slate-400">{s.phoneNumber || 'No Number'}</span>
                          ))}
                          {d.simCards.length > 2 && <span className="text-[10px] text-slate-500">+{d.simCards.length - 2} more...</span>}
                       </div>
                   ) : <span className="text-slate-500 italic">—</span>}
                </td>
                
                <td className="px-3 py-2 text-xs font-medium text-emerald-300 align-top whitespace-nowrap">
                  <div className="h-4 flex items-center">{d.apkUpdateStatus ? <div className="flex items-center gap-1 text-emerald-400 animate-pulse"><RefreshCw size={10} className="animate-spin text-emerald-400" /> {d.apkUpdateStatus}</div> : (d.apkVersion || '—')}</div>
                </td>
                
                <td className="px-3 py-2 text-xs font-medium text-amber-300 align-top whitespace-nowrap">
                  <div className="h-4 flex items-center">{d.guardianVersion || '—'}</div>
                </td>
                
                <td className="px-3 py-2 align-top">
                  <div className="h-4 flex items-center">
                    <label className="relative inline-flex items-center cursor-pointer transform scale-75 origin-left">
                      <input type="checkbox" className="sr-only peer" checked={d.autoUpdate ?? true} onChange={async (e) => { const val = e.target.checked; await fetch(`/api/devices/${d.id}/auto-update`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('jwt')}` }, body: JSON.stringify({ autoUpdate: val }) }); refetch(); }} />
                      <div className="w-9 h-5 bg-slate-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-slate-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-brand-500"></div>
                    </label>
                  </div>
                </td>
                
                <td className="px-3 py-2 align-top">
                  <div className="h-4 flex items-center">
                    <span className={`text-[9px] px-1.5 py-0.5 rounded-sm font-medium ${d.rcsCapable ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30' : 'bg-slate-700/50 text-slate-400 border border-slate-700'}`}>{d.rcsCapable ? 'Yes' : 'No'}</span>
                  </div>
                </td>
                

                <td className="px-3 py-2 align-top">
                  {(() => {
                    const perf = (perfScores as any)[d.id]; if (!perf) return <span className="text-slate-500 text-xs">—</span>;
                    const s2h = perf.score2h; const s7d = perf.score7d;
                    if (!s2h && !s7d) return <span className="text-slate-500 text-xs">—</span>;
                    const scoreColor = (s: number) => s >= 80 ? 'text-emerald-400' : s >= 50 ? 'text-amber-400' : 'text-red-400';
                    const barColor = (s: number) => s >= 80 ? 'bg-emerald-500' : s >= 50 ? 'bg-amber-500' : 'bg-red-500';
                    return (
                      <div className="space-y-1.5 min-w-[70px]">
                        {s2h && <div className="flex items-center gap-1"><span className="text-[8px] text-slate-500 w-3">2h</span><div className="flex-1 h-1.5 bg-slate-700 rounded overflow-hidden"><div className={`h-full ${barColor(s2h.score)}`} style={{ width: `${s2h.score}%` }} /></div><span className={`text-[8px] font-bold ${scoreColor(s2h.score)} w-[14px]`}>{s2h.score}</span></div>}
                        {s7d && <div className="flex items-center gap-1"><span className="text-[8px] text-slate-500 w-3">7d</span><div className="flex-1 h-1.5 bg-slate-700 rounded overflow-hidden"><div className={`h-full ${barColor(s7d.score)}`} style={{ width: `${s7d.score}%` }} /></div><span className={`text-[8px] font-bold ${scoreColor(s7d.score)} w-[14px]`}>{s7d.score}</span></div>}
                      </div>
                    )
                  })()}
                </td>

                <td className="px-3 py-2 align-top text-right">
                  <div className="relative group inline-block">
                    <button 
                      className={`btn-secondary flex flex-col items-center justify-center p-1.5 min-w-[32px] rounded-lg border transition-all duration-300 ${expandedRows.includes(d.id) ? 'bg-indigo-500/20 shadow-[0_0_15px_rgba(99,102,241,0.2)] border-indigo-500/50 text-indigo-400' : 'bg-slate-800 hover:bg-slate-700 border-slate-700 text-slate-300'}`}
                      onClick={() => toggleRow(d.id)}
                    >
                      <Settings size={14} className={`transition-transform duration-500 ${expandedRows.includes(d.id) ? 'rotate-180' : ''}`} />
                      {expandedRows.includes(d.id) && <ChevronDown size={10} className="mt-0.5 opacity-80 animate-bounce" />}
                    </button>
                    <span className="absolute bottom-full right-0 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2.5 py-1 whitespace-nowrap z-50 shadow-xl after:content-[''] after:absolute after:top-full after:right-2.5 after:-mt-px after:border-4 after:border-transparent after:border-t-slate-700">
                      {expandedRows.includes(d.id) ? 'Hide Actions' : 'Settings'}
                    </span>
                  </div>
                </td>
              </tr>
              {expandedRows.includes(d.id) && (
                <tr className="border-b-[3px] border-indigo-500/30 bg-[#0d0d1a] shadow-[inset_0_-2px_15px_rgba(0,0,0,0.5)]">
                  <td colSpan={16} className="p-2">
                    <div className="flex flex-wrap items-center justify-between gap-4 p-2 bg-slate-800/40 border border-slate-700/50 rounded-lg shadow-inner w-full">
                      
                      <div className="flex flex-wrap items-center justify-start gap-3">
                        <div className="flex items-center gap-3 bg-[#12121f] px-2 py-1.5 rounded border border-slate-700/50">
                          <label className="flex items-center gap-1 text-[9px] text-slate-500 uppercase tracking-wider font-bold">
                            Auto Purge 
                            <span className="relative group">
                              <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                              <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2 py-1.5 whitespace-nowrap z-50 shadow-xl">
                                Automatically removes old data<br/>to prevent device storage full.
                              </span>
                            </span>
                          </label>
                          <select className="bg-slate-800 text-[10px] text-slate-300 border border-slate-700 rounded px-1.5 py-0.5 w-[75px]" value={d.autoPurge ?? 'OFF'} onChange={e => setAutoPurge(d, e.target.value)}>
                            <option value="OFF">Off</option>
                            <option value="MESSAGES">Messages</option>
                            <option value="SYSTEM_LOGS">Sys Logs</option>
                            <option value="ALL">All</option>
                          </select>
                        </div>
                        
                        <div className="flex items-center gap-3 bg-[#12121f] px-2 py-1.5 rounded border border-slate-700/50">
                          <label className="flex items-center gap-1 text-[9px] text-slate-500 uppercase tracking-wider font-bold">
                            Interval
                            <span className="relative group">
                              <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                              <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2 py-1.5 whitespace-nowrap z-50 shadow-xl">
                                How often the device sends<br/>heartbeats and pending messages.
                              </span>
                            </span>
                          </label>
                          <div className="relative">
                            <input type="number" step="0.5" min="0" className="bg-slate-800 text-[10px] text-slate-300 border border-slate-700 rounded pl-2 pr-4 py-0.5 w-[55px] text-center" value={d.sendIntervalSeconds ?? 0} onChange={e => setSendInterval(d, parseFloat(e.target.value) || 0)} />
                            <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[8px] text-slate-500">s</span>
                          </div>
                        </div>

                        <div className="h-6 w-px bg-slate-700/50 mx-1"></div>

                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-1.5 py-1 hover:bg-slate-700/30 rounded transition">
                          <input type="checkbox" className="accent-brand-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.autoRebootEnabled ?? false} onChange={() => toggleAutoReboot(d)} /> Auto Reboot
                          <span className="relative group z-50">
                            <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                            <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2.5 py-2 whitespace-nowrap shadow-xl">
                              Reboots device when (last 2h):<br/>
                              • Delivery rate &lt; 50%, OR<br/>
                              • Avg latency &gt; 30 seconds<br/>
                            </span>
                          </span>
                        </label>

                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-1.5 py-1 hover:bg-slate-700/30 rounded transition">
                          <input type="checkbox" className="accent-cyan-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.selfHealingEnabled ?? false} onChange={() => toggleSelfHealing(d)} /> Heal
                          <span className="relative group z-50">
                            <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                            <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2.5 py-2 whitespace-nowrap shadow-xl">
                              Restarts Google Messages bot:<br/>
                              • After 3 consecutive send-button failures<br/>
                              Checked every 5 minutes.
                            </span>
                          </span>
                        </label>
                        
                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-1.5 py-1 hover:bg-slate-700/30 rounded transition">
                          <input type="checkbox" className="accent-amber-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.silentMode ?? false} onChange={() => toggleSilentMode(d)} /> Silent Mode
                        </label>
                        
                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-1.5 py-1 hover:bg-slate-700/30 rounded transition">
                          <input type="checkbox" className="accent-red-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.callBlockEnabled ?? false} onChange={() => toggleCallBlock(d)} /> Call Block
                        </label>
                      </div>

                      <div className="flex flex-wrap items-center justify-end gap-1.5">
                        <button className="btn-primary flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-medium text-brand-300 bg-brand-500/10 border border-brand-500/20 hover:bg-brand-500/20 rounded shadow-sm transition" title="Matrix Setup Guide" onClick={() => setSetupMatrixForDevice(d)}><QrCode size={13} /> Matrix</button>
                        <button className={`btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded border border-slate-700 text-[11px] font-medium shadow-sm transition ${d.autostartPinned ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30' : 'bg-slate-800 text-amber-400 hover:bg-slate-700'}`} title="Pin Autostart (MIUI protection)" onClick={() => confirmAndSendCommand(d.id, 'PIN_AUTOSTART')}><ShieldCheck size={13} /> Autostart</button>
                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-emerald-500/20 border border-slate-700 text-emerald-400 text-[11px] font-medium shadow-sm transition" title="Push Messaging Agent APK Update" onClick={() => confirmAndSendCommand(d.id, 'UPDATE_APK')}><DownloadCloud size={13} /> Agent OTA</button>
                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-amber-500/20 border border-slate-700 text-amber-400 text-[11px] font-medium shadow-sm transition" title="Push Guardian APK Update" onClick={() => confirmAndSendCommand(d.id, 'UPDATE_GUARDIAN')}><DownloadCloud size={13} /> Guardian OTA</button>
                        
                        <div className="w-px h-6 bg-slate-700/50 mx-1 hidden sm:block"></div>

                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-slate-700 border border-slate-700 text-emerald-400 text-[11px] font-medium shadow-sm transition" title="Reconnect" onClick={() => confirmAndSendCommand(d.id, 'RECONNECT')}><RefreshCcw size={13} /> Reconnect</button>
                        <button className="btn-danger flex items-center gap-1.5 px-3 py-1.5 rounded bg-red-500/10 hover:bg-red-500/20 border border-red-500/20 text-red-400 text-[11px] font-medium shadow-sm transition" title="Reboot" onClick={() => confirmAndSendCommand(d.id, 'REBOOT')}><Power size={13} /> Reboot</button>
                        <div className="w-px h-6 bg-slate-700/50 mx-1 hidden sm:block"></div>
                        <button className="btn-secondary flex items-center justify-center p-2 rounded bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 shadow-sm transition" title="Edit" onClick={() => openEdit(d)}><Pencil size={13} /></button>
                        <button className="btn-danger flex items-center justify-center p-2 rounded bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 shadow-sm transition" title="Delete" onClick={() => setConfirmAction({ title: 'Delete Device', message: `Delete ${d.name}?`, onConfirm: () => deleteMut.mutate(d.id) })}><Trash2 size={13} /></button>
                      </div>
                    </div>
                  </td>
                </tr>
              )}

              {showForm && editing?.id === d.id && (
                <tr className="border-b-[3px] border-indigo-500/10 bg-[#0a0a14] shadow-inner">
                  <td colSpan={15} className="p-4">
                    <div className="p-4 bg-slate-800/60 rounded-xl border border-indigo-500/30 w-full relative">
                      <h2 className="text-sm font-semibold text-slate-200 mb-4 pb-2 border-b border-slate-700/50">Edit Device: {d.name}</h2>
                      <div className="grid grid-cols-1 md:grid-cols-5 gap-4 mb-4">
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">Device Name *</label>
                          <input id="device-name" className="inp w-full" placeholder="Pixel 8 #1"
                            value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">Hardware ID</label>
                          <input className="inp w-full text-slate-500" placeholder="16-digit android ID"
                            value={form.hardwareId} onChange={e => setForm(f => ({ ...f, hardwareId: e.target.value }))} readOnly={!!editing} />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">Group</label>
                          <select className="inp w-full bg-slate-800/90"
                            value={form.groupId} onChange={e => setForm(f => ({ ...f, groupId: e.target.value }))}>
                            <option value="">— No group —</option>
                            {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
                          </select>
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">SIM 1 Assignment</label>
                          <select className="inp w-full bg-slate-800/90"
                            value={form.sim1Id} onChange={e => setForm(f => ({ ...f, sim1Id: e.target.value }))}>
                            <option value="">— Unassigned —</option>
                            {allSims.filter((s: SimCard) => !s.device || s.device.id === d.id).map((s: SimCard) => (
                              <option key={s.id} value={s.id}>{s.phoneNumber || s.iccid}</option>
                            ))}
                          </select>
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">SIM 2 Assignment</label>
                          <select className="inp w-full bg-slate-800/90"
                            value={form.sim2Id} onChange={e => setForm(f => ({ ...f, sim2Id: e.target.value }))}>
                            <option value="">— Unassigned —</option>
                            {allSims.filter((s: SimCard) => !s.device || s.device.id === d.id).map((s: SimCard) => (
                              <option key={s.id} value={s.id}>{s.phoneNumber || s.iccid}</option>
                            ))}
                          </select>
                        </div>
                      </div>
                      <div className="flex gap-2 justify-end pt-2">
                        <button className="btn-secondary" onClick={reset}><X size={15} /> Cancel</button>
                        <button id="save-device-btn" className="btn-primary" onClick={save}><Check size={15} /> Save Changes</button>
                      </div>
                    </div>
                  </td>
                </tr>
              )}

              </Fragment>
            ))}
            {filteredDevices.length === 0 && (
              <tr><td colSpan={15} className="px-4 py-8 text-center text-slate-500">{devices.length === 0 ? 'No devices registered' : 'No devices match the current filters'}</td></tr>
            )}
          </tbody>
          </table>
      </div>
      </>)}

      {activeTab === 'simCards' && <SimCardsPage />}
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
