# AgentForge 生产化审计阶段概览

## 本轮完成

- 对本地五端系统执行了生产化审计：前端 Vue/Vite、Java Spring Boot、多模块 Maven、FastAPI 引擎、模型配置链路、JWT/SSE/健康检查。
- 验证服务可访问：前端 `5173`、Java `8080`、Python `8000` 的健康/首页接口均返回 HTTP 200。
- 验证构建：Java `mvn -q -DskipTests package` 通过；前端 `npm run build`（含 `vue-tsc --noEmit`）通过；Python `compileall` 通过。
- Python pytest 未能执行：当前受管 Python 环境缺少 `pytest` 模块，因此不能把测试套件标记为通过。

## 已确认的关键风险

1. **真实模型失败会被伪装成正常 assistant 文本**：`agent-engine/app/model/openai_compatible.py` 的 `build_reply` 捕获异常后返回“真实模型调用失败……”字符串；上层因此可能继续发出 `message_done`，前端看到的是完成态而不是结构化错误。
2. **模型端点规范化不足**：Java `ModelConfigService` 与 Python 适配器都直接拼接 `/chat/completions`；用户填写完整 endpoint 或不同路径时会形成错误 URL。
3. **Provider 约束不足**：工厂将除 `deterministic` 外的任意 provider 都创建为 OpenAI-compatible 模型，未知/非兼容协议会延迟到聊天时失败。
4. **聊天错误可能泄漏内部细节**：`ChatService` 与 Python `/v1/chat/stream` 直接将异常信息写入 SSE；需要区分用户可见摘要与 trace 日志。
5. **模型配置无效时可能静默回退**：`resolveConfig` 返回 null，调用链可能退回引擎默认确定性模型，用户会误以为真实模型已调用。
6. **前端关键操作错误态和并发保护仍不足**：聊天页初始化串行操作缺少统一错误界面；会话删除无确认；模型测试按钮使用 `openEdit(m); testConn()` 连续调用，依赖响应式时序。
7. **健康检查不完整**：当前 `/health` 只是存活检查，没有 DB/Redis/引擎依赖就绪状态；`/actuator/health` 未启用。
8. **认证刷新队列仍有缺陷**：刷新失败时排队请求被空 token resolve，可能触发二次无效请求；路由恢复的模块级一次性标记不利于后端短暂不可用后的重试。
9. **数据库迁移存在手工补字段历史**：运行库曾缺少 `knowledge_doc.content`，依赖人工 ALTER；生产应纳入可追踪、幂等迁移。

## 构建与运行证据

- Python：`python3 -m compileall -q app tests` → PASS。
- Java：`cd backend && mvn -q -DskipTests package` → PASS。
- Frontend：`npm run build` → PASS。
- Python tests：`python3 -m pytest -q` → FAIL（`No module named pytest`，环境依赖缺失，不是测试断言失败）。
- `GET http://127.0.0.1:8000/health` → 200，当前引擎报告 provider `openai-compatible`、model `gpt5.6`。
- `GET http://127.0.0.1:8080/health` → 200。
- `HEAD http://127.0.0.1:5173/` → 200。

## 后续优先级

- P0：真实模型调用错误结构化、禁止静默回退、修正 endpoint/provider/API key 校验、端到端复现真实 Provider。
- P1：统一前端错误/加载态与聊天 SSE 事件处理；补健康 readiness、迁移机制、JWT 刷新队列与重试。
- P2：真正 HTTP streaming、供应商差异适配、限流/重试/熔断、结构化日志与发布流程。
