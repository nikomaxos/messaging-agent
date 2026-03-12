import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getDevices, getGroups, createDevice, updateDevice, deleteDevice } from '../api/client'
import { Device, DeviceGroup } from '../types'
import { Plus, Pencil, Trash2, X, Check, Copy } from 'lucide-react'
import { formatDistanceToNow } from 'date-fns'

export default function DevicesPage() {
  const qc = useQueryClient()
  const { data: devices = [] } = useQuery({ queryKey: ['devices'], queryFn: getDevices, refetchInterval: 5000 })
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<Device | null>(null)
  const [form, setForm] = useState({ name: '', imei: '', groupId: '' })

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
    const payload = { name: form.name, imei: form.imei || null, groupId: form.groupId ? Number(form.groupId) : null }
    if (editing) updateMut.mutate({ id: editing.id, d: payload })
    else createMut.mutate(payload)
  }

  const copyToken = (token?: string) => token && navigator.clipboard.writeText(token)

  const statusClass = (s: Device['status']) =>
    ({ ONLINE: 'pill-green', OFFLINE: 'pill-gray', BUSY: 'pill-yellow', MAINTENANCE: 'pill-yellow' }[s])

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Devices</h1>
          <p className="text-slate-400 text-sm mt-0.5">Android phones in the device pool</p>
        </div>
        <button id="add-device-btn" className="btn-primary" onClick={() => setShowForm(true)}>
          <Plus size={16} /> Add Device
        </button>
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
            <th className="px-4">Group</th>
            <th className="px-4">Battery</th>
            <th className="px-4">Wi-Fi</th>
            <th className="px-4">GSM</th>
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
                  <div className="text-[10px] text-slate-500">{d.imei ?? '—'}</div>
                </td>
                <td className="px-4"><span className={`pill ${statusClass(d.status)}`}>{d.status}</span></td>
                <td className="px-4 text-slate-400 text-xs">{d.group?.name ?? '—'}</td>
                <td className="px-4 text-xs text-slate-300">{d.batteryPercent != null ? `${d.batteryPercent}%` : '—'}</td>
                <td className="px-4 text-xs text-slate-300">{d.wifiSignalDbm != null ? `${d.wifiSignalDbm} dBm` : '—'}</td>
                <td className="px-4 text-xs text-slate-300">{d.gsmSignalDbm != null ? `${d.gsmSignalDbm} dBm` : '—'}</td>
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
                    <button
                      title="Copy token"
                      onClick={() => copyToken(d.registrationToken)}
                      className="text-slate-500 hover:text-brand-400 transition"
                    ><Copy size={13} /></button>
                  )}
                </td>
                <td className="px-4 text-right">
                  <div className="flex items-center justify-end gap-2">
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
