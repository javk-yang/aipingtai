# AgentForge 全阶段完成概览（P0–P14）

## 完成内容

AgentForge 大模型 Agent 平台 **15 个阶段全部完成**，当前五端全链路运行中，前端工作台可在线体验。

- **P10 代码执行沙箱**：AST 预检（import 白名单 / 调用黑名单 / 魔术属性拦截）+ 进程组隔离（`start_new_session` + `killpg` 强杀）+ 资源限额（CPU 2s / 内存 256MB / fd 32）+ 输出截断 8KB；`code_exec` 工具闭环（`tool_call_start → tool_call_result`）。
- **P11 前端 Agent 工作台**：`WorkspaceView` 壳 + 子路由（chat/knowledge/tools/obs）；ChatView 流式渲染 + 工具/技能调用时间线卡片 + 停止按钮；自研 AfMarkdown（代码高亮/行号/复制，无 XSS）；ObservabilityView（用量卡片 + 配额条 + 7 天趋势 SVG + 审计日志）；KnowledgeView（检索高亮 + 文档管理）；ToolsView（工具/技能网格）。构建双绿（vue-tsc + vite build）。
- **P12 RAG 知识库**：Python 侧 `data/knowledge/` 向量索引（PG 降级方案）+ MySQL `knowledge_doc` 元数据；`/api/knowledge/index|search`（分块 + 相似度 + 溯源）。
- **P13 可观测性与计费**：`audit_log` 审计底座 + `api_usage` 用量记账 + Redis 配额预检 + `/api/usage/stats` + `/api/audit/logs`。
- **P14 部署**：`scripts/start-all.sh` 幂等五端启动（MySQL 3308 / Redis 6379 / Engine 8000 / Java 8080 / Vite 5173），日志统一入 `logs/`。

## 本阶段关键修复

1. **code_exec 结果校验失败**（`None is not of type 'string'`）：DB 种子 `output_schema` 中 `error_code`/`exit_code` 声明为不可空，沙箱成功时返回 `null` 触发校验失败 → 改为 `anyOf null` 可空，沙箱执行 `sum(1..100)=5050` 验证通过。
2. **技能触发词劫持计算器**：unit_converter 关键词含"等于多少"，把"计算 X 等于多少"错误路由到技能 → 移除该词 + 正则支持小数；"计算 (15+7)*3-2 等于多少" 正确走 calculator 工具（=64），"5.5 kg 转换成斤" 仍走技能。
3. **联调字段校准**：登录取 `data.accessToken`；聊天请求体字段为 `content`（非 message）；`knowledge_doc` 补齐 `deleted_at` 列。

## 真实验证（curl E2E）

| 链路 | 结果 |
| --- | --- |
| 计算器 `(15+7)*3-2` | `tool_call_result` = 64 ✅ |
| 当前时间 | `tool_call_result`（Asia/Shanghai）✅ |
| code_exec 沙箱 `sum(1..100)` | `tool_call_result` stdout=5050，21ms ✅ |
| unit_converter 技能 5.5kg→斤 | `skill_call_start → skill_call_result` ✅ |
| 知识库检索 | `/api/knowledge/search` hits + score ✅ |
| 配额统计 | `/api/usage/stats` 今日 14 次调用、剩余 998,810 tokens ✅ |

## 在线体验

- 前端工作台：**http://localhost:5173**（账号 `admin` / 密码 `Admin@2026`）
- 五端状态：MySQL ✅ / Redis ✅ / Agent 引擎 ✅ / Java ✅ / Vite ✅
