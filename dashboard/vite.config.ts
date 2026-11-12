import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the dashboard runs on Vite's server (port 5173) and the
// backend on 8080. The proxy forwards API calls so the browser sees a single
// origin, exactly like production where Spring Boot serves both.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/platform': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
      '/realtime': { target: 'http://localhost:8080', ws: true },
    },
  },
})
