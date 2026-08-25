// 会话/消息/聊天流式 类型契约 —— 与后端 af-session-api DTO 逐字段对齐
// 前端的"类型即契约": 编译期就能发现和后端 DTO 不一致的字段

/** 会话响应(对应 ConversationResponse) */
export interface ConversationResponse {
  id: string
  userId: number
  agentId: number | null
  title: string | null
  status: number // 1活跃 2归档 3已删除
  messageCount: number
  lastMessageAt: string | null
  createdAt: string
  updatedAt: string
}

/** 消息响应(对应 MessageResponse) */
export interface MessageResponse {
  id: number
  conversationId: string
  role: string // user / assistant / tool / system
  seq: number
  content: string | null
  contentType: string // text / markdown / json
  status: number // 0流式中 1完成 2失败 3中断
  model: string | null
  tokenInput: number
  tokenOutput: number
  parentId: number | null
  createdAt: string
}

/** SSE 流式事件(对应 ChatStreamEvent) —— 平台级"流式语言" */
export type ChatEventType =
  | 'message_start'
  | 'content_delta'
  | 'tool_call_start'
  | 'tool_call_result'
  | 'tool_call_error'
  | 'skill_call_start'
  | 'skill_call_result'
  | 'skill_call_error'
  | 'message_done'
  | 'error'
  | 'ping'

export interface ChatStreamEvent {
  type: ChatEventType
  conversationId?: string
  messageId?: number
  seq?: number
  /** 负载: 不同事件语义不同(见后端 ChatStreamEvent 注释) */
  data?: {
    delta?: string
    role?: string
    messageId?: number
    model?: string
    tokenInput?: number
    tokenOutput?: number
    callId?: string
    toolId?: number | null
    toolCode?: string
    toolName?: string
    arguments?: Record<string, unknown>
    result?: unknown
    status?: 'running' | 'success' | 'error' | 'timeout'
    durationMs?: number
    errorCode?: string
    errorMessage?: string
    // 技能事件字段（skill_call_*）
    skillId?: number | null
    skillCode?: string
    skillName?: string
    skillVersion?: string
    callArgs?: Record<string, unknown>
    code?: number
    message?: string
    [k: string]: unknown
  }
  traceId?: string
}

export interface ToolCallView {
  callId: string
  toolCode: string
  toolName: string
  arguments: Record<string, unknown>
  result?: unknown
  status: 'running' | 'success' | 'error' | 'timeout'
  durationMs: number
  errorMessage?: string
}

/** 技能调用时间线条目（skill_call_* 事件聚合） */
export interface SkillCallView {
  callId: string
  skillCode: string
  skillName: string
  skillVersion?: string
  callArgs?: Record<string, unknown>
  result?: unknown
  status: 'running' | 'success' | 'error' | 'timeout'
  durationMs: number
  errorMessage?: string
}

/** 聊天请求(对应 ChatRequest) */
export interface ChatRequest {
  content: string
  conversationId?: string
  agentId?: number
  /** 模型配置 ID：选择本次对话使用的大模型；不传则走租户默认模型 */
  modelConfigId?: number
}
