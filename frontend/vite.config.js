import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootDir, '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8088'
  // 默认与业务面同端口；需双端口联调时可设 VITE_DEV_MANAGEMENT_PROXY_TARGET=http://localhost:8089
  const managementProxyTarget =
    env.VITE_DEV_MANAGEMENT_PROXY_TARGET || env.VITE_AWARD_LOG_MANAGEMENT_BASE_URL || proxyTarget

  return {
    root: rootDir,
    plugins: [vue()],
    build: {
      sourcemap: false,
      chunkSizeWarningLimit: 720,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) {
              return undefined
            }
            // Element Plus 按组件拆 chunk 会与 teleport/affix/dialog 形成循环依赖，
            // 浏览器报 Cannot access 'x' before initialization；整库打一个包即可。
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element-plus'
            if (id.includes('echarts')) return 'vendor-echarts'
            if (id.includes('zrender')) return 'vendor-zrender'
            if (id.includes('html2canvas')) return 'vendor-html2canvas'
            if (id.includes('jspdf')) return 'vendor-jspdf'
            if (id.includes('markdown-it')) return 'vendor-markdown'
            if (id.includes('vue/') || id.includes('@vue/')) return 'vendor-vue'
            if (id.includes('axios')) return 'vendor-axios'
            if (id.includes('lodash')) return 'vendor-lodash'
            return 'vendor-misc'
          }
        }
      }
    },
    server: {
      port: 3000,
      strictPort: false,
      proxy: {
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
          target: managementProxyTarget,
          changeOrigin: true,
          ws: true,
          rewrite: (path) => path.replace(/^\/mcp/, '/award-log/api/mcp')
        },
        '^/award-log/(admin|api/mcp|api/assistant|api/security|api/system-config|api/agent/path-policy|api/runbook|api/ops|api/ops-|api/ops-trace|api/performance|api/collector|api/kafka|api/v1/model|api/platform/info|api/platform/readiness|api/platform/backend-probe|ws/)': {
          target: managementProxyTarget,
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
