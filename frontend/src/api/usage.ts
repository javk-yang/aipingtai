// 用量 / 审计 接口层 —— 与 UsageController / AuditController 端点一一对应（P13）
import { http } from '@/utils/request'
import type { PageResult } from '@/types'
import type { AuditLogResponse, UsageStatsResponse } from '@/types/usage'

/** 用量统计：今日累计 + 配额状态 + 最近 7 天趋势 */
export const usageApi = {
  stats: () => http.get<UsageStatsResponse>('/usage/stats'),
}

/** 审计日志：只读分页查询，支持 action / userId 过滤 */
export const auditApi = {
  logs: (params: {
    action?: string
    userId?: number
    page?: number
    size?: number
  }) => http.get<PageResult<AuditLogResponse>>('/audit/logs', params),
}
