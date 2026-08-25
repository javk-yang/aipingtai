<script setup lang="ts">
/**
 * AfInput —— 表单输入框
 *
 * 设计（P0.5）：
 * - label 用 11px 大写小标签 + 字距拉开（label-group 风格）
 * - 输入框 36px 高，surface 底 + hairline 边框；focus 时边框加深不加彩色
 * - error 时边框和提示转 danger 色（唯一允许的彩色信号，表"出错"语义）
 */
import { computed } from 'vue'
import AfIcon from '../icon/AfIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    label?: string
    placeholder?: string
    type?: 'text' | 'password' | 'email' | 'number' | 'tel'
    error?: string
    disabled?: boolean
    autocomplete?: string
    maxlength?: number
    /** 右侧图标（AfIcon name），如密码可见性切换 */
    icon?: string
  }>(),
  { type: 'text', placeholder: '', disabled: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'focus', ev: FocusEvent): void
  (e: 'blur', ev: FocusEvent): void
  (e: 'enter'): void
  (e: 'iconClick'): void
}>()

const cls = computed(() => [
  'af-input__control',
  { 'is-error': !!props.error },
])

function onInput(ev: Event) {
  emit('update:modelValue', (ev.target as HTMLInputElement).value)
}

function onKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Enter') emit('enter')
}
</script>

<template>
  <div class="af-input">
    <label v-if="label" class="af-input__label label-group">{{ label }}</label>
    <div class="af-input__wrap">
      <input
        :class="cls"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :autocomplete="autocomplete"
        :maxlength="maxlength"
        @input="onInput"
        @focus="(e) => emit('focus', e)"
        @blur="(e) => emit('blur', e)"
        @keydown="onKeydown"
      />
      <button v-if="icon" type="button" class="af-input__icon" @click="emit('iconClick')">
        <AfIcon :name="icon as any" :size="16" />
      </button>
    </div>
    <p v-if="error" class="af-input__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.af-input {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.af-input__label {
  color: var(--color-text-secondary);
}
.af-input__wrap {
  position: relative;
}
.af-input__control {
  width: 100%;
  height: var(--control-height);
  padding: 0 12px;
  background-color: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  transition: border-color var(--transition-fast);
}
.af-input__control::placeholder {
  color: var(--color-text-tertiary);
}
.af-input__control:focus {
  outline: none;
  border-color: var(--color-border-strong);
}
.af-input__control.is-error {
  border-color: var(--color-danger);
}
.af-input__control:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.af-input__icon {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
  transition: color var(--transition-fast);
}
.af-input__icon:hover {
  color: var(--color-text);
  background-color: var(--color-surface-2);
}
.af-input__error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
</style>
