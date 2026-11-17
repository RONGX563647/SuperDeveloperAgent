/** 能力列表排序：已启用优先，其次保持后端顺序；按名称时走中文排序。 */
export interface CapabilityOrderItem {
  key: string
  label: string
}

export function orderCapabilities<T extends CapabilityOrderItem>(
  list: T[],
  isOn: (key: string) => boolean,
  sortBy: 'default' | 'name',
): T[] {
  if (sortBy === 'name') {
    return [...list].sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
  }
  // Array.prototype.sort 是稳定排序：同组内保持入参顺序
  return [...list].sort((a, b) => Number(!isOn(a.key)) - Number(!isOn(b.key)))
}
