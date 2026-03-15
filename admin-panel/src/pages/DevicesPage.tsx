import { useState, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getDevices, getGroups, createDevice, updateDevice, deleteDevice } from '../api/client'
import { Device, DeviceGroup } from '../types'
import { Plus, Pencil, Trash2, X, Check, Copy, RefreshCw, Wifi, WifiOff, Power, RefreshCcw, Upload, DownloadCloud } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

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
  const [wsConnected, setWsConnected] = useState(false)
  const [refreshToast, setRefreshToast] = useState<string | null>(null)
  const [autoFetch, setAutoFetch] = useState(false)
  const [uploadingApk, setUploadingApk] = useState(false)
  const [uploadSuccess, setUploadSuccess] = useState(false)
  const stompRef = useRef<Client | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const { data: devices = [], refetch, isFetching } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: autoFetch ? 10_000 : false,
    staleTime: Infinity,
    placeholderData: (prev: any) => prev,
  })

  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState({ name: '', imei: '', groupId: '' })

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
        setTimeout(() => setUploadSuccess(false), 3000)
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
                      wifiSignalDbm: event.wifiSignalDbm !== undefined && event.wifiSignalDbm !== "" ? event.wifiSignalDbm : d.wifiSignalDbm,
                      gsmSignalDbm: event.gsmSignalDbm !== undefined && event.gsmSignalDbm !== "" ? event.gsmSignalDbm : d.gsmSignalDbm,
                      activeNetworkType: event.activeNetworkType !== undefined && event.activeNetworkType !== "" ? event.activeNetworkType : d.activeNetworkType,
                      apkVersion: event.apkVersion !== undefined && event.apkVersion !== "" ? event.apkVersion : d.apkVersion,
                      apkUpdateStatus: event.apkUpdateStatus !== undefined ? event.apkUpdateStatus : d.apkUpdateStatus }
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
    onSuccess: () => qc.invalidateQueries({ queryKey: ['devices'] }) })

  const reset = () => { setShowForm(false); setEditing(null); setForm({ name: '', imei: '', groupId: '' }) }
  const openEdit = (d: Device) => {
    setEditing(d); setForm({ name: d.name, imei: d.imei ?? '', groupId: String(d.group?.id ?? '') }); setShowForm(true)
  }
  const save = () => {
    const payload = { ...form, groupId: form.groupId ? Number(form.groupId) : null }
    if (editing) updateMut.mutate({ id: editing.id, d: payload })
    else createMut.mutate(payload)
  }

  const sendCommand = async (id: number, command: string) => {
    if (!window.confirm(`Send ${command} to device?`)) return
    const token = localStorage.getItem('jwt')
    await fetch(`/api/devices/${id}/command`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body: JSON.stringify({ command })
    })
  }

  const toggleAutoReboot = (d: Device) => {
    updateMut.mutate({ id: d.id, d: { name: d.name, imei: d.imei, groupId: d.group?.id, autoRebootEnabled: !d.autoRebootEnabled } })
  }

  const copyToken = (token?: string) => token && navigator.clipboard.writeText(token)

  const statusClass = (s: Device['status']) =>
    ({ ONLINE: 'pill-green', OFFLINE: 'pill-gray', BUSY: 'pill-yellow', MAINTENANCE: 'pill-yellow' }[s])

  return (
    <div className="p-6 space-y-6">
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
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1 cursor-pointer text-sm text-slate-300 mr-2 border border-slate-700 px-3 py-1.5 rounded-lg bg-slate-800/50 hover:bg-slate-700/50 transition">
            <input type="checkbox" className="accent-brand-500" checked={autoFetch} onChange={e => setAutoFetch(e.target.checked)} />
            Auto Fetch (10s)
          </label>
          <input type="file" accept=".apk" ref={fileRef} className="hidden" onChange={handleFileUpload} />
          <button className={`btn-secondary whitespace-nowrap ${uploadSuccess ? '!bg-green-600/20 !border-green-500/50 !text-green-400' : ''}`} onClick={() => fileRef.current?.click()} disabled={uploadingApk} title="Upload a new APK to the server">
            {uploadingApk ? <RefreshCw size={14} className="animate-spin" /> : uploadSuccess ? <Check size={14} /> : <Upload size={14} />} {uploadSuccess ? 'APK Uploaded' : 'Upload APK'}
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
      </div>

      {showForm && (
        <div className="glass p-5 space-y-4">
          <h2 className="text-sm font-semibold text-slate-300">{editing ? 'Edit' : 'New'} Device</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Device Name *</label>
              <input id="device-name" className="inp" placeholder="Pixel 8 #1"
                value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
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

      <div className="glass overflow-hidden">
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
            <th className="px-4">Token</th>
            <th className="px-4 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {devices.map((d: Device) => (
              <tr key={d.id}>
                <td className="px-4">
                  <div className="font-medium text-slate-200">{d.name}</div>
                  <div className="text-[10px] text-slate-500">{d.imei ? d.imei : `Device ID: #${d.id}`}</div>
                </td>
                <td className="px-4">
                  <span className={`pill ${statusClass(d.status)}`}>{d.status}</span>
                </td>
                <td className="px-4 text-xs text-slate-300 font-medium">
                  {d.status === 'ONLINE' ? <LiveUptime connectedAt={d.connectedAt} /> : '—'}
                </td>
                <td className="px-4 text-slate-400 text-xs">{d.group?.name ?? '—'}</td>
                <td className="px-4 text-xs text-slate-300">{d.batteryPercent != null ? `${d.batteryPercent}%` : '—'}</td>
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
                <td className="px-4 text-xs text-slate-500">
                  {d.lastHeartbeat ? formatDistanceToNow(new Date(d.lastHeartbeat), { addSuffix: true }) : 'never'}
                </td>
                <td className="px-4">
                  {d.registrationToken && (
                    <button title="Copy token"
                      onClick={() => copyToken(d.registrationToken)}
                      className="text-slate-500 hover:text-brand-400 transition"
                    ><Copy size={13} /></button>
                  )}
                </td>
                <td className="px-4 text-right">
                  <div className="flex items-center justify-end gap-2">
                    <label className="flex items-center gap-1 cursor-pointer text-[10px] text-slate-500 mr-2 leading-none" title="Auto-reboot if disconnected over 5m">
                      <input type="checkbox" className="accent-brand-500" checked={d.autoRebootEnabled ?? false} onChange={() => toggleAutoReboot(d)} />
                      Auto<br/>Reboot
                    </label>
                    <button className="btn-secondary !px-2 !py-1 text-brand-400" title="Push APK Update" onClick={() => sendCommand(d.id, 'UPDATE_APK')}><DownloadCloud size={13} /></button>
                    <button className="btn-secondary !px-2 !py-1 text-emerald-400" title="Reconnect" onClick={() => sendCommand(d.id, 'RECONNECT')}><RefreshCcw size={13} /></button>
                    <button className="btn-danger !px-2 !py-1" title="Reboot" onClick={() => sendCommand(d.id, 'REBOOT')}><Power size={13} /></button>
                    <button className="btn-secondary !px-2 !py-1" onClick={() => openEdit(d)}><Pencil size={13} /></button>
                    <button className="btn-danger !px-2 !py-1"
                      onClick={() => window.confirm('Delete device?') && deleteMut.mutate(d.id)}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {devices.length === 0 && (
              <tr><td colSpan={10} className="px-4 py-8 text-center text-slate-500">No devices registered</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
