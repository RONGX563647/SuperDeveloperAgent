<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Connection, Folder, Loading, Select, UserFilled, WarningFilled
} from '@element-plus/icons-vue'
import {
  giteeBindStatus,
  intakeLocal, intakeRemote, intakeStatus,
  type GiteeBinding, type GiteeRepo,
  type IntakeStatus, type RecipeReport
} from '../api/project'
import DirPickerDialog from '../components/DirPickerDialog.vue'
import GiteeRepoPicker from '../components/GiteeRepoPicker.vue'
import { appStore } from '../stores/app'
import { applySelection } from '../utils/projectId'

/**
 * 项目接入向导：
 * - Gitee 账号：**已迁移到「设置 → 代码仓库」**，这里只展示状态并跳转；
 * - 本地目录：现代选择器（快捷位置 + 面包屑 + 搜索 + 键盘）；
 * - Gitee 仓库：卡片式列表选择（支持私有库标记与键盘导航）。
 */
const router = useRouter()
const source = ref<'local' | 'remote'>('local')
const projectId = ref('')
const gitInitIfMissing = ref(false)

// Gitee 账号（只读状态；绑定/解绑在设置页）
const binding = ref<GiteeBinding | null>(null)
const giteeDialog = ref(false)
const selectedRepo = ref<GiteeRepo | null>(null)
const cloneTarget = ref('')

// 本地目录选择
const dirBrowser = ref(false)
const selectedDir = ref('')
const selectedIsGit = ref(false)

// 状态机
const phase = ref<'FORM' | 'RUNNING' | 'DONE' | 'FAILED'>('FORM')
const status = ref<IntakeStatus | null>(null)
const report = ref<RecipeReport | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const steps = ['VALIDATING', 'LINKING', 'RECIPE_CHECK', 'REGISTERED'] as const
const stepIndex = computed(() => {
  if (!status.value) return -1
  if (status.value.phase === 'INTAKE_FAILED') return -2
  const i = steps.indexOf(status.value.phase as (typeof steps)[number])
  return i < 0 ? steps.length - 1 : i
})

const idOk = computed(() => /^[A-Z][A-Z0-9_]{1,15}$/.test(projectId.value))
const canSubmit = computed(() => {
  if (!idOk.value) return false
  if (source.value === 'local') return !!selectedDir.value
  return !!selectedRepo.value && !!cloneTarget.value
})

// ---------------------------------------------------------------- 账号

async function loadBinding() {
  try {
    binding.value = await giteeBindStatus()
  } catch { /* 未登录态由路由守卫处理 */ }
}

// ---------------------------------------------------------------- Gitee 仓库选择

function openGitee() {
  giteeDialog.value = true
}

function pickRepo(r: GiteeRepo) {
  selectedRepo.value = r
  giteeDialog.value = false
  suggestId()
}

// ---------------------------------------------------------------- 目录选择（现代选择器）

/** 点"浏览"：打开内置的现代目录选择器（快捷位置 + 面包屑 + 搜索 + 键盘）。 */
function chooseDir(target: 'project' | 'clone') {
  dirPickTarget.value = target
  pickerInitial.value = target === 'clone'
    ? (cloneTarget.value || selectedDir.value)
    : selectedDir.value
  dirBrowser.value = true
}

/** 文件夹选择目标：本地项目目录 or Gitee 克隆目标目录。 */
const dirPickTarget = ref<'project' | 'clone'>('project')
const pickerInitial = ref('')

function onDirPicked(payload: { path: string; git: boolean }) {
  applyPickedDir(dirPickTarget.value, payload.path, payload.git)
}

function applyPickedDir(target: 'project' | 'clone', path: string, isGit: boolean) {
  if (target === 'clone') {
    cloneTarget.value = path
    if (!selectedIsGit.value) selectedIsGit.value = isGit
  } else {
    selectedDir.value = path
    selectedIsGit.value = isGit
    gitInitIfMissing.value = !isGit
  }
  suggestId()
}

function openDirBrowser() {
  chooseDir('project')
}

/** 上一次自动生成的 ID：用来区分"我们填的"和"用户手改的"。 */
const lastSuggestedId = ref('')

/**
 * 按当前选择推导项目 ID（规则见 utils/projectId）。
 *
 * <p>关键点：**跟着选择走，但不覆盖用户手改过的值**。
 * 之前的实现是"ID 非空就直接 return"，于是换一个目录/仓库后 ID 还是上一个项目名，
 * 提交时撞上已登记的 projectId。
 */
function suggestId() {
  const name = source.value === 'remote'
    ? (selectedRepo.value?.fullName.split('/')[1] ?? '')
    : (selectedDir.value.split(/[\\/]/).filter(Boolean).pop() ?? '')
  const next = applySelection(
    { current: projectId.value, lastSuggested: lastSuggestedId.value }, name)
  projectId.value = next.current
  lastSuggestedId.value = next.lastSuggested
}

// ---------------------------------------------------------------- 提交与轮询

async function submit() {
  if (!idOk.value) {
    ElMessage.warning('项目 ID：大写字母开头，2-16 位大写字母/数字/下划线')
    return
  }
  if (source.value === 'remote' && !cloneTarget.value) {
    ElMessage.warning('请选择克隆目标目录（仓库将 clone 到你选的本地目录）')
    return
  }
  try {
    status.value = source.value === 'local'
      ? await intakeLocal({
          projectId: projectId.value,
          localPath: selectedDir.value,
          gitInitIfMissing: gitInitIfMissing.value,
          storageMode: 'DIRECT'
        })
      : await intakeRemote({
          projectId: projectId.value,
          repoUrl: selectedRepo.value!.cloneUrl,
          storageMode: 'DIRECT',
          cloneTargetDir: cloneTarget.value
        })
    phase.value = 'RUNNING'
    timer = setInterval(poll, 2000)
    void poll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败')
  }
}

async function poll() {
  if (!status.value) return
  try {
    status.value = await intakeStatus(status.value.projectId)
    if (status.value.phase === 'REGISTERED') {
      stopPoll()
      phase.value = 'DONE'
      report.value = status.value.report as unknown as RecipeReport
    } else if (status.value.phase === 'INTAKE_FAILED') {
      stopPoll()
      phase.value = 'FAILED'
    }
  } catch { /* 下轮再试 */ }
}

function stopPoll() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function retry() {
  stopPoll()
  phase.value = 'FORM'
  status.value = null
  report.value = null
}

/** 识别结果一行摘要：stack · Java 目录 · 前端目录 · 包管理器。 */
const summaryLine = computed(() => {
  const s = report.value?.summary
  const stack = s?.stack ?? report.value?.stack ?? ''
  if (!s) return stack
  const parts = [stack]
  if (s.javaDir !== undefined && s.javaDir !== null) {
    parts.push(`Java: ${s.javaDir === '' ? '仓库根' : s.javaDir}`)
  }
  if (s.nodeDir !== undefined && s.nodeDir !== null) {
    parts.push(`前端: ${s.nodeDir === '' ? '仓库根' : s.nodeDir}`)
  }
  if (s.packageManager) parts.push(s.packageManager)
  return parts.filter(Boolean).join(' · ')
})

/** 预演：跳到流水线页，选「只检查构建（verify）」跑一次，不部署。 */
function goPreview() {
  if (!status.value) return
  appStore.projectId = status.value.projectId
  void router.push('/pipeline')
}

onMounted(loadBinding)
onUnmounted(stopPoll)
</script>

<template>
  <div class="intake-page">
    <div class="intake-column">
      <!-- Gitee 授权已迁移到「设置 → 代码仓库」；这里只保留状态提示与跳转 -->
      <section v-if="!binding?.bound" class="account-card muted">
        <div class="acct-left">
          <el-icon class="acct-icon"><UserFilled /></el-icon>
          <div class="acct-info">
            <span class="acct-name">未绑定 Gitee 账号</span>
            <span class="acct-sub">需要浏览/克隆 Gitee 仓库时，请先到「设置 → 代码仓库」授权</span>
          </div>
        </div>
        <div class="acct-actions">
          <button type="button" class="mini-btn" @click="router.push('/settings')">去设置授权</button>
        </div>
      </section>

      <section v-else class="account-card">
        <div class="acct-left">
          <el-icon class="acct-icon"><UserFilled /></el-icon>
          <div class="acct-info">
            <span class="acct-name">Gitee：{{ binding.giteeLogin }}</span>
            <span class="mono acct-token">{{ binding.tokenMasked }}</span>
          </div>
        </div>
        <div class="acct-actions">
          <button type="button" class="mini-btn" @click="openGitee()">浏览仓库</button>
          <button type="button" class="mini-btn" @click="router.push('/settings')">管理授权</button>
        </div>
      </section>

      <!-- Step 1: 表单 -->
      <template v-if="phase === 'FORM'">
        <h1 class="page-title">接入项目</h1>
        <p class="page-sub">本地目录就地收编（平台只读你的目录），或从 Gitee 选择仓库 clone。</p>

        <div class="tabs">
          <button type="button" class="tab" :class="{ active: source === 'local' }" @click="source = 'local'; suggestId()">
            <el-icon><Folder /></el-icon> 本地目录
          </button>
          <button type="button" class="tab" :class="{ active: source === 'remote' }" @click="source = 'remote'; suggestId()">
            <el-icon><Connection /></el-icon> Gitee 仓库
          </button>
        </div>

        <div class="form-card">
          <template v-if="source === 'local'">
            <div class="field">
              <span class="field-label">选择项目目录</span>
              <div class="dir-pick" @click="chooseDir('project')">
                <el-icon><Folder /></el-icon>
                <span class="mono" :class="{ placeholder: !selectedDir }">
                  {{ selectedDir || '点击浏览文件夹…' }}
                </span>
                <span v-if="selectedDir" class="git-tag" :class="selectedIsGit ? 'ok' : 'warn'">
                  {{ selectedIsGit ? 'git 仓库' : '非 git（将自动 init）' }}
                </span>
              </div>
              <span class="field-hint">
                点击会弹出系统文件夹选择框；平台只读你的目录并同步提交，<b>不修改任何文件</b>。
              </span>
            </div>
            <label v-if="selectedDir && !selectedIsGit" class="check-field">
              <el-checkbox v-model="gitInitIfMissing">
                允许平台自动 <code class="mono">git init</code>（只加 .git，不改任何文件）
              </el-checkbox>
            </label>
          </template>

          <template v-else>
            <div class="field">
              <span class="field-label">选择 Gitee 仓库</span>
              <div class="dir-pick" @click="openGitee">
                <el-icon><Connection /></el-icon>
                <span class="mono" :class="{ placeholder: !selectedRepo }">
                  {{ selectedRepo ? selectedRepo.fullName : '从你的 Gitee 仓库列表选择…' }}
                </span>
                <span v-if="selectedRepo?.privateRepo" class="git-tag warn">私有</span>
              </div>
              <span v-if="!binding?.bound" class="field-hint warn-text">
                尚未绑定 Gitee 账号——请先在上方绑定（或配置平台兜底 token）。
              </span>
            </div>
            <div class="field">
              <span class="field-label">克隆到哪里</span>
              <div class="dir-pick" @click="chooseDir('clone')">
                <el-icon><Folder /></el-icon>
                <span class="mono" :class="{ placeholder: !cloneTarget }">
                  {{ cloneTarget || '点击浏览文件夹（仓库将 clone 到这里）…' }}
                </span>
              </div>
              <span class="field-hint">
                仓库 clone 到你选的本地目录，平台直接在该目录上构建（不复制到平台工作区）。
              </span>
            </div>
          </template>

          <label class="field">
            <span class="field-label">项目 ID</span>
            <el-input v-model="projectId" class="mono" placeholder="如 SHOP（大写字母开头）" />
          </label>

          <div class="form-actions">
            <button type="button" class="primary-btn" :disabled="!canSubmit" @click="submit">
              校验并接入
            </button>
          </div>
        </div>
      </template>

      <!-- Step 2 -->
      <template v-else-if="phase === 'RUNNING' || phase === 'FAILED'">
        <h1 class="page-title">
          正在接入 {{ status?.projectId }}
          <span v-if="phase === 'FAILED'" class="fail-tag">失败</span>
        </h1>
        <ol class="timeline">
          <li
            v-for="(s, i) in steps"
            :key="s"
            class="tl-step"
            :class="{ done: stepIndex > i, active: phase === 'RUNNING' && stepIndex === i }"
          >
            <span class="tl-dot">
              <el-icon v-if="phase === 'RUNNING' && stepIndex === i" class="is-loading"><Loading /></el-icon>
              <el-icon v-else-if="stepIndex > i"><Select /></el-icon>
            </span>
            <span class="tl-name">{{ s }}</span>
            <span v-if="phase === 'RUNNING' && stepIndex === i" class="tl-msg">{{ status?.message }}</span>
          </li>
        </ol>
        <div v-if="phase === 'FAILED'" class="error-box">
          <div class="err-head"><el-icon><WarningFilled /></el-icon> 接入失败</div>
          <pre class="mono err-body">{{ status?.message }}</pre>
          <div class="err-actions">
            <button type="button" class="ghost-btn" @click="retry">重试</button>
          </div>
        </div>
        <p v-else class="hint">大仓库可能需要 1-3 分钟，可安全离开此页。</p>
      </template>

      <!-- Step 3 -->
      <template v-else>
        <h1 class="page-title">接入完成</h1>
        <div class="report-card" v-if="report">
          <div class="report-line">
            <span>自检</span>
            <span
              class="badge"
              :class="report.ready ? 'ok' : (report.blockers?.length ? 'bad' : 'warn')"
            >
              {{ report.ready ? '就绪' : (report.blockers?.length
                ? `${report.blockers.length} 个阻塞项` : '有建议') }}
            </span>
          </div>
          <div class="report-line">
            <span>流水线</span>
            <span class="mono">
              {{ report.pipeline ?? (report.scaffoldWritten?.length ? '已自动生成（平台侧）' : '缺失（可一键生成）') }}
            </span>
          </div>

          <template v-if="report.summary">
            <div class="report-line">
              <span>识别</span>
              <span class="mono">{{ summaryLine }}</span>
            </div>
            <div class="report-line">
              <span>部署目标</span>
              <span>
                <span class="mono">{{ report.summary.deployTarget }}</span>
                <span v-if="report.summary.targetReason" class="src-note">· {{ report.summary.targetReason }}</span>
              </span>
            </div>
            <div class="report-line">
              <span>端口</span>
              <span>
                <span class="mono">{{ report.summary.port }}</span>
                <span class="src-note">· {{ report.summary.portSource }}</span>
              </span>
            </div>
            <div class="report-line">
              <span>健康路径</span>
              <span>
                <span class="mono">{{ report.summary.healthPath }}</span>
                <span class="src-note">· {{ report.summary.healthSource }}</span>
              </span>
            </div>
          </template>

          <div class="report-line">
            <span>Dockerfile</span>
            <span>{{ report.dockerfile ? '✓ 已声明' : '✗ 缺失' }}</span>
          </div>

          <details v-if="report.blockers?.length" class="warn-detail" open>
            <summary class="grp-blocker">阻塞项（{{ report.blockers.length }}）</summary>
            <div v-for="d in report.blockers" :key="d.code" class="diag-row blocker">
              <div>{{ d.message }}</div>
              <div v-if="d.fix" class="diag-fix">建议：{{ d.fix }}</div>
            </div>
          </details>

          <details v-if="report.suggestions?.length" class="warn-detail" :open="!report.blockers?.length">
            <summary class="grp-suggestion">建议（{{ report.suggestions.length }}）</summary>
            <div v-for="d in report.suggestions" :key="d.code" class="diag-row suggestion">
              <div>{{ d.message }}</div>
              <div v-if="d.fix" class="diag-fix">建议：{{ d.fix }}</div>
            </div>
          </details>

          <details v-if="report.info?.length" class="warn-detail">
            <summary>说明（{{ report.info.length }}）</summary>
            <div v-for="d in report.info" :key="d.code" class="diag-row info">{{ d.message }}</div>
          </details>

          <details class="actions-detail">
            <summary>动作配方（{{ Object.keys(report.actions).length }}）</summary>
            <div v-for="(src, name) in report.actions" :key="name" class="action-row">
              <span class="mono">{{ name }}</span>
              <span :class="src ? 'src-ok' : 'src-miss'">{{ src ?? '无配方（stage 会失败）' }}</span>
            </div>
          </details>

          <details v-if="report.scaffoldFiles?.length" class="tpl-detail" open>
            <summary>
              已生成的 ci/ 脚本
              <span v-if="report.scaffoldWritten?.length" class="gen-ok">
                · 已写入 {{ report.scaffoldWritten.join('、') }}
              </span>
            </summary>
            <div v-for="f in report.scaffoldFiles" :key="f.path" class="gen-file">
              <div class="gen-file-head mono">{{ f.path }}</div>
              <pre class="mono tpl-body">{{ f.content }}</pre>
            </div>
          </details>
        </div>
        <div class="form-actions">
          <button type="button" class="primary-btn" @click="goPreview">
            预演一次（只构建，不部署）
          </button>
          <button type="button" class="ghost-btn" @click="retry">再接一个</button>
        </div>
      </template>
    </div>

    <!-- 目录选择器（现代：快捷位置 + 面包屑 + 搜索 + 键盘） -->
    <DirPickerDialog
      v-model="dirBrowser"
      :initial-path="pickerInitial"
      :title="dirPickTarget === 'clone' ? '选择克隆目标目录' : '选择项目目录'"
      @pick="onDirPicked"
    />

    <!-- Gitee 仓库选择（卡片式列表 + 键盘导航） -->
    <GiteeRepoPicker
      v-model="giteeDialog"
      :account="binding?.giteeLogin ?? ''"
      @pick="pickRepo"
    />
  </div>
</template>

<style scoped src="./intake-shared.css"></style>
