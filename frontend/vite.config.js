import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8088'

  return {
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
      // 整块转发（OpenAPI / Knife4j / 静态挂载），避免 3000→8088 跨域导致 fetch /v3/api-docs 失败
      '/award-log': {
        target: proxyTarget,
        changeOrigin: true,
        ws: true
      },
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/api/, '/award-log/api')
      },
      '/mcp': {
        target: proxyTarget,
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/mcp/, '/award-log/api/mcp')
      }
    }
  }
  }
})
