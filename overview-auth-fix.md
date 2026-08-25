# 登录过期问题修复概览

## 根因

前端登录成功后调用 `setTokens()` 只把 accessToken 和 refreshToken 保存在内存变量中，没有持久化 refreshToken。页面刷新或前端开发服务热更新后，内存被清空，路由守卫无法恢复会话，于是显示“登录已过期，请重新登录”。

## 修复

修改文件：

- `frontend/src/utils/request.ts`

`setTokens(access, refresh)` 现在会：

1. accessToken 继续只存内存；
2. refreshToken 写入 `localStorage` 的 `af-refresh-token`；
3. 登录成功、刷新成功都统一执行持久化；
4. 登出和刷新失败继续清理 refreshToken。

## 验证

- 前端 `vue-tsc --noEmit && vite build`：通过
- 登录接口：HTTP 200
- `/api/auth/refresh`：HTTP 200，refresh token 轮换正常
- `/api/auth/me`：HTTP 200
- `/api/models`：HTTP 200
- `/api/tools`：HTTP 200
- `/api/skills`：HTTP 200
- 前端 `http://localhost:5173`：HTTP 200
- Python 引擎 `http://127.0.0.1:8000/health`：HTTP 200

## 使用提示

修复前已经打开过页面的浏览器，浏览器里没有旧 refreshToken。请在当前页面执行一次硬刷新后重新登录：

- macOS：`Command + Shift + R`
- 或打开开发者工具后选择“清空站点数据”，再访问 `http://localhost:5173`

修复后再次刷新页面时，前端会自动调用 `/api/auth/refresh` 恢复登录，不应再立即提示登录过期。
