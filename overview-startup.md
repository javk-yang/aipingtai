# AgentForge 启动概览

## 启动结果

已执行：

```bash
bash scripts/start-all.sh --restart-java
```

五端服务均已就绪：

- MySQL：`127.0.0.1:3308`
- Redis：`127.0.0.1:6379`
- Agent 引擎：`127.0.0.1:8000`
- Java 后端：`127.0.0.1:8090`
- Vue 前端：`127.0.0.1:5173`

## 健康检查

```text
frontend=200
java=200
engine=200
```

## 访问地址

- 前端：`http://127.0.0.1:5173`
- 局域网访问：`http://10.0.4.17:5173`
- Java 健康接口：`http://127.0.0.1:8090/health`
- Agent 引擎健康接口：`http://127.0.0.1:8000/health`

Java 后端已按启动参数重新加载最新代码。
