package top.rongx.agent.executor;

/**
 * 开发环境前端静态服务器脚本（Node 原生，无第三方依赖）。
 *
 * <p>行为：
 * <ul>
 *   <li>{@code /prod-api/*}、{@code /dev-api/*} 反代到本机后端（去掉前缀）；</li>
 *   <li>命中 dist 里的文件直接返回；</li>
 *   <li>未命中回 {@code index.html}（SPA 前端路由）。</li>
 * </ul>
 *
 * <p>运行参数：{@code node static-server.mjs <distDir> <port> <backendPort>}。
 * 使用文本块保持可读；脚本里刻意不用反斜杠，避免被 Java 文本块转义。
 */
final class StaticServerScript {

    private StaticServerScript() {
    }

    static String text() {
        return """
                'use strict'
                const http = require('node:http')
                const fs = require('node:fs')
                const path = require('node:path')

                const distDir = path.resolve(process.argv[2] || '.')
                const port = Number(process.argv[3] || 0)
                const backendPort = Number(process.argv[4] || 0)
                const API_PREFIXES = ['/prod-api', '/dev-api']

                const MIME = {
                  '.html': 'text/html; charset=utf-8',
                  '.js': 'application/javascript; charset=utf-8',
                  '.mjs': 'application/javascript; charset=utf-8',
                  '.css': 'text/css; charset=utf-8',
                  '.json': 'application/json; charset=utf-8',
                  '.png': 'image/png',
                  '.jpg': 'image/jpeg',
                  '.jpeg': 'image/jpeg',
                  '.gif': 'image/gif',
                  '.svg': 'image/svg+xml',
                  '.ico': 'image/x-icon',
                  '.woff': 'font/woff',
                  '.woff2': 'font/woff2',
                  '.ttf': 'font/ttf',
                  '.eot': 'application/vnd.ms-fontobject',
                  '.txt': 'text/plain; charset=utf-8',
                  '.map': 'application/json; charset=utf-8'
                }

                function proxy(req, res, upstreamPath) {
                  if (!backendPort) {
                    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
                    res.end('backend port missing')
                    return
                  }
                  const headers = Object.assign({}, req.headers)
                  headers.host = '127.0.0.1:' + backendPort
                  const upstream = http.request({
                    host: '127.0.0.1',
                    port: backendPort,
                    path: upstreamPath,
                    method: req.method,
                    headers
                  }, (r) => {
                    res.writeHead(r.statusCode || 502, r.headers)
                    r.pipe(res)
                  })
                  upstream.on('error', (e) => {
                    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
                    res.end('bad gateway: ' + e.message)
                  })
                  req.pipe(upstream)
                }

                function sendIndex(res) {
                  const index = path.join(distDir, 'index.html')
                  fs.readFile(index, (err, buf) => {
                    if (err) {
                      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
                      res.end('index.html not found')
                      return
                    }
                    res.writeHead(200, {
                      'Content-Type': 'text/html; charset=utf-8',
                      'Cache-Control': 'no-cache'
                    })
                    res.end(buf)
                  })
                }

                const server = http.createServer((req, res) => {
                  const url = new URL(req.url, 'http://127.0.0.1')
                  for (const prefix of API_PREFIXES) {
                    if (url.pathname.startsWith(prefix)) {
                      proxy(req, res, url.pathname.slice(prefix.length) + url.search)
                      return
                    }
                  }
                  let rel = url.pathname
                  try {
                    rel = decodeURIComponent(rel)
                  } catch (ignored) {
                    // 非法编码按原样处理
                  }
                  const file = path.resolve(distDir, '.' + rel)
                  if (!file.startsWith(distDir)) {
                    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
                    res.end('forbidden')
                    return
                  }
                  fs.stat(file, (err, st) => {
                    if (!err && st.isFile()) {
                      res.writeHead(200, {
                        'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream'
                      })
                      fs.createReadStream(file).pipe(res)
                      return
                    }
                    sendIndex(res)
                  })
                })

                server.listen(port, '127.0.0.1', () => {
                  console.log('[static] serving ' + distDir + ' on http://127.0.0.1:' + port
                    + ' -> backend 127.0.0.1:' + backendPort)
                })
                """;
    }
}
