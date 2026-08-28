<script setup lang="ts">
/**
 * 工作台外壳：仅负责导航、主题与路由出口。
 * 本文件的改动限定为视觉层，不修改权限、路由和登出业务逻辑。
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

const activePath = computed(() => route.path)

function visibleItems(items: NavItem[]) {
  return items.filter((item) => !item.perm || userStore.hasPerm(item.perm))
}

function onNav(path: string) {
  router.push(path)
}

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
    <aside class="shell__sidebar">
      <div class="shell__brand">
        <span class="shell__logo" aria-hidden="true"><AfIcon name="spark" :size="19" :stroke-width="1.65" /></span>
        <span class="shell__brand-copy">
          <span class="shell__brand-name">AgentForge</span>
          <span class="shell__brand-sub">INTELLIGENCE STUDIO</span>
        </span>
      </div>

      <div class="shell__spectrum" aria-hidden="true"><span /></div>

      <nav class="shell__nav" aria-label="主导航">
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
            <span class="shell__nav-indicator" aria-hidden="true" />
            <span class="shell__nav-icon"><AfIcon :name="item.icon" :size="16" :stroke-width="1.65" /></span>
            <span class="shell__nav-label">{{ item.label }}</span>
          </button>
        </template>
      </nav>

      <div class="shell__sidebar-foot">
        <span class="shell__presence" aria-hidden="true" />
        <span class="mono shell__ver">SYSTEM ONLINE · v0.11</span>
      </div>
    </aside>

    <main class="shell__main">
      <header class="shell__topbar">
        <div class="shell__topbar-left">
          <div class="shell__title-mark" aria-hidden="true" />
          <div>
            <p class="label-group shell__eyebrow">WORKSPACE / {{ pageTitle }}</p>
            <h1 class="page-title">{{ pageTitle }}</h1>
          </div>
        </div>
        <div class="shell__topbar-right">
          <span class="mono shell__trace">live session · trace injected</span>
          <AfButton class="shell__theme-button" variant="ghost" size="sm" :title="themeStore.theme === 'light' ? '切换到深色主题' : '切换到浅色主题'" @click="themeStore.toggle()">
            <AfIcon :name="themeStore.theme === 'light' ? 'moon' : 'sun'" :size="15" :stroke-width="1.65" />
          </AfButton>
          <span class="shell__profile-mark">{{ (userStore.user?.nickname || userStore.user?.username || 'A').slice(0, 1) }}</span>
          <span class="shell__user">{{ userStore.user?.nickname || userStore.user?.username }}</span>
          <AfButton class="shell__logout" variant="ghost" size="sm" title="退出登录" @click="onLogout">
            <AfIcon name="logout" :size="15" :stroke-width="1.65" />
            <span>登出</span>
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
  background: var(--color-bg);
}

.shell__sidebar {
  position: relative;
  width: var(--sidebar-width);
  height: 100%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-surface) 94%, transparent);
}

.shell__brand {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 88px;
  padding: 0 22px;
}

.shell__logo,
.shell__nav-icon,
.shell__profile-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.shell__logo {
  width: 34px;
  height: 34px;
  color: var(--color-on-primary);
  border-radius: 12px;
  background: var(--color-primary);
  box-shadow: var(--shadow-float);
}

.shell__brand-copy { display: flex; flex-direction: column; gap: 1px; }
.shell__brand-name { font-size: 15px; font-weight: var(--weight-semibold); letter-spacing: -0.035em; }
.shell__brand-sub { font-size: 8px; font-weight: var(--weight-medium); color: var(--color-text-tertiary); letter-spacing: 0.18em; }

.shell__spectrum { height: 2px; margin: 0 22px; overflow: hidden; border-radius: var(--radius-pill); background: var(--color-lifeline-soft); }
.shell__spectrum span { display: block; width: 56%; height: 100%; border-radius: inherit; background: var(--color-lifeline); }

.shell__nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 20px 14px;
  overflow-y: auto;
}

.shell__nav-group { padding: 16px 10px 6px; }
.shell__nav-group:first-child { padding-top: 0; }

.shell__nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 40px;
  padding: 0 10px;
  overflow: hidden;
  border-radius: 11px;
  color: var(--color-text-secondary);
  font-size: var(--text-base);
  text-align: left;
  transition: color var(--transition-fast), background-color var(--transition-fast), transform var(--transition-fast);
}

.shell__nav-item::before {
  position: absolute;
  inset: 0;
  content: '';
  opacity: 0;
  background: var(--color-lifeline-soft);
  transition: opacity var(--transition-base);
}

.shell__nav-item:hover { color: var(--color-text); background: var(--color-surface-2); transform: translateX(2px); }
.shell__nav-item.is-active { color: var(--color-text); font-weight: var(--weight-medium); background: color-mix(in srgb, var(--color-surface-raised) 74%, transparent); }
.shell__nav-item.is-active::before { opacity: 1; }
.shell__nav-icon, .shell__nav-label { position: relative; z-index: 1; }
.shell__nav-icon { width: 23px; height: 23px; border: 1px solid transparent; border-radius: 8px; color: var(--color-text-secondary); }
.shell__nav-item.is-active .shell__nav-icon { color: var(--color-text); border-color: var(--color-border); background: var(--color-surface-raised); }

.shell__nav-indicator {
  position: absolute;
  z-index: 2;
  left: 0;
  top: 50%;
  width: 3px;
  height: 0;
  transform: translateY(-50%);
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--color-lifeline);
  transition: height var(--transition-base);
}
.shell__nav-item.is-active .shell__nav-indicator { height: 21px; }

.shell__sidebar-foot { display: flex; align-items: center; gap: 8px; padding: 17px 22px; border-top: 1px solid var(--color-border); }
.shell__presence { width: 6px; height: 6px; border-radius: 50%; background: var(--color-success); box-shadow: 0 0 0 4px var(--color-success-bg); }
.shell__ver { color: var(--color-text-tertiary); font-size: 9px; letter-spacing: 0.06em; }

.shell__main { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.shell__topbar { display: flex; align-items: center; justify-content: space-between; height: var(--topbar-height); padding: 0 28px; border-bottom: 1px solid var(--color-border); background: color-mix(in srgb, var(--color-surface) 88%, transparent); }
.shell__topbar-left, .shell__topbar-right { display: flex; align-items: center; }
.shell__topbar-left { gap: 12px; }
.shell__topbar-right { gap: 8px; }
.shell__title-mark { width: 3px; height: 27px; border-radius: var(--radius-pill); background: var(--color-lifeline); }
.shell__eyebrow { margin-bottom: 0; font-size: 9px; }
.shell__trace { margin-right: 7px; color: var(--color-text-tertiary); font-size: 10px; }
.shell__theme-button :deep(svg) { color: var(--color-text-secondary); }
.shell__profile-mark { width: 27px; height: 27px; border: 1px solid var(--color-border); border-radius: 50%; background: var(--color-icon-bg); color: var(--color-text-secondary); font-size: 11px; font-weight: var(--weight-semibold); }
.shell__user { padding-right: 2px; font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text-secondary); }
.shell__logout { color: var(--color-text-secondary); }
.shell__content { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }

@media (max-width: 860px) {
  .shell__sidebar { width: 210px; }
  .shell__brand { padding: 0 16px; }
  .shell__spectrum { margin: 0 16px; }
  .shell__topbar { padding: 0 18px; }
  .shell__trace, .shell__user, .shell__logout span { display: none; }
}

@media (max-width: 620px) {
  .shell__sidebar { width: 58px; }
  .shell__brand { justify-content: center; padding: 0; }
  .shell__brand-copy, .shell__spectrum, .shell__nav-label, .shell__nav-group, .shell__sidebar-foot { display: none; }
  .shell__nav { align-items: center; padding: 16px 8px; }
  .shell__nav-item { justify-content: center; padding: 0; }
  .shell__nav-icon { width: 28px; height: 28px; }
  .shell__topbar { padding: 0 14px; }
}
/* ---- 小巧灵动的工作室导航：参考卡片式轻量节奏 ---- */
.shell__sidebar { width: 232px; background: color-mix(in srgb, var(--color-surface) 93%, transparent); }
.shell__brand { height: 72px; padding: 0 16px; gap: 9px; }.shell__logo { width: 30px; height: 30px; border-radius: 10px; }.shell__brand-name { font-size: 14px; }.shell__brand-sub { font-size: 7px; }.shell__spectrum { margin: 0 16px; }
.shell__nav { gap: 2px; padding: 16px 10px; }.shell__nav-group { padding: 13px 9px 5px; }.shell__nav-item { height: 36px; gap: 8px; padding: 0 9px; border-radius: 10px; font-size: var(--text-sm); }.shell__nav-icon { width: 22px; height: 22px; border-radius: 7px; }.shell__nav-item.is-active .shell__nav-indicator { height: 18px; }.shell__sidebar-foot { padding: 14px 16px; }
.shell__topbar { height: 56px; padding: 0 20px; }.shell__topbar-left { gap: 10px; }.shell__title-mark { width: 2px; height: 23px; }.page-title { font-size: 18px; }.shell__profile-mark { width: 25px; height: 25px; font-size: 10px; }
@media (max-width: 860px) { .shell__sidebar { width: 196px; } .shell__brand { padding: 0 14px; } .shell__spectrum { margin: 0 14px; } }
</style>
