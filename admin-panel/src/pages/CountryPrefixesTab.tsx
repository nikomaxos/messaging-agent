import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Globe, Database, Edit2, Save, X, Search, Activity, CheckCircle2, AlertTriangle, XCircle, Info, Moon, ShieldBan } from 'lucide-react'
import { getCountryPrefixes, syncCountryPrefixes, resolvePrefix, updateCountry, updateNetwork } from '../api/client'

export default function CountryPrefixesTab() {
  const qc = useQueryClient()
  const { data: countries = [], isFetching } = useQuery({ queryKey: ['countryPrefixes'], queryFn: getCountryPrefixes })
  
  const [searchTerm, setSearchTerm] = useState('')
  const [testNumber, setTestNumber] = useState('')
  const [testResult, setTestResult] = useState<any>(null)

  // Editing state for Network (inline)
  const [editingNetworkId, setEditingNetworkId] = useState<number | null>(null)
  const [networkForm, setNetworkForm] = useState<any>({})

  // Editing state for Country (modal)
  const [editingCountry, setEditingCountry] = useState<any>(null)

  const updateCountryMut = useMutation({
    mutationFn: ({ id, data }: any) => updateCountry(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['countryPrefixes'] }); setEditingCountry(null) }
  })

  const updateNetworkMut = useMutation({
    mutationFn: ({ id, data }: any) => updateNetwork(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['countryPrefixes'] }); setEditingNetworkId(null) }
  })

  const handleTest = async () => {
    try {
      const data = await resolvePrefix(testNumber);
      setTestResult(data);
    } catch (e: any) {
      setTestResult({ error: e.response?.data?.error || 'Failed to resolve number' });
    }
  }

  // Flatten data for table view
  const flattenedData = countries.flatMap((c: any) => {
    if (!c.networks || c.networks.length === 0) {
      return [{ ...c, isCountryRow: true, rowId: `c-${c.id}` }]
    }
    return c.networks.map((n: any, idx: number) => ({
      ...n,
      countryId: c.id,
      countryName: c.name,
      countryIso: c.isoCode,
      countryMccs: c.mccs,
      countryNotes: c.notes,
      countryQuietHoursStart: c.quietHoursStart,
      countryQuietHoursEnd: c.quietHoursEnd,
      countryHasDndList: c.hasDndList,
      isFirstInCountry: idx === 0,
      rowspan: c.networks.length,
      rowId: `n-${n.id}`
    }))
  })

  const filteredData = flattenedData.filter((r: any) => 
    r.countryName?.toLowerCase().includes(searchTerm.toLowerCase()) || 
    r.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    r.countryIso?.toLowerCase().includes(searchTerm.toLowerCase())
  )

  const getStatusIcon = (status: string) => {
    switch(status) {
      case 'ACTIVE': return <CheckCircle2 size={14} className="text-green-500" />
      case 'INACTIVE': return <Activity size={14} className="text-slate-600 dark:text-slate-400" />
      case 'NOT_MNO_MVNO': return <Info size={14} className="text-blue-500" />
      case 'DEACTIVATED': return <XCircle size={14} className="text-red-500" />
      case 'MERGED': return <AlertTriangle size={14} className="text-orange-500" />
      default: return <Activity size={14} className="text-slate-700 dark:text-slate-500" />
    }
  }

  return (
    <div className="space-y-6 mt-6 relative">
      <div className="flex justify-between items-start">
        <div>
          <h2 className="text-xl font-semibold text-slate-900 dark:text-white">Global Networks & Routing</h2>
          <p className="text-sm text-slate-600 dark:text-slate-400">Manage MCC/MNC allocations and specific routing prefixes per network.</p>
          <p className="text-xs text-brand-400 mt-1 flex items-center gap-1">
            <Database size={12}/> Data Source: Auto-sync + Manual Overrides
          </p>
        </div>
        <div className="flex gap-3">
          <button onClick={() => {
            if (confirm('Are you sure you want to pull the latest carrier database? This will safely merge new codes and preserve your manual notes and statuses.')) {
              syncCountryPrefixes().then((res: any) => {
                alert(`Successfully merged ${res} countries/networks.`);
                qc.invalidateQueries({ queryKey: ['countryPrefixes'] });
              }).catch((err: any) => alert("Error syncing prefixes: " + err.message));
            }
          }} className="bg-slate-50 dark:bg-slate-800 hover:bg-slate-100 dark:bg-slate-700 border border-slate-300 dark:border-white/10 text-slate-900 dark:text-white px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-2 transition">
            <Database size={16} /> Sync Carrier Data
          </button>
        </div>
      </div>

      {/* Number Resolution Tool */}
      <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 p-5 rounded-xl flex gap-4 items-end shadow-sm">
        <div className="flex-1">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">Test Number Resolution (libphonenumber)</label>
          <div className="flex gap-2">
            <input 
              type="text" 
              placeholder="e.g. +44 7911 123456" 
              value={testNumber}
              onChange={e => setTestNumber(e.target.value)}
              className="flex-1 bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg px-4 py-2 text-slate-900 dark:text-white font-mono"
            />
            <button onClick={handleTest} className="bg-blue-600 hover:bg-blue-700 text-white px-6 py-2 rounded-lg font-medium shadow-md transition-colors">
              Resolve
            </button>
          </div>
        </div>
        
        {testResult && (
          <div className="flex-1 bg-white dark:bg-[#12121f] border border-slate-200 dark:border-white/5 rounded-lg p-3 text-sm">
            {testResult.error ? (
              <span className="text-red-500">{testResult.error}</span>
            ) : (
              <div className="grid grid-cols-2 gap-y-2 gap-x-4">
                <div className="text-slate-700 dark:text-slate-500">Valid: <span className={testResult.valid ? "text-green-500 font-bold" : "text-red-500 font-bold"}>{testResult.valid ? 'YES' : 'NO'}</span></div>
                <div className="text-slate-700 dark:text-slate-500">Type: <span className="text-slate-900 dark:text-white font-medium">{testResult.numberType}</span></div>
                <div className="text-slate-700 dark:text-slate-500">Region: <span className="text-slate-900 dark:text-white font-medium">{testResult.region} (+{testResult.countryCode})</span></div>
                <div className="text-slate-700 dark:text-slate-500">Carrier: <span className="text-slate-900 dark:text-white font-medium">{testResult.carrier || 'Unknown'}</span></div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Main Table */}
      <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl overflow-hidden shadow-sm flex flex-col">
        <div className="p-4 border-b border-slate-200 dark:border-white/5 flex gap-4 bg-slate-50 dark:bg-black/20">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-2.5 text-slate-600 dark:text-slate-400 w-4 h-4" />
            <input 
              type="text" 
              placeholder="Search country or network..." 
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg pl-9 pr-4 py-2 text-sm text-slate-900 dark:text-white"
            />
          </div>
        </div>

        <div className="overflow-x-auto flex-1">
          <table className="w-full text-left text-sm text-slate-600 dark:text-slate-300">
            <thead className="bg-slate-200/50 dark:bg-white/5 border-b border-slate-300 dark:border-white/5">
              <tr>
                <th className="px-4 py-3 font-medium w-[280px]">Country</th>
                <th className="px-4 py-3 font-medium w-64">Network</th>
                <th className="px-4 py-3 font-medium w-32">MNCs</th>
                <th className="px-4 py-3 font-medium w-48">Prefixes</th>
                <th className="px-4 py-3 font-medium w-40">Status</th>
                <th className="px-4 py-3 font-medium">Notes</th>
                <th className="px-4 py-3 font-medium w-24 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-white/5">
              {isFetching && flattenedData.length === 0 ? (
                <tr><td colSpan={7} className="p-8 text-center text-slate-700 dark:text-slate-500">Loading network data...</td></tr>
              ) : filteredData.length === 0 ? (
                <tr><td colSpan={7} className="p-8 text-center text-slate-700 dark:text-slate-500">No networks found.</td></tr>
              ) : filteredData.map((row: any) => (
                <tr key={row.rowId} className="hover:bg-white dark:hover:bg-white/[0.02] transition-colors">
                  
                  {/* Country Columns */}
                  {row.isFirstInCountry || row.isCountryRow ? (
                    <td className="px-4 py-3 align-top border-r border-slate-200 dark:border-white/5" rowSpan={row.rowspan || 1}>
                      <div className="flex items-start justify-between group/c">
                        <div>
                          <div className="flex items-center gap-2 mb-1">
                            <Globe size={14} className="text-blue-500" />
                            <span className="font-medium text-slate-900 dark:text-white">{row.countryName || row.name}</span>
                            <span className="text-[10px] bg-slate-200 dark:bg-white/10 px-1 rounded">{row.countryIso || row.isoCode}</span>
                          </div>
                          
                          <div className="text-xs text-slate-700 dark:text-slate-500 font-mono mb-2">MCC: {(row.countryMccs || row.mccs)?.join(', ') || '-'}</div>

                          <div className="flex gap-2">
                            {row.countryQuietHoursStart && row.countryQuietHoursEnd && (
                              <div className="flex items-center gap-1 text-[10px] bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300 px-1.5 py-0.5 rounded" title="Quiet Hours">
                                <Moon size={10} />
                                {row.countryQuietHoursStart}-{row.countryQuietHoursEnd}
                              </div>
                            )}
                            {row.countryHasDndList && (
                              <div className="flex items-center gap-1 text-[10px] bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-300 px-1.5 py-0.5 rounded" title="National DND List active">
                                <ShieldBan size={10} /> DND
                              </div>
                            )}
                          </div>
                        </div>
                        <button 
                          onClick={() => setEditingCountry({ 
                            id: row.countryId || row.id, 
                            name: row.countryName || row.name,
                            mccs: (row.countryMccs || row.mccs)?.join(', ') || '',
                            notes: row.countryNotes || row.notes || '',
                            quietHoursStart: row.countryQuietHoursStart || row.quietHoursStart || '',
                            quietHoursEnd: row.countryQuietHoursEnd || row.quietHoursEnd || '',
                            hasDndList: row.countryHasDndList ?? row.hasDndList ?? false
                          })} 
                          className="text-blue-500 opacity-0 group-hover/c:opacity-100 p-1 bg-blue-500/10 hover:bg-blue-500/20 rounded transition-opacity"
                        >
                          <Edit2 size={12}/>
                        </button>
                      </div>
                    </td>
                  ) : null}

                  {/* Network Columns */}
                  {row.isCountryRow ? (
                    <td colSpan={6} className="px-4 py-3 text-slate-600 dark:text-slate-400 italic">No networks mapped</td>
                  ) : (
                    <>
                      <td className="px-4 py-3 text-slate-800 dark:text-slate-200">{row.name}</td>
                      <td className="px-4 py-3">
                        {editingNetworkId === row.id ? (
                          <input type="text" value={networkForm.mncs} onChange={e => setNetworkForm({...networkForm, mncs: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border rounded px-2 py-1 text-xs font-mono" />
                        ) : (
                          <span className="font-mono text-xs">{row.mncs?.join(', ') || '-'}</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {editingNetworkId === row.id ? (
                          <input type="text" value={networkForm.prefixes} onChange={e => setNetworkForm({...networkForm, prefixes: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border rounded px-2 py-1 text-xs font-mono" placeholder="Comma separated" />
                        ) : (
                          <div className="flex flex-wrap gap-1">
                            {row.prefixes?.length > 0 ? row.prefixes.map((p:string) => <span key={p} className="bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 px-1.5 rounded text-[10px] font-mono">{p}</span>) : '-'}
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {editingNetworkId === row.id ? (
                          <select value={networkForm.operatingStatus} onChange={e => setNetworkForm({...networkForm, operatingStatus: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border rounded px-2 py-1 text-xs">
                            <option value="ACTIVE">Active</option>
                            <option value="INACTIVE">Inactive</option>
                            <option value="NOT_MNO_MVNO">Not an MNO/MVNO</option>
                            <option value="DEACTIVATED">Deactivated</option>
                            <option value="MERGED">Merged</option>
                          </select>
                        ) : (
                          <div className="flex items-center gap-1.5 text-xs font-medium">
                            {getStatusIcon(row.operatingStatus)}
                            <span>{row.operatingStatus?.replace(/_/g, ' ') || 'ACTIVE'}</span>
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {editingNetworkId === row.id ? (
                          <input type="text" value={networkForm.notes || ''} onChange={e => setNetworkForm({...networkForm, notes: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border rounded px-2 py-1 text-xs" placeholder="Admin notes..." />
                        ) : (
                          <span className="text-xs text-slate-700 dark:text-slate-500 line-clamp-2">{row.notes || '-'}</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        {editingNetworkId === row.id ? (
                          <div className="flex justify-end gap-1">
                            <button onClick={() => updateNetworkMut.mutate({ id: row.id, data: { ...row, mncs: networkForm.mncs.split(',').map((s:string)=>s.trim()).filter(Boolean), prefixes: networkForm.prefixes.split(',').map((s:string)=>s.trim()).filter(Boolean), operatingStatus: networkForm.operatingStatus, notes: networkForm.notes } })} className="text-green-500 p-1.5 bg-green-500/10 hover:bg-green-500/20 rounded"><Save size={14}/></button>
                            <button onClick={() => setEditingNetworkId(null)} className="text-slate-700 dark:text-slate-500 p-1.5 bg-slate-500/10 hover:bg-slate-500/20 rounded"><X size={14}/></button>
                          </div>
                        ) : (
                          <button onClick={() => { 
                            setEditingNetworkId(row.id); 
                            setNetworkForm({ 
                              mncs: row.mncs?.join(', ') || '', 
                              prefixes: row.prefixes?.join(', ') || '',
                              operatingStatus: row.operatingStatus || 'ACTIVE',
                              notes: row.notes || ''
                            });
                          }} className="text-blue-500 p-1.5 bg-blue-500/10 hover:bg-blue-500/20 rounded">
                            <Edit2 size={14}/>
                          </button>
                        )}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Country Edit Modal */}
      {editingCountry && (
        <div className="fixed inset-0 bg-slate-900/20 dark:bg-black/60 z-50 flex items-center justify-center p-4 backdrop-blur-sm">
          <div className="bg-white dark:bg-[#1a1a2e] rounded-xl shadow-2xl w-full max-w-lg overflow-hidden border border-slate-200 dark:border-white/10">
            <div className="flex justify-between items-center p-4 border-b border-slate-200 dark:border-white/10 bg-slate-50 dark:bg-black/20">
              <h3 className="text-lg font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                <Globe size={18} className="text-blue-500"/> Edit Country: {editingCountry.name}
              </h3>
              <button onClick={() => setEditingCountry(null)} className="text-slate-600 dark:text-slate-400 hover:text-slate-600 dark:hover:text-slate-700 dark:text-slate-300">
                <X size={20} />
              </button>
            </div>
            
            <div className="p-6 space-y-5">
              <div>
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">MCCs (Comma separated)</label>
                <input 
                  type="text" 
                  value={editingCountry.mccs} 
                  onChange={e => setEditingCountry({...editingCountry, mccs: e.target.value})} 
                  className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg px-4 py-2 text-sm text-slate-900 dark:text-white font-mono" 
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">Admin Notes</label>
                <textarea 
                  value={editingCountry.notes} 
                  onChange={e => setEditingCountry({...editingCountry, notes: e.target.value})} 
                  className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg px-4 py-2 text-sm text-slate-900 dark:text-white min-h-[80px]" 
                  placeholder="Compliance info, general notes..."
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">Quiet Hours Start</label>
                  <input 
                    type="time" 
                    value={editingCountry.quietHoursStart} 
                    onChange={e => setEditingCountry({...editingCountry, quietHoursStart: e.target.value})} 
                    className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg px-4 py-2 text-sm text-slate-900 dark:text-white" 
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">Quiet Hours End</label>
                  <input 
                    type="time" 
                    value={editingCountry.quietHoursEnd} 
                    onChange={e => setEditingCountry({...editingCountry, quietHoursEnd: e.target.value})} 
                    className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded-lg px-4 py-2 text-sm text-slate-900 dark:text-white" 
                  />
                </div>
              </div>

              <div className="flex items-center gap-3 pt-2">
                <input 
                  type="checkbox" 
                  id="dndToggle"
                  checked={editingCountry.hasDndList} 
                  onChange={e => setEditingCountry({...editingCountry, hasDndList: e.target.checked})} 
                  className="w-4 h-4 text-blue-600 rounded border-slate-300 focus:ring-blue-500 bg-white dark:bg-[#12121f]"
                />
                <label htmlFor="dndToggle" className="text-sm font-medium text-slate-700 dark:text-slate-300 flex items-center gap-2">
                  <ShieldBan size={16} className="text-red-500"/> Has National DND List
                </label>
              </div>
            </div>

            <div className="p-4 border-t border-slate-200 dark:border-white/10 bg-slate-50 dark:bg-black/20 flex justify-end gap-3">
              <button onClick={() => setEditingCountry(null)} className="px-4 py-2 text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-100 dark:bg-white/5 rounded-lg transition-colors">
                Cancel
              </button>
              <button 
                onClick={() => updateCountryMut.mutate({ 
                  id: editingCountry.id, 
                  data: { 
                    mccs: editingCountry.mccs.split(',').map((s:string) => s.trim()).filter(Boolean),
                    notes: editingCountry.notes,
                    quietHoursStart: editingCountry.quietHoursStart,
                    quietHoursEnd: editingCountry.quietHoursEnd,
                    hasDndList: editingCountry.hasDndList
                  } 
                })} 
                disabled={updateCountryMut.isPending}
                className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg shadow transition-colors disabled:opacity-50 flex items-center gap-2"
              >
                {updateCountryMut.isPending ? <Activity size={16} className="animate-spin" /> : <Save size={16} />}
                Save Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
