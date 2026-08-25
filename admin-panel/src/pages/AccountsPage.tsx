import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client';
import { SmppClientFormFields } from '../components/SmppClientFormFields';
import { Plus, Edit2, Trash2, Search, Building2, UserCircle, Server, ArrowUpRight } from 'lucide-react';

interface Username {
  id?: number;
  username: string;
  whitelistedIps: string;
  enforceIpWhitelist: boolean;
  smppEnabled: boolean;
  apiEnabled: boolean;
  webEnabled: boolean;
  banned?: boolean;
}

interface Account {
  id: number;
  name: string;
  type: string;
  companyName: string;
  vatNumber: string;
  address: string;
  email: string;
  contactPerson: string;
  usernames: Username[];
  createdAt: string;
}

export default function AccountsPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [smppClients, setSmppClients] = useState<any[]>([]);
  const [smscSuppliers, setSmscSuppliers] = useState<any[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);
  
  // Modals state
  const [showAccountModal, setShowAccountModal] = useState(false);
  const [editingAccountParams, setEditingAccountParams] = useState<Partial<Account> | null>(null);
  
  const [showUsernameModal, setShowUsernameModal] = useState(false);
  const [editingUsername, setEditingUsername] = useState<Partial<Username> | null>(null);
  const [editingUsernameIndex, setEditingUsernameIndex] = useState<number | null>(null);

  // SMPP Flow state
  const [showSmppModal, setShowSmppModal] = useState(false);
  const [pendingSmppConfigs, setPendingSmppConfigs] = useState<{usernameRef: Username, smppData: any, fullPayload: any}[]>([]);
  const [isSaving, setIsSaving] = useState(false);

  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    fetchAccounts();
  }, []);

  const fetchAccounts = async (preserveSelection = true) => {
    try {
      const [accRes, smppRes, smscRes] = await Promise.all([
        api.get('/accounts'),
        api.get('/smpp/clients'),
        api.get('/admin/smsc-suppliers')
      ]);
      setAccounts(accRes.data);
      setSmppClients(smppRes.data);
      setSmscSuppliers(smscRes.data);
      if (!preserveSelection) {
        setSelectedAccountId(null);
      }
    } catch (error) {
      console.error('Failed to fetch accounts or smpp clients:', error);
    }
  };

  const selectedAccount = accounts.find(a => a.id === selectedAccountId);

  // --------------------------------------------------------
  // ACCOUNT CRUD
  // --------------------------------------------------------

  const handleDeleteAccount = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this account?')) return;
    try {
      await api.delete(`/accounts/${id}`);
      fetchAccounts(false);
    } catch (error) {
      console.error('Failed to delete account:', error);
    }
  };

  const handleSaveAccountBasic = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    const payload = {
      name: formData.get('name') as string,
      type: formData.get('type') as string,
      companyName: formData.get('companyName') as string,
      vatNumber: formData.get('vatNumber') as string,
      address: formData.get('address') as string,
      email: formData.get('email') as string,
      contactPerson: formData.get('contactPerson') as string,
      usernames: editingAccountParams?.id ? (selectedAccount?.usernames || []) : [],
    };

    setIsSaving(true);
    try {
      if (editingAccountParams?.id) {
        await api.put(`/accounts/${editingAccountParams.id}`, payload);
      } else {
        const res = await api.post('/accounts', payload);
        setSelectedAccountId(res.data.id);
      }
      setShowAccountModal(false);
      setEditingAccountParams(null);
      await fetchAccounts(true);
    } catch (error: any) {
      alert('Failed to save account: ' + (error.response?.data?.message || error.message));
    } finally {
      setIsSaving(false);
    }
  };

  // --------------------------------------------------------
  // USERNAME CRUD & SMPP FLOW
  // --------------------------------------------------------

  const handleSaveUsername = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!selectedAccount) return;

    const newUsername: Username = {
      id: editingUsername?.id,
      username: editingUsername?.username || '',
      whitelistedIps: editingUsername?.whitelistedIps || '',
      enforceIpWhitelist: !!editingUsername?.enforceIpWhitelist,
      smppEnabled: !!editingUsername?.smppEnabled,
      apiEnabled: !!editingUsername?.apiEnabled,
      webEnabled: !!editingUsername?.webEnabled,
      banned: !!editingUsername?.banned
    };

    const updatedUsernames = [...selectedAccount.usernames];
    if (editingUsernameIndex !== null) {
      updatedUsernames[editingUsernameIndex] = newUsername;
    } else {
      updatedUsernames.push(newUsername);
    }

    const payload = {
      name: selectedAccount.name,
      type: selectedAccount.type,
      companyName: selectedAccount.companyName,
      vatNumber: selectedAccount.vatNumber,
      address: selectedAccount.address,
      email: selectedAccount.email,
      contactPerson: selectedAccount.contactPerson,
      usernames: updatedUsernames
    };

    // Check if we need to configure SMPP
    const needsSmpp = newUsername.smppEnabled && (!newUsername.id || !smppClients.find(c => c.usernameId === newUsername.id));
    
    if (needsSmpp) {
       setPendingSmppConfigs([{
           usernameRef: newUsername,
           smppData: { name: newUsername.username + ' SMPP', systemId: newUsername.username, active: true, priority: 2, password: '' },
           fullPayload: payload
       }]);
       setShowUsernameModal(false);
       setShowSmppModal(true);
       return;
    }

    // Otherwise save immediately
    await executeAccountPayloadSave(payload, selectedAccount.id, []);
  };

  const handleDeleteUsername = async (index: number) => {
    if (!selectedAccount) return;
    if (!window.confirm('Are you sure you want to delete this username?')) return;
    
    const updatedUsernames = selectedAccount.usernames.filter((_, i) => i !== index);
    const payload = {
      name: selectedAccount.name,
      type: selectedAccount.type,
      companyName: selectedAccount.companyName,
      vatNumber: selectedAccount.vatNumber,
      address: selectedAccount.address,
      email: selectedAccount.email,
      contactPerson: selectedAccount.contactPerson,
      usernames: updatedUsernames
    };
    
    await executeAccountPayloadSave(payload, selectedAccount.id, []);
  };

  const executeAccountPayloadSave = async (payload: any, accountId: number, smppConfigsToCreate: any[]) => {
    setIsSaving(true);
    try {
      const res = await api.put(`/accounts/${accountId}`, payload);
      const savedAccount = res.data;

      // Handle SMPP Client creation sequentially if requested
      if (smppConfigsToCreate.length > 0) {
        for (const config of smppConfigsToCreate) {
           const savedUsername = savedAccount.usernames.find((u: any) => u.username === config.usernameRef.username);
           if (savedUsername) {
              await api.post('/smpp/clients', {
                  ...config.smppData,
                  usernameId: savedUsername.id
              });
           }
        }
      }

      setShowUsernameModal(false);
      setShowSmppModal(false);
      setEditingUsername(null);
      setEditingUsernameIndex(null);
      setPendingSmppConfigs([]);
      await fetchAccounts(true);
    } catch (error: any) {
      alert('Failed to save data: ' + (error.response?.data?.message || error.message));
    } finally {
      setIsSaving(false);
    }
  };

  // --------------------------------------------------------
  // RENDER
  // --------------------------------------------------------

  const filteredAccounts = accounts.filter(a => a.name.toLowerCase().includes(searchQuery.toLowerCase()) || a.companyName?.toLowerCase().includes(searchQuery.toLowerCase()));

  return (
    <div className="p-8 h-[calc(100vh-64px)] flex flex-col relative overflow-hidden">
      <div className="flex justify-between items-center mb-6 shrink-0">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-1">Accounts Management</h1>
          <p className="text-slate-600 dark:text-slate-400 text-sm">Manage billing accounts, customer details, and routing channels</p>
        </div>
        <button
          onClick={() => { setEditingAccountParams({}); setShowAccountModal(true); }}
          className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-4 py-2 rounded-lg text-sm font-medium transition flex items-center gap-2"
        >
          <Plus size={16} /> Add Account
        </button>
      </div>

      <div className="flex gap-6 flex-1 min-h-0">
        {/* Left Sidebar (Master) */}
        <div className="w-1/3 max-w-sm bg-slate-100 dark:bg-[#1a1a2e] border border-slate-200 dark:border-white/[0.05] rounded-xl flex flex-col overflow-hidden shadow-sm">
          <div className="p-4 border-b border-slate-200 dark:border-white/[0.05] bg-white dark:bg-[#12121f]">
             <div className="relative">
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-700 dark:text-slate-500" />
                <input 
                   type="text" 
                   placeholder="Search accounts..." 
                   className="w-full bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-lg pl-9 pr-4 py-2 text-sm text-slate-900 dark:text-white focus:outline-none focus:border-brand-500 transition"
                   value={searchQuery}
                   onChange={e => setSearchQuery(e.target.value)}
                />
             </div>
          </div>
          <div className="overflow-y-auto flex-1 p-2 space-y-1">
             {filteredAccounts.map(acc => (
                <button 
                  key={acc.id}
                  onClick={() => setSelectedAccountId(acc.id)} 
                  className={`w-full text-left px-4 py-3 rounded-lg border flex flex-col gap-1 transition ${
                    selectedAccountId === acc.id 
                    ? 'bg-brand-600/20 border-brand-500/50' 
                    : 'bg-transparent border-transparent hover:bg-slate-200/50 dark:bg-white/5'
                  }`}
                >
                   <div className="flex justify-between items-center w-full">
                      <span className="font-semibold text-slate-900 dark:text-white text-sm">{acc.name}</span>
                      <span className={`px-1.5 py-0.5 inline-flex text-[10px] leading-4 font-semibold rounded ${
                        acc.type === 'CUSTOMER' ? 'bg-green-500/10 text-green-400 border border-green-500/20' :
                        acc.type === 'SUPPLIER' ? 'bg-blue-500/10 text-blue-400 border border-blue-500/20' :
                        'bg-purple-500/10 text-purple-400 border border-purple-500/20'
                      }`}>
                        {acc.type}
                      </span>
                   </div>
                   <div className="text-xs text-slate-600 dark:text-slate-400 truncate">
                      {acc.companyName || 'No Company Name'}
                   </div>
                </button>
             ))}
             {filteredAccounts.length === 0 && (
                <div className="p-4 text-center text-slate-700 dark:text-slate-500 text-sm">No accounts found.</div>
             )}
          </div>
        </div>

        {/* Right Content Area (Detail) */}
        <div className="flex-1 flex flex-col gap-6 min-h-0">
           {selectedAccount ? (
              <>
                 {/* Top Frame: Account Details */}
                 <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-200 dark:border-white/[0.05] rounded-xl flex flex-col shrink-0 shadow-sm overflow-hidden">
                    <div className="px-6 py-4 border-b border-slate-200 dark:border-white/[0.05] bg-white dark:bg-[#12121f] flex justify-between items-center">
                       <h2 className="text-lg font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                          <Building2 size={18} className="text-brand-400" />
                          Account Information
                       </h2>
                       <div className="flex items-center gap-2">
                          <button onClick={() => { setEditingAccountParams(selectedAccount); setShowAccountModal(true); }} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white hover:bg-slate-200 dark:bg-white/10 rounded transition" title="Edit Account">
                             <Edit2 size={16} />
                          </button>
                          <button onClick={() => handleDeleteAccount(selectedAccount.id)} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete Account">
                             <Trash2 size={16} />
                          </button>
                       </div>
                    </div>
                    <div className="p-6 grid grid-cols-2 md:grid-cols-3 gap-6">
                       <div>
                          <p className="text-xs font-medium text-slate-700 dark:text-slate-500 mb-1">Company Name</p>
                          <p className="text-sm text-slate-900 dark:text-white font-medium">{selectedAccount.companyName || '-'}</p>
                       </div>
                       <div>
                          <p className="text-xs font-medium text-slate-700 dark:text-slate-500 mb-1">VAT Number</p>
                          <p className="text-sm text-slate-700 dark:text-slate-300 font-mono">{selectedAccount.vatNumber || '-'}</p>
                       </div>
                       <div>
                          <p className="text-xs font-medium text-slate-700 dark:text-slate-500 mb-1">Email</p>
                          <p className="text-sm text-slate-700 dark:text-slate-300">{selectedAccount.email || '-'}</p>
                       </div>
                       <div className="col-span-2">
                          <p className="text-xs font-medium text-slate-700 dark:text-slate-500 mb-1">Address</p>
                          <p className="text-sm text-slate-700 dark:text-slate-300">{selectedAccount.address || '-'}</p>
                       </div>
                       <div>
                          <p className="text-xs font-medium text-slate-700 dark:text-slate-500 mb-1">Contact Person</p>
                          <p className="text-sm text-slate-700 dark:text-slate-300">{selectedAccount.contactPerson || '-'}</p>
                       </div>
                    </div>
                 </div>

                 {/* Bottom Frame: Usernames */}
                 <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-200 dark:border-white/[0.05] rounded-xl flex flex-col flex-1 min-h-0 shadow-sm overflow-hidden">
                    <div className="px-6 py-4 border-b border-slate-200 dark:border-white/[0.05] bg-white dark:bg-[#12121f] flex justify-between items-center shrink-0">
                       <h2 className="text-lg font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                          <UserCircle size={18} className="text-brand-400" />
                          Usernames & Routing
                       </h2>
                       <button onClick={() => { 
                          setEditingUsername({ username: 'user_' + (selectedAccount.usernames.length + 1), smppEnabled: true });
                          setEditingUsernameIndex(null); 
                          setShowUsernameModal(true); 
                       }} className="bg-brand-600 hover:bg-brand-500 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition flex items-center gap-1">
                          <Plus size={14} /> Add Username
                       </button>
                    </div>
                    <div className="overflow-y-auto flex-1 p-0">
                       <table className="w-full text-left text-sm text-slate-700 dark:text-slate-300">
                         <thead className="bg-white dark:bg-[#12121f]/50 text-slate-600 dark:text-slate-400 border-b border-slate-200 dark:border-white/[0.05] sticky top-0">
                           <tr>
                             <th className="px-6 py-3 font-medium">Username</th>
                             <th className="px-6 py-3 font-medium">Channels</th>
                             <th className="px-6 py-3 font-medium">Security</th>
                             <th className="px-6 py-3 font-medium text-right">Actions</th>
                           </tr>
                         </thead>
                         <tbody className="divide-y divide-white/[0.05]">
                           {selectedAccount.usernames.map((u, idx) => (
                             <tr key={idx} className="hover:bg-white/[0.02] transition">
                               <td className="px-6 py-4 text-slate-900 dark:text-white font-medium">{u.username}</td>
                               <td className="px-6 py-4">
                                  <div className="flex gap-2">
                                     <span className={`px-2 py-1 rounded text-[10px] uppercase font-bold tracking-wider ${u.smppEnabled ? 'bg-blue-500/20 text-blue-400' : 'bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-500'}`}>SMPP</span>
                                     <span className={`px-2 py-1 rounded text-[10px] uppercase font-bold tracking-wider ${u.apiEnabled ? 'bg-purple-500/20 text-purple-400' : 'bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-500'}`}>API</span>
                                     <span className={`px-2 py-1 rounded text-[10px] uppercase font-bold tracking-wider ${u.webEnabled ? 'bg-emerald-500/20 text-emerald-400' : 'bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-500'}`}>WEB</span>
                                  </div>
                               </td>
                               <td className="px-6 py-4">
                                  <div className="flex flex-col gap-1">
                                    <span className="text-xs text-slate-600 dark:text-slate-400">IP Lock: {u.enforceIpWhitelist ? <span className="text-green-400">ON</span> : 'OFF'}</span>
                                    {u.banned && <span className="inline-block mt-1 px-2 py-0.5 bg-red-500/20 border border-red-500/50 text-red-500 text-[10px] uppercase font-bold rounded-sm w-fit">BANNED</span>}
                                    {u.whitelistedIps && <span className="text-[10px] text-slate-700 dark:text-slate-500 font-mono truncate max-w-[150px]" title={u.whitelistedIps}>{u.whitelistedIps}</span>}
                                  </div>
                               </td>
                               <td className="px-6 py-4 text-right">
                                  <button onClick={() => { setEditingUsername(u); setEditingUsernameIndex(idx); setShowUsernameModal(true); }} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white hover:bg-slate-200 dark:bg-white/10 rounded transition mr-1" title="Edit Username"><Edit2 size={15} /></button>
                                  <button onClick={() => handleDeleteUsername(idx)} className="p-1.5 text-slate-600 dark:text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition" title="Delete Username"><Trash2 size={15} /></button>
                               </td>
                             </tr>
                           ))}
                           {selectedAccount.usernames.length === 0 && (
                             <tr><td colSpan={4} className="px-6 py-12 text-center text-slate-700 dark:text-slate-500">No usernames configured yet.</td></tr>
                           )}
                         </tbody>
                       </table>
                    </div>
                 </div>

                 {/* Third Frame: SMSC Suppliers (Only for SUPPLIER or BILATERAL) */}
                 {(selectedAccount.type === 'SUPPLIER' || selectedAccount.type === 'BILATERAL') && (
                 <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-200 dark:border-white/[0.05] rounded-xl flex flex-col flex-1 min-h-0 shadow-sm overflow-hidden">
                    <div className="px-6 py-4 border-b border-slate-200 dark:border-white/[0.05] bg-white dark:bg-[#12121f] flex justify-between items-center shrink-0">
                       <h2 className="text-lg font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                          <Server size={18} className="text-blue-400" />
                          SMSC Supplier Records
                       </h2>
                       <Link to="/smscs" className="text-xs text-brand-400 hover:text-brand-300 font-medium flex items-center gap-1">
                          Manage SMSCs <ArrowUpRight size={14} />
                       </Link>
                    </div>
                    <div className="overflow-y-auto flex-1 p-0">
                       <table className="w-full text-left text-sm text-slate-700 dark:text-slate-300">
                         <thead className="bg-white dark:bg-[#12121f]/50 text-slate-600 dark:text-slate-400 border-b border-slate-200 dark:border-white/[0.05] sticky top-0">
                           <tr>
                             <th className="px-6 py-3 font-medium">Name</th>
                             <th className="px-6 py-3 font-medium">System ID</th>
                             <th className="px-6 py-3 font-medium">Host</th>
                             <th className="px-6 py-3 font-medium">Status</th>
                           </tr>
                         </thead>
                         <tbody className="divide-y divide-white/[0.05]">
                           {smscSuppliers
                             .filter((wrapper: any) => wrapper.supplier.accountId === selectedAccount.id)
                             .map((wrapper: any) => {
                               const s = wrapper.supplier;
                               return (
                               <tr key={s.id} className="hover:bg-white/[0.02] transition">
                                 <td className="px-6 py-4 text-slate-900 dark:text-white font-medium">{s.name}</td>
                                 <td className="px-6 py-4 font-mono text-xs">{s.systemId}</td>
                                 <td className="px-6 py-4 font-mono text-xs">{s.host}:{s.port}</td>
                                 <td className="px-6 py-4">
                                   <div className="flex flex-col items-start gap-1">
                                     <div>
                                       {wrapper.connected 
                                          ? <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-green-500/10 text-green-400 border border-green-500/20">Bound</span>
                                          : s.active 
                                            ? <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-yellow-500/10 text-yellow-400 border border-yellow-500/20">Attempting...</span>
                                            : <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-red-500/10 text-red-400 border border-red-500/20">Unbound</span>}
                                     </div>
                                     {wrapper.uptimeSeconds != null && (
                                       <span className="text-[10px] text-slate-700 dark:text-slate-500">
                                         Up: {Math.floor(wrapper.uptimeSeconds / 3600)}h {Math.floor((wrapper.uptimeSeconds % 3600) / 60)}m
                                       </span>
                                     )}
                                   </div>
                                 </td>
                               </tr>
                               );
                             })}
                           {smscSuppliers.filter((w: any) => w.supplier.accountId === selectedAccount.id).length === 0 && (
                             <tr><td colSpan={4} className="px-6 py-12 text-center text-slate-700 dark:text-slate-500">No SMSC Suppliers configured for this account.</td></tr>
                           )}
                         </tbody>
                       </table>
                    </div>
                 </div>
                 )}
              </>
           ) : (
              <div className="flex-1 flex items-center justify-center bg-slate-100 dark:bg-[#1a1a2e] border border-slate-200 dark:border-white/[0.05] rounded-xl">
                 <div className="text-center text-slate-700 dark:text-slate-500 flex flex-col items-center gap-3">
                    <Building2 size={48} className="opacity-20" />
                    <p>Select an account from the sidebar<br/>to view details and routing configuration.</p>
                 </div>
              </div>
           )}
        </div>
      </div>

      {/* Account Basic Info Modal */}
      {showAccountModal && editingAccountParams && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/20 dark:bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl w-full max-w-xl shadow-2xl overflow-hidden flex flex-col">
            <div className="px-6 py-4 border-b border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f]">
              <h3 className="text-lg font-semibold text-slate-900 dark:text-white">
                {editingAccountParams.id ? 'Edit Account' : 'New Account'}
              </h3>
            </div>
            <form onSubmit={handleSaveAccountBasic} className="flex flex-col">
              <div className="p-6 space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Account Name *</label>
                    <input type="text" name="name" defaultValue={editingAccountParams.name} required className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Type *</label>
                    <select name="type" defaultValue={editingAccountParams.type || 'CUSTOMER'} required className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm">
                      <option value="CUSTOMER">Customer (Tx Traffic)</option>
                      <option value="SUPPLIER">Supplier (Rx Traffic)</option>
                      <option value="BILATERAL">Bilateral (Both)</option>
                    </select>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4 pt-2">
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Company Name</label>
                    <input type="text" name="companyName" defaultValue={editingAccountParams.companyName} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">VAT Number</label>
                    <input type="text" name="vatNumber" defaultValue={editingAccountParams.vatNumber} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                  <div className="col-span-2">
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Address</label>
                    <input type="text" name="address" defaultValue={editingAccountParams.address} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Email (Billing)</label>
                    <input type="email" name="email" defaultValue={editingAccountParams.email} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Contact Person</label>
                    <input type="text" name="contactPerson" defaultValue={editingAccountParams.contactPerson} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm" />
                  </div>
                </div>
              </div>
              <div className="px-6 py-4 border-t border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f] flex justify-end gap-3 shrink-0">
                <button type="button" onClick={() => setShowAccountModal(false)} className="px-4 py-2 rounded-lg text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white transition">Cancel</button>
                <button type="submit" disabled={isSaving} className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50">Save</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Username Modal */}
      {showUsernameModal && editingUsername && (
        <div className="fixed inset-0 z-[55] flex items-center justify-center bg-slate-900/20 dark:bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-slate-300 dark:border-white/10 rounded-xl w-full max-w-lg shadow-2xl overflow-hidden flex flex-col">
            <div className="px-6 py-4 border-b border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f]">
              <h3 className="text-lg font-semibold text-slate-900 dark:text-white">
                {editingUsernameIndex !== null ? 'Edit Username' : 'New Username'}
              </h3>
            </div>
            <form onSubmit={handleSaveUsername} className="flex flex-col">
              <div className="p-6 space-y-5">
                <div>
                  <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Username *</label>
                  <input type="text" value={editingUsername.username || ''} onChange={e => setEditingUsername({...editingUsername, username: e.target.value})} disabled={editingUsernameIndex !== null} required className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm disabled:opacity-50 disabled:cursor-not-allowed" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Whitelisted IPs (Comma separated)</label>
                  <input type="text" value={editingUsername.whitelistedIps || ''} onChange={e => setEditingUsername({...editingUsername, whitelistedIps: e.target.value})} className="w-full bg-white dark:bg-[#12121f] border border-slate-300 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm font-mono" />
                </div>
                <div className="pt-2 border-t border-slate-300 dark:border-white/5">
                   <h4 className="text-xs font-medium text-slate-600 dark:text-slate-400 mb-3">Capabilities</h4>
                   <div className="grid grid-cols-2 gap-4">
                      <label className="flex items-center gap-2 cursor-pointer p-2 rounded-lg border border-slate-300 dark:border-white/5 hover:bg-slate-200/50 dark:bg-white/5 transition">
                        <input type="checkbox" checked={editingUsername.enforceIpWhitelist || false} onChange={e => setEditingUsername({...editingUsername, enforceIpWhitelist: e.target.checked})} className="form-checkbox text-brand-500 rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/20" />
                        <span className="text-sm text-slate-700 dark:text-slate-300">Enforce IP Whitelist</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer p-2 rounded-lg border border-brand-500/30 bg-brand-500/5 hover:bg-brand-500/10 transition">
                        <input type="checkbox" checked={editingUsername.smppEnabled || false} onChange={e => setEditingUsername({...editingUsername, smppEnabled: e.target.checked})} className="form-checkbox text-brand-500 rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/20" />
                        <span className="text-sm text-brand-100 font-medium">SMPP Access</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer p-2 rounded-lg border border-slate-300 dark:border-white/5 hover:bg-slate-200/50 dark:bg-white/5 transition">
                        <input type="checkbox" checked={editingUsername.apiEnabled || false} onChange={e => setEditingUsername({...editingUsername, apiEnabled: e.target.checked})} className="form-checkbox text-brand-500 rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/20" />
                        <span className="text-sm text-slate-700 dark:text-slate-300">HTTP API Access</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer p-2 rounded-lg border border-slate-300 dark:border-white/5 hover:bg-slate-200/50 dark:bg-white/5 transition">
                        <input type="checkbox" checked={editingUsername.webEnabled || false} onChange={e => setEditingUsername({...editingUsername, webEnabled: e.target.checked})} className="form-checkbox text-brand-500 rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/20" />
                        <span className="text-sm text-slate-700 dark:text-slate-300">Web Portal Access</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer p-2 rounded-lg border border-red-500/30 bg-red-500/5 hover:bg-red-500/10 transition col-span-2 mt-2">
                        <input type="checkbox" checked={editingUsername.banned || false} onChange={e => setEditingUsername({...editingUsername, banned: e.target.checked})} className="form-checkbox text-red-500 rounded bg-white dark:bg-[#12121f] border-red-500/50" />
                        <span className="text-sm text-red-400 font-bold uppercase tracking-wider">BAN USER (Cut off all access)</span>
                      </label>
                   </div>
                </div>
              </div>
              <div className="px-6 py-4 border-t border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f] flex justify-between items-center shrink-0">
                <div>
                  {editingUsernameIndex !== null && editingUsername.smppEnabled && smppClients.find(c => c.usernameId === editingUsername.id) && (
                    <button 
                       type="button" 
                       onClick={() => {
                          const existingClient = smppClients.find(c => c.usernameId === editingUsername.id);
                          if (existingClient) {
                              setPendingSmppConfigs([{
                                  usernameRef: editingUsername as Username,
                                  smppData: existingClient,
                                  fullPayload: null // No account update needed, just edit SMPP
                              }]);
                              setShowUsernameModal(false);
                              setShowSmppModal(true);
                          }
                       }}
                       className="text-xs text-brand-400 hover:text-brand-300 font-medium underline underline-offset-2"
                    >
                      Edit SMPP Config
                    </button>
                  )}
                </div>
                <div className="flex gap-3">
                  <button type="button" onClick={() => setShowUsernameModal(false)} className="px-4 py-2 rounded-lg text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white transition">Cancel</button>
                  <button type="submit" disabled={isSaving} className="bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50">Save Username</button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* SMPP Configuration Modal (Dynamic flow for usernames) */}
      {showSmppModal && pendingSmppConfigs.length > 0 && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/20 dark:bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-slate-100 dark:bg-[#1a1a2e] border border-brand-500/30 rounded-xl w-full max-w-md shadow-[0_0_50px_rgba(var(--brand-500),0.1)] overflow-hidden flex flex-col">
            <div className="px-6 py-4 border-b border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f]">
              <h3 className="text-lg font-semibold text-slate-900 dark:text-white">
                SMPP Credentials
              </h3>
              <p className="text-xs text-slate-600 dark:text-slate-400 mt-1">
                You enabled SMPP for <span className="text-brand-400 font-medium">{pendingSmppConfigs[0].usernameRef.username}</span>. Please configure credentials.
              </p>
            </div>
            
            <div className="p-6">
               <SmppClientFormFields 
                   formData={pendingSmppConfigs[0].smppData} 
                   setFormData={(newData) => {
                       const updated = [...pendingSmppConfigs];
                       updated[0].smppData = newData;
                       setPendingSmppConfigs(updated);
                   }}
                   layout="vertical-div"
                   showUsernameSelect={false}
               />
            </div>

            <div className="px-6 py-4 border-t border-slate-300 dark:border-white/5 bg-white dark:bg-[#12121f] flex justify-end gap-3 shrink-0">
              <button
                type="button"
                onClick={() => {
                  setShowSmppModal(false);
                  setPendingSmppConfigs([]);
                }}
                className="px-4 py-2 rounded-lg text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white transition"
                disabled={isSaving}
              >
                Abort Save
              </button>
              <button
                type="button"
                onClick={() => {
                   if (pendingSmppConfigs[0].fullPayload === null) {
                       // Direct SMPP edit
                       setIsSaving(true);
                       const config = pendingSmppConfigs[0].smppData;
                       const req = config.id ? api.put(`/smpp/clients/${config.id}`, config) : api.post('/smpp/clients', config);
                       req.then(() => {
                          setShowSmppModal(false);
                          setPendingSmppConfigs([]);
                          fetchAccounts(true);
                       }).catch((err) => {
                          alert('Failed to save SMPP config: ' + (err.response?.data?.message || err.message));
                       }).finally(() => setIsSaving(false));
                   } else {
                       // Execute the full save payload + smpp configs
                       executeAccountPayloadSave(
                           pendingSmppConfigs[0].fullPayload, 
                           selectedAccount!.id, 
                           pendingSmppConfigs
                       );
                   }
                }}
                disabled={isSaving}
                className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
              >
                {isSaving ? 'Saving...' : 'Confirm & Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
