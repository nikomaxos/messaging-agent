import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getNotificationConfigs, createNotificationConfig, updateNotificationConfig,
  deleteNotificationConfig, getAlertHistory, acknowledgeAlert, massAcknowledgeAlerts,
  getGroups, getSmscSuppliers
} from '../api/client'
import { Bell, Plus, Trash2, Check, X, ToggleLeft, ToggleRight, AlertTriangle, AlertCircle, Info } from 'lucide-react'
import { format } from 'date-fns'

const ALERT_TYPES = [
  { value: 'LOW_DELIVERY_RATE', label: 'Low Delivery Rate', desc: 'Fires when delivery rate falls below threshold %' },
  { value: 'QUEUE_BUILDUP', label: 'Queue Buildup', desc: 'Fires when queued messages exceed threshold count' },
  { value: 'DEVICE_OFFLINE', label: 'Device Offline', desc: 'Fires when offline device count exceeds threshold' },
  { value: 'SMSC_DISCONNECT', label: 'SMSC Disconnect', desc: 'Fires when active SMSC suppliers disconnect' },
  { value: 'HIGH_LATENCY', label: 'High Latency', desc: 'Fires when avg delivery latency exceeds threshold (future)' },
]

const severityIcon = (s: string) => {
  switch (s) {
    case 'CRITICAL': return <AlertCircle size={14} className="text-red-400" />
    case 'WARNING': return <AlertTriangle size={14} className="text-amber-400" />
    default: return <Info size={14} className="text-blue-400" />
  }
}

export default function NotificationsPage() {
  const qc = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editConfig, setEditConfig] = useState<any>(null)
  const [form, setForm] = useState<{
    name: string; type: string; threshold: number; cooldownMinutes: number; enabled: boolean;
    channels: string[]; alertDeviceGroupId: number | null; alertSmppSupplierId: number | null;
  }>({ 
    name: '', type: 'LOW_DELIVERY_RATE', threshold: 50, cooldownMinutes: 15, enabled: true,
    channels: ['BROWSER_PUSH'], alertDeviceGroupId: null, alertSmppSupplierId: null
  })

  const { data: deviceGroups = [] } = useQuery({ queryKey: ['groups'], queryFn: getGroups })
  const { data: smppSuppliers = [] } = useQuery({ queryKey: ['smsc-suppliers'], queryFn: getSmscSuppliers })

  const { data: configs = [] } = useQuery({ queryKey: ['notif-configs'], queryFn: getNotificationConfigs })
  const { data: alertsData } = useQuery({ queryKey: ['active-alerts'], queryFn: () => getAlertHistory(0, 100, false), refetchInterval: 15_000 })
  const { data: archivedAlertsData } = useQuery({ queryKey: ['archived-alerts'], queryFn: () => getAlertHistory(0, 50, true) })

  const activeAlerts = alertsData?.content ?? []
  const archivedAlerts = archivedAlertsData?.content ?? []

  const createMut = useMutation({
    mutationFn: createNotificationConfig,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['notif-configs'] }); resetForm() },
  })
  const updateMut = useMutation({
    mutationFn: updateNotificationConfig,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['notif-configs'] }); resetForm() },
  })
  const deleteMut = useMutation({
    mutationFn: deleteNotificationConfig,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notif-configs'] }),
  })
  const ackMut = useMutation({
    mutationFn: acknowledgeAlert,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-alerts'] })
      qc.invalidateQueries({ queryKey: ['archived-alerts'] })
    },
  })
  const massAckMut = useMutation({
    mutationFn: massAcknowledgeAlerts,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-alerts'] })
      qc.invalidateQueries({ queryKey: ['archived-alerts'] })
    },
  })

  const resetForm = () => {
    setShowForm(false)
    setEditConfig(null)
    setForm({ 
      name: '', type: 'LOW_DELIVERY_RATE', threshold: 50, cooldownMinutes: 15, enabled: true,
      channels: ['BROWSER_PUSH'], alertDeviceGroupId: null, alertSmppSupplierId: null
    })
  }

  const startEdit = (c: any) => {
    setEditConfig(c)
    setForm({ 
      name: c.name, type: c.type, threshold: c.threshold, 
      cooldownMinutes: c.cooldownMinutes, enabled: c.enabled,
      channels: c.channels || [],
      alertDeviceGroupId: c.alertDeviceGroupId || null,
      alertSmppSupplierId: c.alertSmppSupplierId || null
    })
    setShowForm(true)
  }

  const toggleChannel = (ch: string) => {
    setForm(prev => ({
      ...prev,
      channels: prev.channels.includes(ch)
        ? prev.channels.filter(c => c !== ch)
        : [...prev.channels, ch]
    }))
  }

  const saveConfig = () => {
    if (editConfig) {
      updateMut.mutate({ ...form, id: editConfig.id })
    } else {
      createMut.mutate(form)
    }
  }

  const toggleEnabled = (c: any) => {
    updateMut.mutate({ ...c, enabled: !c.enabled })
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white mb-1">Notifications & Alerts</h1>
          <p className="text-slate-400 text-sm">Configure alert rules and view triggered notifications.</p>
        </div>
        <button className="bg-brand-600 hover:bg-brand-500 text-white rounded px-4 py-1.5 text-sm font-medium transition flex items-center gap-2"
          onClick={() => { resetForm(); setShowForm(true) }}>
          <Plus size={14} /> New Alert Rule
        </button>
      </div>

      {/* ── Alert Rules ───────────────────────────────────────────── */}
      <div className="glass p-5">
        <h2 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
          <Bell size={14} className="text-brand-400" /> Alert Rules
        </h2>
        {configs.length === 0 ? (
          <div className="text-slate-500 text-sm text-center py-6">No alert rules configured yet.</div>
        ) : (
          <div className="space-y-2">
            {configs.map((c: any) => (
              <div key={c.id} className={`flex items-center justify-between p-3 rounded-lg border ${c.enabled ? 'bg-white/[0.02] border-white/5' : 'bg-slate-900/30 border-slate-800 opacity-60'}`}>
                <div className="flex items-center gap-3 flex-1">
                  <button onClick={() => toggleEnabled(c)} className="text-slate-400 hover:text-white transition" title="Toggle">
                    {c.enabled ? <ToggleRight size={20} className="text-emerald-400" /> : <ToggleLeft size={20} />}
                  </button>
                  <div>
                    <div className="text-sm font-medium text-white">{c.name}</div>
                    <div className="text-[10px] text-slate-500">
                      {c.type} • Threshold: {c.threshold} • Cooldown: {c.cooldownMinutes}min
                      {c.channels && c.channels.length > 0 && ` • Channels: ${c.channels.join(', ')}`}
                      {c.lastTriggeredAt && <> • Last: {format(new Date(c.lastTriggeredAt), 'MMM d HH:mm')}</>}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button className="text-xs text-slate-400 hover:text-brand-400 transition" onClick={() => startEdit(c)}>Edit</button>
                  <button className="text-slate-500 hover:text-red-400 transition" onClick={() => deleteMut.mutate(c.id)}>
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Active Alert History ──────────────────────────────────────────── */}
      <div className="glass p-5">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-bold text-white flex items-center gap-2">
            <AlertTriangle size={14} className="text-amber-400" /> Active Alerts
          </h2>
          {activeAlerts.length > 0 && (
            <button 
              className="px-3 py-1 bg-emerald-900/40 hover:bg-emerald-900/60 text-emerald-400 text-xs font-semibold rounded transition"
              onClick={() => massAckMut.mutate()}
              disabled={massAckMut.isPending}
            >
              Mass Acknowledge
            </button>
          )}
        </div>
        
        {activeAlerts.length === 0 ? (
          <div className="text-slate-500 text-sm text-center py-6">No unacknowledged alerts.</div>
        ) : (
          <div className="space-y-1 max-h-[400px] overflow-y-auto pr-1">
            {activeAlerts.map((a: any) => (
              <div key={a.id} className={`flex items-center gap-3 px-3 py-2 rounded text-xs ${a.severity === 'CRITICAL' ? 'bg-red-900/10 border border-red-500/15' : a.severity === 'WARNING' ? 'bg-amber-900/10 border border-amber-500/15' : 'bg-white/[0.02] border border-white/5'}`}>
                {severityIcon(a.severity)}
                <span className="text-slate-300 flex-1">{a.message}</span>
                <span className="text-slate-600 font-mono">{a.metricValue != null ? `${a.metricValue}` : ''}</span>
                <span className="text-slate-600 whitespace-nowrap">{format(new Date(a.createdAt), 'MMM d HH:mm')}</span>
                <button className="text-emerald-500 hover:text-emerald-400 transition" onClick={() => ackMut.mutate(a.id)} title="Acknowledge">
                  <Check size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Archived Alerts ──────────────────────────────────────────────── */}
      <div className="glass p-5 opacity-80">
        <h2 className="text-sm font-bold text-white mb-3 flex items-center gap-2">
          <Info size={14} className="text-slate-400" /> Archived Alerts (Acknowledged)
        </h2>
        {archivedAlerts.length === 0 ? (
          <div className="text-slate-500 text-sm text-center py-6">No archived alerts found.</div>
        ) : (
          <div className="space-y-1 max-h-[300px] overflow-y-auto pr-1">
            {archivedAlerts.map((a: any) => (
              <div key={a.id} className="flex items-center gap-3 px-3 py-2 rounded text-xs bg-black/20 border border-white/5 opacity-70">
                {severityIcon(a.severity)}
                <span className="text-slate-400 flex-1">{a.message}</span>
                <span className="text-slate-600 font-mono">{a.metricValue != null ? `${a.metricValue}` : ''}</span>
                <span className="text-slate-600 whitespace-nowrap">{format(new Date(a.createdAt), 'MMM d HH:mm')}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Create/Edit Modal ──────────────────────────────────────── */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" onClick={resetForm}>
          <div className="bg-[#12121f] border border-white/10 rounded-xl shadow-2xl max-w-md w-full" onClick={e => e.stopPropagation()}>
            <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between">
              <h2 className="text-lg font-bold text-white">{editConfig ? 'Edit Rule' : 'New Alert Rule'}</h2>
              <button onClick={resetForm} className="text-slate-500 hover:text-white transition"><X size={20} /></button>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Name</label>
                <input className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                  value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="e.g. Low Delivery Warning" />
              </div>
              <div>
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Alert Type</label>
                <select className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                  value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}>
                  {ALERT_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                <div className="text-[10px] text-slate-600 mt-1">{ALERT_TYPES.find(t => t.value === form.type)?.desc}</div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Threshold</label>
                  <input type="number" className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                    value={form.threshold} onChange={e => setForm({ ...form, threshold: Number(e.target.value) })} />
                </div>
                <div>
                  <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Cooldown (min)</label>
                  <input type="number" className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                    value={form.cooldownMinutes} onChange={e => setForm({ ...form, cooldownMinutes: Number(e.target.value) })} />
                </div>
              </div>
              <div className="pt-2">
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-2">Notification Channels</label>
                <div className="space-y-2">
                  <label className="flex items-center gap-2 text-sm text-slate-300">
                    <input type="checkbox" checked={form.channels.includes('BROWSER_PUSH')} onChange={() => toggleChannel('BROWSER_PUSH')} className="rounded border-slate-700 bg-slate-900 text-brand-500" />
                    Browser Push
                  </label>
                  <label className="flex items-center gap-2 text-sm text-slate-300">
                    <input type="checkbox" checked={form.channels.includes('RCS_VIRTUAL_SMSC')} onChange={() => toggleChannel('RCS_VIRTUAL_SMSC')} className="rounded border-slate-700 bg-slate-900 text-brand-500" />
                    RCS via Virtual SMSC
                  </label>
                  {form.channels.includes('RCS_VIRTUAL_SMSC') && (
                    <div className="pl-6 pt-1">
                      <select className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-1.5"
                        value={form.alertDeviceGroupId || ''} onChange={e => setForm({ ...form, alertDeviceGroupId: e.target.value ? Number(e.target.value) : null })}>
                        <option value="">-- Select Admin Device Group --</option>
                        {deviceGroups.map((g: any) => <option key={g.id} value={g.id}>{g.name}</option>)}
                      </select>
                    </div>
                  )}
                  <label className="flex items-center gap-2 text-sm text-slate-300">
                    <input type="checkbox" checked={form.channels.includes('SMPP_SUPPLIER')} onChange={() => toggleChannel('SMPP_SUPPLIER')} className="rounded border-slate-700 bg-slate-900 text-brand-500" />
                    SMS via SMPP
                  </label>
                  {form.channels.includes('SMPP_SUPPLIER') && (
                    <div className="pl-6 pt-1">
                      <select className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-1.5"
                        value={form.alertSmppSupplierId || ''} onChange={e => setForm({ ...form, alertSmppSupplierId: e.target.value ? Number(e.target.value) : null })}>
                        <option value="">-- Select Admin SMPP Supplier --</option>
                        {smppSuppliers.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name} ({s.supplier.systemId})</option>)}
                      </select>
                    </div>
                  )}
                </div>
              </div>
              <div className="flex justify-end gap-3 pt-2">
                <button className="btn-secondary" onClick={resetForm}>Cancel</button>
                <button className="bg-brand-600 hover:bg-brand-500 text-white rounded px-4 py-1.5 text-sm font-medium transition disabled:opacity-40"
                  disabled={!form.name} onClick={saveConfig}>
                  {editConfig ? 'Save Changes' : 'Create Rule'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
