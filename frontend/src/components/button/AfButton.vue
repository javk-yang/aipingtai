<script setup lang="ts">
/**
 * AfButton —— 全站唯一按钮
 *
 * variant 语义（P0.5 设计基线）：
 * - primary：近黑/近白实底按钮（--color-primary），页面主操作
 * - secondary：白底 + hairline 边框，次级操作
 * - ghost：无边框，弱操作/工具栏
 * - danger：危险操作（删除/吊销）
 *
 * 单色设计下 hover 统一用 opacity 表达"可点感"，不引入彩色变化。
 */
import { computed } from 'vue'
import AfIcon from '../icon/AfIcon.vue'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'sm' | 'md' | 'lg'

const props = withDefaults(
  defineProps<{
    variant?: Variant
    size?: Size
    loading?: boolean
    disabled?: boolean
    block?: boolean
    type?: 'button' | 'submit'
    /** 左侧图标（AfIcon name） */
    icon?: string
  }>(),
  { variant: 'primary', size: 'md', loading: false, disabled: false, block: false, type: 'button' },
)

const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()

const isDisabled = computed(() => props.disabled || props.loading)

function onClick(ev: MouseEvent) {
  if (isDisabled.value) return
  emit('click', ev)
}

const cls = computed(() => [
  'af-btn',
  `af-btn--${props.variant}`,
  `af-btn--${props.size}`,
  { 'af-btn--block': props.block, 'is-loading': props.loading },
])
</script>

<template>
  <button :type="type" :class="cls" :disabled="isDisabled" @click="onClick">
    <span v-if="loading" class="af-btn__spinner" aria-hidden="true" />
    <AfIcon v-else-if="icon" :name="icon as any" :size="size === 'sm' ? 14 : 16" />
    <span class="af-btn__label"><slot /></span>
  </button>
</template>

<style scoped>
.af-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-weight: var(--weight-medium);
  letter-spacing: var(--tracking-tight);
  white-space: nowrap;
  transition: opacity var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast), transform var(--transition-fast);
  user-select: none;
}
.af-btn:not(:disabled):active { transform: scale(0.98); }
.af-btn:disabled { opacity: 0.45; cursor: not-allowed; }

.af-btn--sm { height: 28px; padding: 0 9px; font-size: var(--text-xs); }
.af-btn--md { height: 34px; padding: 0 13px; font-size: var(--text-base); }
.af-btn--lg { height: var(--control-height-lg); padding: 0 20px; font-size: var(--text-md); }
.af-btn--block { width: 100%; }

.af-btn--primary { background-color: var(--color-primary); color: var(--color-on-primary); box-shadow: 0 5px 14px color-mix(in srgb, var(--color-primary) 14%, transparent); }
.af-btn--primary:not(:disabled):hover { opacity: 0.88; transform: translateY(-1px); }
.af-btn--secondary { background-color: var(--color-surface-raised); color: var(--color-text); border-color: var(--color-border); }
.af-btn--secondary:not(:disabled):hover { background-color: var(--color-surface-2); border-color: var(--color-border-strong); }
.af-btn--ghost { background: transparent; color: var(--color-text-secondary); }
.af-btn--ghost:not(:disabled):hover { color: var(--color-text); background-color: var(--color-surface-2); }
.af-btn--danger { background-color: var(--color-danger); color: var(--color-on-danger); }
.af-btn--danger:not(:disabled):hover { opacity: 0.88; transform: translateY(-1px); }

.af-btn__spinner { width: 14px; height: 14px; border: 1.5px solid currentColor; border-top-color: transparent; border-radius: 50%; animation: af-spin 0.7s linear infinite; }
@keyframes af-spin { to { transform: rotate(360deg); } }
</style>
