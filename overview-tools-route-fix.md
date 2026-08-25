# 工具与技能入口修复概览

## 问题
工作台侧边栏已经配置了 `/workspace/tools`，但路由表没有注册该子路由。点击后会命中通配路由并重定向到 `/workspace`，随后工作台默认重定向到 `/workspace/chat`，因此表现为进入会话窗口。

## 修复
在 `frontend/src/router/index.ts` 注册：

```ts
{
  path: 'tools',
  name: 'workspace-tools',
  component: () => import('@/views/workspace/tools/ToolsView.vue'),
  meta: {
    requiresAuth: true,
    perm: 'agent:skill:read',
    title: '工具与技能 · AgentForge',
  },
}
```

## 验证
- Vue 类型检查通过。
- Vite 生产构建通过。
- 构建产物已出现 `ToolsView` 路由页面 chunk。

刷新前端页面后，点击“工具与技能”应进入工具/技能治理页面，而不是会话窗口。
