<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand">研发运维智能体平台</div>
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
.hint {
  font-size: 12px;
  color: #b8c8d6;
}
.app-main {
  padding: 0;
  background: #f5f7fa;
}
</style>
