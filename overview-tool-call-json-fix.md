# 修复聊天原始工具调用 JSON 概览

## 问题

用户发送“你好”后，助手直接显示：

```json
{"tool_calls":[{"name":"get_self_introduction","arguments":{}}]}
```

日志确认该 JSON 被直接写入 assistant message，说明上游 OpenAI-compatible 模型返回了非标准工具规划格式，而引擎未将其转换为内部工具调用，也未阻止其作为最终答案展示。

## 修复

修改：`agent-engine/app/model/openai_compatible.py`

- 兼容标准 `message.tool_calls` 格式。
- 兼容 `content` 中嵌套的 `tool_calls` JSON。
- 将模型返回的工具名称映射为已注册工具编码。
- 将字符串形式的 arguments 解析为对象。
- 真实工具规划统一返回内部格式，不再把原始 JSON传到前端。
- 对普通回答和工具结果总结增加原始工具 JSON 防泄漏保护。
- 如果上游仍返回原始工具 JSON，则自动发起一次“只输出自然语言”的重试；重试仍异常时返回安全的中文兜底回答。

## 验证

- Python `compileall` 通过。
- 已重启 Agent 引擎和 Java 后端。
- 服务健康检查：
  - Agent 引擎 `8000`：200
  - Java 后端 `8090`：200
  - 前端 `5173`：200
- 当前引擎健康状态：`up`。

## 注意

当前本地 Python 运行环境没有安装 `pytest` 和 `httpx`，因此完整 pytest 和独立模拟脚本无法运行；语法检查和服务启动检查已通过。服务实际启动使用项目专用 Python 环境。
