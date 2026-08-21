import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/backup': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
      '/ws-admin': {
        target: 'ws://localhost:9090',
        ws: true,
      },
    },
  },
})
