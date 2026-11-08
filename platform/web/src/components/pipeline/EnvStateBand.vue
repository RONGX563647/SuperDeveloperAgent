<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, Loading, WarningFilled } from '@element-plus/icons-vue'
import { deployConfirm, deployEnvs, deployReplay, type EnvStatus } from '../../api/deploy'
import { triggerPipeline } from '../../api/pipeline'

/**
 * 环境状态带：每个环境"有没有跑通过、能不能上服务器"。
 *
 * 规则（后端也是同一套）：
 * - dev / test：agent 可以全权跑，跑通后自动固化成可复现脚本（vN）；
 * - prod：**必须已有测试机成功案例**（测试脚本 + 证据运行成功 + 同一提交），
 *   且需要人手动确认一次（一次性令牌）才能操作。
 */
const props = defineProps<{
  projectId: string
  sha: string
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'started', runId: string, env: string): void
}>()

const envs = ref<EnvStatus[]>([])
const loading = ref(false)
const acting = ref('')

const sorted = computed(() => {
  const order = ['dev', 'test', 'prod']
  return [...envs.value].sort((a, b) => order.indexOf(a.env) - order.indexOf(b.env))
})

const prod = computed(() => envs.value.find((e) => e.env === 'prod'))

function envLabel(env: string): string {
  if (env === 'dev') return '开发'
  if (env === 'test') return '测试'
  if (env === 'prod') return '生产'
  return env
}

function stateOf(row: EnvStatus): 'none' | 'frozen' | 'ready' | 'blocked' {
  if (!row.recipeVersion) return 'none'
  if (row.evidenceStatus === 'SUCCEEDED') return 'ready'
  if (row.evidenceStatus) return 'blocked'
  return 'frozen'
}

function stateText(row: EnvStatus): string {
  const state = stateOf(row)
  if (state === 'none') return '未跑通（还没有可复现脚本）'
  const base = `v${row.recipeVersion} · 证据 ${row.evidenceRunId || '—'}`
  if (state === 'ready') return `${base}（成功）`
  if (state === 'blocked') return `${base}（证据状态 ${row.evidenceStatus}）`
  return base
}

async function load(): Promise<void> {
  if (!props.projectId) return
  loading.value = true
  try {
    envs.value = await deployEnvs(props.projectId)
  } catch {
    envs.value = []
  } finally {
    loading.value = false
  }
}

/** dev/test：有脚本就重放，没脚本就先跑一次（跑通会自动固化）。 */
async function runEnv(env: string): Promise<void> {
  if (!props.sha) {
    ElMessage.warning('请先选择提交')
    return
  }
  acting.value = env
  try {
    const row = envs.value.find((e) => e.env === env)
    const result = row?.recipeVersion
      ? await deployReplay(props.projectId, env, props.sha)
      : await triggerPipeline('release', props.projectId, props.sha, undefined, env)
    ElMessage.success(`已触发 ${envLabel(env)} 环境运行`)
    emit('started', result.runId, env)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '触发失败')
  } finally {
    acting.value = ''
  }
}

/** prod：先看测试证据，再要人点一次确认（一次性令牌）。 */
async function promote(): Promise<void> {
  if (!props.sha) {
    ElMessage.warning('请先选择提交')
    return
  }
  acting.value = 'prod'
  try {
    const plan = await deployConfirm(props.projectId, 'prod', props.sha)
    if (!plan.allowed) {
      ElMessage.warning(plan.reason ?? '测试环境还没有成功案例')
      return
    }
    const evidence = plan.evidence
    const ok = await ElMessageBox.confirm(
      `即将在**生产服务器**上发布提交 ${props.sha.slice(0, 7)}。\n`
      + `依据：测试环境 v${evidence?.version ?? '?'} · 证据运行 ${evidence?.runId ?? '?'}（${evidence?.status ?? '?'}）\n`
      + '这是高危操作，确认后立即执行。',
      '确认发布到生产',
      { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' }
    ).then(() => true).catch(() => false)
    if (!ok) {
      ElMessage.info('已取消')
      return
    }
    const result = await deployReplay(props.projectId, 'prod', props.sha, plan.confirmToken)
    ElMessage.success('已触发生成环境发布')
    emit('started', result.runId, 'prod')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    acting.value = ''
  }
}

watch(() => [props.projectId, props.sha], () => void load())
onMounted(load)

defineExpose({ reload: load })
</script>

<template>
  <section class="env-band" aria-label="环境状态">
    <div class="eb-head">
      <span class="eb-title">环境</span>
      <span class="eb-sub">开发/测试由 Agent 全权跑通并自动固化脚本；生产必须先在测试环境留下成功案例</span>
      <el-icon v-if="loading" class="is-loading eb-loading"><Loading /></el-icon>
    </div>

    <div class="eb-rows">
      <div v-for="row in sorted" :key="row.env" class="eb-row" :class="stateOf(row)">
        <span class="eb-env">{{ envLabel(row.env) }}</span>
        <span class="eb-state">
          <el-icon v-if="stateOf(row) === 'ready'" class="ok"><CircleCheck /></el-icon>
          <el-icon v-else-if="stateOf(row) === 'blocked'" class="bad"><WarningFilled /></el-icon>
          <span>{{ stateText(row) }}</span>
        </span>
        <span v-if="row.env === 'prod' && row.reason" class="eb-reason">{{ row.reason }}</span>
        <span class="eb-actions">
          <el-button
            v-if="row.env !== 'prod'"
            size="small" :loading="acting === row.env" :disabled="busy"
            @click="runEnv(row.env)"
          >
            {{ row.recipeVersion ? '重放' : '跑通' }}
          </el-button>
          <el-button
            v-else
            size="small" type="warning" plain
            :loading="acting === 'prod'"
            :disabled="busy || !prod?.promotable"
            :title="prod?.promotable ? '' : (prod?.reason ?? '需先在测试环境跑通')"
            @click="promote"
          >
            确认发布
          </el-button>
        </span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.env-band {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.eb-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.eb-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.eb-sub {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.eb-loading {
  color: var(--ink-faint);
}
.eb-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.eb-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  font-size: 12px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.eb-env {
  flex: none;
  width: 34px;
  font-weight: 600;
  color: var(--ink);
}
.eb-state {
  display: flex;
  flex: 1;
  gap: 5px;
  align-items: center;
  min-width: 0;
  color: var(--ink-soft);
}
.eb-state .ok {
  color: var(--ok);
}
.eb-state .bad {
  color: var(--warn);
}
.eb-row.ready .eb-env {
  color: var(--ok);
}
.eb-reason {
  flex-basis: 100%;
  padding-left: 42px;
  font-size: 11px;
  color: var(--ink-faint);
}
.eb-actions {
  flex: none;
}
</style>
