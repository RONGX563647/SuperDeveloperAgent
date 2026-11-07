<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand">研发运维智能体平台</div>
      <nav class="nav">
        <router-link to="/chat" class="nav-link">对话 / Chat</router-link>
        <router-link to="/pipeline" class="nav-link">流水线 / 动作</router-link>
      </nav>
      <div class="meta">
        <span class="hint">内网环境 · 所有写操作需人工确认</span>
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
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e8ee;
  background: #0f2a43;
  color: #fff;
}
.brand {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.nav {
  display: flex;
  align-items: stretch;
  gap: 4px;
  height: 100%;
  margin-right: auto;
  margin-left: 28px;
}
.nav-link {
  display: flex;
  align-items: center;
  padding: 0 14px;
  font-size: 13px;
  color: #c6d6e4;
  text-decoration: none;
  border-bottom: 2px solid transparent;
}
.nav-link:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}
.nav-link.router-link-active {
  color: #fff;
  border-bottom-color: var(--accent);
  background: rgba(255, 255, 255, 0.08);
}
.hint {
  font-size: 12px;
  color: #b8c8d6;
}
.app-main {
  padding: 0;
  background: #f5f7fa;
}
</style>
