# AgentForge 前端入口纠正记录

## 根因

`127.0.0.1:5173` 被另一个本地工作区的 Vite 开发服务占用，进程工作目录为：

`/Users/jack.yang/WorkBuddy/2026-08-27-12-17-34/web`

因此此前通过 5173 打开的是其他平台，并非 AgentForge。

## 已完成修复

- 将 AgentForge Vite 开发端口从 `5173` 调整为独立端口 `5175`。
- 同步更新 `frontend/vite.config.ts` 与 `scripts/start-all.sh` 中的前端端口。
- 已在 AgentForge 项目目录启动专属 Vite 服务。
- 验证 `http://127.0.0.1:5175` 返回 HTTP 200，且页面入口指向本项目的 `/src/main.ts`。

## 正确访问地址

- 本机：`http://127.0.0.1:5175`

后端仍为：

- Java Backend：`http://127.0.0.1:8090`
- Agent Engine：`http://127.0.0.1:8000`
