<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Notebook, Plus, Fold, Expand, Search, Setting, MoreFilled, SwitchButton } from '@element-plus/icons-vue'
import { logout } from '../../api/auth'
import { appStore } from '../../stores/app'

/**
 * 左侧栏 —— 结构对齐 DSH：
 * 品牌行（Logo + 折叠）/「新会话」软底按钮 / 一组入口（对话、流水线、设置）/「工作区」分组标题
 * （带搜索与更多）/ 会话列表（时间 hover 才出现）/ 底部用户行。
 *
 * 层级全靠白度差：侧栏 #f5f6f8 比主区 #f3f4f6 略亮一档，只在对主区一侧画一条发丝线。
 */
const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ 'update:collapsed': [value: boolean] }>()

const route = useRoute()
const router = useRouter()
const identity = computed(() => appStore.identity)

const entries = [
  { to: '/chat', name: 'chat', icon: ChatDotRound, label: '对话' },
  { to: '/pipeline', name: 'pipeline', icon: Notebook, label: '流水线' }
]

const activeName = computed(() => String(route.name ?? ''))
const sessions = computed(() => appStore.sessions)

function toggle() {
  emit('update:collapsed', !props.collapsed)
}

function newSession() {
  appStore.newSession()
  void router.push('/chat')
}

function openSession(id: string) {
  appStore.selectSession(id)
  void router.push('/chat')
}

async function doLogout() {
  await logout()
  void router.push('/login')
}

/** 相对时间：比绝对时间短，且不会出现「11-08 15:42」这种要换算的字符串。 */
function formatTime(ms: number): string {
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}
</script>

<template>
  <aside class="aside" :class="{ collapsed }">
    <div class="aside-inner">
      <!-- 品牌行 -->
      <div class="aside-top">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">运</span>
          <span class="brand-text">研发运维智能体</span>
        </div>
        <button type="button" class="icon-btn" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="toggle">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
      </div>

      <!-- 滚动区：入口 + 工作区列表 -->
      <div class="aside-scroll">
        <button type="button" class="new-chat" @click="newSession">
          <el-icon><Plus /></el-icon>
          <span>新会话</span>
        </button>

        <nav class="nav" aria-label="主导航">
          <router-link
            v-for="e in entries"
            :key="e.name"
            :to="e.to"
            class="nav-entry"
            :class="{ active: activeName === e.name }"
          >
            <el-icon><component :is="e.icon" /></el-icon>
            <span>{{ e.label }}</span>
          </router-link>
          <button type="button" class="nav-entry is-muted" disabled title="设置面板待接入">
            <el-icon><Setting /></el-icon>
            <span>设置</span>
          </button>
        </nav>

        <div class="group-head">
          <span class="group-title">工作区</span>
          <span class="group-actions">
            <button type="button" class="icon-btn sm" disabled title="会话搜索待接入">
              <el-icon><Search /></el-icon>
            </button>
            <button type="button" class="icon-btn sm" disabled title="更多操作待接入">
              <el-icon><MoreFilled /></el-icon>
            </button>
          </span>
        </div>

        <div class="session-list">
          <button
            v-for="s in sessions"
            :key="s.id"
            type="button"
            class="session-item"
            :class="{ active: s.id === appStore.sessionId }"
            @click="openSession(s.id)"
          >
            <span class="session-dot" aria-hidden="true" />
            <span class="session-title">{{ s.title }}</span>
            <span class="session-time">{{ formatTime(s.at) }}</span>
          </button>
          <p v-if="!sessions.length" class="session-empty">
            还没有会话，点上方「新会话」开始。
          </p>
        </div>
      </div>

      <!-- 底部用户行 -->
      <div class="aside-bottom">
        <div class="user-chip">
          <span class="user-avatar" aria-hidden="true">{{ (identity.displayName || identity.userId).slice(0, 1).toUpperCase() }}</span>
          <div class="user-meta">
            <span class="user-name">{{ identity.displayName }}</span>
            <span class="user-hint">{{ identity.role }} · 内网环境</span>
          </div>
          <button type="button" class="logout-btn" title="退出登录" @click="doLogout">
            <el-icon><SwitchButton /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.aside {
  display: flex;
  flex: 0 0 var(--sidebar-width-260);
  flex-direction: column;
  width: var(--sidebar-width-260);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--sidebar-background-color);
  border-right: 1px solid var(--line-soft);
  transition: flex-basis var(--dur-base) var(--ease-out), width var(--dur-base) var(--ease-out);
}
.aside.collapsed {
  flex-basis: 0;
  width: 0;
  border-right: none;
}
.aside-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: var(--sidebar-width-260);
  min-height: 0;
}

/* 窄屏（≤900px）：侧栏不再常驻 —— 260px 会把主区挤到只剩一百来像素。
   这里让侧栏收缩为 0，入口靠浏览器前进/后退与路由保持可达；
   折叠按钮在此宽度下隐藏，避免用户对着一个 0 宽侧栏点开。 */
@media (max-width: 900px) {
  .aside {
    flex-basis: 0;
    width: 0;
    border-right: none;
  }
  .aside-inner {
    display: none;
  }
}

/* ---- 品牌行 ---- */
.aside-top {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: var(--header-height);
  padding: 0 10px 0 14px;
}
.brand {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
/* 品牌标记：深灰实心块 + 白字 —— 不占用交互蓝，浅色底上也够醒目 */
.brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-mark-ink);
  letter-spacing: -0.02em;
  background: var(--brand-mark-bg);
  border-radius: 7px;
}
.brand-text {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 图标按钮（多处复用） */
.icon-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  font-size: 14px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.icon-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.icon-btn.sm {
  width: 22px;
  height: 22px;
  font-size: 13px;
}

/* ---- 滚动区 ---- */
.aside-scroll {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
  padding: 4px 8px 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 新会话：DSH 用软底块做主按钮，比描边按钮轻、比纯文字重 */
.new-chat {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--ink);
  cursor: pointer;
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.new-chat:hover {
  background: var(--surface-active);
  border-color: var(--line);
}

/* 入口 */
.nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
  margin-top: 8px;
}
.nav-entry {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 34px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--ink-soft);
  text-decoration: none;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.nav-entry:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
/* 选中：不加蓝色块，只用左侧 2px 蓝条 + 提亮 —— 侧栏要安静 */
.nav-entry.active {
  position: relative;
  color: var(--ink);
  background: var(--surface-hover);
}
.nav-entry.active::before {
  position: absolute;
  top: 50%;
  left: 0;
  width: 2px;
  height: 14px;
  content: '';
  background: var(--accent);
  border-radius: var(--radius-pill);
  transform: translateY(-50%);
}
.nav-entry.is-muted {
  color: var(--ink-muted);
  cursor: not-allowed;
}

/* 分组标题 */
.group-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 16px 10px 4px;
}
.group-title {
  flex: 1;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  letter-spacing: 0.06em;
}
.group-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.group-head:hover .group-actions {
  opacity: 1;
}

/* 会话条目 */
.session-list {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 32px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.session-item:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.session-item.active {
  color: var(--ink);
  background: var(--surface-hover);
}
.session-dot {
  flex: none;
  width: 4px;
  height: 4px;
  background: currentColor;
  border-radius: 50%;
  opacity: 0.5;
}
.session-item.active .session-dot {
  background: var(--accent);
  opacity: 1;
}
.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 时间默认隐去，hover 让位显示 —— 列表安静，信息不丢 */
.session-time {
  flex: none;
  font-size: 11px;
  color: var(--ink-muted);
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.session-item:hover .session-time,
.session-item.active .session-time {
  opacity: 1;
}
.session-empty {
  padding: 6px 10px;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-muted);
}

/* ---- 底部用户行 ---- */
.aside-bottom {
  flex: none;
  padding: 8px;
  border-top: 1px solid var(--line-soft);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease;
}
.user-chip:hover {
  background: var(--surface-hover);
}
.user-avatar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-active);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}
.user-name {
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.user-hint {
  font-size: 10.5px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.logout-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-left: auto;
  color: rgb(0 0 0 / 45%);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 8px;
}
.logout-btn:hover {
  color: #f56c6c;
  background: rgb(0 0 0 / 5%);
}
</style>
