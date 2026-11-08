import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'
const EDGE = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(p=>existsSync(p))
const b = await puppeteer.launch({ executablePath: EDGE, headless:'new', args:['--no-first-run','--disable-gpu','--force-color-profile=srgb'] })
const p = await b.newPage()
await p.setViewport({ width:1440, height:900, deviceScaleFactor:1 })
await p.goto('http://127.0.0.1:5199/chat', { waitUntil:'networkidle2' })
await new Promise(r=>setTimeout(r,1200))
await p.screenshot({ path:'E:/CODE/Agent/agentscope-ruoyi/platform/web/.shots-lt/chat.png' })
const probe = await p.evaluate(() => {
  const cs = s => { const el=document.querySelector(s); return el?getComputedStyle(el).backgroundColor:null }
  return { body:getComputedStyle(document.body).backgroundColor, aside:cs('.aside'), composer:cs('.composer') }
})
console.log('chat:', JSON.stringify(probe))

await p.evaluate(() => {
  const frames = [
    ['agent', { tool: 'Set persistent user env vars for model config' }],
    ['agent', { text: '帮你把环境变量配置到 Windows 用户级（持久化，重启 IDE 或终端后仍生效）：' }],
    ['agent', { tool: 'run_command · Start app to verify config works' }],
    ['agent', { text: '第一轮 install 已完成。现在重新编译并启动应用验证配置：' }]
  ]
  window.fetch = async () => new Response(new ReadableStream({ start(c){ const enc=new TextEncoder(); let i=0; const tick=()=>{ if(i<frames.length){ c.enqueue(enc.encode(`event: ${frames[i][0]}\ndata: ${JSON.stringify(frames[i][1])}\n\n`)); i++; setTimeout(tick,120) } else c.close() }; tick() } }), { status:200, headers:{ 'Content-Type':'text/event-stream' } })
})
await p.type('.composer-input textarea', '帮我看看 test 环境为什么起不来，把环境变量配好再启动验证一下')
await p.keyboard.press('Enter')
await new Promise(r=>setTimeout(r,1800))
await p.screenshot({ path:'E:/CODE/Agent/agentscope-ruoyi/platform/web/.shots-lt/chat-filled.png' })
await p.setViewport({ width:1440, height:900, deviceScaleFactor:1 })
await p.goto('http://127.0.0.1:5199/pipeline', { waitUntil:'networkidle2' })
await new Promise(r=>setTimeout(r,1400))
await p.screenshot({ path:'E:/CODE/Agent/agentscope-ruoyi/platform/web/.shots-lt/pipeline.png' })
await b.close()
