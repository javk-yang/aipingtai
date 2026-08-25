// 模型配置管理 接口层 —— 与后端 ModelConfigController 端点一一对应
// 端点前缀 /api/models（http 实例已带 /api baseURL）
import { http } from '@/utils/request'
import type { ModelConfig, ModelConfigRequest, ModelTestResult } from '@/types/model'

export const modelsApi = {
  list: () => http.get<ModelConfig[]>('/models'),
  get: (id: number) => http.get<ModelConfig>(`/models/${id}`),
  create: (body: ModelConfigRequest) => http.post<ModelConfig>('/models', body),
  update: (id: number, body: ModelConfigRequest) =>
    http.put<ModelConfig>(`/models/${id}`, body),
  remove: (id: number) => http.delete<void>(`/models/${id}`),
  /** 新建前/不落库连通性测试（入参即配置草稿） */
  test: (body: ModelConfigRequest) =>
    http.post<ModelTestResult>('/models/test', body),
  /** 已存在配置的连通性测试 */
  testId: (id: number) => http.post<ModelTestResult>(`/models/${id}/test`),
}
