# AgentForge 平台启动结果

- 启动方式：执行 `scripts/start-all.sh`。
- 结果：MySQL（3308）、Redis（6379）、Python Agent Engine（8000）、Java Backend（8090）及 Vue Frontend（5173）均已在运行，启动脚本已进入保活状态。
- Java 健康检查：`/health` 返回 `status: UP`，应用为 `AgentForge 1.0.0-SNAPSHOT`。
- Agent Engine 健康检查：返回 `status: up`，LangGraph 引擎已就绪，当前使用 deterministic 开发降级模型。
- 前端入口：`http://127.0.0.1:5173`；局域网入口：`http://10.0.4.17:5173`。

说明：启动脚本确认 MySQL 与 Redis 已运行。独立 TCP 探测因当前 shell 的 `/dev/tcp` 兼容性未返回可靠结果，未影响脚本对五端运行状态的判定。
