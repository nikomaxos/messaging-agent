import { Outlet, NavLink } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  LayoutDashboard, Layers, Smartphone, FileText, LogOut, Users, Route as RouteIcon, Server
} from 'lucide-react'

const nav = [
  { to: '/dashboard',     icon: <LayoutDashboard size={18} />, label: 'Dashboard' },
  { to: '/groups',        icon: <Layers size={18} />,          label: 'Device Groups' },
  { to: '/devices',       icon: <Smartphone size={18} />,      label: 'Devices' },
  { to: '/smpp/server',   icon: <Server size={18} />,          label: 'SMPP Server' },
  { to: '/smscs',         icon: <Server size={18} className="text-blue-400" />, label: 'SMSc Suppliers' },
  { to: '/smpp/clients',  icon: <Users size={18} />,           label: 'SMPP Clients' },
  { to: '/smpp/routing',  icon: <RouteIcon size={18} />,       label: 'Routing' },
  { to: '/logs/messages', icon: <FileText size={18} className="text-amber-400" />, label: 'Message Tracking' },
  { to: '/logs/device',   icon: <Smartphone size={18} className="text-teal-400" />, label: 'Device Logs' },
  { to: '/logs/system',   icon: <Server size={18} className="text-indigo-400" />, label: 'System Logs' },
]

export default function Layout() {
  const { username, logout } = useAuth()

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside className="flex flex-col w-[240px] shrink-0 bg-[#12121f] border-r border-white/[0.07]">
        {/* Logo */}
        <div className="px-5 py-5 border-b border-white/[0.07]">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-brand-600 flex items-center justify-center text-white font-bold text-sm">
              MA
            </div>
            <div>
              <div className="text-white font-semibold text-sm leading-tight">Messaging Agent</div>
              <div className="text-slate-500 text-[10px]">SMPP · RCS Gateway</div>
            </div>
          </div>
        </div>

        {/* Nav */}
        <nav className="flex-1 py-4 px-3 space-y-0.5 overflow-y-auto">
          {nav.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-all
                 ${isActive
                   ? 'bg-brand-600/20 text-brand-400 border border-brand-600/30 font-medium'
                   : 'text-slate-400 hover:bg-white/[0.05] hover:text-slate-200'}`
              }
            >
              {item.icon}
              {item.label}
            </NavLink>
          ))}
        </nav>

        {/* User */}
        <div className="px-4 py-4 border-t border-white/[0.07]">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-full bg-brand-900 flex items-center justify-center text-brand-400 font-semibold text-sm uppercase">
              {username?.[0] ?? 'A'}
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-medium text-slate-300 truncate">{username}</div>
              <div className="text-[10px] text-slate-500">Admin</div>
            </div>
            <button onClick={logout} className="text-slate-500 hover:text-red-400 transition" title="Logout">
              <LogOut size={15} />
            </button>
          </div>
        </div>
      </aside>

      {/* Main */}
      <main className="flex-1 overflow-y-auto bg-[#0f0f1a]">
        <Outlet />
      </main>
    </div>
  )
}
