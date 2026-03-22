import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getUsers, createUser, updateUser, resetPassword, deleteUser } from '../api/client'
import { AppUser } from '../types'
import { Plus, Pencil, Trash2, KeyRound, ShieldCheck, ShieldOff } from 'lucide-react'
import { format, formatDistanceToNow } from 'date-fns'

export default function UsersPage() {
  const qc = useQueryClient()
  const { data: users = [] } = useQuery<AppUser[]>({ queryKey: ['users'], queryFn: getUsers })

  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<AppUser | null>(null)
  const [form, setForm] = useState({ username: '', password: '', role: 'ADMIN' })
  const [pwResetId, setPwResetId] = useState<number | null>(null)
  const [newPassword, setNewPassword] = useState('')
  const [confirmAction, setConfirmAction] = useState<{ title: string; message: string; onConfirm: () => void } | null>(null)

  const createMut = useMutation({ mutationFn: (d: any) => createUser(d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['users'] }); reset() },
    onError: (err: any) => alert(err.response?.data?.error || err.message)
  })
  const updateMut = useMutation({ mutationFn: ({ id, d }: { id: number, d: any }) => updateUser(id, d),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['users'] }); reset() },
    onError: (err: any) => alert(err.response?.data?.error || err.message)
  })
  const deleteMut = useMutation({ mutationFn: deleteUser,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
    onError: (err: any) => alert(err.response?.data?.error || err.message)
  })
  const pwResetMut = useMutation({ mutationFn: ({ id, pw }: { id: number, pw: string }) => resetPassword(id, pw),
    onSuccess: () => { setPwResetId(null); setNewPassword(''); alert('Password updated') },
    onError: (err: any) => alert(err.response?.data?.error || err.message)
  })

  const reset = () => { setShowForm(false); setEditing(null); setForm({ username: '', password: '', role: 'ADMIN' }) }
  const openEdit = (u: AppUser) => { setEditing(u); setForm({ username: u.username, password: '', role: u.role }); setShowForm(true) }
  const save = () => {
    if (editing) updateMut.mutate({ id: editing.id, d: { username: form.username, role: form.role } })
    else createMut.mutate(form)
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">Users</h1>
          <p className="text-slate-400 text-sm mt-0.5">Manage admin panel user accounts</p>
        </div>
        <button id="add-user-btn" className="btn-primary" onClick={() => setShowForm(true)}>
          <Plus size={16} /> Add User
        </button>
      </div>

      {showForm && (
        <div className="glass p-5 space-y-4">
          <h2 className="text-sm font-semibold text-slate-300">{editing ? 'Edit' : 'New'} User</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">Username</label>
              <input className="input text-black" placeholder="Enter username" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} />
            </div>
            {!editing && (
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1">Password</label>
                <input className="input text-black" type="password" placeholder="Enter password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
              </div>
            )}
            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">Role</label>
              <select className="input text-black" value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}>
                <option value="ADMIN">Admin</option>
                <option value="VIEWER">Viewer</option>
              </select>
            </div>
          </div>
          <div className="flex gap-2">
            <button className="btn-primary" onClick={save}>
              {editing ? 'Update' : 'Create'}
            </button>
            <button className="btn-secondary" onClick={reset}>Cancel</button>
          </div>
        </div>
      )}

      <div className="glass overflow-hidden">
        <table className="tbl">
          <thead><tr>
            <th className="px-4 pt-4 pb-3">Username</th>
            <th className="px-4">Role</th>
            <th className="px-4">Status</th>
            <th className="px-4">Created</th>
            <th className="px-4 text-right">Actions</th>
          </tr></thead>
          <tbody>
            {users.map((u: AppUser) => (
              <tr key={u.id} className="hover:bg-white/[0.03] transition">
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 rounded-full bg-brand-900 flex items-center justify-center text-brand-400 font-semibold text-xs uppercase">
                      {u.username[0]}
                    </div>
                    <span className="font-medium text-white">{u.username}</span>
                  </div>
                </td>
                <td className="px-4">
                  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                    u.role === 'ADMIN' ? 'bg-brand-900/40 text-brand-400' : 'bg-slate-800 text-slate-400'
                  }`}>
                    {u.role === 'ADMIN' ? <ShieldCheck size={10} /> : <ShieldOff size={10} />}
                    {u.role}
                  </span>
                </td>
                <td className="px-4">
                  <button
                    className={`px-2 py-0.5 rounded-full text-[10px] font-semibold transition ${
                      u.active ? 'bg-emerald-900/40 text-emerald-400 hover:bg-emerald-900/60' : 'bg-red-900/40 text-red-400 hover:bg-red-900/60'
                    }`}
                    onClick={() => updateMut.mutate({ id: u.id, d: { active: !u.active } })}
                    title={u.active ? 'Click to deactivate' : 'Click to activate'}
                  >
                    {u.active ? 'Active' : 'Inactive'}
                  </button>
                </td>
                <td className="px-4 text-slate-400 text-xs">
                  {u.createdAt && (
                    <span title={format(new Date(u.createdAt), 'PPpp')}>
                      {formatDistanceToNow(new Date(u.createdAt), { addSuffix: true })}
                    </span>
                  )}
                </td>
                <td className="px-4 text-right">
                  <div className="flex items-center justify-end gap-1">
                    {pwResetId === u.id ? (
                      <div className="flex items-center gap-1">
                        <input
                          className="input !py-0.5 !px-2 !text-xs w-28"
                          type="password"
                          placeholder="New password"
                          value={newPassword}
                          onChange={e => setNewPassword(e.target.value)}
                          autoFocus
                        />
                        <button className="btn-primary !px-2 !py-0.5 !text-xs" onClick={() => pwResetMut.mutate({ id: u.id, pw: newPassword })}>Set</button>
                        <button className="btn-secondary !px-2 !py-0.5 !text-xs" onClick={() => { setPwResetId(null); setNewPassword('') }}>✕</button>
                      </div>
                    ) : (
                      <>
                        <button className="btn-secondary !px-2 !py-1 text-amber-400" title="Reset Password" onClick={() => setPwResetId(u.id)}>
                          <KeyRound size={13} />
                        </button>
                        <button className="btn-secondary !px-2 !py-1 text-brand-400" title="Edit" onClick={() => openEdit(u)}>
                          <Pencil size={13} />
                        </button>
                        <button className="btn-danger !px-2 !py-1" title="Delete" onClick={() => setConfirmAction({
                          title: 'Delete User',
                          message: `Delete user "${u.username}"? This cannot be undone.`,
                          onConfirm: () => deleteMut.mutate(u.id)
                        })}>
                          <Trash2 size={13} />
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {users.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-8 text-center text-slate-500">No users found</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Confirm Dialog */}
      {confirmAction && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="glass p-6 max-w-sm space-y-4">
            <h3 className="text-white font-semibold">{confirmAction.title}</h3>
            <p className="text-slate-400 text-sm">{confirmAction.message}</p>
            <div className="flex gap-2 justify-end">
              <button className="btn-secondary" onClick={() => setConfirmAction(null)}>Cancel</button>
              <button className="btn-danger" onClick={() => { confirmAction.onConfirm(); setConfirmAction(null) }}>Delete</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
