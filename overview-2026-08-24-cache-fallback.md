# AgentForge 默认模型与浏览器缓存修复总结

## 问题现象
用户反馈"还是存在不能调用模型"，截图显示：
- 聊天页底部模型选择器显示 **代码开发 · gpt5.6**（真实模型）
- 发送"你好"后返回 **「[生成失败] 生成失败，请稍后重试」**

## 根因分析
1. **浏览器缓存了旧 JS**：此前已修改 `ChatView.vue` 让 deterministic 离线模型优先默认，但用户浏览器仍加载旧版本 JS，导致 `loadModels()` 按旧逻辑选中 `isDefault === 1` 的真实模型。
2. **真实模型在当前环境不可达**：`api.openai.com` 经本地透明代理返回 502/超时，因此选中真实模型必然失败。
3. **缺少兜底保护**：即使前端代码正确，若用户手动切换到真实模型或缓存未刷新，仍会触发失败。

## 修复内容

### 1. 前端禁用开发模式缓存
- `frontend/vite.config.ts`：server.headers 增加 `Cache-Control: no-store, no-cache, must-revalidate, max-age=0` 和 `Pragma: no-cache`
- `frontend/index.html`：增加 `Cache-Control / Pragma / Expires` no-cache meta 标签

### 2. 发送前自动兜底切换到离线模型
- `frontend/src/views/workspace/chat/ChatView.vue`
  - 新增 `ensureReachableModel()`：若当前选中非 deterministic 的真实模型，且存在可用的 deterministic 模型，则自动切换。
  - 新增 `modelSwitchTip` 提示：切换时在输入区上方显示黄色提示条，告知用户已自动切换及原因。
  - `send()` 调用 `ensureReachableModel()` 后再发送请求。

### 3. 后端默认模型确认
- 数据库 `model_config` 中 `is_default=1` 已确认是 `内置确定性模型(离线演示)`（provider=deterministic）。
- Java 后端已用最新 jar 重启。

## 验证结果

### 服务状态
- MySQL(3308)、Redis(6379)、Python Agent 引擎(8000)、Java 后端(8080)、前端 Vite(5173) 全部在线。
- 访问地址：
  - 局域网：`http://10.0.4.17:5173`
  - 本机：`http://127.0.0.1:5173`

### 测试脚本
新增/更新两个测试脚本：
- `tests/e2e-smoke.sh`：5 项核心冒烟测试
- `tests/e2e-full.sh`：13 项全模块回归测试

### 本轮测试结果
**冒烟测试 5/5 PASS**：登录、默认模型策略、会话创建删除、确定性模型聊天 SSE、真实模型失败降级。

**全量测试 13/13 PASS**：认证、模型管理、会话与聊天、工具技能、知识库全部通过。

## 仍需用户配合的一步
由于浏览器缓存了旧 JS，**请强制刷新页面**：
- Windows / Linux：`Ctrl + F5` 或 `Ctrl + Shift + R`
- macOS：`Cmd + Shift + R`

刷新后：
1. 底部模型选择器应显示 **内置确定性模型(离线演示) · agentforge-dev-model**。
2. 发送消息应能正常收到回复。
3. 若手动切换回真实模型并发送，系统会自动切回离线模型并显示提示条。

## 环境限制说明
当前运行环境无法直接访问 `api.openai.com` 等上游模型端点（经代理返回 502/超时），因此**真实大模型暂时仍无法调用**。这不是平台代码问题，而是网络/代理限制。离线确定性模型已可完整对话。

## 后续持续优化方向
1. 在能访问上游的网络下，对 OpenAI / DeepSeek / Qwen / Ollama 等 Provider 做真实成功回归。
2. 增加模型连通性预检 API，模型管理页面可一键测试。
3. 优化真实模型错误提示，显示具体 Provider 和 HTTP 状态码。
4. 将 `knowledge_doc.content` 等 schema 变更纳入幂等迁移脚本。
5. 完善多租户：彻底移除对 `X-Tenant-Id` 请求头的信任。
