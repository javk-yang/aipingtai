# AgentForge 启动概览

## 本次启动

- 启动命令：`bash scripts/start-all.sh`
- 启动时间：2026-08-25 00:03（GMT+8）

## 服务状态

- MySQL：`127.0.0.1:3308`，已运行
- Redis：`127.0.0.1:6379`，已运行
- Agent 引擎：`127.0.0.1:8000`，已运行
- Java 后端：`127.0.0.1:8090`，进程已运行
- Vue 前端：`127.0.0.1:5173`，已就绪

## HTTP 检查

- 前端 `/`：HTTP 200
- Python `/health`：HTTP 200，LangGraph 引擎正常，当前 provider 为 deterministic
- Java `/health`：HTTP 200，返回 `status=UP`
- Java `/` 和 `/actuator/health`：HTTP 500。根路径及 Actuator 路径当前被统一异常处理器接管，不影响专用 `/health` 接口和主要业务接口；如需生产化，可单独修复 Actuator 暴露配置。

## 访问地址

- `http://127.0.0.1:5173`
- `http://172.20.10.5:5173`

## 备注

启动脚本仍在前台保持运行，以维持前端开发服务器；服务可直接通过上述地址访问。
