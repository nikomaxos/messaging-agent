import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import Layout from './components/Layout'
import LoginPage from './pages/LoginPage'
import GuardianDownloadPage from './pages/GuardianDownloadPage'
import DashboardPage from './pages/DashboardPage'
import DevicesPage from './pages/DevicesPage'
import SmppServerPage from './pages/SmppServerPage'
import SmppClientsPage from './pages/SmppClientsPage'
import SmppRoutingPage from './pages/SmppRoutingPage'
import SmscsPage from './pages/SmscsPage'
import SystemLogsPage from './pages/SystemLogsPage'
import MessageTrackingPage from './pages/MessageTrackingPage'
import UsersPage from './pages/UsersPage'
import InfraMonitoringPage from './pages/InfraMonitoringPage'
import NotificationsPage from './pages/NotificationsPage'
import AiAgentPage from './pages/AiAgentPage'
import DeadLetterPage from './pages/DeadLetterPage'
import AuditLogPage from './pages/AuditLogPage'
import ReportsPage from './pages/ReportsPage'
import ThroughputPage from './pages/ThroughputPage'
import TrafficAnalyticsPage from './pages/TrafficAnalyticsPage'
import DeployPage from './pages/DeployPage'

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
          <Route path="/guardian" element={<GuardianDownloadPage />} />
          <Route path="/" element={<PrivateRoute><Layout /></PrivateRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="devices" element={<DevicesPage />} />
            <Route path="smpp/server" element={<SmppServerPage />} />
            <Route path="smscs" element={<SmscsPage />} />
            <Route path="smpp/clients" element={<SmppClientsPage />} />
            <Route path="smpp/routing" element={<SmppRoutingPage />} />
            <Route path="logs/messages" element={<MessageTrackingPage />} />
            <Route path="logs/system" element={<SystemLogsPage />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="infrastructure" element={<InfraMonitoringPage />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="ai-agent" element={<AiAgentPage />} />
            <Route path="dead-letters" element={<DeadLetterPage />} />
            <Route path="audit" element={<AuditLogPage />} />
            <Route path="reports" element={<ReportsPage />} />
            <Route path="throughput" element={<ThroughputPage />} />
            <Route path="analytics" element={<TrafficAnalyticsPage />} />
            <Route path="deploy" element={<DeployPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
