/**
 * 路由守卫 —— 登录态 + 权限 的前端闸门
 *
 * 三段式（先讲原理）：
 * 1. 白名单直放：/login 这类无需登录的路由，不触发任何会话恢复
 * 2. 登录态恢复：刷新页面后 store 是空的，这里懒加载 restoreSession
 *    （用持久化的 refreshToken 换新 token）。整个应用只有这一个恢复入口，
 *    避免每个页面各自判断"我到底登没登录"。
 * 3. 权限检查：meta.perm 存在时用 hasPerm 校验，无权 → 403 页
 *
 * 注意：restoreSession 只应执行一次。用模块级布尔量保护，
 * 否则每次路由跳转都调 /auth/refresh，白耗网络。
 */
import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router'
import { router } from './index'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'

/** 会话是否已尝试恢复过（模块级：进程内只恢复一次） */
let sessionRestored = false

router.beforeEach(async (to: RouteLocationNormalized, _from, next: NavigationGuardNext) => {
  const userStore = useUserStore()
  const themeStore = useThemeStore()

  // 主题属性同步（防万一：某些入口跳过了 index.html 内联脚本）
  themeStore.apply(themeStore.theme)

  // 1) 白名单直放
  if (to.meta.requiresAuth === false) {
    // 已登录用户访问登录页 → 直接送去工作台（常见体验优化）
    if (userStore.isLoggedIn) return next({ path: '/workspace' })
    return next()
  }

  // 2) 登录态恢复（惰性：只有真的需要时才开始）
  if (!sessionRestored && !userStore.isLoggedIn) {
    sessionRestored = true
    try {
      const ok = await userStore.restoreSession()
      if (!ok) {
        return next({ path: '/login', query: { redirect: to.fullPath } })
      }
    } catch {
      return next({ path: '/login', query: { redirect: to.fullPath } })
    }
  }

  // 恢复后仍无登录态 → 登录页
  if (!userStore.isLoggedIn) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  // 3) 权限检查（对应后端 @RequirePermission）
  if (to.meta.perm && !userStore.hasPerm(to.meta.perm)) {
    return next({ path: '/403' })
  }

  next()
})

/* 标题守卫：meta.title 同步到 document.title */
router.afterEach((to) => {
  if (to.meta.title) document.title = to.meta.title
})
