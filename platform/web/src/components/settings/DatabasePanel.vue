<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Connection, Refresh, Right, Upload } from '@element-plus/icons-vue'
import {
  getDatabaseStatus, migrateSqliteToPostgres, resetDatabaseBackend,
  savePostgresBackend, testPostgres,
  type DatabaseStatus, type MigrationResult, type PostgresInput,
  type PostgresTestResult
} from '../../api/settings'

/**
 * 数据库后端面板：当前后端 → PostgreSQL 连接表单 → 连通性测试 → 一键迁移 → 保存（重启生效）。
 *
 * 交互上的两个坑，UI 必须直说，否则用户会误判：
 * 1. **保存不等于生效**：连接池在启动时装配，保存只落 `db-backend.yml`，必须重启；
 * 2. **迁移与切换是两件事**：迁移只搬数据，先迁移再保存再重启，顺序写在步骤条里。
 */
const status = ref<DatabaseStatus | null>(null)
const loading = ref(false)
const testing = ref(false)
const migrating = ref(false)
const saving = ref(false)

const testResult = ref<PostgresTestResult | null>(null)
const migration = ref<MigrationResult | null>(null)

const form = ref<PostgresInput>({
  url: 'jdbc:postgresql://127.0.0.1:5432/agent',
  username: 'agent',
  password: ''
})

const isPostgres = computed(() => status.value?.backend === 'POSTGRES')
const migratable = computed(() => status.value?.migratable === true)

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
    '把当前 SQLite 的数据迁移到目标 PostgreSQL？会在目标库建表并逐表复制，已有行不会被覆盖。'
      + '迁移不停机，完成后仍需保存配置并重启才会切换到 PostgreSQL。',
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

async function reset(): Promise<void> {
  const ok = await ElMessageBox.confirm(
    '清除 PostgreSQL 配置并回到 SQLite？重启后生效，SQLite 文件里的数据不受影响。',
    '回到 SQLite',
    { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    const result = await resetDatabaseBackend()
    ElMessage.success(result.message)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '清除失败')
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
          <span class="db-url">{{ status?.jdbcUrl }}</span>
        </p>
      </div>
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <el-alert
      v-if="isPostgres"
      type="success"
      :closable="false"
      show-icon
      title="当前已在用 PostgreSQL"
      description="迁移入口仅在 SQLite 后端可用；如需换库，先清除配置并重启回到 SQLite，或直接改目标地址后保存。"
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
      </el-form>

      <div class="db-actions">
        <el-button :icon="Connection" :loading="testing" @click="testConnection">测试连接</el-button>
        <el-button
          type="primary" :icon="Upload" :loading="migrating"
          :disabled="!migratable" @click="migrate"
        >一键迁移 SQLite 数据</el-button>
        <el-button :loading="saving" @click="save">保存（重启生效）</el-button>
        <el-button v-if="status?.savedTarget" text type="danger" @click="reset">回到 SQLite</el-button>
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
      <h3 class="card-title">切换顺序</h3>
      <ol class="db-steps">
        <li>填目标 PostgreSQL 地址 → <strong>测试连接</strong>通过；</li>
        <li><strong>一键迁移</strong>把 SQLite 数据搬过去（可重复执行，不会覆盖 PG 已有行）；</li>
        <li><strong>保存</strong>写入 <code>db-backend.yml</code>（密码加密落盘，明文不回流前端）；</li>
        <li><strong>重启平台</strong>，启动时按新配置装配连接池与方言；</li>
        <li>重启后本页后端会显示 <code>POSTGRES</code>；SQLite 文件保留作为回退。</li>
      </ol>
      <p class="db-file">配置文件：<code>{{ status?.configFile }}</code></p>
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
