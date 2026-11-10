<script setup lang="ts">
import { computed, useTemplateRef } from 'vue'
import { renderMarkdown } from '../../utils/markdown'

/** Markdown 渲染容器（复用 ruoyi-copilot 的 MarkdownView 职责：v-html + 防注入由渲染层保证）。 */
interface Props {
  content?: string
}

const props = withDefaults(defineProps<Props>(), { content: '' })
const root = useTemplateRef<HTMLDivElement>('root')
const html = computed(() => renderMarkdown(props.content))

/** 代码块复制：渲染层不挂 Vue 事件，这里对容器做一次事件委托。 */
function onRootClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  if (!target?.classList.contains('code-copy')) return
  const code = target.closest('.code-wrap')?.querySelector('code')?.textContent ?? ''
  void navigator.clipboard?.writeText(code)
  const previous = target.textContent
  target.textContent = '已复制'
  window.setTimeout(() => {
    target.textContent = previous ?? '复制'
  }, 1200)
}
</script>

<template>
  <!-- MarkdownIt runs with html=false; generated markup contains escaped user source. -->
  <div ref="root" class="markdown-view" v-html="html" @click="onRootClick" />
</template>

<style>
.markdown-view {
  font-size: 14.5px;
  line-height: 1.9;
  color: var(--ink);
  word-break: break-word;
}
.markdown-view p {
  margin: 0 0 10px;
}
.markdown-view p:last-child {
  margin-bottom: 0;
}
.markdown-view h1,
.markdown-view h2,
.markdown-view h3,
.markdown-view h4,
.markdown-view h5,
.markdown-view h6 {
  margin: 16px 0 8px;
  font-size: 15.5px;
  line-height: 1.5;
  font-weight: 600;
  color: var(--ink);
}
.markdown-view h1 { font-size: 18px; }
.markdown-view h2 { font-size: 16.5px; }
.markdown-view > :first-child { margin-top: 0; }
.markdown-view ul,
.markdown-view ol {
  margin: 0 0 10px;
  padding-left: 22px;
}
.markdown-view li + li {
  margin-top: 3px;
}
.markdown-view code:not(.hljs) {
  padding: 1px 5px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--accent-ink);
  background: var(--surface-hover);
  border-radius: 4px;
  word-break: break-word;
}
/* 代码块：右上角复制按钮，hover 才显形 */
.markdown-view .code-wrap {
  position: relative;
  margin: 10px 0;
}
.markdown-view pre.code-block {
  margin: 0;
  padding: 11px 13px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.markdown-view .code-copy {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  opacity: 0;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xs);
  transition: opacity var(--dur-fast) ease;
}
.markdown-view .code-wrap:hover .code-copy {
  opacity: 1;
}
.markdown-view .code-copy:hover {
  color: var(--ink);
}
.markdown-view blockquote {
  margin: 10px 0;
  padding: 4px 12px;
  color: var(--ink-muted);
  border-left: 2px solid var(--line);
}
.markdown-view hr {
  margin: 16px 0;
  border: 0;
  border-top: 1px solid var(--line-soft);
}
.markdown-view .table-wrap {
  margin: 10px 0;
  overflow-x: auto;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.markdown-view table {
  width: 100%;
  font-size: 13px;
  line-height: 1.6;
  border-collapse: collapse;
}
.markdown-view th,
.markdown-view td {
  padding: 7px 10px;
  vertical-align: top;
  overflow-wrap: anywhere;
  border-right: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}
.markdown-view tbody tr:nth-child(even) {
  background: color-mix(in srgb, var(--surface-hover) 55%, transparent);
}
.markdown-view th:last-child,
.markdown-view td:last-child {
  border-right: 0;
}
.markdown-view tbody tr:last-child td {
  border-bottom: 0;
}
.markdown-view thead th {
  font-weight: 600;
  color: var(--ink-soft);
  white-space: nowrap;
  background: var(--surface-hover);
}
.markdown-view a {
  color: var(--accent-ink);
  text-decoration: none;
  border-bottom: 1px solid color-mix(in srgb, var(--accent-ink) 35%, transparent);
}
.markdown-view a:hover {
  border-bottom-color: var(--accent-ink);
}
/* 内置高亮 token（utils/markdown.ts） */
.markdown-view .tok-kw { color: #c678dd; }
.markdown-view .tok-str { color: #98c379; }
.markdown-view .tok-comment { color: #7f848e; font-style: italic; }
.markdown-view .tok-num { color: #d19a66; }
</style>
