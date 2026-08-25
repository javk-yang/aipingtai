# 工具技能页面与工作台滚动修复记录

## 用户反馈

1. 工具与技能页面中的功能和数据打不开，页面可能表现为乱码或空白。
2. 聊天内容向下滚动时，左侧工作台导航栏跟随滚动。

## 根因与修复

### 工具与技能页面

- 实际后端接口正常：
  - `/api/tools`：HTTP 200，返回 4 个工具。
  - `/api/skills`：HTTP 200，返回 3 个技能。
- 页面 CSS 中 `.grid-empty,.tools-loading` 规则少了一个 `)`，导致 Vite/PostCSS 报 `Unclosed bracket`，页面构建产物异常。
- 已补齐 `var(--color-text-tertiary)` 的闭合括号。
- 路由权限从 `agent:skill:read` 调整为工具页面实际需要的 `agent:tool:read`，避免权限配置不一致导致页面被拦截。

### 左侧导航滚动

已调整以下布局容器：

- `WorkspaceView.vue`
  - 外层 shell 使用 `height: 100dvh`、`overflow: hidden`。
  - 侧边栏固定高度并隐藏外层溢出。
  - 主区补充 `min-height: 0`。
- `ChatView.vue`
  - 聊天页使用固定高度和 `overflow: hidden`。
  - 会话侧栏固定高度并独立滚动会话列表。
  - 消息主区补充 `min-height: 0` 和 `overflow: hidden`。
  - 消息区自身继续通过 `.msg-area { overflow-y: auto; }` 滚动。

## 验证结果

- `vue-tsc --noEmit`：通过。
- `vite build`：通过。
- 工具页面路由 `/workspace/tools`：HTTP 200。
- 工具数据接口：HTTP 200，4 条数据。
- 技能数据接口：HTTP 200，3 条数据。
- 已重启本地前端并加载最新代码。

## 访问地址

- 前端：http://127.0.0.1:5173
- 工具与技能：http://127.0.0.1:5173/workspace/tools
- 聊天工作台：http://127.0.0.1:5173/workspace/chat
