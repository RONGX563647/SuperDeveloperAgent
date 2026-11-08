import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'
const EDGE = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(p => existsSync(p))
const browser = await puppeteer.launch({ executablePath: EDGE, headless: 'new', args: ['--no-first-run','--disable-gpu'] })
const page = await browser.newPage()
let body = '', status = 0, respText = ''
page.on('request', r => { if (r.url().includes('/auth/login')) body = r.postData() })
page.on('response', async r => {
  if (r.url().includes('/auth/login')) { status = r.status(); try { respText = (await r.text()).slice(0, 200) } catch {} }
})
await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle2' })
await page.evaluate(() => localStorage.clear())
await page.reload({ waitUntil: 'networkidle2' })
const inputs = await page.$$('.login-card input')
console.log('input count:', inputs.length)
for (let i = 0; i < inputs.length; i++) {
  console.log('input', i, 'type=', await page.evaluate((el) => el.type, inputs[i]), 'placeholder=', await page.evaluate((el) => el.placeholder, inputs[i]))
}
await inputs[0].click({ clickCount: 3 }); await page.keyboard.press('Backspace'); await inputs[0].type('devops')
await inputs[1].click({ clickCount: 3 }); await inputs[1].type('devops-2026')
// 打印输入框实际 value
console.log('values:', await page.evaluate(() => [...document.querySelectorAll('.login-card input')].map(i => i.value)))
await page.click('.login-card button.el-button--primary')
await new Promise(r => setTimeout(r, 2500))
console.log('sent body:', body)
console.log('status:', status, 'resp:', respText)
await browser.close()
