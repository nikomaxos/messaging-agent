import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use(config => {
  const token = localStorage.getItem('jwt')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Track consecutive 401s so transient polling errors don't immediately log out.
let consecutive401s = 0
let redirecting = false

api.interceptors.response.use(
  r => { consecutive401s = 0; return r },
  err => {
    if (err.response?.status === 401) {
      consecutive401s++
      // Only log out after 3 consecutive 401s (not on every background poll hiccup)
      if (consecutive401s >= 3 && !redirecting) {
        redirecting = true
        localStorage.removeItem('jwt')
        window.location.href = '/login'
      }
    } else {
      consecutive401s = 0   // non-401 errors reset the counter
    }
    return Promise.reject(err)
  }
)

export default api

// ── Auth ──────────────────────────────────────────────────────────────────
export const login = (username: string, password: string) =>
  api.post('/auth/login', { username, password }).then(r => r.data)

// ── Device Groups ─────────────────────────────────────────────────────────
export const getGroups      = () => api.get('/groups').then(r => r.data)
export const getGroup       = (id: number) => api.get(`/groups/${id}`).then(r => r.data)
export const createGroup    = (d: any) => api.post('/groups', d).then(r => r.data)
export const updateGroup    = (id: number, d: any) => api.put(`/groups/${id}`, d).then(r => r.data)
export const deleteGroup    = (id: number) => api.delete(`/groups/${id}`)

// ── Devices ───────────────────────────────────────────────────────────────
export const getDevices     = () => api.get('/devices').then(r => r.data)
export const getDevicesByGroup = (gid: number) => api.get(`/devices/group/${gid}`).then(r => r.data)
export const getDevice      = (id: number) => api.get(`/devices/${id}`).then(r => r.data)
export const createDevice   = (d: any) => api.post('/devices', d).then(r => r.data)
export const updateDevice   = (id: number, d: any) => api.put(`/devices/${id}`, d).then(r => r.data)
export const deleteDevice   = (id: number) => api.delete(`/devices/${id}`)

// ── SMPP Configs ──────────────────────────────────────────────────────────
export const getSmppConfigs   = () => api.get('/smpp-configs').then(r => r.data)
export const createSmppConfig = (d: any) => api.post('/smpp-configs', d).then(r => r.data)
export const updateSmppConfig = (id: number, d: any) => api.put(`/smpp-configs/${id}`, d).then(r => r.data)
export const deleteSmppConfig = (id: number) => api.delete(`/smpp-configs/${id}`)

// ── Message Logs ──────────────────────────────────────────────────────────
export const getLogs = (page = 0) => api.get(`/logs?page=${page}&size=50`).then(r => r.data)
