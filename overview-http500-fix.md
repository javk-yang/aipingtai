# AgentForge HTTP 500 / 页面服务异常修复

## 根因

前端通过 `http://10.0.4.17:5173` 访问时，Java 后端 CORS 只允许 `localhost` 和 `127.0.0.1`，局域网来源被 Spring 返回 `Invalid CORS request`，前端统一翻译为服务异常。

## 修复

- 修改 `backend/af-common/src/main/java/com/agentforge/common/config/WebMvcConfig.java`
- 增加本地局域网 HTTP 来源白名单：10.x、192.168.x、172.16-31.x
- 重新执行 Maven 构建并通过
- 彻底重启 Java 服务，避免旧 fat-jar 继续运行
- 验证局域网来源健康接口和登录接口

## 验证结果

- `Origin: http://10.0.4.17:5173` 调用 Java `/health` → HTTP 200
- `Origin: http://127.0.0.1:5173` 调用 Java `/health` → HTTP 200
- 登录接口 → HTTP 200，管理员账号登录成功
- 登录字段已确认使用 `identifier`，不是 `username`
- 前端页面 → HTTP 200

## 访问

```text
http://10.0.4.17:5173
```

本机也可用：

```text
http://127.0.0.1:5173
```
