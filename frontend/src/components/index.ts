/**
 * 基础组件库统一出口
 * P0 决策：自建约 15 个基础组件，不用现成组件库——
 * 视觉识别度是产品资产，套用 Element/Ant 会丢掉"千亿级"的辨识度。
 */
import type { App } from 'vue'
import AfIcon from './icon/AfIcon.vue'
import AfButton from './button/AfButton.vue'
import AfInput from './input/AfInput.vue'
import AfCard from './card/AfCard.vue'
import AfModal from './modal/AfModal.vue'
import AfMarkdown from './markdown/AfMarkdown.vue'

/** 全局注册：组件统一 Af 前缀，避免与原生元素/第三方冲突 */
export function installComponents(app: App) {
  app.component('AfIcon', AfIcon)
  app.component('AfButton', AfButton)
  app.component('AfInput', AfInput)
  app.component('AfCard', AfCard)
  app.component('AfModal', AfModal)
  app.component('AfMarkdown', AfMarkdown)
}

export { AfIcon, AfButton, AfInput, AfCard, AfModal, AfMarkdown }
