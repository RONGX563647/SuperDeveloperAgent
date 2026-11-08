<script setup lang="ts">
import { Notebook, ChatDotRound, User } from '@element-plus/icons-vue'
import { currentUserId } from './api/http'

const userId = currentUserId()
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header" height="56px">
      <div class="header-left">
        <div class="brand-box">
          <div class="brand-mark">运</div>
          <span class="brand-text">研发运维智能体平台</span>
        </div>
      </div>
      <nav class="nav">
        <router-link to="/chat" class="nav-link" active-class="active">
          <el-icon><ChatDotRound /></el-icon>
          <span>对话</span>
        </router-link>
        <router-link to="/pipeline" class="nav-link" active-class="active">
          <el-icon><Notebook /></el-icon>
          <span>流水线 / 动作</span>
        </router-link>
      </nav>
      <div class="header-right">
        <span class="hint">内网环境 · 所有写操作需人工确认</span>
        <span class="user-chip" title="本地标识（账号体系接入前）">
          <el-icon><User /></el-icon>
          <span class="mono">{{ userId }}</span>
        </span>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
  height: 100dvh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}
.header-left {
  display: flex;
  align-items: center;
  min-width: 0;
}
.brand-box {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  background: var(--accent);
  border-radius: 9px;
}
.brand-text {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.02em;
  white-space: nowrap;
}
.nav {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 100%;
  margin-left: 8px;
}
.nav-link {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  font-size: 13px;
  color: var(--ink-soft);
  text-decoration: none;
  border-radius: 8px;
  transition: background 0.15s ease, color 0.15s ease;
}
.nav-link:hover {
  color: var(--accent);
  background: var(--accent-soft);
}
.nav-link.active {
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  color: var(--ink-soft);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 999px;
}
.hint {
  font-size: 12px;
  color: #a0a6ad;
  white-space: nowrap;
}
.app-main {
  padding: 0;
  background: var(--bg);
  overflow: hidden;
}
@media (max-width: 860px) {
  .hint {
    display: none;
  }
  .brand-text {
    display: none;
  }
}
</style>
