// 会话/消息/聊天流式 接口层 —— 与后端 ConversationController / ChatController 端点一一对应
import { http, getAccessToken } from '@/utils/request'
import type { PageResult } from '@/types'
import type { ChatRequest, ChatStreamEvent, ConversationResponse, MessageResponse } from '@/types/chat'

/** 会话 CRUD(对应 ConversationController) */
export const conversationApi = {
  list: (page = 1, size = 20) =>
    http.get<PageResult<ConversationResponse>>('/conversations', { page, size }),
  create: (agentId?: number) =>
    http.post<ConversationResponse>('/conversations', { agentId }),
  get: (id: string) =>
    http.get<ConversationResponse>(`/conversations/${id}`),
  update: (id: string, body: { title?: string; status?: number }) =>
    http.patch<ConversationResponse>(`/conversations/${id}`, body),
  remove: (id: string) =>
    http.delete<void>(`/conversations/${id}`),
  // 断线重连/历史回看恢复点: 拉回已落库(含 status=0 流式中的)消息
  messages: (id: string) =>
    http.get<MessageResponse[]>(`/conversations/${id}/messages`),
}

/**
 * SSE 流式聊天 —— 原生 fetch 读 text/event-stream
 *
 * 为什么不用 EventSource?
 * EventSource 只支持 GET + 无 body, 而聊天请求是 POST(带 content)。
 * fetch + ReadableStream 能读任意 POST 的流式响应, 更灵活(也是 P11 工作台的做法)。
 *
 * 解析: SSE 以 "\n\n" 分隔事件, 每行 "event:/data:/id:", 多行 data 用 \n 拼接后 JSON.parse。
 */
export async function chatStream(
  body: ChatRequest,
  onEvent: (e: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const token = getAccessToken()
  const traceId = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Trace-Id': traceId,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`流式请求失败: HTTP ${resp.status}`)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const raw = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const ev = parseSseBlock(raw)
      if (ev) onEvent(ev)
    }
  }
}

/** 解析单个 SSE block -> ChatStreamEvent */
function parseSseBlock(raw: string): ChatStreamEvent | null {
  let type = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    // id: 后端用 seq 去重, 前端暂忽略
  }
  if (dataLines.length === 0) return null
  let payload: ChatStreamEvent['data']
  try {
    payload = JSON.parse(dataLines.join('\n'))
  } catch {
    return null
  }
  return { type: type as ChatStreamEvent['type'], data: payload }
}
