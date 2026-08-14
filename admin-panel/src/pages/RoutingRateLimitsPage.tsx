import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getRoutingRateLimits, createRoutingRateLimit, updateRoutingRateLimit, deleteRoutingRateLimit, getSmppClients, getSmscSuppliers } from '../api/client'
import { RoutingRateLimit, SmppClient, SmscSupplier } from '../types'
import { Plus, Pencil, Trash2, X, Check, Gauge } from 'lucide-react'
import { ConfirmModal } from '../components/ConfirmModal'
import mccList from 'mcc-mnc-list'

export default function RoutingRateLimitsPage() {
  const qc = useQueryClient()
  const { data: limits = [], isFetching } = useQuery({ queryKey: ['rateLimits'], queryFn: getRoutingRateLimits })
  const { data: clients = [] } = useQuery({ queryKey: ['smppClients'], queryFn: getSmppClients })
  const { data: suppliers = [] } = useQuery({ queryKey: ['smscSuppliers'], queryFn: getSmscSuppliers })


  const [editingId, setEditingId] = useState<number | null>(null)
  const [formData, setFormData] = useState<Partial<RoutingRateLimit>>({})
  const [isCreating, setIsCreating] = useState(false)
  const [confirmAction, setConfirmAction] = useState<{ title: string, message: string, onConfirm: () => void } | null>(null)

  const allRecords = mccList.all()
  const uniqueCountries = [...new Set(allRecords.map(r => r.countryName).filter(Boolean))].sort()
  const networksForCountry = formData.countryCode && formData.countryCode !== 'ALL' 
    ? [...new Set(allRecords.filter(r => r.countryCode === formData.countryCode).map(r => r.brand || r.operator || 'Unknown'))].sort()
    : [...new Set(allRecords.map(r => r.brand || r.operator || 'Unknown'))].sort()
  
  const getSupplierName = (sysId: string) => {
    if (sysId === 'ALL') return 'ALL'
    if (sysId === 'WEBSOCKET') return 'WEBSOCKET (Devices)'
    const supp = suppliers.find((s: any) => s.supplier.systemId === sysId)
    return supp ? supp.supplier.name : sysId
  }

  const createMut = useMutation({
    mutationFn: createRoutingRateLimit,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['rateLimits'] }); setIsCreating(false); },
    onError: (err: any) => alert('Failed to create rate limit: ' + (err.response?.data?.message || err.message))
  })
  
  const updateMut = useMutation({
    mutationFn: (d: RoutingRateLimit) => updateRoutingRateLimit(d.id!, d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['rateLimits'] }); setEditingId(null); },
    onError: (err: any) => alert('Failed to update rate limit: ' + (err.response?.data?.message || err.message))
  })
  
  const deleteMut = useMutation({
    mutationFn: deleteRoutingRateLimit,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['rateLimits'] }); },
    onError: (err: any) => alert('Failed to delete rate limit: ' + (err.response?.data?.message || err.message))
  })

  const startCreate = () => {
    setIsCreating(true)
    setEditingId(null)
    setFormData({ customerProfileId: 'ALL', countryCode: 'ALL', networkId: 'ALL', supplierId: 'ALL', speedTps: 10.0 })
  }

  const startEdit = (limit: RoutingRateLimit) => {
    setIsCreating(false)
    setEditingId(limit.id!)
    setFormData({ ...limit })
  }

  const handleSave = () => {
    if (isCreating) {
      if (!formData.customerProfileId || !formData.speedTps) {
        alert('Customer and Speed are required')
        return
      }
      createMut.mutate(formData as RoutingRateLimit)
    } else if (editingId) {
      updateMut.mutate({ id: editingId, ...formData } as RoutingRateLimit)
    }
  }

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
          <h1 className="text-2xl font-bold text-white mb-1 flex items-center gap-2">
            <Gauge size={24} className="text-brand-400" />
            Rate Limits & Speeds
          </h1>
          <p className="text-slate-400 text-sm">Configure fractional TPS limits (Minimum Delay Algorithm) per connection</p>
        </div>
        <button
          onClick={startCreate}
          disabled={isCreating}
          className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
        >
          <Plus size={16} /> Add Limit
        </button>
      </div>

      <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-[#12121f] text-slate-400 border-b border-white/[0.05]">
            <tr>
              <th className="px-5 py-4 font-medium">Customer (System ID)</th>
              <th className="px-5 py-4 font-medium">Country</th>
              <th className="px-5 py-4 font-medium">Network</th>
              <th className="px-5 py-4 font-medium">Supplier Connection</th>
              <th className="px-5 py-4 font-medium">Speed (TPS)</th>
              <th className="px-5 py-4 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.05]">
            {isCreating && (
              <tr className="bg-brand-900/10">
                <td className="px-5 py-3">
                  <select className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.customerProfileId} onChange={e => setFormData({ ...formData, customerProfileId: e.target.value })}>
                    <option value="ALL">ALL (Default)</option>
                    {clients.map((c: SmppClient) => <option key={c.systemId} value={c.systemId}>{c.systemId}</option>)}
                  </select>
                </td>
                <td className="px-5 py-3">
                  <select className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.countryCode} onChange={e => setFormData({ ...formData, countryCode: e.target.value, networkId: 'ALL' })}>
                    <option value="ALL">ALL Countries</option>
                    {uniqueCountries.map((cName: any) => {
                      const rec = allRecords.find((r: any) => r.countryName === cName);
                      return <option key={rec?.countryCode || cName} value={rec?.countryCode || cName}>{cName} ({rec?.countryCode})</option>
                    })}
                  </select>
                </td>
                <td className="px-5 py-3">
                  <select className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.networkId} onChange={e => setFormData({ ...formData, networkId: e.target.value })}>
                    <option value="ALL">ALL Networks</option>
                    {networksForCountry.map((net: any) => <option key={net} value={net}>{net}</option>)}
                  </select>
                </td>
                <td className="px-5 py-3">
                  <select className="w-full bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.supplierId} onChange={e => setFormData({ ...formData, supplierId: e.target.value })}>
                    <option value="ALL">ALL</option>
                    <option value="WEBSOCKET">WEBSOCKET (Devices)</option>
                    {suppliers.map((s: SmscSupplier) => <option key={s.supplier.systemId} value={s.supplier.systemId}>{s.supplier.name}</option>)}
                  </select>
                </td>
                <td className="px-5 py-3">
                  <input type="number" step="0.1" min="0.1" className="w-24 bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm"
                    value={formData.speedTps} onChange={e => setFormData({ ...formData, speedTps: parseFloat(e.target.value) })} />
                </td>
                <td className="px-5 py-3 flex items-center justify-end gap-2">
                  <button onClick={handleSave} className="p-1.5 text-green-400 hover:bg-green-400/10 rounded transition" title="Save"><Check size={16} /></button>
                  <button onClick={handleCancel} className="p-1.5 text-slate-500 hover:bg-white/5 rounded transition" title="Cancel"><X size={16} /></button>
                </td>
              </tr>
            )}

            {limits.map((l: RoutingRateLimit) => {
              const isEd = editingId === l.id
              return (
                <tr key={l.id} className="hover:bg-white/[0.02] transition">
                  <td className="px-5 py-3 font-mono text-xs">
                    {isEd ? (
                      <select className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm"
                        value={formData.customerProfileId} onChange={(e: any) => setFormData({ ...formData, customerProfileId: e.target.value })}>
                        <option value="ALL">ALL (Default)</option>
                        {clients.map((c: SmppClient) => <option key={c.systemId} value={c.systemId}>{c.systemId}</option>)}
                      </select>
                    ) : (
                      l.customerProfileId === 'ALL' ? <span className="text-slate-500">ALL</span> : <span className="text-white">{l.customerProfileId}</span>
                    )}
                  </td>
                  <td className="px-5 py-3 font-mono text-xs">
                    {isEd ? (
                      <select className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm"
                        value={formData.countryCode} onChange={(e: any) => setFormData({ ...formData, countryCode: e.target.value, networkId: 'ALL' })}>
                        <option value="ALL">ALL Countries</option>
                        {uniqueCountries.map((cName: any) => {
                          const rec = allRecords.find((r: any) => r.countryName === cName);
                          return <option key={rec?.countryCode || cName} value={rec?.countryCode || cName}>{cName} ({rec?.countryCode})</option>
                        })}
                      </select>
                    ) : (
                      l.countryCode === 'ALL' ? <span className="text-slate-500">ALL</span> : l.countryCode
                    )}
                  </td>
                  <td className="px-5 py-3 font-mono text-xs">
                    {isEd ? (
                      <select className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm"
                        value={formData.networkId} onChange={(e: any) => setFormData({ ...formData, networkId: e.target.value })}>
                        <option value="ALL">ALL Networks</option>
                        {networksForCountry.map((net: any) => <option key={net} value={net}>{net}</option>)}
                      </select>
                    ) : (
                      l.networkId === 'ALL' ? <span className="text-slate-500">ALL</span> : l.networkId
                    )}
                  </td>
                  <td className="px-5 py-3 font-mono text-xs">
                    {isEd ? (
                      <select className="w-full bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm"
                        value={formData.supplierId} onChange={(e: any) => setFormData({ ...formData, supplierId: e.target.value })}>
                        <option value="ALL">ALL</option>
                        <option value="WEBSOCKET">WEBSOCKET (Devices)</option>
                        {suppliers.map((s: SmscSupplier) => <option key={s.supplier.systemId} value={s.supplier.systemId}>{s.supplier.name}</option>)}
                      </select>
                    ) : (
                      <span className={l.supplierId === 'ALL' ? "text-slate-500" : "text-white"}>{getSupplierName(l.supplierId)}</span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    {isEd ? (
                      <input type="number" step="0.1" min="0.1" className="w-24 bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-white text-sm"
                        value={formData.speedTps} onChange={(e: any) => setFormData({ ...formData, speedTps: parseFloat(e.target.value) })} />
                    ) : (
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-[12px] font-bold bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                        {l.speedTps} TPS
                      </span>
                    )}
                  </td>
                  <td className="px-5 py-3 flex justify-end items-center gap-2">
                    {isEd ? (
                      <>
                        <button onClick={handleSave} className="p-1.5 text-green-400 hover:bg-green-400/10 rounded transition" title="Save"><Check size={16} /></button>
                        <button onClick={handleCancel} className="p-1.5 text-slate-400 hover:bg-white/5 rounded transition" title="Cancel"><X size={16} /></button>
                      </>
                    ) : (
                      <>
                        <button onClick={() => startEdit(l)} className="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded transition" title="Edit"><Pencil size={15} /></button>
                        <button onClick={() => setConfirmAction({
                          title: 'Delete Rate Limit',
                          message: `Are you sure you want to delete this limit?`,
                          onConfirm: () => deleteMut.mutate(l.id!)
                        })} className="p-1.5 text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete"><Trash2 size={15} /></button>
                      </>
                    )}
                  </td>
                </tr>
              )
            })}
            
            {!isFetching && limits.length === 0 && !isCreating && (
              <tr><td colSpan={6} className="px-5 py-12 text-center text-slate-500">No custom rate limits configured. System defaults to 10 TPS.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
