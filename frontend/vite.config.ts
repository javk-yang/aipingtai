import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // @ 指向 src，所有 import 用 @/xxx 绝对路径，避免 ../../ 地狱
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    headers: {
      // 开发模式下禁用浏览器缓存，避免 HMR / 旧 JS 导致功能看起来未生效
      'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0',
      'Pragma': 'no-cache',
    },
    proxy: {
      // 开发环境代理：/api 和 /v1/agent 走后端 Java / Python 引擎
      // 生产环境由 Nginx 做同源反向代理，前端代码零感知
      '/api': {
        target: 'http://localhost:8090', // af-bootstrap
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 800,
    // WorkBuddy 安全删除阈值下避免构建时批量清空旧产物；发布前可手动清理 dist。
    emptyOutDir: false,
  },
})
