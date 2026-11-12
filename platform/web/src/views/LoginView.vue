<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, login, setToken } from '../api/auth'
import { appStore } from '../stores/app'

const router = useRouter()
const route = useRoute()
// 已有 token 直接进主界面（有效性由路由守卫校验）
if (getToken().length > 0) void router.replace('/chat')

/** 登录后回到被拦截的页面（守卫带的 redirect），否则进对话页。 */
const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
  ? route.query.redirect
  : '/chat'

const username = ref('')
const password = ref('')
const busy = ref(false)
const errorText = ref('')

async function doLogin() {
  if (busy.value) return
  if (!username.value.trim() || !password.value) {
    errorText.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  errorText.value = ''
  try {
    const result = await login(username.value.trim(), password.value)
    appStore.setIdentity({
      userId: result.userId,
      displayName: result.displayName,
      role: result.role,
      permissions: result.permissions
    })
    ElMessage.success(`欢迎，${result.displayName}（${result.role}）`)
    void router.push(redirect)
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand-row">
        <span class="brand-mark">运</span>
        <div>
          <h1 class="title">研发运维智能体平台</h1>
          <p class="subtitle">内网部署 · 按角色授权</p>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent="doLogin">
        <el-form-item label="用户名">
          <el-input
            v-model="username"
            size="large"
            placeholder="admin / devops / viewer"
            autofocus
            @keydown.enter="doLogin"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            size="large"
            type="password"
            show-password
            placeholder="••••••••"
            @keydown.enter="doLogin"
          />
        </el-form-item>
        <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" show-icon class="err" />
        <el-button
          type="primary"
          size="large"
          class="submit"
          native-type="submit"
          :loading="busy"
          :disabled="!username.trim() || !password"
          @click="doLogin"
        >
          登 录
        </el-button>
      </el-form>

      <div class="seed-hint">
        <p>种子账号（首次部署用，正式环境请改密）：</p>
        <p class="mono">admin / admin-2026 · devops / devops-2026 · viewer / viewer-2026</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  padding: 16px;
  background: var(--bg);
}
.login-card {
  width: min(400px, calc(100vw - 32px));
  padding: 32px 32px 24px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 20px;
  box-shadow: 0 4px 24px rgb(0 0 0 / 6%);
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  font-size: 20px;
  font-weight: 700;
  color: #fff;
  background: var(--accent);
  border-radius: 13px;
}
.title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.02em;
}
.subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ink-soft);
}
.err {
  margin-bottom: 12px;
}
.submit {
  width: 100%;
  margin-top: 4px;
}
.seed-hint {
  margin-top: 18px;
  padding: 10px 12px;
  background: var(--bg);
  border-radius: 10px;
}
.seed-hint p {
  margin: 2px 0;
  font-size: 11px;
  color: var(--ink-soft);
}
</style>
