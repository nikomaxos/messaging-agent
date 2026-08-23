import React, { useState, useEffect } from 'react';
import { CreditCard, Plus, Check, RefreshCw, AlertTriangle, DollarSign } from 'lucide-react';
import api from '../api/client';

export default function BillingPage() {
  const [activeTab, setActiveTab] = useState<'accounts' | 'tariffs'>('accounts');
  
  // Data State
  const [accounts, setAccounts] = useState<any[]>([]);
  const [plans, setPlans] = useState<any[]>([]);
  const [rates, setRates] = useState<any[]>([]);
  const [selectedPlanId, setSelectedPlanId] = useState<number | null>(null);
  
  // UI State
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Top-Up State
  const [showTopUpModal, setShowTopUpModal] = useState(false);
  const [topUpClient, setTopUpClient] = useState<any>(null);
  const [topUpAmount, setTopUpAmount] = useState('');
  const [topUpDesc, setTopUpDesc] = useState('');

  // Tariff State
  const [newPlanName, setNewPlanName] = useState('');
  const [newRatePrefix, setNewRatePrefix] = useState('');
  const [newRateAmount, setNewRateAmount] = useState('');

  useEffect(() => {
    fetchData();
  }, []);

  useEffect(() => {
    if (selectedPlanId) {
      fetchRates(selectedPlanId);
    }
  }, [selectedPlanId]);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [accRes, planRes] = await Promise.all([
        api.get('/billing/accounts'),
        api.get('/billing/tariffs')
      ]);
      setAccounts(accRes.data || []);
      setPlans(planRes.data || []);
      if (planRes.data && planRes.data.length > 0 && !selectedPlanId) {
        setSelectedPlanId(planRes.data[0].id);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to load billing data');
    } finally {
      setLoading(false);
    }
  };

  const fetchRates = async (planId: number) => {
    try {
      const res = await api.get(`/billing/tariffs/${planId}/rates`);
      setRates(res.data || []);
    } catch (err) {
      console.error(err);
    }
  };

  const handleTopUp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!topUpClient) return;
    try {
      await api.post(`/billing/accounts/${topUpClient.accountId}/topup`, {
        amount: parseFloat(topUpAmount),
        description: topUpDesc || 'Manual Top-Up via Admin Panel'
      });
      setShowTopUpModal(false);
      setTopUpAmount('');
      setTopUpDesc('');
      fetchData();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Top-up failed');
    }
  };

  const handleCreatePlan = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post('/billing/tariffs', { name: newPlanName, currency: 'EUR' });
      setNewPlanName('');
      fetchData();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create plan');
    }
  };

  const handleAddRate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPlanId) return;
    try {
      await api.post(`/billing/tariffs/${selectedPlanId}/rates`, {
        prefix: newRatePrefix,
        rate: parseFloat(newRateAmount)
      });
      setNewRatePrefix('');
      setNewRateAmount('');
      fetchRates(selectedPlanId);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to add rate');
    }
  };

  const handleDeleteRate = async (rateId: number) => {
    if (!selectedPlanId) return;
    try {
      await api.delete(`/billing/tariffs/${selectedPlanId}/rates/${rateId}`);
      fetchRates(selectedPlanId);
    } catch (err) {
      console.error(err);
    }
  };

  const handleUpdateAccount = async (accountId: number, billingType: string, tariffPlanId: number | null) => {
    try {
      await api.put(`/billing/accounts/${accountId}`, {
        billingType,
        tariffPlanId,
        creditLimit: 0
      });
      fetchData();
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Billing & Rating</h1>
          <p className="text-slate-600 dark:text-slate-400 mt-1">Manage carrier tariffs and client balances in real-time.</p>
        </div>
        <button 
          onClick={fetchData} 
          className="flex items-center gap-2 px-4 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg transition"
        >
          <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
          Refresh
        </button>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/20 text-red-400 px-4 py-3 rounded-lg flex items-center gap-2">
          <AlertTriangle size={18} />
          {error}
        </div>
      )}

      {/* Tabs */}
      <div className="flex space-x-1 bg-slate-800/50 p-1 rounded-xl">
        <button
          onClick={() => setActiveTab('accounts')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg transition ${
            activeTab === 'accounts' ? 'bg-indigo-500 text-slate-900 dark:text-white shadow' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white hover:bg-slate-800'
          }`}
        >
          <CreditCard size={18} />
          Client Accounts
        </button>
        <button
          onClick={() => setActiveTab('tariffs')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg transition ${
            activeTab === 'tariffs' ? 'bg-indigo-500 text-slate-900 dark:text-white shadow' : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white hover:bg-slate-800'
          }`}
        >
          <DollarSign size={18} />
          Tariff Plans
        </button>
      </div>

      {/* Accounts Tab */}
      {activeTab === 'accounts' && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-slate-800/50 border-b border-slate-700/50">
                <th className="px-6 py-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider">Account</th>
                <th className="px-6 py-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider">Billing Type</th>
                <th className="px-6 py-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider">Tariff Plan</th>
                <th className="px-6 py-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider text-right">Live Balance</th>
                <th className="px-6 py-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/50">
              {accounts.map(acc => (
                <tr key={acc.accountId} className="hover:bg-slate-800/20 transition group">
                  <td className="px-6 py-4 whitespace-nowrap text-sm font-medium">Account #{acc.accountId}</td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm">
                    <select 
                      value={acc.billingType || 'POSTPAID'} 
                      onChange={(e) => handleUpdateAccount(acc.accountId, e.target.value, acc.tariffPlanId)}
                      className="bg-slate-800 border border-slate-700 text-slate-900 dark:text-white text-xs rounded px-2 py-1 focus:ring-indigo-500 focus:border-indigo-500"
                    >
                      <option value="PREPAID">Prepaid</option>
                      <option value="POSTPAID">Postpaid</option>
                    </select>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm">
                    <select 
                      value={acc.tariffPlanId || ''} 
                      onChange={(e) => handleUpdateAccount(acc.accountId, acc.billingType, e.target.value ? Number(e.target.value) : null)}
                      className="bg-slate-800 border border-slate-700 text-slate-900 dark:text-white text-xs rounded px-2 py-1 focus:ring-indigo-500 focus:border-indigo-500"
                    >
                      <option value="">No Plan (Free)</option>
                      {plans.map(p => (
                        <option key={p.id} value={p.id}>{p.name}</option>
                      ))}
                    </select>
                  </td>
                  <td className={`px-6 py-4 whitespace-nowrap text-sm font-mono text-right ${acc.balance < 0 ? 'text-red-400' : 'text-emerald-400'}`}>
                    €{Number(acc.balance).toFixed(5)}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                    <button 
                      onClick={() => { setTopUpClient(acc); setShowTopUpModal(true); }}
                      className="text-indigo-400 hover:text-indigo-300 bg-indigo-500/10 hover:bg-indigo-500/20 px-3 py-1.5 rounded transition"
                    >
                      Top-Up
                    </button>
                  </td>
                </tr>
              ))}
              {accounts.length === 0 && !loading && (
                <tr><td colSpan={5} className="px-6 py-8 text-center text-slate-500">No client accounts found.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Tariffs Tab */}
      {activeTab === 'tariffs' && (
        <div className="grid grid-cols-3 gap-6">
          <div className="col-span-1 space-y-4">
            <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl p-4">
              <h3 className="font-semibold mb-4 text-sm uppercase text-slate-600 dark:text-slate-400 tracking-wider">Plans</h3>
              <ul className="space-y-2">
                {plans.map(p => (
                  <li key={p.id}>
                    <button 
                      onClick={() => setSelectedPlanId(p.id)}
                      className={`w-full text-left px-3 py-2 rounded-lg transition ${selectedPlanId === p.id ? 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/30' : 'hover:bg-slate-800 text-slate-300 border border-transparent'}`}
                    >
                      {p.name} ({p.currency})
                    </button>
                  </li>
                ))}
              </ul>
              
              <div className="mt-6 pt-6 border-t border-slate-800">
                <form onSubmit={handleCreatePlan} className="space-y-3">
                  <input 
                    type="text" 
                    value={newPlanName}
                    onChange={e => setNewPlanName(e.target.value)}
                    placeholder="New Plan Name" 
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none transition"
                    required
                  />
                  <button type="submit" className="w-full bg-slate-800 hover:bg-slate-700 text-slate-900 dark:text-white font-medium py-2 rounded-lg border border-slate-700 transition flex items-center justify-center gap-2 text-sm">
                    <Plus size={16} /> Create Plan
                  </button>
                </form>
              </div>
            </div>
          </div>
          
          <div className="col-span-2">
            <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-xl">
              <div className="px-6 py-4 border-b border-slate-800 bg-slate-800/30 flex justify-between items-center">
                <h3 className="font-semibold text-slate-900 dark:text-white">
                  Rates for {plans.find(p => p.id === selectedPlanId)?.name || 'Selected Plan'}
                </h3>
              </div>
              <div className="p-4 bg-slate-800/10">
                <form onSubmit={handleAddRate} className="flex gap-3">
                  <input 
                    type="text" 
                    value={newRatePrefix}
                    onChange={e => setNewRatePrefix(e.target.value)}
                    placeholder="Prefix (e.g. +44 or *)" 
                    className="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none transition"
                    required
                  />
                  <input 
                    type="number" 
                    step="0.00001"
                    value={newRateAmount}
                    onChange={e => setNewRateAmount(e.target.value)}
                    placeholder="Rate (e.g. 0.025)" 
                    className="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 outline-none transition"
                    required
                  />
                  <button type="submit" className="bg-indigo-600 hover:bg-indigo-500 text-slate-900 dark:text-white px-4 py-2 rounded-lg font-medium transition text-sm">
                    Add Rate
                  </button>
                </form>
              </div>
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-slate-800/50 border-y border-slate-700/50">
                    <th className="px-6 py-3 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider">Prefix</th>
                    <th className="px-6 py-3 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider text-right">Rate</th>
                    <th className="px-6 py-3 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider text-right"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/50">
                  {rates.map(rate => (
                    <tr key={rate.id} className="hover:bg-slate-800/20 transition group">
                      <td className="px-6 py-3 whitespace-nowrap text-sm font-mono">{rate.prefix}</td>
                      <td className="px-6 py-3 whitespace-nowrap text-sm font-mono text-right text-emerald-400">€{Number(rate.rate).toFixed(5)}</td>
                      <td className="px-6 py-3 whitespace-nowrap text-right text-sm">
                        <button onClick={() => handleDeleteRate(rate.id)} className="text-red-400 hover:text-red-300 text-xs">Delete</button>
                      </td>
                    </tr>
                  ))}
                  {rates.length === 0 && (
                    <tr><td colSpan={3} className="px-6 py-8 text-center text-slate-500">No rates defined for this plan.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Top-Up Modal */}
      {showTopUpModal && topUpClient && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-md shadow-2xl overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-800 bg-slate-800/50">
              <h3 className="font-semibold text-lg">Top-Up Account</h3>
              <p className="text-slate-600 dark:text-slate-400 text-sm">Add funds for Account #{topUpClient.accountId}</p>
            </div>
            <form onSubmit={handleTopUp} className="p-6 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">Current Balance</label>
                <div className="text-2xl font-mono text-emerald-400">€{Number(topUpClient.balance).toFixed(5)}</div>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">Amount to Add (€)</label>
                <input 
                  type="number" 
                  step="0.01"
                  value={topUpAmount}
                  onChange={e => setTopUpAmount(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-lg font-mono focus:ring-2 focus:ring-emerald-500 outline-none transition"
                  required
                  autoFocus
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">Description (Optional)</label>
                <input 
                  type="text" 
                  value={topUpDesc}
                  onChange={e => setTopUpDesc(e.target.value)}
                  placeholder="e.g. Wire Transfer Ref 123"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-2 text-sm focus:ring-2 focus:ring-emerald-500 outline-none transition"
                />
              </div>
              <div className="flex justify-end gap-3 pt-4 border-t border-slate-800 mt-6">
                <button 
                  type="button" 
                  onClick={() => setShowTopUpModal(false)}
                  className="px-4 py-2 text-sm font-medium text-slate-300 hover:text-slate-900 dark:text-white transition"
                >
                  Cancel
                </button>
                <button 
                  type="submit"
                  className="bg-emerald-600 hover:bg-emerald-500 text-slate-900 dark:text-white px-6 py-2 rounded-lg font-medium transition flex items-center gap-2"
                >
                  <Plus size={18} /> Add Funds
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
