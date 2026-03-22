import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import Layout from './components/Layout'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import GroupsPage from './pages/GroupsPage'
import DevicesPage from './pages/DevicesPage'
import SmppServerPage from './pages/SmppServerPage'
import SmppClientsPage from './pages/SmppClientsPage'
import SmppRoutingPage from './pages/SmppRoutingPage'
import SmscsPage from './pages/SmscsPage'
import SystemLogsPage from './pages/SystemLogsPage'
import DeviceLogsPage from './pages/DeviceLogsPage'
import MessageTrackingPage from './pages/MessageTrackingPage'
import UsersPage from './pages/UsersPage'

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<PrivateRoute><Layout /></PrivateRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="groups" element={<GroupsPage />} />
            <Route path="devices" element={<DevicesPage />} />
            <Route path="smpp/server" element={<SmppServerPage />} />
            <Route path="smscs" element={<SmscsPage />} />
            <Route path="smpp/clients" element={<SmppClientsPage />} />
            <Route path="smpp/routing" element={<SmppRoutingPage />} />
            <Route path="logs/messages" element={<MessageTrackingPage />} />
            <Route path="logs/system" element={<SystemLogsPage />} />
            <Route path="logs/device" element={<DeviceLogsPage />} />
            <Route path="users" element={<UsersPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
