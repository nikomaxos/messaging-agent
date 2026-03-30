import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use((config: any) => {
  const token = localStorage.getItem('jwt')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Track consecutive 401s — fire a clean React-integrated logout instead of
// a hard window.location redirect so React Router handles navigation.
let consecutive401s = 0

api.interceptors.response.use(
  (r: any) => { consecutive401s = 0; return r },
  (err: any) => {
    if (err.response?.status === 401) {
      consecutive401s++
      // After 5 consecutive 401s (not 3 — tolerate transient network blips),
      // dispatch a custom event. AuthContext listens and calls logout() which
      // clears state so PrivateRoute redirects to /login via React Router.
      if (consecutive401s >= 5) {
        consecutive401s = 0
        window.dispatchEvent(new CustomEvent('auth:unauthorized'))
      }
    } else {
      consecutive401s = 0
    }
    return Promise.reject(err)
  }
)

export default api

// ── Auth ──────────────────────────────────────────────────────────────────
export const login = (username: string, password: string) =>
  api.post('/auth/login', { username, password }).then((r: any) => r.data)

// ── Device Groups ─────────────────────────────────────────────────────────
export const getGroups      = () => api.get('/groups').then((r: any) => r.data)
export const getGroup       = (id: number) => api.get(`/groups/${id}`).then((r: any) => r.data)
export const createGroup    = (d: any) => api.post('/groups', d).then((r: any) => r.data)
export const updateGroup    = (id: number, d: any) => api.put(`/groups/${id}`, d).then((r: any) => r.data)
export const deleteGroup    = (id: number) => api.delete(`/groups/${id}`)

// ── Devices ───────────────────────────────────────────────────────────────
export const getDevices     = () => api.get('/devices').then((r: any) => r.data)
export const getDevicesByGroup = (gid: number) => api.get(`/devices/group/${gid}`).then((r: any) => r.data)
export const getDevice      = (id: number) => api.get(`/devices/${id}`).then((r: any) => r.data)
export const createDevice   = (d: any) => api.post('/devices', d).then((r: any) => r.data)
export const updateDevice   = (id: number, d: any) => api.put(`/devices/${id}`, d).then((r: any) => r.data)
export const deleteDevice   = (id: number) => api.delete(`/devices/${id}`)
export const getDevicePerformance = () => api.get('/devices/performance').then((r: any) => r.data)

// ── SMPP Server, Clients & Routing ──────────────────────────────────────────
export const getServerConfig  = () => api.get('/admin/smpp/server').then((r: any) => r.data)
export const updateServerConfig = (d: any) => api.put('/admin/smpp/server', d).then((r: any) => r.data)
export const restartServer    = () => api.post('/admin/smpp/server/restart').then((r: any) => r.data)
export const getSmppMetrics   = () => api.get('/admin/smpp/server/metrics').then((r: any) => r.data)

export const getSmppClients   = () => api.get('/smpp/clients').then((r: any) => r.data)
export const createSmppClient = (d: any) => api.post('/smpp/clients', d).then((r: any) => r.data)
export const updateSmppClient = (d: any) => api.put(`/smpp/clients/${d.id}`, d).then((r: any) => r.data)
export const deleteSmppClient = (id: number) => api.delete(`/smpp/clients/${id}`)
export const disconnectSmppClient = (systemId: string) => api.post(`/smpp/clients/${systemId}/disconnect`).then((r: any) => r.data)

export const getSmscSuppliers   = () => api.get('/admin/smsc-suppliers').then((r: any) => r.data)
export const createSmscSupplier = (d: any) => api.post('/admin/smsc-suppliers', d).then((r: any) => r.data)
export const updateSmscSupplier = (d: any) => api.put(`/admin/smsc-suppliers/${d.id}`, d).then((r: any) => r.data)
export const deleteSmscSupplier = (id: number) => api.delete(`/admin/smsc-suppliers/${id}`)
export const bindSmscSupplier   = (id: number) => api.post(`/admin/smsc-suppliers/${id}/bind`)
export const unbindSmscSupplier = (id: number) => api.post(`/admin/smsc-suppliers/${id}/unbind`)

export const getSmppRoutings   = () => api.get('/smpp/routings').then((r: any) => r.data)
export const createSmppRouting = (d: any) => api.post('/smpp/routings', d).then((r: any) => r.data)
export const updateSmppRouting = (id: number, d: any) => api.put(`/smpp/routings/${id}`, d).then((r: any) => r.data)
export const deleteSmppRouting = (id: number) => api.delete(`/smpp/routings/${id}`)

// ── Message Logs ──────────────────────────────────────────────────────────
export const getLogs = (page = 0, filters?: Record<string, any>, sortBy = 'createdAt', sortDir = 'DESC', size = 50) => 
  api.get('/logs', { params: { page, size, sortBy, sortDir, ...filters } }).then((r: any) => r.data)

export const getLogIds = (filters?: Record<string, any>) =>
  api.get('/logs/ids', { params: { ...filters } }).then((r: any) => r.data as number[])

export const resubmitMessages = (messageIds: number[], fallbackSmscId: number) =>
  api.post('/logs/resubmit', { messageIds, fallbackSmscId }).then((r: any) => r.data)

export const getSystemHealth = () => api.get('/system/health').then((r: any) => r.data)
export const getPlatformHealth = () => api.get('/platform/health').then((r: any) => r.data)

// Notification system
export const getNotificationConfigs = () => api.get('/notifications/configs').then((r: any) => r.data)
export const createNotificationConfig = (d: any) => api.post('/notifications/configs', d).then((r: any) => r.data)
export const updateNotificationConfig = (d: any) => api.put(`/notifications/configs/${d.id}`, d).then((r: any) => r.data)
export const deleteNotificationConfig = (id: number) => api.delete(`/notifications/configs/${id}`)
export const getAlertHistory = (page = 0, size = 50, acknowledged?: boolean) => api.get('/notifications/alerts', { params: { page, size, acknowledged } }).then((r: any) => r.data)
export const acknowledgeAlert = (id: number) => api.patch(`/notifications/alerts/${id}/acknowledge`).then((r: any) => r.data)
export const massAcknowledgeAlerts = () => api.patch('/notifications/alerts/acknowledge-all').then((r: any) => r.data)

// AI Agent
export const getAiAgentConfig = () => api.get('/ai-agent/config').then((r: any) => r.data)
export const updateAiAgentConfig = (d: any) => api.put('/ai-agent/config', d).then((r: any) => r.data)
export const getAiAgentContext = () => api.get('/ai-agent/context').then((r: any) => r.data)
export const testAiAgent = () => api.post('/ai-agent/test').then((r: any) => r.data)
export const chatWithAiAgent = (messages: { role: string; content: string }[]) =>
  api.post('/ai-agent/chat', { messages }).then((r: any) => r.data)
export const getAiChatHistory = () => api.get('/ai-agent/history').then((r: any) => r.data)
export const clearAiChatHistory = () => api.delete('/ai-agent/history')

// Dead-Letter Queue
export const getDeadLetters = (page = 0, size = 50) => api.get('/dlq', { params: { page, size } }).then((r: any) => r.data)
export const retryDeadLetter = (id: number) => api.post(`/dlq/${id}/retry`).then((r: any) => r.data)
export const discardDeadLetter = (id: number) => api.delete(`/dlq/${id}`).then((r: any) => r.data)
export const getDlqCount = () => api.get('/dlq/count').then((r: any) => r.data)

// Audit Log
export const getAuditLogs = (page = 0, size = 50, username?: string, action?: string) =>
  api.get('/audit', { params: { page, size, username, action } }).then((r: any) => r.data)

// Scheduled Reports
export const getReports = (page = 0, size = 20) => api.get('/reports', { params: { page, size } }).then((r: any) => r.data)
export const generateReport = () => api.post('/reports/generate').then((r: any) => r.data)

// Throughput
export const getThroughput = (window = '1h') => api.get('/throughput', { params: { window } }).then((r: any) => r.data)
export const getLiveTps = (minutes = 5) => api.get('/throughput/live', { params: { minutes } }).then((r: any) => r.data)

// Bulk Device Commands
export const bulkDeviceCommand = (deviceIds: number[], command: string) =>
  api.post('/devices/bulk-command', { deviceIds, command }).then((r: any) => r.data)

// Traffic Analytics (BI)
export const getAnalyticsBySender = (window = '24h', limit = 100) =>
  api.get('/analytics/by-sender', { params: { window, limit } }).then((r: any) => r.data)
export const getAnalyticsByContent = (window = '24h', limit = 100) =>
  api.get('/analytics/by-content', { params: { window, limit } }).then((r: any) => r.data)
export const getSpamSuspects = (window = '24h') =>
  api.get('/analytics/spam-suspects', { params: { window } }).then((r: any) => r.data)
export const getAitSuspects = (window = '24h') =>
  api.get('/analytics/ait-suspects', { params: { window } }).then((r: any) => r.data)

export const getDeviceLogs = (page = 0, filters?: Record<string, any>) =>
  api.get('/logs/device', { params: { page, size: 50, ...filters } }).then((r: any) => r.data)

// ── Users ─────────────────────────────────────────────────────────────────
export const getUsers       = () => api.get('/users').then((r: any) => r.data)
export const createUser     = (d: any) => api.post('/users', d).then((r: any) => r.data)
export const updateUser     = (id: number, d: any) => api.put(`/users/${id}`, d).then((r: any) => r.data)
export const resetPassword  = (id: number, password: string) => api.put(`/users/${id}/password`, { password }).then((r: any) => r.data)
export const deleteUser     = (id: number) => api.delete(`/users/${id}`)

// ── Remote Desktop (WebSocket-based, no ADB) ──────────────────────────────
export const startScreenStream = (deviceId: number) =>
  api.post(`/devices/${deviceId}/remote/start`).then((r: any) => r.data)

export const stopScreenStream = (deviceId: number) =>
  api.post(`/devices/${deviceId}/remote/stop`).then((r: any) => r.data)

export const sendRemoteInput = (deviceId: number, input: Record<string, any>) =>
  api.post(`/devices/${deviceId}/remote/input`, input).then((r: any) => r.data)

export const sendRemoteWake = (deviceId: number) =>
  api.post(`/devices/${deviceId}/remote/wake`).then((r: any) => r.data)

export const sendShellCommand = (deviceId: number, cmd: string) =>
  api.post(`/devices/${deviceId}/remote/shell`, { cmd }).then((r: any) => r.data)

// ── Web Push ────────────────────────────────────────────────────────────────
export const getPushPublicKey = () => api.get('/push/public-key').then((r: any) => r.data)
export const syncPushSubscription = (subData: any) => api.post('/push/subscribe', subData).then((r: any) => r.data)

