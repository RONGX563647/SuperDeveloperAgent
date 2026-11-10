/**
 * 流水线/环境/阶段的人类可读名称（UI 唯一出处）。
 *
 * 后端存的是 id（release / verify / image_package），页面不应直接把 id 丢给用户。
 */
import type { PreviewStage } from '../api/pipeline'

export const ENV_LABELS: Record<string, string> = {
  dev: '开发环境',
  test: '测试环境',
  prod: '生产环境',
}

export const ENV_SHORT: Record<string, string> = {
  dev: '开发',
  test: '测试',
  prod: '生产',
}

export function envLabel(env: string): string {
  return ENV_LABELS[env] ?? env
}

export function envShort(env: string): string {
  return ENV_SHORT[env] ?? env
}

export function pipelineLabel(id: string): string {
  if (id === 'release') return '发布'
  if (id === 'verify') return '检查构建'
  return id || '—'
}

export function pipelineDesc(id: string): string {
  if (id === 'release') return '构建 → 部署 → 健康检查'
  if (id === 'verify') return '只构建，不部署'
  return '仓库自定义'
}

const STAGE_LABELS: Record<string, string> = {
  checkout: '检出',
  build: '构建',
  backend: '后端构建',
  test: '测试',
  frontend: '前端构建',
  image: '镜像打包',
  deploy: '部署',
  health: '健康检查',
  rollback: '回滚',
  'pipeline-def': '解析定义',
  'deploy-guard': '配置校验',
  engine: '引擎',
}

/** stage 中文名：优先按 id，再按 action/type（仓库自定义 id 也能看懂动作）。 */
export function stageLabel(stage: { id: string, type?: string, action?: string }): string {
  const byId = STAGE_LABELS[stage.id]
  if (byId) return byId
  const action = stage.action ?? ''
  const byAction: Record<string, string> = {
    backend_build: '后端构建',
    backend_test: '后端测试',
    frontend_build: '前端构建',
    image_package: '镜像打包',
    stack_deploy: '部署',
    stack_rollback: '回滚',
  }
  if (byAction[action]) return byAction[action]
  if (stage.type === 'deploy') return '部署'
  if (stage.type === 'health') return '健康检查'
  if (stage.type === 'rollback') return '回滚'
  return stage.id
}

/** 预览 stage 的展示名。 */
export function previewLabel(stage: PreviewStage): string {
  return stageLabel({ id: stage.id, type: stage.type, action: stage.action })
}

/** 文件大小格式化（产物栏用）。 */
export function formatBytes(bytes: number): string {
  if (!bytes || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
