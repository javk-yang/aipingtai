# AgentForge 企业级 AI Agent 平台

## 项目全貌、架构设计、实现细节、测试验收与问题修复总结

> 文档版本：v1.0
>
> 整理时间：2026-08-24
>
> 代码仓库：`https://github.com/javk-yang/aipingtai`
>
> 文档定位：面向项目负责人、架构师、后端/前端/AI 工程师、测试与运维人员的完整交付说明。
>
> 安全说明：本文不包含真实 API Key、GitHub Token、refresh token、JWT 生产密钥或其他凭据。示例中的敏感配置统一使用 `<...>` 占位符。

---

## 1. 文档摘要

### 1.1 一句话介绍

AgentForge 是一个面向企业内部私有部署的 AI Agent 智能体平台：通过 Vue 工作台完成模型、Agent、工具、技能和知识库治理，通过 Java 业务层统一承接认证、权限、会话、审计、用量与数据落库，通过 Python LangGraph 引擎执行模型路由、工具调用、技能注入和知识库增强，最终以 SSE/NDJSON 方式向用户提供可审计的流式回答。

### 1.2 项目当前结论

截至本次整理，平台已形成可运行的五端本地闭环：

- MySQL：业务数据与治理数据存储。
- Redis：验证码、限流、刷新令牌、配额预检、缓存和运行时支撑。
- Python Agent Engine：FastAPI + LangGraph 智能编排与工具/技能执行。
- Java Backend：Spring Boot 模块化单体，统一 API、认证、权限、会话、SSE 中继和审计。
- Vue Frontend：Vue 3 + TypeScript + Vite 工作台。

已完成的核心能力包括：

1. 登录、注册、找回密码、JWT 双 Token 与并发刷新队列。
2. 会话创建、编辑、删除、批量删除和一键清空。
3. 多模型配置与请求级模型选择，支持 deterministic、OpenAI、OpenAI-compatible、DeepSeek、Qwen、Ollama。
4. Agent 创建、编辑、发布、启停、删除、会话级复用与运行时资源白名单。
5. LangGraph 显式状态图、工具调用、技能调用、知识库检索和过程事件。
6. MCP 工具注册中心、Python 工具网关、内置计算器、当前时间、单位换算和代码执行沙箱。
7. SKILL.md 技能体系和 `.skillzip` 安全导入。
8. RAG 知识库的文档分块、检索和来源溯源。
9. 安全过程可视化：展示摘要、行动、工具、技能、知识库和生成阶段，但不展示隐藏逐字 Chain-of-Thought。
10. 审计日志、Token 用量、配额预检和可观测看板。
11. 一键启动、健康检查、冒烟测试、全量回归和稳定性巡检。
12. 源码已提交到 GitHub 目标仓库。

### 1.3 验证摘要

已记录并执行的关键验证结果：

| 验证项 | 结果 |
|---|---|
| Python 引擎测试 | 生产加固阶段记录为 `35 passed, 1 warning`；此前完整工具/技能回归达到 `21 passed` 等分组结果 |
| Java Maven 构建 | `BUILD SUCCESS` |
| Vue 类型检查 | `vue-tsc --noEmit` 通过 |
| Vue 生产构建 | `vite build` 通过 |
| 30 分钟稳定性巡检 | 35 轮、353 个检查通过、0 个失败 |
| 工具调用 | calculator、get_current_time、code_exec 通过 |
| 技能调用 | unit_converter、text_polish 等路径通过 |
| 知识库检索 | 命中、分数与溯源返回通过 |
| GitHub 发布 | 本地 `main` 与远程 `origin/main` 已同步 |

---

## 2. 项目背景与建设目标

### 2.1 建设背景

企业在使用大模型时，通常会遇到以下问题：

- 模型供应商多，API 协议、模型名称、鉴权方式和错误码不统一。
- 直接把模型调用写进业务接口，长耗时请求会占满 Web 线程。
- 工具调用、技能能力和知识库能力难以统一治理。
- Agent 的系统提示词、模型、工具权限和知识范围容易失控。
- 流式输出中会泄漏原始工具协议、内部参数或不可读的错误堆栈。
- 多轮会话历史被错误协议污染后，模型可能反复复述旧 JSON。
- 缺少审计和用量数据，无法判断谁调用了什么、消耗了多少 Token、发生了什么异常。
- 技能包如果直接解压，存在路径穿越、危险文件落盘、资源耗尽和跨租户污染风险。

AgentForge 的目标不是训练模型，而是建设一个企业内部可控的模型应用运行平台，把模型、智能体、工具、技能、知识、权限和审计组织成一个闭环。

### 2.2 建设目标

#### 业务目标

- 提供统一的 AI Agent 工作台。
- 允许管理员配置多个模型，并在会话中明确选择实际模型。
- 允许创建可复用的 Agent，并将 Agent 与模型、工具、技能、知识库绑定。
- 让普通用户以对话方式使用平台能力。
- 让管理人员可以查看用量、审计和运行异常。

#### 技术目标

- Java 业务层与 Python 智能层独立进程部署。
- 业务数据由 Java 单一写入口维护。
- Python 引擎无状态化，运行状态通过 checkpoint 或运行时结构管理。
- 流式事件在 Python、Java、Vue 之间保持统一契约。
- 工具和技能均支持权限约束、执行审计和失败降级。
- 所有真实模型失败都应可解释，不能伪装成模型成功回答。

#### 安全目标

- access token 仅保存在前端内存，refresh token 用于会话恢复。
- API Key 脱敏值不能发送给上游模型。
- Agent 只能使用绑定的工具、技能和知识文档。
- 技能只收窄工具权限，不能扩大权限。
- 技能包解压前后均进行安全校验。
- 不向用户展示隐藏逐字思维链、原始工具 JSON、内部系统提示词和异常堆栈。

### 2.3 明确不做的事情

- 不做基础模型训练、微调或模型权重管理。
- 不把平台建设成通用 IDE。
- 不把代码执行沙箱当作任意服务器命令执行器。
- v1 不追求完整的多租户 SaaS 运营体系，但数据结构预留 `tenant_id`。
- 不允许 Python 引擎直接写 MySQL 业务数据。
- 不把“展示思考过程”实现为泄漏隐藏 Chain-of-Thought，而是展示安全摘要和可审计事件。

---

## 3. 功能全景

### 3.1 用户侧能力

- 登录、退出、注册、找回密码。
- 用户名、邮箱或手机号统一作为 `identifier` 登录。
- 工作台查看会话列表和历史消息。
- 选择 Agent 与模型进行对话。
- 实时接收文本增量、工具、技能、知识检索和完成事件。
- 取消或中断长时间生成。
- 查看可读的过程时间线。
- 批量删除或一键清空历史会话。

### 3.2 管理侧能力

- 模型配置：新增、编辑、删除、连接测试、设置默认模型、启停。
- Agent 管理：创建、编辑、发布、停用、删除、运行时查看。
- 工具管理：注册、编辑、启停、删除、MCP Server 管理。
- 技能管理：手动创建、编辑、启停、删除、上传 `.skillzip`。
- 知识库管理：新增、编辑、重索引、检索、查看详情、删除。
- 可观测性：Token 用量、7 日趋势、配额、审计日志。

### 3.3 AI 能力层

- deterministic 离线确定性模型。
- OpenAI-compatible HTTP API。
- DeepSeek、Qwen、Ollama 等兼容 Provider。
- LangGraph 状态机编排。
- 内置 calculator 工具。
- 内置 get_current_time 工具。
- 内置 unit_converter 技能/工具。
- code_exec 代码执行沙箱。
- 知识库检索工具。
- SKILL.md 提示词技能注入。
- 工具和技能的 SSE 事件与 MySQL 双轨审计。

---

## 4. 总体架构

### 4.1 五层架构

```text
┌─────────────────────────────────────────────────────────────┐
│ 接入层：Vue 3 工作台、路由守卫、请求层、SSE 消费、过程可视化       │
├─────────────────────────────────────────────────────────────┤
│ 业务层：Spring Boot 3、认证 RBAC、会话消息、Agent/工具/技能管理    │
├─────────────────────────────────────────────────────────────┤
│ 智能层：Python FastAPI、LangGraph、模型工厂、上下文与路由          │
├─────────────────────────────────────────────────────────────┤
│ 能力层：MCP 工具、内置工具、SKILL.md 技能、代码执行沙箱、RAG      │
├─────────────────────────────────────────────────────────────┤
│ 数据层：MySQL 业务库、Redis 缓存与配额、PostgreSQL/本地向量降级    │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 本地运行拓扑

```text
浏览器
  │ http://127.0.0.1:5173
  ▼
Vue 3 + Vite 前端
  │ /api 代理
  ▼
Java Spring Boot 8090
  ├── MySQL 127.0.0.1:3308
  ├── Redis 127.0.0.1:6379
  └── Python Agent Engine 127.0.0.1:8000
          ├── LangGraph
          ├── OpenAI-compatible 上游模型
          ├── Tool Gateway / MCP
          ├── Skill Engine
          ├── Knowledge Store
          └── Sandbox Executor
```

### 4.3 五端端口

| 服务 | 地址 | 作用 |
|---|---|---|
| MySQL | `127.0.0.1:3308` | 用户、会话、消息、Agent、工具、技能、审计、用量、模型配置 |
| Redis | `127.0.0.1:6379` | Token、验证码、限流、缓存、配额、运行时支撑 |
| Python | `127.0.0.1:8000` | Agent 编排、模型调用、工具/技能/知识库执行 |
| Java | `127.0.0.1:8090` | 对外 API、鉴权、SSE 中继、业务落库 |
| Vue | `127.0.0.1:5173` | 管理工作台与聊天界面 |

> 早期开发阶段 Java 曾使用 8080，后因本机其他项目占用改为 8090；当前启动脚本、Vite 代理、Python 内部调用和验证脚本均以 8090 为准。

### 4.4 为什么 Python 智能层独立

1. **生命周期隔离**：模型调用与工具执行可能持续数秒到数分钟，不能阻塞 Java 业务线程池。
2. **变化速度不同**：Prompt、图编排和工具描述变化频率高于用户表、权限表和业务事务代码。
3. **故障域隔离**：模型超时、Token 超限、沙箱 OOM 和工具异常不应拖垮登录、权限和审计基础服务。
4. **生态匹配**：Python 是 LangGraph、模型 SDK、Embedding、MCP 和数据处理生态的主要承载语言。

### 4.5 层间契约

| 边界 | 协议 | 主要约束 |
|---|---|---|
| Vue ↔ Java | HTTP/JSON、SSE | JWT 鉴权、统一 `R<T>` 响应、traceId |
| Java ↔ Python | HTTP/JSON、NDJSON | Java 传入租户、模型和 Agent runtime；Python 返回事件 |
| Python ↔ 工具 | 进程内、MCP stdio、MCP HTTP | 工具描述、JSON Schema、callId、错误脱敏 |
| Java ↔ MySQL | JDBC/MyBatis-Plus | Java 是业务数据唯一写入口 |
| Java/Python ↔ Redis | Redis 协议 | TTL、原子计数、限流、配额和临时状态 |

---

## 5. 技术选型与工程结构

### 5.1 技术栈

| 领域 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、原生 CSS Token |
| Java 后端 | Java 17、Spring Boot 3.2/3.3 系列、MyBatis-Plus、Spring Security/JWT、Redis |
| AI 引擎 | Python 3.13、FastAPI、LangGraph、LangChain Core、Pydantic、httpx、MCP SDK |
| 数据库 | MySQL 8/9 本地兼容、PostgreSQL + pgvector 设计、Redis 7 |
| 流式协议 | Java SSE、Python NDJSON |
| 构建与验证 | Maven、npm/Vite、pytest、shell E2E、稳定性巡检 |

### 5.2 根目录结构

```text
开发全流程体验/
├── backend/
│   ├── pom.xml
│   ├── af-common/
│   ├── af-auth-api/
│   ├── af-auth-impl/
│   ├── af-session-api/
│   ├── af-session-impl/
│   ├── af-agent/
│   ├── af-bootstrap/
│   ├── sql/
│   └── skill-repo/
├── agent-engine/
│   ├── app/
│   │   ├── graph/
│   │   ├── model/
│   │   ├── tools/
│   │   ├── skills/
│   │   ├── knowledge/
│   │   └── sandbox/
│   └── tests/
├── frontend/
│   └── src/
│       ├── api/
│       ├── components/
│       ├── router/
│       ├── stores/
│       ├── types/
│       ├── utils/
│       └── views/
├── scripts/
│   └── start-all.sh
├── tests/
│   ├── e2e-smoke.sh
│   ├── e2e-full.sh
│   └── soak-30m.sh
├── docs/
├── logs/
└── overview*.md
```

### 5.3 Java 模块依赖

```text
af-bootstrap
  ├── af-auth-impl
  │     └── af-auth-api → af-common
  ├── af-session-impl
  │     ├── af-session-api → af-common
  │     └── af-auth-api
  └── af-agent → af-common
```

设计原则是模块间优先依赖 `api` 包，避免 `impl` 之间直接互相引用，为后续拆分微服务保留边界。

### 5.4 前端工作台路由

| 路由 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/register` | 注册 | 公开 |
| `/forgot-password` | 找回密码 | 公开 |
| `/workspace/chat` | 会话工作台 | 登录 |
| `/workspace/knowledge` | 知识库 | `agent:knowledge:read` |
| `/workspace/agents` | 智能体管理 | `agent:agent:read` |
| `/workspace/tools` | 工具与技能 | `agent:tool:read` |
| `/workspace/models` | 模型管理 | `agent:model:read` |
| `/workspace/obs` | 可观测 | `agent:usage:read` |
| `/403` | 无权访问 | 登录 |

---

## 6. 一次聊天请求的完整链路

### 6.1 请求进入

前端向 Java 发起：

```http
POST /api/chat/stream
Authorization: Bearer <ACCESS_TOKEN>
Content-Type: application/json
X-Trace-Id: <TRACE_ID>
```

请求体核心字段：

```json
{
  "conversationId": "<CONVERSATION_ID>",
  "content": "你好，请介绍一下 AgentForge",
  "modelConfigId": 1,
  "agentId": 1
}
```

### 6.2 Java ChatService 处理顺序

1. 从 `UserContext` 获取用户和租户。
2. 执行 Token 配额预检。
3. 创建会话或校验会话归属。
4. 确定有效 Agent：会话绑定 Agent > 请求级 Agent > 默认助手。
5. 读取最近 20 条消息构造上下文。
6. 清理历史中错误保存的 `tool_calls`/`tool_code` JSON。
7. 读取 Agent runtime。
8. 解析模型优先级：请求级 `modelConfigId` > Agent 绑定模型 > 平台默认模型。
9. 写入 user 消息。
10. 创建 assistant 空壳消息，状态为 streaming。
11. 通过 `HttpAgentEngineClient` 调 Python 引擎。
12. 将 Python NDJSON 事件转成 Java SSE 事件。
13. 增量累积 assistant 文本，并每 500ms 节流落库。
14. 每 15 秒发送 ping 保活。
15. 完成后写模型、Token、耗时、审计和用量。
16. 异常时保留已生成内容，标记失败/中断并发送安全错误。

### 6.3 Python AgentGraph 路由

```text
START
  ↓
agent
  ├── 命中技能 → skill_start → skill_execute → agent
  ├── 命中工具 → tool_start → tools → agent
  └── 无技能/工具 → END
```

`agent` 节点的优先级为：

1. 处理技能执行结果。
2. 处理工具执行结果并生成自然语言总结。
3. 从历史上下文中提取最后一条用户问题。
4. 注入 Agent 系统提示词。
5. 优先匹配技能。
6. 再规划工具。
7. 最后生成普通自然语言回复。

### 6.4 Python NDJSON 到 Java SSE

Python 事件示例：

```json
{"type":"message_start","data":{"model":"<MODEL>"}}
{"type":"tool_call_start","data":{"call_id":"<CALL_ID>","tool_code":"calculator","status":"running"}}
{"type":"tool_call_result","data":{"call_id":"<CALL_ID>","result":{"value":64},"status":"success"}}
{"type":"content_delta","data":{"delta":"计算结果是 64。"}}
{"type":"message_done","data":{"model":"<MODEL>","token_input":10,"token_output":20}}
```

Java 对外通过 SSE 输出：

```text
event: message_start
event: tool_call_start
event: tool_call_result
event: content_delta
event: message_done
```

### 6.5 SSE 事件类型

| 事件 | 含义 |
|---|---|
| `message_start` | assistant 消息已创建，开始生成 |
| `content_delta` | 文本增量 |
| `tool_call_start` | 工具开始调用 |
| `tool_call_result` | 工具成功返回 |
| `tool_call_error` | 工具失败/超时 |
| `skill_call_start` | 技能开始执行 |
| `skill_call_result` | 技能成功返回 |
| `skill_call_error` | 技能失败 |
| `message_done` | 本轮完成并返回模型与 Token 元数据 |
| `error` | 安全错误摘要 |
| `ping` | 连接保活 |

---

## 7. 认证、Token 与权限体系

### 7.1 登录契约

正确的登录字段是 `identifier`，不是 `username`：

```json
{
  "identifier": "admin",
  "password": "<PASSWORD>"
}
```

`identifier` 可以是用户名、邮箱或手机号，后端统一识别。

### 7.2 双 Token 设计

- access token：短生命周期，仅在前端内存中保存。
- refresh token：较长生命周期，保存在 `localStorage`，用于刷新 access token。
- refresh token 轮换：旧 refresh token 使用后立即失效。
- 密码重置后：吊销用户全部 refresh token。

开发配置默认值仅用于本地演示，生产环境应通过环境变量提供：

```text
JWT_SECRET=<JWT_SECRET_AT_LEAST_32_BYTES>
```

### 7.3 401 刷新队列

前端请求层维护：

```ts
let isRefreshing = false
let pendingQueue: Array<(token: string) => void> = []
```

处理规则：

1. 第一个 401 请求触发 `/api/auth/refresh`。
2. 其他并发 401 请求进入队列。
3. 刷新成功后统一使用新 access token 重放。
4. 刷新失败则清理本地 Token 并跳转登录页。
5. 刷新请求使用裸请求，避免刷新接口自身进入 401 递归。

### 7.4 RBAC 权限

后端通过 `@RequirePermission` 和 `PermissionAspect` 做真正拦截；前端通过路由 `meta.perm` 和按钮条件做体验层隐藏。

管理员角色在前端 `hasPerm()` 和后端权限层均可通配放行，但生产环境仍应保留最小权限治理。

典型权限码：

```text
agent:model:read
agent:model:write
agent:agent:read
agent:agent:write
agent:tool:read
agent:tool:write
agent:skill:read
agent:skill:write
agent:knowledge:read
agent:knowledge:write
agent:usage:read
agent:audit:read
```

---

## 8. 多模型接入与真实模型调用

### 8.1 Provider

平台模型工厂支持：

| Provider | 用途 |
|---|---|
| `deterministic` | 离线开发、回归测试、无外网兜底 |
| `openai` | OpenAI 兼容服务 |
| `openai-compatible` | 任意兼容 `/chat/completions` 的服务 |
| `deepseek` | DeepSeek API |
| `qwen` | 通义千问兼容 API |
| `ollama` | 本地 Ollama 服务 |

### 8.2 Base URL 规范化

输入可为：

```text
https://api.example.com
https://api.example.com/
https://api.example.com/v1/
https://api.example.com/v1/chat/completions
```

系统统一解析为兼容端点，并避免重复拼接 `/chat/completions`。

### 8.3 API Key 安全

- 数据库和管理页面只显示脱敏 Key。
- 脱敏值不能作为真实上游请求凭据。
- 更新模型时，如果前端提交的是脱敏占位值，后端应保留原密钥，而不是覆盖为脱敏文本。
- 文档、日志、审计和 SSE 均不输出真实 Key。

### 8.4 代理与网络故障

真实模型调用使用：

```python
httpx.Client(
    timeout=httpx.Timeout(90.0, connect=10.0),
    trust_env=False,
)
```

这样可避免 `HTTP_PROXY`/`HTTPS_PROXY` 环境变量将模型请求导向不可用的本地代理。对于本地 Ollama，可通过 `NO_PROXY` 和 `trust_env=False` 确保直连。

### 8.5 错误分类

| 上游情况 | 对外摘要 |
|---|---|
| 401 | 上游模型认证失败 |
| 402 | 账户余额或额度不足，应检查供应商账户 |
| 403 | 上游模型访问被拒绝 |
| 404 | 上游模型接口或模型不存在 |
| 429 | 上游模型请求过于频繁 |
| 5xx | 上游模型服务暂时不可用 |
| 超时 | 请求上游模型超时 |
| 连接失败 | 无法连接上游模型 |
| 响应格式错误 | 上游模型响应格式无效 |

### 8.6 原始工具协议清理

曾出现上游返回：

```json
{"tool_calls":[{"name":"get_self_introduction","arguments":{}}]}
```

修复策略：

1. 读取标准 `message.tool_calls`。
2. 兼容 content 内嵌 JSON 的 `tool_calls`/`tool_code`。
3. 转换为内部统一工具计划。
4. 仅通过 `tool_call_start`、`tool_call_result` 等事件对外展示。
5. 工具执行后重新生成自然语言总结。
6. 若上游仍输出原始 JSON，触发自然语言重试。
7. Java 和 Python 均清洗历史，禁止把原始协议作为普通 assistant 内容保存。

---

## 9. Agent 智能体系统

### 9.1 Agent 生命周期

```text
草稿 status=1
   ↓ 编辑配置
发布 status=2
   ↓
启用并可被会话调用
   ↓ 停用 status=3
   ↓ 删除
```

### 9.2 Agent 配置内容

一个 Agent 可配置：

- 名称、编码、描述。
- 系统提示词。
- 默认模型。
- 工具白名单。
- 技能白名单。
- 知识库文档白名单。
- 发布状态和版本。

### 9.3 Agent 选择优先级

```text
会话绑定 Agent
  > 请求级 agentId
  > 默认助手
```

会话绑定后，后续多轮消息复用同一 Agent，避免用户每轮重复选择。

### 9.4 模型选择优先级

```text
请求级 modelConfigId
  > Agent 绑定 model_config_id
  > 平台默认模型
```

该优先级解决了“聊天页面选了真实模型但请求又切回 deterministic”的问题。

### 9.5 运行时资源白名单

Python 引擎收到 Agent runtime 后：

- `tool_ids=[]` 表示不开放工具，不表示加载全部工具。
- `skill_ids=[]` 表示不开放技能。
- `knowledge_doc_ids` 限制知识检索范围。
- Java 注入配置，Python 再过滤一次。
- Agent 不能调用未绑定工具或技能。
- `knowledge_search` 会携带限定的 `doc_ids`。

### 9.6 Agent REST 接口

```text
GET    /api/agents
GET    /api/agents/{id}
GET    /api/agents/{id}/runtime
POST   /api/agents
PUT    /api/agents/{id}
PATCH  /api/agents/{id}/status
POST   /api/agents/{id}/publish
DELETE /api/agents/{id}
```

---

## 10. MCP 工具体系

### 10.1 工具注册中心

Java 侧维护工具和 MCP Server 元数据，提供管理接口和内部发现接口。管理端返回安全 DTO，不把 command、args、headers 等敏感执行配置直接暴露给普通前端。

### 10.2 工具描述契约

核心字段包括：

```text
id
code
name
description
inputSchema
outputSchema
executorType
transport
timeoutMs
enabled
```

工具执行结果统一包含：

```text
call_id
tool_code
status
result
error_code
error_message
duration_ms
```

### 10.3 Python Tool Gateway

Tool Gateway 负责：

1. 校验工具是否存在且启用。
2. 校验 JSON Schema 输入。
3. 根据 `executorType` 分发 builtin 或 MCP。
4. 执行超时控制。
5. 将异常转换为稳定业务错误。
6. 返回工具结果并保留 callId。

### 10.4 内置工具

#### calculator

使用 AST 白名单安全解析，不使用 `eval`。支持数字、四则运算和括号，拒绝属性访问、函数调用、导入和危险语法。

#### get_current_time

按 IANA 时区返回当前时间，例如 `Asia/Shanghai`。

#### unit_converter

支持长度、重量、温度、面积等类别的单位换算。技能白名单可以限制其只在单位换算场景使用。

#### code_exec

通过独立进程执行受限 Python 代码，返回：

```text
status
stdout
stderr
duration_ms
exit_code
error_code
```

### 10.5 工具审计

工具开始时写入 `message_tool_call`，完成时更新状态、结果/错误、耗时和结束时间。`call_id` 在 Python、Java、SSE、MySQL 之间贯穿，使用租户和 callId 关联。

---

## 11. Skill 技能系统与 `.skillzip` 导入

### 11.1 技能定位

P9 的 Skill 是受信提示词技能包，不等同于任意代码插件。技能主要负责：

- 触发规则。
- 领域提示词。
- 步骤、模板和约束。
- 对工具权限进行进一步收窄。

任意代码执行属于 P10 沙箱能力。

### 11.2 三态披露

```text
L0：元数据
    code/name/description/triggers/allowed_tools
       ↓ 命中
L1：读取 SKILL.md 全文
       ↓ 执行
L2：注入 system prompt + 工具白名单收窄 + 审计
```

### 11.3 SKILL.md 格式

```markdown
---
name: 单位换算
description: 把自然语言里的单位换算成标准值
version: 1.0.0
allowed_tools:
  - unit_converter
---

当用户请求单位换算时，识别源单位、目标单位和数值，调用允许的工具并返回自然语言结果。
```

文件位置：

```text
skill-repo/tenant-{tenantId}/{skillCode}/SKILL.md
```

### 11.4 技能权限原则

```text
实际可用工具 = Agent 工具白名单 ∩ Skill allowed_tools
```

技能只能收窄工具集合，不能绕过 Agent 白名单。

### 11.5 技能事件

```text
skill_call_start
skill_call_result
skill_call_error
```

技能内部调用工具时，仍然生成独立的 `tool_call_*` 事件，形成技能轨和工具轨的双轨审计。

### 11.6 `.skillzip` 安全校验

上传接口：

```http
POST /api/skills/upload
Content-Type: multipart/form-data
```

校验项目：

- 上传文件不超过 10MB。
- 解压后总大小不超过 32MB。
- ZIP 条目不超过 256 个。
- 拒绝绝对路径。
- 拒绝 `../` 路径穿越。
- 拒绝危险扩展名。
- 必须存在唯一 `SKILL.md`。
- 校验 YAML front matter。
- 校验 `name`、`description`、`version`。
- 校验 `allowed_tools` 格式。
- 使用临时目录解压。
- 通过校验后原子移动到租户目录。
- 数据库事务失败时清理落盘文件并回滚。
- 所有导入动作写审计记录。

### 11.7 技能管理接口

```text
GET    /api/skills
POST   /api/skills
POST   /api/skills/upload
PATCH  /api/skills/{id}
PATCH  /api/skills/{id}/status
DELETE /api/skills/{id}
```

---

## 12. 代码执行沙箱

### 12.1 安全模型

代码执行采用多层防线：

1. AST 预检：只允许安全节点。
2. import 白名单：仅允许纯计算类库，例如 `math`。
3. 调用黑名单：拒绝 `open`、`exec`、`eval`、`compile` 等。
4. 魔术属性拦截：拒绝 `__class__`、`__globals__` 等逃逸路径。
5. 独立进程：避免执行代码污染 Agent 主进程。
6. 进程组隔离：使用 `start_new_session`，超时后强杀进程组。
7. 资源限制：CPU 约 2 秒、内存约 256MB、文件描述符约 32。
8. 输出截断：stdout/stderr 限制约 8KB。
9. 统一结果：不把底层 traceback 直接返回用户。

### 12.2 验证场景

- `sum(1..100)=5050` 成功。
- `import os` 被拒绝。
- `open(...)` 被拒绝。
- 魔术属性访问被拒绝。
- 死循环超时后进程组被清理。
- 代码崩溃会返回稳定错误码。
- `import math` 等安全库可用。

---

## 13. RAG 知识库

### 13.1 数据流

```text
文档导入
  ↓
文本清洗
  ↓
按段落/长度分块
  ↓
向量或关键词索引
  ↓
按 query 检索
  ↓
返回 chunk、score、文档标题和来源
  ↓
Agent 生成回答并可展示知识来源
```

### 13.2 存储设计

MySQL 保存文档元数据，例如：

```text
knowledge_doc
  id
  tenant_id
  title
  source_type
  source_url
  status
  chunk_count
  indexed_at
  deleted_at
```

PostgreSQL 设计包含：

```text
knowledge_base
document
doc_chunk
embed_job
```

本地开发环境提供 Python `data/knowledge/` 降级存储，便于没有 PostgreSQL/pgvector 时完成开发与验证。

### 13.3 Agent 绑定范围

Agent runtime 传入 `knowledge_doc_ids`，Python 侧的知识搜索只能在允许的文档范围内检索，避免 Agent 看到不应访问的资料。

### 13.4 管理接口

```text
GET    /api/knowledge
GET    /api/knowledge/{docId}
PUT    /api/knowledge/{docId}
POST   /api/knowledge/{docId}/reindex
POST   /api/knowledge/search
DELETE /api/knowledge/{docId}
```

---

## 14. 数据库与 Redis 设计

### 14.1 MySQL 表域

当前 MySQL 初始化脚本包含用户、权限、会话、Agent、工具、技能、审计、用量、知识和模型等表，代表性表包括：

```text
sys_user
sys_role
sys_permission
sys_role_permission
sys_user_role
conversation
message
message_tool_call
message_skill_call
agent
agent_version
tool
mcp_server
skill
audit_log
api_usage
api_quota
knowledge_doc
model_config
```

### 14.2 删除策略

- 用户、会话、Agent：软删。
- 消息关联、工具调用和技能调用：按业务关系清理。
- 审计日志：原则上不随业务删除，按分区和归档治理。

### 14.3 Redis Key 规范

统一格式：

```text
af:{env}:{domain}:{business-key}
```

典型域：

```text
captcha
rl
lock
agent
cache
quota
jwt
```

所有 Key 必须设置 TTL；禁止使用 `KEYS`，使用 `SCAN`。

### 14.4 Redis 使用场景

- 验证码：`GETDEL` 单次消费。
- 登录/接口限流：`INCR + EXPIRE`，必要时 Lua 保证原子性。
- Agent 任务锁：`SET NX PX`，释放时比较持有者标识。
- checkpoint：按 thread/node 保存运行快照。
- Token 配额：`INCRBY` 实时统计。
- refresh token：按用户和 jti 存储并轮换。
- 缓存：Cache-Aside，写库后删除缓存。

---

## 15. 前端工作台实现

### 15.1 设计系统

前端使用 CSS Design Token，不引入 Element Plus 等现成 UI 库。核心视觉方向为冷静工程感：中性灰、细边框、留白、双主题、线性 SVG 图标。

浅色/深色主题通过：

```html
<html data-theme="light">
```

和 CSS 变量切换，组件不感知具体主题。

### 15.2 ChatView

ChatView 负责：

- 会话列表。
- 会话新建、删除、批量删除、一键清空。
- Agent 与模型选择。
- 消息历史恢复。
- SSE 文本增量消费。
- Markdown 轻渲染。
- 工具和技能时间线。
- 安全过程摘要。
- 停止按钮。

### 15.3 过程可视化

前端使用安全的 `ThinkingStep` 结构：

```ts
interface ThinkingStep {
  label: string
  detail?: string
  phase?: 'analysis' | 'action' | 'knowledge' | 'generation' | 'complete'
  status: 'running' | 'done' | 'error'
}
```

展示内容可以包括：

- 正在分析请求。
- 正在选择工具或技能。
- 正在检索知识库。
- 工具执行成功/失败。
- 技能执行成功/失败。
- 正在生成回答。
- 回答已完成。

不展示：

- 模型隐藏逐字思维链。
- 原始 system prompt。
- 完整工具内部参数。
- 内部规划 JSON。
- Python 堆栈和环境变量。

### 15.4 滚动布局修复

工作台外层固定 `100dvh` 并 `overflow: hidden`；聊天消息区独立 `overflow-y: auto`；所有关键 Flex 子项增加 `min-height: 0`。这样聊天区域向下滚动时，左侧导航不会跟随页面一起滚动。

### 15.5 工具与技能页面修复

曾经出现页面乱码、空白和入口进入聊天页等问题，最终修复包括：

- 增加 `/workspace/tools` 子路由。
- 权限从错误的 `agent:skill:read` 修正为 `agent:tool:read`。
- 工具和技能接口使用 `Promise.allSettled` 独立加载。
- 增加加载中、错误、空数据和重试状态。
- 补齐 `AfIcon` 的 `upload` 图标。
- 未知图标使用安全空对象兜底。
- 修正 Vue CSS 未闭合括号导致的构建失败。

真实浏览器验收时，页面可以看到 4 个工具和 3 个技能，并能打开编辑弹窗。

---

## 16. 可观测性、审计与用量

### 16.1 traceId

traceId 从前端请求进入，经过 Java、Python、工具/技能执行，最终进入日志、SSE 和审计记录，用于跨语言串联一次请求。

### 16.2 审计范围

- 登录成功/失败。
- 模型连接测试和模型调用。
- Agent 创建、编辑、发布、停用、删除。
- 工具注册、启停、删除和调用。
- 技能创建、上传、启停、删除和调用。
- 知识库创建、编辑、重索引、删除和检索。
- 聊天完成、失败和中断。
- 配额拒绝和上游模型异常。

### 16.3 用量记录

`api_usage` 记录：

```text
tenant_id
user_id
conversation_id
model
input_tokens
output_tokens
latency_ms
created_at
```

Redis 用于实时预检和展示，MySQL 用于记账和后续对账。

### 16.4 可观测接口

```text
GET /api/usage/stats
GET /api/audit/logs
```

前端 ObservabilityView 展示今日用量、配额、7 日趋势和审计日志。

---

## 17. 关键问题、根因与修复记录

### 17.1 登录反复提示“登录已过期”

**根因**：页面刷新后 access token 只存在内存；refresh token 生命周期和并发刷新处理不完整。

**修复**：refresh token 持久化、access token 内存化、统一 refresh 接口、401 刷新队列、失败清理和登录跳转。

### 17.2 登录接口字段错误

**错误请求**：`username`。

**正确请求**：`identifier`。

**修复**：前端 LoginView、E2E 脚本和文档统一使用 `identifier`。

### 17.3 Java 端口冲突

**根因**：8080 被本机其他项目占用。

**修复**：Java 切换到 8090，并同步修改前端代理、Python 调用、启动脚本和测试脚本。

### 17.4 旧 JAR 未加载

**根因**：代码已修改，但 Java 进程仍运行旧 JAR。

**修复**：统一使用 `scripts/start-all.sh --restart-java`，启动后访问 `/health` 并重新回归。

### 17.5 根路径/Actuator 500

**根因**：根路径和 Actuator 入口不是稳定的项目健康契约。

**修复**：以专用 `/health` 为有效检查入口，返回：

```json
{
  "code": 0,
  "data": { "status": "UP" }
}
```

### 17.6 DeepSeek 401/402

**401 根因**：Key 错误、脱敏 Key 被发送、Provider/base URL/model 配置不一致。

**402 根因**：供应商账户余额或额度不足。

**修复**：禁止脱敏 Key 上送；分类提示认证失败、余额不足、权限拒绝和请求过频；增加连接测试和安全降级。

### 17.7 系统代理导致模型连接拒绝

**根因**：Python/http 客户端读取了不可用的 `HTTP_PROXY`/`HTTPS_PROXY`。

**修复**：真实模型客户端使用 `trust_env=False`，本地服务配置 `NO_PROXY`，Git 操作单独使用可用网络路径。

### 17.8 聊天选择模型后自动切回 deterministic

**根因**：请求级模型 ID 没有贯通；Agent 绑定模型优先级未生效。

**修复**：Java 读取 `modelConfigId`，无请求级值时读取 Agent runtime，再回退平台默认；Python 按请求级配置创建模型实例。

### 17.9 普通问候返回 tool_calls JSON

**根因**：上游返回结构化工具协议，Java/Python 将其当成 assistant 文本展示并落库。

**修复**：解析标准与非标准工具协议、转内部事件、自然语言重试、历史清洗、禁止原始协议作为普通 assistant 内容。

### 17.10 偶发不回答问题

**根因**：旧工具 JSON 污染历史；技能节点收到整段会话；当前问题没有从历史中提取。

**修复**：Java `sanitizeHistoryContent`、Python `_latest_user_prompt()`、过滤旧工具协议、自然语言兜底和多轮回归。

### 17.11 Python 缩进错误导致引擎无法启动

**根因**：修改 `async for graph_event` 流式循环时造成缩进错误。

**修复**：重新整理 `main.py` 事件循环，执行 Python 测试后重启引擎。

### 17.12 工具与技能入口进入聊天页

**根因**：路由缺失或导航目标不一致。

**修复**：增加 `/workspace/tools`，指向 `ToolsView.vue`，校准权限。

### 17.13 工具与技能页面 CSS 构建失败

**根因**：`var(--color-text-tertiary` 少了右括号。

**修复**：补齐括号，并重新执行类型检查与生产构建。

### 17.14 工具与技能页面空白

**根因**：接口实际返回正常，但 `AfIcon` 缺少 `upload` 图标，模板运行期渲染异常；同时存在旧 HMR 缓存干扰。

**修复**：增加 upload 图标、未知图标兜底、独立接口加载状态和强制刷新建议。

### 17.15 聊天滚动带动左侧导航

**根因**：外层高度不固定、Flex 子项缺少 `min-height: 0`、滚动容器边界不清晰。

**修复**：固定 100dvh、外层 hidden、消息区独立滚动和子项最小高度修复。

### 17.16 GitHub 首次推送非 fast-forward

**根因**：远程仓库已有初始 README 提交，本地仓库为独立历史。

**修复**：`git fetch origin main`、`git rebase origin/main`、`git push -u origin main`。

---

## 18. 测试与验收

### 18.1 Python 单元测试

测试覆盖：

- 引擎健康和流式契约。
- calculator 安全表达式。
- 除零错误脱敏。
- current_time 工具。
- code_exec 成功、危险 import、文件访问、魔术属性、超时和进程组清理。
- unit_converter 重量、温度、跨类别和未知单位。
- 技能 keyword/regex 匹配。
- 技能工具白名单只收窄不放大。
- text_polish 纯提示词技能。
- 知识库分块、检索、删除和相似度。
- Provider/base URL 校验。
- 401、403、404、429、5xx、超时和网络错误摘要。
- deterministic 模型对当前问题的回答。

### 18.2 前端验证

```bash
npm run type-check
npm run build
```

验收标准：

- `vue-tsc --noEmit` 无类型错误。
- `vite build` 成功。
- 路由懒加载正常。
- ChatView 可消费 SSE。
- ToolsView 不因一侧接口失败而完全空白。
- 左侧导航不随消息区域滚动。

### 18.3 Java 验证

```bash
mvn -DskipTests package
```

验收标准：

- 多模块依赖正确。
- fat-jar 生成。
- `/health` 返回 UP。
- 认证、模型、会话、Agent、工具、技能、知识库、用量和审计接口可访问。

### 18.4 冒烟测试

`tests/e2e-smoke.sh` 覆盖：

1. 登录。
2. 默认模型检查。
3. 会话创建和删除。
4. deterministic SSE。
5. 普通问候和平台介绍。
6. 真实模型失败降级。
7. 原始工具协议不泄漏。

### 18.5 全量回归

`tests/e2e-full.sh` 覆盖：

- 双 Token 登录和刷新。
- `/auth/me`。
- 模型列表和连接测试。
- 会话创建、删除和列表消失。
- deterministic 聊天。
- 真实模型失败降级。
- 工具列表。
- 技能列表。
- 知识库列表。

### 18.6 30 分钟稳定性巡检

`tests/soak-30m.sh` 周期执行：

- Java health。
- Python engine health。
- 登录。
- 模型列表。
- 会话创建。
- 普通问候。
- 平台介绍。
- 数学问题。
- calculator。
- 会话删除。
- 每十轮 Agent 列表。
- 原始工具协议泄漏检查。

历史执行结果为 35 轮、353 个检查通过、0 个失败。

---

## 19. 本地启动与运维

### 19.1 一键启动

```bash
bash scripts/start-all.sh
```

如需重启 Java：

```bash
bash scripts/start-all.sh --restart-java
```

脚本具备：

- 端口探测。
- MySQL/Redis/Python/Java/Vite 幂等启动。
- HTTP/TCP readiness 检查。
- 日志统一写入 `logs/`。
- 已运行服务自动跳过。
- 失败时返回非零退出码。

### 19.2 本地访问

```text
前端：http://127.0.0.1:5173
后端健康：http://127.0.0.1:8090/health
Python 健康：http://127.0.0.1:8000/health
```

本地演示账号：

```text
账号：admin
密码：<LOCAL_DEMO_PASSWORD>
```

如需使用实际演示密码，应仅从本地安全配置读取，不要把密码提交到仓库或写入公开文档。

### 19.3 关键环境变量

```text
MYSQL_URL=<MYSQL_JDBC_URL>
MYSQL_USER=<MYSQL_USER>
MYSQL_PASSWORD=<MYSQL_PASSWORD>
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
JWT_SECRET=<JWT_SECRET>
AGENT_ENGINE_PROVIDER=http
AGENT_ENGINE_URL=http://127.0.0.1:8000
SKILL_REPO_DIR=<SKILL_REPO_DIR>
KNOWLEDGE_ENGINE_URL=http://127.0.0.1:8000
```

真实模型配置不要写入 Git：

```text
MODEL_PROVIDER=<deepseek|openai|qwen|ollama>
MODEL_NAME=<MODEL_NAME>
MODEL_BASE_URL=<MODEL_BASE_URL>
MODEL_API_KEY=<MODEL_API_KEY>
```

### 19.4 日志

```text
logs/mysql.log
logs/redis.log
logs/agent-engine.log
logs/java.log
logs/frontend.log
logs/soak-*.log
```

生产环境应进一步使用结构化日志、集中采集、敏感字段脱敏和日志保留策略。

---

## 20. GitHub 发布与仓库安全

### 20.1 发布结果

- 远程仓库：`https://github.com/javk-yang/aipingtai`
- 远程分支：`main`
- 本地工作区：已同步、干净。
- 远程已有 README 初始提交，已通过 rebase 合并。
- 根目录 `.gitignore` 已忽略数据库目录、Redis 数据、日志、构建产物、环境文件、Node/Python 缓存和 `.workbuddy` 项目数据。

### 20.2 发布过程中解决的问题

- 初始配置使用不可用代理。
- 远程仓库已有独立初始历史。
- 推送需要认证。
- 通过 fetch/rebase 合并远程初始提交后完成推送。

### 20.3 强制安全动作

历史过程中曾有 GitHub Personal Access Token 被直接发送到对话中。该 Token 已属于暴露凭据，必须：

1. 立即在 GitHub 中撤销。
2. 重新创建最小权限 Token。
3. 后续不要把 Token 粘贴到聊天、文档、日志或代码中。
4. 检查 GitHub Actions、shell 历史和本地 credential helper 是否残留。
5. 如果该 Token 曾具备仓库写权限，应检查仓库审计记录。

本文没有写入该 Token，也不保留其内容。

---

## 21. 当前已知限制与生产化差距

### 21.1 真实上游成功回归仍依赖可达环境

当前环境对部分公网 OpenAI 端点访问超时，因此已重点验证：

- Provider/base URL 校验。
- 代理绕过。
- 401/402/403/404/429/5xx 分类。
- 真实模型失败时不发送虚假的 `message_done`。
- deterministic 离线链路完整可用。

要完成真实上游成功回归，需要在受控环境提供可达的 DeepSeek、OpenAI-compatible、Qwen 或 Ollama 服务，并注入合法 Key。

### 21.2 readiness 仍可继续加强

当前启动脚本已经检查端口和健康接口，但生产环境建议继续补充：

- MySQL SQL 查询就绪。
- Redis PING 就绪。
- Python 模型工厂配置就绪。
- Java 到 Python 的依赖检查。
- 启动失败原因结构化输出。
- 服务启动顺序和重试退避。

### 21.3 租户上下文统一

部分知识库、Usage、Audit、内部工具/技能接口仍存在历史上通过 `X-Tenant-Id` 或默认租户 1 获取租户的路径，后续应统一到 JWT `UserContext`，防止请求头篡改租户边界。

### 21.4 真实 MCP Server 生产兼容性

内置工具链路已验证；MCP SDK 适配代码已具备 stdio 和 Streamable HTTP 路径，但仍需要连接真实第三方 MCP Server 完成：

- 认证配置。
- 连接生命周期。
- 工具动态发现。
- 超时和断线重连。
- 第三方 schema 差异。
- 旧式 SSE transport 兼容性。

### 21.5 生产级模型弹性

后续应补充：

- 多供应商自动故障转移。
- 重试与指数退避。
- 熔断器。
- 单用户/租户并发限制。
- 上游模型健康评分。
- Token 成本按模型计价。
- 请求取消真正传递到 Python 和上游。

### 21.6 RAG 生产化

当前已有分块、检索和溯源闭环，后续可增强：

- 文档上传格式扩展。
- OCR 和扫描件处理。
- Embedding 异步任务队列。
- pgvector HNSW 生产参数调优。
- 混合检索与 rerank。
- 文档版本与权限继承。
- 删除后的向量清理。

---

## 22. 后续路线图

### P15：生产可用性加固

- 统一 readiness。
- 完善租户上下文。
- 增加模型重试、熔断和降级。
- 完善取消生成。
- 增加安全扫描和依赖漏洞检查。

### P16：真实 MCP 生态接入

- MCP Server 注册测试。
- 凭据安全存储。
- stdio/HTTP 生命周期管理。
- 工具版本和灰度发布。

### P17：知识库生产化

- 多格式文件导入。
- 异步解析与索引任务。
- pgvector 与混合检索。
- 权限继承、版本管理和增量重索引。

### P18：运营与计费

- 按模型和租户成本计算。
- 预算预警。
- 用量报表导出。
- 运营审计和管理员告警。

### P19：微服务演进

只有在团队、并发或发布节奏确实需要时，再将认证、会话、Agent 能力、知识库和运营拆分为独立服务。当前模块化单体已经通过 API 边界为拆分预留空间，不建议在早期无条件引入服务注册、网关和分布式事务。

---

## 23. 交付验收清单

### 功能验收

- [x] 登录和 Token 刷新。
- [x] 会话 CRUD、批量删除、一键清空。
- [x] deterministic 模型自然语言回答。
- [x] 请求级真实模型选择。
- [x] Agent 创建、发布、启停、删除。
- [x] Agent 模型/工具/技能/知识绑定。
- [x] 工具注册和调用。
- [x] 技能注册和 `.skillzip` 上传。
- [x] 代码执行沙箱。
- [x] 知识库检索和溯源。
- [x] 用量和审计。
- [x] 工具与技能工作台页面。
- [x] 聊天安全过程可视化。

### 安全验收

- [x] API Key 脱敏。
- [x] 原始 tool_calls 清理。
- [x] 历史上下文清洗。
- [x] Agent 运行时白名单。
- [x] 技能 allowed_tools 收窄。
- [x] skillzip 路径穿越拦截。
- [x] 危险扩展名拦截。
- [x] ZIP 大小和条目数限制。
- [x] 沙箱 AST 预检和进程隔离。
- [x] 审计和 traceId。

### 工程验收

- [x] Java 构建通过。
- [x] Python 测试通过。
- [x] 前端类型检查通过。
- [x] 前端生产构建通过。
- [x] 一键启动脚本可用。
- [x] E2E 冒烟测试。
- [x] E2E 全量回归。
- [x] 30 分钟稳定性巡检。
- [x] GitHub 源码发布完成。

---

## 24. 结语

AgentForge 已从一个初始的登录、会话和 SSE 原型，逐步发展为覆盖模型、Agent、工具、技能、知识库、沙箱、审计、用量和部署的企业级 AI Agent 平台骨架。

本次建设过程中最重要的工程成果并不是某一个页面或某一个 API，而是形成了几条可持续演进的边界：

1. Java 负责业务数据和治理，Python 负责智能计算。
2. 模型、Agent、工具、技能和知识库通过显式契约连接。
3. 运行时权限使用白名单交集，而不是隐式放权。
4. 流式输出既面向用户体验，也面向审计和故障排查。
5. 模型失败、工具失败、技能失败和历史污染都有明确的安全处理方式。
6. 代码、测试、启动脚本和发布记录共同组成可交付系统。

后续工作重点应从“功能闭环”转向“生产确定性”：统一租户上下文、加强 readiness、完成真实 MCP 和上游模型验收、完善 RAG 与成本运营，并在实际组织规模和并发需求出现后再进行微服务拆分。

---

## 附录 A：核心文件索引

| 文件 | 作用 |
|---|---|
| `backend/af-session-impl/.../ChatService.java` | 会话、上下文、模型选择、SSE 中继、审计和落库 |
| `agent-engine/app/graph/agent_graph.py` | LangGraph 状态图、技能/工具路由、Agent runtime |
| `agent-engine/app/model/openai_compatible.py` | 真实模型适配、URL 规范化、错误分类、tool_calls 清理 |
| `agent-engine/app/main.py` | Python 健康、聊天 NDJSON、知识库接口 |
| `backend/af-agent/.../SkillPackageService.java` | skillzip 解压、校验、事务、租户落盘 |
| `backend/af-agent/.../AgentController.java` | Agent CRUD、发布、状态和 runtime |
| `backend/af-agent/.../ToolController.java` | 工具与 MCP Server 治理 |
| `backend/af-agent/.../SkillController.java` | 技能 CRUD 和上传 |
| `frontend/src/views/workspace/chat/ChatView.vue` | 会话和聊天工作台 |
| `frontend/src/views/workspace/agents/AgentManageView.vue` | Agent 管理页面 |
| `frontend/src/views/workspace/tools/ToolsView.vue` | 工具与技能页面 |
| `frontend/src/views/workspace/models/ModelManageView.vue` | 模型配置页面 |
| `frontend/src/views/workspace/knowledge/KnowledgeView.vue` | 知识库页面 |
| `frontend/src/views/workspace/obs/ObservabilityView.vue` | 可观测页面 |
| `frontend/src/utils/request.ts` | Token、刷新队列、错误处理、traceId |
| `backend/sql/01-mysql-schema.sql` | MySQL 业务表和种子 |
| `backend/sql/02-postgres-vector.sql` | PostgreSQL/pgvector 设计 |
| `docs/03-Redis设计.md` | Redis Key 和缓存策略 |
| `scripts/start-all.sh` | 五端幂等启动 |
| `tests/e2e-smoke.sh` | 冒烟测试 |
| `tests/e2e-full.sh` | 全量回归 |
| `tests/soak-30m.sh` | 稳定性巡检 |

## 附录 B：敏感信息替换规范

以下内容禁止写入公开文档、Git 仓库或普通日志：

```text
<DEEPSEEK_API_KEY>
<OPENAI_API_KEY>
<GITHUB_TOKEN>
<JWT_SECRET>
<REFRESH_TOKEN>
<MYSQL_PASSWORD>
<SMTP_PASSWORD>
<MCP_CREDENTIAL>
```

如果需要提供可运行配置，应通过本地 `.env`、系统密钥管理器或部署平台 Secret 注入，并确保 `.env` 已被 `.gitignore` 忽略。
