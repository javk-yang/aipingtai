# 原始 tool_calls JSON 循环修复概览

## 根因

本次日志确认存在两个叠加问题：

1. DeepSeek 上游曾将 `{"tool_calls":[...]}` 作为 assistant 文本返回并写入数据库。
2. 后续聊天会把历史 assistant 内容直接拼接进 prompt，导致模型看到旧的内部工具协议后继续复述同一段 JSON。

## 修复

### Python 引擎

文件：`agent-engine/app/model/openai_compatible.py`

- 兼容标准和非标准 `tool_calls` 返回格式。
- 将工具调用统一解析为内部 `tool_code + arguments`。
- 普通回复和工具结果总结增加原始工具 JSON 防泄漏处理。
- 上游返回原始工具 JSON 时，自动重试自然语言回答，并提供安全兜底。

### Java 会话服务

文件：`backend/af-session-impl/src/main/java/com/agentforge/session/impl/service/ChatService.java`

- 历史消息进入模型上下文前进行清洗。
- assistant 历史内容如果是原始 `tool_calls` / `tool_code` JSON，不再原样传给模型。
- 改为使用安全占位说明，避免历史污染和 JSON 复读循环。

## 验证

- Java Maven 构建通过：`mvn -q -DskipTests package`
- Python 语法编译通过：`compileall -q app`
- 运行环境中的工具调用归一化测试通过：`normalization: PASS`
- Agent 引擎已使用项目专用 Python 环境重启。
- 五端健康检查：
  - 前端 `5173`：200
  - Java `8090`：200
  - Agent 引擎 `8000`：200

## 备注

旧会话中已经保存的原始 JSON 仍可能在历史消息列表中显示；新请求不会再把这段 JSON作为上下文复述。建议刷新页面后新建一个会话，再发送“你好”验证。
