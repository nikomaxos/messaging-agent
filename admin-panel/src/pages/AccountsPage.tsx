import React, { useState, useEffect } from 'react';
import api from '../api/client';

interface Account {
  id: number;
  name: string;
  type: string;
  companyName: string;
  vatNumber: string;
  address: string;
  email: string;
  contactPerson: string;
  whitelistedIps: string;
  enforceIpWhitelist: boolean;
  smppEnabled: boolean;
  apiEnabled: boolean;
  webEnabled: boolean;
  createdAt: string;
}

export default function AccountsPage() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);

  useEffect(() => {
    fetchAccounts();
  }, []);

  const fetchAccounts = async () => {
    try {
      const res = await api.get('/accounts');
      setAccounts(res.data);
    } catch (error) {
      console.error('Failed to fetch accounts:', error);
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('Are you sure you want to delete this account?')) return;
    try {
      await api.delete(`/accounts/${id}`);
      fetchAccounts();
    } catch (error) {
      console.error('Failed to delete account:', error);
    }
  };

  const handleSave = async (e: React.FormEvent<HTMLFormElement>) => {
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
      whitelistedIps: formData.get('whitelistedIps') as string,
      enforceIpWhitelist: formData.get('enforceIpWhitelist') === 'on',
      smppEnabled: formData.get('smppEnabled') === 'on',
      apiEnabled: formData.get('apiEnabled') === 'on',
      webEnabled: formData.get('webEnabled') === 'on',
    };

    try {
      if (editingAccount) {
        await api.put(`/accounts/${editingAccount.id}`, payload);
      } else {
        await api.post('/accounts', payload);
      }
      setIsModalOpen(false);
      setEditingAccount(null);
      fetchAccounts();
    } catch (error) {
      console.error('Failed to save account:', error);
    }
  };

  const openModal = (account?: Account) => {
    setEditingAccount(account || null);
    setIsModalOpen(true);
  };

  return (
    <div className="p-8 relative">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-bold text-white mb-1">Accounts Management</h1>
          <p className="text-slate-400 text-sm">Manage billing accounts, customer details, and feature toggles</p>
        </div>
        <button
          onClick={() => openModal()}
          className="bg-brand-600 hover:bg-brand-500 text-white px-4 py-2 rounded-lg text-sm font-medium transition"
        >
          + Add Account
        </button>
      </div>

      <div className="bg-[#1a1a2e] border border-white/[0.05] rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="bg-[#12121f] text-slate-400 border-b border-white/[0.05]">
            <tr>
              <th className="px-5 py-4 font-medium">ID / Name</th>
              <th className="px-5 py-4 font-medium">Type</th>
              <th className="px-5 py-4 font-medium">Company Details</th>
              <th className="px-5 py-4 font-medium">Channels</th>
              <th className="px-5 py-4 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.05]">
            {accounts.map((account) => (
              <tr key={account.id} className="hover:bg-white/[0.02] transition">
                <td className="px-5 py-3 text-white">
                  <div className="font-medium">{account.name}</div>
                  <div className="text-[10px] text-slate-500 mt-1">ID: {account.id}</div>
                </td>
                <td className="px-5 py-3">
                  <span className={`px-2 inline-flex text-[11px] leading-5 font-semibold rounded ${
                    account.type === 'CUSTOMER' ? 'bg-green-500/10 text-green-400 border border-green-500/20' :
                    account.type === 'SUPPLIER' ? 'bg-blue-500/10 text-blue-400 border border-blue-500/20' :
                    'bg-purple-500/10 text-purple-400 border border-purple-500/20'
                  }`}>
                    {account.type}
                  </span>
                </td>
                <td className="px-5 py-3">
                  <div className="text-sm text-white">{account.companyName || '-'}</div>
                  <div className="text-xs text-slate-400">{account.email || '-'}</div>
                </td>
                <td className="px-5 py-3 text-sm text-slate-400">
                  <div className="flex space-x-2">
                    <span title="SMPP" className={`w-2 h-2 rounded-full ${account.smppEnabled ? 'bg-green-400' : 'bg-slate-600'}`}></span>
                    <span title="API" className={`w-2 h-2 rounded-full ${account.apiEnabled ? 'bg-green-400' : 'bg-slate-600'}`}></span>
                    <span title="Web" className={`w-2 h-2 rounded-full ${account.webEnabled ? 'bg-green-400' : 'bg-slate-600'}`}></span>
                  </div>
                </td>
                <td className="px-5 py-3 text-right">
                  <button
                    onClick={() => openModal(account)}
                    className="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded transition mr-2 text-sm"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => handleDelete(account.id)}
                    className="p-1.5 text-slate-400 hover:text-red-400 hover:bg-red-400/10 rounded transition text-sm"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {accounts.length === 0 && (
              <tr>
                <td colSpan={5} className="px-5 py-12 text-center text-slate-500">
                  No accounts found. Create one to get started.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-[#1a1a2e] border border-white/10 rounded-xl w-full max-w-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
            <div className="px-6 py-4 border-b border-white/5 bg-[#12121f]">
              <h3 className="text-lg font-semibold text-white">
                {editingAccount ? 'Edit Account' : 'New Account'}
              </h3>
            </div>
            
            <div className="p-0 overflow-hidden flex-1 flex flex-col">
              <form id="accountForm" onSubmit={handleSave} className="flex flex-col h-full">
                
                <div className="p-6 overflow-y-auto space-y-6 flex-1">
                  {/* Basic Info */}
                  <div className="bg-[#12121f]/50 p-4 rounded-lg border border-white/[0.02] space-y-4">
                  <h4 className="font-medium text-white mb-2">Basic Information</h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">Account Name *</label>
                      <input type="text" name="name" defaultValue={editingAccount?.name} required
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">Type *</label>
                      <select name="type" defaultValue={editingAccount?.type || 'CUSTOMER'} required
                              className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm">
                        <option value="CUSTOMER">Customer (Tx Traffic)</option>
                        <option value="SUPPLIER">Supplier (Rx Traffic)</option>
                        <option value="BILATERAL">Bilateral (Both)</option>
                      </select>
                    </div>
                  </div>
                </div>

                {/* Company & Billing */}
                <div className="bg-[#12121f]/50 p-4 rounded-lg border border-white/[0.02] space-y-4">
                  <h4 className="font-medium text-white mb-2">Invoice Issuing Data</h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">Company Name</label>
                      <input type="text" name="companyName" defaultValue={editingAccount?.companyName}
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">VAT Number</label>
                      <input type="text" name="vatNumber" defaultValue={editingAccount?.vatNumber}
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                    <div className="col-span-2">
                      <label className="block text-xs font-medium text-slate-400 mb-1">Address</label>
                      <input type="text" name="address" defaultValue={editingAccount?.address}
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">Email (Billing)</label>
                      <input type="email" name="email" defaultValue={editingAccount?.email}
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-slate-400 mb-1">Contact Person</label>
                      <input type="text" name="contactPerson" defaultValue={editingAccount?.contactPerson}
                             className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                    </div>
                  </div>
                </div>

                {/* Security & Access */}
                <div className="bg-[#12121f]/50 p-4 rounded-lg border border-white/[0.02] space-y-4">
                  <h4 className="font-medium text-white mb-2">Security & Access</h4>
                  <div className="col-span-2 mb-4">
                    <label className="block text-xs font-medium text-slate-400 mb-1">Whitelisted IPs (comma separated)</label>
                    <textarea name="whitelistedIps" defaultValue={editingAccount?.whitelistedIps} rows={2}
                           className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 text-white text-sm" />
                  </div>
                  
                  <div className="grid grid-cols-2 gap-4">
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="checkbox" name="enforceIpWhitelist" defaultChecked={editingAccount?.enforceIpWhitelist} className="form-checkbox text-brand-500 rounded bg-[#12121f] border-white/20" />
                      <span className="text-sm text-slate-300">Enforce IP Whitelist</span>
                    </label>
                    
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="checkbox" name="smppEnabled" defaultChecked={editingAccount ? editingAccount.smppEnabled : true} className="form-checkbox text-brand-500 rounded bg-[#12121f] border-white/20" />
                      <span className="text-sm text-slate-300">Enable SMPP Access</span>
                    </label>

                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="checkbox" name="apiEnabled" defaultChecked={editingAccount?.apiEnabled} className="form-checkbox text-brand-500 rounded bg-[#12121f] border-white/20" />
                      <span className="text-sm text-slate-300">Enable API Access</span>
                    </label>

                    <label className="flex items-center gap-2 cursor-pointer">
                      <input type="checkbox" name="webEnabled" defaultChecked={editingAccount?.webEnabled} className="form-checkbox text-brand-500 rounded bg-[#12121f] border-white/20" />
                      <span className="text-sm text-slate-300">Enable Web Portal</span>
                    </label>
                  </div>
                </div>
                </div>

                <div className="px-6 py-4 border-t border-white/5 bg-[#12121f] flex justify-end gap-3 rounded-b-lg shrink-0">
                  <button
                    type="button"
                    onClick={() => setIsModalOpen(false)}
                    className="px-4 py-2 rounded-lg text-sm text-slate-400 hover:text-white transition"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="flex items-center gap-2 bg-brand-600 hover:bg-brand-500 text-white px-6 py-2 rounded-lg text-sm font-medium transition disabled:opacity-50"
                  >
                    Save Account
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
