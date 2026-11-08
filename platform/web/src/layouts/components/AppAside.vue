<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Notebook, Plus, User, Fold, Expand } from '@element-plus/icons-vue'
import { currentUserId } from '../../api/http'
import { appStore } from '../../stores/app'

/**
 * 左侧栏：三段式 —— 顶部（Logo + 新会话，固定）/ 中间（导航 + 工作区列表，独立滚动）/ 底部（用户，mt-auto）。
 */
const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ 'update:collapsed': [value: boolean] }>()

const route = useRoute()
const router = useRouter()
const userId = currentUserId()

const entries = [
  { to: '/chat', name: 'chat', icon: ChatDotRound, label: '对话' },
  { to: '/pipeline', name: 'pipeline', icon: Notebook, label: '流水线 / 动作' }
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

function formatTime(ms: number): string {
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<template>
  <aside class="aside" :class="{ collapsed }">
    <div class="aside-inner">
      <!-- 顶部：Logo + 折叠（固定，不滚动） -->
      <div class="aside-top">
        <div class="brand">
          <span class="brand-mark">运</span>
          <span class="brand-text">研发运维智能体</span>
        </div>
        <button type="button" class="icon-btn" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="toggle">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
      </div>

      <!-- 中间：导航 + 工作区列表（独立滚动） -->
      <div class="aside-scroll">
        <button type="button" class="new-chat" @click="newSession">
          <el-icon><Plus /></el-icon>
          <span>新会话</span>
        </button>

        <nav class="nav">
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
        </nav>

        <div class="divider" />

        <div class="group-title">
          最近会话
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
            <span class="session-title">{{ s.title }}</span>
            <span class="session-time mono">{{ formatTime(s.at) }}</span>
          </button>
          <div v-if="!sessions.length" class="session-empty">
            还没有会话，点上方「新会话」开始。
          </div>
        </div>
      </div>

      <!-- 底部：用户（mt-auto 固定） -->
      <div class="aside-bottom">
        <div class="user-chip">
          <el-icon><User /></el-icon>
          <div class="user-meta">
            <span class="mono">{{ userId }}</span>
            <span class="user-hint">内网环境 · 写操作需确认</span>
          </div>
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
  border-right: 0.5px solid rgb(0 0 0 / 8%);
  transition: flex-basis 0.24s ease, width 0.24s ease;
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

/* 顶部 */
.aside-top {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: 60px;
  padding: 0 14px;
  border-bottom: 1px solid rgb(0 0 0 / 6%);
}
.brand {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: var(--accent);
  border-radius: 9px;
}
.brand-text {
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.icon-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: rgb(0 0 0 / 55%);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 7px;
}
.icon-btn:hover {
  color: var(--ink);
  background: rgb(0 0 0 / 5%);
}

/* 中间滚动区：min-height:0 是关键，否则 flex 子项会被内容撑破 */
.aside-scroll {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
  padding: 12px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.new-chat {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  color: rgb(0 0 0 / 78%);
  cursor: pointer;
  background: rgb(0 0 0 / 4%);
  border: 0;
  border-radius: 10px;
  transition: background 0.2s ease;
}
.new-chat:hover {
  background: rgb(0 0 0 / 7%);
}
.nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 4px;
}
.nav-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: rgb(0 0 0 / 78%);
  text-decoration: none;
  border-radius: 10px;
  transition: all 0.2s ease;
}
.nav-entry:hover {
  background: rgb(0 0 0 / 4%);
}
.nav-entry.active {
  color: var(--accent);
  background: var(--accent-soft);
}
.divider {
  flex: none;
  height: 1px;
  margin: 6px 0;
  background: rgb(0 0 0 / 6%);
}
.group-title {
  flex: none;
  padding: 4px 12px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: rgb(0 0 0 / 40%);
}
.session-list {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 2px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  font-family: inherit;
  font-size: 13px;
  color: rgb(0 0 0 / 70%);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 9px;
}
.session-item:hover {
  background: rgb(0 0 0 / 4%);
}
.session-item.active {
  color: var(--accent);
  background: var(--accent-soft);
}
.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.session-time {
  flex: none;
  font-size: 10px;
  color: #a0a6ad;
}
.session-empty {
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #a0a6ad;
}

/* 底部：mt-auto 固定在底 */
.aside-bottom {
  flex: none;
  padding: 12px;
  margin-top: auto;
  border-top: 1px solid rgb(0 0 0 / 6%);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  color: var(--ink-soft);
  background: rgb(0 0 0 / 4%);
  border-radius: 10px;
}
.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.user-hint {
  font-size: 10px;
  color: #a0a6ad;
}
</style>
