// 知识库接口层 —— 与 KnowledgeController 端点一一对应（P12）
import { http } from '@/utils/request'
import type {
  KnowledgeCreateRequest,
  KnowledgeDetailResponse,
  KnowledgeResponse,
  KnowledgeSearchRequest,
  KnowledgeSearchResult,
} from '@/types/knowledge'

export const knowledgeApi = {
  /** 文档列表 */
  list: () => http.get<KnowledgeResponse[]>('/knowledge'),
  /** 新建文档（文本粘贴形态，服务端分块入库） */
  create: (body: KnowledgeCreateRequest) =>
    http.post<KnowledgeResponse>('/knowledge', body),
  /** 文档详情（编辑态） */
  get: (docId: string) => http.get<KnowledgeDetailResponse>(`/knowledge/${docId}`),

  update: (docId: string, body: KnowledgeCreateRequest) =>
    http.put<KnowledgeResponse>(`/knowledge/${docId}`, body),
  /** 用已保存正文重新建立索引 */
  reindex: (docId: string) =>
    http.post<KnowledgeResponse>(`/knowledge/${docId}/reindex`),
  /** 删除文档 */
  remove: (docId: string) =>
    http.delete<void>(`/knowledge/${docId}`),

  /** 检索（含溯源 chunk 命中） */
  search: (body: KnowledgeSearchRequest) =>
    http.post<KnowledgeSearchResult>('/knowledge/search', body),
}
