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
</script>

<template>
  <!-- MarkdownIt runs with html=false; generated markup contains escaped user source. -->
  <div ref="root" class="markdown-view" v-html="html" />
</template>

<style>
.markdown-view {
  font-size: 14.5px;
  line-height: 1.9;
  color: var(--ink);
  word-break: break-word;
}
.markdown-view p {
  margin: 0 0 8px;
}
.markdown-view p:last-child {
  margin-bottom: 0;
}
.markdown-view ul,
.markdown-view ol {
  margin: 0 0 8px;
  padding-left: 22px;
}
.markdown-view code:not(.hljs) {
  padding: 1px 5px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--accent-ink);
  background: var(--surface-hover);
  border-radius: 4px;
}
.markdown-view pre.code-block {
  margin: 8px 0;
  padding: 10px 12px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.markdown-view blockquote {
  margin: 8px 0;
  padding: 4px 12px;
  color: var(--ink-muted);
  border-left: 2px solid var(--line);
}
.markdown-view table {
  margin: 8px 0;
  font-size: 13px;
  border-collapse: collapse;
}
.markdown-view th,
.markdown-view td {
  padding: 5px 10px;
  border: 1px solid var(--line-soft);
}
.markdown-view a {
  color: var(--accent-ink);
}
/* 内置高亮 token（utils/markdown.ts） */
.markdown-view .tok-kw { color: #c678dd; }
.markdown-view .tok-str { color: #98c379; }
.markdown-view .tok-comment { color: #7f848e; font-style: italic; }
.markdown-view .tok-num { color: #d19a66; }
</style>
