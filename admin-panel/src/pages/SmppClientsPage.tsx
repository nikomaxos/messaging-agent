import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSmppClients, createSmppClient, updateSmppClient, deleteSmppClient, disconnectSmppClient } from '../api/client'
import { SmppClient } from '../types'
import { Plus, Pencil, Trash2, X, Check, Unplug } from 'lucide-react'
import { format } from 'date-fns'
import { ConfirmModal } from '../components/ConfirmModal'

export default function SmppClientsPage() {
  const qc = useQueryClient()
  const { data: clients = [], isFetching } = useQuery({ queryKey: ['smppClients'], queryFn: getSmppClients })

  const [editingId, setEditingId] = useState<number | null>(null)
  const [formData, setFormData] = useState<Partial<SmppClient>>({})
  const [isCreating, setIsCreating] = useState(false)
  const [confirmAction, setConfirmAction] = useState<{ title: string, message: string, onConfirm: () => void } | null>(null)

  const createMut = useMutation({
    mutationFn: createSmppClient,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppClients'] }); setIsCreating(false); },
    onError: (err: any) => alert('Failed to create client: ' + (err.response?.data?.message || err.message))
  })
  const updateMut = useMutation({
    mutationFn: (d: SmppClient) => updateSmppClient(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppClients'] }); setEditingId(null); },
    onError: (err: any) => alert('Failed to update client: ' + (err.response?.data?.message || err.message))
  })
  const deleteMut = useMutation({
    mutationFn: deleteSmppClient,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppClients'] }); },
    onError: (err: any) => alert('Failed to delete client: ' + (err.response?.data?.message || err.message))
  })
  const disconnectMut = useMutation({
    mutationFn: disconnectSmppClient,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppClients'] }); },
    onError: (err: any) => alert('Failed to disconnect client: ' + (err.response?.data?.message || err.message))
  })

  // Start creation
  const startCreate = () => {
    setIsCreating(true)
    setEditingId(null)
    setFormData({ name: '', systemId: '', password: '', active: true })
  }

  // Start edit
  const startEdit = (c: SmppClient) => {
    setIsCreating(false)
    setEditingId(c.id)
    setFormData({ ...c, password: '' }) // Blank password field initially
  }

  // Save changes
  const handleSave = () => {
    if (isCreating) {
      if (!formData.name || !formData.systemId || !formData.password) {
        alert('Name, System ID, and Password are required')
        return
      }
      createMut.mutate(formData as any)
    } else if (editingId) {
      const payload = { ...formData }
      if (!payload.password) delete payload.password // Don't send empty password if unchanged
      updateMut.mutate({ id: editingId, ...payload } as SmppClient)
    }
  }

  // Cancel edits
  const handleCancel = () => {
    setIsCreating(false)
    setEditingId(null)
  }

  return (
    <div className="p-8">
      <ConfirmModal
        isOpen={confirmAction !== null}
        title={confirmAction?.title || ''}
        message={confirmAction?.message || ''}
        onConfirm={() => confirmAction?.onConfirm()}
        onCancel={() => setConfirmAction(null)}
      />
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">SMPP Clients</h1>
          <p className="text-slate-400 text-sm">Manage customers connecting via SMPP</p>
        </div>
        <button
          onClick={startCreate}
          disabled={isCreating}
          className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
        >
          <Plus size={16} /> New Client
        </button>
      </div>

      <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-[#12121f] text-slate-400 border-b border-white/[0.05]">
            <tr>
              <th className="px-5 py-4 font-medium">Name</th>
              <th className="px-5 py-4 font-medium">System ID</th>
              <th className="px-5 py-4 font-medium">Password</th>
              <th className="px-5 py-4 font-medium">Status</th>
              <th className="px-5 py-4 font-medium">State</th>
              <th className="px-5 py-4 font-medium">Active Binds</th>
              <th className="px-5 py-4 font-medium">Uptime</th>
              <th className="px-5 py-4 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.05]">
            {isCreating && (
              <tr className="bg-brand-900/10">
                <td className="px-5 py-3">
                  <input autoFocus className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.name || ''} onChange={e => setFormData({ ...formData, name: e.target.value })} placeholder="Client Name" />
                </td>
                <td className="px-5 py-3">
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.systemId || ''} onChange={e => setFormData({ ...formData, systemId: e.target.value })} placeholder="username" />
                </td>
                <td className="px-5 py-3">
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.password || ''} onChange={e => setFormData({ ...formData, password: e.target.value })} placeholder="secret" />
                </td>
                <td className="px-5 py-3">
                  <input type="checkbox" checked={formData.active !== false} onChange={e => setFormData({ ...formData, active: e.target.checked })} /> Active
                </td>
                <td className="px-5 py-3 text-slate-500">—</td>
                <td className="px-5 py-3 text-slate-500">—</td>
                <td className="px-5 py-3 text-slate-500">—</td>
                <td className="px-5 py-3 flex items-center justify-end gap-2">
                  <button onClick={handleSave} className="p-1.5 text-green-400 hover:bg-green-400/10 rounded transition" title="Save"><Check size={16} /></button>
                  <button onClick={handleCancel} className="p-1.5 text-slate-500 hover:bg-white/5 rounded transition" title="Cancel"><X size={16} /></button>
                </td>
              </tr>
            )}

            {clients.map((c: SmppClient) => {
              const isEd = editingId === c.id
              return (
                <tr key={c.id} className="hover:bg-white/[0.02] transition">
                  <td className="px-5 py-3">
                    {isEd ? <input className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm" autoFocus value={formData.name || ''} onChange={(e: any) => setFormData({ ...formData, name: e.target.value })} />
                          : <span className="font-medium text-white">{c.name}</span>}
                  </td>
                  <td className="px-5 py-3 font-mono text-xs">
                    {isEd ? <input className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm" value={formData.systemId || ''} onChange={(e: any) => setFormData({ ...formData, systemId: e.target.value })} />
                          : c.systemId}
                  </td>
                  <td className="px-5 py-3 font-mono text-xs text-slate-500">
                    {isEd ? <input className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm" value={formData.password || ''} onChange={(e: any) => setFormData({ ...formData, password: e.target.value })} placeholder="(unchanged)" />
                          : '••••••••'}
                  </td>
                  <td className="px-5 py-3">
                    {isEd ? (
                      <input type="checkbox" checked={formData.active} onChange={(e: any) => setFormData({ ...formData, active: e.target.checked })} />
                    ) : (
                      c.active 
                        ? <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-green-500/10 text-green-400 border border-green-500/20">Active</span>
                        : <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-red-500/10 text-red-400 border border-red-500/20">Inactive</span>
                    )}
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-400">
                    {c.activeSessions && c.activeSessions.length > 0 ? (
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                        Online
                      </span>
                    ) : (
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-slate-500/10 text-slate-400 border border-slate-500/20">Offline</span>
                    )}
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-400 font-mono">
                    {c.activeSessions && c.activeSessions.length > 0 ? (
                      (() => {
                        const tx = c.activeSessions.filter(s => s.bindType.includes('TRANSMITTER')).length
                        const rx = c.activeSessions.filter(s => s.bindType.includes('RECEIVER')).length
                        const trx = c.activeSessions.filter(s => s.bindType.includes('TRANSCEIVER')).length
                        return `TX:${tx} RX:${rx} TRX:${trx}`
                      })()
                    ) : (
                      <span className="text-slate-500">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3 text-xs font-medium text-slate-300">
                    {c.activeSessions && c.activeSessions.length > 0 ? (
                      (() => {
                        const uptimeSec = Math.max(...c.activeSessions.map(s => s.uptimeSeconds));
                        const m = Math.floor(uptimeSec / 60);
                        const h = Math.floor(m / 60);
                        const displayUptime = h > 0 ? `${h}h ${m % 60}m` : (m > 0 ? `${m}m` : `${uptimeSec}s`);
                        return displayUptime;
                      })()
                    ) : (
                      <span className="text-slate-500">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex justify-end items-center gap-2 block">
                      {isEd ? (
                        <>
                          <button onClick={handleSave} className="p-1.5 text-green-400 hover:bg-green-400/10 rounded transition" title="Save"><Check size={16} /></button>
                          <button onClick={handleCancel} className="p-1.5 text-slate-400 hover:bg-white/5 rounded transition" title="Cancel"><X size={16} /></button>
                        </>
                      ) : (
                        <>
                          {(c.activeSessions && c.activeSessions.length > 0) ? (
                            <button onClick={() => setConfirmAction({
                              title: 'Disconnect Client',
                              message: `Kick out all active connections for ${c.systemId}?`,
                              onConfirm: () => disconnectMut.mutate(c.systemId)
                            })} className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-medium text-orange-400 hover:text-white hover:bg-orange-500 focus:ring-2 focus:outline-none focus:ring-orange-300 rounded transition" title="Disconnect All Active Binds">
                              <Unplug size={14} /> Kick
                            </button>
                          ) : (
                            <button disabled className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-medium text-slate-500 bg-slate-500/10 rounded cursor-not-allowed" title="No active connections">
                              <Unplug size={14} /> Kick
                            </button>
                          )}
                          <button onClick={() => startEdit(c)} className="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded transition" title="Edit"><Pencil size={15} /></button>
                          <button onClick={() => setConfirmAction({
                            title: 'Delete Client',
                            message: `Are you sure you want to delete ${c.systemId}?`,
                            onConfirm: () => deleteMut.mutate(c.id)
                          })} className="p-1.5 text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete"><Trash2 size={15} /></button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
            
            {!isFetching && clients.length === 0 && !isCreating && (
              <tr><td colSpan={6} className="px-5 py-12 text-center text-slate-500">No smpp clients defined yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
