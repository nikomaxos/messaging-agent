import { AlertTriangle } from 'lucide-react'

export function ConfirmModal({ 
  isOpen, 
  title, 
  message, 
  onConfirm, 
  onCancel 
}: { 
  isOpen: boolean, 
  title: string, 
  message: string, 
  onConfirm: () => void, 
  onCancel: () => void 
}) {
  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="glass p-6 w-full max-w-sm rounded-xl shadow-2xl border border-slate-700/50">
        <div className="flex items-start gap-4">
          <div className="p-3 bg-red-500/10 rounded-full text-red-400">
            <AlertTriangle size={24} />
          </div>
          <div className="flex-1 mt-1">
            <h3 className="text-lg font-semibold text-white">{title}</h3>
            <p className="text-sm text-slate-400 mt-1">{message}</p>
          </div>
        </div>
        <div className="flex justify-end gap-3 mt-6">
          <button className="btn-secondary px-4 py-2 text-sm" onClick={onCancel}>Cancel</button>
          <button className="btn-danger px-4 py-2 text-sm" onClick={() => { onConfirm(); onCancel() }}>Confirm</button>
        </div>
      </div>
    </div>
  )
}
