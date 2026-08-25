import { http } from '@/utils/request'

export interface AgentResponse {
  id: number
  code: string
  name: string
  description: string | null
  agentType: string
  /** 1 草稿，2 已发布/启用，3 下线 */
  status: number
  isDefault: number
  version: string | null
  systemPrompt: string | null
  modelConfigId: number | null
  toolIds: number[]
  skillIds: number[]
  knowledgeDocIds: string[]
  createdAt: string
  updatedAt: string
}

export interface AgentUpsertRequest {
  code: string
  name: string
  description?: string
  agentType?: string
  systemPrompt?: string
  modelConfigId?: number
  toolIds?: number[]
  skillIds?: number[]
  knowledgeDocIds?: string[]
  enabled?: boolean
  defaultAgent?: boolean
}

export const agentsApi = {
  list: () => http.get<AgentResponse[]>('/agents'),
  get: (id: number) => http.get<AgentResponse>(`/agents/${id}`),
  create: (body: AgentUpsertRequest) => http.post<AgentResponse>('/agents', body),
  update: (id: number, body: AgentUpsertRequest) => http.put<AgentResponse>(`/agents/${id}`, body),
  remove: (id: number) => http.delete<void>(`/agents/${id}`),
  setStatus: (id: number, enabled: boolean) => http.patch<AgentResponse>(`/agents/${id}/status`, { enabled }),
  publish: (id: number) => http.post<AgentResponse>(`/agents/${id}/publish`),
}
