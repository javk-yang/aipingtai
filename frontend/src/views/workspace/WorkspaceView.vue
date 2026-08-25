<script setup lang="ts">
/**
 * 工作台壳子 —— P11 升级：三栏骨架 + 子路由渲染
 *
 * 布局（P0.5 原型基线）：
 *   侧边栏 248px（品牌 + 导航，激活态跟随路由）
 *   顶栏 52px（当前页标题 + 主题切换 + 用户信息）
 *   内容区 = <router-view>（Chat / Knowledge / Obs / Tools 四个子页）
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import type { IconName } from '@/components/icon/AfIcon.vue'

interface NavItem {
  key: string
  icon: IconName
  label: string
  path: string
  /** 是否需要权限码（无权限则隐藏） */
  perm?: string
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const themeStore = useThemeStore()

const navGroups: Array<{ label: string; items: NavItem[] }> = [
  {
    label: '平台',
    items: [
      { key: 'chat', icon: 'message', label: '会话工作台', path: '/workspace/chat' },
      { key: 'knowledge', icon: 'search', label: '知识库', path: '/workspace/knowledge', perm: 'agent:knowledge:read' },
      { key: 'agents', icon: 'robot', label: '智能体管理', path: '/workspace/agents', perm: 'agent:agent:read' },
      { key: 'tools', icon: 'settings', label: '工具与技能', path: '/workspace/tools', perm: 'agent:tool:read' },
      { key: 'models', icon: 'robot', label: '模型管理', path: '/workspace/models', perm: 'agent:model:read' },
    ],
  },
  {
    label: '运营',
    items: [
      { key: 'obs', icon: 'eye', label: '可观测', path: '/workspace/obs', perm: 'agent:usage:read' },
    ],
  },
]

/** 当前激活导航项：由路由 path 推导 */
const activePath = computed(() => route.path)

/** 过滤掉无权限的导航项 */
function visibleItems(items: NavItem[]) {
  return items.filter((item) => !item.perm || userStore.hasPerm(item.perm))
}

function onNav(path: string) {
  router.push(path)
}

/** 顶栏标题：跟随路由 */
const pageTitle = computed(() => {
  const map: Record<string, string> = {
    '/workspace/chat': '会话工作台',
    '/workspace/knowledge': '知识库',
    '/workspace/agents': '智能体管理',
    '/workspace/tools': '工具与技能',
    '/workspace/models': '模型管理',
    '/workspace/obs': '可观测',
  }
  return map[route.path] ?? 'AgentForge'
})

async function onLogout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <!-- 侧边栏：248px，P0.5 原型基线 -->
    <aside class="shell__sidebar">
      <div class="shell__brand">
        <span class="shell__logo"><AfIcon name="spark" :size="18" /></span>
        <span class="shell__brand-name">AgentForge</span>
      </div>

      <nav class="shell__nav">
        <template v-for="group in navGroups" :key="group.label">
          <p class="label-group shell__nav-group">{{ group.label }}</p>
          <button
            v-for="item in visibleItems(group.items)"
            :key="item.key"
            type="button"
            class="shell__nav-item"
            :class="{ 'is-active': activePath === item.path }"
            @click="onNav(item.path)"
          >
            <!-- 激活态：2px 左指示条 + 文字加重（P0.5 原则 4） -->
            <span class="shell__nav-indicator" />
            <AfIcon :name="item.icon" :size="16" />
            <span>{{ item.label }}</span>
          </button>
        </template>
      </nav>

      <div class="shell__sidebar-foot">
        <span class="mono shell__ver">v0.11 · P11</span>
      </div>
    </aside>

    <!-- 主区 -->
    <main class="shell__main">
      <header class="shell__topbar">
        <div class="shell__topbar-left">
          <h1 class="page-title">{{ pageTitle }}</h1>
          <span class="mono shell__trace">traceId: 由请求层自动注入</span>
        </div>
        <div class="shell__topbar-right">
          <AfButton variant="ghost" size="sm" @click="themeStore.toggle()">
            <AfIcon :name="themeStore.theme === 'light' ? 'moon' : 'sun'" :size="14" />
          </AfButton>
          <span class="shell__user">{{ userStore.user?.nickname || userStore.user?.username }}</span>
          <AfButton variant="ghost" size="sm" @click="onLogout">
            <AfIcon name="logout" :size="14" />
            登出
          </AfButton>
        </div>
      </header>

      <div class="shell__content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  width: 100%;
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
  background-color: var(--color-bg);
}

/* ---------- 侧边栏 ---------- */
.shell__sidebar {
  width: var(--sidebar-width);
  height: 100%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  background-color: var(--color-surface);
}
.shell__brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: var(--topbar-height);
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--color-border);
}
.shell__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-on-primary);
}
.shell__brand-name {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
}
.shell__nav {
  flex: 1;
  padding: var(--space-4) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}
.shell__nav-group {
  padding: var(--space-2) var(--space-3) var(--space-1);
}
.shell__nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  height: 34px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  color: var(--color-text-secondary);
  transition: color var(--transition-fast), background-color var(--transition-fast);
}
.shell__nav-item:hover {
  color: var(--color-text);
  background-color: var(--color-surface-2);
}
.shell__nav-indicator {
  position: absolute;
  left: -3px;
  top: 50%;
  transform: translateY(-50%);
  width: 2px;
  height: 16px;
  border-radius: 2px;
  background-color: transparent;
  transition: background-color var(--transition-fast);
}
.shell__nav-item.is-active {
  color: var(--color-text);
  font-weight: var(--weight-medium);
}
.shell__nav-item.is-active .shell__nav-indicator {
  background-color: var(--color-text);
}
.shell__sidebar-foot {
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--color-border);
}
.shell__ver {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

/* ---------- 主区 ---------- */
.shell__main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.shell__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--topbar-height);
  padding: 0 var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background-color: var(--color-surface);
}
.shell__topbar-left {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}
.shell__trace {
  color: var(--color-text-tertiary);
}
.shell__topbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.shell__user {
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  padding: 0 var(--space-2);
}
.shell__content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

@media (max-width: 720px) {
  .shell__sidebar { width: 196px; }
  .shell__topbar { padding: 0 16px; }
  .shell__trace { display: none; }
  .shell__user { display: none; }
}
</style>
