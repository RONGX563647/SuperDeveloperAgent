import { register } from 'node:module'

// 让 Node 的 ESM 解析器认无扩展名的相对 TS 导入（前端源码沿用打包器写法：'./agentEventReducer'）。
// 只用于跑零依赖自检脚本，不参与构建——vite 自己处理解析。
register('./ts-resolve-hooks.mjs', import.meta.url)
