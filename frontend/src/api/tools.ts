// 工具 / 技能接口层 —— 与管理端 CRUD 端点一一对应
import { http } from '@/utils/request'
import type { SkillResponse, ToolResponse } from '@/types/tools'

export interface ToolCreateRequest {
  code: string
  name: string
  description?: string
  mcpServerId?: number
  inputSchema: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  async?: boolean
  timeoutMs?: number
}

export interface SkillCreateRequest {
  code: string
  name: string
  description?: string
  triggers: Array<Record<string, unknown>>
  content?: Record<string, unknown>
  skillFileUrl?: string
  version?: string
}

export const toolsApi = {
  list: () => http.get<ToolResponse[]>('/tools'),
  create: (body: ToolCreateRequest) => http.post<ToolResponse>('/tools', body),
  update: (id: number, body: ToolCreateRequest) => http.patch<ToolResponse>(`/tools/${id}`, body),
  setStatus: (id: number, enabled: boolean) =>
    http.patch<ToolResponse>(`/tools/${id}/status`, { enabled }),
  remove: (id: number) => http.delete<void>(`/tools/${id}`),
  listServers: () => http.get<unknown[]>('/tools/mcp-servers'),
}

export interface SkillUploadResponse {
  id: number
  code: string
  name: string
  version: string
  filePath: string
  imported: boolean
}

export const skillsApi = {
  list: () => http.get<SkillResponse[]>('/skills'),
  upload: (file: File, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    // 不手动设置 Content-Type：交由浏览器/Axios 自动补充带 boundary 的 multipart 头，
    // 否则 Spring MultipartFile 会因缺少 boundary 解析失败。
    return http.post<SkillUploadResponse>('/skills/upload', form, {
      onUploadProgress: (event) => {
        if (event.total) onProgress?.(Math.round((event.loaded / event.total) * 100))
      },
    })
  },
  create: (body: SkillCreateRequest) => http.post<SkillResponse>('/skills', body),
  update: (id: number, body: SkillCreateRequest) => http.patch<SkillResponse>(`/skills/${id}`, body),
  setStatus: (id: number, enabled: boolean) =>
    http.patch<SkillResponse>(`/skills/${id}/status`, { enabled }),
  remove: (id: number) => http.delete<void>(`/skills/${id}`),
}
