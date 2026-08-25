# AgentForge 持续优化阶段概览

## 本轮完成

- 修复后端 Java 语法结构错误：补回工具、技能的 `setEnabled` 方法，以及知识库删除接口的方法签名与边界。
- 完成后端构建：`backend/` 下 `mvn -q -DskipTests package` 通过。
- 修复前端类型问题：补充知识库检索请求类型导入，补回知识库删除 API。
- 处理 Vite 构建产物清理阈值：设置 `build.emptyOutDir=false`，前端 `vue-tsc + vite build` 已通过。
- 补齐 Python 知识库删除路由：`DELETE /api/knowledge/docs/{doc_id}`。
- 将当前运行库 `knowledge_doc` 补迁移 `content MEDIUMTEXT` 字段，使知识库详情读取与编辑态数据链路可用。
- 启动并验证五端：MySQL 3308、Redis 6379、Python 引擎 8000、Java 8080、前端 5173。

## 关键验证结果

| 验证项 | 结果 |
| --- | --- |
| 前端首页 | HTTP 200 |
| Java 自定义健康接口 `/health` | HTTP 200 |
| Python 引擎 `/health` | HTTP 200 |
| 管理员 JWT 登录 | HTTP 200 |
| 模型列表 `/api/models` | HTTP 200 |
| 工具列表 `/api/tools` | HTTP 200 |
| 技能列表 `/api/skills` | HTTP 200 |
| 知识库列表、详情、检索、删除 | HTTP 200 |
| 聊天 SSE | HTTP 200，收到 `message_start`、多段 `content_delta`、`message_done` |
| 聊天实际模型 | `agentforge-dev-model` |

## 关键变更文件

- `backend/af-agent/src/main/java/com/agentforge/agent/tool/service/ToolRegistryService.java`
- `backend/af-agent/src/main/java/com/agentforge/agent/skill/service/SkillRegistryService.java`
- `backend/af-agent/src/main/java/com/agentforge/agent/knowledge/controller/KnowledgeController.java`
- `backend/af-agent/src/main/java/com/agentforge/agent/knowledge/service/KnowledgeService.java`
- `backend/sql/01-mysql-schema.sql`
- `frontend/src/api/knowledge.ts`
- `frontend/vite.config.ts`
- `agent-engine/app/main.py`

## 当前访问方式

- 前端：`http://localhost:5173`
- 账号：`admin`
- 密码：`Admin@2026`
- 一键启动：`bash scripts/start-all.sh`

## 遗留说明

- `/actuator/health` 当前未启用，生产验证应使用项目自定义 `/health`。
- 既有历史知识文档如果此前未保存原始正文，重索引接口会按设计返回“无原始正文”；新建或编辑后的文档会保存正文。
- 当前环境为离线确定性模型，模型管理与聊天模型选择链路已经打通，可在工作台模型管理页配置 OpenAI-compatible Provider。
