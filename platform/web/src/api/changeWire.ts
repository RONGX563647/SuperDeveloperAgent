/**
 * changeId 提取（BUG-31）：与后端 ChangeWire 完全同一口径的唯一前端实现。
 *
 * <p>放在独立模块而不是 chat.ts：chat.ts 引着 http.ts / auth.ts 等带依赖的文件，
 * 零依赖自检脚本（node --experimental-strip-types）跑不动它们；
 * 而这份逻辑必须能被纯 Node 测试直接加载，才能与后端 ChangeWireTest
 * 断言同一批输入得到同一结论。
 *
 * <p>后端契约（agent-domain ChangeWire.LINE）：
 * {@code (?m)^\s*changeId:\s*(chg-[A-Za-z0-9-]{8,64})\s*$}——
 * 1) 必须独占一行（整行匹配，正文里顺带提到的单号不算）；
 * 2) 形状是 {@code chg-} + [A-Za-z0-9-]，长度 8–64（不要求严格 UUID）。
 * 前端必须与后端口径一致，否则双向都会错：多认（句子里的单号渲染成假确认卡）
 * 或漏认（非 UUID 真单号不出卡）。
 *
 * <p>前端不需要做后端的 JSON-unwrap（ToolResultText），因为到前端时文本已还原。
 */

/** 从文本里识别变更单号；未按约定输出时返回 null（与后端 ChangeWire.extract 同语义）。 */
export function findChangeId(text: string): string | null {
  const lines = (text ?? '').split('\n')
  for (const line of lines) {
    const match = line.match(/^\s*changeId:\s*(chg-[A-Za-z0-9-]{8,64})\s*$/)
    if (match) return match[1]
  }
  return null
}
