# AgentForge 启动状态报告

生成时间：2026-08-24 11:44

## 本次操作

检查发现：MySQL、Redis、Python Agent 引擎、Java 后端均在线，**仅前端 Vite（5173）掉线**。
执行动作：单独拉起前端 Vite（`exec` 前台常驻，跨轮次保活），未重启其余服务，避免不必要的中断。

## 五端运行状态

| 服务 | 端口 | 状态 | PID / 说明 |
|---|---|---|---|
| MySQL | 3308 | ✅ 运行中 | mysqld pid=24983 |
| Redis | 6379 | ✅ 运行中 | redis-server pid=25008 |
| Python Agent 引擎 | 8000 | ✅ health=200 | uvicorn pid=34135 |
| Java 后端 af-bootstrap | 8090 | ✅ health=200 | java pid=34151 |
| 前端 Vite | 5173 | ✅ 200（本次新启动） | VITE v5.4.21，260ms ready |

> 后端使用 **8090**（不是 8080）：本机另一项目「个人财务管理」的 Vite 长期占用 8080，为避免破坏对方进程，我们的后端已永久迁至 8090，前端代理、Agent 引擎注册表 URL、e2e 脚本均已同步。

## 链路验证

- 前端标题：`AgentForge · 企业级 AI Agent 平台` —— 确认 5173 是本项目而非其他项目。
- **跨层代理链路**：`POST http://127.0.0.1:5173/api/auth/login` → Vite 代理 → 后端 8090，返回有效 token（长度 298）。说明前端 → 后端全链路通。

## 回归测试结果

### e2e-smoke：5/5 PASS
1. 登录成功（token 长度 298）
2. 默认模型 provider=deterministic (id=1)
3. 会话创建 / 删除 / 删除后从列表消失
4. 确定性模型 SSE 完整：7×`content_delta` + `message_start` + `message_done`
5. 真实模型失败优雅降级：`message_start` + `error`，且不再发 `message_done`

### e2e-full：13/13 PASS
- 认证模块：登录双 token、refresh token、获取当前用户
- 模型管理：默认模型为 deterministic、草稿模型连通性测试
- 会话与聊天：创建、删除、列表同步、确定性 SSE 完整、真实模型 error 降级
- 工具与技能：工具列表、技能列表可访问
- 知识库：列表可访问

失败数：**0**

## 访问方式

浏览器打开 **http://localhost:5173**
默认账号：`admin` / `Admin@2026`

默认对话走内置「确定性模型」，开箱即可用。

## 真实大模型现状（环境限制，非代码问题）

已探测的端点连通性：

| 端点 | 结果 |
|---|---|
| `https://api.openai.com/v1` | ❌ 超时，被本机透明代理拦截 |
| `https://api.deepseek.com/v1` | ✅ 可达（HTTP 401，仅缺有效 Key） |
| `https://api.moonshot.cn/v1` | ✅ 可达（HTTP 401，仅缺有效 Key） |
| `https://dashscope.aliyuncs.com/compatible-mode/v1` | ✅ 可达（HTTP 401，仅缺有效 Key） |

模型管理中已预置配置 **`DeepSeek-V3（可连接）`**（provider=`openai-compatible`，model=`deepseek-chat`），填入有效 API Key 后点「测试连通性」即可转为真实模型对话。

## 后续可选优化

- 真实模型调用链的重试退避 / 熔断限流
- 前端聊天错误态独立「重试」按钮（当前已有自动切换离线模型的提示条）
- 部分多租户接口仍信任客户端 `X-Tenant-Id` 头
- `knowledge_doc.content` 未纳入幂等迁移
