/**
 * Vite 开发配置：Vue 单页 + 将 API/WS 代理到后端 8088，避免前后端跨域。
 */
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootDir, '')
  // 单端口：业务 + 登录 + MCP + Agent 全部代理到 8088
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8088'
  const apiProxy = {
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

  return {
    root: rootDir,
    plugins: [vue()],
    // Spring Boot serves the built SPA under its context path. Keep Vite
    // development at the root, but make production asset URLs include the
    // context path so the packaged app can load its JS/CSS bundles.
    base: mode === 'production' ? '/award-log/' : '/',
    // The optimized markdown-it bundle otherwise points Chrome at TypeScript
    // sources that are not published by Vite, producing noisy 404s in the
    // Network panel although the application request succeeds.
    optimizeDeps: {
      esbuildOptions: {
        sourcemap: false
      }
    },
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
      // 本地开发直接通过 http://localhost 访问；80 被占用时应明确报错，
      // 不要静默切换到 3001/其他端口，避免浏览器地址与实际服务不一致。
      host: '127.0.0.1',
      port: 80,
      strictPort: true,
      proxy: apiProxy
    },
    // `vite preview` 也使用 80，保证构建产物测试时仍可直接访问 http://localhost。
    preview: {
      host: '127.0.0.1',
      port: 80,
      strictPort: true,
      proxy: apiProxy
    }
  }
})
