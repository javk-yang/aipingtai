/**
 * 通用 API 契约 —— 与后端 af-common 的 R.java / PageResult.java 一一对应
 *
 * 这是前后端的"握手协议"：
 * 后端改字段 → 这里改类型 → 编译期暴露所有用到的地方，不会带病上线。
 * 铁律：本文件是只读契约，业务代码不得修改这些类型。
 */

/** 统一响应体 R<T>（对应 af-common R.java）
 *  code=0 成功；非 0 失败（分段见 ErrorCode：1xxx 参数 / 2xxx 认证 / 3xxx 业务 / 5xxx 系统）
 *  traceId 用于把问题反馈给后端时一键拉取调用链
 */
export interface R<T = unknown> {
  code: number
  msg: string
  data: T | null
  /** 毫秒时间戳：排查异步/重试时序问题 */
  timestamp: number
  /** 链路追踪 ID：由后端 TraceIdFilter 写入 */
  traceId: string | null
}

/** 分页响应体（对应 af-common PageResult.java） */
export interface PageResult<T> {
  page: number
  size: number
  total: number
  records: T[]
}

/** 错误码分段（与后端 ErrorCode 枚举对齐，前端用于业务分支判断） */
export const ErrorCode = {
  OK: 0,
  /** 1xxx 参数错误 */
  PARAM_ERROR: 1000,
  /** 2xxx 认证错误 */
  UNAUTHORIZED: 2001,
  TOKEN_EXPIRED: 2002,
  TOKEN_INVALID: 2003,
  ACCOUNT_LOCKED: 2004,
  CAPTCHA_REQUIRED: 2005,
  /** 3xxx 业务错误 */
  BIZ_ERROR: 3000,
  /** 5xxx 系统错误 */
  SYSTEM_ERROR: 5000,
} as const

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode]
