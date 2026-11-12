<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Close } from '@element-plus/icons-vue'
import {
  decommission, listProjects, scaffoldApply, scaffoldPreview,
  type ProjectRow, type ScaffoldPlan,
} from '../api/project'
import { appStore } from '../stores/app'
import PageHeader from '../layouts/components/PageHeader.vue'
import ProjectTree from '../components/project/ProjectTree.vue'
import ResizableSplitter from '../components/project/ResizableSplitter.vue'
import ProjectOverview from '../components/project/ProjectOverview.vue'
import FilePreview from '../components/project/FilePreview.vue'
import TabbedFiles from '../components/project/TabbedFiles.vue'
import type { TreeNode } from '../components/project/useProjectTree'
import { notifyError, confirmAction } from '../utils/feedback'

/**
 * 项目管理工作台（原"项目列表"重构）：
 *
 * 布局：全局导航（AppLayout）｜ 左：文档树 25%（可拖拽 15–50%）｜ 右：详情 75%
 * 交互：
 * - 点根节点 → 右侧项目概览（全局配置 / git 状态 / 仓库复制 / 运行流水线）
 * - 点文件节点 → 右侧 Tab 打开文件预览（行号 / 元数据 / 编辑·下载·运行流水线）
 * - 未选中 → 空状态引导
 */
const router = useRouter()

const projects = ref<ProjectRow[]>([])
const loading = ref(false)
const leftPct = ref(25)
const selected = shallowRef<TreeNode | null>(null)
const tabbed = ref<InstanceType<typeof TabbedFiles> | null>(null)

const selectedId = computed(() => selected.value?.id ?? null)
/** 手机端主从切换：false=文件树，true=详情；桌面端该状态不参与布局。 */
const mobileDetail = ref(false)

/** 手机端详情页顶部的返回条标题。 */
const mobileTitle = computed(() => {
  if (!selected.value) return ''
  if (selected.value.kind === 'file') return selected.value.name
  return selected.value.project?.name ?? selected.value.name
})

/* ---------------------------------------------------------------- 流水线脚手架 */

const scaffoldDialog = ref(false)
const scaffoldPlan = shallowRef<ScaffoldPlan | null>(null)
const scaffoldTarget = ref('auto')
const scaffoldLoading = ref(false)
const scaffoldApplying = ref(false)
const scaffoldOverwrite = ref(false)
/** 默认存平台侧（生成即可运行）；勾选则同时写进仓库 ci/（随代码 review，需自行提交）。 */
const scaffoldToRepo = ref(false)

/** 当前作用项目：优先树选中项，其次全局 projectId。 */
const scaffoldProjectId = computed(() => {
  const fromTree = selected.value?.projectId
  if (fromTree) return fromTree
  return projects.value.some((p) => p.id === appStore.projectId) ? appStore.projectId : ''
})

async function openScaffold(): Promise<void> {
  if (!scaffoldProjectId.value) {
    ElMessage.warning('请先在左侧选中一个项目')
    return
  }
  scaffoldDialog.value = true
  scaffoldLoading.value = true
  scaffoldPlan.value = null
  try {
    scaffoldPlan.value = await scaffoldPreview(scaffoldProjectId.value, scaffoldTarget.value)
  } catch (e) {
    notifyError(e, '生成预览失败')
    scaffoldDialog.value = false
  } finally {
    scaffoldLoading.value = false
  }
}

async function reloadScaffold(): Promise<void> {
  if (!scaffoldProjectId.value) return
  scaffoldLoading.value = true
  try {
    scaffoldPlan.value = await scaffoldPreview(scaffoldProjectId.value, scaffoldTarget.value)
  } catch (e) {
    notifyError(e, '生成预览失败')
  } finally {
    scaffoldLoading.value = false
  }
}

async function applyScaffold(): Promise<void> {
  if (!scaffoldProjectId.value || !scaffoldPlan.value) return
  scaffoldApplying.value = true
  try {
    const mode = scaffoldToRepo.value ? 'repo' : 'platform'
    const result = await scaffoldApply(
      scaffoldProjectId.value, scaffoldTarget.value, scaffoldOverwrite.value, mode)
    if (!result.written.length) {
      ElMessage.warning(result.note ?? '没有写入任何文件（可能已存在；勾选"覆盖"后重试）')
      return
    }
    ElMessage.success(mode === 'repo'
      ? `已写入仓库：${result.written.join('、')}（记得提交后再运行）`
      : '已保存到平台，可直接在流水线页运行')
    scaffoldDialog.value = false
  } catch (e) {
    notifyError(e, '写入失败')
  } finally {
    scaffoldApplying.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    projects.value = await listProjects()
  } catch (e) {
    notifyError(e, '加载项目失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onSelect(node: TreeNode): void {
  selected.value = node
  mobileDetail.value = true
  // 文件节点 → 打开/聚焦一个 Tab；目录或根 → 右侧概览，Tab 区隐藏
  if (node.kind === 'file') {
    tabbed.value?.openTab(node.path, node.name)
  }
}

/** 手机端：从详情返回文件树。 */
function backToTree(): void {
  mobileDetail.value = false
}

function clearSelection(): void {
  selected.value = null
  mobileDetail.value = false
}

async function onAction(action: 'refresh' | 'rename' | 'delete', node: TreeNode): Promise<void> {
  if (action === 'refresh') {
    if (node.kind === 'project') await load()
    else ElMessage.info(`已请求刷新：${node.name}（目录缓存刷新待接入）`)
    return
  }
  if (action === 'rename') {
    ElMessage.info('重命名待接入（需后端 fs/rename 端点）')
    return
  }
  if (action === 'delete') {
    if (node.kind === 'project') {
      const confirmed = await confirmAction(
        `下线项目「${node.name}」？平台将移除托管元数据与工作树，你的本地目录不受影响。`,
        '删除项目'
      )
      if (!confirmed) return
      try {
        await decommission(node.projectId)
        ElMessage.success(`已下线 ${node.name}`)
        if (selected.value?.projectId === node.projectId) selected.value = null
        await load()
      } catch (e) {
        notifyError(e, '下线失败')
      }
      return
    }
    ElMessage.info('文件删除待接入（需后端 fs/delete 端点，且仅限工作树内）')
  }
}

function runPipeline(projectId: string): void {
  appStore.projectId = projectId
  void router.push('/pipeline')
}

const overviewProject = computed(() =>
  selected.value?.kind !== 'file' ? selected.value?.project ?? null : null)
</script>

<template>
  <div class="workbench-page">
    <PageHeader title="项目管理" subtitle="文档树 · 详情面板 · 流水线入口">
      <template #actions>
        <button type="button" class="hdr-btn" @click="openScaffold">
          <span class="hdr-long">生成流水线脚本</span><span class="hdr-short">生成脚本</span>
        </button>
        <button type="button" class="hdr-btn primary" @click="router.push('/projects/intake')">
          <span class="hdr-long">接入项目</span><span class="hdr-short">接入</span>
        </button>
      </template>
    </PageHeader>

    <div class="wb-body" :class="mobileDetail ? 'mpane-detail' : 'mpane-tree'">
      <!-- 左：文档树 -->
      <div class="wb-left" :style="{ width: `${leftPct}%` }">
        <ProjectTree
          :projects="projects"
          :selected-id="selectedId"
          @select="onSelect"
          @action="onAction"
          @intake="router.push('/projects/intake')"
        />
      </div>

      <ResizableSplitter v-model="leftPct" :min="15" :max="50" />

      <!-- 右：详情区 -->
      <div class="wb-right">
        <!-- 手机端详情页顶部：返回文件树 + 当前节点 -->
        <div class="wb-mobile-bar">
          <button type="button" class="wb-back" @click="backToTree">
            <el-icon><ArrowLeft /></el-icon> 文件树
          </button>
          <span class="wb-mobile-title">{{ mobileTitle }}</span>
          <button type="button" class="wb-mobile-close" aria-label="关闭详情" @click="clearSelection">
            <el-icon><Close /></el-icon>
          </button>
        </div>
        <!-- 项目概览（根/目录节点） -->
        <ProjectOverview
          v-if="overviewProject"
          :project="overviewProject"
          @run-pipeline="runPipeline(overviewProject.id)"
        />

        <!-- 文件 Tab 区（保持挂载避免 Tab 状态丢失；无 Tab 时显示空状态） -->
        <TabbedFiles
          v-show="selected?.kind === 'file'"
          ref="tabbed"
          class="wb-tabs"
        >
          <template #default="{ tab }">
            <FilePreview
              :path="tab.path"
              :name="tab.name"
              :project-id="selected?.projectId ?? ''"
              @run-pipeline="runPipeline(selected?.projectId ?? '')"
            />
          </template>
        </TabbedFiles>

        <!-- 空状态：未选中任何节点（TabbedFiles 内部有自己的无 Tab 空态？这里统一处理） -->
        <div v-if="!selected" class="wb-empty">
          <div class="we-illus" aria-hidden="true">
            <svg width="96" height="64" viewBox="0 0 96 64" fill="none">
              <rect x="8" y="8" width="36" height="48" rx="6" fill="var(--surface-sunken)" />
              <rect x="14" y="16" width="18" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.5" />
              <rect x="14" y="24" width="24" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.35" />
              <rect x="14" y="32" width="14" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.25" />
              <rect x="52" y="8" width="36" height="48" rx="6" fill="var(--panel)" stroke="var(--line-soft)" />
              <rect x="58" y="16" width="24" height="3" rx="1.5" fill="var(--accent)" opacity="0.4" />
              <rect x="58" y="24" width="18" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.3" />
              <rect x="58" y="40" width="24" height="10" rx="3" fill="var(--accent-soft)" />
            </svg>
          </div>
          <p class="we-title">从左侧选择一个项目或文件</p>
          <p class="we-sub">点击项目名查看配置概览，点击文件打开预览标签页</p>
          <button v-if="!projects.length" type="button" class="we-btn" @click="router.push('/projects/intake')">
            接入第一个项目
          </button>
        </div>
      </div>
    </div>

    <!-- 生成流水线定义：预览 → 保存（默认存平台侧，勾选才写进仓库 ci/） -->
    <el-dialog v-model="scaffoldDialog" title="生成流水线定义" width="720px">
      <div class="scaffold">
        <div class="sc-row">
          <span class="sc-label">项目</span>
          <b class="mono">{{ scaffoldProjectId || '—' }}</b>
          <span v-if="scaffoldPlan" class="sc-stack">
            识别：{{ scaffoldPlan.stack }}
            <template v-if="scaffoldPlan.meta?.deployTarget"> · {{ scaffoldPlan.meta.deployTarget }}</template>
            <template v-if="scaffoldPlan.meta?.port">
              · {{ scaffoldPlan.meta.port }}{{ scaffoldPlan.meta.healthPath }}
            </template>
          </span>
          <el-select v-model="scaffoldTarget" size="small" class="sc-target" @change="reloadScaffold">
            <el-option label="自动（按仓库能力）" value="auto" />
            <el-option label="本机 Docker 容器" value="docker" />
            <el-option label="本机直接运行" value="host" />
            <el-option label="远程服务器" value="server" />
          </el-select>
        </div>

        <el-alert
          v-for="(note, i) in (scaffoldPlan?.notes ?? [])" :key="i"
          class="sc-note" type="info" :closable="false" :title="note" show-icon
        />

        <div v-if="scaffoldLoading" class="sc-loading">扫描仓库中…</div>
        <template v-else-if="scaffoldPlan">
          <div v-for="f in scaffoldPlan.files" :key="f.path" class="sc-file">
            <div class="sc-file-head">
              <code class="mono">{{ f.path }}</code>
              <span class="sc-file-hint">
                {{ scaffoldToRepo ? '写入仓库（需自行提交）' : '保存到平台（生成即可运行）' }}
              </span>
            </div>
            <pre class="sc-code mono">{{ f.content }}</pre>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="sc-foot">
          <div class="sc-foot-checks">
            <el-checkbox v-model="scaffoldToRepo" size="small" class="sc-overwrite">
              写入仓库 ci/（随代码 review，不自动提交）
            </el-checkbox>
            <el-checkbox v-model="scaffoldOverwrite" size="small" class="sc-overwrite">
              覆盖已存在文件
            </el-checkbox>
          </div>
          <div class="sc-foot-actions">
            <el-button @click="scaffoldDialog = false">取消</el-button>
            <el-button :disabled="!scaffoldPlan" :loading="scaffoldLoading" @click="reloadScaffold">
              重新扫描
            </el-button>
            <el-button type="primary" :disabled="!scaffoldPlan" :loading="scaffoldApplying" @click="applyScaffold">
              {{ scaffoldToRepo ? '写入仓库 ci/' : '保存到平台' }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workbench-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.wb-body {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}
.wb-left {
  flex: none;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: var(--panel);
  border-right: 1px solid var(--line-soft);
}
.wb-right {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
}
.wb-tabs {
  height: 100%;
}

/* 空状态 */
.wb-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  text-align: center;
}
.we-title {
  margin: 12px 0 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-muted);
}
.we-sub {
  margin: 0;
  font-size: 12px;
  color: var(--ink-faint);
}
.we-btn {
  height: 32px;
  padding: 0 16px;
  margin-top: 12px;
  font-family: inherit;
  font-size: 13px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-md);
}
.we-btn:hover {
  background: var(--accent-hover);
}

.hdr-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.hdr-btn.primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
/* 顶栏按钮在手机上用短文案，给标题留出空间 */
.hdr-short {
  display: none;
}
@media (max-width: 600px) {
  .hdr-long {
    display: none;
  }
  .hdr-short {
    display: inline;
  }
  .hdr-btn {
    padding: 0 10px;
  }
}

/* 生成脚本对话框 */
.scaffold {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 58vh;
  overflow-y: auto;
}
.sc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.sc-label {
  color: var(--ink-muted);
}
.sc-stack {
  padding: 1px 8px;
  font-size: 11px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.sc-target {
  width: 160px;
  margin-left: auto;
}
.sc-note {
  margin: 0;
}
.sc-loading {
  padding: 32px 0;
  font-size: 12px;
  color: var(--ink-faint);
  text-align: center;
}
.sc-file {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.sc-file-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--surface-hover);
}
.sc-file-head code {
  font-size: 12px;
  color: var(--ink);
}
.sc-file-hint {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.sc-code {
  margin: 0;
  padding: 10px 12px;
  max-height: 220px;
  overflow: auto;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre;
  background: var(--code-bg);
}
.sc-overwrite {
  margin-right: 0;
}

.sc-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.sc-foot-checks {
  display: flex;
  flex: 1 1 auto;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.sc-foot-actions {
  display: flex;
  flex: none;
  gap: 8px;
  margin-left: auto;
}

/* 手机端详情页顶部返回条：桌面端始终隐藏 */
.wb-mobile-bar {
  display: none;
}

@media (max-width: 900px) {
  /* 主从布局：一次只显示文件树或详情，避免上下各占一半导致两边都滚不全 */
  .wb-body.mpane-tree .wb-right {
    display: none;
  }
  .wb-body.mpane-detail .wb-left {
    display: none;
  }
  .wb-left {
    width: 100% !important;
    height: auto;
    flex: 1 1 0;
    border-right: 0;
  }
  .wb-body :deep(.resizer) {
    display: none;
  }
  .wb-right {
    flex: 1 1 0;
    min-height: 0;
  }
  .wb-mobile-bar {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
    height: 40px;
    padding: 0 8px;
    background: var(--panel);
    border-bottom: 1px solid var(--line-soft);
  }
  .wb-back,
  .wb-mobile-close {
    display: inline-flex;
    flex: none;
    align-items: center;
    gap: 2px;
    height: 28px;
    padding: 0 8px;
    font-family: inherit;
    font-size: 12.5px;
    color: var(--ink-soft);
    cursor: pointer;
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
  }
  .wb-back:active {
    background: var(--surface-hover);
  }
  .wb-mobile-close {
    width: 28px;
    padding: 0;
    justify-content: center;
  }
  .wb-mobile-title {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    font-size: 12.5px;
    font-weight: 600;
    color: var(--ink);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* 概览/文件区在移动端各自占满剩余高度，内部滚动到底 */
  .wb-tabs {
    height: auto;
    flex: 1 1 0;
    min-height: 0;
  }
  .wb-empty {
    padding: 24px 16px;
  }
}

/* 手机：生成脚本对话框的信息行/目标选择换行，避免内容被右侧裁掉 */
@media (max-width: 600px) {
  .sc-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .sc-stack {
    flex: 1 1 100%;
    white-space: normal;
  }
  .sc-target {
    width: 100%;
    margin-left: 0;
  }
  .sc-file-head {
    flex-wrap: wrap;
  }
  .sc-file-hint {
    margin-left: 0;
  }
  .sc-code {
    max-height: 40vh;
  }
  .scaffold {
    max-height: none;
    overflow: visible;
  }
  .sc-foot {
    flex-direction: column;
    align-items: stretch;
  }
  .sc-foot-checks {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
  .sc-foot-actions {
    width: 100%;
    margin-left: 0;
  }
  .sc-foot-actions .el-button {
    flex: 1;
  }
}
</style>
