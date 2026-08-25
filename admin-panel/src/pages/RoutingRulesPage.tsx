import React, { useState, useEffect, useCallback } from 'react';
import { 
  getRoutingRules, 
  createRoutingRule, 
  updateRoutingRule, 
  deleteRoutingRule, 
  testRoutingRule 
} from '../api/client';
import { Settings, Plus, Trash2, Edit, AlertTriangle, ArrowRight, Save, X, Play, Code } from 'lucide-react';
import { ReactFlow, Background, Controls, Node, Edge, Position } from '@xyflow/react';
import '@xyflow/react/dist/style.css';

interface RuleCondition {
  id?: number;
  field: string;
  operator: string;
  value: string;
}

interface RuleAction {
  id?: number;
  actionType: string;
  actionValue: string;
}

interface RoutingRule {
  id: number;
  name: string;
  description: string;
  priority: number;
  active: boolean;
  conditions: RuleCondition[];
  actions: RuleAction[];
  enableRoutingPerCountryPrefix: boolean;
}

const REGEX_SNIPPETS = [
  { name: 'OTP (5-7 digits)', regex: '\\b\\d{5,7}\\b', desc: 'Matches exactly 5 to 7 digits in a row' },
  { name: 'URL (http/https)', regex: 'https?:\\/\\/[\\w\\-\\.]+(?:\\.[\\w\\.-]+)+[\\w\\-\\._~:/?#[\\]@!$&\'()*+,;=]+', desc: 'Matches standard URLs starting with http or https' },
  { name: 'URL (without http)', regex: '\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]\\b', desc: 'Matches domains like google.com' },
  { name: 'Phone Number (+44...)', regex: '^\\+44\\d{10}$', desc: 'Matches UK phone numbers starting with +44' }
];

export default function RoutingRulesPage() {
  const [rules, setRules] = useState<RoutingRule[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  
  // Editor State
  const [editingId, setEditingId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState(100);
  const [isActive, setIsActive] = useState(true);
  const [enableRoutingPerCountryPrefix, setEnableRoutingPerCountryPrefix] = useState(false);
  const [conditions, setConditions] = useState<RuleCondition[]>([]);
  const [actions, setActions] = useState<RuleAction[]>([]);
  
  // Test State
  const [testPayload, setTestPayload] = useState({
    systemId: 'test-client',
    sourceAddress: 'BrandX',
    destinationAddress: '+447911123456',
    messageText: 'Your OTP is 123456'
  });
  const [testResult, setTestResult] = useState<any>(null);
  
  const [activeTab, setActiveTab] = useState<'BUILDER' | 'TESTER' | 'FLOWCHART'>('BUILDER');

  useEffect(() => {
    fetchRules();
  }, []);

  const fetchRules = async () => {
    try {
      const res = await getRoutingRules();
      setRules(res);
    } catch (e) {
      console.error(e);
    }
  };

  const openEditor = (r?: RoutingRule) => {
    if (r) {
      setEditingId(r.id);
      setName(r.name);
      setDescription(r.description);
      setPriority(r.priority);
      setIsActive(r.active);
      setEnableRoutingPerCountryPrefix(r.enableRoutingPerCountryPrefix || false);
      setConditions(r.conditions || []);
      setActions(r.actions || []);
    } else {
      setEditingId(null);
      setName('New Rule');
      setDescription('');
      setPriority(100);
      setIsActive(true);
      setEnableRoutingPerCountryPrefix(false);
      setConditions([]);
      setActions([]);
    }
    setTestResult(null);
    setActiveTab('BUILDER');
    setIsModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const payload = { name, description, priority, active: isActive, enableRoutingPerCountryPrefix, conditions, actions };
      if (editingId) {
        await updateRoutingRule(editingId, payload);
      } else {
        await createRoutingRule(payload);
      }
      setIsModalOpen(false);
      fetchRules();
    } catch (e) {
      console.error(e);
      alert('Failed to save rule');
    }
  };

  const handleDelete = async (id: number) => {
    if (confirm('Are you sure?')) {
      await deleteRoutingRule(id);
      fetchRules();
    }
  };

  const handleTest = async () => {
    try {
      const res = await testRoutingRule(testPayload);
      setTestResult(res);
    } catch (e) {
      console.error(e);
      alert('Test failed');
    }
  };

  // Flowchart Nodes generator
  const getFlowNodes = useCallback(() => {
    const nodes: Node[] = [];
    const edges: Edge[] = [];
    let yPos = 50;

    nodes.push({
      id: 'start',
      type: 'input',
      data: { label: 'Incoming Message' },
      position: { x: 250, y: yPos },
      style: { background: '#2563eb', color: 'white', borderRadius: '8px' }
    });

    let parentId = 'start';
    yPos += 100;

    // Conditions
    if (conditions.length === 0) {
      nodes.push({
        id: 'no-cond',
        data: { label: 'No Conditions (Matches All)' },
        position: { x: 250, y: yPos },
        style: { background: '#f59e0b', color: 'white' }
      });
      edges.push({ id: `e-${parentId}-no-cond`, source: parentId, target: 'no-cond' });
      parentId = 'no-cond';
      yPos += 100;
    } else {
      conditions.forEach((c, idx) => {
        const cId = `cond-${idx}`;
        nodes.push({
          id: cId,
          data: { label: `IF ${c.field} ${c.operator} ${c.value}` },
          position: { x: 250, y: yPos },
          style: { background: '#f59e0b', color: 'white', borderRadius: '4px' }
        });
        edges.push({ id: `e-${parentId}-${cId}`, source: parentId, target: cId });
        parentId = cId;
        yPos += 100;
      });
    }

    // Actions
    if (actions.length === 0) {
        nodes.push({
            id: 'no-act',
            data: { label: 'No Actions (Pass Through)' },
            position: { x: 250, y: yPos },
            style: { background: '#10b981', color: 'white' }
        });
        edges.push({ id: `e-${parentId}-no-act`, source: parentId, target: 'no-act' });
    } else {
        actions.forEach((a, idx) => {
        const aId = `act-${idx}`;
        nodes.push({
            id: aId,
            data: { label: `THEN ${a.actionType} = ${a.actionValue}` },
            position: { x: 250, y: yPos },
            style: { background: '#10b981', color: 'white', borderRadius: '4px' }
        });
        edges.push({ id: `e-${parentId}-${aId}`, source: parentId, target: aId });
        parentId = aId;
        yPos += 100;
        });
    }

    return { nodes, edges };
  }, [conditions, actions]);

  return (
    <div className="p-6">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <Settings className="w-6 h-6 text-blue-500" />
            Rules Engine
          </h1>
          <p className="text-slate-700 dark:text-slate-500 dark:text-slate-400 mt-1">
            Build automations to route, rewrite, and block messages.
          </p>
        </div>
        <button 
          onClick={() => openEditor()}
          className="bg-blue-600 hover:bg-blue-700 text-slate-900 dark:text-white px-4 py-2 rounded-lg flex items-center gap-2 transition-colors"
        >
          <Plus className="w-4 h-4" /> Create Automation
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {rules.map(r => (
          <div key={r.id} className={`bg-white dark:bg-[#1a1a2e] rounded-xl shadow-md border p-4 flex items-center justify-between transition-all hover:shadow-lg hover:border-blue-500 ${!r.active ? 'opacity-75 border-slate-400 dark:border-slate-600' : 'border-slate-400 dark:border-slate-600'}`}>
            <div className="flex items-center gap-4">
              <div className={`w-3 h-3 rounded-full ${r.active ? 'bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.6)]' : 'bg-slate-400'}`}></div>
              <div>
                <h3 className="font-semibold text-slate-800 dark:text-white">{r.name}</h3>
                <p className="text-sm text-slate-700 dark:text-slate-500">{r.description || 'No description'} • Priority: {r.priority}</p>
              </div>
            </div>
            
            <div className="flex gap-4 items-center">
              <div className="text-xs text-slate-700 dark:text-slate-500 bg-slate-100 dark:bg-slate-700 px-2 py-1 rounded">
                {r.conditions.length} Triggers
              </div>
              <ArrowRight className="w-4 h-4 text-slate-600 dark:text-slate-400" />
              <div className="text-xs text-slate-700 dark:text-slate-500 bg-slate-100 dark:bg-slate-700 px-2 py-1 rounded">
                {r.actions.length} Actions
              </div>
              
              <div className="w-px h-6 bg-slate-200 dark:bg-slate-700 mx-2"></div>
              
              <button onClick={() => openEditor(r)} className="p-2 text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg">
                <Edit className="w-4 h-4" />
              </button>
              <button onClick={() => handleDelete(r.id)} className="p-2 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg">
                <Trash2 className="w-4 h-4" />
              </button>
            </div>
          </div>
        ))}
        {rules.length === 0 && (
          <div className="text-center py-12 text-slate-700 dark:text-slate-500 bg-slate-100 dark:bg-[#1a1a2e]/50 rounded-xl border border-dashed border-slate-300 dark:border-slate-600">
            No routing rules defined. Create one to get started.
          </div>
        )}
      </div>

      {isModalOpen && (
        <div className="fixed inset-0 bg-slate-900/20 dark:bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white dark:bg-[#1a1a2e] rounded-2xl shadow-2xl w-full max-w-5xl h-[90vh] flex flex-col overflow-hidden">
            <div className="p-4 border-b border-slate-200 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-[#12121f]">
              <h2 className="text-xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
                <Settings className="w-5 h-5 text-blue-500" />
                {editingId ? 'Edit Automation' : 'New Automation'}
              </h2>
              <button onClick={() => setIsModalOpen(false)} className="text-slate-700 dark:text-slate-500 hover:text-slate-700 p-1">
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="flex border-b border-slate-200 dark:border-slate-700">
              {['BUILDER', 'TESTER', 'FLOWCHART'].map(tab => (
                <button
                  key={tab}
                  onClick={() => setActiveTab(tab as any)}
                  className={`px-6 py-3 text-sm font-medium transition-colors ${activeTab === tab ? 'border-b-2 border-blue-500 text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-[#1a1a2e]' : 'text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-100 dark:bg-slate-700'}`}
                >
                  {tab === 'BUILDER' ? 'Rule Builder' : tab === 'TESTER' ? 'Dry Run Tester' : 'Live Flowchart'}
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-y-auto">
              {activeTab === 'BUILDER' && (
                <div className="p-6 space-y-8">
                  
                  {/* General Info */}
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium mb-1">Name</label>
                      <input type="text" value={name} onChange={e => setName(e.target.value)} className="w-full bg-slate-50 dark:bg-[#12121f] border border-slate-300 dark:border-slate-600 rounded-lg p-2" placeholder="e.g. Block Spam Links" />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Description</label>
                      <input type="text" value={description} onChange={e => setDescription(e.target.value)} className="w-full bg-slate-50 dark:bg-[#12121f] border border-slate-300 dark:border-slate-600 rounded-lg p-2" />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Priority (Lower runs first)</label>
                      <input type="number" value={priority} onChange={e => setPriority(parseInt(e.target.value))} className="w-full bg-slate-50 dark:bg-[#12121f] border border-slate-300 dark:border-slate-600 rounded-lg p-2" />
                    </div>
                    <div className="flex items-end">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input type="checkbox" checked={isActive} onChange={e => setIsActive(e.target.checked)} className="w-5 h-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                        <span className="text-sm font-medium">Rule Active</span>
                      </label>
                    </div>
                    <div className="flex items-end">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input type="checkbox" checked={enableRoutingPerCountryPrefix} onChange={e => setEnableRoutingPerCountryPrefix(e.target.checked)} className="w-5 h-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                        <span className="text-sm font-medium">Enable Routing Per Country Prefix</span>
                      </label>
                    </div>
                  </div>

                  {/* Triggers (Conditions) */}
                  <div className="bg-orange-50 dark:bg-orange-950/20 border border-orange-200 dark:border-orange-900/50 rounded-xl p-5">
                    <h3 className="text-lg font-semibold text-orange-800 dark:text-orange-400 mb-4 flex items-center gap-2">
                      When (Triggers)
                      <span className="text-xs font-normal text-orange-600 dark:text-orange-500 bg-orange-200 dark:bg-orange-900/60 px-2 py-0.5 rounded-full">AND logic</span>
                    </h3>
                    
                    <div className="space-y-3">
                      {conditions.map((c, i) => (
                        <div key={i} className="flex gap-2 items-center bg-white dark:bg-[#1a1a2e] p-2 rounded-lg border border-slate-200 dark:border-slate-700">
                          <select value={c.field} onChange={e => {
                            const newCond = [...conditions];
                            newCond[i].field = e.target.value;
                            setConditions(newCond);
                          }} className="p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700">
                            <option value="SOURCE_ADDRESS">Source Address</option>
                            <option value="DESTINATION_ADDRESS">Destination Address</option>
                            <option value="MESSAGE_TEXT">Message Text</option>
                            <option value="SYSTEM_ID">Client System ID</option>
                          </select>
                          
                          <select value={c.operator} onChange={e => {
                            const newCond = [...conditions];
                            newCond[i].operator = e.target.value;
                            setConditions(newCond);
                          }} className="p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700 text-blue-600 font-medium">
                            <option value="MATCHES_REGEX">Matches Regex</option>
                            <option value="EQUALS">Equals</option>
                            <option value="CONTAINS">Contains</option>
                            <option value="STARTS_WITH">Starts With</option>
                          </select>
                          
                          <input type="text" value={c.value} onChange={e => {
                            const newCond = [...conditions];
                            newCond[i].value = e.target.value;
                            setConditions(newCond);
                          }} className="flex-1 p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700 font-mono text-sm" placeholder="Value or Regex" />
                          
                          <button onClick={() => setConditions(conditions.filter((_, idx) => idx !== i))} className="p-2 text-red-500 hover:bg-red-100 rounded-md">
                            <X className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                      
                      <button onClick={() => setConditions([...conditions, { field: 'MESSAGE_TEXT', operator: 'MATCHES_REGEX', value: '' }])} className="bg-orange-600 hover:bg-orange-500 text-slate-900 dark:text-white px-3 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1 transition w-fit">
                        <Plus className="w-4 h-4" /> Add Trigger
                      </button>
                    </div>

                    <div className="mt-4 pt-4 border-t border-orange-200 dark:border-orange-900/50">
                      <p className="text-xs text-orange-700 dark:text-orange-500 mb-2 font-medium">Regex Snippets:</p>
                      <div className="flex flex-wrap gap-2">
                        {REGEX_SNIPPETS.map((snip, i) => (
                          <button key={i} title={snip.desc} onClick={() => setConditions([...conditions, { field: 'MESSAGE_TEXT', operator: 'MATCHES_REGEX', value: snip.regex }])} className="text-xs bg-white dark:bg-[#1a1a2e] border border-slate-200 dark:border-slate-600 px-2 py-1 rounded hover:bg-slate-100 dark:hover:bg-slate-100 dark:bg-slate-700 flex items-center gap-1 transition-colors">
                            <Code className="w-3 h-3 text-blue-500" /> {snip.name}
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>

                  {/* Actions */}
                  <div className="bg-emerald-50 dark:bg-emerald-950/20 border border-emerald-200 dark:border-emerald-900/50 rounded-xl p-5">
                    <h3 className="text-lg font-semibold text-emerald-800 dark:text-emerald-400 mb-4">
                      Then Do (Actions)
                    </h3>
                    
                    <div className="space-y-3">
                      {actions.map((a, i) => (
                        <div key={i} className="flex gap-2 items-center bg-white dark:bg-[#1a1a2e] p-2 rounded-lg border border-slate-200 dark:border-slate-700">
                          <select value={a.actionType} onChange={e => {
                            const newActs = [...actions];
                            newActs[i].actionType = e.target.value;
                            if (e.target.value === 'FAKE_DLR') newActs[i].actionValue = 'DELIVRD';
                            else if (e.target.value === 'DROP') newActs[i].actionValue = 'DROP';
                            else newActs[i].actionValue = '';
                            setActions(newActs);
                          }} className="p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700 font-medium text-emerald-600">
                            <option value="REWRITE_TEXT">Rewrite Message Text</option>
                            <option value="REWRITE_SOURCE">Rewrite Source Address</option>
                            <option value="OVERRIDE_SMSC">Route to SMSC (Override)</option>
                            <option value="FAKE_DLR">Provide Fake DLR & Terminate</option>
                            <option value="DROP">Drop Message silently & Terminate</option>
                          </select>
                          
                          {a.actionType === 'FAKE_DLR' ? (
                            <select value={a.actionValue} onChange={e => {
                              const newActs = [...actions];
                              newActs[i].actionValue = e.target.value;
                              setActions(newActs);
                            }} className="flex-1 p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700 font-mono text-sm">
                              <option value="DELIVRD">DELIVRD (Delivered - Billed)</option>
                              <option value="REJECTD">REJECTD (Rejected)</option>
                              <option value="UNDELIV">UNDELIV (Undeliverable)</option>
                              <option value="EXPIRED">EXPIRED (Expired)</option>
                              <option value="DELETED">DELETED (Deleted)</option>
                              <option value="ACCEPTD">ACCEPTD (Accepted)</option>
                              <option value="UNKNOWN">UNKNOWN (Unknown)</option>
                            </select>
                          ) : a.actionType === 'DROP' ? (
                            <div className="flex-1 p-2 text-sm text-slate-700 dark:text-slate-500 italic">No configuration needed. Message will be silently dropped.</div>
                          ) : (
                            <input type="text" value={a.actionValue} onChange={e => {
                              const newActs = [...actions];
                              newActs[i].actionValue = e.target.value;
                              setActions(newActs);
                            }} className="flex-1 p-2 border rounded-md dark:bg-[#12121f] dark:border-slate-700 font-mono text-sm" placeholder={a.actionType.startsWith('REWRITE') ? 'search_regex|||replacement OR replacement' : 'Value (e.g. SMSC ID)'} />
                          )}
                          
                          <button onClick={() => setActions(actions.filter((_, idx) => idx !== i))} className="p-2 text-red-500 hover:bg-red-100 rounded-md">
                            <X className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                      
                      <button onClick={() => setActions([...actions, { actionType: 'REWRITE_TEXT', actionValue: '' }])} className="bg-emerald-600 hover:bg-emerald-500 text-white px-3 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1 transition w-fit">
                        <Plus className="w-4 h-4" /> Add Action
                      </button>
                    </div>
                  </div>

                </div>
              )}

              {activeTab === 'TESTER' && (
                <div className="p-6 flex flex-col lg:flex-row gap-6 h-full">
                  <div className="flex-1 space-y-4">
                    <h3 className="font-semibold text-lg flex items-center gap-2">
                      <Play className="w-5 h-5 text-blue-500" /> Send Dummy Payload
                    </h3>
                    <p className="text-sm text-slate-700 dark:text-slate-500">Test how the active rules in the system will evaluate this message. Save your rule first to include it.</p>
                    
                    <div>
                      <label className="block text-sm font-medium mb-1">Client System ID</label>
                      <input type="text" value={testPayload.systemId} onChange={e => setTestPayload({...testPayload, systemId: e.target.value})} className="w-full bg-slate-50 dark:bg-[#12121f] border rounded-lg p-2" />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Source Address</label>
                      <input type="text" value={testPayload.sourceAddress} onChange={e => setTestPayload({...testPayload, sourceAddress: e.target.value})} className="w-full bg-slate-50 dark:bg-[#12121f] border rounded-lg p-2" />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Destination Address</label>
                      <input type="text" value={testPayload.destinationAddress} onChange={e => setTestPayload({...testPayload, destinationAddress: e.target.value})} className="w-full bg-slate-50 dark:bg-[#12121f] border rounded-lg p-2" />
                    </div>
                    <div>
                      <label className="block text-sm font-medium mb-1">Message Text</label>
                      <textarea value={testPayload.messageText} onChange={e => setTestPayload({...testPayload, messageText: e.target.value})} className="w-full bg-slate-50 dark:bg-[#12121f] border rounded-lg p-2 h-24" />
                    </div>

                    <button onClick={handleTest} className="w-full bg-slate-50 dark:bg-slate-800 dark:bg-slate-100 text-slate-900 dark:text-white dark:text-slate-900 py-3 rounded-xl font-bold flex justify-center items-center gap-2 hover:bg-slate-100 dark:bg-slate-700 transition-colors">
                      <Play className="w-4 h-4" /> Execute Dry Run
                    </button>
                  </div>
                  
                  <div className="flex-1 bg-white dark:bg-slate-900 rounded-xl p-4 text-emerald-400 font-mono text-sm overflow-y-auto">
                    <h4 className="text-slate-900 dark:text-white mb-2 pb-2 border-b border-slate-700">Execution Result:</h4>
                    {testResult ? (
                      <pre className="whitespace-pre-wrap">
                        {JSON.stringify(testResult, null, 2)}
                      </pre>
                    ) : (
                      <span className="text-slate-700 dark:text-slate-500">No result yet. Run the test to see output.</span>
                    )}
                  </div>
                </div>
              )}

              {activeTab === 'FLOWCHART' && (
                <div className="w-full h-full p-4 bg-slate-50 dark:bg-[#12121f]">
                  <div className="w-full h-full border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden bg-white dark:bg-[#1a1a2e]">
                    <ReactFlow 
                      nodes={getFlowNodes().nodes}
                      edges={getFlowNodes().edges}
                      fitView
                    >
                      <Background />
                      <Controls />
                    </ReactFlow>
                  </div>
                </div>
              )}
            </div>

            <div className="p-4 border-t border-slate-200 dark:border-slate-700 flex justify-end gap-3 bg-white dark:bg-[#1a1a2e]">
              <button onClick={() => setIsModalOpen(false)} className="px-4 py-2 text-slate-600 font-medium hover:bg-slate-100 dark:hover:bg-slate-100 dark:bg-slate-700 rounded-lg transition-colors">
                Cancel
              </button>
              <button onClick={handleSave} className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-slate-900 dark:text-white font-bold rounded-lg flex items-center gap-2 shadow-lg shadow-blue-500/30 transition-all">
                <Save className="w-4 h-4" /> Save Rule
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
