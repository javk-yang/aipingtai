/**
 * 主题状态 —— 双主题切换的唯一入口
 *
 * 机制：主题 = html[data-theme] 上的一个属性。
 * CSS 侧 tokens.css 已把全部颜色挂在 [data-theme] 选择器下，
 * JS 侧只负责改属性 + 持久化，颜色变化由 CSS 变量自动完成。
 *
 * 持久化 key 必须与 index.html 内联脚本一致（af-theme）：
 * 内联脚本在首屏渲染前同步读它，避免白屏闪烁（FOUC）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export type Theme = 'light' | 'dark'

const THEME_KEY = 'af-theme'

/** 读取初始主题：localStorage 优先，其次跟随系统，默认浅色 */
function initialTheme(): Theme {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  if (window.matchMedia('(prefers-color-scheme: dark)').matches) return 'dark'
  return 'light'
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(initialTheme())

  function apply(t: Theme) {
    theme.value = t
    // 关键：只改 html 属性，CSS 变量联动由 tokens.css 完成
    document.documentElement.setAttribute('data-theme', t)
    localStorage.setItem(THEME_KEY, t)
  }

  function toggle() {
    apply(theme.value === 'light' ? 'dark' : 'light')
  }

  return { theme, apply, toggle }
})
