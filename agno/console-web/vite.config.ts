import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/console/',
  server: {
    port: 5173,
    proxy: {
      '/os': {
        target: 'http://127.0.0.1:7788',
        changeOrigin: true,
      },
      '/console/api': {
        target: 'http://127.0.0.1:7788',
        changeOrigin: true,
      },
    },
  },
})
