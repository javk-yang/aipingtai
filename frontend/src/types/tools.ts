/**
 * 工具 / 技能 类型契约 —— 与后端 ToolResponse / SkillResponse 逐字段对齐
 * P8 MCP 工具注册中心 + P9 Skill 技能系统
 */

/** 管理端工具响应（治理元数据，绝不包含执行凭据） */
export interface ToolResponse {
  id: number
  code: string
  name: string
  description: string
  inputSchema: Record<string, unknown>
  outputSchema: Record<string, unknown>
  executorType: string
  transport: string
  timeoutMs: number
  enabled: boolean
}

/** 管理端技能响应 */
export interface SkillResponse {
  id: number
  code: string
  name: string
  description: string
  triggers: Array<Record<string, unknown>>
  content: Record<string, unknown>
  version: string
  enabled: boolean
  builtin: boolean
  createdAt: string
  updatedAt: string
}

/** 执行器类型展示映射 */
export const ExecutorTypeLabels: Record<string, string> = {
  python: 'Python',
  java: 'Java',
  http: 'HTTP',
  mcp: 'MCP',
}
