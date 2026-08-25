<script setup lang="ts">
/**
 * AfModal —— 弹层对话框
 *
 * 设计：
 * - Teleport 到 body，避免父容器 overflow 裁剪
 * - 遮罩 = 半透明近黑（rgba），面板 = surface + --shadow-modal 浮层阴影
 * - Esc 关闭 + 点击遮罩关闭（可配置），支持可访问性（aria-modal）
 */
import { onBeforeUnmount, onMounted, watch } from 'vue'
import AfIcon from '../icon/AfIcon.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: number
    /** 点击遮罩是否关闭 */
    maskClosable?: boolean
  }>(),
  { title: '', width: 480, maskClosable: true },
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'close'): void
}>()

function close() {
  emit('update:open', false)
  emit('close')
}

function onKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape' && props.open) close()
}

function onMaskClick() {
  if (props.maskClosable) close()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

/* 打开时锁定 body 滚动，关闭时恢复 */
watch(
  () => props.open,
  (v) => {
    document.body.style.overflow = v ? 'hidden' : ''
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition name="af-modal">
      <div v-if="open" class="af-modal" @click.self="onMaskClick">
        <div class="af-modal__panel" :style="{ width: `${width}px` }" role="dialog" aria-modal="true">
          <header v-if="title" class="af-modal__header">
            <h3 class="af-modal__title">{{ title }}</h3>
            <button type="button" class="af-modal__close" aria-label="关闭" @click="close">
              <AfIcon name="x" :size="16" />
            </button>
          </header>
          <div class="af-modal__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="af-modal__footer">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.af-modal {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(0, 0, 0, 0.4);
  padding: var(--space-6);
}
.af-modal__panel {
  max-width: 100%;
  background-color: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-modal);
}
.af-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border);
}
.af-modal__title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
}
.af-modal__close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
  transition: color var(--transition-fast), background-color var(--transition-fast);
}
.af-modal__close:hover {
  color: var(--color-text);
  background-color: var(--color-surface-2);
}
.af-modal__body {
  padding: var(--space-6);
}
.af-modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-6);
  border-top: 1px solid var(--color-border);
}

/* 过渡动画：透明度 + 轻微上移，克制不炫技 */
.af-modal-enter-active,
.af-modal-leave-active {
  transition: opacity var(--transition-base);
}
.af-modal-enter-active .af-modal__panel,
.af-modal-leave-active .af-modal__panel {
  transition: transform var(--transition-base), opacity var(--transition-base);
}
.af-modal-enter-from,
.af-modal-leave-to {
  opacity: 0;
}
.af-modal-enter-from .af-modal__panel,
.af-modal-leave-to .af-modal__panel {
  transform: translateY(8px);
  opacity: 0;
}
</style>
