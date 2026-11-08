/**
 * Markdown 渲染器（零外部依赖，内置实现）。
 *
 * 背景：环境无法安装 markdown-it / highlight.js，故内置一个覆盖常用语法的
 * 轻量渲染器，保证前端启动与基础阅读体验；后续如引入 markdown-it，
 * 只需保持 renderMarkdown(content): string 签名替换本文件即可。
 *
 * 支持：标题 / 段落 / 粗斜体 / 行内代码 / 围栏代码块（含简易关键字高亮）/
 * 有序无序列表 / 引用块 / 链接 / 分隔线。安全：所有用户文本先转义再拼 HTML，
 * 不解析原始 HTML 标签（等价 markdown-it 的 html:false）。
 */

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

/* ---- 行内语法：粗体 / 斜体 / 行内代码 / 链接（输入已转义） ---- */

const INLINE_PATTERNS: Array<[RegExp, string]> = [
  [/`([^`]+)`/g, '<code>$1</code>'],
  [/\*\*([^*]+)\*\*/g, '<strong>$1</strong>'],
  [/\*([^*]+)\*/g, '<em>$1</em>'],
  [/&lt;([a-z]+:\/\/[^ &]+)&gt;/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>'],
  [/\[([^\]]+)\]\(([^ )]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'],
]

function renderInline(escaped: string): string {
  let out = escaped
  for (const [pattern, replacement] of INLINE_PATTERNS) {
    out = out.replaceAll(pattern, replacement)
  }
  return out
}

/* ---- 代码高亮：极简 token 着色（关键字 / 字符串 / 注释 / 数字） ---- */

const KEYWORDS = new Set([
  'abstract', 'as', 'async', 'await', 'bool', 'break', 'case', 'catch', 'class', 'const',
  'continue', 'def', 'default', 'delete', 'do', 'else', 'enum', 'export', 'extends', 'false',
  'finally', 'for', 'from', 'function', 'if', 'implements', 'import', 'in', 'instanceof',
  'interface', 'let', 'new', 'null', 'private', 'protected', 'public', 'return', 'static',
  'super', 'switch', 'this', 'throw', 'true', 'try', 'type', 'typeof', 'var', 'void',
  'while', 'yield', 'select', 'insert', 'update', 'delete', 'where', 'and', 'or', 'not',
])

function highlightCode(code: string): string {
  return escapeHtml(code).replaceAll(
    /(&quot;[^&]*?&quot;|&#39;[^&]*?&#39;|&#x27;[^&]*?&#x27;|'[^'\n]*'|"[^"\n]*")|(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_][A-Za-z0-9_]*\b)/g,
    (match, str, comment, num, word) => {
      if (str) return `<span class="tok-str">${match}</span>`
      if (comment) return `<span class="tok-comment">${match}</span>`
      if (num) return `<span class="tok-num">${match}</span>`
      if (word && KEYWORDS.has(word)) return `<span class="tok-kw">${match}</span>`
      return match
    },
  )
}

/** 共享渲染器：避免每条消息行都重建解析状态。 */
export function renderMarkdown(content: string): string {
  const lines = content.split(/\r?\n/)
  const html: string[] = []
  let i = 0

  let listType: 'ul' | 'ol' | null = null
  const closeList = () => {
    if (listType) {
      html.push(`</${listType}>`)
      listType = null
    }
  }

  while (i < lines.length) {
    const line = lines[i]

    // 围栏代码块
    const fence = line.match(/^```(\w*)\s*$/)
    if (fence) {
      closeList()
      const lang = fence[1] ?? ''
      const body: string[] = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        body.push(lines[i])
        i++
      }
      i++ // 跳过收尾 ```
      html.push(
        `<pre class="code-block"><code class="hljs${lang ? ` language-${escapeHtml(lang)}` : ''}">`
        + highlightCode(body.join('\n'))
        + '</code></pre>',
      )
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${renderInline(escapeHtml(heading[2]))}</h${level}>`)
      i++
      continue
    }

    // 分隔线
    if (/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      closeList()
      html.push('<hr>')
      i++
      continue
    }

    // 引用块
    if (/^&gt;\s?/.test(line) || /^>\s?/.test(line)) {
      closeList()
      const quote: string[] = []
      while (i < lines.length && (/^&gt;\s?/.test(lines[i]) || /^>\s?/.test(lines[i]))) {
        quote.push(lines[i].replace(/^(&gt;|>)\s?/, ''))
        i++
      }
      html.push(`<blockquote>${renderInline(escapeHtml(quote.join(' ')))}</blockquote>`)
      continue
    }

    // 列表
    const unordered = line.match(/^\s*[-*+]\s+(.*)$/)
    const ordered = line.match(/^\s*\d+[.)]\s+(.*)$/)
    if (unordered || ordered) {
      const wanted: 'ul' | 'ol' = unordered ? 'ul' : 'ol'
      if (listType !== wanted) {
        closeList()
        html.push(`<${wanted}>`)
        listType = wanted
      }
      html.push(`<li>${renderInline(escapeHtml((unordered ?? ordered)![1]))}</li>`)
      i++
      continue
    }

    // 空行 / 普通段落
    closeList()
    if (!line.trim()) {
      i++
      continue
    }
    html.push(`<p>${renderInline(escapeHtml(line))}</p>`)
    i++
  }
  closeList()
  return html.join('\n')
}
