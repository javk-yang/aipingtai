/**
 * 请求层 —— 全站唯一的 axios 实例
 *
 * 封装的五件事（先讲原理）：
 *
 * 1. traceId 注入（铁律 4 的前端端）
 *    请求发出前，若已有 traceId 则透传，否则生成新 UUID。
 *    这样一次用户操作 = 一个 traceId，贯穿 浏览器 → Java → Python 全链路日志。
 *
 * 2. Access Token 注入
 *    Authorization: Bearer <token>。token 只存内存（模块变量），不落 localStorage：
 *    localStorage 任何 XSS 都能读，内存只活在当前页面，攻击面小得多。
 *    刷新页面后的恢复靠 refreshToken（见 stores/user.ts 的 restoreSession）。
 *
 * 3. 401 刷新队列（并发场景的关键设计）
 *    页面打开瞬间可能同时发 10 个请求，access token 恰好过期 → 10 个请求全部 401。
 *    如果每个请求各自调 /auth/refresh，会刷新 10 次，后 9 次拿到的 refreshToken
 *    已被第一次轮换作废 → 全部失败。解法：只让第一个 401 触发刷新，
 *    其余 401 把自己的"重放请求"挂到队列里，新 token 一到统一重放。
 *
 * 4. 统一错误翻译
 *    所有失败最终都规约为 { code, msg }，业务代码只关心 msg 展示。
 *
 * 5. 业务码与 HTTP 码分离
 *    后端设计 R.code=0 表示业务成功，HTTP 状态不代表业务成败。
 *    所以这里以 R.code 为唯一成功判据（response.data.code === 0）。
 */

import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { R } from '@/types'

/** 基础配置：开发环境走 vite proxy → 8080，生产由 Nginx 同源反代 */
const BASE_URL = '/api'
const TIMEOUT = 15_000

/** 内存 token（不落 localStorage，见设计 2） */
let accessToken: string | null = null
let refreshToken: string | null = null

/** token 到期事件：stores/user.ts 订阅，用于到期前主动刷新 */
type TokenExpiredListener = () => void
const tokenExpiredListeners: TokenExpiredListener[] = []

export function setTokens(access: string | null, refresh: string | null) {
  accessToken = access
  refreshToken = refresh
  // 登录和刷新成功后都持久化 refresh token，保证刷新页面不会被误判为“登录已过期”。
  // access token 仍只保存在内存中，避免长期落盘。
  persistRefreshToken(refresh)
}

/** 取内存中的 access token（供 SSE 原生 fetch 注入 Authorization 头，不走 axios 拦截器） */
export function getAccessToken(): string | null {
  return accessToken
}

export function onTokenExpired(listener: TokenExpiredListener) {
  tokenExpiredListeners.push(listener)
}

function notifyTokenExpired() {
  tokenExpiredListeners.forEach((l) => l())
}

/* ---------- 401 刷新队列状态 ---------- */
let isRefreshing = false
/** 等待新 token 的重放请求队列 */
let pendingQueue: Array<(token: string) => void> = []

/** 用新 access token 重放排队的请求 */
function flushQueue(newToken: string) {
  pendingQueue.forEach((resolve) => resolve(newToken))
  pendingQueue = []
}

/** 生成 traceId（浏览器环境 UUID） */
function newTraceId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 单次刷新：调用 /auth/refresh，成功返回新 token，失败抛错 */
async function doRefresh(): Promise<string> {
  if (!refreshToken) throw new Error('refresh token 缺失')
  const resp = await axios.post<R<TokenRefreshPayload>>(`${BASE_URL}/auth/refresh`, {
    refreshToken,
  })
  const body = resp.data
  if (body.code !== 0 || !body.data) {
    throw new Error(body.msg || '刷新令牌失败')
  }
  accessToken = body.data.accessToken
  refreshToken = body.data.refreshToken
  // 同步回持久层（refreshToken 存 localStorage 用于刷新后恢复会话）
  persistRefreshToken(refreshToken)
  return accessToken
}

interface TokenRefreshPayload {
  accessToken: string
  refreshToken: string
}

/* ---------- refreshToken 持久化（模块内私有） ---------- */
const REFRESH_KEY = 'af-refresh-token'

function persistRefreshToken(token: string | null) {
  if (token) localStorage.setItem(REFRESH_KEY, token)
  else localStorage.removeItem(REFRESH_KEY)
}
export function getPersistedRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

/** 登出：清内存 + 清持久化 */
export function clearTokens() {
  accessToken = null
  refreshToken = null
  persistRefreshToken(null)
}

/* ---------- axios 实例 ---------- */
const service = axios.create({
  baseURL: BASE_URL,
  timeout: TIMEOUT,
})

/* 请求拦截器：traceId + token */
service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // traceId 透传：有过就带，没有就生成（第一次入口）
  config.headers.set('X-Trace-Id', config.headers.get('X-Trace-Id') || newTraceId())
  if (accessToken) {
    config.headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return config
})

/* 响应拦截器：错误翻译 + 401 刷新队列 */
service.interceptors.response.use(
  (response) => {
    // 业务成功以 R.code === 0 为准（HTTP 200 只代表"请求到达了服务器"）
    const body = response.data as R
    if (body && typeof body.code === 'number' && body.code !== 0) {
      // 业务失败：统一 reject，让调用方走 catch
      return Promise.reject(new ApiError(body.code, body.msg, body.traceId))
    }
    return response
  },
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean
    }
    const status = error.response?.status
    const body = error.response?.data as R | undefined

    // 1) 未登录场景（无 token 访问受保护接口）→ 直接 401 跳登录，不走刷新
    if (status === 401 && !accessToken) {
      notifyTokenExpired()
      return Promise.reject(new ApiError(2001, '登录已过期，请重新登录', null))
    }

    // 2) access token 过期 → 刷新一次后重放原请求
    if (status === 401 && original && !original._retry) {
      if (isRefreshing) {
        // 已有请求在刷新：排队等新 token
        return new Promise((resolve) => {
          pendingQueue.push((newToken) => {
            original.headers.set('Authorization', `Bearer ${newToken}`)
            resolve(service(original))
          })
        })
      }

      original._retry = true
      isRefreshing = true
      try {
        const newToken = await doRefresh()
        flushQueue(newToken)
        original.headers.set('Authorization', `Bearer ${newToken}`)
        return service(original)
      } catch (refreshErr) {
        // 刷新失败（refresh 也过期/作废）→ 清凭证，通知上层跳登录
        flushQueueFail()
        clearTokens()
        notifyTokenExpired()
        return Promise.reject(
          new ApiError(2001, '登录已过期，请重新登录', null),
        )
      } finally {
        isRefreshing = false
      }
    }

    // 3) 刷新接口自己 401 → refresh token 已废
    if (status === 401 && original?.url?.includes('/auth/refresh')) {
      clearTokens()
      notifyTokenExpired()
      return Promise.reject(new ApiError(2001, '登录已过期，请重新登录', null))
    }

    // 4) 网络/超时/5xx
    if (!error.response) {
      return Promise.reject(new ApiError(5000, '网络异常，请检查网络连接', null))
    }
    const msg =
      body?.msg || `服务异常（HTTP ${status}），请稍后重试或联系管理员`
    return Promise.reject(new ApiError(body?.code ?? 5000, msg, body?.traceId ?? null))
  },
)

/** 刷新失败时：把排队请求全部以失败收尾，避免请求永远挂起 */
function flushQueueFail() {
  pendingQueue.forEach((resolve) => resolve(''))
  pendingQueue = []
}

/* ---------- 业务异常类型 ---------- */
export class ApiError extends Error {
  code: number
  traceId: string | null
  constructor(code: number, msg: string, traceId: string | null) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

/* ---------- 对外请求函数（泛型返回 R<T>） ----------
 * 成功判据只有一条：code === 0。
 * data 为 null 是合法成功（如 logout / sendSmsCode），返回 null 给调用方。
 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request<R<T>>(config).then((resp) => {
    const body = resp.data
    if (body.code !== 0) {
      throw new ApiError(body.code, body.msg, body.traceId)
    }
    return body.data as T
  })
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) {
    return request<T>({ ...config, url, method: 'GET', params })
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, url, method: 'POST', data })
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, url, method: 'PUT', data })
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return request<T>({ ...config, url, method: 'PATCH', data })
  },
  delete<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) {
    return request<T>({ ...config, url, method: 'DELETE', params })
  },
}
