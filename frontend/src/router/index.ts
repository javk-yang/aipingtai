/**
 * 路由表 —— meta 驱动的权限模型
 *
 * 与后端 SecurityProperties 白名单 + PermissionAspect 的对应：
 * - requiresAuth: false → 白名单路由，未登录可访问（如 /login），对应后端白名单
 * - requiresAuth: true  → 登录守卫拦截，未登录重定向 /login?redirect=xxx
 * - perm: 'agent:tool:call' → 权限守卫检查，对应后端 @RequirePermission
 *
 * 前端做菜单/按钮级控制是体验优化，安全边界永远在后端——
 * 前端只是"藏"，后端才是"拦"。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 是否需要登录（false = 白名单路由） */
    requiresAuth?: boolean
    /** 所需权限码：不填则只要求登录 */
    perm?: string
    /** 页面标题 */
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/workspace',
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { requiresAuth: false, title: '登录 · AgentForge' },
  },
  {
    path: '/workspace',
    component: () => import('@/views/workspace/WorkspaceView.vue'),
    meta: { requiresAuth: true, title: '工作台 · AgentForge' },
    redirect: '/workspace/chat',
    children: [
      {
        path: 'chat',
        name: 'workspace-chat',
        component: () => import('@/views/workspace/chat/ChatView.vue'),
        meta: { requiresAuth: true, title: '会话工作台 · AgentForge' },
      },
      {
        path: 'knowledge',
        name: 'workspace-knowledge',
        component: () => import('@/views/workspace/knowledge/KnowledgeView.vue'),
        meta: { requiresAuth: true, perm: 'agent:knowledge:read', title: '知识库 · AgentForge' },
      },
      {
        path: 'agents',
        name: 'workspace-agents',
        component: () => import('@/views/workspace/agents/AgentManageView.vue'),
        meta: { requiresAuth: true, perm: 'agent:agent:read', title: '智能体管理 · AgentForge' },
      },
      {
        path: 'tools',
        name: 'workspace-tools',
        component: () => import('@/views/workspace/tools/ToolsView.vue'),
        meta: { requiresAuth: true, perm: 'agent:tool:read', title: '工具与技能 · AgentForge' },
      },
      {
        path: 'models',
        name: 'workspace-models',
        component: () => import('@/views/workspace/models/ModelManageView.vue'),
        meta: { requiresAuth: true, perm: 'agent:model:read', title: '模型管理 · AgentForge' },
      },
      {
        path: 'obs',
        name: 'workspace-obs',
        component: () => import('@/views/workspace/obs/ObservabilityView.vue'),
        meta: { requiresAuth: true, perm: 'agent:usage:read', title: '可观测 · AgentForge' },
      },
    ],
  },
  {
    path: '/demo/chat',
    name: 'demo-chat',
    component: () => import('@/views/demo/ChatDemoView.vue'),
    meta: { requiresAuth: true, title: 'SSE 流式验证 · AgentForge' },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { requiresAuth: true, title: '无权访问 · AgentForge' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/login/RegisterView.vue'),
    meta: { requiresAuth: false, title: '注册 · AgentForge' },
  },
  {
    path: '/forgot-password',
    name: 'forgot-password',
    component: () => import('@/views/login/ForgotPasswordView.vue'),
    meta: { requiresAuth: false, title: '找回密码 · AgentForge' },
  },
  // P11 会继续追加：智能体编排、会话工作台……
  {
    path: '/:pathMatch(.*)*',
    redirect: '/workspace',
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
