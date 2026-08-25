/**
 * 用量 / 配额 / 审计 类型契约 —— 与后端 UsageStatsResponse / AuditLogResponse 逐字段对齐
 * P13 可观测性看板的数据契约
 */

/** 用量统计响应 —— GET /api/usage/stats */
export interface UsageStatsResponse {
  /** 今日累计 */
  today: UsageToday
  /** 配额状态（未配置时 quota=null，前端隐藏进度条） */
  quota: QuotaStatus | null
  /** 最近 7 天按天聚合（倒序，最新在前） */
  daily: UsageDaily[]
}

export interface UsageToday {
  calls: number
  tokenInput: number
  tokenOutput: number
  /** 成本（元），6 位小数 */
  cost: string
}

export interface QuotaStatus {
  tokenLimit: number
  tokenUsed: number
  remaining: number
  /** 已用百分比 0-100 */
  usedPercent: number
  /** 软告警阈值（百分比） */
  softThreshold: number
  /** 是否达到软阈值（预警不阻断） */
  softAlert: boolean
  /** 是否超限（阻断） */
  exceeded: boolean
}

export interface UsageDaily {
  date: string
  calls: number
  tokenInput: number
  tokenOutput: number
  cost: string
}

/** 审计日志单条记录 —— GET /api/audit/logs */
export interface AuditLogResponse {
  id: number
  tenantId: number
  traceId: string | null
  userId: number | null
  action: string
  resource: string | null
  resourceId: string | null
  detailJson: string | null
  ip: string | null
  userAgent: string | null
  /** 1成功 0失败 */
  status: number
  createdAt: string
}

/** 审计动作码展示字典（与后端 AuditAction 埋点对应） */
export const AuditActionLabels: Record<string, string> = {
  'user.register': '用户注册',
  'user.login': '用户登录',
  'chat.message.complete': '消息完成',
  'tool.call': '工具调用',
  'skill.call': '技能调用',
  'knowledge.upsert': '知识入库',
  'knowledge.delete': '知识删除',
  'agent.run': '智能体运行',
}
