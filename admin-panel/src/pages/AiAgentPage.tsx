import { useState, useRef, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAiAgentConfig, updateAiAgentConfig, testAiAgent, chatWithAiAgent, getAiChatHistory, clearAiChatHistory } from '../api/client'
import { Bot, Save, Zap, Key, Eye, EyeOff, Send, Settings, MessageSquare, Loader2, Trash2, AlertCircle } from 'lucide-react'

const PROVIDERS = [
  { value: 'GEMINI', label: 'Google Gemini', models: ['gemini-2.0-flash', 'gemini-2.5-pro', 'gemini-2.5-flash'] },
  { value: 'CLAUDE', label: 'Anthropic Claude', models: ['claude-sonnet-4-20250514', 'claude-3-5-haiku-20241022'] },
  { value: 'OPENAI', label: 'OpenAI', models: ['gpt-4o', 'gpt-4o-mini', 'o3-mini'] },
]

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp: Date
}

// Minimal markdown-to-HTML renderer for assistant messages
function renderMarkdown(text: string) {
  return text
    // Code blocks (```lang ... ```)
    .replace(/```(\w*)\n?([\s\S]*?)```/g, '<pre class="bg-black/40 rounded px-3 py-2 my-2 text-[11px] overflow-x-auto"><code>$2</code></pre>')
    // Inline code
    .replace(/`([^`]+)`/g, '<code class="bg-white/10 px-1 py-0.5 rounded text-[11px] text-amber-300">$1</code>')
    // Bold
    .replace(/\*\*(.+?)\*\*/g, '<strong class="text-white font-semibold">$1</strong>')
    // Italic
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // Headers
    .replace(/^### (.+)$/gm, '<div class="text-white font-bold text-sm mt-3 mb-1">$1</div>')
    .replace(/^## (.+)$/gm, '<div class="text-white font-bold mt-3 mb-1">$1</div>')
    .replace(/^# (.+)$/gm, '<div class="text-white font-bold text-lg mt-3 mb-1">$1</div>')
    // Bullet lists
    .replace(/^[-•] (.+)$/gm, '<div class="flex gap-2 ml-2"><span class="text-brand-400 shrink-0">•</span><span>$1</span></div>')
    // Numbered lists
    .replace(/^\d+\. (.+)$/gm, '<div class="flex gap-2 ml-2"><span class="text-brand-400 shrink-0">→</span><span>$1</span></div>')
    // Line breaks
    .replace(/\n\n/g, '<div class="h-2"></div>')
    .replace(/\n/g, '<br/>')
}

export default function AiAgentPage() {
  const qc = useQueryClient()
  const [showKey, setShowKey] = useState(false)
  const [form, setForm] = useState<any>(null)
  const [testResult, setTestResult] = useState<any>(null)
  const [activeTab, setActiveTab] = useState<'chat' | 'settings'>('chat')

  // Chat state
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [isThinking, setIsThinking] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const { data: config, isLoading } = useQuery({
    queryKey: ['ai-config'],
    queryFn: getAiAgentConfig,
  })

  if (config && !form) {
    setForm({ provider: config.provider, modelName: config.modelName, enabled: config.enabled, apiKey: '' })
  }

  const saveMut = useMutation({
    mutationFn: updateAiAgentConfig,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ai-config'] }),
  })

  const testMut = useMutation({
    mutationFn: testAiAgent,
    onSuccess: (d: any) => setTestResult(d),
  })

  // Load persisted chat history on mount
  useEffect(() => {
    getAiChatHistory().then((history: any[]) => {
      if (history && history.length > 0) {
        setMessages(history.map((m: any) => ({
          role: m.role as 'user' | 'assistant',
          content: m.content,
          timestamp: new Date(m.createdAt),
        })))
      }
    }).catch(() => { /* first load, no history */ })
  }, [])

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isThinking])

  const sendMessage = async () => {
    const text = input.trim()
    if (!text || isThinking) return

    const userMsg: ChatMessage = { role: 'user', content: text, timestamp: new Date() }
    const updatedMessages = [...messages, userMsg]
    setMessages(updatedMessages)
    setInput('')
    setIsThinking(true)

    // Resize textarea back
    if (inputRef.current) inputRef.current.style.height = '44px'

    try {
      const history = updatedMessages.map(m => ({ role: m.role, content: m.content }))
      const data = await chatWithAiAgent(history)

      if (data.error) {
        setMessages([...updatedMessages, {
          role: 'assistant',
          content: `⚠️ **Error:** ${data.error}`,
          timestamp: new Date()
        }])
      } else {
        setMessages([...updatedMessages, {
          role: 'assistant',
          content: data.reply,
          timestamp: new Date()
        }])
      }
    } catch (err: any) {
      setMessages([...updatedMessages, {
        role: 'assistant',
        content: `⚠️ **Error:** ${err?.response?.data?.error || err?.message || 'Failed to get response'}`,
        timestamp: new Date()
      }])
    } finally {
      setIsThinking(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  const handleTextareaInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
    // Auto-resize
    const ta = e.target
    ta.style.height = '44px'
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px'
  }

  if (isLoading || !form) return <div className="p-6 text-slate-500">Loading AI agent config…</div>

  const isConfigured = config?.enabled && config?.apiKey

  return (
    <div className="flex flex-col h-[calc(100vh-48px)]">
      {/* Header */}
      <div className="shrink-0 px-6 py-4 border-b border-white/5">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-white flex items-center gap-2">
              <Bot size={22} className="text-brand-400" /> AI Agent
            </h1>
            <p className="text-slate-500 text-xs mt-0.5">
              {isConfigured
                ? <span className="text-emerald-400">● Connected to {config.provider} / {config.modelName}</span>
                : <span className="text-amber-400">● Not configured — go to Settings</span>}
            </p>
          </div>
          <div className="flex gap-1">
            <button
              className={`px-3 py-1.5 rounded text-xs font-medium flex items-center gap-1.5 transition ${activeTab === 'chat' ? 'bg-brand-600/20 text-brand-400 border border-brand-500/30' : 'text-slate-400 hover:text-white hover:bg-white/5'}`}
              onClick={() => setActiveTab('chat')}>
              <MessageSquare size={13} /> Chat
            </button>
            <button
              className={`px-3 py-1.5 rounded text-xs font-medium flex items-center gap-1.5 transition ${activeTab === 'settings' ? 'bg-brand-600/20 text-brand-400 border border-brand-500/30' : 'text-slate-400 hover:text-white hover:bg-white/5'}`}
              onClick={() => setActiveTab('settings')}>
              <Settings size={13} /> Settings
            </button>
          </div>
        </div>
      </div>

      {/* ─── Chat Tab ─── */}
      {activeTab === 'chat' && (
        <div className="flex-1 flex flex-col min-h-0">
          {/* Messages area */}
          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
            {messages.length === 0 && !isThinking && (
              <div className="flex-1 flex items-center justify-center py-20">
                <div className="text-center max-w-md">
                  <div className="w-16 h-16 rounded-2xl bg-brand-600/10 border border-brand-500/20 flex items-center justify-center mx-auto mb-4">
                    <Bot size={28} className="text-brand-400" />
                  </div>
                  <h2 className="text-white font-semibold mb-2">Platform AI Assistant</h2>
                  <p className="text-slate-500 text-sm mb-6">
                    I have access to your platform's real-time metrics — health status, device fleet, message pipeline, and SMSC connections. Ask me anything.
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {[
                      'What is the current system health?',
                      'Are any devices offline?',
                      'What is the delivery rate?',
                      'How many messages are queued?',
                    ].map(q => (
                      <button key={q}
                        className="px-3 py-2 rounded-lg text-xs text-left text-slate-400 bg-white/[0.03] border border-white/5 hover:border-brand-500/30 hover:text-brand-400 transition"
                        onClick={() => { setInput(q); inputRef.current?.focus() }}>
                        {q}
                      </button>
                    ))}
                  </div>
                  {!isConfigured && (
                    <div className="mt-6 p-3 rounded-lg bg-amber-900/10 border border-amber-500/20 text-amber-400 text-xs flex items-center gap-2">
                      <AlertCircle size={14} />
                      Configure an AI provider in Settings before chatting.
                    </div>
                  )}
                </div>
              </div>
            )}

            {messages.map((msg, i) => (
              <div key={i} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                {msg.role === 'assistant' && (
                  <div className="shrink-0 w-7 h-7 rounded-lg bg-brand-600/15 border border-brand-500/20 flex items-center justify-center mt-1">
                    <Bot size={14} className="text-brand-400" />
                  </div>
                )}
                <div className={`max-w-[85%] sm:max-w-[75%] ${
                  msg.role === 'user'
                    ? 'bg-brand-600/20 border border-brand-500/20 rounded-2xl rounded-tr-sm px-4 py-2.5'
                    : 'bg-white/[0.03] border border-white/5 rounded-2xl rounded-tl-sm px-4 py-2.5'
                }`}>
                  {msg.role === 'user' ? (
                    <div className="text-sm text-white whitespace-pre-wrap">{msg.content}</div>
                  ) : (
                    <div className="text-sm text-slate-300 leading-relaxed ai-response"
                      dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.content) }} />
                  )}
                  <div className="text-[9px] text-slate-600 mt-1 text-right">
                    {msg.timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  </div>
                </div>
              </div>
            ))}

            {/* Thinking indicator */}
            {isThinking && (
              <div className="flex gap-3">
                <div className="shrink-0 w-7 h-7 rounded-lg bg-brand-600/15 border border-brand-500/20 flex items-center justify-center mt-1">
                  <Bot size={14} className="text-brand-400" />
                </div>
                <div className="bg-white/[0.03] border border-white/5 rounded-2xl rounded-tl-sm px-4 py-3">
                  <div className="flex items-center gap-2 text-sm text-slate-500">
                    <Loader2 size={14} className="animate-spin text-brand-400" />
                    Analyzing system metrics…
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Input area */}
          <div className="shrink-0 px-6 pb-4 pt-2 border-t border-white/5">
            <div className="flex gap-2 items-end">
              {messages.length > 0 && (
                <button
                  className="shrink-0 p-2.5 rounded-lg text-slate-600 hover:text-red-400 hover:bg-red-900/10 transition"
                  title="Clear chat"
                  onClick={() => setMessages([])}>
                  <Trash2 size={16} />
                </button>
              )}
              <div className="flex-1 relative">
                <textarea
                  ref={inputRef}
                  className="w-full bg-white/[0.04] border border-white/10 rounded-xl px-4 py-3 pr-12 text-sm text-white resize-none focus:outline-none focus:border-brand-500/40 placeholder:text-slate-600 transition"
                  style={{ height: '44px', maxHeight: '120px' }}
                  placeholder={isConfigured ? 'Ask about system health, devices, delivery rates…' : 'Configure AI provider in Settings first…'}
                  value={input}
                  onChange={handleTextareaInput}
                  onKeyDown={handleKeyDown}
                  disabled={!isConfigured || isThinking}
                />
                <button
                  className={`absolute right-2 bottom-2 p-1.5 rounded-lg transition ${
                    input.trim() && isConfigured && !isThinking
                      ? 'bg-brand-600 text-white hover:bg-brand-500'
                      : 'text-slate-700 cursor-not-allowed'
                  }`}
                  onClick={sendMessage}
                  disabled={!input.trim() || !isConfigured || isThinking}>
                  <Send size={16} />
                </button>
              </div>
            </div>
            <div className="text-[9px] text-slate-700 mt-1.5 text-center">
              AI has access to real-time metrics. Responses may take a few seconds.
            </div>
          </div>
        </div>
      )}

      {/* ─── Settings Tab ─── */}
      {activeTab === 'settings' && (
        <div className="flex-1 overflow-y-auto">
          <div className="p-6 space-y-6 max-w-3xl">

            <div className="glass p-6 space-y-5">
              {/* Enable Toggle */}
              <div className="flex items-center justify-between p-3 rounded-lg bg-white/[0.02] border border-white/5">
                <div>
                  <div className="text-sm font-medium text-white">AI Agent</div>
                  <div className="text-[10px] text-slate-500">Enable AI-powered system analysis and recommendations</div>
                </div>
                <label className="relative inline-flex items-center cursor-pointer">
                  <input type="checkbox" className="sr-only peer" checked={form.enabled}
                    onChange={e => setForm({ ...form, enabled: e.target.checked })} />
                  <div className="w-11 h-6 bg-slate-700 peer-checked:bg-brand-600 rounded-full transition-colors after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:after:translate-x-5"></div>
                </label>
              </div>

              {/* Provider */}
              <div>
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">AI Provider</label>
                <div className="grid grid-cols-3 gap-2">
                  {PROVIDERS.map(p => (
                    <button key={p.value}
                      className={`p-3 rounded-lg border text-sm font-medium text-center transition ${form.provider === p.value ? 'bg-brand-600/20 border-brand-500/40 text-brand-400' : 'bg-white/[0.02] border-white/5 text-slate-400 hover:border-white/10 hover:text-white'}`}
                      onClick={() => setForm({ ...form, provider: p.value, modelName: p.models[0] })}>
                      {p.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Model */}
              <div>
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">Model</label>
                <select className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2"
                  value={form.modelName} onChange={e => setForm({ ...form, modelName: e.target.value })}>
                  {(PROVIDERS.find(p => p.value === form.provider) ?? PROVIDERS[0]).models.map(m => <option key={m} value={m}>{m}</option>)}
                </select>
              </div>

              {/* API Key */}
              <div>
                <label className="block text-[10px] font-bold text-slate-500 uppercase mb-1">API Key</label>
                <div className="flex gap-2">
                  <div className="relative flex-1">
                    <input
                      type={showKey ? 'text' : 'password'}
                      className="w-full bg-[#0d0d18] text-sm text-white border border-white/10 rounded px-3 py-2 pr-10 font-mono"
                      placeholder={config?.apiKey ? '••••••••••••••• (key is set)' : 'Enter API key…'}
                      value={form.apiKey}
                      onChange={e => setForm({ ...form, apiKey: e.target.value })}
                    />
                    <button className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-white transition"
                      onClick={() => setShowKey(!showKey)} type="button">
                      {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                  </div>
                </div>
                <div className="text-[10px] text-slate-600 mt-1 flex items-center gap-1">
                  <Key size={10} /> Key is stored on the backend. Not visible after saving.
                </div>
              </div>

              {/* Actions */}
              <div className="flex items-center gap-3 pt-2">
                <button
                  className="bg-brand-600 hover:bg-brand-500 text-white rounded px-5 py-2 text-sm font-medium transition flex items-center gap-2 disabled:opacity-40"
                  disabled={saveMut.isPending}
                  onClick={() => saveMut.mutate(form)}>
                  <Save size={14} /> {saveMut.isPending ? 'Saving…' : 'Save Configuration'}
                </button>
                <button
                  className="btn-secondary"
                  onClick={() => testMut.mutate()}>
                  <Zap size={14} /> Test Connection
                </button>
              </div>

              {/* Test Result */}
              {testResult && (
                <div className={`p-3 rounded border text-sm ${testResult.status === 'OK' ? 'bg-emerald-900/20 border-emerald-500/20 text-emerald-400' : 'bg-red-900/20 border-red-500/20 text-red-400'}`}>
                  <strong>{testResult.status}:</strong> {testResult.message}
                  {testResult.provider && <span className="text-slate-500 ml-2">({testResult.provider} / {testResult.model})</span>}
                </div>
              )}

              {/* Status indicator */}
              {config && (
                <div className="text-[10px] text-slate-600 border-t border-white/5 pt-3 mt-2">
                  Current: <span className="text-slate-400">{config.provider}</span> / <span className="text-slate-400">{config.modelName}</span>
                  {config.apiKey && <span className="text-emerald-500 ml-2">✓ Key configured</span>}
                  {!config.apiKey && <span className="text-red-500 ml-2">✗ No key</span>}
                  <span className="ml-2">{config.enabled ? '🟢 Enabled' : '🔴 Disabled'}</span>
                </div>
              )}
            </div>

            {/* Info Panel */}
            <div className="glass p-5">
              <h2 className="text-sm font-bold text-white mb-3">How it works</h2>
              <div className="text-xs text-slate-400 space-y-2">
                <p>The AI Agent connects to your chosen LLM provider and has access to:</p>
                <ul className="list-disc pl-4 space-y-1">
                  <li>Real-time system health (CPU, RAM, disk, JVM metrics)</li>
                  <li>Device fleet status (online/offline per group)</li>
                  <li>Message pipeline stats (delivery rates, queue depth)</li>
                  <li>SMSC supplier connection status</li>
                  <li>Infrastructure component health (Postgres, Redis, Kafka)</li>
                </ul>
                <p className="text-slate-500">When enabled, the agent can diagnose issues, suggest fixes, and provide system maintenance recommendations.</p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
