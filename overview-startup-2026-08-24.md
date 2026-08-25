# AgentForge 启动与可用性修复总结（2026-08-24）

## 一、当前运行状态（已全部就绪）

| 服务 | 地址 | 状态 |
|------|------|------|
| 前端 Vite | http://127.0.0.1:5173 / http://10.0.4.17:5173 | 200，已验证 |
| Java 后端 | http://127.0.0.1:8080（/health 200） | 运行中 |
| Python Agent 引擎 | http://127.0.0.1:8000（/health 200） | 运行中 |
| MySQL | 127.0.0.1:3308 | 运行中 |
| Redis | 127.0.0.1:6379 | 运行中 |

浏览器入口：**http://10.0.4.17:5173**（局域网）/ **http://127.0.0.1:5173**（本机）

已验证的端到端链路：
- 管理员登录 `admin / Admin@2026` → 返回 accessToken（200）
- 经 Vite 代理 `/api/models` → 200，返回真实模型(id=2) 与确定性模型(id=1)
- 聊天 `modelConfigId=1`（确定性）→ 完整 SSE：`message_start → content_delta* → message_done`
- 聊天不传 `modelConfigId`（后端默认）→ 已回退到确定性模型，正常返回
- 聊天 `modelConfigId=2`（真实模型）→ `message_start → error` 优雅失败，不再伪装成成功回复

## 二、本轮关键修复

### 1. 聊天默认模型策略（解决“对话框用不了/模型调用不了”）
- **前端** `frontend/src/views/workspace/chat/ChatView.vue`
  - 默认模型选择优先级改为：**启用的 deterministic 离线模型 > 标记默认 > 第一个启用 > 任意 deterministic > 第一个**。
  - 打开对话框即默认选中离线确定性模型，保证开箱即可对话；用户仍可手动切换到真实模型。
- **后端** `backend/.../service/ModelConfigService.java`
  - `defaultConfigId()` 增加确定性模型优先回退：当未显式指定模型时，优先使用 `deterministic` 离线模型，避免一个不可达的真实 Provider 让所有默认请求直接失败。

### 2. 真实模型失败语义（此前会把失败伪装成正常回复）
- 真实 Provider 调用失败（超时/连接拒绝/401/403/404/429/5xx/格式异常）统一抛出结构化 `ModelCallError`，Python 发送 `error` 事件，Java 标记 assistant 消息失败并返回 `code:3303`，前端显示“[生成失败] …”。
- 本次环境到 `api.openai.com` 经本地代理返回 502/无法连接，因此真实模型会明确报错而非卡死。

### 3. 构建与重启
- 前端 `vue-tsc` 类型检查 + `vite build` 通过。
- Java 用正确 Maven 路径重新打包 `af-bootstrap-1.0.0-SNAPSHOT.jar` 并热替换运行。

### 4. 启动脚本健壮性
- `scripts/start-all.sh`
  - 关键进程以 `nohup &` 拉起，脚本末尾 `exec sleep 31536000` 保活，避免启动任务结束后子进程被回收（此前“服务随后退出”的根因）。
  - 已支持 `0.0.0.0` 绑定与局域网地址输出。

## 三、仍存在的环境限制（非代码缺陷）

- **真实大模型无法连通**：当前运行环境访问 `api.openai.com` 等上游经代理返回 502 / 超时，因此默认只保证离线确定性模型可用。需在可访问上游的网络环境（或配置本地 Ollama / 可达的 OpenAI 兼容端点）下切换真实模型才能成功调用。
- 多租户部分接口仍信任客户端 `X-Tenant-Id`；`knowledge_doc.content` 等字段未纳入完整幂等迁移；真实流式 delta / 重试退避 / 熔断限流待后续完善（均不影响当前离线对话可用性）。

## 四、访问方式

浏览器打开 **http://10.0.4.17:5173** → 用 `admin / Admin@2026` 登录 → 进入「工作台 / 聊天」即可对话（默认已选离线模型，立即可用）。
