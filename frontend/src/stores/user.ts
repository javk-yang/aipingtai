/**
 * 用户状态 —— 登录态 + 角色 + 权限码
 *
 * 与后端的对应关系：
 * - setLogin 对应 JwtAuthFilter"解析并装填 UserContext"
 * - fetchMe 对应 GET /api/auth/me（UserInfoResponse，含 permissions）
 * - hasPerm 对应后端 PermissionAspect 的权限注解逻辑（前端按钮级版本）
 *
 * token 生命周期：
 *   accessToken 只存内存（见 request.ts），刷新页面后靠 restoreSession
 *   用持久化的 refreshToken 调 /auth/refresh 恢复会话。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import type { TokenResponse, UserInfoResponse } from '@/types'
import {
  clearTokens,
  getPersistedRefreshToken,
  setTokens,
} from '@/utils/request'

export const useUserStore = defineStore('user', () => {
  /** 当前用户完整信息（含权限码） */
  const user = ref<UserInfoResponse | null>(null)
  /** 是否正在恢复会话（避免路由守卫重复触发） */
  const restoring = ref(false)

  const isLoggedIn = computed(() => user.value !== null)

  /** 权限检查：admin 通配（与后端 PermissionAspect 一致） */
  function hasPerm(perm: string): boolean {
    const u = user.value
    if (!u) return false
    if (u.roles.includes('admin')) return true
    return u.permissions.includes(perm)
  }

  /** 登录成功：存 token + 拉取完整资料 */
  async function setLogin(resp: TokenResponse) {
    setTokens(resp.accessToken, resp.refreshToken)
    await fetchMe()
  }

  /** 拉取 /me：刷新资料与权限码（登录后 / 刷新页面后 / 权限变更后） */
  async function fetchMe() {
    user.value = await authApi.getMe()
  }

  /** 刷新页面后恢复会话：有 refreshToken 就换新 token + 拉资料 */
  async function restoreSession(): Promise<boolean> {
    if (restoring.value) return user.value !== null
    restoring.value = true
    try {
      const refresh = getPersistedRefreshToken()
      if (!refresh) return false
      const resp = await authApi.refresh({ refreshToken: refresh })
      setTokens(resp.accessToken, resp.refreshToken)
      await fetchMe()
      return true
    } catch {
      clearTokens()
      user.value = null
      return false
    } finally {
      restoring.value = false
    }
  }

  /** 登出：通知后端吊销 refresh token + 清空本地 */
  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 后端吊销失败也要继续清本地（服务端 key 有 TTL 兜底）
    } finally {
      clearTokens()
      user.value = null
    }
  }

  return { user, restoring, isLoggedIn, hasPerm, setLogin, fetchMe, restoreSession, logout }
})
