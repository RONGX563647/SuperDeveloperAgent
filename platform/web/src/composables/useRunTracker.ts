import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import {
  cancelRun, getRun, isTerminalStatus,
  type RunView
} from '../api/pipeline'

/**
 * 运行跟踪 composable：
 *
 * - 运行详情独立于历史列表，切换查看不丢当前状态（原实现单一 run 槽位会互相覆盖）；
 * - 轮询带指数退避（网络抖动不再一次失败就永久停摆），失败原因可见；
 * - 只在运行未到终态时轮询；日志由 RunLog 走增量接口，不在此重复拉取；
 * - 终态自动回调（刷新历史）。
 */
export function useRunTracker(options: { onFinished?: (run: RunView) => void } = {}) {
  const run = shallowRef<RunView | null>(null)
  const runId = ref('')
  const loading = shallowRef(false)
  const errorText = shallowRef('')
  const startedAt = shallowRef(0)

  let timer: ReturnType<typeof setTimeout> | null = null
  let backoff = 1000
  let generation = 0

  const running = computed(() => !!run.value && !isTerminalStatus(run.value.status))

  function stop(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule(id: string, delay: number): void {
    stop()
    timer = setTimeout(() => void poll(id), delay)
  }

  async function poll(id: string): Promise<void> {
    const current = generation
    try {
      const data = await getRun(id, false)
      if (current !== generation) return // 用户已切换运行，丢弃迟到响应
      run.value = data
      errorText.value = ''
      backoff = 1000
      if (isTerminalStatus(data.status)) {
        stop()
        options.onFinished?.(data)
      } else {
        schedule(id, 1000)
      }
    } catch (e) {
      if (current !== generation) return
      errorText.value = `状态刷新失败：${e instanceof Error ? e.message : String(e)}`
      // 指数退避，最长 8s；不终止轮询
      backoff = Math.min(backoff * 2, 8000)
      schedule(id, backoff)
    }
  }

  /** 打开一次运行（历史点击 / 触发后）。 */
  async function open(id: string, known?: { startedAt?: number }): Promise<void> {
    generation++
    stop()
    runId.value = id
    run.value = null
    errorText.value = ''
    loading.value = true
    startedAt.value = known?.startedAt ?? Date.now()
    try {
      const data = await getRun(id, false)
      run.value = data
      loading.value = false
      if (!isTerminalStatus(data.status)) schedule(id, 1000)
      else options.onFinished?.(data)
    } catch (e) {
      loading.value = false
      errorText.value = `加载运行失败：${e instanceof Error ? e.message : String(e)}`
    }
  }

  /** 触发后直接用后端返回的状态渲染（少一次往返）。 */
  function attach(id: string, initial: RunView): void {
    generation++
    stop()
    runId.value = id
    run.value = initial
    startedAt.value = Date.now()
    loading.value = false
    errorText.value = ''
    if (!isTerminalStatus(initial.status)) schedule(id, 1000)
  }

  async function cancel(): Promise<boolean> {
    if (!run.value || !running.value) return false
    try {
      run.value = await cancelRun(run.value.runId)
      if (isTerminalStatus(run.value.status)) {
        stop()
        options.onFinished?.(run.value)
      }
      return true
    } catch (e) {
      errorText.value = `取消失败：${e instanceof Error ? e.message : String(e)}`
      return false
    }
  }

  function clear(): void {
    generation++
    stop()
    run.value = null
    runId.value = ''
    errorText.value = ''
  }

  onScopeDispose(stop)

  return {
    run, runId, loading, errorText, startedAt, running,
    open, attach, cancel, clear, stop,
  }
}
