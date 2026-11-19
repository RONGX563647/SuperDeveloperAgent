/**
 * 会话里插入图片的纯逻辑：拼 Markdown、按光标位置插入。
 *
 * <p>抽出来的理由：插入位置这类细节最容易出错（追加到末尾 vs 插到光标、
 * 中文文件名要不要转义），而它在组件里很难被自动测到。这里用纯函数锁住。
 */

/** 生成可写进正文的 Markdown 图片语法。 */
export function imageMarkdown(url: string, name: string): string {
  // 方括号里的 ] 会截断语法；替换成占位符而不是丢弃，保留可读性
  const label = (name || 'image').replace(/[[\]]/g, '_')
  return `![${label}](${url})`
}

export interface InsertResult {
  text: string
  /** 插入后光标应停的位置 */
  caret: number
}

/**
 * 在 {@code at} 处插入 {@code snippet}。
 *
 * <p>光标位置做夹紧：textarea 失焦后 selectionStart 可能是 0 或越界值，
 * 直接切片会把内容插到开头或抛异常。
 */
export function insertAt(text: string, snippet: string, at: number | null | undefined): InsertResult {
  const source = text ?? ''
  const raw = typeof at === 'number' && Number.isFinite(at) ? Math.trunc(at) : source.length
  const index = Math.min(Math.max(0, raw), source.length)
  const next = source.slice(0, index) + snippet + source.slice(index)
  return { text: next, caret: index + snippet.length }
}

/** 是否是图片文件（粘贴/拖入时先判一次，避免把文本当图传）。 */
export function isImageFile(type: string | undefined): boolean {
  return typeof type === 'string' && type.startsWith('image/')
}
