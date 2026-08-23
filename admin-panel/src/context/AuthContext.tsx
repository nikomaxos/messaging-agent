import React, { createContext, useContext, useState, useEffect } from 'react'
import { login as apiLogin } from '../api/client'

interface AuthState {
  token: string | null
  username: string | null
  role: string | null
  themePreference: string
}

interface AuthContextType extends AuthState {
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  toggleTheme: () => void
  isAuthenticated: boolean
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [auth, setAuth] = useState<AuthState>({
    token: localStorage.getItem('jwt'),
    username: localStorage.getItem('username'),
    role: localStorage.getItem('role'),
    themePreference: localStorage.getItem('themePreference') || 'dark',
  })

  // Apply theme class to HTML element
  useEffect(() => {
    if (auth.themePreference === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }, [auth.themePreference])

  const login = async (username: string, password: string) => {
    const data = await apiLogin(username, password)
    localStorage.setItem('jwt', data.token)
    localStorage.setItem('username', data.username)
    localStorage.setItem('role', data.role)
    const theme = data.themePreference || 'dark';
    localStorage.setItem('themePreference', theme)
    setAuth({ token: data.token, username: data.username, role: data.role, themePreference: theme })
  }

  const logout = () => {
    localStorage.removeItem('jwt')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    setAuth({ token: null, username: null, role: null, themePreference: 'dark' })
  }

  const toggleTheme = async () => {
    const newTheme = auth.themePreference === 'dark' ? 'light' : 'dark';
    localStorage.setItem('themePreference', newTheme);
    setAuth({ ...auth, themePreference: newTheme });
    try {
      await fetch('/api/users/theme', {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${auth.token}`
        },
        body: JSON.stringify({ theme: newTheme })
      });
    } catch (e) {
      console.error('Failed to save theme preference', e);
    }
  }

  // Listen for 401-based logout dispatched by the Axios interceptor.
  // Using a DOM event keeps the Axios interceptor decoupled from React context.
  useEffect(() => {
    const handler = () => {
      localStorage.removeItem('jwt')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      setAuth({ token: null, username: null, role: null, themePreference: 'dark' })
    }
    window.addEventListener('auth:unauthorized', handler)
    return () => window.removeEventListener('auth:unauthorized', handler)
  }, [])

  return (
    <AuthContext.Provider value={{ ...auth, login, logout, toggleTheme, isAuthenticated: !!auth.token }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
