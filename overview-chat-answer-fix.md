# AgentForge 普通问答修复概览

## 已完成

- 修复 `agent-engine/app/graph/agent_graph.py`：从 Java 拼接的多轮上下文中提取最后一条 `user:` 消息，再进行技能路由、工具路由和普通回答，避免旧回答或旧 tool_calls 污染当前问题。
- 修复 `agent-engine/app/model/deterministic.py`：确定性模型不再只返回固定演示文本；现在可对问候、AgentForge 平台介绍、能力说明、直接回复、简单算术等常见问题返回实际相关的自然语言答案，并保留对复杂开放域问题的明确降级说明。
- 新增 `agent-engine/tests/test_model_factory.py` 回归断言：普通问答必须回答当前问题，不能复述旧历史或原始 `tool_calls`。
- 扩展 `tests/e2e-smoke.sh`：增加 SSE 自然语言回答、平台介绍内容、原始工具协议防泄漏断言。

## 验证结果

- Python 编译通过。
- Agent 引擎全量测试：`55 passed, 1 warning`。警告为 LangGraph 依赖的弃用提示，不影响测试结果。
- Java 后端构建通过：`backend` Maven package 成功。
- 五端已重启并就绪：前端 5173、Java 8090、Python 引擎 8000、Redis 6379、MySQL 3308。
- 端到端冒烟测试通过：登录、模型默认策略、会话创建/删除、确定性模型 SSE、普通平台介绍、真实模型失败降级，失败数为 0。

## 关键行为

- `你好`：返回自然语言问候，不再返回 `{"tool_calls": ...}`。
- `请介绍 AgentForge 平台`：返回包含“企业级 AI Agent 平台”的平台介绍。
- `你好，请只回复：连接测试成功`：返回指定文本。
- `请直接回答：1+1等于几？`：返回 `1+1 等于 2`。
- 历史中存在旧 `tool_calls` JSON 时，当前问题仍按最后一条用户输入回答，且最终回答不复述内部协议。

## 遗留说明

- Maven 曾在项目根目录执行并提示没有 POM；随后已在 `backend` 目录正确构建并通过。
- 重启脚本会保持后台任务运行，这是既有启动脚本设计；服务健康检查已通过。
- Java fat JAR shutdown hook 的依赖兼容性问题仍属于既有技术债，本轮未改变关闭流程。
