<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { Loading, VideoPlay } from '@element-plus/icons-vue'
import type { ActionView } from '../../api/action'

/**
 * 快捷动作列表（与流水线统一为一个"执行项"视图）：
 * - 命令原文默认可见（可折叠），执行前就知道要跑什么——不再藏在下拉里；
 * - L3 动作明确标注"需确认"，点击走变更确认流程；
 * - 分组：可直接执行 / 需人工确认。
 */
interface Props {
  actions: ActionView[]
  busyAction: string
  loading?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'run', action: string): void
  (e: 'request', action: string): void
}>()

const expanded = shallowRef<Set<string>>(new Set())

function isL3(a: ActionView): boolean {
  return a.level.startsWith('L3')
}

const readable = computed(() => props.actions.filter((a) => !isL3(a)))
const dangerous = computed(() => props.actions.filter(isL3))

function toggle(action: string): void {
  const next = new Set(expanded.value)
  if (next.has(action)) next.delete(action)
  else next.add(action)
  expanded.value = next
}
</script>

<template>
  <section class="action-panel" aria-label="快捷动作">
    <div class="ap-head">
      <h2 class="ap-title">快捷动作</h2>
      <span class="ap-count">{{ actions.length }} 项 · 命令原文可查</span>
    </div>

    <div class="ap-body">
      <template v-for="group in [
        { key: 'run', label: '可直接执行', rows: readable },
        { key: 'confirm', label: '需人工确认', rows: dangerous },
      ]" :key="group.key">
        <p v-if="group.rows.length" class="ap-group">{{ group.label }}</p>
        <article
          v-for="a in group.rows" :key="a.action"
          class="action-row" :class="{ l3: isL3(a), busy: busyAction === a.action }"
        >
          <div class="ar-head">
            <span class="ar-name mono">{{ a.action }}</span>
            <span class="ar-tag" :class="isL3(a) ? 'warn' : 'ok'">
              {{ isL3(a) ? 'L3 · 需确认' : 'L2 · 直接执行' }}
            </span>
            <button type="button" class="ar-toggle" @click="toggle(a.action)">
              {{ expanded.has(a.action) ? '收起命令' : '查看命令' }}
            </button>
            <button
              type="button" class="ar-run"
              :class="isL3(a) ? 'warn' : 'primary'"
              :disabled="busyAction === a.action"
              @click="isL3(a) ? emit('request', a.action) : emit('run', a.action)"
            >
              <el-icon v-if="busyAction === a.action" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><VideoPlay /></el-icon>
              {{ isL3(a) ? '登记变更' : '执行' }}
            </button>
          </div>

          <pre v-if="expanded.has(a.action) && a.commands.length" class="ar-cmd mono">{{ a.commands.join('\n') }}</pre>
          <p v-else-if="expanded.has(a.action)" class="ar-cmd-empty">（无可展示命令）</p>

          <div v-if="a.produce.length" class="ar-produce">
            <span class="ar-produce-label">产出</span>
            <span v-for="p in a.produce" :key="p" class="ar-produce-item mono">{{ p }}</span>
          </div>
        </article>
      </template>

      <p v-if="!loading && !actions.length" class="ap-empty">该项目没有可执行动作（检查 recipe / yml）</p>
    </div>
  </section>
</template>

<style scoped>
.action-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ap-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 6px;
}
.ap-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.ap-count {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.ap-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 10px 12px;
}
.ap-group {
  margin: 6px 2px 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  letter-spacing: 0.02em;
}
.action-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 9px 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.action-row.busy {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: var(--accent-soft);
}
.ar-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.ar-name {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ar-tag {
  flex: none;
  padding: 1px 7px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.ar-tag.ok {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.ar-tag.warn {
  color: var(--warn);
  background: var(--warn-soft);
}
.ar-toggle {
  margin-left: auto;
  padding: 0;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
.ar-toggle:hover {
  color: var(--ink);
}
.ar-run {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 9px;
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ar-run.primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
.ar-run.warn {
  color: var(--warn);
  background: var(--warn-soft);
  border-color: color-mix(in srgb, var(--warn) 35%, transparent);
}
.ar-run:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.ar-cmd {
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--code-bg);
  border-radius: var(--radius-xs);
  max-height: 140px;
}
.ar-cmd-empty {
  margin: 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.ar-produce {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}
.ar-produce-label {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ar-produce-item {
  padding: 1px 6px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.ap-empty {
  margin: 0;
  padding: 16px 8px;
  font-size: 12px;
  color: var(--ink-faint);
  text-align: center;
}
</style>
