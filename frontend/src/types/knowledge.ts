/**
 * 知识库类型契约 —— 与后端 KnowledgeController DTO 逐字段对齐
 * P12 RAG 知识库：分块检索 + 溯源
 */

/** 文档响应 */
export interface KnowledgeResponse {
  docId: string
  title: string
  chunkCount: number
  /** 1已就绪 0处理中 */
  status: number
  createdAt: string
}

/** 文档详情（编辑态） */
export interface KnowledgeDetailResponse extends KnowledgeResponse {
  content: string
  updatedAt: string
}


export interface KnowledgeCreateRequest {
  title: string
  text: string
}

/** 检索请求 */
export interface KnowledgeSearchRequest {
  query: string
  topK?: number
}

/** 检索结果（含溯源） */
export interface KnowledgeSearchResult {
  query: string
  count: number
  results: ChunkHit[]
}

export interface ChunkHit {
  docId: string
  title: string
  chunkId: string
  text: string
  /** 相似度分数 0-1 */
  score: number
}
