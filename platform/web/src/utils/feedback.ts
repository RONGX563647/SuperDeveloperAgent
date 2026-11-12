import { ElMessage, ElMessageBox } from 'element-plus'

/** 统一取错误文案：Error 取 message，其它一律回退。 */
export function errorMessage(e: unknown, fallback = '操作失败'): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** 统一错误提示（等价 ElMessage.error(errorMessage(e, fallback))）。 */
export function notifyError(e: unknown, fallback?: string): void {
  ElMessage.error(errorMessage(e, fallback))
}

/** 统一确认框：确定返回 true，取消/关闭返回 false。 */
export async function confirmAction(message: string, title = '确认操作'): Promise<boolean> {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => true)
    .catch(() => false)
}
