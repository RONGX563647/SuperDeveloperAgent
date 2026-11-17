<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AskPayload } from '../../types'

/**
 * 选项卡片（ask_user 工具下发）。
 *
 * <p>交互约定：用户点选后把选项文案作为下一条用户消息发回给 Agent（不新增协议），
 * 因此只需要 emit('select', 文本)。多选时等「确定」，单选点击即发送。
 * 卡片只有在「是最后一条消息且不在流式中」时可交互，避免误点历史卡片。
 */
interface Props {
  ask: AskPayload
  /** 是否可交互（最后一条消息、且当前没有在跑）。 */
  active?: boolean
}

const props = withDefaults(defineProps<Props>(), { active: false })

const emit = defineEmits<{
  (e: 'select', answer: string): void
}>()

const picked = ref<string[]>([])
const freeText = ref('')
const submitted = ref(false)

const multiple = computed(() => props.ask.multiple === true)
const options = computed(() => props.ask.options ?? [])
const disabled = computed(() => !props.active || submitted.value)

function toggle(label: string): void {
  if (disabled.value) return
  if (!multiple.value) {
    submitted.value = true
    emit('select', label)
    return
  }
  const index = picked.value.indexOf(label)
  if (index >= 0) picked.value.splice(index, 1)
  else picked.value.push(label)
}

function submit(): void {
  if (disabled.value) return
  const answer = multiple.value && picked.value.length
    ? picked.value.join('、')
    : freeText.value.trim()
  if (!answer) return
  submitted.value = true
  emit('select', answer)
}
</script>

<template>
  <div class="ask-card" :class="{ done: !props.active || submitted }">
    <div class="ask-head">
      <span class="ask-title">需要你确认</span>
      <span v-if="multiple" class="ask-hint">可多选</span>
      <span v-else-if="submitted || !props.active" class="ask-hint">已回答</span>
    </div>
    <p class="ask-question">{{ props.ask.question }}</p>

    <div class="ask-options">
      <button
        v-for="option in options"
        :key="option.label"
        type="button"
        class="ask-option"
        :class="{ picked: picked.includes(option.label) }"
        :disabled="disabled"
        @click="toggle(option.label)"
      >
        <span class="option-label">{{ option.label }}</span>
        <span v-if="option.detail" class="option-detail">{{ option.detail }}</span>
      </button>
    </div>

    <div v-if="props.ask.allowText !== false" class="ask-free">
      <input
        v-model="freeText"
        class="free-input"
        type="text"
        placeholder="也可以直接输入你的答案"
        :disabled="disabled"
        @keydown.enter.prevent="submit"
      >
      <button
        type="button" class="free-send"
        :disabled="disabled || !freeText.trim()"
        @click="submit"
      >
        发送
      </button>
    </div>
    <button
      v-if="multiple"
      type="button" class="multi-confirm"
      :disabled="disabled || !picked.length"
      @click="submit"
    >
      确定（已选 {{ picked.length }}）
    </button>
  </div>
</template>

<style scoped>
.ask-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 13px 15px;
  margin: 6px 0;
  background: var(--panel);
  border: 1px solid var(--accent-border, var(--line));
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-2);
}
.ask-card.done {
  opacity: 0.72;
}
.ask-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ask-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--accent-ink, var(--ink));
  letter-spacing: 0.02em;
}
.ask-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ask-question {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ink);
  word-break: break-word;
}
.ask-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ask-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 120px;
  max-width: 100%;
  padding: 7px 12px;
  font-family: inherit;
  text-align: left;
  color: var(--ink);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: border-color var(--dur-fast) ease, background var(--dur-fast) ease;
}
.ask-option:hover:not(:disabled) {
  background: var(--surface-hover);
  border-color: var(--accent);
}
.ask-option.picked {
  border-color: var(--accent);
  background: var(--accent-soft, var(--surface-hover));
}
.ask-option:disabled {
  cursor: default;
}
.option-label {
  font-size: 12.5px;
}
.option-detail {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ask-free {
  display: flex;
  gap: 8px;
}
.free-input {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.free-input:focus {
  border-color: var(--accent);
}
.free-send,
.multi-confirm {
  flex: none;
  padding: 6px 14px;
  font-family: inherit;
  font-size: 12px;
  color: var(--accent-ink, #fff);
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-sm);
}
.free-send:disabled,
.multi-confirm:disabled {
  cursor: default;
  opacity: 0.5;
}
.multi-confirm {
  align-self: flex-start;
}
</style>
