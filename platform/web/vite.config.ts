import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { manualChunkOf } from './src/utils/chunkSplit'

const MOCK_DIR = fileURLToPath(new URL('./mock/', import.meta.url))

interface MockRoute {
  method: string
  pattern: RegExp
  status: number
  body: unknown
}

/** 读取 `npm run mock:gen` 生成的 JSON，按「METHOD /path」建路由表。 */
function loadMockRoutes(): MockRoute[] {
  if (!existsSync(MOCK_DIR)) return []
  const routes: MockRoute[] = []
  for (const file of readdirSync(MOCK_DIR).filter((name) => name.endsWith('.json'))) {
    const parsed = JSON.parse(readFileSync(path.join(MOCK_DIR, file), 'utf8')) as {
      routes?: Record<string, { status?: number, body?: unknown }>
    }
    for (const [key, value] of Object.entries(parsed.routes ?? {})) {
      const [method, template] = key.split(' ')
      if (!method || !template) continue
      const pattern = new RegExp(`^${template.split('/').map((segment) =>
        segment.startsWith('{') ? '[^/]+' : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      ).join('/')}$`)
      routes.push({ method, pattern, status: value.status ?? 200, body: value.body ?? null })
    }
  }
  return routes
}

/** VITE_MOCK=1 时拦截 /api/**，返回 OpenAPI 生成的假数据；未命中继续走代理。 */
function mockApi(): Plugin {
  const enabled = process.env.VITE_MOCK === '1'
  return {
    name: 'agent-platform-mock-api',
    configureServer(server) {
      if (!enabled) return
      const routes = loadMockRoutes()
      server.config.logger.info(`[mock] 已加载 ${routes.length} 条路由（${MOCK_DIR}）`)
      server.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/api/')) return next()
        const pathname = req.url.split('?')[0]
        const method = (req.method ?? 'GET').toUpperCase()
        const hit = routes.find((route) => route.method === method && route.pattern.test(pathname))
        if (!hit) return next()
        res.statusCode = hit.status
        res.setHeader('Content-Type', 'application/json; charset=utf-8')
        res.setHeader('X-Mock', 'true')
        res.end(JSON.stringify(hit.body))
      })
    }
  }
}

// 开发期把 /api 代理到后端，避免 CORS；生产由 nginx 反代
export default defineConfig({
  plugins: [vue(), mockApi()],
  build: {
    rollupOptions: {
      output: {
        // 第三方依赖统一进单一 vendor chunk，规则见 src/utils/chunkSplit.ts。
        // 拆分 element-plus / vue / 其余会触发跨 chunk 循环依赖 TDZ → 部署后白屏。
        manualChunks: manualChunkOf,
      },
    },
  },
  server: {
    // 容器/远程预览：双栈监听，localhost 解析到 ::1 时也能连上
    host: '::',
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
