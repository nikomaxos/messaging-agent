import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Edit2, Globe, CheckCircle2, XCircle, Database } from 'lucide-react'
import { ConfirmModal } from '../components/ConfirmModal'
import { getCountryPrefixes, createCountryPrefix, updateCountryPrefix, deleteCountryPrefix, syncCountryPrefixes } from '../api/client'
import mccList from 'mcc-mnc-list'

function PrefixModal({ isOpen, onClose, prefix, isEditing }: any) {
  const qc = useQueryClient()
  const [formData, setFormData] = useState(
    prefix || { countryName: '', prefix: '', networkName: '', mcc: '', mnc: '', iso: '', active: true }
  )

  const allRecords = useMemo(() => mccList.all(), [])
  const uniqueCountries = useMemo(() => Array.from(new Set(allRecords.map(r => r.countryName).filter(Boolean))).sort(), [allRecords])
  const selectedCountryRecords = useMemo(() => allRecords.filter(r => r.countryName === formData.countryName), [allRecords, formData.countryName])

  const createMut = useMutation({
    mutationFn: createCountryPrefix,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['countryPrefixes'] }); onClose() }
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }: any) => updateCountryPrefix(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['countryPrefixes'] }); onClose() }
  })

  const handleSave = () => {
    if (!formData.countryName || !formData.prefix || !formData.networkName) {
      alert("Please fill all fields")
      return
    }
    if (isEditing) {
      updateMut.mutate({ id: prefix.id, data: formData })
    } else {
      createMut.mutate(formData)
    }
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex justify-center items-center">
      <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl w-[500px] shadow-2xl p-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">{isEditing ? 'Edit Prefix' : 'Add Prefix'}</h2>
        
        <div className="space-y-4">
          <div>
            <label className="block text-sm text-slate-300 mb-1">Country Name</label>
            <select 
              className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"
              value={formData.countryName}
              onChange={e => setFormData({...formData, countryName: e.target.value, mcc: '', mnc: '', iso: '', networkName: ''})}
            >
              <option value="">Select a country...</option>
              {uniqueCountries.map((c: any) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm text-slate-300 mb-1">Prefix (e.g., 3069)</label>
            <input type="text" className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" 
                   value={formData.prefix} onChange={e => setFormData({...formData, prefix: e.target.value})} />
          </div>
          <div>
            <label className="block text-sm text-slate-300 mb-1">Network (MCC / MNC)</label>
            <select 
              className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"
              value={`${formData.mcc}-${formData.mnc}`}
              onChange={e => {
                const rec = selectedCountryRecords.find(r => `${r.mcc}-${r.mnc}` === e.target.value)
                if (rec) {
                  setFormData({...formData, mcc: rec.mcc, mnc: rec.mnc, iso: rec.countryCode, networkName: rec.brand || rec.operator || 'Unknown'})
                }
              }}
              disabled={!formData.countryName}
            >
              <option value="-">Select a network...</option>
              {selectedCountryRecords.map((r: any) => (
                <option key={`${r.mcc}-${r.mnc}`} value={`${r.mcc}-${r.mnc}`}>
                  {r.brand || r.operator || 'Unknown'} ({r.mcc} / {r.mnc})
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm text-slate-300 mb-1">Network Name</label>
              <input type="text" className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" 
                     value={formData.networkName} onChange={e => setFormData({...formData, networkName: e.target.value})} />
            </div>
            <div>
              <label className="block text-sm text-slate-300 mb-1">ISO Code</label>
              <input type="text" className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white uppercase" 
                     value={formData.iso || ''} onChange={e => setFormData({...formData, iso: e.target.value.toUpperCase()})} />
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
            <input type="checkbox" checked={formData.active} onChange={e => setFormData({...formData, active: e.target.checked})} className="rounded bg-white dark:bg-[#12121f]" />
            Active
          </label>
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white">Cancel</button>
          <button onClick={handleSave} className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-4 py-2 rounded text-sm font-medium">Save</button>
        </div>
      </div>
    </div>
  )
}

export default function CountryPrefixesTab() {
  const qc = useQueryClient()
  const { data: prefixes = [], isFetching } = useQuery({ queryKey: ['countryPrefixes'], queryFn: getCountryPrefixes })
  
  const [modalOpen, setModalOpen] = useState(false)
  const [editingPrefix, setEditingPrefix] = useState<any>(null)
  const [confirmDelete, setConfirmDelete] = useState<any>(null)

  const deleteMut = useMutation({
    mutationFn: deleteCountryPrefix,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['countryPrefixes'] })
  })

  return (
    <div className="space-y-4 mt-6">
      <ConfirmModal
        isOpen={confirmDelete !== null}
        title="Delete Prefix"
        message={`Are you sure you want to delete prefix ${confirmDelete?.prefix}?`}
        onConfirm={() => { deleteMut.mutate(confirmDelete.id); setConfirmDelete(null) }}
        onCancel={() => setConfirmDelete(null)}
      />

      <div className="flex justify-between items-center">
        <div>
          <h2 className="text-xl font-semibold text-slate-900 dark:text-white">Country Mobile Prefixes</h2>
          <p className="text-sm text-slate-600 dark:text-slate-400">Manage network prefixes and MCC/MNC codes for carrier-grade routing.</p>
          <p className="text-xs text-brand-400 mt-1 flex items-center gap-1">
            <Database size={12}/> Data Source: <code>mcc-mnc-list</code> public npm package
          </p>
        </div>
        <div className="flex gap-3">
          <button onClick={() => {
            if (confirm('Are you sure you want to pull the latest carrier database? This will overwrite existing MCC/MNC mappings.')) {
              syncCountryPrefixes().then(res => {
                alert(`Successfully synced ${res.count} network prefixes.`);
                qc.invalidateQueries({ queryKey: ['countryPrefixes'] });
              }).catch(err => alert("Error syncing prefixes: " + err.message));
            }
          }} className="bg-slate-800 hover:bg-slate-700 border border-slate-300 dark:border-white/10 text-slate-900 dark:text-white px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-2 transition">
            <Database size={16} /> Sync Carrier Data
          </button>
          <button onClick={() => { setEditingPrefix(null); setModalOpen(true) }} className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-2 transition">
            <Plus size={16} /> Add Prefix
          </button>
        </div>
      </div>

      <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-white/[0.05] rounded-xl overflow-hidden">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-black/20 border-b border-slate-300 dark:border-white/5 text-slate-600 dark:text-slate-400 text-xs uppercase tracking-wider">
            <tr>
              <th className="px-6 py-4 font-medium">Country (ISO)</th>
              <th className="px-6 py-4 font-medium">Network Name</th>
              <th className="px-6 py-4 font-medium">MCC / MNC</th>
              <th className="px-6 py-4 font-medium">Prefix</th>
              <th className="px-6 py-4 font-medium">Status</th>
              <th className="px-6 py-4 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/5">
            {prefixes.length === 0 ? (
              <tr><td colSpan={5} className="px-6 py-8 text-center text-slate-500">No prefixes defined.</td></tr>
            ) : (prefixes as any[]).map((p: any) => (
              <tr key={p.id} className="hover:bg-white/[0.02] transition">
                <td className="px-6 py-4 flex items-center gap-2">
                  <Globe size={16} className="text-brand-400" />
                  <span className="font-medium text-slate-900 dark:text-white">{p.countryName}</span>
                  {p.iso && <span className="bg-slate-200 dark:bg-white/10 px-1.5 py-0.5 rounded text-[10px] text-slate-600 dark:text-slate-400 ml-1">{p.iso}</span>}
                </td>
                <td className="px-6 py-4">{p.networkName}</td>
                <td className="px-6 py-4 font-mono text-xs text-slate-600 dark:text-slate-400">
                  {p.mcc && p.mnc ? `${p.mcc} / ${p.mnc}` : '-'}
                </td>
                <td className="px-6 py-4 font-mono text-emerald-400">{p.prefix || '-'}</td>
                <td className="px-6 py-4">
                  {p.active ? <span className="inline-flex items-center gap-1 text-green-400 bg-green-400/10 px-2 py-1 rounded text-xs"><CheckCircle2 size={12}/> Active</span> : <span className="inline-flex items-center gap-1 text-red-400 bg-red-400/10 px-2 py-1 rounded text-xs"><XCircle size={12}/> Inactive</span>}
                </td>
                <td className="px-6 py-4 text-right flex justify-end gap-2">
                  <button onClick={() => { setEditingPrefix(p); setModalOpen(true) }} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white bg-slate-200/50 dark:bg-white/5 rounded"><Edit2 size={14}/></button>
                  <button onClick={() => setConfirmDelete(p)} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded"><Trash2 size={14}/></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <PrefixModal isOpen={modalOpen} onClose={() => setModalOpen(false)} prefix={editingPrefix} isEditing={!!editingPrefix} />
    </div>
  )
}
