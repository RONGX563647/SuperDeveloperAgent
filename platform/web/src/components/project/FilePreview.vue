<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, EditPen, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { fsRead, type FsFile } from '../../api/project'

/**
 * 文件预览（右侧详情的文件 Tab 内容）：
 * 行号 + 代码文本（零依赖，pre 网格布局）；元数据条（大小/修改时间）；
 * 操作按钮：编辑（占位）/ 下载 / 运行流水线；大文件或二进制给出只读降级提示。
 */
interface Props {
  path: string
  name: string
  projectId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'runPipeline', path: string): void
}>()

const file = ref<FsFile | null>(null)
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  file.value = null
  try {
    file.value = await fsRead(props.path)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载文件失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.path, load, { immediate: true })

const lines = computed(() => file.value?.content ? file.value.content.split(/\r?\n/) : [])
const lineNoWidth = computed(() => `${String(lines.value.length).length + 1}ch`)

const sizeText = computed(() => {
  if (!file.value) return ''
  const kb = file.value.sizeBytes / 1024
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb.toFixed(1)} KB`
})

const modifiedText = computed(() => {
  if (!file.value) return ''
  const d = new Date(file.value.modifiedAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

function download(): void {
  if (!file.value?.content) return
  const blob = new Blob([file.value.content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = props.name
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已下载')
}
</script>

<template>
  <div class="file-view">
    <!-- 元数据 + 操作条 -->
    <div class="fv-toolbar">
      <div class="fv-meta">
        <span class="fv-path mono" :title="path">{{ path }}</span>
        <span v-if="file" class="fv-stat">{{ sizeText }} · {{ modifiedText }} · {{ lines.length }} 行</span>
        <span v-if="file?.truncated" class="fv-truncated">已截断（大文件预览前若干行）</span>
      </div>
      <div class="fv-actions">
        <button type="button" class="fv-btn" disabled title="编辑（待接入）">
          <el-icon><EditPen /></el-icon> 编辑
        </button>
        <button type="button" class="fv-btn" :disabled="!file?.content" @click="download">
          <el-icon><Download /></el-icon> 下载
        </button>
        <button type="button" class="fv-btn primary" @click="emit('runPipeline', path)">
          <el-icon><VideoPlay /></el-icon> 运行流水线
        </button>
      </div>
    </div>

    <!-- 内容区 -->
    <div v-if="loading" class="fv-hint">加载中…</div>
    <div v-else-if="error" class="fv-error">
      <p>{{ error }}</p>
      <button type="button" class="fv-btn" @click="load">
        <el-icon><RefreshRight /></el-icon> 重试
      </button>
    </div>
    <div v-else-if="file && file.content === null" class="fv-hint">
      二进制或超大文件，不支持预览。可下载后查看。
    </div>
    <div v-else-if="file" class="fv-code" role="figure" aria-label="文件内容">
      <pre class="code"><template v-for="(line, idx) in lines" :key="idx"><span class="ln" :style="{ width: lineNoWidth }">{{ idx + 1 }}</span><span class="lc">{{ line || ' ' }}</span>
</template></pre>
    </div>
  </div>
</template>

<style scoped>
.file-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.fv-toolbar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line-soft);
}
.fv-meta {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 10px;
}
.fv-path {
  overflow: hidden;
  font-size: 12px;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fv-stat {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
.fv-truncated {
  flex: none;
  padding: 1px 8px;
  font-size: 10.5px;
  color: var(--warn);
  background: var(--warn-soft);
  border-radius: var(--radius-pill);
}
.fv-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
}
.fv-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease,
    border-color var(--dur-fast) ease;
}
.fv-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.fv-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.fv-btn.primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
.fv-btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
}
.fv-hint {
  padding: 48px 0;
  font-size: 13px;
  color: var(--ink-faint);
  text-align: center;
}
.fv-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 48px 0;
}
.fv-error p {
  margin: 0;
  font-size: 13px;
  color: var(--danger);
}
.fv-code {
  flex: 1 1 0;
  min-height: 0;
  overflow: auto;
  background: var(--code-bg);
}
.code {
  display: block;
  margin: 0;
  padding: 12px 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink);
  white-space: pre;
}
.ln {
  display: inline-block;
  padding-right: 14px;
  color: var(--ink-faint);
  text-align: right;
  user-select: none;
  vertical-align: top;
}
.lc {
  vertical-align: top;
}

/* 手机：工具栏换行，路径占一行、操作按钮右对齐，避免横向溢出 */
@media (max-width: 600px) {
  .fv-toolbar {
    flex-wrap: wrap;
    gap: 6px;
    padding: 8px 10px;
  }
  .fv-meta {
    flex: 1 1 100%;
  }
  .fv-stat {
    display: none;
  }
  .fv-actions {
    flex: 1 1 100%;
    justify-content: flex-end;
  }
  .fv-btn {
    height: 28px;
  }
  .code {
    font-size: 12px;
  }
}
</style>
