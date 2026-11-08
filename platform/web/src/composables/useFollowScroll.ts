import { onBeforeUnmount, readonly, shallowRef } from 'vue'
import type { ShallowRef } from 'vue'

/**
 * 粘底滚动（直接参考 ruoyi-copilot 的 useFollowScroll.ts）：
 * 用户上翻查看历史时暂停自动滚底，回到底部附近恢复；RAF 合并滚动帧。
 */
interface ScrollToLatestOptions {
  behavior?: ScrollBehavior
  force?: boolean
}

interface UseFollowScrollOptions {
  bottomThreshold?: number
}

export function useFollowScroll(
  container: Readonly<ShallowRef<HTMLElement | null>>,
  options: UseFollowScrollOptions = {},
) {
  const bottomThreshold = options.bottomThreshold ?? 96
  const _isNearBottom = shallowRef(true)
  const _hasNewContent = shallowRef(false)
  let animationFrame: number | null = null

  function updateScrollState(): void {
    const element = container.value
    if (!element) return
    const distanceFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight
    _isNearBottom.value = distanceFromBottom <= bottomThreshold
    if (_isNearBottom.value) _hasNewContent.value = false
  }

  function scrollToLatest({ behavior = 'auto', force = false }: ScrollToLatestOptions = {}): void {
    const element = container.value
    if (!element) return
    if (!force && !_isNearBottom.value) {
      _hasNewContent.value = true
      return
    }

    if (animationFrame !== null) cancelAnimationFrame(animationFrame)
    animationFrame = requestAnimationFrame(() => {
      const current = container.value
      if (!current) return
      const reduceMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
      current.scrollTo({
        top: current.scrollHeight,
        behavior: reduceMotion ? 'auto' : behavior,
      })
      _isNearBottom.value = true
      _hasNewContent.value = false
      animationFrame = null
    })
  }

  function followNewContent(): void {
    scrollToLatest({ behavior: 'auto' })
  }

  onBeforeUnmount(() => {
    if (animationFrame !== null) cancelAnimationFrame(animationFrame)
  })

  return {
    isNearBottom: readonly(_isNearBottom),
    hasNewContent: readonly(_hasNewContent),
    updateScrollState,
    followNewContent,
    scrollToLatest,
  }
}
