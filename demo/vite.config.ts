import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  // Relative asset paths, because GitHub Pages serves the app from a sub-path.
  base: './',
  // The SDK lives in the sibling ../sdk folder, outside this project's root.
  server: { fs: { allow: ['..'] } },
})
