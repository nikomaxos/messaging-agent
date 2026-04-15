const fs = require('fs');

let code = fs.readFileSync('c:/Dev/messaging-agent/admin-panel/src/pages/DevicesPage.tsx', 'utf-8');

// 1. Add ChevronDown to lucide-react import
code = code.replace("QrCode, Settings } from 'lucide-react'", "QrCode, Settings, ChevronDown } from 'lucide-react'");

// 2. Remove Status column content
code = code.replace(/<div className="flex flex-col gap-0\.5">[\s\S]*?Auto Purge[\s\S]*?<\/select>\s*<\/div>/, '');

// 3. Remove Uptime column content  
code = code.replace(/<div className="flex flex-col gap-0\.5">[\s\S]*?Interval[\s\S]*?<\/input>\s*<\/div>/, '');

// 4. Remove Groups column content
code = code.replace(/<label className="flex items-center gap-1 cursor-pointer text-\[9px\] text-slate-400 font-medium whitespace-nowrap mt-\[10px\]">[\s\S]*?toggleAutoReboot[\s\S]*?<\/label>/, '');

// 5. Remove Battery column content
code = code.replace(/<label className="flex items-center gap-1 cursor-pointer text-\[9px\] text-slate-400 font-medium whitespace-nowrap mt-\[10px\]">[\s\S]*?toggleSelfHealing[\s\S]*?<\/label>/, '');

// 6. Remove Interface column content
code = code.replace(/<label className="flex items-center gap-1 cursor-pointer text-\[9px\] text-slate-400 font-medium whitespace-nowrap mt-\[10px\]">[\s\S]*?toggleSilentMode[\s\S]*?<\/label>/, '');

// 7. Remove Wi-Fi column content
code = code.replace(/<label className="flex items-center gap-1 cursor-pointer text-\[9px\] text-slate-400 font-medium whitespace-nowrap mt-\[10px\]">[\s\S]*?toggleCallBlock[\s\S]*?<\/label>/, '');


// 8. Replace gear button
code = code.replace(
  /<button\s+className={`btn-secondary flex items-center justify-center p-\[7px\][\s\S]*?<\/button>/,
  `<button 
                      className={\`btn-secondary flex flex-col items-center justify-center p-1.5 min-w-[32px] rounded-lg border transition-all \${expandedRows.includes(d.id) ? 'bg-indigo-500/20 shadow-[0_0_15px_rgba(99,102,241,0.3)] border-indigo-500/50 text-indigo-400' : 'bg-slate-800 hover:bg-slate-700 border-slate-700 text-slate-300'}\`}
                      onClick={() => toggleRow(d.id)}
                    >
                      <Settings size={14} className={\`transition-transform duration-300 \${expandedRows.includes(d.id) ? 'rotate-180' : ''}\`} />
                      {expandedRows.includes(d.id) && <ChevronDown size={10} className="mt-0.5 opacity-80 animate-bounce" />}
                    </button>`
);

// 9. Redesign Expanded row
code = code.replace(
  /<tr className="border-b border-slate-700\/50 bg-\[#12121f\]\/90">[\s\S]*?<\/tr>/,
  `<tr className="border-b border-slate-700/50 bg-[#0d0d17] shadow-inner">
                  <td colSpan={15} className="p-3">
                    <div className="flex flex-col gap-3">
                      <div className="flex flex-wrap items-center justify-start gap-3 p-3 bg-slate-800/20 border border-slate-700/30 rounded-lg">
                        <div className="flex items-center gap-3 bg-[#12121f]/50 px-2.5 py-1.5 rounded border border-slate-700/50 backdrop-blur-sm">
                          <label className="flex items-center gap-1 text-[9px] text-slate-500 uppercase tracking-wider font-bold">
                            Auto Purge 
                            <span className="relative group">
                              <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                              <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2 py-1.5 whitespace-nowrap z-50 shadow-xl">
                                Automatically removes old data<br/>to prevent device storage full.
                              </span>
                            </span>
                          </label>
                          <select className="bg-slate-800/80 text-[10px] text-slate-300 border border-slate-700 rounded px-1.5 py-0.5 w-[75px]" value={d.autoPurge ?? 'OFF'} onChange={e => setAutoPurge(d, e.target.value)}>
                            <option value="OFF">Off</option>
                            <option value="MESSAGES">Messages</option>
                            <option value="SYSTEM_LOGS">Sys Logs</option>
                            <option value="ALL">All</option>
                          </select>
                        </div>
                        
                        <div className="flex items-center gap-3 bg-[#12121f]/50 px-2.5 py-1.5 rounded border border-slate-700/50 backdrop-blur-sm">
                          <label className="flex items-center gap-1 text-[9px] text-slate-500 uppercase tracking-wider font-bold">
                            Interval
                            <span className="relative group">
                              <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                              <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2 py-1.5 whitespace-nowrap z-50 shadow-xl">
                                How often the device sends<br/>heartbeats and pending messages.
                              </span>
                            </span>
                          </label>
                          <div className="relative">
                            <input type="number" step="0.5" min="0" className="bg-slate-800/80 text-[10px] text-slate-300 border border-slate-700 rounded pl-2 pr-4 py-0.5 w-[55px] text-center" value={d.sendIntervalSeconds ?? 0} onChange={e => setSendInterval(d, parseFloat(e.target.value) || 0)} />
                            <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[8px] text-slate-500">s</span>
                          </div>
                        </div>

                        <div className="h-6 w-px bg-slate-700/50 mx-1"></div>

                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-2.5 py-1.5 bg-slate-800/30 hover:bg-slate-800/80 border border-slate-700/50 rounded transition">
                          <input type="checkbox" className="accent-brand-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.autoRebootEnabled ?? false} onChange={() => toggleAutoReboot(d)} /> Auto Reboot
                          <span className="relative group z-50">
                            <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                            <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2.5 py-2 whitespace-nowrap shadow-xl">
                              Reboots device when (last 2h):<br/>
                              • Delivery rate &lt; 50%, OR<br/>
                              • Avg latency &gt; 30 seconds<br/>
                            </span>
                          </span>
                        </label>

                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-2.5 py-1.5 bg-slate-800/30 hover:bg-slate-800/80 border border-slate-700/50 rounded transition">
                          <input type="checkbox" className="accent-cyan-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.selfHealingEnabled ?? false} onChange={() => toggleSelfHealing(d)} /> Heal
                          <span className="relative group z-50">
                            <Info size={10} className="text-slate-600 hover:text-brand-400 cursor-help transition" />
                            <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block bg-slate-800 border border-slate-700 text-slate-200 text-[10px] leading-tight rounded-lg px-2.5 py-2 whitespace-nowrap shadow-xl">
                              Restarts Google Messages bot:<br/>
                              • After 3 consecutive send-button failures<br/>
                              Checked every 5 minutes.
                            </span>
                          </span>
                        </label>
                        
                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-2.5 py-1.5 bg-slate-800/30 hover:bg-slate-800/80 border border-slate-700/50 rounded transition">
                          <input type="checkbox" className="accent-amber-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.silentMode ?? false} onChange={() => toggleSilentMode(d)} /> Silent Mode
                        </label>
                        
                        <label className="flex items-center gap-1.5 cursor-pointer text-[10px] text-slate-300 font-medium whitespace-nowrap px-2.5 py-1.5 bg-slate-800/30 hover:bg-slate-800/80 border border-slate-700/50 rounded transition">
                          <input type="checkbox" className="accent-red-500 m-0 cursor-pointer w-3.5 h-3.5" checked={d.callBlockEnabled ?? false} onChange={() => toggleCallBlock(d)} /> Call Block
                        </label>
                      </div>

                      <div className="flex flex-wrap items-center justify-end gap-1.5">
                        <button className="btn-primary flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-medium text-brand-300 bg-brand-500/10 border border-brand-500/20 hover:bg-brand-500/20 rounded shadow-sm transition" title="Matrix Setup Guide" onClick={() => setSetupMatrixForDevice(d)}><QrCode size={13} /> Matrix</button>
                        <button className={\`btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded border border-slate-700 text-[11px] font-medium shadow-sm transition \${d.autostartPinned ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30' : 'bg-slate-800 text-amber-400 hover:bg-slate-700'}\`} title="Pin Autostart (MIUI protection)" onClick={() => confirmAndSendCommand(d.id, 'PIN_AUTOSTART')}><ShieldCheck size={13} /> Autostart</button>
                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-emerald-500/20 border border-slate-700 text-emerald-400 text-[11px] font-medium shadow-sm transition" title="Push Messaging Agent APK Update" onClick={() => confirmAndSendCommand(d.id, 'UPDATE_APK')}><DownloadCloud size={13} /> Agent OTA</button>
                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-amber-500/20 border border-slate-700 text-amber-400 text-[11px] font-medium shadow-sm transition" title="Push Guardian APK Update" onClick={() => confirmAndSendCommand(d.id, 'UPDATE_GUARDIAN')}><DownloadCloud size={13} /> Guardian OTA</button>
                        
                        <div className="w-px h-6 bg-slate-700/50 mx-2 hidden sm:block"></div>

                        <button className="btn-secondary flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-800 hover:bg-slate-700 border border-slate-700 text-emerald-400 text-[11px] font-medium shadow-sm transition" title="Reconnect" onClick={() => confirmAndSendCommand(d.id, 'RECONNECT')}><RefreshCcw size={13} /> Reconnect</button>
                        <button className="btn-danger flex items-center gap-1.5 px-3 py-1.5 rounded bg-red-500/10 hover:bg-red-500/20 border border-red-500/20 text-red-400 text-[11px] font-medium shadow-sm transition" title="Reboot" onClick={() => confirmAndSendCommand(d.id, 'REBOOT')}><Power size={13} /> Reboot</button>
                        <div className="w-px h-6 bg-slate-700/50 mx-2 hidden sm:block"></div>
                        <button className="btn-secondary flex items-center justify-center p-2 rounded bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 shadow-sm transition" title="Edit" onClick={() => openEdit(d)}><Pencil size={13} /></button>
                        <button className="btn-danger flex items-center justify-center p-2 rounded bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 shadow-sm transition" title="Delete" onClick={() => setConfirmAction({ title: 'Delete Device', message: \`Delete \${d.name}?\`, onConfirm: () => deleteMut.mutate(d.id) })}><Trash2 size={13} /></button>
                      </div>
                    </div>
                  </td>
                </tr>`
);

fs.writeFileSync('c:/Dev/messaging-agent/admin-panel/src/pages/DevicesPage.tsx', code);
