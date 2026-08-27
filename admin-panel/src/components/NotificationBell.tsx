import { useState, useRef, useEffect } from 'react'
import { Bell, Check, Info, AlertTriangle, AlertCircle } from 'lucide-react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { getAlertHistory, acknowledgeAlert, massAcknowledgeAlerts } from '../api/client'
import { format } from 'date-fns'

const severityIcon = (s: string) => {
  switch (s) {
    case 'CRITICAL': return <AlertCircle size={14} className="text-red-400" />
    case 'WARNING': return <AlertTriangle size={14} className="text-amber-600 dark:text-amber-400" />
    default: return <Info size={14} className="text-blue-400" />
  }
}

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const qc = useQueryClient()

  const { data: alertsData } = useQuery({ 
    queryKey: ['active-alerts-bell'], 
    queryFn: () => getAlertHistory(0, 5, false), 
    refetchInterval: 15_000 
  })
  const activeAlerts = alertsData?.content ?? []
  const unreadCount = alertsData?.totalElements ?? 0

  const ackMut = useMutation({
    mutationFn: acknowledgeAlert,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-alerts-bell'] })
      qc.invalidateQueries({ queryKey: ['active-alerts'] }) // for NotificationsPage
      qc.invalidateQueries({ queryKey: ['archived-alerts'] })
    },
  })

  const massAckMut = useMutation({
    mutationFn: massAcknowledgeAlerts,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-alerts-bell'] })
      qc.invalidateQueries({ queryKey: ['active-alerts'] })
      qc.invalidateQueries({ queryKey: ['archived-alerts'] })
      setOpen(false)
    },
  })

  // Close dropdown on outside click
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    if (open) document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [open])

  const handleAlertClick = (alert: any) => {
    // If it's a security alert, maybe navigate to ai-agent or notifications
    navigate('/notifications')
    setOpen(false)
  }

  return (
    <div className="relative" ref={ref}>
      <button 
        onClick={() => setOpen(!open)}
        className="relative text-slate-700 dark:text-slate-500 hover:text-amber-500 transition p-1.5 rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]" 
        title="Notifications"
      >
        <Bell size={15} />
        {unreadCount > 0 && (
          <span className="absolute top-0 right-0 -mt-1 -mr-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-[9px] font-bold text-white shadow-sm ring-2 ring-white dark:ring-[#12121f]">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute bottom-full mb-2 left-0 w-80 bg-white dark:bg-[#1e1e2d] border border-slate-200 dark:border-white/10 shadow-2xl rounded-xl overflow-hidden z-50">
          <div className="px-4 py-3 border-b border-slate-100 dark:border-white/5 flex items-center justify-between bg-slate-50 dark:bg-[#1a1a26]">
            <h3 className="font-bold text-sm text-slate-900 dark:text-white">Notifications</h3>
            {unreadCount > 0 && (
              <button 
                onClick={() => massAckMut.mutate()}
                className="text-xs text-brand-600 dark:text-brand-400 font-medium hover:underline"
              >
                Mark all read
              </button>
            )}
          </div>
          
          <div className="max-h-80 overflow-y-auto">
            {activeAlerts.length === 0 ? (
              <div className="p-6 text-center text-sm text-slate-500 dark:text-slate-400">
                You're all caught up!
              </div>
            ) : (
              <div className="flex flex-col">
                {activeAlerts.map((a: any) => (
                  <div key={a.id} className="group flex flex-col p-3 border-b border-slate-100 dark:border-white/5 hover:bg-slate-50 dark:hover:bg-white/[0.02] transition cursor-pointer" onClick={() => handleAlertClick(a)}>
                    <div className="flex items-start gap-3">
                      <div className="mt-0.5">{severityIcon(a.severity)}</div>
                      <div className="flex-1 min-w-0">
                        <div className="text-xs text-slate-800 dark:text-slate-200 font-medium line-clamp-2">
                          {a.message}
                        </div>
                        <div className="text-[10px] text-slate-500 dark:text-slate-500 mt-1">
                          {format(new Date(a.createdAt), 'MMM d, HH:mm')}
                        </div>
                      </div>
                      <button 
                        onClick={(e) => { e.stopPropagation(); ackMut.mutate(a.id); }}
                        className="opacity-0 group-hover:opacity-100 text-slate-400 hover:text-emerald-500 transition p-1"
                        title="Acknowledge"
                      >
                        <Check size={14} />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
          <div className="p-2 border-t border-slate-100 dark:border-white/5 bg-slate-50 dark:bg-[#1a1a26] text-center">
            <button 
              onClick={() => { navigate('/notifications'); setOpen(false); }}
              className="text-xs text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white transition font-medium"
            >
              View all notifications
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
