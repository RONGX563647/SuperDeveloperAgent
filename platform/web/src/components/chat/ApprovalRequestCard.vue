<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getChange, getChangePatch, confirmChange, applyChange } from '../../api/change'
import type { ChangePatchView, ChangeView } from '../../types'
import { notifyError } from '../../utils/feedback'

/**
 * 变更确认卡（信息结构对齐 ruoyi-copilot 的 ApprovalRequestCard + 本项目 ConfirmTokenService）：
 * 加载变更单 → 确认（高危需密码）→ 带令牌执行，全部状态内聚在本组件。
 * 补丁类变更额外展示 diff 与目标分支，避免"只看到一句摘要就确认"。
 */
interface Props {
  changeId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'applied', result: { action: string, status: string, durationMs: number }): void
  (e: 'dismiss'): void
}>()

const change = ref<ChangeView | null>(null)
const patch = ref<ChangePatchView | null>(null)
const password = ref('')
const confirmToken = ref<string | null>(null)
const applyResult = ref<string | null>(null)
const applyLog = ref<string | null>(null)
const applyBusy = ref(false)

async function load(): Promise<void> {
  try {
    change.value = await getChange(props.changeId)
    patch.value = change.value.kind.startsWith('patch:')
      ? await getChangePatch(props.changeId)
      : null
    confirmToken.value = null
    applyResult.value = null
    applyLog.value = null
    password.value = ''
  } catch (error) {
    notifyError(error, '加载变更失败')
  }
}

async function doConfirm(): Promise<void> {
  if (!change.value) return
  try {
    const result = await confirmChange(change.value.changeId, change.value.highRisk ? password.value : undefined)
    confirmToken.value = result.confirmToken
    ElMessage.success('已确认，令牌 5 分钟内有效')
  } catch (error) {
    notifyError(error, '确认失败')
  }
}

async function doApply(): Promise<void> {
  if (!change.value || !confirmToken.value) return
  applyBusy.value = true
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    applyResult.value = `${result.action} → ${result.status}（${result.durationMs} ms）`
    applyLog.value = result.logTail || null
    ElMessage.success(`变更执行完毕：${result.status}`)
    emit('applied', result)
  } catch (error) {
    notifyError(error, '执行失败')
  } finally {
    applyBusy.value = false
  }
}

defineExpose({ load })
</script>

<template>
  <div v-if="change" class="change-card">
    <div class="card-head">
      <span class="head-title">变更待确认</span>
      <code class="mono head-id">{{ change.changeId }}</code>
      <el-tag
        :type="change.highRisk ? 'danger' : 'success'"
        size="small" effect="dark" round
      >
        {{ change.highRisk ? '高危（需二次密码）' : '常规' }}
      </el-tag>
    </div>

    <div class="card-body">
      <div class="line"><span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b></div>
      <div class="line"><span>类型</span><b>{{ change.kind }}</b></div>
      <div class="line"><span>内容</span><b>{{ change.summary }}</b></div>
      <div class="line"><span>镜像 digest</span><b class="mono">{{ change.imageDigest ?? '—' }}</b></div>
      <div class="line"><span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b></div>
    </div>

    <div v-if="patch" class="card-body patch-box">
      <div class="line"><span>基于提交</span><b class="mono">{{ patch.baseSha.slice(0, 12) }}</b></div>
      <div class="line"><span>目标分支</span><b class="mono">{{ patch.branch }}</b></div>
      <div class="line"><span>改动文件</span><b>{{ patch.files.join('、') }}</b></div>
      <div class="line"><span>增删行</span><b>+{{ patch.added }} / -{{ patch.removed }}</b></div>
      <el-collapse>
        <el-collapse-item title="查看补丁 diff（应用前请核对）" name="diff">
          <pre class="diff mono">{{ patch.diff }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>

    <el-input
      v-if="change.highRisk && !confirmToken"
      v-model="password"
      type="password"
      placeholder="请输入密码（账号体系接入前会失败关闭）"
      show-password
    />

    <el-alert
      v-if="confirmToken"
      type="success" :closable="false" show-icon
      title="确认令牌已签发（5 分钟内有效，一次性）"
      :description="confirmToken"
    />
    <el-alert
      v-if="applyResult"
      type="success" :closable="false" show-icon
      title="已带令牌执行" :description="applyResult"
    />
    <el-collapse v-if="applyLog" class="apply-log">
      <el-collapse-item title="执行输出（含新提交 sha）" name="log">
        <pre class="diff mono">{{ applyLog }}</pre>
      </el-collapse-item>
    </el-collapse>

    <div class="card-actions">
      <el-button size="small" @click="emit('dismiss')">忽略</el-button>
      <el-button size="small" :disabled="!!confirmToken" @click="doConfirm">确认变更</el-button>
      <el-button
        size="small" type="primary"
        :disabled="!confirmToken || applyBusy" :loading="applyBusy"
        @click="doApply"
      >
        {{ patch ? '带令牌应用补丁' : '带令牌执行' }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.change-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  margin-top: 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-2);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.head-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--warn);
}
.head-id {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.patch-box {
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
.apply-log {
  border-top: none;
}
.diff {
  max-height: 320px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre;
  background: var(--bg-soft, rgba(0, 0, 0, 0.04));
  border-radius: var(--radius-sm, 6px);
}
.card-body .line {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.card-body .line span {
  flex: none;
  width: 90px;
  color: var(--ink-muted);
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
