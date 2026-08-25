// 模型配置 类型契约 —— 与后端 af-session-api ModelConfigResponse / ModelConfigRequest 逐字段对齐
// 前端的"类型即契约"：后端改字段 → 这里改类型 → 编译期暴露所有用到处。

/** 供应商枚举（与后端 ModelConfigRequest.provider 注释对齐） */
export type ModelProvider =
  | 'openai'
  | 'openai-compatible'
  | 'deepseek'
  | 'qwen'
  | 'anthropic'
  | 'deterministic'

/** 供应商下拉选项（label 中文，value 与后端一致） */
export const PROVIDER_OPTIONS: Array<{ value: ModelProvider; label: string; hint: string }> = [
  { value: 'openai', label: 'OpenAI', hint: '官方 api.openai.com，或自定义 baseUrl' },
  { value: 'openai-compatible', label: 'OpenAI 兼容', hint: '任意兼容 /chat/completions 的端点' },
  { value: 'deepseek', label: 'DeepSeek', hint: 'DeepSeek 官方端点' },
  { value: 'qwen', label: '通义千问', hint: '阿里云百炼 DashScope 兼容端点' },
  { value: 'anthropic', label: 'Anthropic', hint: 'Claude 系列（兼容网关）' },
  { value: 'deterministic', label: '确定性(离线)', hint: '无外部 Key，用于演示与编排验证' },
]

/** 模型配置响应（对应 ModelConfigResponse，apiKey 已脱敏） */
export interface ModelConfig {
  id: number
  name: string
  provider: string
  model: string
  baseUrl: string | null
  /** 脱敏后的密钥，如 sk-****abcd */
  apiKey: string | null
  /** 采样温度（后端 BigDecimal → JSON number） */
  temperature: number
  /** 最大生成 token */
  maxTokens: number
  /** 1 启用 0 禁用 */
  enabled: number
  /** 1 默认模型 0 否 */
  isDefault: number
  description: string | null
  createdAt: string
  updatedAt: string
}

/** 模型配置新增/编辑请求（对应 ModelConfigRequest）
 *  apiKey 传明文即覆盖；传脱敏串(含 ****)表示不修改原值。
 */
export interface ModelConfigRequest {
  name: string
  provider: string
  model: string
  baseUrl?: string
  /** 明文密钥；留空或不传表示不修改（编辑态传脱敏串亦视为不修改） */
  apiKey?: string
  temperature?: number
  maxTokens?: number
  /** 1 启用 0 禁用 */
  enabled?: number
  /** 是否默认模型 */
  isDefault?: boolean
  description?: string
}

/** 连通性测试结果（对应 service.test 返回的 Map<String,Object>） */
export interface ModelTestResult {
  ok?: boolean
  message?: string
  model?: string
  latencyMs?: number
  [k: string]: unknown
}
