<script setup lang="ts">
/**
 * AfIcon —— 全站唯一图标组件（P0.5 设计原则 5：1.5 描边线性 SVG，禁用 emoji）
 *
 * 用法：<AfIcon name="sun" :size="16" />
 * 颜色跟随 currentColor：外层控制 color 即可，图标自动适配主题
 */
import { computed } from 'vue'

interface IconDef {
  /** 路径 d 列表（fill=none，stroke 由 svg 统一控制） */
  p?: string[]
  /** 圆：cx, cy, r */
  c?: Array<[number, number, number]>
}

/** 图标字典：全部 24x24 线性图标，语义命名 */
const ICONS: Record<string, IconDef> = {
  sun: {
    p: ['M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z', 'M12 1v2', 'M12 21v2', 'M4.2 4.2l1.4 1.4', 'M18.4 18.4l1.4 1.4', 'M1 12h2', 'M21 12h2', 'M4.2 19.8l1.4-1.4', 'M18.4 5.6l1.4-1.4'],
  },
  moon: { p: ['M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z'] },
  plus: { p: ['M12 5v14', 'M5 12h14'] },
  send: { p: ['M22 2L11 13', 'M22 2l-7 20-4-9-9-4z'] },
  search: { p: ['M21 21l-4.35-4.35', 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z'] },
  'chevron-left': { p: ['M15 18l-6-6 6-6'] },
  'chevron-right': { p: ['M9 18l6-6-6-6'] },
  x: { p: ['M18 6L6 18', 'M6 6l12 12'] },
  check: { p: ['M20 6L9 17l-5-5'] },
  copy: { p: ['M9 9h11a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H11a2 2 0 0 1-2-2z', 'M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1'] },
  trash: { p: ['M3 6h18', 'M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2', 'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6'] },
  spark: { p: ['M12 3l1.9 5.8a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3z'] },
  robot: { p: ['M12 2v3', 'M4 8h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z'], c: [[8, 13, 1.2], [16, 13, 1.2]] },
  layout: { p: ['M3 3h18v18H3z', 'M3 9h18', 'M9 9v12'] },
  settings: { p: ['M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z'] },
  user: { p: ['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 11a4 4 0 1 0 8 0 4 4 0 1 0-8 0z'] },
  logout: { p: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'] },
  edit: { p: ['M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7', 'M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4z'] },
  'more-h': { c: [[5, 12, 1.5], [12, 12, 1.5], [19, 12, 1.5]] },
  refresh: { p: ['M23 4v6h-6', 'M1 20v-6h6', 'M3.51 9a9 9 0 0 1 14.85-3.36L23 10', 'M1 14l4.64 4.36A9 9 0 0 0 20.49 15'] },
  upload: { p: ['M12 16V4', 'M7 9l5-5 5 5', 'M5 20h14', 'M5 16v4', 'M19 16v4'] },
  message: { p: ['M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'] },
  'chevron-down': { p: ['M6 9l6 6 6-6'] },
  'arrow-right': { p: ['M5 12h14', 'M12 5l7 7-7 7'] },
  eye: {
    p: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  },
  'eye-off': {
    p: [
      'M17.94 17.94A10 10 0 0 1 12 19c-6.5 0-10-7-10-7a18 18 0 0 1 5.06-5.94M9.9 4.24A9 9 0 0 1 12 5c6.5 0 10 7 10 7a18 18 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24',
      'M1 1l22 22',
    ],
  },
}

export type IconName = keyof typeof ICONS

const props = withDefaults(
  defineProps<{
    name: IconName
    size?: number
    strokeWidth?: number
  }>(),
  { size: 16, strokeWidth: 1.5 },
)

const def = computed(() => ICONS[props.name] ?? {})
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in def.p ?? []" :key="`p${i}`" :d="d" />
    <circle v-for="(c, i) in def.c" :key="`c${i}`" :cx="c[0]" :cy="c[1]" :r="c[2]" />
  </svg>
</template>
