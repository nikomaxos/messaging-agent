import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSmscSuppliers, createSmscSupplier, updateSmscSupplier, deleteSmscSupplier, bindSmscSupplier, unbindSmscSupplier } from '../api/client'
import { SmscSupplier, SmscSupplierConfig } from '../types'
import { Plus, Pencil, Trash2, X, Check, Server, RefreshCw, Play, Square } from 'lucide-react'
import { format } from 'date-fns'
import { ConfirmModal } from '../components/ConfirmModal'

export default function SmscsPage() {
  const qc = useQueryClient()
  const [autoRefresh, setAutoRefresh] = useState(true)
  const { data: suppliers = [], isFetching, refetch } = useQuery({ 
    queryKey: ['smscSuppliers'], 
    queryFn: getSmscSuppliers,
    refetchInterval: autoRefresh ? 3000 : false
  })

  const [modalOpen, setModalOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [confirmAction, setConfirmAction] = useState<{ title: string, message: string, onConfirm: () => void } | null>(null)
  
  // Form State
  const [formData, setFormData] = useState<Partial<SmscSupplierConfig>>({})

  const createMut = useMutation({
    mutationFn: createSmscSupplier,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smscSuppliers'] }); setModalOpen(false); },
    onError: (err: any) => alert('Failed to create supplier: ' + (err.response?.data?.message || err.message))
  })
  const updateMut = useMutation({
    mutationFn: (d: SmscSupplierConfig) => updateSmscSupplier(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smscSuppliers'] }); setModalOpen(false); },
    onError: (err: any) => alert('Failed to update supplier: ' + (err.response?.data?.message || err.message))
  })
  const deleteMut = useMutation({
    mutationFn: deleteSmscSupplier,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smscSuppliers'] }); },
    onError: (err: any) => alert('Failed to delete supplier: ' + (err.response?.data?.message || err.message))
  })
  const bindMut = useMutation({
    mutationFn: bindSmscSupplier,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['smscSuppliers'] }),
    onError: (err: any) => alert('Failed to bind supplier: ' + (err.response?.data?.message || err.message))
  })
  const unbindMut = useMutation({
    mutationFn: unbindSmscSupplier,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['smscSuppliers'] }),
    onError: (err: any) => alert('Failed to unbind supplier: ' + (err.response?.data?.message || err.message))
  })

  const openCreateModal = () => {
    setEditingId(null)
    setFormData({
      name: '', host: '', port: 2775, systemId: '', password: '',
      systemType: '', bindType: 'TRANSCEIVER', addressRange: '',
      sourceTon: 0, sourceNpi: 0, destTon: 0, destNpi: 0,
      throughput: 0, enquireLinkInterval: 30000, active: true
    })
    setModalOpen(true)
  }

  const openEditModal = (s: SmscSupplierConfig) => {
    setEditingId(s.id)
    setFormData({ ...s, password: '' }) // blank out password for editing (only update if changed)
    setModalOpen(true)
  }

  const handleSave = () => {
    if (!formData.name || !formData.host || !formData.port || !formData.systemId) {
      alert('Name, Host, Port and System ID are required')
      return
    }
    if (editingId) {
      const payload = { ...formData }
      if (!payload.password) delete payload.password
      updateMut.mutate({ id: editingId, ...payload } as SmscSupplierConfig)
    } else {
      if (!formData.password) {
        alert('Password is required for new suppliers')
        return
      }
      createMut.mutate(formData as any)
    }
  }

  return (
    <div className="p-8 relative">
      <ConfirmModal
        isOpen={confirmAction !== null}
        title={confirmAction?.title || ''}
        message={confirmAction?.message || ''}
        onConfirm={() => confirmAction?.onConfirm()}
        onCancel={() => setConfirmAction(null)}
      />
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">SMSc Suppliers</h1>
          <p className="text-slate-400 text-sm">Manage upstream SMPP connections to telecom providers</p>
        </div>
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-2 cursor-pointer bg-[#1a1a2e] px-3 py-1.5 rounded-lg border border-white/10">
            <span className="text-xs text-slate-300 font-medium">Auto Refresh</span>
            <div className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${autoRefresh ? 'bg-brand-500' : 'bg-slate-600'}`}>
              <input type="checkbox" className="sr-only" checked={autoRefresh} onChange={e => setAutoRefresh(e.target.checked)} />
              <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${autoRefresh ? 'translate-x-4' : 'translate-x-1'}`} />
            </div>
          </label>

          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-2 bg-[#1a1a2e] hover:bg-white/5 border border-white/10 text-white px-3 py-1.5 rounded-lg text-sm transition disabled:opacity-50"
          >
            <RefreshCw size={14} className={isFetching && autoRefresh ? 'animate-spin' : ''} />
            Refresh
          </button>

          <button
            onClick={openCreateModal}
            className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition"
          >
            <Plus size={16} /> Add SMSc
          </button>
        </div>
      </div>

      <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-[#12121f] text-slate-400 border-b border-white/[0.05]">
            <tr>
              <th className="px-5 py-4 font-medium">Name</th>
              <th className="px-5 py-4 font-medium">Endpoint</th>
              <th className="px-5 py-4 font-medium">Status / Uptime</th>
              <th className="px-5 py-4 font-medium text-right">Total SMS</th>
              <th className="px-5 py-4 font-medium text-right text-green-400">DLRs</th>
              <th className="px-5 py-4 font-medium text-right text-orange-400">Queue</th>
              <th className="px-5 py-4 font-medium text-right text-red-400">Failed</th>
              <th className="px-5 py-4 font-medium text-center">Controls</th>
              <th className="px-5 py-4 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.05]">
            {suppliers.map((wrapper: SmscSupplier) => {
              const s = wrapper.supplier;
              return (
              <tr key={s.id} className="hover:bg-white/[0.02] transition">
                <td className="px-5 py-3 text-white">
                  <div className="font-medium">{s.name}</div>
                  <div className="text-[10px] text-slate-500 mt-1">{s.systemId} ({s.bindType})</div>
                </td>
                <td className="px-5 py-3 font-mono text-xs text-slate-400">{s.host}:{s.port}</td>
                <td className="px-5 py-3">
                  <div className="flex flex-col items-start gap-1">
                    {wrapper.connected 
                      ? <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-green-500/10 text-green-400 border border-green-500/20">Bound</span>
                      : s.active 
                        ? <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-yellow-500/10 text-yellow-400 border border-yellow-500/20">Attempting...</span>
                        : <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-red-500/10 text-red-400 border border-red-500/20">Unbound</span>}
                    {wrapper.uptimeSeconds != null && (
                      <span className="text-[10px] text-slate-500">
                        Up: {Math.floor(wrapper.uptimeSeconds / 3600)}h {Math.floor((wrapper.uptimeSeconds % 3600) / 60)}m
                      </span>
                    )}
                  </div>
                </td>
                <td className="px-5 py-3 text-right font-mono text-xs">{wrapper.totalMessages.toLocaleString()}</td>
                <td className="px-5 py-3 text-right font-mono text-xs text-green-400/80">{wrapper.dlrsReceived.toLocaleString()}</td>
                <td className="px-5 py-3 text-right font-mono text-xs text-orange-400/80">{wrapper.inQueue.toLocaleString()}</td>
                <td className="px-5 py-3 text-right font-mono text-xs text-red-400/80">{wrapper.failed.toLocaleString()}</td>
                <td className="px-5 py-3 text-center">
                  {s.active ? (
                    <button onClick={() => unbindMut.mutate(s.id)} disabled={unbindMut.isPending}
                      className="p-1.5 text-slate-400 hover:text-orange-400 hover:bg-orange-400/10 rounded transition" title="Unbind">
                      <Square size={14} className="fill-current" />
                    </button>
                  ) : (
                    <button onClick={() => bindMut.mutate(s.id)} disabled={bindMut.isPending}
                      className="p-1.5 text-slate-400 hover:text-green-400 hover:bg-green-400/10 rounded transition" title="Bind">
                      <Play size={14} className="fill-current" />
                    </button>
                  )}
                </td>
                <td className="px-5 py-3">
                  <div className="flex justify-end items-center gap-2">
                    <button onClick={() => openEditModal(s)} className="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded transition" title="Edit"><Pencil size={15} /></button>
                    <button onClick={() => setConfirmAction({
                      title: 'Delete Supplier',
                      message: `Are you sure you want to delete ${s.name}?`,
                      onConfirm: () => deleteMut.mutate(s.id)
                    })} className="p-1.5 text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete"><Trash2 size={15} /></button>
                  </div>
                </td>
              </tr>
            )})}
            
            {!isFetching && suppliers.length === 0 && (
              <tr><td colSpan={7} className="px-5 py-12 text-center text-slate-500">No upstream suppliers configured.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-[#1a1a2e] border border-white/10 rounded-xl w-full max-w-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
            <div className="px-6 py-4 border-b border-white/5 flex justify-between items-center bg-[#12121f]">
              <h2 className="text-lg font-semibold text-white flex items-center gap-2">
                <Server size={18} className="text-blue-400" />
                {editingId ? 'Edit SMSc Supplier' : 'Add SMSc Supplier'}
              </h2>
              <button onClick={() => setModalOpen(false)} className="text-slate-400 hover:text-white transition"><X size={20} /></button>
            </div>
            
            <div className="p-6 overflow-y-auto space-y-6">
              {/* Basic Details */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Friendly Name</label>
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                    value={formData.name || ''} onChange={e => setFormData({ ...formData, name: e.target.value })} placeholder="Provider A" />
                </div>
                <div className="flex items-center pt-6">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" className="form-checkbox text-brand-500 rounded bg-[#12121f] border-white/20"
                      checked={formData.active} onChange={e => setFormData({ ...formData, active: e.target.checked })} />
                    <span className="text-sm text-slate-300">Active Connection</span>
                  </label>
                </div>
              </div>

              {/* Endpoint Details */}
              <div className="grid grid-cols-3 gap-4">
                <div className="col-span-2">
                  <label className="block text-xs font-medium text-slate-400 mb-1">Host (IP or FQDN)</label>
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.host || ''} onChange={e => setFormData({ ...formData, host: e.target.value })} placeholder="smsc.example.com" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Port</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.port || ''} onChange={e => setFormData({ ...formData, port: parseInt(e.target.value) || 2775 })} />
                </div>
              </div>

              {/* Credentials */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">System ID</label>
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.systemId || ''} onChange={e => setFormData({ ...formData, systemId: e.target.value })} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Password</label>
                  <input type="password" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.password || ''} onChange={e => setFormData({ ...formData, password: e.target.value })} 
                    placeholder={editingId ? '(unchanged)' : 'Required'} />
                </div>
              </div>

              {/* SMPP Specifics */}
              <div className="grid grid-cols-3 gap-4 bg-[#12121f]/50 p-4 rounded-lg border border-white/[0.02]">
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Bind Type</label>
                  <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm outline-none"
                    value={formData.bindType} onChange={e => setFormData({ ...formData, bindType: e.target.value })}>
                    <option value="TRANSCEIVER">TRANSCEIVER</option>
                    <option value="TRANSMITTER">TRANSMITTER</option>
                    <option value="RECEIVER">RECEIVER</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">System Type</label>
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                    value={formData.systemType || ''} onChange={e => setFormData({ ...formData, systemType: e.target.value })} placeholder="Optional" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Throughput (msg/s)</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.throughput || 0} onChange={e => setFormData({ ...formData, throughput: parseInt(e.target.value) || 0 })} />
                  <p className="text-[10px] text-slate-500 mt-1">0 = Unlimited</p>
                </div>
              </div>

              {/* Advanced Flags */}
              <div className="grid grid-cols-4 gap-4 bg-[#12121f]/50 p-4 rounded-lg border border-white/[0.02]">
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Src TON</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.sourceTon ?? 0} onChange={e => setFormData({ ...formData, sourceTon: parseInt(e.target.value) || 0 })} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Src NPI</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.sourceNpi ?? 0} onChange={e => setFormData({ ...formData, sourceNpi: parseInt(e.target.value) || 0 })} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Dest TON</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.destTon ?? 0} onChange={e => setFormData({ ...formData, destTon: parseInt(e.target.value) || 0 })} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-400 mb-1">Dest NPI</label>
                  <input type="number" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                    value={formData.destNpi ?? 0} onChange={e => setFormData({ ...formData, destNpi: parseInt(e.target.value) || 0 })} />
                </div>
                <div className="col-span-2">
                  <label className="block text-xs font-medium text-slate-400 mb-1">Address Range</label>
                  <input className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                    value={formData.addressRange || ''} onChange={e => setFormData({ ...formData, addressRange: e.target.value })} placeholder="Optional regex" />
                </div>
                <div className="col-span-2">
                  <label className="block text-xs font-medium text-slate-400 mb-1">Enquire Link Interval (ms)</label>
                  <input type="number" step="1000" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm font-mono"
                     value={formData.enquireLinkInterval || 30000} onChange={e => setFormData({ ...formData, enquireLinkInterval: parseInt(e.target.value) || 30000 })} />
                </div>
              </div>
            </div>

            <div className="px-6 py-4 border-t border-white/5 bg-[#12121f] flex justify-end gap-3">
              <button onClick={() => setModalOpen(false)} className="px-4 py-2 rounded-lg text-sm text-slate-400 hover:text-white transition">
                Cancel
              </button>
              <button onClick={handleSave} disabled={createMut.isPending || updateMut.isPending} 
                className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50">
                <Check size={16} /> Save Supplier
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
