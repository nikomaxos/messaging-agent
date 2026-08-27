import { useState, useEffect } from 'react'
import { Outlet, NavLink, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  LayoutDashboard, Smartphone, FileText, LogOut, Users, Route as RouteIcon,
  Server, UserCog, Activity, Bell, Bot, Skull, Shield, BarChart3, PieChart,
  PanelLeftClose, PanelLeftOpen, Rocket, ChevronDown, Globe, Database, DollarSign, Sun, Moon
} from 'lucide-react'
import PushSubscriptionPrompt from './PushSubscriptionPrompt'
import NotificationBell from './NotificationBell'

const nav = [
  { to: '/dashboard',     icon: <LayoutDashboard size={20} />, label: 'Dashboard' },
  { to: '/ai-agent',      icon: <Bot size={20} className="text-emerald-400" />, label: 'AI Agent' },
  { to: '/devices',       icon: <Smartphone size={20} />,      label: 'Devices' },
  
  {
    icon: <Server size={20} className="text-blue-400" />, label: 'SMPP',
    children: [
      { to: '/smpp/server',   icon: <Server size={20} />,          label: 'SMPP Server' },
      { to: '/smpp/clients',  icon: <Users size={20} />,           label: 'SMPP Clients' },
      { to: '/smscs',         icon: <Server size={20} className="text-blue-400" />, label: 'SMSc Suppliers' },
      { to: '/smpp/proxy',    icon: <Globe size={20} className="text-purple-400" />, label: 'SMPP Proxy' },
    ]
  },
  
  {
    icon: <RouteIcon size={20} />, label: 'Routing',
    children: [
      { to: '/smpp/rules',    icon: <RouteIcon size={20} className="text-orange-400" />, label: 'Rules Engine' },
      { to: '/smpp/routing',  icon: <RouteIcon size={20} />,       label: 'Routing Configuration' },
      { to: '/smpp/rate-limits', icon: <Activity size={20} />,     label: 'Rate Limits' },
    ]
  },

  {
    icon: <FileText size={20} className="text-fuchsia-400" />, label: 'Reports',
    children: [
      { to: '/logs/messages', icon: <FileText size={20} className="text-amber-600 dark:text-amber-400" />, label: 'Message Tracking' },
      { to: '/analytics',    icon: <PieChart size={20} className="text-fuchsia-400" />, label: 'Traffic Analytics' },
      { to: '/dead-letters',  icon: <Skull size={20} className="text-red-400" />, label: 'Dead-Letter Queue' },
      { to: '/throughput',    icon: <BarChart3 size={20} className="text-orange-400" />, label: 'Throughput' },
    ]
  },

  { to: '/testing',       icon: <Activity size={20} className="text-yellow-400" />, label: 'Testing' },

  {
    icon: <Shield size={20} className="text-slate-600 dark:text-slate-400" />, label: 'Administration',
    children: [
      { to: '/accounts',      icon: <Users size={20} className="text-blue-400" />, label: 'Accounts' },
      { to: '/billing',       icon: <DollarSign size={20} className="text-emerald-400" />, label: 'Billing & Rating' },
      { to: '/deploy',        icon: <Rocket size={20} className="text-teal-400" />, label: 'Auto-Deploy' },
      { to: '/backup-restore',icon: <Database size={20} className="text-emerald-400" />, label: 'Backup/Restore' },
      { to: '/users',          icon: <UserCog size={20} className="text-rose-400" />, label: 'Users' },
      { to: '/infrastructure', icon: <Activity size={20} className="text-cyan-400" />, label: 'Infrastructure' },
      { to: '/logs/system',   icon: <Server size={20} className="text-indigo-400" />, label: 'System Logs' },
      { to: '/audit',         icon: <Shield size={20} className="text-violet-400" />, label: 'Audit Log' },
      { to: '/reports',       icon: <FileText size={20} className="text-sky-400" />, label: 'Scheduled Reports' },
      { to: '/notifications', icon: <Bell size={20} className="text-amber-600 dark:text-amber-400" />, label: 'Notifications' },
    ]
  }
]

const COLLAPSED_KEY = 'sidebar-collapsed'

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const mql = window.matchMedia(query)
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [query])
  return matches
}

function NavGroup({ item, collapsed, setCollapsed }: any) {
  const location = useLocation();
  const isActive = item.children.some((child: any) => location.pathname.startsWith(child.to));
  const [expanded, setExpanded] = useState(isActive);

  useEffect(() => {
    if (isActive && !collapsed) {
      setExpanded(true);
    }
  }, [isActive, collapsed]);

  return (
    <div className="flex flex-col">
      <button 
        onClick={() => {
          if (collapsed) {
            setCollapsed(false);
            setExpanded(true);
          } else {
            setExpanded(!expanded);
          }
        }}
        className={`group relative flex items-center justify-between w-full gap-3 rounded-lg text-sm transition-all
          ${collapsed ? 'px-0 py-2.5 justify-center' : 'px-3 py-2.5'}
          ${isActive ? 'text-brand-700 dark:text-brand-400 font-medium' : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05] hover:text-slate-900 dark:hover:text-slate-200'}
        `}
      >
        <div className={`flex items-center gap-3 ${collapsed ? 'justify-center w-full' : ''}`}>
          <span className="shrink-0">{item.icon}</span>
          {!collapsed && <span className="sidebar-label truncate">{item.label}</span>}
        </div>
        {!collapsed && (
           <ChevronDown size={14} className={`shrink-0 transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`} />
        )}
        
        {collapsed && (
          <span className="sidebar-tooltip pointer-events-none absolute left-full ml-2 px-2.5 py-1.5 rounded-md bg-slate-50 dark:bg-slate-800 text-slate-200 text-xs font-medium whitespace-nowrap opacity-0 group-hover:opacity-100 transition-opacity duration-200 shadow-lg border border-slate-700/50 z-50">
            {item.label}
          </span>
        )}
      </button>

      {!collapsed && expanded && (
        <div className="mt-1 ml-4 pl-3 border-l border-slate-300 dark:border-white/10 space-y-0.5">
          {item.children.map((child: any) => (
            <NavLink
              key={child.to}
              to={child.to}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg text-sm transition-all px-3 py-2
                 ${isActive
                   ? 'bg-brand-600/10 dark:bg-brand-600/20 text-brand-700 dark:text-brand-400 font-medium border border-brand-600/20 dark:border-brand-600/30'
                   : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-white/[0.05] hover:text-slate-900 dark:hover:text-slate-200 border border-transparent'}`
              }
            >
              {child.icon && <span className="shrink-0 scale-90 opacity-80">{child.icon}</span>}
              <span className="truncate">{child.label}</span>
            </NavLink>
          ))}
        </div>
      )}
    </div>
  )
}

export default function Layout() {
  const { username, logout, themePreference, toggleTheme } = useAuth()
  const isMobile = useMediaQuery('(max-width: 768px)')

  const [collapsed, setCollapsed] = useState(() => {
    if (typeof window !== 'undefined' && window.innerWidth <= 768) return true
    const stored = localStorage.getItem(COLLAPSED_KEY)
    return stored === 'true'
  })

  // Auto-collapse on mobile, restore on desktop
  useEffect(() => {
    if (isMobile) {
      setCollapsed(true)
    } else {
      const stored = localStorage.getItem(COLLAPSED_KEY)
      setCollapsed(stored === 'true')
    }
  }, [isMobile])

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    if (!isMobile) localStorage.setItem(COLLAPSED_KEY, String(next))
  }

  const sidebarWidth = collapsed ? 'w-[68px]' : 'w-[240px]'

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside className={`sidebar flex flex-col shrink-0 bg-white dark:bg-[#12121f] border-r border-white/[0.07] transition-all duration-300 ease-in-out ${sidebarWidth}`}>

        {/* Logo + Toggle */}
        <div className={`border-b border-white/[0.07] ${collapsed ? 'px-2 py-3 flex flex-col items-center gap-2' : 'px-3 py-4 flex items-center gap-2.5'} min-h-[64px]`}>
          <div className="w-9 h-9 rounded-lg bg-brand-600 flex items-center justify-center text-slate-900 dark:text-white font-bold text-sm shrink-0">
            MA
          </div>
          {!collapsed && (
            <div className="flex-1 min-w-0 sidebar-label">
              <div className="text-slate-900 dark:text-white font-semibold text-sm leading-tight">Messaging Agent</div>
              <div className="text-slate-700 dark:text-slate-500 text-[10px]">SMPP · RCS Gateway</div>
            </div>
          )}
          <button
            onClick={toggle}
            className="text-slate-700 dark:text-slate-500 hover:text-slate-700 dark:text-slate-300 transition shrink-0 p-1.5 rounded hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]"
            title={collapsed ? 'Expand menu' : 'Collapse menu'}
          >
            {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={18} />}
          </button>
        </div>

        {/* Nav */}
        <nav className="flex-1 py-3 px-2 space-y-0.5 overflow-y-auto overflow-x-hidden">
          {nav.map(item => {
            if (item.children) {
              return <NavGroup key={item.label} item={item} collapsed={collapsed} setCollapsed={setCollapsed} />;
            }
            return (
            <NavLink
              key={item.to}
              to={item.to!}
              title={collapsed ? item.label : undefined}
              className={({ isActive }) =>
                `group relative flex items-center gap-3 rounded-lg text-sm transition-all
                 ${collapsed ? 'px-0 py-2.5 justify-center' : 'px-3 py-2.5'}
                 ${isActive
                   ? 'bg-brand-600/10 dark:bg-brand-600/20 text-brand-700 dark:text-brand-400 border border-brand-600/20 dark:border-brand-600/30 font-medium'
                   : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-white/[0.05] hover:text-slate-900 dark:hover:text-slate-200 border border-transparent'}`
              }
            >
              <span className="shrink-0">{item.icon}</span>
              {!collapsed && <span className="sidebar-label truncate">{item.label}</span>}
              {/* Tooltip on hover when collapsed */}
              {collapsed && (
                <span className="sidebar-tooltip pointer-events-none absolute left-full ml-2 px-2.5 py-1.5 rounded-md bg-slate-50 dark:bg-slate-800 text-slate-200 text-xs font-medium whitespace-nowrap opacity-0 group-hover:opacity-100 transition-opacity duration-200 shadow-lg border border-slate-700/50 z-50">
                  {item.label}
                </span>
              )}
            </NavLink>
          )})}
        </nav>

        {/* User */}
        <div className={`border-t border-white/[0.07] ${collapsed ? 'px-2 py-3' : 'px-3 py-3'}`}>
          <div className={`flex items-center ${collapsed ? 'justify-center' : 'gap-3'}`}>
            <div className="w-8 h-8 rounded-full bg-brand-900 flex items-center justify-center text-brand-400 font-semibold text-sm uppercase shrink-0"
                 title={collapsed ? `${username} (Admin)` : undefined}>
              {username?.[0] ?? 'A'}
            </div>
            {!collapsed && (
              <>
                <div className="flex-1 min-w-0 sidebar-label">
                  <div className="text-xs font-medium text-slate-700 dark:text-slate-300 truncate">{username}</div>
                  <div className="text-[10px] text-slate-700 dark:text-slate-500">Admin</div>
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <NotificationBell />
                  <button onClick={toggleTheme} className="text-slate-700 dark:text-slate-500 hover:text-blue-400 transition p-1.5 rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]" title="Toggle Theme">
                    {themePreference === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
                  </button>
                  <button onClick={logout} className="text-slate-700 dark:text-slate-500 hover:text-red-400 transition p-1.5 rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]" title="Logout">
                    <LogOut size={15} />
                  </button>
                </div>
              </>
            )}
          </div>
          {collapsed && (
            <div className="mt-2 flex flex-col gap-1 items-center">
              <NotificationBell />
              <button
                onClick={toggleTheme}
                className="w-full flex justify-center text-slate-700 dark:text-slate-500 hover:text-blue-400 transition p-1 rounded hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]"
                title="Toggle Theme"
              >
                {themePreference === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
              </button>
              <button
                onClick={logout}
                className="w-full flex justify-center text-slate-700 dark:text-slate-500 hover:text-red-400 transition p-1 rounded hover:bg-slate-200/50 dark:hover:bg-slate-100 dark:bg-white/[0.05]"
                title="Logout"
              >
                <LogOut size={16} />
              </button>
            </div>
          )}
        </div>
      </aside>

      {/* Main */}
      <main className="flex-1 overflow-y-auto bg-slate-100 dark:bg-[#0f0f1a] pt-4">
        <Outlet />
      </main>

      {/* Push Notifications Prompt overlay */}
      <PushSubscriptionPrompt />
    </div>
  )
}
