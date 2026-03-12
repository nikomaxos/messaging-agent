import React, { createContext, useContext, useState, useEffect } from 'react'
import { login as apiLogin } from '../api/client'

interface AuthState {
  token: string | null
  username: string | null
  role: string | null
}

interface AuthContextType extends AuthState {
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  isAuthenticated: boolean
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [auth, setAuth] = useState<AuthState>({
    token: localStorage.getItem('jwt'),
    username: localStorage.getItem('username'),
    role: localStorage.getItem('role'),
  })

  const login = async (username: string, password: string) => {
    const data = await apiLogin(username, password)
    localStorage.setItem('jwt', data.token)
    localStorage.setItem('username', data.username)
    localStorage.setItem('role', data.role)
    setAuth({ token: data.token, username: data.username, role: data.role })
  }

  const logout = () => {
    localStorage.removeItem('jwt')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    setAuth({ token: null, username: null, role: null })
  }

  // Listen for 401-based logout dispatched by the Axios interceptor.
  // Using a DOM event keeps the Axios interceptor decoupled from React context.
  useEffect(() => {
    const handler = () => {
      localStorage.removeItem('jwt')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      setAuth({ token: null, username: null, role: null })
    }
    window.addEventListener('auth:unauthorized', handler)
    return () => window.removeEventListener('auth:unauthorized', handler)
  }, [])

  return (
    <AuthContext.Provider value={{ ...auth, login, logout, isAuthenticated: !!auth.token }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
