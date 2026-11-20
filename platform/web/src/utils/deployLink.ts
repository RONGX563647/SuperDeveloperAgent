/**
 * 部署端点 → 「打开部署地址」的页面 URL。
 *
 * <p>历史构建记录里的 deployment.ref 可能是探活路径（如
 * {@code http://127.0.0.1:8184/actuator/health}），直接打开只会看到一段 JSON，
 * 用户以为部署错了地方。页面入口统一取协议 + host + port + 根路径。
 *
 * <p>新构建写下的 ref 已经是根路径，这里对所有历史值做归一化，保证新旧一致。
 */
export function asPageUrl(ref: string | undefined | null): string {
  const value = (ref ?? '').trim()
  if (!/^https?:\/\//i.test(value)) return ''
  try {
    const url = new URL(value)
    // 只保留协议 + 主机（含端口），路径一律归一到根 —— 首页入口。
    return `${url.protocol}//${url.host}/`
  } catch {
    return ''
  }
}
