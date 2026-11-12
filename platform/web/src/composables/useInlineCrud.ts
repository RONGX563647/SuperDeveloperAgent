import { nextTick, ref, useTemplateRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { TestResult } from '../api/settings'
import { errorMessage, confirmAction } from '../utils/feedback'

export interface InlineCrudOptions<T, I> {
  list: () => Promise<T[]>
  create: (input: I) => Promise<unknown>
  update: (id: string, input: I) => Promise<unknown>
  remove: (id: string) => Promise<unknown>
  emptyForm: () => I
  toForm: (item: T) => I
  removeTip: (item: T) => string
  /** 文案：加载/保存/删除失败提示、保存成功提示 */
  messages: { load: string; save: string; remove: string; saved?: string }
}

/**
 * 「列表 + 内联表单 + 测试连接 + 设默认」的通用骨架。
 *
 * <p>服务器面板与模型面板此前是两份逐行对应的 CRUD 复制（连 nextTick +
 * scrollIntoView 都一样），差异只有 API 函数与字段名，故抽取为组合式函数。
 */
export function useInlineCrud<T extends { id: string }, I>(options: InlineCrudOptions<T, I>) {
  const items = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const testResult = ref<TestResult | null>(null)
  const formOpen = ref(false)
  const editing = ref<T | null>(null) as Ref<T | null>
  const form = ref(options.emptyForm()) as Ref<I>
  const formRef = useTemplateRef<HTMLElement>('formRef')

  async function load(): Promise<void> {
    loading.value = true
    try {
      items.value = await options.list()
    } catch (e) {
      ElMessage.error(errorMessage(e, options.messages.load))
    } finally {
      loading.value = false
    }
  }

  async function scrollToForm(): Promise<void> {
    await nextTick()
    formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }

  async function openCreate(): Promise<void> {
    editing.value = null
    form.value = options.emptyForm()
    testResult.value = null
    formOpen.value = true
    await scrollToForm()
  }

  async function openEdit(item: T): Promise<void> {
    editing.value = item
    form.value = options.toForm(item)
    testResult.value = null
    formOpen.value = true
    await scrollToForm()
  }

  function closeForm(): void {
    formOpen.value = false
    testResult.value = null
    editing.value = null
  }

  async function save(): Promise<void> {
    saving.value = true
    try {
      if (editing.value) {
        await options.update(editing.value.id, form.value)
      } else {
        await options.create(form.value)
      }
      ElMessage.success(options.messages.saved ?? '已保存')
      closeForm()
      await load()
    } catch (e) {
      ElMessage.error(errorMessage(e, options.messages.save))
    } finally {
      saving.value = false
    }
  }

  async function remove(item: T): Promise<void> {
    if (!(await confirmAction(options.removeTip(item), '删除确认'))) return
    try {
      await options.remove(item.id)
      ElMessage.success('已删除')
      await load()
    } catch (e) {
      ElMessage.error(errorMessage(e, options.messages.remove))
    }
  }

  /**
   * 测试连接一律用「当前表单草稿」：编辑态若走已保存配置，
   * 改了地址/密钥再测，结果仍是第一次的，看起来像结果被缓存。
   */
  async function runTest(probe: (form: I, id?: string) => Promise<TestResult>): Promise<void> {
    testing.value = true
    testResult.value = null
    try {
      testResult.value = await probe(form.value, editing.value?.id)
      await load()
    } catch (e) {
      testResult.value = { ok: false, latencyMs: 0, message: errorMessage(e, '测试失败') }
    } finally {
      testing.value = false
    }
  }

  return {
    items, loading, saving, testing, testResult, formOpen, editing, form, formRef,
    load, openCreate, openEdit, closeForm, save, remove, runTest
  }
}
