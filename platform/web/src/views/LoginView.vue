<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
const usernameInput = ref<{ focus: () => void } | null>(null)

/** 种子账号：点一下直接填充，省去手抄示例密码。 */
const seedAccounts = [
  { role: '管理员', user: 'admin', pass: 'admin-2026' },
  { role: '运维', user: 'devops', pass: 'devops-2026' },
  { role: '只读', user: 'viewer', pass: 'viewer-2026' },
]

function useSeed(account: { user: string; pass: string }): void {
  username.value = account.user
  password.value = account.pass
  errorText.value = ''
}

onMounted(() => usernameInput.value?.focus())

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
    <main class="login-card">
      <div class="brand-row">
        <span class="brand-mark" aria-hidden="true">运</span>
        <div class="brand-copy">
          <h1 class="title">研发运维智能体平台</h1>
          <p class="subtitle">内网部署 · 按角色授权 · 数据不出域</p>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent="doLogin">
        <el-form-item label="用户名">
          <el-input
            ref="usernameInput"
            v-model="username"
            size="large"
            placeholder="请输入用户名"
            autocomplete="username"
            @keydown.enter="doLogin"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            size="large"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
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
        <p class="seed-title">种子账号（首次部署用，正式环境请改密）</p>
        <div class="seed-list">
          <button
            v-for="a in seedAccounts" :key="a.user" type="button" class="seed-chip"
            :title="`使用 ${a.user} 登录`" @click="useSeed(a)"
          >
            <span class="seed-role">{{ a.role }}</span>
            <span class="seed-user mono">{{ a.user }}</span>
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  place-items: center;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  padding: 20px;
  overflow: hidden;
  background:
    radial-gradient(circle at 16% 10%, rgb(77 130 247 / 9%), transparent 44%),
    radial-gradient(circle at 84% 92%, rgb(46 163 107 / 7%), transparent 40%),
    var(--bg);
}
.login-card {
  position: relative;
  width: min(404px, calc(100vw - 32px));
  padding: 34px 32px 26px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xl);
  box-shadow: 0 1px 2px rgb(16 24 40 / 4%), 0 16px 48px rgb(16 24 40 / 10%);
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}
.brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  font-size: 19px;
  font-weight: 700;
  color: var(--brand-mark-ink);
  background: var(--brand-mark-bg);
  border-radius: 12px;
}
.brand-copy {
  min-width: 0;
}
.title {
  margin: 0;
  font-size: var(--text-xl);
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.subtitle {
  margin: 4px 0 0;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.err {
  margin-bottom: 12px;
}
.submit {
  width: 100%;
  margin-top: 6px;
  letter-spacing: 0.04em;
}
.seed-hint {
  padding-top: 16px;
  margin-top: 20px;
  border-top: 1px solid var(--line-soft);
}
.seed-title {
  margin: 0 0 8px;
  font-size: var(--text-2xs);
  color: var(--ink-muted);
}
.seed-list {
  display: flex;
  gap: 6px;
}
.seed-chip {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1px;
  align-items: flex-start;
  min-width: 0;
  padding: 6px 9px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.seed-chip:hover {
  background: var(--panel);
  border-color: var(--line);
}
.seed-role {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.seed-user {
  overflow: hidden;
  max-width: 100%;
  font-size: var(--text-xs);
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 420px) {
  .login-card {
    padding: 28px 22px 22px;
  }
}
</style>
