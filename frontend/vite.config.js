import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element-plus'
            if (id.includes('echarts')) return 'vendor-echarts'
            if (id.includes('vue')) return 'vendor-vue'
            if (id.includes('axios')) return 'vendor-axios'
            return 'vendor-misc'
          }
          if (id.includes('/src/components/')) return 'feature-components'
        }
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/api/, '/award-log/api')
      },
      '/mcp': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/mcp/, '/award-log/api/mcp')
      }
    }
  }
})
