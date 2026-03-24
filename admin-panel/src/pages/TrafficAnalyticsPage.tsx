import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getAnalyticsBySender, getAnalyticsByContent, getSpamSuspects, getAitSuspects } from '../api/client'
import { PieChart, AlertTriangle, Zap, Users, FileText, ShieldAlert } from 'lucide-react'

const WINDOWS = [
  { value: '1h', label: '1 hour' },
  { value: '24h', label: '24 hours' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
]

type Tab = 'sender' | 'content' | 'spam' | 'ait'

const TABS: { id: Tab; label: string; icon: any; color: string }[] = [
  { id: 'sender', label: 'By Sender ID', icon: Users, color: 'text-brand-400' },
  { id: 'content', label: 'By Content', icon: FileText, color: 'text-sky-400' },
  { id: 'spam', label: 'Spam Suspects', icon: AlertTriangle, color: 'text-amber-400' },
  { id: 'ait', label: 'AIT Suspects', icon: ShieldAlert, color: 'text-red-400' },
]

const REASON_STYLES: Record<string, { bg: string; text: string }> = {
  HIGH_VOLUME: { bg: 'bg-amber-500/20', text: 'text-amber-400' },
  REPETITIVE_CONTENT: { bg: 'bg-orange-500/20', text: 'text-orange-400' },
  BURST_RATE: { bg: 'bg-red-500/20', text: 'text-red-400' },
  HIGH_FAILURE_RATE: { bg: 'bg-red-500/20', text: 'text-red-400' },
  SEQUENTIAL_NUMBERS: { bg: 'bg-violet-500/20', text: 'text-violet-400' },
  NARROW_RANGE: { bg: 'bg-pink-500/20', text: 'text-pink-400' },
}

function ScoreBadge({ score }: { score: number }) {
  const color = score >= 70 ? 'text-red-400 bg-red-500/20' : score >= 40 ? 'text-amber-400 bg-amber-500/20' : 'text-slate-400 bg-white/5'
  return <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${color}`}>{score}/100</span>
}

function ReasonBadge({ reason }: { reason: string }) {
  const style = REASON_STYLES[reason] || { bg: 'bg-white/5', text: 'text-slate-400' }
  return <span className={`px-2 py-0.5 rounded text-[10px] font-mono ${style.bg} ${style.text}`}>{reason.replace(/_/g, ' ')}</span>
}

export default function TrafficAnalyticsPage() {
  const [tab, setTab] = useState<Tab>('sender')
  const [window, setWindow] = useState('24h')

  const { data: senderData, isLoading: senderLoading } = useQuery({
    queryKey: ['analytics-sender', window],
    queryFn: () => getAnalyticsBySender(window),
    enabled: tab === 'sender',
  })

  const { data: contentData, isLoading: contentLoading } = useQuery({
    queryKey: ['analytics-content', window],
    queryFn: () => getAnalyticsByContent(window),
    enabled: tab === 'content',
  })

  const { data: spamData, isLoading: spamLoading } = useQuery({
    queryKey: ['analytics-spam', window],
    queryFn: () => getSpamSuspects(window),
    enabled: tab === 'spam',
  })

  const { data: aitData, isLoading: aitLoading } = useQuery({
    queryKey: ['analytics-ait', window],
    queryFn: () => getAitSuspects(window),
    enabled: tab === 'ait',
  })

  return (
    <div className="p-6 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <PieChart size={22} className="text-brand-400" /> Traffic Analytics
          </h1>
          <p className="text-slate-500 text-xs mt-0.5">BI reporting, spam detection & AIT analysis</p>
        </div>
        <div className="flex gap-1">
          {WINDOWS.map(w => (
            <button key={w.value}
              className={`px-3 py-1.5 rounded text-xs font-medium transition ${window === w.value ? 'bg-brand-600/20 text-brand-400 border border-brand-500/30' : 'text-slate-400 hover:text-white bg-white/5'}`}
              onClick={() => setWindow(w.value)}>
              {w.label}
            </button>
          ))}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-white/[0.07] pb-0">
        {TABS.map(t => {
          const Icon = t.icon
          return (
            <button key={t.id}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-xs font-medium transition border-b-2 -mb-[1px]
                ${tab === t.id ? `${t.color} border-current` : 'text-slate-500 border-transparent hover:text-slate-300'}`}
              onClick={() => setTab(t.id)}>
              <Icon size={14} />
              {t.label}
            </button>
          )
        })}
      </div>

      {/* Content */}
      <div className="glass overflow-hidden">
        {tab === 'sender' && (
          senderLoading ? <Loading /> : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                  <th className="px-4 py-2 text-left">Sender ID</th>
                  <th className="px-4 py-2 text-right">Total</th>
                  <th className="px-4 py-2 text-right">Delivered</th>
                  <th className="px-4 py-2 text-right">Failed</th>
                  <th className="px-4 py-2 text-right">Delivery %</th>
                  <th className="px-4 py-2 text-right">Unique Dests</th>
                  <th className="px-4 py-2 text-left">First Seen</th>
                  <th className="px-4 py-2 text-left">Last Seen</th>
                </tr>
              </thead>
              <tbody>
                {(senderData ?? []).map((s: any, i: number) => (
                  <tr key={i} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                    <td className="px-4 py-2 text-white font-mono text-xs">{s.sender}</td>
                    <td className="px-4 py-2 text-right text-white text-xs font-bold">{s.total?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right text-emerald-400 text-xs">{s.delivered?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right text-red-400 text-xs">{s.failed?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right">
                      <RateBadge rate={s.deliveryRate} />
                    </td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{s.uniqueDestinations}</td>
                    <td className="px-4 py-2 text-slate-500 text-xs">{formatTime(s.firstSeen)}</td>
                    <td className="px-4 py-2 text-slate-500 text-xs">{formatTime(s.lastSeen)}</td>
                  </tr>
                ))}
                {(senderData ?? []).length === 0 && <EmptyRow cols={8} />}
              </tbody>
            </table>
          )
        )}

        {tab === 'content' && (
          contentLoading ? <Loading /> : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                  <th className="px-4 py-2 text-left">Message Content</th>
                  <th className="px-4 py-2 text-right">Count</th>
                  <th className="px-4 py-2 text-right">Unique Dests</th>
                  <th className="px-4 py-2 text-right">Senders</th>
                  <th className="px-4 py-2 text-left">First Seen</th>
                  <th className="px-4 py-2 text-left">Last Seen</th>
                </tr>
              </thead>
              <tbody>
                {(contentData ?? []).map((c: any, i: number) => (
                  <tr key={i} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                    <td className="px-4 py-2 text-slate-300 text-xs max-w-[400px]">
                      <div className="truncate" title={c.fullContent}>{c.content}</div>
                    </td>
                    <td className="px-4 py-2 text-right text-white text-xs font-bold">{c.total?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{c.uniqueDestinations}</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{c.uniqueSenders}</td>
                    <td className="px-4 py-2 text-slate-500 text-xs">{formatTime(c.firstSeen)}</td>
                    <td className="px-4 py-2 text-slate-500 text-xs">{formatTime(c.lastSeen)}</td>
                  </tr>
                ))}
                {(contentData ?? []).length === 0 && <EmptyRow cols={6} />}
              </tbody>
            </table>
          )
        )}

        {tab === 'spam' && (
          spamLoading ? <Loading /> : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                  <th className="px-4 py-2 text-left">Sender ID</th>
                  <th className="px-4 py-2 text-right">Score</th>
                  <th className="px-4 py-2 text-left">Reasons</th>
                  <th className="px-4 py-2 text-right">Total Msgs</th>
                  <th className="px-4 py-2 text-right">Delivery %</th>
                  <th className="px-4 py-2 text-right">Repetition %</th>
                  <th className="px-4 py-2 text-right">Max Burst/min</th>
                </tr>
              </thead>
              <tbody>
                {(spamData ?? []).map((s: any, i: number) => (
                  <tr key={i} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                    <td className="px-4 py-2 text-white font-mono text-xs">{s.sender}</td>
                    <td className="px-4 py-2 text-right"><ScoreBadge score={s.score} /></td>
                    <td className="px-4 py-2">
                      <div className="flex gap-1 flex-wrap">
                        {(s.reasons ?? []).map((r: string) => <ReasonBadge key={r} reason={r} />)}
                      </div>
                    </td>
                    <td className="px-4 py-2 text-right text-white text-xs font-bold">{s.total?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right"><RateBadge rate={s.deliveryRate} /></td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{s.repetitionRate}%</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{s.maxBurstPerMin}</td>
                  </tr>
                ))}
                {(spamData ?? []).length === 0 && (
                  <tr><td colSpan={7} className="px-4 py-8 text-center text-emerald-400/60 text-sm">
                    ✓ No spam suspects detected in this window
                  </td></tr>
                )}
              </tbody>
            </table>
          )
        )}

        {tab === 'ait' && (
          aitLoading ? <Loading /> : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-[10px] uppercase text-slate-500 border-b border-white/5">
                  <th className="px-4 py-2 text-left">Sender ID</th>
                  <th className="px-4 py-2 text-right">Score</th>
                  <th className="px-4 py-2 text-left">Reasons</th>
                  <th className="px-4 py-2 text-right">Total Msgs</th>
                  <th className="px-4 py-2 text-right">Fail Rate %</th>
                  <th className="px-4 py-2 text-right">Unique Dests</th>
                  <th className="px-4 py-2 text-right">Number Range</th>
                  <th className="px-4 py-2 text-right">Seq. Score %</th>
                </tr>
              </thead>
              <tbody>
                {(aitData ?? []).map((a: any, i: number) => (
                  <tr key={i} className="border-b border-white/[0.03] hover:bg-white/[0.02]">
                    <td className="px-4 py-2 text-white font-mono text-xs">{a.sender}</td>
                    <td className="px-4 py-2 text-right"><ScoreBadge score={a.score} /></td>
                    <td className="px-4 py-2">
                      <div className="flex gap-1 flex-wrap">
                        {(a.reasons ?? []).map((r: string) => <ReasonBadge key={r} reason={r} />)}
                      </div>
                    </td>
                    <td className="px-4 py-2 text-right text-white text-xs font-bold">{a.total?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right text-red-400 text-xs">{a.failRate}%</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{a.uniqueDestinations}</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs font-mono">{a.numberRange?.toLocaleString()}</td>
                    <td className="px-4 py-2 text-right text-slate-400 text-xs">{a.sequentialScore}%</td>
                  </tr>
                ))}
                {(aitData ?? []).length === 0 && (
                  <tr><td colSpan={8} className="px-4 py-8 text-center text-emerald-400/60 text-sm">
                    ✓ No AIT suspects detected in this window
                  </td></tr>
                )}
              </tbody>
            </table>
          )
        )}
      </div>
    </div>
  )
}

function RateBadge({ rate }: { rate: number }) {
  const color = rate >= 90 ? 'text-emerald-400' : rate >= 70 ? 'text-amber-400' : 'text-red-400'
  return <span className={`text-xs font-mono ${color}`}>{rate}%</span>
}

function Loading() {
  return <div className="px-4 py-8 text-center text-slate-500 text-sm">Loading analytics…</div>
}

function EmptyRow({ cols }: { cols: number }) {
  return <tr><td colSpan={cols} className="px-4 py-8 text-center text-slate-600 text-sm">No data in this time window</td></tr>
}

function formatTime(iso: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}
