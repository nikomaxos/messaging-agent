import { useState, Fragment } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSimCards, getDevices, assignSimCard, updateSimCard } from '../api/client'
import { SimCard, Device } from '../types'
import { RefreshCw, Pencil, Check, X } from 'lucide-react'

export default function SimCardsPage() {
  const qc = useQueryClient()
  const { data: sims = [], isFetching } = useQuery({ queryKey: ['sim-cards'], queryFn: getSimCards })
  const { data: devices = [] } = useQuery({ queryKey: ['devices'], queryFn: getDevices })

  const [editingId, setEditingId] = useState<number | null>(null)
  const [isCreating, setIsCreating] = useState(false)
  const [form, setForm] = useState({ iccid: '', phoneNumber: '', imsi: '', carrierName: '', imei: '', slotIndex: '' })

  const assignMut = useMutation({
    mutationFn: ({ simId, deviceId }: { simId: number, deviceId: number | null }) => assignSimCard(simId, deviceId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sim-cards'] })
      qc.invalidateQueries({ queryKey: ['devices'] })
    }
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: number, data: any }) => updateSimCard(id, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sim-cards'] })
      setEditingId(null)
    }
  })

  const createMut = useMutation({
    mutationFn: (data: any) => {
       // Using api.post mapping added in client.ts
       return import('../api/client').then(m => m.createSimCard(data))
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sim-cards'] })
      setIsCreating(false)
    }
  })

  const handleAssign = (simId: number, deviceIdStr: string) => {
    const deviceId = deviceIdStr === '' ? null : Number(deviceIdStr)
    assignMut.mutate({ simId, deviceId })
  }

  const startEdit = (sim: SimCard) => {
    setIsCreating(false)
    setEditingId(sim.id)
    setForm({
      iccid: sim.iccid || '',
      phoneNumber: sim.phoneNumber || '',
      imsi: sim.imsi || '',
      carrierName: sim.carrierName || '',
      imei: sim.imei || '',
      slotIndex: sim.slotIndex !== undefined && sim.slotIndex !== -1 ? String(sim.slotIndex) : ''
    })
  }

  const startCreate = () => {
    setEditingId(null)
    setIsCreating(true)
    setForm({ iccid: '', phoneNumber: '', imsi: '', carrierName: '', imei: '', slotIndex: '' })
  }

  const saveEdit = (id: number) => {
    const payload = {
      phoneNumber: form.phoneNumber || null,
      imsi: form.imsi || null,
      carrierName: form.carrierName || null,
      imei: form.imei || null,
      slotIndex: form.slotIndex ? parseInt(form.slotIndex, 10) : null
    }
    updateMut.mutate({ id, data: payload })
  }

  const saveCreate = () => {
    const payload = {
      iccid: form.iccid || null,
      phoneNumber: form.phoneNumber || null,
      imsi: form.imsi || null,
      carrierName: form.carrierName || null,
      imei: form.imei || null,
      slotIndex: form.slotIndex ? parseInt(form.slotIndex, 10) : null
    }
    createMut.mutate(payload)
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white flex items-center gap-3">
          SIM Inventory
        </h1>
        <div className="flex gap-2">
          <button 
            className="btn-primary px-3 py-1.5 text-xs"
            onClick={startCreate}
          >
            + Add SIM Manually
          </button>
          <button 
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-600 bg-slate-800 text-slate-300 text-xs font-medium hover:bg-slate-700 hover:text-white transition-colors"
            onClick={() => qc.invalidateQueries({ queryKey: ['sim-cards'] })}
            disabled={isFetching}
          >
            <RefreshCw size={13} className={isFetching ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      <div className="glass">
        <table className="tbl">
          <thead>
            <tr>
              <th className="px-4 py-3">ICCID</th>
              <th className="px-4">Phone Number</th>
              <th className="px-4">IMSI / Carrier</th>
              <th className="px-4">Slot Index</th>
              <th className="px-4">Assigned Device</th>
              <th className="px-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {isCreating && (
              <tr className="border-b-[3px] border-emerald-500/10 bg-[#0a0a14] shadow-inner">
                <td colSpan={6} className="p-4">
                  <div className="p-4 bg-emerald-900/10 rounded-xl border border-emerald-500/30 w-full relative grid grid-cols-1 md:grid-cols-5 gap-4">
                    <div>
                      <label className="block text-xs text-slate-400 mb-1.5">ICCID (Hardware ID)</label>
                      <input className="inp w-full" value={form.iccid} onChange={e => setForm(f => ({ ...f, iccid: e.target.value }))} placeholder="Auto-generated if empty" />
                    </div>
                    <div>
                      <label className="block text-xs text-slate-400 mb-1.5">Phone Number</label>
                      <input className="inp w-full" value={form.phoneNumber} onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} placeholder="+1234567890" />
                    </div>
                    <div>
                      <label className="block text-xs text-slate-400 mb-1.5">Carrier</label>
                      <input className="inp w-full" value={form.carrierName} onChange={e => setForm(f => ({ ...f, carrierName: e.target.value }))} placeholder="Vodafone" />
                    </div>
                    <div>
                      <label className="block text-xs text-slate-400 mb-1.5">IMSI</label>
                      <input className="inp w-full" value={form.imsi} onChange={e => setForm(f => ({ ...f, imsi: e.target.value }))} placeholder="Optional" />
                    </div>
                    <div>
                      <label className="block text-xs text-slate-400 mb-1.5">IMEI</label>
                      <input className="inp w-full" value={form.imei} onChange={e => setForm(f => ({ ...f, imei: e.target.value }))} placeholder="Optional" />
                    </div>
                    <div className="col-span-1 md:col-span-1">
                      <label className="block text-xs text-slate-400 mb-1.5">Slot Index</label>
                      <input type="number" min="0" max="1" className="inp w-full" value={form.slotIndex} onChange={e => setForm(f => ({ ...f, slotIndex: e.target.value }))} placeholder="0 or 1" />
                    </div>
                    <div className="col-span-1 md:col-span-4 flex justify-end gap-2 items-end mt-2">
                       <button className="btn-secondary" onClick={() => setIsCreating(false)}><X size={14} /> Cancel</button>
                       <button className="btn-primary" onClick={saveCreate} disabled={createMut.isPending}>{createMut.isPending ? 'Creating...' : <><Check size={14} /> Create Manual SIM</>}</button>
                    </div>
                  </div>
                </td>
              </tr>
            )}
            {sims.map((sim: SimCard) => (
              <Fragment key={sim.id}>
                {editingId === sim.id ? (
                  <tr className="border-b-[3px] border-indigo-500/10 bg-[#0a0a14] shadow-inner">
                    <td colSpan={6} className="p-4">
                      <div className="p-4 bg-slate-800/60 rounded-xl border border-indigo-500/30 w-full relative grid grid-cols-1 md:grid-cols-5 gap-4">
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">ICCID (Hardware ID)</label>
                          <input className="inp w-full text-slate-500 cursor-not-allowed" readOnly value={sim.iccid} />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">Phone Number</label>
                          <input className="inp w-full" value={form.phoneNumber} onChange={e => setForm(f => ({ ...f, phoneNumber: e.target.value }))} placeholder="+1234567890" />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">Carrier</label>
                          <input className="inp w-full" value={form.carrierName} onChange={e => setForm(f => ({ ...f, carrierName: e.target.value }))} placeholder="Vodafone" />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">IMSI</label>
                          <input className="inp w-full" value={form.imsi} onChange={e => setForm(f => ({ ...f, imsi: e.target.value }))} placeholder="Optional" />
                        </div>
                        <div>
                          <label className="block text-xs text-slate-400 mb-1.5">IMEI</label>
                          <input className="inp w-full" value={form.imei} onChange={e => setForm(f => ({ ...f, imei: e.target.value }))} placeholder="Optional" />
                        </div>
                        <div className="col-span-1 md:col-span-5 flex justify-end gap-2 mt-2">
                          <button className="btn-secondary" onClick={() => setEditingId(null)}><X size={14} /> Cancel</button>
                          <button className="btn-primary" onClick={() => saveEdit(sim.id)}><Check size={14} /> Save Changes</button>
                        </div>
                      </div>
                    </td>
                  </tr>
                ) : (
                  <tr className="border-b border-slate-700/50 hover:bg-slate-800/20 transition-colors">
                    <td className="px-4 py-3 font-mono text-sm text-slate-200">{sim.iccid}</td>
                    <td className="px-4 text-brand-400 font-medium">{sim.phoneNumber || '—'}</td>
                    <td className="px-4">
                      <div className="flex flex-col">
                        <span className="text-slate-200 text-xs">{sim.carrierName || 'Unknown Carrier'}</span>
                        <span className="text-slate-500 font-mono text-[10px]">{sim.imsi ? `IMSI: ${sim.imsi}` : 'No IMSI'}</span>
                        <span className="text-slate-400 font-mono text-[10px]">{sim.imei ? `IMEI: ${sim.imei}` : ''}</span>
                      </div>
                    </td>
                    <td className="px-4 text-slate-400">
                      {sim.slotIndex !== undefined && sim.slotIndex !== -1 ? `Slot ${sim.slotIndex + 1}` : '—'}
                    </td>
                    <td className="px-4">
                      {sim.device ? (
                        <span className="flex items-center gap-1.5 text-emerald-400 font-medium text-xs">
                          {sim.device.name}
                        </span>
                      ) : (
                        <span className="text-slate-500 italic text-xs">Unassigned</span>
                      )}
                    </td>
                    <td className="px-4">
                      <div className="flex flex-wrap items-center justify-end gap-2">
                        <select 
                          className="bg-[#12121f] text-xs text-slate-300 border border-slate-700 rounded px-2 py-1 max-w-[140px]"
                          value={sim.device?.id || ''}
                          onChange={(e) => handleAssign(sim.id, e.target.value)}
                        >
                          <option value="">-- Unassigned --</option>
                          {devices.map((d: Device) => (
                            <option key={d.id} value={d.id}>{d.name}</option>
                          ))}
                        </select>
                        <button className="btn-secondary p-1.5" title="Edit SIM details" onClick={() => startEdit(sim)}>
                          <Pencil size={13} />
                        </button>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
            {sims.length === 0 && (
              <tr><td colSpan={6} className="px-4 py-8 text-center text-slate-500">No SIM cards discovered yet. Please connect an agent device with a SIM.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
