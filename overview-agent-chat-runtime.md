# Agent 聊天闭环阶段完成概览

## 已完成
- 建立 Java → Python 的 `agent_config` 运行时契约，传递 `agent_id`、`agent_code`、`system_prompt`、`tool_ids`、`skill_ids`、`knowledge_doc_ids`。
- AgentGraph 支持按绑定工具和技能 ID 收窄资源；空绑定列表在显式 Agent 配置下表示不开放对应资源。
- 知识库检索支持 `doc_ids` 白名单，Agent 绑定文档可限制检索范围。
- 确定性模型输出可确认 Agent 系统提示词已注入。
- 重启最新 Java 和 Python 服务，健康检查通过。
- 验证请求级 Agent 聊天成功，SSE 包含 `message_start`、`content_delta`、`message_done`。
- 验证会话绑定 Agent：第二次只传 `conversationId` 不传 `agentId`，仍使用会话 Agent 的系统指令。
- 验证工具过滤：仅绑定 `calculator` 时，计算请求成功调用 calculator；当前时间请求不会调用未绑定工具。
- Java 后端构建、前端 type-check/build、Python compileall、启动脚本 bash 语法检查均通过。
- `scripts/start-all.sh` 增加 `--restart-java` 参数，并让 `--rebuild-java` 同时触发 Java 重启，避免旧 JAR 常驻导致接口未加载。

## 关键验证结果
- Agent 运行时 Agent：创建并发布成功，runtime 返回模型绑定和系统提示词。
- 请求级 Agent：确定性模型完成，系统提示词文本出现在回复中。
- 会话级 Agent：会话响应包含 `agentId=2`；后续不传 Agent 的请求仍输出该 Agent 的系统指令。
- 工具白名单 Agent：`toolId=1` 的 calculator 调用事件和结果事件均成功。

## 当前注意事项
- 真实大模型是否可用仍取决于模型配置中的有效 API Key、余额、网络和上游端点；确定性模型链路已稳定。
- `AgentService.resolveRuntime` 对工具/技能/知识库绑定的结构已接入运行时，但生产环境仍建议补充绑定 ID 合法性校验和管理端资源选择的空值语义说明。
- fat JAR 的旧进程问题已通过启动参数缓解；Spring shutdown hook 的历史 `NoClassDefFoundError` 仍建议后续单独整理依赖树并做优雅退出回归。
