# AgentForge 生产化加固阶段总结

## 本轮完成

- Python 真实模型适配器增加 provider 白名单，保留 deterministic，并加入 `ollama` OpenAI-compatible Provider。
- 统一规范化 `base_url`：支持 host、`/v1`、尾斜杠和完整 `/chat/completions`，避免重复拼接。
- 真实模型 HTTP 错误按超时、连接失败、401、403、404、429、5xx 和响应格式异常归类为安全错误摘要；不再把失败伪装成 assistant 成功内容。
- Java `HttpAgentEngineClient` 修复请求超时字段使用问题，并通过请求级模型流转传递模型配置。
- Java `ChatService` 修复异步 lambda 捕获变量、恢复 `eventData` 辅助方法，失败审计不再使用可能因 null 抛错的 `Map.of`，SSE 对外仅返回通用失败消息并保留 traceId。
- 前端类型检查和生产构建通过。
- Python 引擎测试通过：35 passed，1 个 LangGraph 弃用警告。
- Java Maven 生产构建通过：`BUILD SUCCESS`。
- 启动脚本语法检查通过；执行时能识别 MySQL、Redis、Python、Java、前端已运行，并输出前端地址。

## 验证结果

- Python：`compileall` 通过。
- Python：`pytest -q` → `35 passed, 1 warning`。
- 前端：`npm run type-check` 通过。
- 前端：`npm run build` 通过。
- Java：`mvn -DskipTests package` → `BUILD SUCCESS`。
- URL 规范化样例均通过：
  - `https://api.example.com` → `/v1/chat/completions`
  - `https://api.example.com/` → `/v1/chat/completions`
  - `https://api.example.com/v1/` → `/v1/chat/completions`
  - 已填写完整 `/chat/completions` 时不会重复拼接。
- 之前的运行时错误回归仍保持：Python NDJSON 发送 `message_start → error`，不再发送 `message_done`。

## 仍需明确

- 当前环境访问 OpenAI 公网端点超时，因此尚未完成真实上游成功内容回归；需要可达的 OpenAI、DeepSeek、通义千问或本地 Ollama 服务及对应配置。
- `scripts/start-all.sh` 仍建议继续补充严格的启动后 readiness 等待和失败非零退出；本轮已验证其现有幂等启动路径，但不能把端口探测等同于完整依赖就绪。
- 知识库、Usage、Audit、内部工具/技能部分接口仍存在从 `X-Tenant-Id` 或默认租户 1 取租户的历史路径，尚未全部统一到 JWT/UserContext。
- 真实 HTTP streaming、重试/退避/熔断、限流和完整 readiness（DB/Redis/Python 依赖检查）仍属于后续生产化工作。

## 运行入口

```bash
bash scripts/start-all.sh
```

前端：`http://localhost:5173`

默认本地演示账号：`admin / Admin@2026`
