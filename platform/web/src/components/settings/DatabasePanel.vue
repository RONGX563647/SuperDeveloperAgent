<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Connection, Refresh, Right, Switch, Upload } from '@element-plus/icons-vue'
import {
  getDatabaseStatus, migrateSqliteToPostgres, savePostgresBackend, switchToPostgres,
  switchToSqlite, testPostgres,
  type DatabaseStatus, type DatabaseSwitchResult, type MigrationResult,
  type PostgresInput, type PostgresTestResult
} from '../../api/settings'

/**
 * 数据库后端面板：当前后端 → PostgreSQL 连接表单 → 连通性测试 → 热切换。
 *
 * 交互上必须说清的两件事：
 * 1. **热切换不是重启**：点完立刻生效，连接池已换成新库，本页 backend 会立即变化；
 * 2. **失败不动现状**：探活/建表/搬数据任何一步失败，运行中的后端都不会被切走，
 *    配置也不会被改写——这类"宁可切不成，也不能让平台失去数据库"的保证要写在界面上。
 */
const status = ref<DatabaseStatus | null>(null)
const loading = ref(false)
const testing = ref(false)
const migrating = ref(false)
const saving = ref(false)
const switching = ref(false)

const testResult = ref<PostgresTestResult | null>(null)
const migration = ref<MigrationResult | null>(null)
const switchResult = ref<DatabaseSwitchResult | null>(null)

const form = ref<PostgresInput>({
  url: 'jdbc:postgresql://127.0.0.1:5432/agent',
  username: 'agent',
  password: '',
  migrate: true
})

const isPostgres = computed(() => status.value?.backend === 'POSTGRES')
const migratable = computed(() => status.value?.migratable === true)
/** 实际生效后端与启动配置不一致 → 说明是热切换过来的，重启前要提醒 */
const driftedFromConfig = computed(() =>
  status.value != null && status.value.backend !== status.value.configuredBackend)

async function load(): Promise<void> {
  loading.value = true
  try {
    status.value = await getDatabaseStatus()
    if (status.value.savedUrl) {
      form.value.url = status.value.savedUrl
      form.value.username = status.value.savedUsername || form.value.username
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载数据库状态失败')
  } finally {
    loading.value = false
  }
}

async function testConnection(): Promise<void> {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testPostgres(form.value)
  } catch (e) {
    testResult.value = {
      ok: false, latencyMs: 0, serverVersion: null,
      message: e instanceof Error ? e.message : '测试失败'
    }
  } finally {
    testing.value = false
  }
}

async function migrate(): Promise<void> {
  const ok = await ElMessageBox.confirm(
    '把当前 SQLite 的数据搬到目标 PostgreSQL？会在目标库建表并逐表复制，已有行不会被覆盖，'
      + '但不会切换后端——之后仍需点「热切换」。',
    '迁移确认',
    { confirmButtonText: '开始迁移', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  migrating.value = true
  migration.value = null
  try {
    migration.value = await migrateSqliteToPostgres(form.value)
    if (migration.value.ok) {
      ElMessage.success(`迁移完成：${migration.value.copiedRows} 行`)
    } else {
      ElMessage.warning(migration.value.message)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '迁移失败')
  } finally {
    migrating.value = false
  }
}

async function hotSwitch(): Promise<void> {
  const dragging = form.value.migrate ? '并先把当前 SQLite 数据搬过去' : '不搬数据'
  const ok = await ElMessageBox.confirm(
    `立即切换到 PostgreSQL（${dragging}）？\n\n`
      + '探活 → 建表 → 搬数据 → 原子切换，全程不重启。\n'
      + '任何一步失败都不会切换，现有后端继续可用。',
    '热切换确认',
    { confirmButtonText: '立即切换', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  switching.value = true
  switchResult.value = null
  try {
    switchResult.value = await switchToPostgres(form.value)
    ElMessage.success(switchResult.value.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '热切换失败')
  } finally {
    switching.value = false
  }
}

async function backToSqlite(): Promise<void> {
  const ok = await ElMessageBox.confirm(
    '热切换回 SQLite？PostgreSQL 里的数据不会删除，只是不再使用它。',
    '切回 SQLite',
    { confirmButtonText: '切回', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  switching.value = true
  switchResult.value = null
  try {
    switchResult.value = await switchToSqlite()
    ElMessage.success(switchResult.value.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '切回失败')
  } finally {
    switching.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const result = await savePostgresBackend(form.value)
    ElMessage.success(result.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="db-panel">
    <header class="db-head">
      <div>
        <h2 class="db-title">数据库</h2>
        <p class="db-sub">
          当前后端：<strong>{{ status?.backend ?? '—' }}</strong>
          <el-tag v-if="status?.hotReload" size="small" type="success" effect="plain" class="db-tag">
            支持热切换
          </el-tag>
          <span class="db-url">{{ status?.jdbcUrl }}</span>
        </p>
      </div>
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="driftedFromConfig"
      type="warning"
      :closable="false"
      show-icon
      title="运行中的后端与启动配置不一致"
      :description="`当前实际在用 ${status?.backend}，而进程启动时配置的是 ${status?.configuredBackend}。`
        + `这是热切换的结果，无需处理；重启后会以配置文件为准。`"
    />

    <section class="db-card">
      <h3 class="card-title"><el-icon><Coin /></el-icon> 目标 PostgreSQL</h3>
      <el-form label-width="96px" label-position="left">
        <el-form-item label="JDBC URL">
          <el-input v-model="form.url" placeholder="jdbc:postgresql://host:5432/agent" spellcheck="false" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="agent" spellcheck="false" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password" type="password" show-password spellcheck="false"
            :placeholder="status?.hasPassword ? '留空 = 沿用已保存的密码' : '数据库密码'"
          />
        </el-form-item>
        <el-form-item label="切换时搬数据">
          <el-switch v-model="form.migrate" />
          <span class="db-hint-text">关闭则只切后端，不搬现有 SQLite 数据</span>
        </el-form-item>
      </el-form>

      <div class="db-actions">
        <el-button :icon="Connection" :loading="testing" @click="testConnection">测试连接</el-button>
        <el-button
          type="primary" :icon="Switch" :loading="switching"
          :disabled="switching" @click="hotSwitch"
        >热切换（立即生效）</el-button>
        <el-button :icon="Upload" :loading="migrating" :disabled="!migratable" @click="migrate">
          仅迁移数据
        </el-button>
        <el-button :loading="saving" @click="save">仅保存配置</el-button>
        <el-button v-if="isPostgres" text type="danger" @click="backToSqlite">切回 SQLite</el-button>
      </div>

      <el-alert
        v-if="testResult"
        :type="testResult.ok ? 'success' : 'error'"
        :closable="false" show-icon class="db-result"
        :title="testResult.ok ? '连接正常' : '连接失败'"
      >
        <template #default>
          <span>{{ testResult.message }}</span>
          <span v-if="testResult.ok" class="db-meta">
            · {{ testResult.serverVersion }} · {{ testResult.latencyMs }}ms
          </span>
        </template>
      </el-alert>

      <el-alert
        v-if="switchResult"
        type="success"
        :closable="false" show-icon class="db-result"
        :title="switchResult.message"
      >
        <template #default>
          <span>
            {{ switchResult.fromBackend }} → {{ switchResult.toBackend }}
            · 搬迁 {{ switchResult.copiedRows }} 行
            · 耗时 {{ switchResult.elapsedMs }}ms
          </span>
        </template>
      </el-alert>
    </section>

    <section v-if="migration" class="db-card">
      <h3 class="card-title">
        <el-icon><Right /></el-icon> 迁移结果
        <el-tag :type="migration.ok ? 'success' : 'warning'" size="small" effect="plain">
          {{ migration.ok ? '完成' : '部分完成' }}
        </el-tag>
      </h3>
      <p class="db-summary">
        {{ migration.message }} · 共 {{ migration.tables }} 张表 ·
        跳过 {{ migration.skippedTables }} 张 · 耗时 {{ migration.elapsedMs }}ms
      </p>
      <el-table :data="migration.details" size="small" max-height="320" class="db-table">
        <el-table-column prop="table" label="表" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sourceRows" label="源行数" width="100" align="right" />
        <el-table-column prop="copied" label="新增" width="100" align="right" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'info'" size="small" effect="plain">
              {{ row.ok ? 'OK' : '跳过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="skipped" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
    </section>

    <section class="db-card db-hint">
      <h3 class="card-title">热切换做了什么</h3>
      <ol class="db-steps">
        <li><strong>探活</strong>：目标库连不上就直接失败，现有后端一行数据都不动；</li>
        <li><strong>建表</strong>：对目标库跑同一套迁移脚本，表/索引与当前版本一致；</li>
        <li><strong>搬数据</strong>：按表结构交集逐行复制，已存在的行跳过，可重复执行；</li>
        <li><strong>落配置</strong>：写入 <code>db-backend.yml</code>，保证重启后仍指向新库；</li>
        <li><strong>原子替换</strong>：把运行中的连接池换成新库，全程不重启，失败则一切照旧。</li>
      </ol>
      <p class="db-file">
        配置文件：<code>{{ status?.configFile }}</code>
        　·　后端在运行时切换，<strong>不需要重启</strong>
      </p>
    </section>
  </div>
</template>

<style scoped>
.db-panel {
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.db-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
}
.db-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 650;
  color: var(--ink);
}
.db-sub {
  margin: 4px 0 0;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.db-tag {
  margin-left: 8px;
}
.db-url {
  margin-left: 8px;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: var(--text-2xs);
  color: var(--ink-faint);
}
.db-card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink-soft);
}
.db-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.db-result {
  margin-top: 2px;
}
.db-meta {
  color: var(--ink-faint);
}
.db-hint-text {
  margin-left: 10px;
  font-size: var(--text-2xs);
  color: var(--ink-faint);
}
.db-summary {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
.db-table {
  background: var(--panel);
  border-radius: var(--radius-sm);
}
.db-steps {
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  font-size: var(--text-xs);
  line-height: 1.7;
  color: var(--ink-muted);
}
.db-file,
.db-steps code {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: var(--text-2xs);
}
.db-file {
  margin: 0;
  color: var(--ink-faint);
}
</style>
