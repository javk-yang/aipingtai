# AgentForge 增强详版文档交付概览

## 本次目标

在原 v1.0 项目文档基础上，将技术细节扩展到约三倍信息密度，形成可用于架构评审、开发维护、联调验收、故障排查和生产化改造的增强详版。

## 新增内容

- 用户问题到技术修复的追踪矩阵
- 系统级安全与一致性不变量
- 五端职责、启动顺序和生命周期状态
- 聊天请求逐步骤时序与 SSE/NDJSON 事件映射
- Java/Python 分层边界与 ChatService 职责
- 模型 URL、代理、Key、状态码和降级策略
- 原始 tool_calls 协议清理与安全过程展示边界
- Agent runtime、模型/工具/技能/知识库白名单
- MCP 工具、Skill、skillzip、代码沙箱和 RAG 生产化细节
- MySQL 表域、消息落库、Redis Key 和 TTL 设计
- 前端 Token 刷新、ChatView 状态机和 ToolsView 容错
- 登录、模型、工具技能、端口和旧 JAR 故障排查手册
- 单元、静态、端到端和稳定性测试矩阵
- 可观测性、审计脱敏和 traceId/callId 规范
- 生产化加固清单、ADR、术语表和最终验收表

## 交付物

- `stage1/final_draft-enhanced.md`
- `stage2/formatted-agentforge-platform-enhanced.html`
- `stage3/output-enhanced.docx`
- `pipeline-state.yaml`

## 验证结果

- HTML 质量门禁：96/100，通过
- 安全性检查：通过
- 结构完整性检查：通过
- DOCX 转换：成功，无警告
- 一致性检查：通过

原 v1.0 文档保留在原目录中，未被覆盖。

## 安全提醒

此前聊天中曾暴露过 GitHub Personal Access Token。该 Token 仍应立即撤销，并检查 credential helper、Shell 历史和仓库审计记录。
