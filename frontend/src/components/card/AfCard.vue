<script setup lang="ts">
/**
 * AfCard —— 内容容器
 *
 * 设计（P0.5 原则 2）：surface 底 + hairline 边框 + 留白驱动层级，
 * 不靠色块/阴影区分层级。padding 由 --space 令牌控制。
 */
withDefaults(
  defineProps<{
    /** 内边距密度 */
    padding?: 'sm' | 'md' | 'lg' | 'none'
    /** 是否可悬停（列表项等） */
    hoverable?: boolean
  }>(),
  { padding: 'md', hoverable: false },
)
</script>

<template>
  <div class="af-card" :class="[`af-card--${padding}`, { 'af-card--hover': hoverable }]">
    <slot />
  </div>
</template>

<style scoped>
.af-card {
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
}
.af-card--sm { padding: var(--space-3); }
.af-card--md { padding: var(--space-4); }
.af-card--lg { padding: var(--space-6); }
.af-card--none { padding: 0; }
.af-card--hover:hover {
  border-color: var(--color-border-strong);
}
</style>
