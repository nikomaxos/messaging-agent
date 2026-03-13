import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getGroups, createGroup, updateGroup, deleteGroup } from '../api/client'
import { DeviceGroup } from '../types'
import { Plus, Pencil, Trash2, X, Check } from 'lucide-react'

type Mode = 'view' | 'create' | 'edit'

export default function GroupsPage() {
  const qc = useQueryClient()
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const [mode, setMode] = useState<Mode>('view')
  const [editing, setEditing] = useState<DeviceGroup | null>(null)
  const [form, setForm] = useState({ name: '', description: '', active: true })

  const createMut = useMutation({
    mutationFn: createGroup,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['groups'] }); resetForm() }
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: number; data: any }) => updateGroup(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['groups'] }); resetForm() }
  })
  const deleteMut = useMutation({
    mutationFn: deleteGroup,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['groups'] })
  })

  const resetForm = () => { setMode('view'); setEditing(null); setForm({ name: '', description: '', active: true }) }
  const openCreate = () => { resetForm(); setMode('create') }
  const openEdit = (g: DeviceGroup) => {
    setEditing(g); setForm({ name: g.name, description: g.description ?? '', active: g.active }); setMode('edit')
  }

  const handleSave = () => {
    if (mode === 'create') createMut.mutate(form)
    else if (mode === 'edit' && editing) updateMut.mutate({ id: editing.id, data: form })
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Device Groups</h1>
          <p className="text-slate-400 text-sm mt-0.5">Each group is a virtual SMSC presented to requesters</p>
        </div>
        <button id="create-group-btn" className="btn-primary" onClick={openCreate}>
          <Plus size={16} /> New Group
        </button>
      </div>

      {/* Form */}
      {mode !== 'view' && (
        <div className="glass p-5 space-y-4">
          <h2 className="text-sm font-semibold text-slate-300">{mode === 'create' ? 'New' : 'Edit'} Group</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Group Name *</label>
              <input id="group-name" className="inp" placeholder="e.g. EMEA Pool"
                value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Description</label>
              <input className="inp" placeholder="Optional description"
                value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
            </div>
          </div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
              <input type="checkbox" checked={form.active}
                onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                className="accent-brand-600 w-4 h-4" />
              Active
            </label>
            <div className="ml-auto flex gap-2">
              <button className="btn-secondary" onClick={resetForm}><X size={15} /> Cancel</button>
              <button id="save-group-btn" className="btn-primary" onClick={handleSave}><Check size={15} /> Save</button>
            </div>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="glass overflow-hidden">
        <table className="tbl">
          <thead><tr className="px-3">
            <th className="px-4 pt-4 pb-3">Name</th>
            <th className="px-4">Description</th>
            <th className="px-4">Status</th>
            <th className="px-4">Created</th>
            <th className="px-4 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {groups.map((g: DeviceGroup) => (
              <tr key={g.id}>
                <td className="px-4 font-medium text-slate-200">{g.name}</td>
                <td className="px-4 text-slate-400">{g.description || '—'}</td>
                <td className="px-4">
                  <span className={`pill ${g.active ? 'pill-green' : 'pill-gray'}`}>
                    {g.active ? 'Active' : 'Inactive'}
                  </span>
                </td>
                <td className="px-4 text-slate-500 text-xs">{new Date(g.createdAt).toLocaleDateString()}</td>
                <td className="px-4 text-right">
                  <div className="flex items-center justify-end gap-2">
                    <button className="btn-secondary !px-2 !py-1" onClick={() => openEdit(g)}>
                      <Pencil size={13} />
                    </button>
                    <button className="btn-danger !px-2 !py-1"
                      onClick={() => window.confirm('Delete this group?') && deleteMut.mutate(g.id)}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {groups.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-8 text-center text-slate-500">No groups yet</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
