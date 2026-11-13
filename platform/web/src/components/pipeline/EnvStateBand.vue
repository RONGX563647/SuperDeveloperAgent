<script setup lang="ts">
import { computed } from 'vue'
import type { EnvStatus } from '../../api/deploy'
import { envShort } from '../../utils/pipelineLabels'

/**
 * 环境切换条：一个环境一行状态的旧"状态带"过于占空间，改成三个可点的环境胶囊。
 *
 * <p>每个胶囊承担两件事：切换当前环境 + 一眼看到该环境有没有跑通过。
 * 具体动作（运行/重放/发布）统一由运行卡承担，避免同一页面出现两套触发入口。
 */
interface Props {
  rows: EnvStatus[]
  modelValue: string
  loading?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

const ORDER = ['dev', 'test', 'prod']

const sorted = computed(() => {
  const byEnv = new Map(props.rows.map((r) => [r.env, r]))
  return ORDER.map((env) => byEnv.get(env) ?? {
    env,
    recipeVersion: 0,
    evidenceRunId: '',
    evidenceStatus: '',
    gitSha: '',
    createdAt: 0,
    recipeCount: 0,
    promotable: false,
    reason: '',
  } as EnvStatus)
})

type State = 'none' | 'ready' | 'blocked' | 'frozen'

function stateOf(row: EnvStatus): State {
  if (!row.recipeVersion) return 'none'
  if (row.evidenceStatus === 'SUCCEEDED') return 'ready'
  if (row.evidenceStatus) return 'blocked'
  return 'frozen'
}

function stateText(row: EnvStatus): string {
  const state = stateOf(row)
  if (state === 'none') return '未跑通'
  if (state === 'ready') return `v${row.recipeVersion} 已跑通`
  if (state === 'blocked') return `v${row.recipeVersion} 证据异常`
  return `v${row.recipeVersion} 待验证`
}

function tip(row: EnvStatus): string {
  const base = stateText(row)
  if (row.evidenceRunId) return `${base} · 证据 ${row.evidenceRunId}`
  if (row.reason) return `${base} · ${row.reason}`
  return base
}

function tone(row: EnvStatus): string {
  const state = stateOf(row)
  if (state === 'ready') return 'ok'
  if (state === 'blocked') return 'bad'
  if (state === 'frozen') return 'busy'
  return 'idle'
}
</script>

<template>
  <div class="env-switch" role="tablist" aria-label="环境">
    <button
      v-for="row in sorted" :key="row.env"
      type="button" role="tab"
      class="env-pill"
      :class="{ active: row.env === modelValue }"
      :aria-selected="row.env === modelValue"
      :title="tip(row)"
      @click="emit('update:modelValue', row.env)"
    >
      <span class="env-dot" :class="tone(row)" />
      <span class="env-name">{{ envShort(row.env) }}</span>
      <span class="env-state">{{ stateText(row) }}</span>
    </button>
  </div>
</template>

<style scoped>
.env-switch {
  display: flex;
  flex: 1;
  gap: 6px;
  min-width: 0;
}
.env-pill {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease,
    box-shadow var(--dur-fast) ease;
}
.env-pill:hover {
  background: var(--surface-hover);
  border-color: var(--line);
}
.env-pill.active {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  box-shadow: var(--shadow-1);
}
.env-pill:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}
.env-dot {
  width: 6px;
  height: 6px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.env-dot.ok { background: var(--ok); }
.env-dot.bad { background: var(--danger); }
.env-dot.busy { background: var(--warn); }
.env-name {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--ink);
}
.env-state {
  overflow: hidden;
  font-size: var(--text-2xs);
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.env-pill.active .env-state {
  color: var(--accent-ink);
}
</style>
