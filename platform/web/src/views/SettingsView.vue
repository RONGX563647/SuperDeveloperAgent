<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Cpu, Connection, Files, Key, Monitor, Refresh } from '@element-plus/icons-vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import ServerPanel from '../components/settings/ServerPanel.vue'
import ModelPanel from '../components/settings/ModelPanel.vue'
import RepoPanel from '../components/settings/RepoPanel.vue'
import ProjectEnvPanel from '../components/settings/ProjectEnvPanel.vue'
import EnvTemplatePanel from '../components/settings/EnvTemplatePanel.vue'

/**
 * 设置页（布局对齐「云端工作台」式工作台）：
 *
 * ┌ 标题 + 副标题 ────────────────────────── 刷新 / 返回 ┐
 * │ ┌ 左侧分组导航 ┐  ┌ 右侧内容卡片 ────────────────┐ │
 * │ │ 服务器       │  │ 分区标题            + 操作    │ │
 * │ │ 模型连接     │  │ 表单 / 列表                   │ │
 * │ └─────────────┘  └──────────────────────────────┘ │
 * └───────────────────────────────────────────────────┘
 *
 * 左侧是"分区导航"而不是 Tabs：设置项会越来越多（服务器/模型/仓库/通知），
 * 纵向分组可扩展、也能承载每个分区的说明文字。
 */
type Section = 'servers' | 'models' | 'envTemplates' | 'envs' | 'repos'

interface SectionMeta {
  key: Section
  label: string
  desc: string
  icon: typeof Monitor
}

const SECTIONS: SectionMeta[] = [
  { key: 'servers', label: '服务器', desc: '连接与运行环境', icon: Monitor },
  { key: 'models', label: '模型连接', desc: '推理服务与密钥', icon: Cpu },
  { key: 'envTemplates', label: '环境模板', desc: 'dev/test/prod 默认值', icon: Files },
  { key: 'envs', label: '项目环境', desc: '覆盖 · 注入变量 · 重新检测', icon: Key },
  { key: 'repos', label: '代码仓库', desc: '授权与连接', icon: Connection }
]

const route = useRoute()
const requested = String(route.query.section ?? '')
const active = ref<Section>(
  SECTIONS.some((s) => s.key === requested) ? requested as Section : 'servers')
const serverRef = ref<InstanceType<typeof ServerPanel> | null>(null)
const modelRef = ref<InstanceType<typeof ModelPanel> | null>(null)
const envRef = ref<InstanceType<typeof ProjectEnvPanel> | null>(null)
const envTemplateRef = ref<InstanceType<typeof EnvTemplatePanel> | null>(null)
const repoRef = ref<InstanceType<typeof RepoPanel> | null>(null)
const refreshing = ref(false)

const current = computed(() => SECTIONS.find((s) => s.key === active.value) ?? SECTIONS[0])

/** 切到某个分区时拉一次最新数据（面板首次挂载已加载，这里处理"改动后回来"）。 */
watch(active, (value) => {
  if (value === 'servers') void serverRef.value?.load()
  else if (value === 'models') void modelRef.value?.load()
  else if (value === 'envTemplates') void envTemplateRef.value?.load()
  else if (value === 'envs') void envRef.value?.load()
  else void repoRef.value?.load()
})

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    if (active.value === 'servers') await serverRef.value?.load()
    else if (active.value === 'models') await modelRef.value?.load()
    else if (active.value === 'envTemplates') await envTemplateRef.value?.load()
    else if (active.value === 'envs') await envRef.value?.load()
    else await repoRef.value?.load()
  } finally {
    refreshing.value = false
  }
}
</script>

<template>
  <div class="settings-page">
    <PageHeader title="云端工作台" subtitle="服务器、模型与部署环境">
      <template #actions>
        <el-button size="small" :icon="Refresh" :loading="refreshing" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <div class="settings-body">
      <div class="settings-shell">
        <!-- 左：分区导航 -->
        <aside class="settings-nav" aria-label="设置分区">
          <button
            v-for="s in SECTIONS" :key="s.key"
            type="button" class="nav-card"
            :class="{ active: active === s.key }"
            @click="active = s.key"
          >
            <span class="nav-icon"><el-icon><component :is="s.icon" /></el-icon></span>
            <span class="nav-copy">
              <span class="nav-title">{{ s.label }}</span>
              <span class="nav-desc">{{ s.desc }}</span>
            </span>
          </button>
        </aside>

        <!-- 右：内容卡片 -->
        <section class="settings-card" :aria-label="current.label">
          <ServerPanel v-show="active === 'servers'" ref="serverRef" />
          <ModelPanel v-show="active === 'models'" ref="modelRef" />
          <EnvTemplatePanel v-show="active === 'envTemplates'" ref="envTemplateRef" />
          <ProjectEnvPanel v-show="active === 'envs'" ref="envRef" />
          <RepoPanel v-show="active === 'repos'" ref="repoRef" />
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.settings-body {
  flex: 1 1 0;
  min-height: 0;
  padding: 8px 32px 32px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.settings-shell {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
  max-width: 1120px;
  margin: 0 auto;
}
.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.nav-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.nav-card:hover {
  background: var(--surface-hover);
}
.nav-card.active {
  background: var(--panel);
  border-color: var(--line-soft);
}
.nav-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-top: 1px;
  font-size: 15px;
  color: var(--ink-soft);
  border-radius: var(--radius-sm);
}
.nav-card.active .nav-icon {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.nav-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.nav-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}
.nav-desc {
  font-size: 11.5px;
  color: var(--ink-faint);
}

.settings-card {
  min-width: 0;
  padding: 22px 26px 26px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}

@media (max-width: 900px) {
  .settings-body {
    padding: 4px 16px 24px;
  }
  .settings-shell {
    grid-template-columns: minmax(0, 1fr);
  }
  .settings-nav {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .nav-card {
    flex: 1 1 180px;
  }
}

/* 手机：导航卡占满整行，卡片内边距收紧 */
@media (max-width: 600px) {
  .settings-body {
    padding: 0 12px calc(16px + env(safe-area-inset-bottom));
  }
  .settings-card {
    padding: 16px 14px 18px;
  }
  .nav-card {
    flex: 1 1 100%;
    padding: 10px 12px;
  }
  .nav-desc {
    display: none;
  }
}
</style>
