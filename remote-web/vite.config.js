import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'esnext'
  },
  esbuild: {
    target: 'esnext'
  },
  optimizeDeps: {
    exclude: ['@novnc/novnc'],
    esbuildOptions: {
      target: 'esnext'
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/vnc': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
})
