import { useState, useEffect } from 'react';
import { Database, Save, DownloadCloud, AlertTriangle, Play, RefreshCw, Key, FolderOpen, ShieldAlert, Clock, Timer, Folder, ChevronRight, CheckCircle, ExternalLink, LogIn, Trash2 } from 'lucide-react';

export default function BackupRestorePage() {
  const [config, setConfig] = useState({ gdrivePath: '', serviceAccountJson: '', driveFolderId: '' });
  const [isConfigured, setIsConfigured] = useState(false);
  const [loadingConfig, setLoadingConfig] = useState(true);
  const [savingConfig, setSavingConfig] = useState(false);
  const [configError, setConfigError] = useState('');
  const [configSuccess, setConfigSuccess] = useState('');
  
  const [backups, setBackups] = useState<any[]>([]);
  const [loadingBackups, setLoadingBackups] = useState(false);
  
  const [logs, setLogs] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [processResult, setProcessResult] = useState<'success' | 'error' | null>(null);
  
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleHour, setScheduleHour] = useState(3);
  const [savingSchedule, setSavingSchedule] = useState(false);
  const [scheduleMsg, setScheduleMsg] = useState('');

  const [showBrowser, setShowBrowser] = useState(false);
  const [browseFolders, setBrowseFolders] = useState<{id: string, name: string}[]>([]);
  const [browseLoading, setBrowseLoading] = useState(false);
  const [browseError, setBrowseError] = useState('');
  const [breadcrumbs, setBreadcrumbs] = useState<{id: string, name: string}[]>([]);
  const [selectedFolder, setSelectedFolder] = useState<{id: string, name: string} | null>(null);

  const [hasOAuth, setHasOAuth] = useState(false);
  const [oauthClientId, setOauthClientId] = useState('');
  const [oauthClientSecret, setOauthClientSecret] = useState('');
  const [authUrl, setAuthUrl] = useState('');
  const [authStep, setAuthStep] = useState<'credentials' | 'authorize' | 'done'>('credentials');
  
  const fetchConfig = async () => {
    try {
      const res = await fetch('/api/backup/config');
      const data = await res.json();
      if (data.configured) {
        setIsConfigured(true);
        setConfig(prev => ({ ...prev, gdrivePath: data.gdrivePath, driveFolderId: data.driveFolderId || '' }));
        setHasOAuth(!!data.hasOAuth);
        if (data.hasOAuth) setAuthStep('done');
        fetchBackups();
      }
    } catch (e) {
      console.error('Failed to fetch backup config', e);
    } finally {
      setLoadingConfig(false);
    }
  };

  const fetchBackups = async () => {
    setLoadingBackups(true);
    try {
      const res = await fetch('/api/backup/list');
      const data = await res.json();
      if (data.files) setBackups(data.files);
    } catch (e) {
      console.error('Failed to fetch backups', e);
    } finally {
      setLoadingBackups(false);
    }
  };

  useEffect(() => {
    fetchConfig();
    fetchSchedule();
    
    // Connect to SSE for active backups
    const eventSource = new EventSource('/api/backup/stream');
    eventSource.onmessage = (e) => {
      const payload = JSON.parse(e.data);
      if (payload.sync) {
        setIsRunning(payload.isRunning);
        setLogs(payload.logs || []);
        if (payload.isRunning) setProcessResult(null);
      }
      if (payload.log) {
        setLogs(prev => [...prev, payload.log]);
      }
      if (payload.done !== undefined) {
        setIsRunning(false);
        setProcessResult(payload.success ? 'success' : 'error');
        fetchBackups(); // Refresh list after backup/restore
      }
    };
    return () => eventSource.close();
  }, []);

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingConfig(true);
    setConfigError('');
    setConfigSuccess('');
    try {
      const res = await fetch('/api/backup/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
      });
      const data = await res.json();
      if (res.ok) {
        setIsConfigured(true);
        if (data.testError) {
          setConfigError(`Saved, but connection test failed: ${data.testError}`);
        } else {
          setConfigSuccess(data.message || 'Configuration saved!');
          setShowBrowser(true);
          loadFolders();
        }
        fetchBackups();
      } else {
        setConfigError(data.error || 'Failed to save configuration');
      }
    } catch (err: any) {
      setConfigError(err.message);
    } finally {
      setSavingConfig(false);
    }
  };

  const loadFolders = async (folderId?: string) => {
    setBrowseLoading(true);
    setBrowseError('');
    try {
      const url = folderId ? `/api/backup/browse?folderId=${folderId}` : '/api/backup/browse';
      const res = await fetch(url);
      const data = await res.json();
      if (data.error) {
        setBrowseError(data.error);
        setBrowseFolders([]);
      } else {
        setBrowseFolders(data.folders || []);
      }
    } catch (e: any) {
      setBrowseError(e.message);
    } finally {
      setBrowseLoading(false);
    }
  };

  const navigateInto = (folder: {id: string, name: string}) => {
    setBreadcrumbs(prev => [...prev, folder]);
    loadFolders(folder.id);
  };

  const navigateBreadcrumb = (index: number) => {
    if (index < 0) {
      setBreadcrumbs([]);
      loadFolders();
    } else {
      const bc = breadcrumbs[index];
      setBreadcrumbs(prev => prev.slice(0, index + 1));
      loadFolders(bc.id);
    }
  };

  const selectFolder = async (folder: {id: string, name: string}) => {
    setSelectedFolder(folder);
    const fullPath = [...breadcrumbs.map(b => b.name), folder.name].join('/');
    setConfig(prev => ({ ...prev, driveFolderId: folder.id, gdrivePath: fullPath }));
    try {
      const res = await fetch('/api/backup/select-folder', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ folderId: folder.id, folderPath: fullPath })
      });
      const data = await res.json();
      if (res.ok) {
        setConfigSuccess(`Folder selected: ${fullPath}`);
        setConfigError('');
        setShowBrowser(false);
        fetchBackups();
      } else {
        setConfigError(data.error || 'Failed to save folder selection');
      }
    } catch(e: any) {
      setConfigError(e.message || 'Failed to save folder selection');
    }
  };

  const handleTriggerBackup = async () => {
    if (!confirm('Are you sure you want to trigger a manual backup of the production database now?')) return;
    try {
      await fetch('/api/backup/trigger', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ip: '10.10.10.193' }) // Production IP
      });
    } catch(e) {
      console.error(e);
    }
  };

  const fetchSchedule = async () => {
    try {
      const res = await fetch('/api/backup/schedule');
      const data = await res.json();
      setScheduleEnabled(data.enabled);
      setScheduleHour(data.hour);
    } catch(e) {
      console.error('Failed to fetch schedule', e);
    }
  };

  const handleSaveSchedule = async () => {
    setSavingSchedule(true);
    setScheduleMsg('');
    try {
      const res = await fetch('/api/backup/schedule', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: scheduleEnabled, hour: scheduleHour })
      });
      const data = await res.json();
      if (res.ok) {
        setScheduleMsg(data.message);
      } else {
        setScheduleMsg(data.error || 'Failed to save schedule');
      }
    } catch(e: any) {
      setScheduleMsg(e.message);
    } finally {
      setSavingSchedule(false);
    }
  };

  const handleTriggerRestore = async (filename: string) => {
    if (!confirm(`Are you absolutely sure you want to RESTORE ${filename}?\nThis will OVERWRITE the current production database and the app will experience downtime.`)) {
      return;
    }
    try {
      await fetch('/api/backup/restore', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ip: '10.10.10.193', filename })
      });
    } catch(e) {
      console.error(e);
    }
  };

  const handleDeleteBackup = async (filename: string) => {
    if (!confirm(`Are you sure you want to permanently delete the backup file: ${filename}?`)) {
      return;
    }
    try {
      const res = await fetch(`/api/backup/${encodeURIComponent(filename)}`, {
        method: 'DELETE'
      });
      if (res.ok) {
        fetchBackups();
      } else {
        const data = await res.json();
        alert(data.error || 'Failed to delete backup');
      }
    } catch(e: any) {
      alert(e.message || 'Failed to delete backup');
    }
  };

  if (loadingConfig) return <div className="p-8 text-slate-600 dark:text-slate-400">Loading backup configuration...</div>;

  return (
    <div className="p-8 max-w-6xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white flex items-center gap-3">
            <Database className="text-teal-400" />
            Backup & Restore
          </h1>
          <p className="text-slate-600 dark:text-slate-400 mt-1">Disaster recovery synchronization with Google Drive.</p>
        </div>
      </div>
      
      {!hasOAuth && (
        <div className="bg-blue-500/10 border border-blue-500/30 rounded-xl p-6">
          <h2 className="text-lg font-medium text-blue-300 flex items-center gap-2 mb-4">
            <Key size={18} /> Google Drive Setup
          </h2>
          <ol className="list-decimal list-inside text-sm text-blue-200/80 space-y-2 mb-4">
            <li>Go to the <a href="https://console.cloud.google.com/" target="_blank" rel="noreferrer" className="text-blue-400 underline">Google Cloud Console</a>.</li>
            <li>Create a new Project (or select an existing one).</li>
            <li>Enable the <strong>Google Drive API</strong> in "APIs & Services" → "Library".</li>
            <li>Go to <strong>"APIs & Services" → "OAuth consent screen"</strong>. Set type to "External", fill in app name, and add your email. Under "Scopes", add <code>.../auth/drive</code>. Under "Test users", add <strong>your Gmail address</strong>. Publish or keep in testing mode.</li>
            <li>Go to <strong>"Credentials" → "Create Credentials" → "OAuth client ID"</strong>. Set type to <strong>"Web application"</strong>.</li>
            <li>Under <strong>Authorized redirect URIs</strong>, add exactly: <code className="bg-slate-50 dark:bg-slate-800 px-1 py-0.5 rounded text-brand-300">{window.location.origin}/api/backup/oauth-callback</code></li>
            <li>Copy the <strong>Client ID</strong> and <strong>Client Secret</strong> and paste them below.</li>
          </ol>

          {authStep === 'credentials' && (
            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">OAuth Client ID</label>
                <input
                  type="text"
                  value={oauthClientId}
                  onChange={e => setOauthClientId(e.target.value)}
                  placeholder="123456789-abc.apps.googleusercontent.com"
                  className="w-full bg-white dark:bg-slate-900/50 border border-slate-300 dark:border-white/10 rounded-lg px-3 py-2 text-sm text-slate-900 dark:text-white focus:border-brand-500 outline-none font-mono"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">OAuth Client Secret</label>
                <input
                  type="password"
                  value={oauthClientSecret}
                  onChange={e => setOauthClientSecret(e.target.value)}
                  placeholder="GOCSPX-..."
                  className="w-full bg-white dark:bg-slate-900/50 border border-slate-300 dark:border-white/10 rounded-lg px-3 py-2 text-sm text-slate-900 dark:text-white focus:border-brand-500 outline-none font-mono"
                />
              </div>
              {configError && <div className="text-red-400 text-xs p-2 bg-red-400/10 rounded border border-red-400/20">{configError}</div>}
              <button
                onClick={async () => {
                  if (!oauthClientId || !oauthClientSecret) { setConfigError('Both Client ID and Client Secret are required'); return; }
                  setConfigError('');
                  setSavingConfig(true);
                  try {
                    const res = await fetch('/api/backup/auth-url', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ 
                        clientId: oauthClientId, 
                        clientSecret: oauthClientSecret,
                        redirectUri: window.location.origin + '/api/backup/oauth-callback'
                      })
                    });
                    const data = await res.json();
                    if (data.authUrl) {
                      setAuthUrl(data.authUrl);
                      setAuthStep('authorize');
                    } else {
                      setConfigError(data.error || 'Failed to generate auth URL');
                    }
                  } catch(e: any) { setConfigError(e.message); } finally { setSavingConfig(false); }
                }}
                disabled={savingConfig}
                className="w-full bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white rounded-lg px-4 py-2.5 text-sm font-medium transition flex items-center justify-center gap-2"
              >
                <LogIn size={16} /> Generate Authorization Link
              </button>
            </div>
          )}

          {authStep === 'authorize' && (
            <div className="space-y-3">
              <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3">
                <p className="text-amber-300 text-sm font-medium mb-2">Step 1: Click the link below to authorize access:</p>
                <a href={authUrl} target="_blank" rel="noreferrer" className="text-blue-400 underline text-xs break-all flex items-center gap-1 mb-3">
                  <ExternalLink size={14} className="shrink-0" /> Open Google Authorization Page
                </a>
                <p className="text-amber-200/80 text-xs">Step 2: Sign in with your Google account. The window will close automatically upon success.</p>
                <p className="text-amber-200/80 text-xs mt-2">Step 3: Click the button below to verify authorization.</p>
              </div>
              {configError && <div className="text-red-400 text-xs p-2 bg-red-400/10 rounded border border-red-400/20">{configError}</div>}
              {configSuccess && <div className="text-emerald-400 text-xs p-2 bg-emerald-400/10 rounded border border-emerald-400/20">{configSuccess}</div>}
              <button
                onClick={async () => {
                  setConfigError('');
                  setSavingConfig(true);
                  try {
                    const res = await fetch('/api/backup/config');
                    const data = await res.json();
                    if (data.configured && data.hasOAuth) {
                      setConfigSuccess('Authorization verified successfully!');
                      setHasOAuth(true);
                      setIsConfigured(true);
                      setAuthStep('done');
                      setShowBrowser(true);
                      loadFolders();
                    } else {
                      setConfigError('Authorization not yet completed. Did you finish the Google flow in the new tab?');
                    }
                  } catch(e: any) { setConfigError(e.message); } finally { setSavingConfig(false); }
                }}
                disabled={savingConfig}
                className="w-full bg-emerald-600 hover:bg-emerald-500 text-slate-900 dark:text-white rounded-lg px-4 py-2.5 text-sm font-medium transition flex items-center justify-center gap-2"
              >
                {savingConfig ? <RefreshCw className="animate-spin" size={16} /> : <CheckCircle size={16} />}
                Verify Authorization
              </button>
            </div>
          )}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Left Column: Config & Manual Trigger */}
        <div className="space-y-6 lg:col-span-1">
          <div className="bg-slate-50 dark:bg-slate-800/50 backdrop-blur-sm border border-slate-300 dark:border-white/10 rounded-xl p-6">
            <h2 className="text-slate-900 dark:text-white font-medium mb-4 flex items-center gap-2">
              <FolderOpen size={18} className="text-slate-600 dark:text-slate-400" /> Google Drive Configuration
            </h2>
            <form onSubmit={handleSaveConfig} className="space-y-4">
              {config.driveFolderId && (
                <div className="flex items-center gap-2 p-2 bg-emerald-500/10 border border-emerald-500/20 rounded-lg">
                  <CheckCircle size={16} className="text-emerald-400 shrink-0" />
                  <div className="min-w-0">
                    <p className="text-xs text-emerald-400 font-medium truncate">{config.gdrivePath || 'Selected folder'}</p>
                    <p className="text-[10px] text-slate-700 dark:text-slate-500 font-mono truncate">{config.driveFolderId}</p>
                  </div>
                  <button type="button" onClick={() => { setShowBrowser(true); loadFolders(); }} className="text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white text-xs ml-auto shrink-0">Change</button>
                </div>
              )}

              {!config.driveFolderId && isConfigured && (
                <button
                  type="button"
                  onClick={() => { setShowBrowser(true); loadFolders(); }}
                  className="w-full bg-blue-600/20 hover:bg-blue-600/30 text-blue-400 border border-blue-600/50 rounded-lg px-4 py-2.5 text-sm font-medium transition flex items-center justify-center gap-2"
                >
                  <FolderOpen size={16} /> Browse Google Drive Folders
                </button>
              )}
              
              {configError && <div className="text-red-400 text-xs p-2 bg-red-400/10 rounded border border-red-400/20">{configError}</div>}
              {configSuccess && <div className="text-emerald-400 text-xs p-2 bg-emerald-400/10 rounded border border-emerald-400/20">{configSuccess}</div>}

              {hasOAuth && (
                <div className="flex items-center gap-2 text-xs text-emerald-400/80">
                  <CheckCircle size={14} /> OAuth2 Connected
                </div>
              )}
            </form>
          </div>

          <div className="bg-slate-50 dark:bg-slate-800/50 backdrop-blur-sm border border-slate-300 dark:border-white/10 rounded-xl p-6">
            <h2 className="text-slate-900 dark:text-white font-medium mb-4 flex items-center gap-2">
              <Timer size={18} className="text-amber-600 dark:text-amber-400" /> Daily Auto-Backup
            </h2>
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <span className="text-sm text-slate-700 dark:text-slate-300">Enable Daily Backup</span>
                  <p className="text-xs text-slate-700 dark:text-slate-500 mt-0.5">Automatically backs up to Google Drive every day</p>
                </div>
                <button
                  onClick={() => setScheduleEnabled(!scheduleEnabled)}
                  disabled={!isConfigured}
                  className={`relative w-12 h-6 rounded-full transition-colors duration-200 ${scheduleEnabled ? 'bg-emerald-500' : 'bg-slate-600'} ${!isConfigured ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer'}`}
                >
                  <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform duration-200 ${scheduleEnabled ? 'translate-x-6' : 'translate-x-0'}`} />
                </button>
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Backup Time (UTC)</label>
                <select
                  value={scheduleHour}
                  onChange={e => setScheduleHour(parseInt(e.target.value))}
                  disabled={!isConfigured}
                  className="w-full bg-white dark:bg-slate-900/50 border border-slate-300 dark:border-white/10 rounded-lg px-3 py-2 text-sm text-slate-900 dark:text-white focus:border-brand-500 outline-none disabled:opacity-40"
                >
                  {Array.from({ length: 24 }, (_, i) => (
                    <option key={i} value={i}>{String(i).padStart(2, '0')}:00 UTC</option>
                  ))}
                </select>
              </div>

              <button
                onClick={handleSaveSchedule}
                disabled={!isConfigured || savingSchedule}
                className="w-full bg-amber-600/20 hover:bg-amber-600/30 text-amber-600 dark:text-amber-400 border border-amber-600/50 rounded-lg px-4 py-2 text-sm font-medium transition flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {savingSchedule ? <RefreshCw className="animate-spin" size={16} /> : <Clock size={16} />}
                Save Schedule
              </button>
              {scheduleMsg && <p className="text-xs text-emerald-400">{scheduleMsg}</p>}
            </div>
          </div>

          <div className="bg-slate-50 dark:bg-slate-800/50 backdrop-blur-sm border border-slate-300 dark:border-white/10 rounded-xl p-6">
             <h2 className="text-slate-900 dark:text-white font-medium mb-4 flex items-center gap-2">
              <Play size={18} className="text-emerald-400" /> Manual Actions
            </h2>
            <button 
              onClick={handleTriggerBackup}
              disabled={!isConfigured || isRunning}
              className="w-full bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-400 border border-emerald-600/50 rounded-lg px-4 py-3 text-sm font-medium transition flex flex-col items-center justify-center gap-1 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <DownloadCloud size={20} />
              Trigger Live Backup
            </button>
          </div>
        </div>

        {/* Right Column: Status & Restore List */}
        <div className="lg:col-span-2 space-y-6">

          {showBrowser && (
            <div className="bg-white dark:bg-slate-900 border border-blue-500/30 rounded-xl overflow-hidden shadow-xl">
              <div className="bg-blue-500/10 px-4 py-3 border-b border-blue-500/20 flex items-center justify-between">
                <span className="text-blue-400 font-medium text-sm flex items-center gap-2">
                  <FolderOpen size={16} /> Select Backup Folder
                </span>
                <button onClick={() => setShowBrowser(false)} className="text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white text-xs">Close</button>
              </div>
              
              {/* Breadcrumbs */}
              <div className="px-4 py-2 bg-slate-100 dark:bg-black/30 flex items-center gap-1 text-xs overflow-x-auto">
                <button onClick={() => navigateBreadcrumb(-1)} className="text-blue-400 hover:text-slate-900 dark:text-white transition shrink-0">
                  Shared with me
                </button>
                {breadcrumbs.map((bc, i) => (
                  <span key={bc.id} className="flex items-center gap-1 shrink-0">
                    <ChevronRight size={12} className="text-slate-600" />
                    <button onClick={() => navigateBreadcrumb(i)} className="text-blue-400 hover:text-slate-900 dark:text-white transition">
                      {bc.name}
                    </button>
                  </span>
                ))}
              </div>

              {/* Folder list */}
              <div className="max-h-64 overflow-y-auto">
                {browseLoading ? (
                  <div className="flex items-center justify-center py-8">
                    <RefreshCw size={20} className="animate-spin text-blue-400" />
                  </div>
                ) : browseError ? (
                  <div className="p-4 text-red-400 text-sm">{browseError}</div>
                ) : browseFolders.length === 0 ? (
                  <div className="p-6 text-center text-slate-700 dark:text-slate-500 text-sm">
                    {breadcrumbs.length === 0 
                      ? 'No folders shared with the Service Account. Share a folder first.' 
                      : 'This folder is empty. You can select it as your backup destination.'}
                    {breadcrumbs.length > 0 && (
                      <button
                        onClick={() => selectFolder(breadcrumbs[breadcrumbs.length - 1])}
                        className="mt-3 bg-emerald-600 hover:bg-emerald-500 text-slate-900 dark:text-white rounded-lg px-4 py-2 text-sm font-medium transition flex items-center justify-center gap-2 mx-auto"
                      >
                        <CheckCircle size={16} /> Use Current Folder
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="divide-y divide-white/5">
                    {browseFolders.map(folder => (
                      <div key={folder.id} className="flex items-center px-4 py-2.5 hover:bg-white/[0.03] transition group">
                        <Folder size={18} className="text-amber-600 dark:text-amber-400/70 shrink-0 mr-3" />
                        <span className="text-sm text-slate-200 flex-1 truncate">{folder.name}</span>
                        <div className="flex gap-2 opacity-0 group-hover:opacity-100 transition shrink-0">
                          <button
                            onClick={() => navigateInto(folder)}
                            className="text-xs text-blue-400 hover:text-slate-900 dark:text-white border border-blue-500/30 rounded px-2 py-1 transition"
                          >
                            Open
                          </button>
                          <button
                            onClick={() => selectFolder(folder)}
                            className="text-xs text-emerald-400 hover:text-slate-900 dark:text-white bg-emerald-600/20 border border-emerald-500/30 rounded px-2 py-1 transition"
                          >
                            Select
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
          
          {(isRunning || logs.length > 0) && (
            <div className={`bg-white dark:bg-slate-900 border rounded-xl overflow-hidden shadow-xl ${
              isRunning ? 'border-blue-500/30' : 
              processResult === 'success' ? 'border-emerald-500/30' : 
              'border-red-500/30'
            }`}>
              <div className={`px-4 py-3 border-b flex items-center justify-between ${
                isRunning ? 'bg-blue-500/10 border-blue-500/20' : 
                processResult === 'success' ? 'bg-emerald-500/10 border-emerald-500/20' : 
                'bg-red-500/10 border-red-500/20'
              }`}>
                <span className={`font-medium text-sm flex items-center gap-2 ${
                  isRunning ? 'text-blue-400' : 
                  processResult === 'success' ? 'text-emerald-400' : 
                  'text-red-400'
                }`}>
                  {isRunning ? <RefreshCw size={16} className="animate-spin" /> : 
                   processResult === 'success' ? <CheckCircle size={16} /> : 
                   <AlertTriangle size={16} />} 
                  {isRunning ? 'Process Running...' : 
                   processResult === 'success' ? 'Process Completed Successfully' : 
                   'Process Failed'}
                </span>
                {!isRunning && (
                  <button onClick={() => { setLogs([]); setProcessResult(null); }} className="text-xs text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white bg-slate-50 dark:bg-slate-800 hover:bg-slate-100 dark:bg-slate-700 border border-slate-700 rounded px-3 py-1.5 transition">Dismiss</button>
                )}
              </div>
              <div className="p-4 h-64 overflow-y-auto font-mono text-xs text-slate-700 dark:text-slate-300 space-y-1 bg-slate-900/20 dark:bg-black/50">
                {logs.map((log, i) => (
                  <div key={i} className={log.toLowerCase().includes('fail') || log.toLowerCase().includes('error') ? 'text-red-400' : ''}>
                    {log}
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="bg-slate-50 dark:bg-slate-800/50 backdrop-blur-sm border border-slate-300 dark:border-white/10 rounded-xl overflow-hidden flex flex-col">
            <div className="p-5 border-b border-slate-300 dark:border-white/10 flex items-center justify-between bg-white/[0.02]">
              <h3 className="text-slate-900 dark:text-white font-medium flex items-center gap-2">
                <Database size={18} className="text-slate-600 dark:text-slate-400" /> Available Restores
              </h3>
              <button onClick={fetchBackups} disabled={loadingBackups || !isConfigured} className="text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white transition">
                <RefreshCw size={16} className={loadingBackups ? 'animate-spin' : ''} />
              </button>
            </div>
            
            <div className="overflow-x-auto min-h-[300px]">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-slate-300 dark:border-white/10 bg-slate-100 dark:bg-black/20 text-xs font-medium text-slate-600 dark:text-slate-400 uppercase tracking-wider">
                    <th className="px-5 py-4">Filename</th>
                    <th className="px-5 py-4">Size</th>
                    <th className="px-5 py-4">Date</th>
                    <th className="px-5 py-4 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="text-sm divide-y divide-white/5">
                  {!isConfigured ? (
                    <tr><td colSpan={4} className="px-5 py-8 text-center text-slate-700 dark:text-slate-500">Configure Service Account to view backups.</td></tr>
                  ) : backups.length === 0 ? (
                    <tr><td colSpan={4} className="px-5 py-8 text-center text-slate-700 dark:text-slate-500">No backups found in Google Drive folder.</td></tr>
                  ) : (
                    backups.map((file) => (
                      <tr key={file.ID} className="hover:bg-white/[0.02] transition">
                        <td className="px-5 py-4 text-slate-200 font-mono text-xs">{file.Name}</td>
                        <td className="px-5 py-4 text-slate-600 dark:text-slate-400">{(file.Size / 1024 / 1024).toFixed(2)} MB</td>
                        <td className="px-5 py-4 text-slate-600 dark:text-slate-400">{new Date(file.ModTime).toLocaleString()}</td>
                        <td className="px-5 py-4 text-right">
                          <div className="flex items-center justify-end gap-2">
                            <button
                              onClick={() => handleTriggerRestore(file.Name)}
                              disabled={isRunning}
                              className="bg-red-500/10 hover:bg-red-500/20 text-red-400 border border-red-500/20 rounded px-3 py-1.5 text-xs font-medium transition flex items-center gap-1.5 disabled:opacity-50"
                            >
                              <ShieldAlert size={14} /> Restore Now
                            </button>
                            <button
                              onClick={() => handleDeleteBackup(file.Name)}
                              disabled={isRunning}
                              className="bg-slate-100 dark:bg-slate-700/50 hover:bg-red-900/40 text-slate-600 dark:text-slate-400 hover:text-red-400 border border-slate-600 hover:border-red-500/30 rounded px-2.5 py-1.5 transition flex items-center justify-center disabled:opacity-50"
                              title="Delete Backup"
                            >
                              <Trash2 size={14} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
