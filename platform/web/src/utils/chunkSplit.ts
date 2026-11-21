/**
 * Rollup manualChunks 规则：第三方依赖统一进单一 vendor chunk。
 *
 * <p>历史 bug（部署后白屏）：曾按 element-plus / vue / 其余拆成三块，但
 * element-plus 内部与 @vue/runtime-core、@vueuse 等存在循环引用。浏览器按
 * ES module 拓扑执行跨 chunk 的循环依赖时，会踩到暂时性死区（TDZ）：
 * <pre>{@code Uncaught ReferenceError: Cannot access '$' before initialization}</pre>
 * Vue 因此挂不上，页面空白——而 HTML 与各资源请求全是 200，排查极易误判成
 * "部署没生效"。dev 模式（Vite 不预打包成多 chunk）不复现，只有 build 产物白屏。
 *
 * <p>把全部 node_modules 依赖收敛到一个 vendor chunk，跨 chunk 循环就不存在了。
 * 业务代码仍按动态 import 独立分包，vendor hash 稳定，缓存命中不受影响。
 *
 * <p>抽成纯函数是为了可被零依赖前端测试直接断言，锁死这个回归。
 */
export function manualChunkOf(id: string): string | undefined {
  if (!id.includes('node_modules')) return undefined
  return 'vendor'
}
