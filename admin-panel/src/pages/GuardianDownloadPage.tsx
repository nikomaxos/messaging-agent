import React from 'react'
import { Download, ShieldCheck } from 'lucide-react'

export default function GuardianDownloadPage() {
  return (
    <div className="min-h-screen bg-slate-900 bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-950 flex flex-col items-center justify-center p-4">
      <div className="max-w-md w-full bg-slate-800/50 backdrop-blur-xl border border-slate-700 p-8 rounded-3xl shadow-2xl text-center transform transition-all hover:scale-[1.02] duration-300">
        <div className="bg-indigo-500/20 w-24 h-24 rounded-full flex items-center justify-center mx-auto mb-6 shadow-inner border border-indigo-500/30">
          <ShieldCheck className="w-12 h-12 text-indigo-400" />
        </div>
        <h1 className="text-3xl font-bold text-white mb-2 tracking-tight">Messaging Guardian</h1>
        <p className="text-slate-300 mb-8 leading-relaxed">
          Download and install the Guardian app to securely auto-configure your device and keep the Messaging Agent updated.
        </p>
        
        <a 
          href={`/guardian.apk?v=${Date.now()}`}
          download
          className="group relative w-full flex justify-center items-center py-4 px-4 border border-transparent text-base font-semibold rounded-xl text-white bg-indigo-600 hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-slate-900 focus:ring-offset-indigo-500 shadow-[0_0_20px_rgba(79,70,229,0.4)] transition-all duration-300 overflow-hidden"
        >
          <div className="absolute inset-0 bg-white/10 translate-y-full group-hover:translate-y-0 transition-transform duration-300 ease-in-out"></div>
          <span className="relative flex items-center gap-2">
            <Download className="w-5 h-5 group-hover:-translate-y-1 transition-transform" />
            Download Guardian APK
          </span>
        </a>
        <div className="mt-8 pt-6 border-t border-slate-700/50 text-xs text-slate-400">
          *Requires allowing installation from unknown sources during setup.
        </div>
      </div>
    </div>
  )
}
