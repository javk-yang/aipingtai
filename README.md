AgentForge 是一套面向企业与团队的 AI Agent 智能体平台，用于快速搭建、管理和运营可落地的数字员工与业务智能体。

平台将大模型能力、知识库检索、Agent 编排、工具调用、Skill 技能包、权限控制和运行审计整合在同一套系统中。企业无需从零开发复杂的 AI 应用链路，即可根据实际业务场景配置专属 Agent，让 AI 不只是“聊天”，而是真正参与知识问答、内容生成、数据处理、流程辅助和业务执行。

核心能力
智能体编排
支持为不同业务场景创建 Agent，配置系统提示词、模型、工具、Skill、知识文档范围与发布版本，形成可管理、可回滚的智能体能力组合。

知识库问答
支持上传和维护企业知识文档，将平台介绍、制度规范、产品资料、业务流程等内容沉淀为可检索知识，让 Agent 基于指定资料回答问题，而不是只依赖通用模型知识。

工具与 MCP 接入
平台统一管理内置工具和 MCP 外部工具，Agent 可以在权限范围内调用计算、时间、单位换算、代码沙箱及外部业务能力，并保留完整调用记录。

Skill 技能包体系
支持以 ZIP 技能包形式导入标准化 Skill，通过 SKILL.md 定义技能描述、触发条件、工具白名单和执行规则，让团队能够沉淀、复用和持续迭代业务能力。

流式智能对话
支持实时流式回复，并展示回答生成、工具调用、Skill 执行等过程事件。用户不仅能看到答案，也能了解 Agent 的执行过程和结果。

模型统一管理
支持配置不同模型供应商、模型参数、默认模型和启停状态；在外部模型不可用时，可使用确定性模型保障平台基础链路可验证、可演示。

权限与安全控制
平台提供用户、角色、权限、JWT 双 Token、Refresh Token 轮换、验证码、登录限流和审计能力。不同用户只能访问自身权限范围内的 Agent、工具、Skill 与数据。

可观测与审计
对登录、Agent 发布、工具调用、Skill 调用、模型用量和关键操作进行审计记录；通过 Trace ID 串联前端、Java 服务与 Python Agent 引擎，方便定位问题和追踪运行状态。

技术架构
AgentForge 采用前后端分离与多引擎协作架构：

前端基于 Vue 3 + TypeScript + Pinia + Vue Router
业务后端基于 Spring Boot + MySQL + Redis
Agent 引擎基于 FastAPI + LangChain + LangGraph
工具协议支持 MCP
知识库支持文档分块、检索与后续向量化演进
平台提供 Agent、模型、工具、Skill、知识库、会话、审计和用量等完整管理模块
适用场景
<div>
<img width="1468" height="820" alt="微信图片_20260902172703_755_116" src="https://github.com/user-attachments/assets/35bfd071-b732-401e-8c9c-4ac9edb2f6d1" />
<img width="1468" height="820" alt="微信图片_20260902172703_754_116" src="https://github.com/user-attachments/assets/c743f250-bf55-429d-be84-0bd1b587fd7d" />
<img width="2936" height="1700" alt="微信图片_20260902172702_753_116" src="https://github.com/user-attachments/assets/2ab9835f-9915-44e1-b5cf-fdfa5a8fa7d2" />
<img width="2936" height="1700" alt="微信图片_20260902172702_752_116" src="https://github.com/user-attachments/assets/434be1a2-4d95-44ec-b1d0-da119a7dab2e" />
<img width="2936" height="1700" alt="微信图片_20260902172702_751_116" src="https://github.com/user-attachments/assets/2fe80166-790f-467b-b30c-e9a09e37a13c" />
<img width="2936" height="1700" alt="微信图片_20260902172702_750_116" src="https://github.com/user-attachments/assets/ed7e4b9f-aed5-4290-865d-8284e8755c72" />
<img width="2936" height="1700" alt="微信图片_20260902172702_749_116" src="https://github.com/user-attachments/assets/ee4d86be-ccac-41b1-865b-b7d586b9e0db" />
</div>
AgentForge 可用于企业知识助手、客服辅助、标书审查、文案生成、数据分析、运营助手、流程审批辅助、内部制度问答、数字员工等场景。

它的目标不是简单提供一个聊天窗口，而是帮助企业把分散的知识、工具、流程和业务规则，沉淀为可配置、可调用、可审计、可持续迭代的 AI Agent 能力。
