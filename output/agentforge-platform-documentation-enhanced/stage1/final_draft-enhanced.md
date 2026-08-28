# AgentForge 企业级 AI Agent 平台增强详版

## 项目全貌、架构设计、实现细节、接口契约、运行机制、测试矩阵与生产化指南

> 文档版本：v2.0-enhanced
>
> 基线文档：`output/agentforge-platform-documentation/stage1/final_draft.md`
>
> 增强策略：保留 v1.0 全部内容，并追加面向架构评审、代码维护、联调验收、故障排查和生产化改造的实施级细节。本文不是对原文的删改版，而是“原文 + 深度技术附录”的增强版。
>
> 安全说明：本文不包含真实 API Key、GitHub Token、refresh token、JWT 生产密钥、数据库密码或其他凭据。所有敏感信息均以 `<...>` 占位符表示。

---

## 增强版阅读说明

本增强版针对以下读者设计：

- **项目负责人**：重点阅读目标边界、能力完成度、验收清单、已知限制和路线图。
- **系统架构师**：重点阅读运行拓扑、服务边界、状态机、数据一致性和扩展路线。
- **Java 后端工程师**：重点阅读 ChatService、认证、Agent runtime、SSE 中继、审计和数据库契约。
- **Python/AI 工程师**：重点阅读 AgentGraph、模型适配器、工具协议、Skill 注入、RAG 和沙箱。
- **前端工程师**：重点阅读请求层、路由权限、ChatView、过程时间线、工具技能治理页和滚动布局。
- **测试与运维人员**：重点阅读测试矩阵、启动顺序、健康检查、错误分类、日志定位和恢复步骤。

增强版采用“先结论、后机制、再验证”的写法。每项重要能力尽量同时给出：

1. **目标**：为什么需要这项能力；
2. **实现**：在哪个模块、以什么契约实现；
3. **边界**：什么情况不应该由该模块负责；
4. **验证**：如何证明能力真实生效；
5. **风险**：当前还存在什么生产化差距。

---

## 基线文档：项目全貌、架构设计、实现细节、测试验收与问题修复总结


---

## 基线 1. 文档摘要

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

## 基线 2. 项目背景与建设目标

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


## 增强技术附录

## 25. 平台需求追踪与完成度模型

### 25.1 从用户问题到技术能力的映射

AgentForge 的建设不是一次性写完所有模块，而是从真实故障倒推平台能力。下面给出用户反馈、根因、技术修复和验收证据之间的对应关系。

| 用户侧现象 | 直接根因 | 解决模块 | 关键修复 | 验收证据 |
|---|---|---|---|---|
| 登录后反复提示登录已过期 | access token 只在内存、refresh 生命周期不完整、并发 401 重复刷新 | 前端请求层、认证后端 | refresh token 持久化、刷新队列、失败清理 | 双 Token 测试、受保护接口回归 |
| 对话框不能使用 | 会话页、SSE、后端路由或服务状态不一致 | ChatView、Java ChatService、Python Engine | 打通 `/api/chat/stream` 到 `/v1/chat/stream` | deterministic SSE、全链路聊天测试 |
| 删除会话无效 | 前端交互和后端删除契约不一致 | 会话 Controller/Service、ChatView | 单删、批删、一键清空、删除后列表重载 | e2e 会话 CRUD |
| 选择模型后切回 deterministic | modelConfigId 未贯通或 Agent runtime 未注入 | ChatService、AgentGraph、模型工厂 | 请求级 > Agent 绑定 > 平台默认 | 模型选择回归、错误降级回归 |
| DeepSeek 401/402/连接拒绝 | Key、余额、代理和错误分类混淆 | openai_compatible.py | trust_env=false、脱敏 Key 拒绝、状态码分类 | 401/402/网络错误测试 |
| 普通问候显示 tool_calls JSON | 结构化工具协议未转换为内部事件 | 模型适配器、AgentGraph、Java 历史清洗 | tool_calls/tool_code 解析、工具事件化、自然语言总结 | 原始协议泄漏检查 |
| 工具与技能入口进入会话 | 工作台导航和实际路由不一致 | router、WorkspaceView、ToolsView | 增加 `/workspace/tools` 子路由和权限元数据 | 浏览器 DOM/交互验收 |
| 工具与技能页面空白 | 图标缺失、单接口失败导致整体不渲染 | AfIcon、ToolsView | upload 图标、未知图标兜底、Promise.allSettled | 工具 4 条、技能 3 条显示 |
| 聊天滚动带动导航 | 外层高度和 flex 滚动边界不明确 | WorkspaceView、ChatView CSS | `100dvh`、`overflow:hidden`、`min-height:0` | 浏览器滚动验收 |

### 25.2 完成度不能只看“页面可见”

平台能力采用五层完成度判断：

1. **数据层完成**：表结构、字段、租户隔离和初始化数据存在；
2. **服务层完成**：Java/Python 有可调用接口和明确错误处理；
3. **交互层完成**：Vue 页面能够加载、操作和反馈状态；
4. **链路层完成**：从浏览器到上游模型或内置工具全链路闭环；
5. **验证层完成**：有脚本化测试或可重复的验收步骤。

例如，“工具与技能页面显示出来”只能证明交互层部分完成；只有工具列表、技能列表、上传、启停、执行事件、审计和异常重试均可验证，才可以称为工具技能能力闭环。

### 25.3 系统级不变量

平台实现中应长期保持以下不变量：

- **租户不变量**：任何业务查询、写入、文件路径和审计记录都必须带租户边界；
- **身份不变量**：access token 只承担短期访问，refresh token 只承担轮换刷新；
- **模型不变量**：请求级模型明确指定时不能被默认模型覆盖；
- **资源不变量**：Agent 的空工具列表表示不开放工具，而不是加载所有工具；
- **事件不变量**：工具、技能和生成事件必须有可关联的 traceId/callId；
- **历史不变量**：原始工具协议不能作为普通 assistant 文本进入下一轮上下文；
- **安全不变量**：用户提供的代码、Skill 包、模型返回参数都不能直接获得宿主机无限权限；
- **降级不变量**：真实模型失败时可以返回明确的安全错误或确定性模型结果，但不能伪造真实模型成功；
- **可审计不变量**：关键动作必须能回答“谁、何时、对哪个租户、调用了什么、结果如何”。

## 26. 五端运行拓扑与生命周期

### 26.1 五端职责矩阵

| 组件 | 监听地址 | 主要职责 | 是否持久化业务数据 | 失败影响 |
|---|---|---|---|---|
| MySQL | `127.0.0.1:3308` | 用户、会话、消息、Agent、工具、技能、审计、用量 | 是 | 平台业务不可用 |
| Redis | `127.0.0.1:6379` | 验证码、限流、锁、刷新令牌、配额、缓存、checkpoint | 部分 | 认证/限流/运行时能力受影响 |
| Python Engine | `127.0.0.1:8000` | LangGraph、模型、工具、Skill、RAG、NDJSON | 否，主要执行 | 聊天智能能力不可用 |
| Java Backend | `127.0.0.1:8090` | API、认证、权限、会话、SSE 中继、落库、审计 | 是 | 前端主入口不可用 |
| Vue Frontend | `127.0.0.1:5173` | 登录、工作台、管理页面、SSE 消费和渲染 | 否 | 用户无法操作平台 |

### 26.2 推荐启动顺序

启动脚本虽然支持幂等拉起，但运维上仍应理解依赖顺序：

```text
MySQL / Redis
    ↓
Python Agent Engine
    ↓
Java Backend
    ↓
Vue Frontend
```

原因如下：

- Java 启动时需要访问 MySQL、Redis，并需要知道 Python Engine 的地址；
- Python Engine 可以先启动，因为它主要在请求到来时使用模型和工具；
- Vue 只需要代理到 Java，不应直接绕过 Java 访问 Python；
- 健康检查必须区别“进程存在”和“依赖已就绪”。

### 26.3 进程生命周期状态

每个服务都可抽象成四种状态：

```text
STOPPED → STARTING → READY → DEGRADED
              │          │
              └──────────┴→ FAILED
```

- `STOPPED`：没有进程或进程已退出；
- `STARTING`：进程存在但端口/依赖尚未可用；
- `READY`：端口、健康接口和关键依赖检查通过；
- `DEGRADED`：服务可响应，但某个可选依赖不可用，例如真实模型不可达；
- `FAILED`：服务无法启动、端口冲突、配置错误或依赖完全不可用。

当前项目的有效 Java 健康检查是 `GET /health`，不应把根路径或不稳定的 Actuator 路径作为唯一启动判据。

## 27. 请求级聊天链路：逐步骤时序

### 27.1 浏览器发起请求

ChatView 在用户提交消息后，需要同时确定以下上下文：

- `conversationId`：当前会话；
- `content`：本轮用户输入；
- `agentId`：会话绑定或请求级 Agent；
- `modelConfigId`：用户在模型下拉框中选择的配置；
- `traceId`：用于跨前端、Java、Python 和审计串联；
- access token：从前端内存注入请求头；
- refresh token：不应作为普通业务请求头发送。

请求应经过统一 request 层，而不是在 ChatView 中手工拼接 Token。这样 401、错误翻译、traceId 和刷新队列可以保持一致。

### 27.2 Java ChatService 的处理顺序

建议将 ChatService 的逻辑理解为以下固定序列：

1. 校验当前用户、租户和会话归属；
2. 创建或确认会话；
3. 读取最近消息，当前实现限制为最近 20 条；
4. 对 assistant 历史执行 `sanitizeHistoryContent()`；
5. 解析会话绑定 Agent 和请求级 Agent；
6. 组装 Agent runtime：模型、工具、技能、知识库文档白名单；
7. 计算模型选择优先级；
8. 将统一请求发送到 Python Engine；
9. 逐行读取 Python NDJSON；
10. 映射为浏览器可消费的 SSE；
11. 根据事件更新消息、工具调用、技能调用和审计表；
12. 统计用量，记录成功、失败和耗时；
13. 发送 `message_done` 或安全 `error` 事件。

### 27.3 模型选择优先级的严格定义

```text
请求级 modelConfigId
    ↓ 请求没有指定时
Agent runtime.model_config_id
    ↓ Agent 没有绑定时
平台默认模型
```

伪代码：

```java
Long requestedModelId = req.getModelConfigId();

if (requestedModelId == null && agentRuntime != null) {
    Object configured = agentRuntime.get("model_config_id");
    if (configured instanceof Number n) {
        requestedModelId = n.longValue();
    }
}

Long effectiveModelId = requestedModelId != null
    ? requestedModelId
    : modelConfigService.defaultConfigId(tenantId);
```

必须避免以下错误实现：

- 先读取默认模型，再无条件覆盖请求模型；
- 只在前端切换下拉框，不把 ID 传到 Java；
- Java 传了 ID，但 Python 只读取 provider 默认配置；
- Agent runtime 的字段名和 Python 请求模型字段名不一致；
- 模型连接失败后静默把选择值改回 deterministic，导致用户误以为真实模型成功。

### 27.4 Python AgentGraph 的状态转移

核心图结构：

```text
START
  ↓
agent
  ├─ 命中 Skill → skill_start → skill_execute → agent
  ├─ 命中 Tool  → tool_start  → tools         → agent
  └─ 普通回答   → END
```

其中 `agent` 节点负责判断下一步，不应承担所有副作用；工具执行、技能执行和结果归一化应保持可测试的独立边界。

推荐的状态字段包括：

```python
state = {
    "messages": [...],
    "latest_user_prompt": "...",
    "agent_config": {...},
    "model_config": {...},
    "tool_descriptors": [...],
    "skill_descriptors": [...],
    "knowledge_doc_ids": [...],
    "trace_id": "...",
    "call_id": "...",
    "events": [...],
    "final_answer": "...",
}
```

### 27.5 NDJSON 到 SSE 的事件映射

| Python 事件 | Java 行为 | 前端表现 | 是否落库 |
|---|---|---|---|
| `message_start` | 初始化消息上下文 | 开始生成卡片 | 可选 |
| `content_delta` | 转发文本增量 | 追加 Markdown | 最终合并落库 |
| `tool_call_start` | 建立工具调用记录 | 显示工具运行中 | 是 |
| `tool_call_result` | 更新工具结果 | 显示成功结果摘要 | 是，需脱敏 |
| `tool_call_error` | 记录失败和错误码 | 显示工具失败 | 是 |
| `skill_call_start` | 建立技能记录 | 显示技能命中 | 是 |
| `skill_call_result` | 更新技能执行结果 | 显示技能结果摘要 | 是 |
| `skill_call_error` | 记录技能失败 | 显示失败状态 | 是 |
| `message_done` | 完成消息和用量记账 | 结束时间线 | 是 |
| `error` | 中断或结束错误流 | 显示可理解错误 | 是 |
| `ping` | 保持长连接 | 前端忽略或更新心跳 | 否 |

## 28. Java 业务层详细职责

### 28.1 为什么 Java 是业务数据唯一写入口

Java 负责业务一致性，Python 负责智能执行。这样分层有四个直接收益：

1. 用户、租户、权限和审计逻辑集中，不会被多个 Python 节点重复实现；
2. 消息、工具调用、技能调用和用量可以在一个事务边界内处理；
3. 前端只依赖一个业务 API，不暴露 Python 内部拓扑；
4. 将来替换 LangGraph、模型供应商或 Python 实现时，前端和数据库契约保持稳定。

Python 可以返回执行结果，但不应直接写 MySQL 业务表。对于需要跨服务异步化的场景，应通过事件或 Java 接口回写，而不是建立第二套业务写入口。

### 28.2 ChatService 的边界

ChatService 应负责：

- 会话归属校验；
- 历史上下文读取和清洗；
- Agent 与模型解析；
- runtime 白名单注入；
- Python Engine 调用；
- SSE 事件中继；
- 消息和调用审计落库；
- 用量和配额记账。

ChatService 不应负责：

- 直接解析所有模型厂商的 HTTP 差异；
- 在 Java 内执行用户 Python 代码；
- 把隐藏 Chain-of-Thought 原文传给前端；
- 绕过权限直接加载全部工具或技能；
- 将模型供应商 API Key 写入日志。

### 28.3 历史治理与上下文污染防护

历史污染是本项目中非常关键的故障源。防护分为两端：

**Java 端：**

```java
private String sanitizeHistoryContent(String role, String content) {
    if (content == null) {
        return null;
    }
    String value = content.trim();
    if (!"assistant".equalsIgnoreCase(role)
            || !isRawToolCallJson(value)) {
        return content;
    }
    return "（历史工具动作已隐藏，仅保留可审计的工具事件；禁止复述内部 tool_calls 协议。）";
}
```

**Python 端：**

```python
def _latest_user_prompt(self, content: str) -> str:
    value = str(content or "").strip()
    if not value:
        return ""
    matches = list(re.finditer(
        r"(?:^|\n)user\s*:\s*(.*?)(?=\n(?:user|assistant)\s*:|$)",
        value,
        re.S | re.I,
    ))
    if matches:
        latest = matches[-1].group(1).strip()
        if latest:
            return latest
    return value
```

双端治理的意义是：即使旧数据已经存在，下一轮也不能继续把它当作自然语言上下文；即使 Java 传递的是拼接历史，Python 也必须提取当前真正的问题。

## 29. Python Agent Engine 详细设计

### 29.1 模型适配器分层

模型调用建议拆成三层：

```text
请求契约层
  ↓
Provider 规范化层
  ↓
HTTP/OpenAI-compatible 传输层
```

- 请求契约层：接受 `modelConfigId`、messages、tools、temperature、stream 等统一字段；
- Provider 规范化层：处理 `deepseek`、`qwen`、`ollama` 等 provider 的 base URL、模型名和能力差异；
- 传输层：统一发送 `/chat/completions`，处理状态码、超时和网络异常。

### 29.2 URL 规范化

用户配置可能是以下形式：

```text
https://api.example.com
https://api.example.com/v1
https://api.example.com/v1/
https://api.example.com/v1/chat/completions
```

适配器必须先规范化，避免重复拼接：

```text
host → host/v1/chat/completions
host/v1 → host/v1/chat/completions
host/v1/ → host/v1/chat/completions
完整 chat/completions URL → 原样使用
```

错误的 URL 拼接会造成 404，并且容易被误判成 Key 错误。日志中应记录规范化后的“去密钥 URL”，不能记录 Authorization 头。

### 29.3 代理处理

当前实现使用：

```python
with httpx.Client(
    timeout=httpx.Timeout(90.0, connect=10.0),
    trust_env=False,
) as client:
    resp = client.post(...)
```

原因是开发机上可能存在不可用的系统代理，例如 `127.0.0.1:57777` 或其他已关闭端口。对本地 Ollama 还应配置合理的 `NO_PROXY`，避免本地模型请求绕行外部代理。

生产环境不应简单地永久关闭代理，而应把代理设置显式放入 provider 配置：

- provider 级代理；
- 租户级代理；
- 私网模型直连；
- 公网模型经安全出口；
- 代理健康检查和连接池。

### 29.4 上游错误分类

| HTTP/网络错误 | 含义 | 用户提示 | 是否允许自动重试 |
|---|---|---|---|
| 401 | Key 无效、Key 过期、认证头错误 | 检查 API Key 和 provider 配置 | 通常不重试 |
| 402 | 余额或额度不足 | 检查供应商账户余额/额度 | 不重试 |
| 403 | 权限、地域或模型访问限制 | 检查模型权限和组织配置 | 通常不重试 |
| 404 | URL 或模型不存在 | 检查 base URL、路径和模型名 | 修正配置后重试 |
| 429 | 速率限制或并发超额 | 稍后重试，降低并发 | 指数退避 |
| 5xx | 上游暂时故障 | 稍后重试或切换备用模型 | 指数退避/熔断 |
| timeout | 网络慢或上游处理超时 | 检查网络和请求长度 | 有限次重试 |
| connection refused | 代理/服务未启动 | 检查代理或本地模型服务 | 修正网络后重试 |
| invalid JSON | 上游返回非协议内容 | 检查兼容性和响应体 | 通常不重试 |

关键原则：错误摘要必须可操作，但不能把上游完整响应、Authorization、内部堆栈或用户输入原样暴露给前端。

### 29.5 脱敏 API Key 的硬规则

模型配置页面可能显示脱敏值，例如：

```text
sk-****abcd
```

该值只能用于页面展示，绝不能发送给上游。服务端应区分：

- `secret_present`：数据库中是否存在真实密钥；
- `secret_masked`：返回给前端的展示值；
- `secret_update`：用户是否提交了新的真实密钥；
- `secret_clear`：用户是否明确清除密钥。

更新模型时，如果用户没有重新提交 Key，应保留数据库中的真实 Key；如果只提交了脱敏值，应拒绝覆盖真实 Key，并返回明确校验错误。

## 30. 原始工具协议清理与自然语言回答

### 30.1 为什么会出现原始 JSON

OpenAI-compatible 上游可能返回：

1. 标准 `message.tool_calls` 字段；
2. `content` 内嵌的 `tool_calls` JSON；
3. 非标准 `tool_code` 字段；
4. `arguments` 是 JSON 字符串而不是对象；
5. 模型只返回工具计划，不继续生成自然语言总结。

如果适配器把这些内容当成普通文本，就会出现：

```json
{"tool_calls":[{"name":"get_self_introduction","arguments":{}}]}
```

### 30.2 正确的内部转换流程

```text
上游响应
  ↓
解析 message.tool_calls / tool_code / content 嵌套协议
  ↓
校验工具名称和参数 Schema
  ↓
生成内部 tool plan
  ↓
发出 tool_call_start
  ↓
执行工具
  ↓
发出 tool_call_result 或 tool_call_error
  ↓
将结果重新交给 agent 节点
  ↓
生成自然语言总结
  ↓
只对外输出 content_delta
```

### 30.3 普通问候的期望结果

当用户输入“你好”时，平台可以执行工具，也可以不执行工具，但对用户最终必须是自然语言，例如：

```text
你好！我是 AgentForge 智能助手，很高兴为你服务。
```

不允许把内部工具计划作为最终回答，也不允许把旧工具计划复制到下一轮上下文。

### 30.4 隐藏推理与可审计过程的边界

平台需要展示“过程”，但不能泄露隐藏逐字 Chain-of-Thought。安全过程应由结构化事件组成：

```ts
interface ThinkingStep {
  label: string
  detail?: string
  phase?: 'analysis' | 'action' | 'knowledge' | 'generation' | 'complete'
  status: 'running' | 'done' | 'error'
}
```

允许展示：

- 正在分析请求；
- 命中了某个技能；
- 调用了某个工具；
- 检索了哪些知识库来源；
- 生成阶段已完成；
- 工具返回成功或失败摘要。

不允许展示：

- 系统提示词全文；
- 模型内部逐字思考；
- 原始隐藏规划 JSON；
- 未脱敏工具参数；
- Python traceback；
- 未授权的知识库片段。

## 31. Agent runtime 详细契约

### 31.1 Agent 生命周期

```text
草稿 status=1
   ↓ 发布
已发布/启用 status=2
   ↓ 停用
已停用/下线 status=3
   ↓ 编辑后重新发布
已发布/启用 status=2
```

创建和编辑阶段允许不完整配置；发布阶段应校验：

- Agent 名称和编码唯一性；
- 系统提示词非空；
- 模型配置存在且属于当前租户；
- 工具/技能 ID 存在且处于可用状态；
- 知识库文档 ID 属于当前租户；
- 发布人有对应权限；
- 版本记录可以追溯。

### 31.2 Agent 配置字段的作用

| 字段 | 作用 | 运行时影响 |
|---|---|---|
| `name` | 展示名称 | 前端和会话显示 |
| `code` | 稳定标识 | API、审计和配置引用 |
| `system_prompt` | 系统行为约束 | 注入模型系统消息 |
| `model_config_id` | 默认模型绑定 | 请求未指定模型时使用 |
| `tool_ids` | 工具资源白名单 | 只开放指定工具 |
| `skill_ids` | 技能资源白名单 | 只允许指定技能命中 |
| `knowledge_doc_ids` | 知识文档白名单 | 限定 RAG 检索范围 |
| `status` | 生命周期状态 | 草稿/启用/停用 |
| `version` | 配置版本 | 发布追踪和回滚 |

### 31.3 空列表的安全语义

这是必须固定的语义：

```text
tool_ids=[]  → 不开放任何工具
skill_ids=[] → 不开放任何技能
knowledge_doc_ids=[] → 不开放指定文档检索，按产品定义可视为无绑定
```

绝不能解释成“空列表代表全部”。如果要开放全部，必须使用显式权限或管理员策略，并经过服务端授权校验。

### 31.4 会话级复用

会话创建时可绑定 Agent。后续消息优先使用会话绑定 Agent，避免同一会话中用户切换页面或刷新后丢失 Agent 上下文。

推荐优先级：

```text
会话绑定 Agent
    > 请求级 agentId
    > 默认助手
```

如果请求级 Agent 与会话绑定 Agent 冲突，应记录一次上下文变更审计，并明确产品策略：

- 严格模式：拒绝冲突请求；
- 覆盖模式：只对本轮覆盖；
- 更新模式：修改会话绑定。

当前设计倾向于会话绑定优先，以保证多轮一致性。

## 32. MCP 工具体系的执行与审计

### 32.1 工具描述契约

工具注册信息至少应包含：

```json
{
  "id": 1,
  "code": "calculator",
  "name": "计算器",
  "description": "执行安全的数学表达式计算",
  "inputSchema": {},
  "outputSchema": {},
  "executorType": "builtin",
  "transport": "local",
  "timeoutMs": 5000,
  "enabled": true
}
```

其中 `inputSchema` 和 `outputSchema` 不只是文档，还应参与运行时校验。Schema 校验失败应在工具执行前返回，而不是把非法参数交给执行器。

### 32.2 builtin 与 MCP 双路径

```text
tool code
  ├─ builtin → Java/Python 内置执行器
  └─ mcp     → MCP Server / Tool Gateway
```

两条路径对上层应保持一致的事件契约：

- 相同的 `callId`；
- 相同的开始、成功、失败事件；
- 相同的超时语义；
- 相同的错误脱敏；
- 相同的审计字段。

### 32.3 内置工具安全边界

**calculator**：使用 AST 安全计算，不使用 `eval`。需要限制函数、运算符、数字长度和表达式深度。

**get_current_time**：只接受 IANA 时区，不允许任意系统命令；返回标准化时间和时区名称。

**unit_converter**：单位集合应来自白名单，温度换算必须处理摄氏、华氏和开尔文的偏移关系，错误单位要返回可理解提示。

**code_exec**：必须经过 AST 预检、import 白名单、危险调用拦截、进程隔离、超时、输出截断和进程组清理。

### 32.4 callId 的贯通

一次工具调用应能沿以下链路关联：

```text
Python tool_call_start.callId
  → Java SSE data.callId
  → message_tool_call.call_id
  → audit_log.trace_id/call_id
  → 前端时间线 item.callId
```

缺少 callId 会造成三个问题：无法把结果配对、无法定位失败调用、无法做工具耗时统计。

## 33. Skill 系统与 skillzip 安全导入

### 33.1 L0/L1/L2 渐进式披露

| 层级 | 载荷 | 使用时机 | 风险控制 |
|---|---|---|---|
| L0 | name、description、keywords、version | 每次候选筛选 | 轻量、低成本 |
| L1 | 完整 `SKILL.md` | 关键词命中后 | 读取明确规则 |
| L2 | system prompt、工具白名单、执行参数 | 确认执行时 | 最小权限、可审计 |

渐进式披露的意义是：不把所有技能全文都塞进模型上下文，减少 token 消耗和提示词冲突，也减少恶意技能通过描述字段扩大权限的机会。

### 33.2 技能触发

技能可通过 keyword 或 regex 命中。触发过程至少应记录：

- 用户请求摘要；
- 命中的技能 ID；
- 命中规则类型；
- 命中位置或规则编号；
- 最终是否执行；
- 执行过程中调用了哪些工具。

技能命中不等于技能有权调用所有工具。最终权限必须取：

```text
Agent 允许工具
    ∩ Skill 允许工具
    ∩ 平台启用工具
    ∩ 当前用户权限
```

### 33.3 `.skillzip` 导入检查顺序

推荐检查顺序：

1. 上传文件大小不超过 10MB；
2. ZIP 条目数不超过 256；
3. 检查每个条目是否为绝对路径；
4. 检查是否包含 `../` 路径穿越；
5. 检查危险扩展名；
6. 解压到临时目录；
7. 检查解压总大小不超过 32MB；
8. 搜索唯一 `SKILL.md`；
9. 解析 YAML front matter；
10. 校验 `name`、`description`、`version`、`allowed_tools`；
11. 检查技能编码和租户目录冲突；
12. 原子移动到 `skill-repo/tenant-{tenantId}/{skillCode}/SKILL.md`；
13. 写入数据库；
14. 失败时回滚数据库并清理临时目录。

### 33.4 恶意包风险

应拒绝或进一步审查以下内容：

- 包含符号链接或特殊文件；
- 包含可执行文件、脚本或动态库；
- 使用极深目录制造解压炸弹；
- 通过 `../` 写入仓库外路径；
- 在 Skill 文本中要求忽略平台权限；
- `allowed_tools` 包含不存在或被禁用工具；
- front matter 重复、缺失或字段类型错误。

## 34. 代码执行沙箱：威胁模型与控制面

### 34.1 威胁模型

代码执行面对的风险包括：

- 读取宿主机敏感文件；
- 访问内网服务或云元数据；
- 派生子进程；
- 删除或篡改文件；
- 消耗 CPU、内存和文件描述符；
- 输出海量内容导致内存或日志爆炸；
- 通过魔术属性、反射或动态 import 绕过黑名单。

### 34.2 控制面

当前设计使用多层控制：

```text
输入
  ↓
AST 预检
  ↓
import 白名单 / 危险调用黑名单 / 魔术属性拦截
  ↓
独立进程与新进程组
  ↓
CPU、内存、文件描述符限制
  ↓
超时强杀整个进程组
  ↓
输出截断与统一结果
```

统一返回结构：

```json
{
  "status": "success",
  "stdout": "...",
  "stderr": "",
  "duration_ms": 42,
  "exit_code": 0,
  "error_code": null
}
```

失败时必须区分：语法拒绝、导入拒绝、危险调用、超时、资源超限、进程异常和输出截断。

### 34.3 沙箱不是完整容器隔离

当前进程级沙箱适合开发环境和受控内置代码执行，但不能自动等价于生产级容器或 microVM。生产环境还应考虑：

- 独立容器用户和只读根文件系统；
- seccomp、AppArmor 或同类系统策略；
- 网络默认关闭；
- 独立临时目录；
- cgroup 资源限制；
- 工作节点与业务节点隔离；
- 沙箱镜像版本固定和漏洞扫描。

## 35. RAG 知识库生产化细节

### 35.1 文档处理流水线

```text
上传文档
  ↓
格式识别与权限校验
  ↓
文本提取 / OCR
  ↓
清洗：空白、页眉页脚、重复段落
  ↓
分块：按标题、段落和长度切分
  ↓
生成 embedding
  ↓
写入 document / doc_chunk / vector
  ↓
建立索引状态
  ↓
可检索
```

### 35.2 分块策略

分块不能只按固定字符数。建议组合：

- 标题作为 chunk 元数据；
- 段落边界优先；
- 超长段落再按 token 长度切分；
- 保留前后 overlap；
- 记录页码、来源文件、租户、权限和版本；
- 对代码、表格和列表使用不同切分规则。

### 35.3 检索结果必须可溯源

每个结果至少包含：

```json
{
  "doc_id": "...",
  "chunk_id": "...",
  "title": "...",
  "content": "...",
  "score": 0.82,
  "page": 3,
  "tenant_id": "...",
  "version": 2
}
```

Agent 只能检索 `knowledge_doc_ids` 白名单中的文档。即使向量数据库返回了其他租户的相似内容，也必须在应用层二次过滤。

### 35.4 当前降级方案与生产差距

当前支持本地 `data/knowledge/` 降级存储和 PostgreSQL/pgvector 设计。生产化仍需补齐：

- 异步索引任务和重试队列；
- OCR 和复杂 PDF 解析；
- 混合检索：关键词 + 向量 + rerank；
- 文档版本和删除传播；
- 权限继承和访问审计；
- embedding 模型版本管理；
- 索引耗时、失败率、命中率监控。

## 36. 数据库表域与事务边界

### 36.1 MySQL 表域

| 表域 | 代表表 | 核心关系 |
|---|---|---|
| 身份权限 | `sys_user`、`sys_role`、`sys_permission` | 用户-角色-权限 |
| 会话消息 | `conversation`、`message` | 会话-消息一对多 |
| 工具审计 | `message_tool_call`、`audit_log` | 消息-工具调用、全局审计 |
| Agent | `agent`、`agent_version` | Agent-版本 |
| 工具技能 | `tool`、`mcp_server`、`skill` | 注册、启停、来源 |
| 模型 | `model_config` | provider、模型、URL、密钥引用 |
| 用量配额 | `api_usage`、`api_quota` | 租户/用户用量和限制 |
| 知识库 | `knowledge_doc` | 文档元数据和索引状态 |

### 36.2 消息落库建议

消息应至少保存：

- 会话 ID；
- 租户 ID；
- 用户 ID；
- 消息序号；
- 角色；
- 内容；
- 模型配置 ID；
- Agent ID；
- traceId；
- 状态；
- 创建时间和完成时间。

工具调用和技能调用不要把所有细节塞进消息内容，而应使用独立表和结构化字段。这样可以避免原始协议污染对话文本，也便于审计检索。

### 36.3 删除策略

会话删除需要明确是软删除还是级联删除。当前验收包含单个删除、批量删除和一键清空，但生产环境应进一步定义：

- 删除会话是否删除消息；
- 是否保留审计；
- 是否允许恢复；
- 批量删除的最大数量；
- 一键清空是否需要二次确认；
- 删除任务是否异步化；
- 删除失败如何向前端反馈。

## 37. Redis Key 设计与一致性

### 37.1 Key 命名规范

统一格式：

```text
af:{env}:{domain}:{business-key}
```

示例：

```text
af:dev:auth:refresh:{userId}:{tokenId}
af:dev:rate:chat:{tenantId}:{userId}
af:dev:quota:tenant:{tenantId}:{yyyyMMdd}
af:dev:lock:skill-import:{tenantId}:{skillCode}
af:dev:checkpoint:conversation:{conversationId}
```

### 37.2 TTL 原则

- 验证码：分钟级；
- refresh token：按安全策略设置，轮换后旧值立即失效；
- 限流窗口：秒/分钟级；
- 配额统计：按日/月保留；
- 分布式锁：短 TTL，避免死锁；
- checkpoint：按会话生命周期和清理策略保留。

所有带 TTL 的数据都应有过期策略，不能把 Redis 当作无限数据库。对 refresh token 应使用 tokenId 或 sessionId，而不是把完整 token 放进普通日志。

## 38. 前端工作台详细交互模型

### 38.1 请求层职责

`frontend/src/utils/request.ts` 统一处理：

- access token 注入；
- refresh token 读取；
- traceId 生成/透传；
- 401 刷新队列；
- 业务错误码翻译；
- 刷新失败后的用户态清理；
- 请求重放和原始错误返回。

并发刷新状态：

```ts
let isRefreshing = false
let pendingQueue: Array<(token: string) => void> = []
```

必须避免：

- 每个 401 都单独发 refresh；
- refresh 请求本身再次进入 refresh 拦截；
- 刷新成功但队列未释放；
- 刷新失败仍保留旧用户信息；
- access token 写入 localStorage 造成长期泄露面。

### 38.2 路由与权限

工作台子路由：

```text
/workspace/chat
/workspace/knowledge
/workspace/agents
/workspace/tools
/workspace/models
/workspace/obs
```

路由元数据通过 `meta.perm` 表示权限，守卫检查：

```ts
if (to.meta.perm && !userStore.hasPerm(to.meta.perm)) {
  return next({ path: '/403' })
}
```

管理员角色可以通配，但后端仍必须执行真正权限校验，前端权限只负责体验和导航控制。

### 38.3 ChatView 的状态机

```text
IDLE
  ↓ submit
CREATING_MESSAGE
  ↓ message_start
STREAMING
  ├─ content_delta → 更新 Markdown
  ├─ tool_call_start → 添加工具时间线
  ├─ skill_call_start → 添加技能时间线
  ├─ error → ERROR
  └─ message_done → COMPLETED
```

前端状态至少要区分：

- 空会话；
- 正在创建；
- 正在流式；
- 工具执行中；
- 技能执行中；
- 生成完成；
- 失败可重试；
- 连接断开但服务端可能仍在执行。

### 38.4 ToolsView 的容错设计

工具列表和技能列表使用 `Promise.allSettled` 分开加载。这样某一个接口失败时，另一个列表仍可显示。

页面必须提供：

- loading 状态；
- 空数据状态；
- 单模块错误状态；
- 重新加载按钮；
- 上传进度；
- 上传失败原因；
- 启停状态；
- 删除确认；
- unknown icon fallback。

## 39. 错误排查手册

### 39.1 登录过期

排查顺序：

1. 浏览器内存中是否有 access token；
2. localStorage 是否有 refresh token；
3. `/auth/refresh` 是否返回成功；
4. refresh token 是否轮换；
5. 并发 401 是否只触发一次刷新；
6. 刷新失败后是否清理 user store；
7. Java 是否校验了正确租户和用户；
8. 系统时间是否严重漂移。

### 39.2 模型不调用

排查顺序：

1. 前端请求是否带 `modelConfigId`；
2. Java 是否读取请求字段；
3. Agent runtime 是否覆盖模型；
4. 最终 effective model ID 是什么；
5. provider、base URL、模型名是否匹配；
6. Key 是否为真实值而不是脱敏值；
7. 是否被系统代理劫持；
8. Python 是否进入 OpenAI-compatible 路径；
9. 上游 HTTP 状态码是什么；
10. 是否错误降级到 deterministic。

### 39.3 原始 tool_calls JSON

排查顺序：

1. 上游响应是在 `message.tool_calls` 还是 `content`；
2. 适配器是否解析字符串 arguments；
3. AgentGraph 是否发出工具事件；
4. 工具结果后是否重新生成自然语言；
5. Java 是否把原始 JSON 落入 assistant 消息；
6. 下一轮历史是否经过 sanitize；
7. 前端是否错误地把事件 data 当普通文本追加。

### 39.4 工具技能页面空白

排查顺序：

1. 浏览器控制台是否有 AfIcon unknown icon 错误；
2. `/api/tools` 是否返回 200；
3. `/api/skills` 是否返回 200；
4. 两个响应是否被统一 Promise 拒绝；
5. 页面是否因 CSS 解析失败未完成挂载；
6. 路由是否指向 `ToolsView.vue`；
7. 权限是否错误跳转到 `/workspace/chat`；
8. HMR 缓存是否需要强制刷新。

### 39.5 Java 端口和旧 JAR

排查顺序：

1. 8090 是否被旧 Java 进程占用；
2. 启动脚本打印的 JAR 路径是否最新；
3. `/health` 是否返回新版本行为；
4. 是否执行 `--restart-java`；
5. 日志是否显示旧配置；
6. Python Engine 的 Java 地址是否仍指向 8080。

## 40. 测试矩阵与验收证据

### 40.1 单元和静态检查

| 层级 | 检查项 | 目标 |
|---|---|---|
| Python | pytest | AgentGraph、模型适配器、工具、Skill、RAG 逻辑 |
| Python | import/compile | 防止缩进和导入错误 |
| Java | Maven build | 多模块依赖和编译 |
| Vue | `vue-tsc --noEmit` | 类型契约和组件类型 |
| Vue | `vite build` | CSS、路由、资源和打包 |
| 安全 | skillzip 回归 | 恶意包和边界包拒绝 |

### 40.2 端到端矩阵

| 场景 | 输入 | 预期 | 关键断言 |
|---|---|---|---|
| 登录 | identifier/password | 双 Token | 字段正确、refresh 可用 |
| 普通问候 | “你好” | 自然语言回答 | 不出现原始 tool_calls |
| 数学计算 | 结构化问题 | calculator 结果 | 有工具事件和最终总结 |
| 多轮追问 | 连续两轮 | 只回答当前问题 | 不复述旧工具协议 |
| 真实模型失败 | 无效 Key | 安全错误/降级 | 不伪造真实成功 |
| 模型切换 | modelConfigId | 使用指定模型 | 不自动切 deterministic |
| Agent | 绑定工具/技能 | 只调用白名单 | 空列表不加载全部 |
| 技能包 | 合法 skillzip | 导入成功 | 租户目录和数据库一致 |
| 恶意包 | 路径穿越/危险扩展名 | 拒绝 | 无仓库外写入 |
| 会话删除 | 单个/批量/清空 | 列表正确更新 | 消息和审计策略符合预期 |
| 知识检索 | 指定 doc IDs | 只返回白名单 | 来源可溯源 |

### 40.3 稳定性巡检

历史 30 分钟巡检结果：

```text
35 轮
353 个检查通过
0 个失败
```

增强版建议每轮至少检查：

- 五端端口；
- `/health`；
- 登录和刷新；
- 会话列表；
- deterministic 聊天；
- 原始协议泄漏；
- 工具/技能列表；
- 知识库接口；
- 日志是否持续增长异常；
- Java/Python 进程是否重启。

## 41. 可观测性与审计字段规范

### 41.1 traceId 传播

```text
Browser request
  → Java Controller
  → ChatService
  → Python request
  → AgentGraph event
  → Java SSE
  → MySQL audit_log/api_usage
```

traceId 不应仅在日志中生成，而应在请求入口尽早确定并向下游传播。callId 用于一次工具或技能动作，traceId 用于一次完整请求。

### 41.2 建议记录的指标

- 请求总数、成功数、失败数；
- 首 token 延迟；
- 完整响应耗时；
- SSE 连接时长；
- 模型 provider 和模型名；
- 输入/输出 token；
- 工具调用次数和耗时；
- Skill 命中率和失败率；
- RAG 命中率、检索耗时和来源数；
- 429、5xx、timeout 数量；
- deterministic 降级次数；
- 每租户、每用户、每 Agent 用量。

### 41.3 审计脱敏

审计记录允许保存：

- 谁调用；
- 何时调用；
- 目标资源；
- 动作类型；
- 成功/失败；
- 错误码；
- 耗时；
- 脱敏后的摘要。

不应保存：

- 完整 API Key；
- refresh token；
- JWT secret；
- 完整 Authorization 头；
- 未授权知识库全文；
- 用户代码的全部敏感输出；
- 模型隐藏逐字思考。

## 42. 生产化加固清单

### 42.1 模型弹性

- 指数退避和最大重试次数；
- 429/5xx 分类重试；
- provider 熔断；
- 主备模型故障转移；
- 请求级超时和总预算；
- 最大上下文长度；
- token 成本预估；
- 按租户限流；
- 上游连接池和 DNS 策略。

### 42.2 readiness

建议拆分：

```text
/liveness  只判断进程是否存活
/readiness  判断端口、数据库、Redis、Python Engine 等依赖
/health     返回面向用户的简化状态
```

真实模型公网连通性不一定应作为 Java readiness 的硬依赖，否则供应商短暂故障会导致整个业务服务被判定为未就绪。可以将模型状态作为独立 degraded 状态。

### 42.3 多租户

所有接口统一从认证上下文获得 tenantId，`X-Tenant-Id` 只能作为受控的内部调试或网关字段，不能让普通用户自由切换租户。需要补充：

- tenantId 与 userId 一致性校验；
- 数据库查询统一租户拦截；
- 文件仓库按租户隔离；
- Redis key 带 tenant；
- 向量检索二次过滤；
- 审计查询也带 tenant；
- 管理员跨租户能力单独授权。

### 42.4 真实 MCP

生产兼容性测试应覆盖：

- stdio、HTTP、SSE 等传输；
- server 启停和重连；
- tool list 变化；
- schema 版本不一致；
- 超时、取消和重复调用；
- server 返回大结果；
- server 返回恶意内容；
- 凭据隔离和审计。

## 43. 架构决策记录（ADR 摘要）

### ADR-001：Java 与 Python 分离

**决策**：业务编排由 Java 承担，智能执行由 Python LangGraph 承担。

**原因**：Java 适合认证、事务、权限和数据落库；Python 适合模型、Agent、工具和 RAG 生态。

**代价**：需要维护 HTTP/NDJSON/SSE 契约、traceId 和跨服务错误映射。

### ADR-002：Java 8090

**决策**：避开被其他项目占用的 8080，统一使用 8090。

**影响**：启动脚本、Vite proxy、Python 内部调用、测试脚本必须同步更新。

### ADR-003：请求级模型优先

**决策**：用户在聊天页显式选择模型时，优先使用请求级模型。

**原因**：避免 UI 选择与实际调用脱节，支持 Agent 默认模型和临时覆盖并存。

### ADR-004：原始工具协议永不外显

**决策**：工具协议只在内部结构化处理，对外只发事件和自然语言总结。

**原因**：提升体验，避免协议污染上下文，并降低内部实现泄漏风险。

### ADR-005：空白资源列表表示最小权限

**决策**：空 `tool_ids`、空 `skill_ids` 不开放资源。

**原因**：默认拒绝比默认开放更安全，避免 Agent 配置遗漏导致全量工具暴露。

### ADR-006：业务数据由 Java 写入

**决策**：Python 返回执行结果，Java 统一落库。

**原因**：减少重复事务逻辑，集中审计和租户控制。

## 44. 交付前最终检查表

### 文档与代码

- [ ] 文档中没有真实凭据；
- [ ] 所有路径与当前仓库一致；
- [ ] 端口均为最新端口；
- [ ] 模型优先级与实现一致；
- [ ] SSE/NDJSON 事件名称一致；
- [ ] 工具和技能白名单语义一致；
- [ ] 已知限制没有被写成“已完成”。

### 服务

- [ ] MySQL 可连接；
- [ ] Redis 可连接；
- [ ] Python `/health` 正常；
- [ ] Java `/health` 正常；
- [ ] Vue 可访问；
- [ ] 日志路径可写；
- [ ] 启停脚本可重复执行。

### 聊天

- [ ] 普通问候返回自然语言；
- [ ] 多轮问题不复述旧工具 JSON；
- [ ] 模型选择不被默认值覆盖；
- [ ] 真实模型失败有明确错误；
- [ ] deterministic 只作为明确的离线/降级路径；
- [ ] 工具和技能时间线状态正确；
- [ ] message_done 一定收口。

### 安全

- [ ] refresh token 轮换；
- [ ] access token 不落盘；
- [ ] 401 刷新队列可释放；
- [ ] API Key 不写日志；
- [ ] skillzip 路径穿越被拒绝；
- [ ] 代码沙箱超时会杀进程组；
- [ ] RAG 查询有租户和文档白名单；
- [ ] 管理员通配权限后端仍有审计。

## 45. 术语表

| 术语 | 解释 |
|---|---|
| Agent | 具备提示词、模型、工具、技能和知识范围的可复用智能体配置 |
| Agent runtime | 一次聊天请求实际使用的 Agent 配置快照 |
| deterministic | 不依赖外部模型的离线确定性回答模型 |
| Provider | 模型供应商或兼容协议类型 |
| SSE | Server-Sent Events，服务端向浏览器推送流式事件的协议 |
| NDJSON | 每行一个 JSON 对象的流式数据格式 |
| MCP | 用于连接外部工具和服务的工具协议体系 |
| Skill | 面向特定任务的元数据、提示词、触发规则和权限配置 |
| RAG | Retrieval-Augmented Generation，检索增强生成 |
| callId | 一次工具/技能动作的唯一标识 |
| traceId | 一次完整请求跨服务传播的追踪标识 |
| readiness | 服务是否具备接收业务流量的就绪状态 |
| degraded | 服务仍可运行但部分可选依赖不可用的降级状态 |
| refresh token | 用于换取新 access token 的长期凭据 |
| runtime whitelist | 运行时资源白名单，限制 Agent 可使用的工具、技能和文档 |

## 46. 增强版结论

AgentForge 当前已经不是单纯的聊天页面，而是一套具备以下骨架的企业级 Agent 平台：

```text
身份与租户
  + 会话与消息
  + 模型接入与故障分类
  + Agent 配置与生命周期
  + MCP 工具与审计
  + Skill 渐进披露与安全导入
  + 代码执行沙箱
  + RAG 知识库
  + SSE/NDJSON 流式事件
  + 过程摘要与安全边界
  + 用量、配额、审计和可观测性
  + 本地一键启动与自动化回归
```

从工程成熟度看，已经完成了“可运行闭环”和“核心故障修复”；从生产化角度看，下一阶段重点不再是简单增加页面，而是加固真实模型弹性、租户上下文、MCP 兼容性、RAG 异步索引、沙箱隔离和运维 readiness。

这两类结论必须同时保留：

- **不能因为存在生产化待办，就否定当前已经完成的能力闭环；**
- **也不能因为本地测试通过，就把当前系统表述成已经完成全部生产级加固。**

---

## 附录 C：增强版文件与职责索引

| 文件 | 职责 | 维护重点 |
|---|---|---|
| `backend/af-session-impl/.../ChatService.java` | 聊天业务编排 | 模型优先级、历史清洗、SSE、审计 |
| `agent-engine/app/graph/agent_graph.py` | Agent 状态图 | 工具/技能路由、白名单、当前问题提取 |
| `agent-engine/app/model/openai_compatible.py` | 真实模型适配 | URL、代理、状态码、工具协议、Key 安全 |
| `agent-engine/app/model/deterministic.py` | 离线模型 | 回归兜底、自然语言回答 |
| `agent-engine/app/main.py` | Python API | NDJSON 流式、健康检查、知识库接口 |
| `SkillPackageService.java` | skillzip 导入 | ZIP 安全、临时目录、原子移动、回滚 |
| `SkillController.java` | 技能管理 API | 上传、启停、CRUD、权限 |
| `ToolController.java` | 工具管理 API | 工具 CRUD、MCP Server、权限 |
| `AgentController.java` | Agent 管理 API | CRUD、发布、启停、runtime |
| `frontend/src/utils/request.ts` | 前端请求层 | Token、刷新队列、traceId、错误翻译 |
| `frontend/src/views/workspace/chat/ChatView.vue` | 对话工作台 | SSE、Markdown、过程时间线、模型选择 |
| `frontend/src/views/workspace/tools/ToolsView.vue` | 工具技能治理 | 列表、上传、启停、删除、容错加载 |
| `scripts/start-all.sh` | 五端启动 | 端口、依赖、日志、重启 |
| `tests/e2e-smoke.sh` | 冒烟测试 | 登录、模型、会话、聊天、协议泄漏 |
| `tests/e2e-full.sh` | 全量接口回归 | 双 Token、工具、技能、知识库 |
| `tests/soak-30m.sh` | 稳定性巡检 | 长时间服务和核心接口稳定性 |

## 附录 D：增强版变更说明

相对于 v1.0，增强版新增：

- 需求到技术修复的追踪矩阵；
- 系统级不变量；
- 五端职责、启动顺序和生命周期模型；
- 聊天请求逐步骤时序；
- Java/Python 分层边界；
- 模型适配器和错误分类详解；
- 原始工具协议清理机制；
- Agent runtime 字段和空列表安全语义；
- MCP、Skill、skillzip、沙箱和 RAG 的实施级说明；
- 数据库事务、Redis TTL 和租户隔离细节；
- 前端状态机和页面容错策略；
- 故障排查手册；
- 测试矩阵与审计指标；
- 生产化加固清单；
- ADR 架构决策记录；
- 术语表、文件索引和最终检查表。
