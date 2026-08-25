/**
 * 应用入口 —— 只做组装，零业务逻辑（对应后端 af-bootstrap 的角色）
 *
 * 装配顺序有讲究：
 * 1. pinia 必须先于路由守卫生效——guards.ts 里的 useUserStore 依赖它
 * 2. guards.ts 用"副作用导入"注册守卫（import 即生效），不显式调用
 * 3. 全局组件注册统一走 components/index.ts，main.ts 不堆 import
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import './router/guards' // 副作用导入：注册 beforeEach / afterEach
import './styles/index.css'
import { installComponents } from './components'

const app = createApp(App)

app.use(createPinia())
app.use(router)
installComponents(app)

app.mount('#app')
