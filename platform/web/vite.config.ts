import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期把 /api 代理到后端，避免 CORS；生产由 nginx 反代
export default defineConfig({
  plugins: [vue()],
  server: {
    // 容器/远程预览：必须监听所有网卡，否则端口转发与外部访问都连不上
    host: '0.0.0.0',
    port: 5173,
    // 预览域名不是 localhost，Vite 的 Host 校验会返回 403
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
        // SSE 必须关闭缓冲
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers['cache-control'] = 'no-cache'
          })
        }
      }
    }
  }
})
