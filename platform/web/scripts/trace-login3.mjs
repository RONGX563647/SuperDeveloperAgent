import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'
const EDGE = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(p => existsSync(p))
const browser = await puppeteer.launch({ executablePath: EDGE, headless: 'new', args: ['--no-first-run','--disable-gpu'] })
const page = await browser.newPage()
let body = '', status = 0
page.on('request', r => { if (r.url().includes('/auth/login')) body = r.postData() })
page.on('response', r => { if (r.url().includes('/auth/login')) status = r.status() })
await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle2' })
await page.evaluate(() => localStorage.clear())
await page.reload({ waitUntil: 'networkidle2' })
// 用 vModel 的真实路径：改 value + input 事件
await page.evaluate(() => {
  const inputs = document.querySelectorAll('.login-card input')
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  setter.call(inputs[0], 'devops'); inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
  setter.call(inputs[1], 'devops-2026'); inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
})
await new Promise(r => setTimeout(r, 200))
console.log('values:', await page.evaluate(() => [...document.querySelectorAll('.login-card input')].map(i => i.value)))
await page.click('.login-card button.el-button--primary')
await new Promise(r => setTimeout(r, 2500))
console.log('body:', body, 'status:', status)
console.log('path:', await page.evaluate(() => location.pathname))
console.log('token:', (await page.evaluate(() => localStorage.getItem('agent.token')))?.slice(0, 12))
console.log('userName:', await page.evaluate(() => document.querySelector('.user-name')?.textContent?.trim()))
await browser.close()
