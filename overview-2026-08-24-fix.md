# AgentForge 本轮修复与回归报告

## 修复的问题

### 1. 会话删除后仍显示在列表中（用户反馈"删除不了"）
**根因**：后端 `ConversationService.delete()` 手动设置 `deletedAt` 后调用 `updateById`，但 MyBatis-Plus 的 `@TableLogic` 字段在 `updateById` 中会被框架忽略，导致 `deleted_at` 始终为 NULL；同时 `list()` 查询没有过滤 `status=3` 的软删记录，所以删除后的会话仍出现在列表中。

**修复**：
- `ConversationService.delete()` 改为使用 `conversationMapper.deleteById(c.getPkId())`，由 MyBatis-Plus 自动触发逻辑删除并填充 `deleted_at`。
- `ConversationService.list()` 增加 `.ne(Conversation::getStatus, 3)`，兼容历史未填充 `deleted_at` 的已删除记录。

**文件**：`backend/af-session-impl/src/main/java/com/agentforge/session/impl/service/ConversationService.java`

### 2. 聊天默认选中不可达的真实模型（用户截图显示"代码开发 · gpt5.6（默认）"，对话失败）
**根因**：数据库中 `is_default=1` 的模型是 `openai-compatible / gpt5.6`，当前环境访问 `api.openai.com` 经代理返回 502/超时，导致一打开聊天就失败。前端虽已改为 deterministic 优先，但浏览器可能加载旧代码；且后端默认模型也以数据库为准。

**修复**：
- 数据库中将 `deterministic` 模型（id=1）设为默认：
  ```sql
  UPDATE model_config SET is_default=0;
  UPDATE model_config SET is_default=1 WHERE provider='deterministic';
  ```
- 前端 `ChatView.vue` 保持 deterministic 优先选择策略：
  ```ts
  const def =
    enabled.find((m) => m.provider === 'deterministic') ??
    enabled.find((m) => m.isDefault === 1) ??
    enabled[0] ??
    list.find((m) => m.provider === 'deterministic') ??
    list[0]
  ```

**文件**：`frontend/src/views/workspace/chat/ChatView.vue`

## 服务状态

五端全部在线：
- MySQL 3308
- Redis 6379
- Python Agent 引擎 8000
- Java 后端 8080
- 前端 Vite 5173

访问地址：
- 局域网：`http://10.0.4.17:5173`
- 本机：`http://127.0.0.1:5173`
- 登录：`admin / Admin@2026`

## 回归测试

新增测试脚本：
- `tests/e2e-smoke.sh`：5 项核心冒烟测试
- `tests/e2e-full.sh`：13 项全模块回归测试

### e2e-smoke.sh 结果（5/5 PASS）
1. 登录成功
2. 默认模型为 deterministic
3. 会话创建、删除、删除后不再出现
4. 确定性模型聊天 SSE 完整（message_start → content_delta* → message_done）
5. 真实模型失败正确降级（message_start → error，无 message_done）

### e2e-full.sh 结果（13/13 PASS）
覆盖：登录、token 刷新、当前用户、模型默认策略、草稿模型测试、会话 CRUD、确定性聊天、真实模型失败降级、工具列表、技能列表、知识库列表。

## 使用说明

1. **打开页面后如果模型下拉框仍显示真实模型**：请强制刷新浏览器（Windows: `Ctrl+F5`，Mac: `Cmd+Shift+R`），因为浏览器可能缓存了旧 JS。
2. **默认已经可用**： deterministic 离线模型会立即回复，无需外网。
3. **切换到真实模型**：在「模型管理」里把可访问的 OpenAI 兼容模型设为默认；当前环境因代理限制无法访问 `api.openai.com`。
4. **重新运行测试**：
   ```bash
   /Users/jack.yang/WorkBuddy/开发全流程体验/tests/e2e-smoke.sh
   /Users/jack.yang/WorkBuddy/开发全流程体验/tests/e2e-full.sh
   ```

## 后续可继续优化项

- 前端聊天错误态增加"重试"和"切换到离线模型"按钮。
- 真实 Provider 增加 HTTP streaming delta、重试、退避、熔断、限流。
- 统一多租户来源，移除部分接口对 `X-Tenant-Id` 的信任。
- 将 `knowledge_doc.content` 等 schema 变更纳入可追踪迁移脚本。
