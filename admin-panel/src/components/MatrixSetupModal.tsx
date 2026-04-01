import { X, ExternalLink, Copy, Check, QrCode } from 'lucide-react'
import { useState } from 'react'
import { Device } from '../types'

export function MatrixSetupModal({
  isOpen,
  device,
  onClose
}: {
  isOpen: boolean,
  device: Device | null,
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)

  if (!isOpen || !device) return null

  const matrixUserId = `@device_${device.id}:synapse`
  const botUserId = `@gmessagesbot:synapse`

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="glass w-full max-w-lg rounded-xl shadow-2xl border border-brand-500/50 flex flex-col max-h-[90vh]">
        <div className="flex items-center justify-between p-4 border-b border-brand-500/20 bg-brand-500/10 rounded-t-xl">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-brand-500/20 rounded-full text-brand-400">
              <QrCode size={20} />
            </div>
            <div>
              <h3 className="text-lg font-bold text-white leading-tight">Matrix Setup Guide</h3>
              <p className="text-xs text-brand-200">Pairing Device: {device.name}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 hover:bg-white/10 rounded-lg transition text-slate-400 hover:text-white">
            <X size={20} />
          </button>
        </div>
        
        <div className="p-6 overflow-y-auto space-y-5 text-sm">
          <p className="text-slate-300">
            Follow these steps to link this device's physical Google Messages app to the Matrix routing bridge.
          </p>

          <div className="space-y-4">
            <div className="flex gap-4 items-start">
              <div className="w-6 h-6 rounded-full bg-brand-500/20 text-brand-400 flex items-center justify-center shrink-0 text-xs font-bold ring-1 ring-brand-500/50">1</div>
              <div>
                <h4 className="font-semibold text-slate-200">Open Element Web</h4>
                <p className="text-slate-400 mt-1">Open the self-hosted Matrix client in a new tab.</p>
                <a href="http://localhost:8080" target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 mt-2 text-brand-400 hover:text-brand-300 bg-brand-500/10 px-3 py-1.5 rounded-lg border border-brand-500/20 transition">
                  Open Element Web <ExternalLink size={14} />
                </a>
              </div>
            </div>

            <div className="flex gap-4 items-start">
              <div className="w-6 h-6 rounded-full bg-brand-500/20 text-brand-400 flex items-center justify-center shrink-0 text-xs font-bold ring-1 ring-brand-500/50">2</div>
              <div>
                <h4 className="font-semibold text-slate-200">Log In / Register</h4>
                <p className="text-slate-400 mt-1 mb-2">If this device has not been paired before, click <b>Register</b>. Otherwise, <b>Log in</b> with these exact credentials:</p>
                <div className="bg-slate-900 border border-slate-700 rounded-lg overflow-hidden text-sm">
                  <div className="flex items-center justify-between p-2.5 border-b border-slate-700/50">
                    <div className="flex items-center gap-3">
                      <span className="text-slate-500 text-xs uppercase font-bold w-16">USER</span>
                      <code className="text-emerald-400 font-mono select-all">device_{device.id}</code>
                    </div>
                    <button onClick={() => handleCopy(`device_${device.id}`)} className="text-slate-500 hover:text-white transition" title="Copy Username">
                      {copied ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
                    </button>
                  </div>
                  <div className="flex items-center justify-between p-2.5">
                    <div className="flex items-center gap-3">
                      <span className="text-slate-500 text-xs uppercase font-bold w-16">PASS</span>
                      <code className="text-amber-400 font-mono select-all">msgagent-device-{device.id}</code>
                    </div>
                    <button onClick={() => handleCopy(`msgagent-device-${device.id}`)} className="text-slate-500 hover:text-white transition" title="Copy Password">
                      <Copy size={14} />
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div className="flex gap-4 items-start">
              <div className="w-6 h-6 rounded-full bg-brand-500/20 text-brand-400 flex items-center justify-center shrink-0 text-xs font-bold ring-1 ring-brand-500/50">3</div>
              <div>
                <h4 className="font-semibold text-slate-200">Chat with the Bot</h4>
                <p className="text-slate-400 mt-1">Start a new Direct Message in Element with the Mautrix Bridge Bot:</p>
                <div className="bg-slate-900 border border-slate-700 rounded-lg p-2 mt-2 font-mono text-cyan-400 select-all">
                  {botUserId}
                </div>
              </div>
            </div>

            <div className="flex gap-4 items-start">
              <div className="w-6 h-6 rounded-full bg-brand-500/20 text-brand-400 flex items-center justify-center shrink-0 text-xs font-bold ring-1 ring-brand-500/50">4</div>
              <div>
                <h4 className="font-semibold text-slate-200">Generate QR Code</h4>
                <p className="text-slate-400 mt-1">Send the following message to the bot to request a pairing QR Code:</p>
                <div className="bg-slate-900 border border-slate-700 rounded-lg p-2 mt-2 flex items-center gap-2">
                  <span className="text-slate-500">&gt;</span> <code className="text-amber-400">login</code>
                </div>
              </div>
            </div>

            <div className="flex gap-4 items-start">
              <div className="w-6 h-6 rounded-full bg-brand-500/20 text-brand-400 flex items-center justify-center shrink-0 text-xs font-bold ring-1 ring-brand-500/50">5</div>
              <div>
                <h4 className="font-semibold text-slate-200">Scan on Physical Phone</h4>
                <p className="text-slate-400 mt-1">
                  1. Pick up <b>{device.name}</b>.<br/>
                  2. Ensure <b>Google Messages</b> is set as the default SMS app.<br/>
                  3. Open Google Messages &gt; Profile Icon &gt; <b>Device pairing</b>.<br/>
                  4. Tap <b>QR code scanner</b> and scan the code the bot provided.
                </p>
              </div>
            </div>
          </div>
          
          <div className="bg-amber-500/10 border border-amber-500/20 rounded-xl p-4 mt-2">
            <h5 className="font-bold text-amber-500 flex items-center gap-2 text-sm"><X size={16} /> Important Warning</h5>
            <p className="text-amber-200/80 text-xs mt-1 leading-relaxed">
              If Google Messages is not the <b>Default SMS App</b> on the Android phone, the Matrix bridge will disconnect. The native WebSocket routing will be disabled while the Matrix Routing mode is active for this device.
            </p>
          </div>
        </div>

        <div className="p-4 border-t border-slate-700/50 flex justify-end">
          <button className="btn-primary px-6" onClick={onClose}>Done</button>
        </div>
      </div>
    </div>
  )
}
