# AgentForge 端口冲突修复与状态报告（2026-08-24）

## 问题根因
平台启动失败的根因是 **端口 8080 被另一个项目占用**：

- 本机存在另一个 WorkBuddy 项目「个人财务管理」（`/Users/jack.yang/WorkBuddy/2026-08-21-16-12-24/`），其 Vite 开发服务器占用 `:8080`，其 Java 后端占用 `:8081`。
- 我们的 Java 后端（af-bootstrap）需要 `:8080`，启动即 `BindException` 失败 → 后端一直起不来，前端（`:5173`）也掉了。
- 早期后台任务里出现的 `failed` 通知，正是重启脚本 `pkill` 旧进程以及端口被占导致 Java 无法绑定所致。

## 修复方案（非破坏性，未触碰其他项目）
将**我们自己的后端**迁移到空闲端口 **8090**，并同步修改所有引用，让两个项目互不干扰：

| 文件 | 改动 |
|------|------|
| `backend/af-bootstrap/src/main/resources/application.yml` | `server.port: 8080 → 8090` |
| `scripts/start-all.sh` | Java 段 `port_open`/健康检查/`--server.port` 全部 `8090`（幂等脚本同步） |
| `frontend/vite.config.ts` | dev 代理 `/api` target `http://localhost:8090` |
| `agent-engine/app/config.py` | `tool_registry_url` / `skill_registry_url` → `127.0.0.1:8090`（引擎拉取工具/技能注册表） |
| `tests/e2e-smoke.sh` / `tests/e2e-full.sh` | 默认 `BASE` → `http://127.0.0.1:8090`（仍可用 `AGENTFORGE_BASE` 覆盖） |

> 说明：前端仍跑在 `:5173`，用户访问地址不变；后端端口变化对使用者完全透明。

## 当前运行状态（已验证）
| 服务 | 端口 | 健康检查 | 说明 |
|------|------|----------|------|
| 前端 Vite | 5173 | 200 | 标题 = "AgentForge · 企业级 AI Agent 平台"，`/api` 代理 → 8090 已验证可用 |
| Java 后端 | 8090 | 200 | 已启动 |
| Python 引擎 | 8000 | 200 | 已重启并指向 8090 的 `/internal/tools`、`/internal/skills`（日志确认 200） |
| MySQL | 3308 | — | 运行中 |
| Redis | 6379 | — | 运行中 |

三个后台服务通过 `scripts/start-all.sh` 拉起（`nohup` + 脚本末尾 `exec sleep` 保活），跨轮次常驻。

## 测试结果
- `tests/e2e-smoke.sh`：**5/5 PASS**（登录、模型默认策略=deterministic、会话创建/删除、确定性聊天 SSE、真实模型失败降级）。
- `tests/e2e-full.sh`：**13/13 PASS**（认证/refresh、模型管理、会话 CRUD、确定性 SSE、真实模型降级、工具/技能/知识库列表可访问）。
- 额外端到端验证：**DeepSeek-V4 Pro 真实聊天已跑通**（`message_start` → `content_delta` → `message_done`）。

引擎日志确认：`GET http://127.0.0.1:8090/internal/tools` 与 `/internal/skills` 均返回 200，已从旧 8080 切到正确后端。

## 访问方式
- 浏览器打开 **http://localhost:5173** 即可使用平台。

## 真实模型连接诊断（关键结论，已更新）
已通过后端 API 对多个主流端点做连通性探测：

| 端点 | 结果 | 说明 |
|------|------|------|
| `https://api.openai.com/v1` | ❌ 超时 | 被当前环境透明代理拦截 |
| `https://api.deepseek.com/v1` | ✅ **已联通** | 端到端真实聊天成功 |
| `https://api.moonshot.cn/v1` | ✅ 可达 | HTTP 401，仅缺有效 API Key |
| `https://dashscope.aliyuncs.com/compatible-mode/v1` | ✅ 可达 | HTTP 401，仅缺有效 API Key |

**关键结论：DeepSeek-V4 Pro 已经端到端跑通，真实大模型可以正常对话。**

### 已暴露并修复的三个关键 Bug

#### 1. 前端「测试连通性」误发脱敏 Key
- **问题**：模型编辑弹窗里，API Key 字段回填的是脱敏串（如 `sk-****404b`）；点「测试连通性」时，前端把这个脱敏串当成真实 Key 发给了后端，导致 DeepSeek 返回 401。
- **修复**：`ModelManageView.vue` 的 `testConn()` 现在会检测 Key 是否含 `****`；若是编辑态，自动改走 `/api/models/{id}/test`，由后端使用数据库中真实 Key 测试，并把上游返回的 `detail` 展示出来。

#### 2. Agent 引擎被系统代理带偏
- **问题**：引擎进程继承了 `HTTP_PROXY=http://127.0.0.1:52680`，但 52680 端口未监听，导致 httpx 连外部模型时返回 `Connection refused`。
- **修复**：`agent-engine/app/model/openai_compatible.py` 中 `httpx.Client` 增加 `trust_env=False`，并设置 `connect=10s`，忽略环境代理、快速失败。

#### 3. 并发请求导致模型实例错用（严重）
- **问题**：`AgentGraph` 是单例，`stream()` 会修改 `self.model`/`self.skill_engine.model`。当一个真实模型请求耗时较长（或客户端已超时断开但服务端仍在运行）时，后续确定性请求会误用 OpenAICompatibleModel，反之亦然。
- **修复**：`agent-engine/app/graph/agent_graph.py` 的 `stream()` 已加 `async with self._lock:`，确保请求级模型注入串行执行、正确恢复。

### 当前模型配置模板
已在模型管理里预置两条配置：

| ID | 名称 | 模型名 | 端点 | 备注 |
|----|------|--------|------|------|
| 3 | DeepSeek-V4 Pro（可连接） | `deepseek-v4-pro` | `https://api.deepseek.com/v1` | 已填入你的 Key，连通成功 |
| 4 | DeepSeek-V4 Flash（可连接） | `deepseek-v4-flash` | `https://api.deepseek.com/v1` | 轻量版；填入同一 Key 即可测试 |

> 说明：`deepseek-chat` 已于 2026-07-24 弃用，已改为 `deepseek-v4-pro`。

### 端到端真实模型验证
以 `modelConfigId=3` 调用 `/api/chat/stream`：
- `event:message_start` → `content_delta` × 2 → `message_done`
- 回复示例：「你好！很高兴见到你，有什么可以帮你的吗？」
- token 统计正常：`token_input=9`, `token_output=21`

### 你接下来需要做的
1. 在「模型管理」里点击 DeepSeek-V4 Pro 右侧的「测试」（或编辑弹窗里的「测试连通性」），应显示 **连通成功**。
2. 点击「设为默认」，新会话将自动使用 DeepSeek-V4 Pro。
3. 回到对话页，直接发送「你好」即可收到真实模型回复。

## 代码层面额外优化
- `ModelConfigService.doTest` 改为使用**直连（no-proxy）HttpClient**，避免 `HTTP_PROXY` 环境变量干扰本地/任何端点的连通性测试。
- `scripts/start-all.sh` 启动 Agent 引擎时增加 `NO_PROXY=127.0.0.1,localhost`，确保本地 Ollama 等模型不会被系统代理带偏。
- `agent-engine/app/model/openai_compatible.py`：`httpx.Client` 增加 `trust_env=False` + `connect=10s`，避免环境代理导致连接失败，同时避免长时间挂起。
- `agent-engine/app/graph/agent_graph.py`：`stream()` 加锁，修复并发请求下模型实例被错用的严重 Bug。
- `frontend/src/views/workspace/models/ModelManageView.vue`：编辑态「测试连通性」检测脱敏 Key，改走 `{id}/test`，并展示上游 `detail` 错误详情。
- `tests/e2e-smoke.sh` / `tests/e2e-full.sh`：真实模型失败降级用例兼容新的「直接连接超时」表现，缩短超时时间避免测试挂起。

## 已知限制
- **OpenAI 官方端点被拦截**：当前环境透明代理对 `api.openai.com` 返回超时，无法联通（非代码问题）。
- **Ollama 无法在此沙箱内安装**：Ollama 安装包/模型下载被同一代理拦截，故无法提供本地大模型。
- **Agent 引擎请求已串行化**：为快速修复并发模型错用 Bug，`stream()` 已加锁。多用户同时聊天时会排队执行，后续建议重构为请求级模型注入（通过 graph state 或 context var）。
- 平台默认仍走离线 `deterministic` 模型，保证对话可用；真实模型失败会优雅降级为 `error` 事件。

## 后续建议
1. **现在就可以用**：在「模型管理」里把 DeepSeek-V4 Pro「设为默认」，聊天页发送消息即可走真实模型。
2. 若你有 Moonshot / 通义千问 / Groq / Together 等 Key，也可以新建同样 `openai-compatible` 配置，端点分别为 `https://api.moonshot.cn/v1`、`https://dashscope.aliyuncs.com/compatible-mode/v1` 等。
3. 多租户部分接口仍信任客户端 `X-Tenant-Id` 头；前端聊天错误态缺独立重试按钮（已有切换离线提示）。
4. 如需进一步提升并发能力，可将 `AgentGraph` 改为请求级模型注入，移除 `stream()` 锁。
