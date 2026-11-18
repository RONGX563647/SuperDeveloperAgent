/**
 * Node ESM 解析钩子：把无扩展名的相对导入补成 .ts/.tsx，供零依赖自检脚本使用。
 *
 * <p>前端源码按打包器习惯写 `import x from './agentEventReducer'`；Node 原生
 * `--experimental-strip-types` 只认显式扩展名，于是这些自检脚本此前从来没跑起来过。
 * 这里只在"原样解析失败"时补扩展名，不改变已有行为。
 */
const CANDIDATES = ['.ts', '.tsx', '/index.ts', '/index.tsx']

export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context)
  } catch (error) {
    const relative = specifier.startsWith('./') || specifier.startsWith('../')
    if (!relative || /\.[a-z]+$/i.test(specifier)) {
      throw error
    }
    for (const suffix of CANDIDATES) {
      try {
        return await nextResolve(specifier + suffix, context)
      } catch {
        // 继续尝试下一个后缀
      }
    }
    throw error
  }
}
