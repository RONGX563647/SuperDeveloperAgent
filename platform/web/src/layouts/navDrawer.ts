import { ref } from 'vue'

/**
 * 移动端导航抽屉的全局开关。
 *
 * 侧栏在 ≤900px 会变成离屏抽屉，PageHeader 里的菜单按钮负责打开它；
 * 抽屉与遮罩由 AppLayout 渲染，路由变化或点击遮罩时自动关闭。
 */
export const navDrawerOpen = ref(false)

export function openNav(): void {
  navDrawerOpen.value = true
}

export function closeNav(): void {
  navDrawerOpen.value = false
}
