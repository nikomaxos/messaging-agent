import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getSmscSuppliers, sendTestMessage, sendStressTest, emulateRouting, getSmppClients } from '../api/client'
import { Send, Terminal, Activity, Zap, Server, Globe } from 'lucide-react'
import { ClientSelect } from '../components/ClientSelect'
import mccList from 'mcc-mnc-list'

export default function TestingPage() {
  const [activeTab, setActiveTab] = useState<'send' | 'emulator' | 'stress'>('send')
  const { data: smscs = [] } = useQuery({ queryKey: ['smscs'], queryFn: getSmscSuppliers })
  const { data: clients = [] } = useQuery({ queryKey: ['clients'], queryFn: getSmppClients })

  const uniqueCountries = [...new Set(mccList.all().map(r => r.countryName).filter(Boolean))].sort()

  const getCharStats = (msg: string, dc: string, protocol: string) => {
    const chars = msg.length;
    if (protocol !== 'SMS') return { chars, parts: 1 };
    if (dc === '0') return { chars, parts: chars <= 160 ? 1 : Math.ceil(chars / 153) };
    return { chars, parts: chars <= 70 ? 1 : Math.ceil(chars / 67) };
  };

  // Send Message State
  const [sendForm, setSendForm] = useState({ senderId: '', message: '', destination: '', supplierId: '', protocol: 'SMS', dataCoding: '0', isFlash: false })
  
  // Emulator State
  const [emuForm, setEmuForm] = useState({ clientSystemId: '', senderId: '', message: '', destination: '', supplierId: '', protocol: 'SMS', dataCoding: '0' })
  const [emuResult, setEmuResult] = useState<any>(null)
  
  // Stress Test State
  const [stressForm, setStressForm] = useState({ senderId: '', message: '', clientSystemId: '', supplierId: '', protocol: 'SMS', amount: 100, countryName: '', simulationMode: 'SIMULATE_DELIVERY', forcedErrorCode: 'DELIVRD', dataCoding: '0', sendTowards: 'RANDOM', specificNumbers: '' })

  const sendMut = useMutation({ mutationFn: sendTestMessage, onSuccess: () => alert("Message sent to queue!"), onError: (e: any) => alert(e.response?.data?.error || e.message) })
  const emuMut = useMutation({ mutationFn: emulateRouting, onSuccess: (data) => setEmuResult(data), onError: (e: any) => alert(e.response?.data?.error || e.message) })
  const stressMut = useMutation({ mutationFn: sendStressTest, onSuccess: () => alert("Stress test initiated!"), onError: (e: any) => alert(e.response?.data?.error || e.message) })

  return (
    <div className="p-8 max-w-7xl mx-auto space-y-6">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-1">Platform Testing</h1>
          <p className="text-slate-600 dark:text-slate-400 text-sm">Send live tests, emulate routing mechanisms, and stress test the platform.</p>
        </div>
      </div>

      <div className="flex gap-4 border-b border-slate-300 dark:border-white/10 mb-6 pb-2">
        <button onClick={() => setActiveTab('send')} className={`flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition ${activeTab === 'send' ? 'bg-brand-600/20 text-brand-400 border border-brand-500/20' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white'}`}>
          <Send size={16} /> Send Message
        </button>
        <button onClick={() => setActiveTab('emulator')} className={`flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition ${activeTab === 'emulator' ? 'bg-brand-600/20 text-brand-400 border border-brand-500/20' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white'}`}>
          <Terminal size={16} /> Emulator
        </button>
        <button onClick={() => setActiveTab('stress')} className={`flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition ${activeTab === 'stress' ? 'bg-brand-600/20 text-brand-400 border border-brand-500/20' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white'}`}>
          <Zap size={16} /> Stress Testing
        </button>
      </div>

      {activeTab === 'send' && (
        <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl p-6 max-w-2xl">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white mb-4">Manual Single Message Test</h2>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-slate-300 mb-1">Protocol</label>
                <select value={sendForm.protocol} onChange={e => setSendForm({...sendForm, protocol: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                  <option value="SMS">Standard SMS</option>
                  <option value="RCS">RCS (via Mautrix)</option>
                  <option value="WHATSAPP">WhatsApp (via Mautrix)</option>
                  <option value="WEBSOCKET">WebSocket App</option>
                </select>
              </div>
              <div>
                <label className="block text-sm text-slate-300 mb-1">Sender ID</label>
                <input type="text" value={sendForm.senderId} onChange={e => setSendForm({...sendForm, senderId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-slate-300 mb-1">Data Coding</label>
                <select value={sendForm.dataCoding} onChange={e => setSendForm({...sendForm, dataCoding: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                  <option value="0">0 - Default (7-bit)</option>
                  <option value="3">3 - Latin-1 (8-bit)</option>
                  <option value="4">4 - Binary (8-bit)</option>
                  <option value="8">8 - UCS2 (Unicode)</option>
                </select>
              </div>
              <div className="flex items-end pb-2">
                <label className="flex items-center gap-2 cursor-pointer text-slate-300 text-sm">
                  <input type="checkbox" checked={sendForm.isFlash} onChange={e => setSendForm({...sendForm, isFlash: e.target.checked})} className="rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/10 text-brand-500 focus:ring-brand-500/50 w-4 h-4" />
                  Flash SMS
                </label>
              </div>
            </div>
            <div>
              <label className="block text-sm text-slate-300 mb-1">Destination Number</label>
              <input type="text" value={sendForm.destination} onChange={e => setSendForm({...sendForm, destination: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
            </div>
            <div>
              <label className="block text-sm text-slate-300 mb-1">Force Supplier Connection (Optional)</label>
              <select value={sendForm.supplierId} onChange={e => setSendForm({...sendForm, supplierId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                <option value="">-- Let Routing Engine Decide --</option>
                {smscs.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm text-slate-300 mb-1">Message Body</label>
              <textarea rows={4} value={sendForm.message} onChange={e => setSendForm({...sendForm, message: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"></textarea>
              <div className="text-xs text-slate-500 mt-1 flex justify-end">
                {getCharStats(sendForm.message, sendForm.dataCoding, sendForm.protocol).chars} chars 
                {sendForm.protocol === 'SMS' && ` (${getCharStats(sendForm.message, sendForm.dataCoding, sendForm.protocol).parts} part${getCharStats(sendForm.message, sendForm.dataCoding, sendForm.protocol).parts > 1 ? 's' : ''})`}
              </div>
            </div>
            <button onClick={() => sendMut.mutate(sendForm)} disabled={sendMut.isPending} className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg font-medium">
              {sendMut.isPending ? 'Sending...' : 'Send Live Message'}
            </button>
          </div>
        </div>
      )}

      {activeTab === 'emulator' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 h-[700px]">
          <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl p-6 overflow-y-auto">
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white mb-4">Input Data</h2>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm text-slate-300 mb-1">Client Emulation</label>
                  <ClientSelect clients={clients} value={emuForm.clientSystemId} onChange={v => setEmuForm({...emuForm, clientSystemId: v})} />
                </div>
                <div>
                  <label className="block text-sm text-slate-300 mb-1">Protocol</label>
                  <select value={emuForm.protocol} onChange={e => setEmuForm({...emuForm, protocol: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                    <option value="SMS">Standard SMS</option>
                    <option value="RCS">RCS (via Mautrix)</option>
                    <option value="WHATSAPP">WhatsApp (via Mautrix)</option>
                    <option value="WEBSOCKET">WebSocket App</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm text-slate-300 mb-1">Sender ID</label>
                  <input type="text" value={emuForm.senderId} onChange={e => setEmuForm({...emuForm, senderId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
                </div>
                <div>
                  <label className="block text-sm text-slate-300 mb-1">Destination Number</label>
                  <input type="text" value={emuForm.destination} onChange={e => setEmuForm({...emuForm, destination: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
                </div>
              </div>
              <div>
                <label className="block text-sm text-slate-300 mb-1">Data Coding</label>
                <select value={emuForm.dataCoding} onChange={e => setEmuForm({...emuForm, dataCoding: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                  <option value="0">0 - Default (7-bit)</option>
                  <option value="3">3 - Latin-1 (8-bit)</option>
                  <option value="4">4 - Binary (8-bit)</option>
                  <option value="8">8 - UCS2 (Unicode)</option>
                </select>
              </div>
              <div>
                <label className="block text-sm text-slate-300 mb-1">Supplier Connection (Optional)</label>
                <select value={emuForm.supplierId} onChange={e => setEmuForm({...emuForm, supplierId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                  <option value="">-- Let Routing Engine Decide --</option>
                  {smscs.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-sm text-slate-300 mb-1">Message Body</label>
                <textarea rows={4} value={emuForm.message} onChange={e => setEmuForm({...emuForm, message: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"></textarea>
                <div className="text-xs text-slate-500 mt-1 flex justify-end">
                  {getCharStats(emuForm.message, emuForm.dataCoding, emuForm.protocol).chars} chars 
                  {emuForm.protocol === 'SMS' && ` (${getCharStats(emuForm.message, emuForm.dataCoding, emuForm.protocol).parts} part${getCharStats(emuForm.message, emuForm.dataCoding, emuForm.protocol).parts > 1 ? 's' : ''})`}
                </div>
              </div>
              <button onClick={() => emuMut.mutate(emuForm)} disabled={emuMut.isPending} className="w-full bg-emerald-600 hover:bg-emerald-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg font-medium">
                {emuMut.isPending ? 'Emulating...' : 'Emulate Routing'}
              </button>
            </div>
          </div>
          
          <div className="bg-[#0a0a0f] border border-slate-300 dark:border-white/10 rounded-xl overflow-hidden flex flex-col shadow-2xl">
            <div className="p-3 bg-white/[0.02] border-b border-white/[0.05] flex items-center gap-2">
              <Activity size={16} className="text-slate-500" />
              <span className="text-sm font-mono text-slate-300">routing_trace.out</span>
            </div>
            <div className="p-4 overflow-y-auto font-mono text-sm flex-1 text-slate-300 space-y-2">
              {!emuResult ? (
                <div className="h-full flex items-center justify-center text-slate-600">Run emulation to see the evaluation trace.</div>
              ) : (
                <>
                  <div className="text-brand-400 font-bold mb-4">=== Routing Emulation Trace ===</div>
                  {emuResult.executionTrace?.map((line: string, i: number) => (
                    <div key={i} className={line.includes('[MODIFIER]') ? 'text-amber-400' : line.includes('[ROUTING]') ? 'text-blue-400' : line.includes('[BILLING]') ? 'text-purple-400' : 'text-slate-300'}>
                      {line}
                    </div>
                  ))}
                  <div className="mt-6 border-t border-slate-300 dark:border-white/10 pt-4">
                    <div className="text-emerald-400 font-bold mb-2">Final Message State before Gateway:</div>
                    <pre className="text-xs bg-black/40 p-3 rounded overflow-x-auto text-slate-300 border border-slate-300 dark:border-white/5">
                      {JSON.stringify({
                        finalSenderId: emuResult.finalSenderId,
                        finalDestination: emuResult.finalDestination,
                        selectedRoute: emuResult.selectedRoute,
                        finalMessage: emuResult.finalMessage
                      }, null, 2)}
                    </pre>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {activeTab === 'stress' && (
        <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl p-6 max-w-2xl">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white mb-4">Volume Stress Test</h2>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-slate-300 mb-1">Client Emulation</label>
                <ClientSelect clients={clients} value={stressForm.clientSystemId} onChange={v => setStressForm({...stressForm, clientSystemId: v})} />
              </div>
              <div>
                <label className="block text-sm text-slate-300 mb-1">Protocol</label>
                <select value={stressForm.protocol} onChange={e => setStressForm({...stressForm, protocol: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                  <option value="SMS">Standard SMS</option>
                  <option value="RCS">RCS (via Mautrix)</option>
                  <option value="WHATSAPP">WhatsApp (via Mautrix)</option>
                  <option value="WEBSOCKET">WebSocket App</option>
                </select>
              </div>
            </div>
            <div className="grid grid-cols-1 gap-4">
              <div>
                <label className="block text-sm text-slate-300 mb-1">Sender ID</label>
                <input type="text" value={stressForm.senderId} onChange={e => setStressForm({...stressForm, senderId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
              </div>
            </div>
            
            <div>
              <label className="block text-sm text-slate-300 mb-1">Data Coding</label>
              <select value={stressForm.dataCoding} onChange={e => setStressForm({...stressForm, dataCoding: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                <option value="0">0 - Default (7-bit)</option>
                <option value="3">3 - Latin-1 (8-bit)</option>
                <option value="4">4 - Binary (8-bit)</option>
                <option value="8">8 - UCS2 (Unicode)</option>
              </select>
            </div>
            
            <div>
              <label className="block text-sm text-slate-300 mb-1">Message Body</label>
              <textarea rows={2} value={stressForm.message} onChange={e => setStressForm({...stressForm, message: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"></textarea>
              <div className="text-xs text-slate-500 mt-1 flex justify-end">
                {getCharStats(stressForm.message, stressForm.dataCoding, stressForm.protocol).chars} chars 
                {stressForm.protocol === 'SMS' && ` (${getCharStats(stressForm.message, stressForm.dataCoding, stressForm.protocol).parts} part${getCharStats(stressForm.message, stressForm.dataCoding, stressForm.protocol).parts > 1 ? 's' : ''})`}
              </div>
            </div>

            <div>
              <label className="block text-sm text-slate-300 mb-1">Send Towards</label>
              <select value={stressForm.sendTowards} onChange={e => setStressForm({...stressForm, sendTowards: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                <option value="RANDOM">Randomly emulated</option>
                <option value="SPECIFIC">Specific numbers</option>
              </select>
            </div>

            {stressForm.sendTowards === 'SPECIFIC' && (
              <div>
                <label className="block text-sm text-slate-300 mb-1">Specific Numbers (comma separated, international format)</label>
                <textarea rows={2} value={stressForm.specificNumbers} onChange={e => setStressForm({...stressForm, specificNumbers: e.target.value})} placeholder="e.g. 306981860567, 306981860568" className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white"></textarea>
              </div>
            )}

            {stressForm.sendTowards === 'RANDOM' && (
              <div className="bg-black/20 p-4 border border-slate-300 dark:border-white/5 rounded-lg space-y-4">
                <h3 className="text-sm font-semibold text-slate-300 mb-2 border-b border-slate-300 dark:border-white/5 pb-2">Generate Test Numbers</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm text-slate-300 mb-1 flex items-center gap-1"><Globe size={14}/> Destination Country</label>
                    <select value={stressForm.countryName} onChange={e => setStressForm({...stressForm, countryName: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                      <option value="">-- Select Country --</option>
                      {uniqueCountries.map((c: any) => (
                        <option key={c} value={c}>{c}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm text-slate-300 mb-1">Test Volume</label>
                    <input type="number" min="1" value={stressForm.amount} onChange={e => setStressForm({...stressForm, amount: parseInt(e.target.value) || 1})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white" />
                  </div>
                </div>
              </div>
            )}

            <div className="bg-black/20 p-4 border border-slate-300 dark:border-white/5 rounded-lg space-y-4">
              <div className="grid grid-cols-1 gap-4">
                <div>
                  <label className="block text-sm text-slate-300 mb-1 flex items-center gap-1"><Server size={14}/> Supplier Connection</label>
                  <select value={stressForm.supplierId} onChange={e => setStressForm({...stressForm, supplierId: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                    <option value="">-- Let Routing Engine Decide --</option>
                    {smscs.map((s: any) => <option key={s.supplier.id} value={s.supplier.id}>{s.supplier.name}</option>)}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4 pt-2 border-t border-slate-300 dark:border-white/5">
                <div>
                  <label className="block text-sm text-slate-300 mb-1">Simulation Mode</label>
                  <select value={stressForm.simulationMode} onChange={e => setStressForm({...stressForm, simulationMode: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-brand-500/30 rounded px-3 py-2 text-slate-900 dark:text-white font-medium">
                    <option value="SIMULATE_DELIVERY">Simulate Delivery (Free)</option>
                    <option value="ACTUAL_SEND">Actually Send (Chargeable)</option>
                  </select>
                </div>
                {stressForm.simulationMode === 'SIMULATE_DELIVERY' && (
                  <div>
                    <label className="block text-sm text-slate-300 mb-1">Simulated Final Status</label>
                    <select value={stressForm.forcedErrorCode} onChange={e => setStressForm({...stressForm, forcedErrorCode: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white">
                      <option value="DELIVRD">DELIVRD (Success)</option>
                      <option value="UNDELIV">UNDELIV (Undelivered)</option>
                      <option value="REJECTD">REJECTD (Rejected)</option>
                    </select>
                  </div>
                )}
              </div>
            </div>

            <button onClick={() => {
              let countToConfirm = stressForm.amount;
              if (stressForm.sendTowards === 'SPECIFIC') {
                const numbers = stressForm.specificNumbers.split(',').map(n => n.trim()).filter(n => n.length > 0);
                countToConfirm = numbers.length;
                if (countToConfirm === 0) {
                  alert("Please enter at least one specific number.");
                  return;
                }
              }
              if (window.confirm(`Are you sure you want to test sending ${countToConfirm} messages?`)) {
                stressMut.mutate(stressForm)
              }
            }} disabled={stressMut.isPending} className="w-full bg-orange-600 hover:bg-orange-500 text-slate-900 dark:text-white px-6 py-3 rounded-lg font-bold uppercase tracking-wider mt-2">
              {stressMut.isPending ? 'Generating Load...' : 'Start Stress Test'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
