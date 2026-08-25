<script setup lang="ts">
/**
 * ChatView —— 会话工作台（P11 核心页）
 *
 * 三区布局：
 *   会话列表：新建 / 切换 / 删除（删除回退到新会话）
 *   消息流：user 右对齐、assistant 左对齐（AfMarkdown 渲染 + 打字机光标）
 *           工具调用 / 技能调用以"时间线卡片"嵌入 assistant 消息下方
 *   输入区：Enter 发送 / Shift+Enter 换行 / 生成中可中断（AbortController）
 *
 * 流式语言（SSE）：
 *   message_start → content_delta* → (tool_call_* | skill_call_*)* → message_done
 *   任一步失败 → error 事件，assistant 消息标记 status=2
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { conversationApi, chatStream } from '@/api/session'
import { modelsApi } from '@/api/models'
import { agentsApi, type AgentResponse } from '@/api/agents'
import type {
  ChatStreamEvent,
  ConversationResponse,
  SkillCallView,
  ToolCallView,
} from '@/types/chat'
import type { ModelConfig } from '@/types/model'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'

/** 消息渲染模型（本地聚合，非后端 MessageResponse 直接形态） */
interface ChatMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
  /** 0 流式中 1 完成 2 失败 */
  status: number
  model?: string | null
  tokenOutput?: number
}

interface ThinkingStep {
  label: string
  detail?: string
  phase?: 'analysis' | 'action' | 'knowledge' | 'generation' | 'complete'
  status: 'running' | 'done' | 'error'
}

/* ---------- 状态 ---------- */
const conversations = ref<ConversationResponse[]>([])
const activeId = ref<string | null>(null)
const messages = ref<ChatMsg[]>([])
const thinkingSteps = ref<Record<number, ThinkingStep[]>>({})
const expandedThinking = ref<Record<number, boolean>>({})
const toolCalls = ref<Record<number, ToolCallView[]>>({})
const skillCalls = ref<Record<number, SkillCallView[]>>({})
const input = ref('')
const streaming = ref(false)
const scrollEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)

/* ---------- 模型选择（#87：大模型可选择） ---------- */
const models = ref<ModelConfig[]>([])
const activeModelId = ref<number | null>(null)
const agents = ref<AgentResponse[]>([])
const activeAgentId = ref<number | null>(null)
const modelsLoading = ref(false)
const modelsError = ref('')
const conversationError = ref('')
const conversationLoading = ref(false)
const modelSwitchTip = ref('')
const selectionMode = ref(false)
const selectedConversationIds = ref<string[]>([])
const deletingConversations = ref(false)

const allConversationsSelected = computed(
  () => conversations.value.length > 0 && selectedConversationIds.value.length === conversations.value.length,
)

function syncConversationSelection() {
  const available = new Set(conversations.value.map((c) => c.id))
  selectedConversationIds.value = selectedConversationIds.value.filter((id) => available.has(id))
}

function toggleConversationSelection(id: string) {
  if (selectedConversationIds.value.includes(id)) {
    selectedConversationIds.value = selectedConversationIds.value.filter((item) => item !== id)
  } else {
    selectedConversationIds.value = [...selectedConversationIds.value, id]
  }
}

function toggleSelectAllConversations() {
  selectedConversationIds.value = allConversationsSelected.value
    ? []
    : conversations.value.map((c) => c.id)
}

function toggleSelectionMode() {
  selectionMode.value = !selectionMode.value
  if (!selectionMode.value) selectedConversationIds.value = []
}

function selectedConversationLabel() {
  return selectedConversationIds.value.length === 1
    ? '确认删除选中的 1 个会话？删除后不可恢复。'
    : `确认删除选中的 ${selectedConversationIds.value.length} 个会话？删除后不可恢复。`
}

async function refreshAfterConversationDelete(deletedIds: string[], createIfEmpty = true) {
  const deletedActive = activeId.value != null && deletedIds.includes(activeId.value)
  await loadConversations()
  syncConversationSelection()
  if (deletedActive) {
    if (conversations.value.length) {
      await openConversation(conversations.value[0].id)
    } else if (createIfEmpty) {
      await newConversation()
    } else {
      activeId.value = null
      messages.value = []
      toolCalls.value = {}
      skillCalls.value = {}
    }
  }
}

async function removeConversation(id: string) {
  if (deletingConversations.value) return
  if (!window.confirm('确认删除该会话？删除后不可恢复。')) return
  conversationError.value = ''
  deletingConversations.value = true
  try {
    await conversationApi.remove(id)
    await refreshAfterConversationDelete([id])
  } catch (e) {
    conversationError.value = e instanceof Error ? e.message : '删除会话失败'
  } finally {
    deletingConversations.value = false
  }
}

async function removeSelectedConversations() {
  if (deletingConversations.value || selectedConversationIds.value.length === 0) return
  const ids = [...selectedConversationIds.value]
  if (!window.confirm(selectedConversationLabel())) return
  conversationError.value = ''
  deletingConversations.value = true
  try {
    const results = await Promise.allSettled(ids.map((id) => conversationApi.remove(id)))
    const deletedIds = ids.filter((_, index) => results[index].status === 'fulfilled')
    const failedCount = results.length - deletedIds.length
    if (failedCount > 0) {
      conversationError.value = `${failedCount} 个会话删除失败，请稍后重试。`
    }
    selectedConversationIds.value = []
    await refreshAfterConversationDelete(deletedIds, false)
  } finally {
    deletingConversations.value = false
  }
}

async function removeAllConversations() {
  if (deletingConversations.value || conversations.value.length === 0) return
  const ids = conversations.value.map((c) => c.id)
  if (!window.confirm(`确认清空全部 ${ids.length} 个会话？删除后不可恢复。`)) return
  conversationError.value = ''
  deletingConversations.value = true
  try {
    const results = await Promise.allSettled(ids.map((id) => conversationApi.remove(id)))
    const deletedIds = ids.filter((_, index) => results[index].status === 'fulfilled')
    const failedCount = results.length - deletedIds.length
    if (failedCount > 0) {
      conversationError.value = `${failedCount} 个会话删除失败，请稍后重试。`
    }
    selectedConversationIds.value = []
    await refreshAfterConversationDelete(deletedIds, false)
  } finally {
    deletingConversations.value = false
  }
}
async function loadAgents() {
  try {
    const list = await agentsApi.list()
    agents.value = list.filter((agent) => agent.status === 2)
    const defaultAgent = agents.value.find((agent) => agent.isDefault === 1)
    activeAgentId.value = defaultAgent?.id ?? null
  } catch {
    agents.value = []
    activeAgentId.value = null
  }
}

async function loadModels() {
  modelsLoading.value = true
  modelsError.value = ''
  try {
    const list = await modelsApi.list()
    models.value = list
    // 默认选中：优先用户设置的默认模型，其次是第一个启用模型，最后兜底列表第一项。
    // 真实模型已连通，不再强制切到离线模型。
    const enabled = list.filter((m) => m.enabled === 1)
    const def =
      enabled.find((m) => m.isDefault === 1) ??
      enabled[0] ??
      list[0]
    activeModelId.value = def ? def.id : null
  } catch (e) {
    models.value = []
    activeModelId.value = null
    modelsError.value = e instanceof Error ? e.message : '模型列表加载失败'
  } finally {
    modelsLoading.value = false
  }
}

/** 当前选中模型名称（用于输入框旁展示） */
const activeModelName = computed(() => {
  const m = models.value.find((x) => x.id === activeModelId.value)
  return m ? `${m.name} · ${m.model}` : '默认模型'
})

const activeAgentName = computed(() => {
  const agent = agents.value.find((item) => item.id === activeAgentId.value)
  return agent ? agent.name : '默认助手'
})

/** 中断控制器（停止生成） */
let abortCtrl: AbortController | null = null

/* ---------- 会话 CRUD ---------- */
async function loadConversations(): Promise<boolean> {
  conversationLoading.value = true
  conversationError.value = ''
  try {
    const page = await conversationApi.list(1, 50)
    conversations.value = page.records
    syncConversationSelection()
    return true
  } catch (e) {
    conversationError.value = e instanceof Error ? e.message : '会话列表加载失败'
    return false
  } finally {
    conversationLoading.value = false
  }
}

async function openConversation(id: string) {
  conversationError.value = ''
  try {
    activeId.value = id
    toolCalls.value = {}
    skillCalls.value = {}
    const list = await conversationApi.messages(id)
    messages.value = list.map((m) => ({
      id: m.id,
      role: (m.role === 'user' || m.role === 'assistant' ? m.role : 'assistant') as ChatMsg['role'],
      content: m.content ?? '',
      status: m.status,
      model: m.model,
      tokenOutput: m.tokenOutput,
    }))
    scrollBottom()
  } catch (e) {
    conversationError.value = e instanceof Error ? e.message : '会话消息加载失败'
  }
}

async function newConversation() {
  conversationError.value = ''
  try {
    const conv = await conversationApi.create()
    await loadConversations()
    await openConversation(conv.id)
  } catch (e) {
    conversationError.value = e instanceof Error ? e.message : '新建会话失败'
  }
}

/* ---------- 发送 / 流式 ---------- */
function pushUser(text: string) {
  messages.value.push({
    id: -Date.now(),
    role: 'user',
    content: text,
    status: 1,
  })
}

function pushAssistant() {
  const id = -Date.now()
  messages.value.push({
    id,
    role: 'assistant',
    content: '',
    status: 0,
  })
  thinkingSteps.value[id] = [
    { label: '正在分析你的问题', phase: 'analysis', status: 'running' },
  ]
  expandedThinking.value[id] = true
  return id
}

function setThinkingStep(
  messageId: number,
  label: string,
  detail?: string,
  phase: ThinkingStep['phase'] = 'action',
) {
  const steps = thinkingSteps.value[messageId] ?? []
  const current = steps[steps.length - 1]
  if (current?.status === 'running' && current.label !== label) current.status = 'done'
  const existing = steps.find((step) => step.label === label && step.status === 'running')
  if (existing) {
    if (detail) existing.detail = detail
    if (phase) existing.phase = phase
  } else {
    steps.push({ label, detail, phase, status: 'running' })
  }
  thinkingSteps.value[messageId] = steps
}

function updateThinkingStep(
  messageId: number,
  label: string,
  status: 'done' | 'error',
  detail?: string,
) {
  const steps = thinkingSteps.value[messageId] ?? []
  const step = [...steps].reverse().find((item) => item.label === label)
  if (step) {
    step.status = status
    if (detail) step.detail = detail
  } else {
    steps.push({ label, detail, phase: 'action', status })
  }
  thinkingSteps.value[messageId] = steps
}

function finishThinking(messageId: number, status: 'done' | 'error') {
  const steps = thinkingSteps.value[messageId] ?? []
  const current = steps[steps.length - 1]
  if (current?.status === 'running') current.status = status
  if (status === 'done') {
    const completed = steps.some((step) => step.phase === 'complete' && step.status === 'done')
    if (!completed) steps.push({ label: '回答已完成', phase: 'complete', status: 'done' })
  }
  thinkingSteps.value[messageId] = steps
}

function toggleThinking(messageId: number) {
  expandedThinking.value[messageId] = !expandedThinking.value[messageId]
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  autoResize()

  pushUser(text)
  const assistantId = pushAssistant()
  streaming.value = true
  const hadConv = !!activeId.value

  abortCtrl = new AbortController()
  try {
    await chatStream(
      {
        content: text,
        conversationId: activeId.value ?? undefined,
        agentId: activeAgentId.value ?? undefined,
        modelConfigId: activeModelId.value ?? undefined,
      },
      (e: ChatStreamEvent) => {
        const msg = messages.value.find((m) => m.id === assistantId) ?? messages.value[messages.value.length - 1]
        if (!msg) return

        if (e.type === 'message_start') {
          setThinkingStep(msg.id, activeAgentId.value ? '正在加载 Agent 配置' : '正在选择模型', activeAgentName.value, 'analysis')
          if (e.data?.messageId) {
            // 临时 id → 真实 id：把工具/技能时间线迁移到真实 id 下
            const tools = toolCalls.value[assistantId]
            const skills = skillCalls.value[assistantId]
            if (tools) toolCalls.value[e.data.messageId] = tools
            if (skills) skillCalls.value[e.data.messageId] = skills
            delete toolCalls.value[assistantId]
            delete skillCalls.value[assistantId]
            msg.id = e.data.messageId
            thinkingSteps.value[e.data.messageId] = thinkingSteps.value[assistantId] ?? []
            expandedThinking.value[e.data.messageId] = expandedThinking.value[assistantId] ?? true
            delete thinkingSteps.value[assistantId]
            delete expandedThinking.value[assistantId]
          }
        } else if (e.type === 'content_delta' && e.data?.delta) {
          setThinkingStep(msg.id, '正在生成回答', undefined, 'generation')
          msg.content += e.data.delta
        } else if (e.type === 'tool_call_start' && e.data?.callId) {
          const toolStepLabel = e.data.toolCode === 'knowledge_search' ? '正在检索知识库' : '正在调用工具'
          const toolStepPhase: ThinkingStep['phase'] = e.data.toolCode === 'knowledge_search' ? 'knowledge' : 'action'
          setThinkingStep(msg.id, toolStepLabel, e.data.toolName ?? e.data.toolCode, toolStepPhase)
          const list = toolCalls.value[msg.id] ?? []
          list.push({
            callId: e.data.callId,
            toolCode: e.data.toolCode ?? '',
            toolName: e.data.toolName ?? e.data.toolCode ?? 'tool',
            arguments: (e.data.arguments ?? {}) as Record<string, unknown>,
            status: 'running',
            durationMs: 0,
          })
          toolCalls.value[msg.id] = list
        } else if (
          (e.type === 'tool_call_result' || e.type === 'tool_call_error') &&
          e.data?.callId
        ) {
          const call = (toolCalls.value[msg.id] ?? []).find((c) => c.callId === e.data?.callId)
          if (call) {
            call.status = e.data.status ?? (e.type === 'tool_call_result' ? 'success' : 'error')
            call.result = e.data.result
            call.durationMs = e.data.durationMs ?? 0
            call.errorMessage = e.data.errorMessage
            updateThinkingStep(
              msg.id,
              call.toolCode === 'knowledge_search' ? '正在检索知识库' : '正在调用工具',
              e.type === 'tool_call_result' ? 'done' : 'error',
              e.type === 'tool_call_result' ? `${call.toolName} 已完成` : call.errorMessage,
            )
          }
        } else if (e.type === 'skill_call_start' && e.data?.callId) {
          setThinkingStep(msg.id, '正在执行技能', e.data.skillName ?? e.data.skillCode, 'action')
          const list = skillCalls.value[msg.id] ?? []
          list.push({
            callId: e.data.callId,
            skillCode: e.data.skillCode ?? '',
            skillName: e.data.skillName ?? e.data.skillCode ?? 'skill',
            skillVersion: e.data.skillVersion,
            callArgs: e.data.callArgs,
            status: 'running',
            durationMs: 0,
          })
          skillCalls.value[msg.id] = list
        } else if (
          (e.type === 'skill_call_result' || e.type === 'skill_call_error') &&
          e.data?.callId
        ) {
          const call = (skillCalls.value[msg.id] ?? []).find((c) => c.callId === e.data?.callId)
          if (call) {
            call.status = e.data.status ?? (e.type === 'skill_call_result' ? 'success' : 'error')
            call.result = e.data.result
            call.durationMs = e.data.durationMs ?? 0
            call.errorMessage = e.data.errorMessage
            updateThinkingStep(
              msg.id,
              '正在执行技能',
              e.type === 'skill_call_result' ? 'done' : 'error',
              e.type === 'skill_call_result' ? `${call.skillName} 已完成` : call.errorMessage,
            )
          }
        } else if (e.type === 'message_done') {
          finishThinking(msg.id, 'done')
          msg.status = 1
          msg.model = e.data?.model
          msg.tokenOutput = e.data?.tokenOutput ?? 0
          if (!hadConv && e.conversationId) {
            activeId.value = e.conversationId
            loadConversations()
          }
        } else if (e.type === 'error') {
          finishThinking(msg.id, 'error')
          msg.status = 2
          msg.content = `[生成失败] ${e.data?.message ?? '引擎异常，请稍后重试'}`
        }
        scrollBottom()
      },
      abortCtrl.signal,
    )
  } catch (err) {
    const msg = messages.value.find((m) => m.id === assistantId)
    if (msg && msg.status === 0) {
      // 用户主动中断 vs 请求失败
      const aborted = err instanceof DOMException && err.name === 'AbortError'
      finishThinking(msg.id, 'error')
      msg.status = 2
      msg.content = aborted ? `${msg.content}\n\n_[已中断生成]_` : `[请求失败] ${err instanceof Error ? err.message : String(err)}`
    }
  } finally {
    streaming.value = false
    abortCtrl = null
    modelSwitchTip.value = ''
    await loadConversations()
    scrollBottom()
  }
}

function stop() {
  abortCtrl?.abort()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function autoResize() {
  nextTick(() => {
    const el = inputEl.value
    if (el) {
      el.style.height = 'auto'
      el.style.height = `${Math.min(el.scrollHeight, 120)}px`
    }
  })
}

/* ---------- 展示辅助 ---------- */
function scrollBottom() {
  nextTick(() => {
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}

function fmtTime(t?: string | null) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function fmtArgs(args?: Record<string, unknown> | null) {
  if (!args || Object.keys(args).length === 0) return ''
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return String(args)
  }
}

function fmtResult(result: unknown): string {
  if (result == null) return ''
  if (typeof result === 'string') return result.length > 400 ? `${result.slice(0, 400)}…` : result
  try {
    const s = JSON.stringify(result, null, 2)
    return s.length > 400 ? `${s.slice(0, 400)}…` : s
  } catch {
    return String(result)
  }
}

function statusLabel(s: string): string {
  return s === 'running' ? '调用中' : s === 'success' ? '已完成' : s === 'timeout' ? '已超时' : '失败'
}

function phaseLabel(phase?: ThinkingStep['phase']): string {
  if (phase === 'analysis') return '分析'
  if (phase === 'action') return '行动'
  if (phase === 'knowledge') return '知识'
  if (phase === 'generation') return '回答'
  if (phase === 'complete') return '完成'
  return '过程'
}

/** 演示引导 prompts（一键触发技能 / 工具 / 知识检索） */
const suggestions = [
  '用 unit_converter 技能把 5.5 kg 转换成斤',
  '用 text_polish 技能润色：今天天气真不错，我们去公园玩吧。',
  '用 code_exec 工具计算 1 到 100 的质数之和',
  '检索知识库：什么是 AgentForge？',
]

function useSuggestion(text: string) {
  input.value = text
  autoResize()
  inputEl.value?.focus()
}

onMounted(async () => {
  await loadAgents()
  await loadModels()
  await loadConversations()
  if (conversations.value.length) {
    await openConversation(conversations.value[0].id)
  } else {
    await newConversation()
  }
})

onBeforeUnmount(() => abortCtrl?.abort())
</script>

<template>
  <div class="chat">
    <!-- 会话列表 -->
    <aside class="chat__side">
      <div class="chat__side-head">
        <div class="chat__side-title">
          <span class="label-group">会话</span>
          <span v-if="selectionMode && selectedConversationIds.length" class="selection-count">
            已选 {{ selectedConversationIds.length }}
          </span>
        </div>
        <div class="chat__side-actions">
          <button
            type="button"
            class="side-action"
            :class="{ active: selectionMode }"
            :disabled="conversationLoading || deletingConversations"
            :title="selectionMode ? '退出批量选择' : '批量管理会话'"
            @click="toggleSelectionMode"
          >
            {{ selectionMode ? '完成' : '批量管理' }}
          </button>
          <AfButton size="sm" :disabled="deletingConversations" @click="newConversation">
            <AfIcon name="plus" :size="13" />
            新建
          </AfButton>
        </div>
      </div>
      <div v-if="selectionMode && conversations.length" class="selection-toolbar">
        <button type="button" class="toolbar-link" @click="toggleSelectAllConversations">
          {{ allConversationsSelected ? '取消全选' : '全选' }}
        </button>
        <button
          type="button"
          class="toolbar-delete"
          :disabled="!selectedConversationIds.length || deletingConversations"
          @click="removeSelectedConversations"
        >
          删除选中
        </button>
        <button
          type="button"
          class="toolbar-delete"
          :disabled="deletingConversations"
          @click="removeAllConversations"
        >
          清空全部
        </button>
      </div>
      <ul class="conv-list">
        <li v-if="conversationError" class="conv-error">{{ conversationError }}</li>
        <li v-if="modelsError" class="conv-error">模型加载失败：{{ modelsError }}</li>
        <li v-if="conversationLoading" class="conv-empty">加载中…</li>
        <li
          v-for="c in conversations"
          :key="c.id"
          :class="['conv-item', { active: c.id === activeId, selected: selectedConversationIds.includes(c.id) }]"
          @click="selectionMode ? toggleConversationSelection(c.id) : openConversation(c.id)"
        >
          <span class="conv-item-main">
            <input
              v-if="selectionMode"
              class="conv-checkbox"
              type="checkbox"
              :checked="selectedConversationIds.includes(c.id)"
              :aria-label="`选择会话：${c.title || '新会话'}`"
              @click.stop
              @change="toggleConversationSelection(c.id)"
            />
            <span class="conv-item-title">{{ c.title || '新会话' }}</span>
          </span>
          <span class="conv-item-sub">
            <span>{{ fmtTime(c.updatedAt) }}</span>
            <button
              v-if="!selectionMode"
              type="button"
              class="conv-item-del"
              title="删除会话"
              :disabled="deletingConversations"
              @click.stop="removeConversation(c.id)"
            >
              <AfIcon name="trash" :size="12" />
            </button>
          </span>
        </li>
        <li v-if="!conversationLoading && !conversations.length && !conversationError" class="conv-empty">暂无会话，点击「新建」开始</li>
      </ul>
    </aside>

    <!-- 消息区 + 输入区 -->
    <main class="chat__main">
      <div ref="scrollEl" class="msg-area">
        <div v-for="m in messages" :key="m.id" :class="['msg', m.role]">
          <!-- user 消息 -->
          <template v-if="m.role === 'user'">
            <div class="msg-role">你</div>
            <div class="msg-body user-body">{{ m.content }}</div>
          </template>

          <!-- assistant 消息 -->
          <template v-else>
            <div class="msg-role">
              助手
              <span v-if="m.status === 0" class="msg-tag running">生成中</span>
              <span v-else-if="m.status === 2" class="msg-tag error">失败</span>
              <span v-if="m.model && m.status === 1" class="msg-model">{{ m.model }}</span>
            </div>
            <div class="msg-body assistant-body">
              <div v-if="thinkingSteps[m.id]?.length" class="thinking-panel">
                <button type="button" class="thinking-toggle" @click="toggleThinking(m.id)">
                  <span class="thinking-indicator" :class="{ running: m.status === 0 }" />
                  <span>{{ m.status === 0 ? '过程进行中' : m.status === 2 ? '过程失败' : '查看过程记录' }}</span>
                  <span class="thinking-safe-note">安全摘要 · 不含隐藏思维链</span>
                  <span class="thinking-chevron">{{ expandedThinking[m.id] ? '收起' : '展开' }}</span>
                </button>
                <div v-if="expandedThinking[m.id]" class="thinking-steps" aria-live="polite">
                  <div v-for="(step, index) in thinkingSteps[m.id]" :key="`${m.id}-${index}`" class="thinking-step">
                    <span class="thinking-step-mark" :class="step.status">
                      {{ step.status === 'running' ? '·' : step.status === 'done' ? '✓' : '!' }}
                    </span>
                    <span class="thinking-step-phase">{{ phaseLabel(step.phase) }}</span>
                    <span>{{ step.label }}</span>
                    <span v-if="step.detail" class="thinking-step-detail">{{ step.detail }}</span>
                  </div>
                </div>
              </div>
              <template v-if="m.content">
                <AfMarkdown :content="m.content" />
                <span v-if="m.status === 0" class="caret" />
              </template>
              <span v-else-if="m.status === 0" class="msg-think">正在组织回答…<i class="dots" /></span>
            </div>

            <!-- 技能调用时间线 -->
            <div v-if="skillCalls[m.id]?.length" class="timeline">
              <div
                v-for="call in skillCalls[m.id]"
                :key="call.callId"
                :class="['tl-card', 'skill', call.status]"
              >
                <div class="tl-head">
                  <span class="tl-icon"><AfIcon name="spark" :size="13" /></span>
                  <span class="tl-name">{{ call.skillName }}</span>
                  <span v-if="call.skillVersion" class="tl-ver">v{{ call.skillVersion }}</span>
                  <span class="tl-spacer" />
                  <span v-if="call.durationMs > 0" class="tl-dur">{{ call.durationMs }}ms</span>
                  <span :class="['tl-status', call.status]">{{ statusLabel(call.status) }}</span>
                </div>
                <pre v-if="call.callArgs && Object.keys(call.callArgs).length" class="tl-args">{{ fmtArgs(call.callArgs) }}</pre>
                <pre v-if="call.status !== 'running'" class="tl-result">{{ call.errorMessage || fmtResult(call.result) }}</pre>
              </div>
            </div>

            <!-- 工具调用时间线 -->
            <div v-if="toolCalls[m.id]?.length" class="timeline">
              <div
                v-for="call in toolCalls[m.id]"
                :key="call.callId"
                :class="['tl-card', 'tool', call.status]"
              >
                <div class="tl-head">
                  <span class="tl-icon"><AfIcon name="settings" :size="13" /></span>
                  <span class="tl-name">{{ call.toolName }}</span>
                  <span class="tl-code mono">{{ call.toolCode }}</span>
                  <span class="tl-spacer" />
                  <span v-if="call.durationMs > 0" class="tl-dur">{{ call.durationMs }}ms</span>
                  <span :class="['tl-status', call.status]">{{ statusLabel(call.status) }}</span>
                </div>
                <pre v-if="Object.keys(call.arguments).length" class="tl-args">{{ fmtArgs(call.arguments) }}</pre>
                <pre v-if="call.status !== 'running'" class="tl-result">{{ call.errorMessage || fmtResult(call.result) }}</pre>
              </div>
            </div>
          </template>
        </div>

        <div v-if="!messages.length" class="msg-empty">
          <p class="msg-empty-title">开始与 AgentForge 对话</p>
          <p class="msg-empty-desc">支持工具调用 / 技能执行 / 知识检索，全过程可视化</p>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="composer">
        <div v-if="modelSwitchTip" class="model-switch-tip">
          {{ modelSwitchTip }}
        </div>
        <div v-if="!streaming" class="suggest">
          <button
            v-for="s in suggestions"
            :key="s"
            type="button"
            class="suggest-chip"
            @click="useSuggestion(s)"
          >
            {{ s }}
          </button>
        </div>
        <div class="composer-row">
          <select
            v-model="activeAgentId"
            class="composer-model"
            :disabled="streaming || modelsLoading"
            title="选择本次对话使用的智能体"
          >
            <option :value="null">默认助手</option>
            <option v-for="agent in agents" :key="agent.id" :value="agent.id">
              {{ agent.name }}{{ agent.isDefault === 1 ? '（默认）' : '' }}
            </option>
          </select>
          <select
            v-model="activeModelId"
            class="composer-model"
            :disabled="streaming || modelsLoading"
            title="选择本次对话使用的大模型"
          >
            <option :value="null">默认模型</option>
            <option v-for="m in models" :key="m.id" :value="m.id">
              {{ m.name }} · {{ m.model }}{{ m.isDefault === 1 ? '（默认）' : '' }}
            </option>
          </select>
          <textarea
            ref="inputEl"
            v-model="input"
            class="composer-input"
            placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
            :disabled="streaming"
            @keydown="onKeydown"
            @input="autoResize"
          />
          <AfButton v-if="!streaming" :disabled="!input.trim()" @click="send">
            <AfIcon name="send" :size="14" />
            发送
          </AfButton>
          <AfButton v-else variant="danger" @click="stop">
            <AfIcon name="x" :size="14" />
            停止
          </AfButton>
        </div>
        <p class="composer-hint mono">
          模型：{{ activeModelName }} · Agent 引擎 v0.11 · LangGraph ReAct · SSE 流式
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  width: 100%;
  height: 100%;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* ---------- 会话列表 ---------- */
.chat__side {
  width: 224px;
  height: 100%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  background-color: var(--color-surface);
}
.chat__side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: var(--space-3) var(--space-3) var(--space-2);
  border-bottom: 1px solid var(--color-border);
}
.chat__side-title,
.chat__side-actions,
.conv-item-main {
  display: flex;
  align-items: center;
}
.chat__side-title {
  min-width: 0;
  gap: 6px;
}
.chat__side-actions {
  gap: 6px;
}
.side-action,
.toolbar-link,
.toolbar-delete {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: var(--text-xs);
  white-space: nowrap;
}
.side-action {
  padding: 5px 6px;
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
}
.side-action:hover,
.side-action.active {
  background: var(--color-surface-2);
  color: var(--color-text);
}
.side-action:disabled,
.toolbar-link:disabled,
.toolbar-delete:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.selection-count {
  color: var(--color-text-tertiary);
  font-size: 10px;
}
.selection-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px var(--space-3);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-2);
}
.toolbar-link {
  color: var(--color-text-secondary);
}
.toolbar-link:hover {
  color: var(--color-text);
}
.toolbar-delete {
  color: var(--color-danger);
}
.toolbar-delete:last-child {
  margin-left: auto;
}
.conv-item-main {
  min-width: 0;
  gap: 7px;
}
.conv-checkbox {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  accent-color: var(--color-text);
}
.conv-item.selected {
  background-color: var(--color-surface-2);
}
.conv-item-del:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.conv-list {
  list-style: none;
  margin: 0;
  padding: var(--space-2);
  overflow-y: auto;
  flex: 1;
}
.conv-item {
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: background-color var(--transition-fast);
}
.conv-item:hover {
  background-color: var(--color-surface-2);
}
.conv-item.active {
  background-color: var(--color-surface-2);
  box-shadow: inset 2px 0 0 var(--color-text);
}
.conv-item-title {
  font-size: var(--text-base);
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-item-sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
.conv-item-del {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast);
}
.conv-item:hover .conv-item-del,
.conv-item.active .conv-item-del {
  opacity: 1;
}
.conv-item-del:hover {
  color: var(--color-danger);
}
.conv-empty {
  padding: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  text-align: center;
}
.conv-error {
  padding: var(--space-3);
  color: var(--color-danger);
  font-size: var(--text-sm);
  line-height: 1.5;
}

/* ---------- 消息区 ---------- */
.chat__main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.msg-area {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6) var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 760px;
}
.msg.user {
  align-self: flex-end;
  align-items: flex-end;
}
.msg-role {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
.msg-tag {
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
}
.msg-tag.running {
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
  animation: pulse 1.2s ease-in-out infinite;
}
.msg-tag.error {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.msg-model {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.msg-body {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: var(--text-md);
  line-height: 1.7;
}
.user-body {
  background: var(--color-surface-2);
  border: 1px solid var(--color-border);
  white-space: pre-wrap;
  word-break: break-word;
}
.thinking-panel {
  margin-bottom: 10px;
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 8px;
}
.thinking-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--text-xs);
  text-align: left;
}
.thinking-toggle:hover {
  color: var(--color-text);
}
.thinking-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
}
.thinking-indicator.running {
  background: var(--color-warning);
  animation: pulse 1.2s ease-in-out infinite;
}
.thinking-safe-note {
  color: var(--color-text-tertiary);
  font-size: 10px;
}
.thinking-chevron {
  margin-left: auto;
  color: var(--color-text-tertiary);
  font-size: 10px;
}
.thinking-steps {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 8px;
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
}
.thinking-step {
  display: flex;
  align-items: center;
  gap: 7px;
}
.thinking-step-phase {
  flex-shrink: 0;
  min-width: 32px;
  color: var(--color-text-tertiary);
  font-size: 10px;
  text-align: center;
}
.thinking-step-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-surface-2);
  color: var(--color-text-tertiary);
  font-size: 10px;
}
.thinking-step-mark.running {
  color: var(--color-warning);
}
.thinking-step-mark.done {
  color: var(--color-success);
}
.thinking-step-mark.error {
  color: var(--color-danger);
}
.thinking-step-detail {
  overflow: hidden;
  color: var(--color-text-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-body {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
}
.msg-think {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-tertiary);
  font-size: var(--text-base);
}
.dots::after {
  content: '…';
  animation: dots 1.2s steps(4, end) infinite;
}
@keyframes dots {
  0% { content: ''; }
  25% { content: '.'; }
  50% { content: '..'; }
  75% { content: '...'; }
}
.caret {
  display: inline-block;
  width: 7px;
  height: 14px;
  background: var(--color-text);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
@keyframes pulse {
  50% { opacity: 0.45; }
}
.msg-empty {
  margin: auto;
  text-align: center;
  color: var(--color-text-tertiary);
}
.msg-empty-title {
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
  letter-spacing: var(--tracking-tight);
  margin-bottom: 4px;
}
.msg-empty-desc {
  font-size: var(--text-base);
}

/* ---------- 时间线卡片 ---------- */
.timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 2px;
  max-width: 560px;
}
.tl-card {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}
.tl-card.running {
  border-color: var(--color-border-strong);
}
.tl-card.error,
.tl-card.timeout {
  border-color: var(--color-danger);
}
.tl-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}
.tl-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
}
.tl-card.skill .tl-icon {
  color: var(--color-warning);
}
.tl-name {
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.tl-ver {
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.tl-code {
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.tl-spacer {
  flex: 1;
}
.tl-dur {
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.tl-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
}
.tl-status.running {
  animation: pulse 1.2s ease-in-out infinite;
}
.tl-status.success {
  background: transparent;
  color: var(--color-success);
}
.tl-status.error,
.tl-status.timeout {
  color: var(--color-danger);
}
.tl-args,
.tl-result {
  margin: 6px 0 0;
  padding: 6px 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 4px;
  background: var(--color-surface-2);
  color: var(--color-text-secondary);
}
.tl-result {
  color: var(--color-text);
  background: transparent;
  border-left: 2px solid var(--color-border-strong);
  border-radius: 0;
}

/* ---------- 输入区 ---------- */
.composer {
  padding: var(--space-3) var(--space-6) var(--space-3);
  border-top: 1px solid var(--color-border);
  background-color: var(--color-surface);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.model-switch-tip {
  font-size: var(--text-sm);
  color: var(--color-warning, #f59e0b);
  padding: var(--space-2) var(--space-3);
  background: var(--color-warning-subtle, rgba(245, 158, 11, 0.1));
  border-radius: var(--radius-md);
  border: 1px solid var(--color-warning, #f59e0b);
}
.suggest {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.suggest-chip {
  padding: 4px 10px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}
.suggest-chip:hover {
  color: var(--color-text);
  border-color: var(--color-border-strong);
}
.composer-row {
  display: flex;
  gap: var(--space-2);
  align-items: flex-end;
}
.composer-model {
  flex-shrink: 0;
  align-self: flex-end;
  height: 44px;
  max-width: 220px;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--text-sm);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}
.composer-model:hover:not(:disabled) {
  color: var(--color-text);
  border-color: var(--color-border-strong);
}
.composer-model:focus {
  outline: none;
  border-color: var(--color-text);
}
.composer-model:disabled {
  opacity: 0.7;
}
.composer-input {
  flex: 1;
  resize: none;
  max-height: 120px;
  min-height: 44px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  color: var(--color-text);
  font: inherit;
  font-size: var(--text-md);
  line-height: 1.6;
  transition: border-color var(--transition-fast);
}
.composer-input:focus {
  outline: none;
  border-color: var(--color-text);
}
.composer-input:disabled {
  opacity: 0.7;
}
.composer-hint {
  font-size: 10px;
  color: var(--color-text-tertiary);
}
</style>
