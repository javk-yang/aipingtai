# AgentForge 启动概览

## 启动命令

```bash
bash scripts/start-all.sh --restart-java
```

## 服务状态

- MySQL：`127.0.0.1:3308`，已运行
- Redis：`127.0.0.1:6379`，已运行
- Agent 引擎：`127.0.0.1:8000`，已运行
- Java 后端：`127.0.0.1:8090`，已重启并就绪
- Vue 前端：`127.0.0.1:5173`，已运行

## HTTP 检查

```text
frontend=200
java=200
engine=200
```

## 访问地址

- `http://127.0.0.1:5173`
- `http://10.0.4.17:5173`
