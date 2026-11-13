<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Cpu, Connection, Files, Key, Lock, Monitor, Refresh } from '@element-plus/icons-vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import ServerPanel from '../components/settings/ServerPanel.vue'
import ModelPanel from '../components/settings/ModelPanel.vue'
import RepoPanel from '../components/settings/RepoPanel.vue'
import ProjectEnvPanel from '../components/settings/ProjectEnvPanel.vue'
import EnvTemplatePanel from '../components/settings/EnvTemplatePanel.vue'
import SecretPanel from '../components/settings/SecretPanel.vue'

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
type Section = 'servers' | 'models' | 'envTemplates' | 'envs' | 'secrets' | 'repos'

interface SectionMeta {
  key: Section
  label: string
  desc: string
  icon: typeof Monitor
}

const SECTIONS: SectionMeta[] = [
  { key: 'servers', label: '服务器', desc: '连接与运行环境', icon: Monitor },
  { key: 'models', label: '模型连接', desc: '推理服务与密钥', icon: Cpu },
  { key: 'repos', label: '代码仓库', desc: '授权与连接', icon: Connection },
  { key: 'envTemplates', label: '环境模板', desc: 'dev/test/prod 默认值', icon: Files },
  { key: 'envs', label: '项目环境', desc: '覆盖 · 注入变量 · 重新检测', icon: Key },
  { key: 'secrets', label: '密钥库', desc: 'secret:// 引用与轮换', icon: Lock }
]

/** 高级分区：默认收起，避免首次进入就被六个入口劝退。 */
const ADVANCED: Section[] = ['envTemplates', 'envs', 'secrets']
const BASIC = SECTIONS.filter((s) => !ADVANCED.includes(s.key))
const ADVANCED_SECTIONS = SECTIONS.filter((s) => ADVANCED.includes(s.key))

const route = useRoute()
const requested = String(route.query.section ?? '')
const active = ref<Section>(
  SECTIONS.some((s) => s.key === requested) ? requested as Section : 'servers')
/** 深链进高级分区（如 ?section=envs）时自动展开该分组。 */
const advancedOpen = ref(ADVANCED.includes(active.value))

function selectSection(key: Section): void {
  active.value = key
  if (ADVANCED.includes(key)) advancedOpen.value = true
}
const serverRef = ref<InstanceType<typeof ServerPanel> | null>(null)
const modelRef = ref<InstanceType<typeof ModelPanel> | null>(null)
const envRef = ref<InstanceType<typeof ProjectEnvPanel> | null>(null)
const envTemplateRef = ref<InstanceType<typeof EnvTemplatePanel> | null>(null)
const secretRef = ref<InstanceType<typeof SecretPanel> | null>(null)
const repoRef = ref<InstanceType<typeof RepoPanel> | null>(null)
const refreshing = ref(false)

const current = computed(() => SECTIONS.find((s) => s.key === active.value) ?? SECTIONS[0])

/** 切到某个分区时拉一次最新数据（面板首次挂载已加载，这里处理"改动后回来"）。 */
watch(active, (value) => {
  if (value === 'servers') void serverRef.value?.load()
  else if (value === 'models') void modelRef.value?.load()
  else if (value === 'envTemplates') void envTemplateRef.value?.load()
  else if (value === 'envs') void envRef.value?.load()
  else if (value === 'secrets') void secretRef.value?.load()
  else void repoRef.value?.load()
})

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    if (active.value === 'servers') await serverRef.value?.load()
    else if (active.value === 'models') await modelRef.value?.load()
    else if (active.value === 'envTemplates') await envTemplateRef.value?.load()
    else if (active.value === 'envs') await envRef.value?.load()
    else if (active.value === 'secrets') await secretRef.value?.load()
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
            v-for="s in BASIC" :key="s.key"
            type="button" class="nav-card"
            :class="{ active: active === s.key }"
            @click="selectSection(s.key)"
          >
            <span class="nav-icon"><el-icon><component :is="s.icon" /></el-icon></span>
            <span class="nav-copy">
              <span class="nav-title">{{ s.label }}</span>
              <span class="nav-desc">{{ s.desc }}</span>
            </span>
          </button>

          <button type="button" class="nav-group-toggle" @click="advancedOpen = !advancedOpen">
            <span>{{ advancedOpen ? '收起高级设置' : '高级设置' }}</span>
            <span class="nav-chevron" :class="{ open: advancedOpen }">›</span>
          </button>
          <template v-if="advancedOpen">
            <button
              v-for="s in ADVANCED_SECTIONS" :key="s.key"
              type="button" class="nav-card sub"
              :class="{ active: active === s.key }"
              @click="selectSection(s.key)"
            >
              <span class="nav-icon"><el-icon><component :is="s.icon" /></el-icon></span>
              <span class="nav-copy">
                <span class="nav-title">{{ s.label }}</span>
                <span class="nav-desc">{{ s.desc }}</span>
              </span>
            </button>
          </template>
        </aside>

        <!-- 右：内容卡片（只挂载当前分区：首开不再同时发六组请求） -->
        <section class="settings-card" :aria-label="current.label">
          <ServerPanel v-if="active === 'servers'" ref="serverRef" />
          <ModelPanel v-else-if="active === 'models'" ref="modelRef" />
          <RepoPanel v-else-if="active === 'repos'" ref="repoRef" />
          <EnvTemplatePanel v-else-if="active === 'envTemplates'" ref="envTemplateRef" />
          <ProjectEnvPanel v-else-if="active === 'envs'" ref="envRef" />
          <SecretPanel v-else-if="active === 'secrets'" ref="secretRef" />
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
  padding: 8px clamp(16px, 2.4vw, 40px) 40px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.settings-shell {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
  max-width: 1200px;
  margin: 0 auto;
}
.settings-nav {
  display: flex;
  position: sticky;
  top: 0;
  flex-direction: column;
  gap: 4px;
}
.nav-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 11px;
  width: 100%;
  padding: 11px 13px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease,
    box-shadow var(--dur-fast) ease;
}
.nav-card:hover {
  background: var(--surface-hover);
}
.nav-card.active {
  background: var(--panel);
  border-color: var(--line-soft);
  box-shadow: var(--shadow-1);
}
/* 选中位：左侧 2px 蓝条，和侧栏会话选中保持同一种「定位线」语言 */
.nav-card.active::before {
  position: absolute;
  top: 13px;
  bottom: 13px;
  left: -1px;
  width: 2px;
  content: '';
  background: var(--accent);
  border-radius: var(--radius-pill);
}
.nav-group-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 13px;
  font-family: inherit;
  font-size: var(--text-2xs);
  color: var(--ink-faint);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.nav-group-toggle:hover {
  color: var(--ink-soft);
  background: var(--surface-hover);
}
.nav-chevron {
  display: inline-block;
  font-size: 13px;
  line-height: 1;
  transition: transform var(--dur-fast) ease;
}
.nav-chevron.open {
  transform: rotate(90deg);
}
/* 高级分区：缩进一级，视觉上从属于上方常用入口 */
.nav-card.sub {
  padding-left: 22px;
}
.nav-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-top: -1px;
  font-size: 14px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.nav-card:hover .nav-icon {
  color: var(--ink-soft);
}
.nav-card.active .nav-icon {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.nav-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.nav-title {
  font-size: var(--text-sm);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink-soft);
  transition: color var(--dur-fast) ease;
}
.nav-card.active .nav-title {
  color: var(--ink);
}
.nav-desc {
  overflow: hidden;
  font-size: var(--text-2xs);
  line-height: 1.5;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.settings-card {
  min-width: 0;
  padding: clamp(18px, 2vw, 26px) clamp(18px, 2.2vw, 30px) clamp(22px, 2.4vw, 32px);
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
    gap: 12px;
  }
  /* 分区导航改为顶部横向吸顶条：不再占掉大半屏后再滚动内容 */
  .settings-nav {
    position: sticky;
    top: 0;
    z-index: 6;
    flex-direction: row;
    flex-wrap: nowrap;
    gap: 6px;
    padding: 8px 0;
    overflow-x: auto;
    scrollbar-width: none;
    background: var(--bg);
  }
  .settings-nav::-webkit-scrollbar {
    display: none;
  }
  .nav-card {
    flex: 0 0 auto;
    align-items: center;
    gap: 6px;
    width: auto;
    padding: 6px 11px 6px 9px;
    white-space: nowrap;
    background: var(--panel);
    border-color: var(--line-soft);
    border-radius: var(--radius-pill);
  }
  .nav-card.active {
    background: var(--accent-soft);
    border-color: transparent;
    box-shadow: none;
  }
  .nav-card.active::before {
    content: none;
  }
  /* 手机端高级入口也是一枚可横滑的胶囊，不换行 */
  .nav-group-toggle {
    flex: 0 0 auto;
    width: auto;
    padding: 6px 11px;
    color: var(--ink-soft);
    white-space: nowrap;
    background: var(--panel);
    border: 1px dashed var(--line-soft);
    border-radius: var(--radius-pill);
  }
  .nav-card.sub {
    padding-left: 9px;
  }
  .nav-icon {
    width: 20px;
    height: 20px;
    margin-top: 0;
    font-size: 13px;
    background: none;
  }
  .nav-card.active .nav-icon {
    background: none;
  }
  .nav-desc {
    display: none;
  }
}

/* 手机：内容卡片内边距收紧 */
@media (max-width: 600px) {
  .settings-body {
    padding: 0 12px calc(16px + env(safe-area-inset-bottom));
  }
  .settings-card {
    padding: 16px 14px 18px;
  }
  .nav-title {
    font-size: 12.5px;
  }
}
</style>
