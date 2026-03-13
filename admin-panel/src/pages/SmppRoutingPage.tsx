import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSmppRoutings, createSmppRouting, deleteSmppRouting, getGroups, getSmppClients } from '../api/client'
import { SmppRouting, DeviceGroup, SmppClient } from '../types'
import { Plus, Trash2, Route } from 'lucide-react'

export default function SmppRoutingPage() {
  const qc = useQueryClient()
  const { data: routings = [], isFetching } = useQuery({ queryKey: ['smppRoutings'], queryFn: getSmppRoutings })
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: clients = [] } = useQuery({ queryKey: ['clients'], queryFn: getSmppClients })

  const [formData, setFormData] = useState({ smppClientId: '', deviceGroupId: '', isDefault: false })
  const [isCreating, setIsCreating] = useState(false)

  const createMut = useMutation({
    mutationFn: createSmppRouting,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['smppRoutings'] }); setIsCreating(false) }
  })
  const deleteMut = useMutation({
    mutationFn: deleteSmppRouting,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['smppRoutings'] })
  })

  // Save changes
  const handleSave = () => {
    if (formData.smppClientId && formData.deviceGroupId) {
      createMut.mutate(formData as any)
    }
  }

  return (
    <div className="p-8">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">SMPP Routing Table</h1>
          <p className="text-slate-400 text-sm">Map incoming SMPP clients to a Virtual SMSC (Device Group)</p>
        </div>
        <button
          onClick={() => setIsCreating(true)}
          disabled={isCreating}
          className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
        >
          <Plus size={16} /> Add Route
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {isCreating && (
          <div className="bg-[#1a1a2e] border border-brand-500/50 rounded-xl p-5 shadow-sm flex items-end gap-4">
            <div className="flex-1">
              <label className="block text-xs text-slate-400 mb-1">SMPP Client</label>
              <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                value={formData.smppClientId} onChange={(e: any) => setFormData({...formData, smppClientId: e.target.value})}>
                <option value="">-- Select Client --</option>
                {clients.map((c: SmppClient) => <option key={c.id} value={c.id}>{c.name} ({c.systemId})</option>)}
              </select>
            </div>
            <div className="text-slate-500 mb-2">→</div>
            <div className="flex-1">
              <label className="block text-xs text-slate-400 mb-1">Target Virtual SMSC</label>
              <select className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm"
                value={formData.deviceGroupId} onChange={(e: any) => setFormData({...formData, deviceGroupId: e.target.value})}>
                <option value="">-- Select Group --</option>
                {groups.map((g: DeviceGroup) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </div>
            <div className="mb-2">
               <label className="flex items-center gap-2 text-sm text-slate-300">
                 <input type="checkbox" checked={formData.isDefault} onChange={(e: any) => setFormData({...formData, isDefault: e.target.checked})} /> Default Route
               </label>
            </div>
            <button onClick={handleSave} className="bg-brand-600 text-white px-4 py-2 rounded text-sm font-medium hover:bg-brand-500 transition">Save Route</button>
            <button onClick={() => setIsCreating(false)} className="bg-white/5 text-slate-300 px-4 py-2 rounded text-sm hover:bg-white/10 transition">Cancel</button>
          </div>
        )}

        {routings.length === 0 && !isCreating && !isFetching && (
          <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-12 text-center">
            <Route className="mx-auto text-slate-600 mb-3" size={32} />
            <div className="text-slate-400">No active routes defined.</div>
            <div className="text-sm text-slate-500 mt-1">Messages without a route will drop unless a Default Route exists.</div>
          </div>
        )}

        {routings.map((r: any) => (
          <div key={r.id} className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl p-5 shadow-sm flex items-center justify-between hover:border-white/10 transition group">
            <div className="flex items-center gap-4 flex-1">
               <div className="flex-1">
                 <div className="text-xs text-slate-500 mb-1 uppercase font-semibold tracking-wider">Source Client</div>
                 <div className="font-medium text-white">{r.smppClient.name}</div>
                 <div className="text-xs text-slate-400 font-mono mt-0.5">{r.smppClient.systemId}</div>
               </div>
               
               <div className="text-slate-600 px-4">======►</div>

               <div className="flex-1">
                 <div className="text-xs text-slate-500 mb-1 uppercase font-semibold tracking-wider">Target Group</div>
                 <div className="font-medium text-brand-400">{r.deviceGroup.name}</div>
                 <div className="text-xs text-slate-400 mt-0.5">{r.deviceGroup.description || 'Virtual SMSC'}</div>
               </div>
               
               <div className="w-32 text-right">
                  {r.default && <span className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-bold bg-purple-500/10 text-purple-400 border border-purple-500/20 uppercase tracking-widest">Default Route</span>}
               </div>
            </div>
            <div className="pl-6 border-l border-white/[0.05] ml-4">
              <button onClick={() => deleteMut.mutate(r.id)} className="p-2 text-slate-500 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete Route">
                <Trash2 size={18} />
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
