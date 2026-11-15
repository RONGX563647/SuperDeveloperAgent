<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '../../api/auth'
import { appStore } from '../../stores/app'

/**
 * 账号安全 —— 固定初始密码的收口。
 *
 * <p>三个种子账号首次启动共用同一个公开初始密码，登录后在这里改成自己的密码。
 * 服务端只存 BCrypt 哈希，前端不保存任何凭据；改密成功后当前会话仍有效。
 */
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const busy = ref(false)

const mismatch = computed(() =>
  confirmPassword.value.length > 0 && newPassword.value !== confirmPassword.value)

const canSubmit = computed(() =>
  currentPassword.value.length > 0
  && newPassword.value.length >= 8
  && confirmPassword.value.length > 0
  && !mismatch.value
  && !busy.value)

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  busy.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    // 侧栏的「仍在使用初始密码」提示随之消失
    appStore.identity.usingInitialPassword = false
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    ElMessage.success('密码已修改，下次登录请使用新密码')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '修改失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <h2>账号安全</h2>
      <p>修改当前账号的登录密码。密码以 BCrypt 哈希存库，平台不保存明文。</p>
    </header>

    <el-alert
      v-if="appStore.identity.usingInitialPassword"
      type="warning"
      :closable="false"
      show-icon
      title="当前仍在使用统一初始密码"
      description="初始密码是公开的，请尽快修改为自己的密码。"
      class="initial-alert"
    />

    <el-form class="password-form" label-position="top" @submit.prevent="submit">
      <el-form-item label="当前账号">
        <el-input :model-value="appStore.identity.userId" disabled />
      </el-form-item>
      <el-form-item label="原密码">
        <el-input
          v-model="currentPassword"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="请输入当前密码"
          @keydown.enter="submit"
        />
      </el-form-item>
      <el-form-item label="新密码" :error="newPassword && newPassword.length < 8 ? '至少 8 位' : ''">
        <el-input
          v-model="newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="至少 8 位，不能与初始密码相同"
          @keydown.enter="submit"
        />
      </el-form-item>
      <el-form-item label="确认新密码" :error="mismatch ? '两次输入不一致' : ''">
        <el-input
          v-model="confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="再次输入新密码"
          @keydown.enter="submit"
        />
      </el-form-item>
      <el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">
        修改密码
      </el-button>
      <span class="form-hint">修改后当前会话保持有效，无需重新登录。</span>
    </el-form>
  </div>
</template>

<style scoped>
.account-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.panel-head h2 {
  margin: 0;
  font-size: var(--text-lg, 16px);
  font-weight: 650;
  color: var(--ink);
}
.panel-head p {
  margin: 6px 0 0;
  font-size: var(--text-xs, 12px);
  line-height: 1.6;
  color: var(--ink-muted);
}
.initial-alert {
  max-width: 520px;
}
.password-form {
  max-width: 420px;
}
.form-hint {
  margin-left: 12px;
  font-size: var(--text-xs, 12px);
  color: var(--ink-muted);
}
</style>
