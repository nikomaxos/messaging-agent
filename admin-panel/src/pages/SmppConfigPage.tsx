import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSmppConfigs, getGroups, createSmppConfig, updateSmppConfig, deleteSmppConfig } from '../api/client'
import { SmppConfig, DeviceGroup } from '../types'
import { Plus, Pencil, Trash2, X, Check } from 'lucide-react'

export default function SmppConfigPage() {
  const qc = useQueryClient()
  const { data: configs = [] } = useQuery({ queryKey: ['smpp-configs'], queryFn: getSmppConfigs })
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<SmppConfig | null>(null)
  const [form, setForm] = useState({
    name: '', systemId: '', password: '', host: '', port: '2775',
    bindType: 'TRANSCEIVER', active: true, deviceGroupId: ''
  })

  const createMut = useMutation({ mutationFn: createSmppConfig,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smpp-configs'] }); reset() } })
  const updateMut = useMutation({ mutationFn: ({ id, d }: any) => updateSmppConfig(id, d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smpp-configs'] }); reset() } })
  const deleteMut = useMutation({ mutationFn: deleteSmppConfig,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['smpp-configs'] }) })

  const reset = () => {
    setShowForm(false); setEditing(null)
    setForm({ name: '', systemId: '', password: '', host: '', port: '2775', bindType: 'TRANSCEIVER', active: true, deviceGroupId: '' })
  }
  const openEdit = (c: SmppConfig) => {
    setEditing(c)
    setForm({ name: c.name, systemId: c.systemId, password: c.password, host: c.host,
      port: String(c.port), bindType: c.bindType, active: c.active, deviceGroupId: String(c.deviceGroup?.id ?? '') })
    setShowForm(true)
  }
  const save = () => {
    const payload = { ...form, port: Number(form.port), deviceGroupId: form.deviceGroupId ? Number(form.deviceGroupId) : null }
    if (editing) updateMut.mutate({ id: editing.id, d: payload })
    else createMut.mutate(payload)
  }

  const f = (key: string, val: any) => setForm(prev => ({ ...prev, [key]: val }))

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">SMPP Configurations</h1>
          <p className="text-slate-400 text-sm mt-0.5">Upstream / downstream SMPP endpoint settings</p>
        </div>
        <button id="add-smpp-btn" className="btn-primary" onClick={() => setShowForm(true)}>
          <Plus size={16} /> New Config
        </button>
      </div>

      {showForm && (
        <div className="glass p-5 space-y-4">
          <h2 className="text-sm font-semibold text-slate-300">{editing ? 'Edit' : 'New'} SMPP Config</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {[
              { label: 'Config Name *', key: 'name', placeholder: 'My SMSC' },
              { label: 'System ID *', key: 'systemId', placeholder: 'PARTNER1' },
              { label: 'Password *', key: 'password', placeholder: 'secret', type: 'password' },
              { label: 'Host *', key: 'host', placeholder: 'smsc.provider.com' },
              { label: 'Port *', key: 'port', placeholder: '2775', type: 'number' },
            ].map(f2 => (
              <div key={f2.key}>
                <label className="block text-xs text-slate-400 mb-1.5">{f2.label}</label>
                <input className="inp" type={f2.type ?? 'text'} placeholder={f2.placeholder}
                  value={(form as any)[f2.key]}
                  onChange={e => f(f2.key, e.target.value)} />
              </div>
            ))}
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Bind Type</label>
              <select className="inp bg-slate-800/70" value={form.bindType} onChange={e => f('bindType', e.target.value)}>
                <option>TRANSCEIVER</option><option>TRANSMITTER</option><option>RECEIVER</option>
              </select>
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Device Group (Virtual SMSC)</label>
              <select className="inp bg-slate-800/70" value={form.deviceGroupId} onChange={e => f('deviceGroupId', e.target.value)}>
                <option value="">— None —</option>
                {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
              <input type="checkbox" checked={form.active} onChange={e => f('active', e.target.checked)}
                className="accent-brand-600 w-4 h-4" /> Active
            </label>
            <div className="ml-auto flex gap-2">
              <button className="btn-secondary" onClick={reset}><X size={15} /> Cancel</button>
              <button id="save-smpp-btn" className="btn-primary" onClick={save}><Check size={15} /> Save</button>
            </div>
          </div>
        </div>
      )}

      <div className="glass overflow-hidden">
        <table className="tbl">
          <thead><tr>
            <th className="px-4 pt-4 pb-3">Name</th>
            <th className="px-4">System ID</th>
            <th className="px-4">Host : Port</th>
            <th className="px-4">Bind Type</th>
            <th className="px-4">Group</th>
            <th className="px-4">Status</th>
            <th className="px-4 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {configs.map((c: SmppConfig) => (
              <tr key={c.id}>
                <td className="px-4 font-medium text-slate-200">{c.name}</td>
                <td className="px-4 text-xs font-mono text-slate-300">{c.systemId}</td>
                <td className="px-4 text-xs text-slate-400">{c.host}:{c.port}</td>
                <td className="px-4 text-xs text-slate-400">{c.bindType}</td>
                <td className="px-4 text-xs text-slate-400">{c.deviceGroup?.name ?? '—'}</td>
                <td className="px-4"><span className={`pill ${c.active ? 'pill-green' : 'pill-gray'}`}>{c.active ? 'Active' : 'Off'}</span></td>
                <td className="px-4 text-right">
                  <div className="flex items-center justify-end gap-2">
                    <button className="btn-secondary !px-2 !py-1" onClick={() => openEdit(c)}><Pencil size={13} /></button>
                    <button className="btn-danger !px-2 !py-1"
                      onClick={() => window.confirm('Delete?') && deleteMut.mutate(c.id)}><Trash2 size={13} /></button>
                  </div>
                </td>
              </tr>
            ))}
            {configs.length === 0 && (
              <tr><td colSpan={7} className="px-4 py-8 text-center text-slate-500">No SMPP configurations yet</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
