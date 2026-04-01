import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSmppRoutings, createSmppRouting, updateSmppRouting, deleteSmppRouting, getGroups, getSmppClients, getSmscSuppliers } from '../api/client'
import { SmppClient, DeviceGroup, SmppRouting } from '../types'
import { Plus, Trash2, Route, Edit2, X } from 'lucide-react'
import { ConfirmModal } from '../components/ConfirmModal'

// Modal Component for Create/Edit
function SmppRoutingModal({ isOpen, onClose, route, clients, groups, smscs }: any) {
  const qc = useQueryClient()
  const [formData, setFormData] = useState<any>({
    smppClientId: '',
    isDefault: false,
    routingMode: 'WEBSOCKET',
    autoFailEnabled: false,
    autoFailTimeoutMinutes: 15,
    loadBalancerEnabled: false,
    resendEnabled: false,
    fallbackSmscId: '',
    resendTrigger: 'ALL_FAILURES',
    rcsExpirationSeconds: 30,
    destinations: [{ deviceGroupId: '', weightPercent: 100, fallbackSmscId: '' }]
  })

  useEffect(() => {
    if (route) {
      setFormData({
        smppClientId: route.smppClientId || '',
        isDefault: route.isDefault || false,
        routingMode: route.routingMode || 'WEBSOCKET',
        autoFailEnabled: route.autoFailEnabled || false,
        autoFailTimeoutMinutes: route.autoFailTimeoutMinutes || 15,
        loadBalancerEnabled: route.loadBalancerEnabled || false,
        resendEnabled: route.resendEnabled || false,
        fallbackSmscId: route.fallbackSmscId || '',
        resendTrigger: route.resendTrigger || 'ALL_FAILURES',
        rcsExpirationSeconds: route.rcsExpirationSeconds || 30,
        destinations: route.destinations?.length > 0 
          ? route.destinations.map((d: any) => ({
              deviceGroupId: d.deviceGroupId || '',
              weightPercent: d.weightPercent || 100,
              fallbackSmscId: d.fallbackSmscId || ''
            }))
          : [{ deviceGroupId: '', weightPercent: 100, fallbackSmscId: '' }]
      })
    } else {
      setFormData({
        smppClientId: '',
        isDefault: false,
        routingMode: 'WEBSOCKET',
        autoFailEnabled: false,
        autoFailTimeoutMinutes: 15,
        loadBalancerEnabled: false,
        resendEnabled: false,
        fallbackSmscId: '',
        resendTrigger: 'ALL_FAILURES',
        rcsExpirationSeconds: 30,
        destinations: [{ deviceGroupId: '', weightPercent: 100, fallbackSmscId: '' }]
      })
    }
  }, [route, isOpen])

  const createMut = useMutation({
    mutationFn: createSmppRouting,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppRoutings'] }); onClose() }
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }: any) => updateSmppRouting(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppRoutings'] }); onClose() }
  })

  const handleSave = () => {
    if (!formData.smppClientId) return alert("Please select a Source Client")
    for (const d of formData.destinations) {
      if (!d.deviceGroupId) return alert("Please select a Target Device Group for all destinations")
    }

    const payload = {
      ...formData,
      smppClientId: Number(formData.smppClientId),
      autoFailTimeoutMinutes: formData.autoFailTimeoutMinutes ? Number(formData.autoFailTimeoutMinutes) : 15,
      fallbackSmscId: formData.fallbackSmscId ? Number(formData.fallbackSmscId) : null,
      rcsExpirationSeconds: formData.rcsExpirationSeconds ? Number(formData.rcsExpirationSeconds) : null,
      destinations: formData.destinations.map((d: any) => ({
        ...d,
        deviceGroupId: Number(d.deviceGroupId),
        weightPercent: Number(d.weightPercent),
        fallbackSmscId: d.fallbackSmscId ? Number(d.fallbackSmscId) : null
      }))
    }
    
    if (route) {
      updateMut.mutate({ id: route.id, data: payload })
    } else {
      createMut.mutate(payload)
    }
  }

  const addDestination = () => {
    setFormData({ ...formData, destinations: [...formData.destinations, { deviceGroupId: '', weightPercent: 0, fallbackSmscId: '' }] })
  }
  
  const updateDestination = (index: number, field: string, value: any) => {
    const newDestinations = [...formData.destinations]
    newDestinations[index] = { ...newDestinations[index], [field]: value }
    setFormData({ ...formData, destinations: newDestinations })
  }

  const removeDestination = (index: number) => {
    const newDestinations = formData.destinations.filter((_: any, i: number) => i !== index)
    setFormData({ ...formData, destinations: newDestinations })
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex justify-center items-center overflow-y-auto pt-10 pb-10">
      <div className="bg-[#1a1a2e] border border-white/10 rounded-xl w-[700px] max-w-full shadow-2xl flex flex-col max-h-[90vh]">
        
        {/* Header */}
        <div className="flex justify-between items-center p-5 border-b border-white/10 shrink-0">
          <h2 className="text-xl font-bold text-white">{route ? 'Edit Routing Rule' : 'Add Routing Rule'}</h2>
          <button onClick={onClose} className="text-slate-400 hover:text-white transition"><X size={20} /></button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-6 overflow-y-auto flex-1 custom-scrollbar">
          
          {/* Client Selection */}
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-300 mb-1.5">Incoming SMPP Client</label>
              <select className="w-full bg-[#12121f] border border-white/10 rounded-lg px-4 py-2.5 text-white text-sm"
                value={formData.smppClientId} onChange={(e: any) => setFormData({...formData, smppClientId: e.target.value})}>
                <option value="">-- Select Source Client --</option>
                {clients.map((c: any) => <option key={c.id} value={c.id}>{c.name} ({c.systemId})</option>)}
              </select>
            </div>
            <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer w-fit">
              <input type="checkbox" className="rounded bg-[#12121f] border-white/20 text-brand-500 focus:ring-brand-500" 
                 checked={formData.isDefault} onChange={(e: any) => setFormData({...formData, isDefault: e.target.checked})} />
              <span className="font-medium">Default Route</span>
            </label>
          </div>

          <hr className="border-white/5" />

          {/* Routing Strategy */}
          <div className="space-y-4">
            <h3 className="text-sm font-bold tracking-wide uppercase text-slate-400">Routing Strategy & Validation</h3>
            <div className="grid grid-cols-2 gap-4 bg-black/20 p-4 rounded-lg border border-white/5">
              <div>
                <label className="block text-xs text-slate-400 mb-1.5">Delivery Method</label>
                <select className="w-full bg-[#12121f] border border-white/10 rounded-lg px-3 py-2 text-white text-sm"
                  value={formData.routingMode} onChange={(e: any) => setFormData({...formData, routingMode: e.target.value})}>
                  <option value="WEBSOCKET">WebSocket (Native App)</option>
                  <option value="MATRIX">Matrix (Mautrix Bridge)</option>
                </select>
              </div>
              <div className="space-y-2">
                <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer w-fit mt-1">
                  <input type="checkbox" className="rounded bg-[#12121f] border-white/20 text-brand-500 focus:ring-brand-500" 
                    checked={formData.autoFailEnabled} onChange={(e: any) => setFormData({...formData, autoFailEnabled: e.target.checked})} />
                  <span className="font-medium">Enable Auto-Fail Timeout</span>
                </label>
                {formData.autoFailEnabled && (
                  <div className="flex items-center gap-2">
                    <input type="number" min="1" className="w-20 bg-[#12121f] border border-white/10 rounded px-2 py-1 text-white text-sm text-center"
                      value={formData.autoFailTimeoutMinutes} onChange={(e: any) => setFormData({...formData, autoFailTimeoutMinutes: parseInt(e.target.value) || 0})} />
                    <span className="text-xs text-slate-400">Time (mins) before marking dropped</span>
                  </div>
                )}
              </div>
            </div>
          </div>

          <hr className="border-white/5" />

          {/* Destinations & Load Balancing */}
          <div className="space-y-4">
            <div className="flex justify-between items-center">
              <h3 className="text-sm font-bold tracking-wide uppercase text-slate-400">Target Destinations</h3>
              <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
                <input type="checkbox" className="rounded bg-[#12121f] border-white/20 text-brand-500" 
                  checked={formData.loadBalancerEnabled} onChange={(e: any) => setFormData({...formData, loadBalancerEnabled: e.target.checked})} />
                <span>Enable Load Balancer</span>
              </label>
            </div>

            <div className="space-y-3">
              {formData.destinations.map((dest: any, idx: number) => (
                <div key={idx} className="bg-black/20 border border-white/5 rounded-lg p-4 space-y-3 relative group">
                  {formData.loadBalancerEnabled && formData.destinations.length > 1 && (
                    <button onClick={() => removeDestination(idx)} className="absolute right-3 top-3 text-slate-500 hover:text-red-400 opacity-0 group-hover:opacity-100 transition">
                      <Trash2 size={16} />
                    </button>
                  )}
                  
                  <div className="flex gap-4">
                    <div className="flex-1">
                      <label className="block text-xs text-slate-500 mb-1">Target Device Group</label>
                      <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                        value={dest.deviceGroupId} onChange={(e: any) => updateDestination(idx, 'deviceGroupId', e.target.value)}>
                        <option value="">-- Select Group --</option>
                        {groups.map((g: any) => <option key={g.id} value={g.id}>{g.name}</option>)}
                      </select>
                    </div>
                    {formData.loadBalancerEnabled && (
                      <div className="w-24">
                        <label className="block text-xs text-slate-500 mb-1">Weight %</label>
                        <input type="number" min="1" max="100" className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm text-center"
                          value={dest.weightPercent} onChange={(e: any) => updateDestination(idx, 'weightPercent', parseInt(e.target.value) || 0)} />
                      </div>
                    )}
                  </div>

                  {formData.resendEnabled && (
                    <div className="flex gap-4 items-end">
                      <div className="flex-1 text-xs text-slate-400 pt-2 border-t border-white/5">
                        <label className="block text-xs text-slate-500 mb-1">Per-Destination Fallback SMSC (Overrides Global)</label>
                        <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                          value={dest.fallbackSmscId} onChange={(e: any) => updateDestination(idx, 'fallbackSmscId', e.target.value)}>
                          <option value="">-- Use Global Fallback --</option>
                          {smscs.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name}</option>)}
                        </select>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>

            {formData.loadBalancerEnabled && (
              <button onClick={addDestination} className="text-sm text-brand-400 hover:text-brand-300 font-medium flex items-center gap-1 transition">
                <Plus size={14} /> Add Target Destination
              </button>
            )}
          </div>

          <hr className="border-white/5" />

          {/* Delivery & Timers */}
          <div className="space-y-4">
            <div className="flex justify-between items-center bg-brand-900/10 p-4 rounded-lg border border-brand-500/20">
              <div>
                <h3 className="text-sm font-bold text-brand-300">Message Resend & Fallback</h3>
                <p className="text-xs text-brand-400/60 mt-0.5">Route failed RCS messages through upstream SMSC options</p>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input type="checkbox" className="sr-only peer" checked={formData.resendEnabled} 
                  onChange={(e: any) => setFormData({...formData, resendEnabled: e.target.checked})} />
                <div className="w-11 h-6 bg-slate-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-brand-500"></div>
              </label>
            </div>

            {formData.resendEnabled && (
              <div className="bg-black/20 p-4 rounded-lg border border-white/5 space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Global Fallback SMSC</label>
                    <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                      value={formData.fallbackSmscId} onChange={(e: any) => setFormData({...formData, fallbackSmscId: e.target.value})}>
                      <option value="">-- Select Global Fallback --</option>
                      {smscs.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Trigger Condition</label>
                    <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                      value={formData.resendTrigger} onChange={(e: any) => setFormData({...formData, resendTrigger: e.target.value})}>
                      <option value="ALL_FAILURES">Message Failed (Any Reason / Timeout)</option>
                      <option value="NO_RCS">RCS Feature Not Enabled (No RCS)</option>
                    </select>
                  </div>
                </div>
                
                <div className="pt-2 border-t border-white/5">
                  <label className="block text-xs text-slate-400 mb-1">RCS Delivery Expiration (Seconds)</label>
                  <p className="text-[10px] text-slate-500 mb-2">If RCS delivery is not confirmed within this timeframe, it triggers a timeout failure and activates the fallback automatically.</p>
                  <input type="number" min="5" className="w-1/3 bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                    value={formData.rcsExpirationSeconds} onChange={(e: any) => setFormData({...formData, rcsExpirationSeconds: parseInt(e.target.value) || 0})} />
                </div>
              </div>
            )}
          </div>

        </div>

        {/* Footer */}
        <div className="p-5 border-t border-white/10 bg-black/20 flex justify-end gap-3 shrink-0 rounded-b-xl items-center">
          { (createMut.isError || updateMut.isError) && (
            <span className="text-red-400 text-sm flex-1 ml-2">Failed to save configuration. Please check the network log.</span>
          )}
          <button onClick={onClose} className="px-4 py-2 rounded text-sm text-slate-400 hover:text-white hover:bg-white/5 transition">Cancel</button>
          <button onClick={handleSave} disabled={createMut.isPending || updateMut.isPending} className="bg-brand-600 hover:bg-brand-500 disabled:opacity-50 text-white px-6 py-2 rounded text-sm font-medium transition shadow-lg shadow-brand-500/20">
            {updateMut.isPending || createMut.isPending ? 'Saving...' : 'Save Configuration'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default function SmppRoutingPage() {
  const qc = useQueryClient()
  const { data: routings = [], isFetching } = useQuery({ queryKey: ['smppRoutings'], queryFn: getSmppRoutings })
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: clients = [] } = useQuery({ queryKey: ['clients'], queryFn: getSmppClients })
  const { data: smscs = [] } = useQuery({ queryKey: ['smscs'], queryFn: getSmscSuppliers })

  const [modalOpen, setModalOpen] = useState(false)
  const [editingRoute, setEditingRoute] = useState<any>(null)
  const [confirmDelete, setConfirmDelete] = useState<{ id: number, name: string } | null>(null)

  const deleteMut = useMutation({
    mutationFn: deleteSmppRouting,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['smppRoutings'] })
  })

  const openCreateModal = () => {
    setEditingRoute(null)
    setModalOpen(true)
  }

  const openEditModal = (route: any) => {
    setEditingRoute(route)
    setModalOpen(true)
  }

  return (
    <div className="p-8">
      <ConfirmModal
        isOpen={confirmDelete !== null}
        title="Delete Route"
        message={`Are you sure you want to delete the route for ${confirmDelete?.name}?`}
        onConfirm={() => confirmDelete && deleteMut.mutate(confirmDelete.id)}
        onCancel={() => setConfirmDelete(null)}
      />
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">Routing Configuration</h1>
          <p className="text-slate-400 text-sm">Map incoming SMPP requests to Virtual SMSCs and set up Load Balancing / Failover.</p>
        </div>
        <button
          onClick={openCreateModal}
          className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition shadow-lg shadow-brand-500/20"
        >
          <Plus size={16} /> Add Route
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {routings.length === 0 && !isFetching && (
          <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-12 text-center">
            <Route className="mx-auto text-slate-600 mb-3" size={32} />
            <div className="text-slate-400">No active routes defined.</div>
            <div className="text-sm text-slate-500 mt-1">Messages without a route will drop unless a Default Route exists.</div>
          </div>
        )}

        {routings.map((r: any) => (
          <div key={r.id} className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-6 shadow-sm flex flex-col hover:border-white/10 transition group gap-4 relative">
            <div className="absolute top-4 right-4 flex gap-2">
              <button onClick={() => openEditModal(r)} className="p-2 bg-white/5 text-slate-400 hover:text-white rounded transition" title="Edit Route">
                <Edit2 size={15} />
              </button>
              <button onClick={() => setConfirmDelete({ id: r.id, name: r.smppClientName })} className="p-2 bg-white/5 text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete Route">
                <Trash2 size={15} />
              </button>
            </div>
            
            <div className="flex items-start gap-6">
               <div className="w-1/4">
                 <div className="text-xs text-slate-500 mb-1 uppercase font-semibold tracking-wider">Source Client</div>
                 <div className="font-medium text-white text-lg">{r.smppClientName}</div>
                 <div className="text-xs text-slate-400 font-mono mt-0.5">{r.smppClientSystemId}</div>
                 <div className="flex items-center gap-2 mt-2">
                   {r.isDefault && <span className="inline-flex px-2 py-0.5 rounded-md text-[10px] font-bold bg-purple-500/10 text-purple-400 border border-purple-500/20 uppercase tracking-widest leading-none">Default</span>}
                   <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-bold uppercase tracking-widest leading-none border ${r.routingMode === 'MATRIX' ? 'bg-cyan-500/10 text-cyan-400 border-cyan-500/20' : 'bg-green-500/10 text-green-400 border-green-500/20'}`}>
                     {r.routingMode || 'WEBSOCKET'}
                   </span>
                 </div>
                 {r.autoFailEnabled && (
                   <div className="text-[10px] text-amber-500/70 mt-1 uppercase tracking-wide">
                     Auto-fail: {r.autoFailTimeoutMinutes}m
                   </div>
                 )}
               </div>

               <div className="w-px h-16 bg-white/[0.05] mt-1 shrink-0 px-0"></div>
               
               <div className="flex-1">
                 <div className="text-xs text-slate-500 mb-2 uppercase font-semibold tracking-wider flex items-center gap-2">
                   Target Destinations
                   {r.loadBalancerEnabled && <span className="px-1.5 py-0.5 rounded text-[9px] bg-blue-500/20 text-blue-400 border border-blue-500/20 leading-none">Load Balanced</span>}
                 </div>
                 
                 <div className="space-y-2">
                   {r.destinations?.map((dest: any) => (
                     <div key={dest.id} className="flex items-center gap-3 bg-black/20 px-3 py-2 rounded-lg border border-white/5 text-sm">
                       <span className="font-medium text-brand-400 w-1/3 truncate">{dest.deviceGroupName}</span>
                       {r.loadBalancerEnabled && (
                         <span className="text-xs text-slate-400 bg-white/5 px-2 py-0.5 rounded w-16 text-center">{dest.weightPercent}%</span>
                       )}
                       {dest.fallbackSmscId && dest.fallbackSmscName && (
                         <span className="text-xs text-amber-500 bg-amber-500/10 px-2 py-0.5 rounded border border-amber-500/20 truncate">
                           Fallback: {dest.fallbackSmscName}
                         </span>
                       )}
                     </div>
                   ))}
                 </div>
               </div>

               <div className="w-1/4">
                 <div className="text-xs text-slate-500 mb-1 uppercase font-semibold tracking-wider">Failover State</div>
                 {r.resendEnabled ? (
                   <div className="space-y-1">
                     <div className="text-sm text-amber-400 font-medium">{r.resendTrigger === 'ALL_FAILURES' ? 'On Any Error' : 'On Non-RCS Client'}</div>
                     <div className="text-xs text-slate-400">Timer: {r.rcsExpirationSeconds}s</div>
                     {r.fallbackSmscId && r.fallbackSmscName && (
                        <div className="text-xs mt-2 px-2 py-1 bg-white/5 rounded line-clamp-2" title={r.fallbackSmscName}>
                          Global: {r.fallbackSmscName}
                        </div>
                     )}
                   </div>
                 ) : (
                   <div className="text-sm text-slate-500 italic">No Fallback</div>
                 )}
               </div>
            </div>
          </div>
        ))}
      </div>

      <SmppRoutingModal 
        isOpen={modalOpen} 
        onClose={() => setModalOpen(false)} 
        route={editingRoute} 
        clients={clients} 
        groups={groups} 
        smscs={smscs} 
      />
    </div>
  )
}
