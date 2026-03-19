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
export const getLogs = (page = 0, filters?: Record<string, any>) => 
  api.get('/logs', { params: { page, size: 50, ...filters } }).then((r: any) => r.data)

export const getDeviceLogs = (page = 0, filters?: Record<string, any>) =>
  api.get('/logs/device', { params: { page, size: 50, ...filters } }).then((r: any) => r.data)
