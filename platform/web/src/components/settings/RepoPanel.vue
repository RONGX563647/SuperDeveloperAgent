<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Link, UserFilled } from '@element-plus/icons-vue'
import {
  giteeBind, giteeBindStatus, giteeOauthStart, giteeRepos, giteeUnbind,
  type GiteeBinding, type GiteeRepo
} from '../../api/project'
import { notifyError, confirmAction } from '../../utils/feedback'

/**
 * 代码仓库授权面板（从「接入项目」页迁移过来）：Gitee 账号绑定 + OAuth + 私人令牌。
 *
 * <p>token 加密存 SQLite，接口只回掩码；绑定后可在这里预览仓库列表，
 * 「接入项目」页只负责选目录/选仓库，不再重复展示授权卡。
 */
const binding = ref<GiteeBinding | null>(null)
const patToken = ref('')
const patBusy = ref(false)
const oauthBusy = ref(false)
const repos = ref<GiteeRepo[]>([])
const reposLoading = ref(false)

async function load(): Promise<void> {
  try {
    binding.value = await giteeBindStatus()
  } catch {
    binding.value = { bound: false }
  }
  if (binding.value?.bound) {
    void loadRepos()
  } else {
    repos.value = []
  }
}

async function loadRepos(): Promise<void> {
  reposLoading.value = true
  try {
    repos.value = (await giteeRepos('personal')).slice(0, 6)
  } catch {
    repos.value = []
  } finally {
    reposLoading.value = false
  }
}

async function bindPat(): Promise<void> {
  const token = patToken.value.trim()
  if (!token) return
  patBusy.value = true
  try {
    const r = await giteeBind(token)
    ElMessage.success(`已绑定 Gitee 账号：${r.giteeLogin}`)
    patToken.value = ''
    await load()
  } catch (e) {
    notifyError(e, '绑定失败')
  } finally {
    patBusy.value = false
  }
}

async function oauthStart(): Promise<void> {
  oauthBusy.value = true
  try {
    const r = await giteeOauthStart()
    window.open(r.authorizeUrl, '_blank', 'noopener')
    ElMessage.info('已打开 Gitee 授权页；授权成功后回到本页点「刷新」')
  } catch (e) {
    notifyError(e, '无法发起授权')
  } finally {
    oauthBusy.value = false
  }
}

async function unbind(): Promise<void> {
  const ok = await confirmAction('解绑后需要重新授权才能浏览/克隆私有仓库。确认解绑？', '解绑 Gitee')
  if (!ok) return
  try {
    await giteeUnbind()
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    notifyError(e, '解绑失败')
  }
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">代码仓库</div>
        <div class="panel-desc">Gitee 授权与连接；token 加密保存，只回显掩码</div>
      </div>
      <el-button
        v-if="binding?.bound" size="small" :loading="reposLoading"
        @click="loadRepos"
      >
        刷新仓库
      </el-button>
    </div>

    <!-- 已绑定 -->
    <section v-if="binding?.bound" class="account-card">
      <span class="acct-icon"><el-icon><UserFilled /></el-icon></span>
      <div class="acct-main">
        <div class="acct-name">Gitee：{{ binding.giteeLogin }}</div>
        <div class="acct-token mono">{{ binding.tokenMasked }}</div>
      </div>
      <el-tag size="small" type="success" round effect="light">已连接</el-tag>
      <el-button size="small" text type="danger" @click="unbind">解绑</el-button>
    </section>

    <!-- 未绑定 -->
    <template v-else>
      <section class="account-card muted">
        <span class="acct-icon"><el-icon><Link /></el-icon></span>
        <div class="acct-main">
          <div class="acct-name">未绑定 Gitee 账号</div>
          <div class="acct-token">绑定后可直接从你的仓库列表选择项目；token 加密存储</div>
        </div>
      </section>

      <div class="bind-grid">
        <div class="bind-block">
          <div class="bind-title">方式一：网页授权（推荐）</div>
          <p class="bind-desc">跳转 Gitee 授权页，回调后自动绑定，无需手动复制令牌。</p>
          <el-button size="small" type="primary" :loading="oauthBusy" @click="oauthStart">
            <el-icon><Connection /></el-icon> 前往授权
          </el-button>
        </div>
        <div class="bind-block">
          <div class="bind-title">方式二：私人令牌</div>
          <p class="bind-desc">在 Gitee「设置 → 私人令牌」创建，勾选 projects 权限。</p>
          <div class="pat-row">
            <el-input
              v-model="patToken" type="password" show-password size="default"
              class="pat-input mono" placeholder="粘贴私人令牌"
              @keydown.enter="bindPat"
            />
            <el-button size="default" :disabled="!patToken.trim() || patBusy" :loading="patBusy" @click="bindPat">
              绑定
            </el-button>
          </div>
        </div>
      </div>
    </template>

    <!-- 已授权仓库预览 -->
    <section v-if="binding?.bound" class="repos">
      <div class="repos-head">
        <span class="repos-title">你的仓库</span>
        <span class="repos-hint">最近更新的前 6 个；接入项目时可选任意仓库</span>
      </div>
      <div v-if="reposLoading" class="repos-empty">加载中…</div>
      <div v-else-if="!repos.length" class="repos-empty">没有可访问的仓库</div>
      <div v-for="r in repos" :key="r.fullName" class="repo-row">
        <span class="repo-name mono">{{ r.fullName }}</span>
        <span v-if="r.privateRepo" class="repo-tag warn">私有</span>
        <span class="repo-desc">{{ r.description }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.panel-desc {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.panel-head .el-button {
  margin-left: auto;
}
.account-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.account-card.muted {
  color: var(--ink-muted);
}
.acct-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 18px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 50%;
}
.acct-main {
  flex: 1;
  min-width: 0;
}
.acct-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.acct-token {
  margin-top: 2px;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bind-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}
.bind-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.bind-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.bind-desc {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.bind-block .el-button {
  align-self: flex-start;
}
.pat-row {
  display: flex;
  gap: 8px;
}
.pat-input {
  flex: 1;
  min-width: 0;
}
.repos {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 4px;
  border-top: 1px dashed var(--line-soft);
}
.repos-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.repos-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.repos-hint {
  font-size: 11px;
  color: var(--ink-faint);
}
.repos-empty {
  padding: 14px 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.repo-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 10px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.repo-name {
  flex: none;
  font-size: 11.5px;
  color: var(--ink);
}
.repo-tag.warn {
  flex: none;
  padding: 1px 6px;
  font-size: 10px;
  color: var(--warn);
  background: var(--warn-soft);
  border-radius: var(--radius-pill);
}
.repo-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
