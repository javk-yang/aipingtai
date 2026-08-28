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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
/** 是否在工具时间线中展开知识库检索（RAG）的完整召回内容（默认关闭，仅展示检索轨迹摘要） */
const showProcess = ref(false)
/** 模型思维链（推理过程）按消息 ID 存储 */
const reasoningByMsg = ref<Record<number, string>>({})
/** 当前正在流式生成的 assistant 消息真实 ID（message_start 后从临时 ID 迁移而来） */
const currentAssistantId = ref<number | null>(null)

/** 判断是否为知识库检索类工具（RAG）：其召回内容默认不展开，避免长文本刷屏 */
function isKnowledgeTool(call: ToolCallView): boolean {
  const code = (call.toolCode || '').toLowerCase()
  const name = call.toolName || ''
  return code === 'knowledge_search' || code.includes('knowledge') || name.includes('检索') || name.includes('知识库') || name.includes('RAG')
}

/** 知识检索工具的结果摘要：不铺开召回内容，仅提示命中数量 */
function knowledgeSummary(result: unknown): string {
  const obj = result && typeof result === 'object' ? (result as Record<string, unknown>) : null
  const arr = obj && Array.isArray(obj.results) ? (obj.results as unknown[]) : null
  const n = arr ? arr.length : 0
  return n > 0 ? `已检索知识库，命中 ${n} 条相关片段，已用于生成回答` : '已检索知识库'
}

/* ---------- 模型选择（#87：大模型可选择） ---------- */
const models = ref<ModelConfig[]>([])
const activeModelId = ref<number | null>(null)
const agents = ref<AgentResponse[]>([])
const activeAgentId = ref<number | null>(null)
const modelsLoading = ref(false)
const agentsLoading = ref(false)
const modelsError = ref('')
const agentsError = ref('')
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
  agentsLoading.value = true
  agentsError.value = ''
  try {
    const list = await agentsApi.list()
    agents.value = list.filter((agent) => agent.status === 2)
    const defaultAgent = agents.value.find((agent) => agent.isDefault === 1)
    activeAgentId.value = defaultAgent?.id ?? null
    if (list.length > 0 && agents.value.length === 0) {
      agentsError.value = '暂无已发布的智能体，请先在「智能体」页面发布后再选择。'
    }
  } catch (e) {
    agents.value = []
    activeAgentId.value = null
    agentsError.value = e instanceof Error ? e.message : '智能体列表加载失败'
  } finally {
    agentsLoading.value = false
  }
}

async function loadModels() {
  modelsLoading.value = true
  modelsError.value = ''
  try {
    const list = await modelsApi.list()
    models.value = list
    // 仅当当前未选模型或所选模型已不在列表中时，才回退到默认模型；
    // 已选中的有效模型（例如 Agent 绑定的模型）予以保留，避免覆盖 Agent 回填。
    if (activeModelId.value == null || !list.some((m) => m.id === activeModelId.value)) {
      const enabled = list.filter((m) => m.enabled === 1)
      const def = enabled.find((m) => m.isDefault === 1) ?? enabled[0] ?? list[0]
      activeModelId.value = def ? def.id : null
    }
  } catch (e) {
    models.value = []
    activeModelId.value = null
    modelsError.value = e instanceof Error ? e.message : '模型列表加载失败'
  } finally {
    modelsLoading.value = false
  }
}

// 切换智能体时，将模型下拉框回填为该 Agent 绑定的默认模型（回单模型）。
// 若 Agent 未绑定模型，则保留用户当前选择（无效时回退默认）。
function syncModelToAgent() {
  const agent = agents.value.find((a) => a.id === activeAgentId.value)
  if (agent && agent.modelConfigId != null) {
    if (models.value.some((m) => m.id === agent.modelConfigId)) {
      activeModelId.value = agent.modelConfigId
      return
    }
  }
  if (activeModelId.value == null || !models.value.some((m) => m.id === activeModelId.value)) {
    const enabled = models.value.filter((m) => m.enabled === 1)
    const def = enabled.find((m) => m.isDefault === 1) ?? enabled[0] ?? models.value[0]
    activeModelId.value = def ? def.id : null
  }
}

watch(activeAgentId, syncModelToAgent)

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
    const conversation = conversations.value.find((item) => item.id === id)
    // 已绑定 Agent 的会话必须沿用其配置；未绑定的会话允许在输入区选择并在首条消息时绑定。
    activeAgentId.value = conversation?.agentId ?? null
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
    // 将当前选择的 Agent 在建会话时固化，后续该会话稳定使用同一智能体。
    const conv = await conversationApi.create(activeAgentId.value ?? undefined)
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
  currentAssistantId.value = assistantId
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
            currentAssistantId.value = e.data.messageId
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
        } else if (e.type === 'reasoning' && e.data?.content) {
          const rid = currentAssistantId.value ?? msg.id
          reasoningByMsg.value[rid] = String(e.data.content)
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
  syncModelToAgent()
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
        <li v-if="agentsError" class="conv-error">智能体加载失败：{{ agentsError }}</li>
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
                  <span class="thinking-safe-note">含模型推理思维链</span>
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
                <div v-if="reasoningByMsg[m.id]" class="reasoning-block">
                  <div class="reasoning-head">🧠 模型思维链（推理过程）</div>
                  <pre class="reasoning-text">{{ reasoningByMsg[m.id] }}</pre>
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
                <pre v-if="call.status !== 'running' && !isKnowledgeTool(call)" class="tl-result">{{ call.errorMessage || fmtResult(call.result) }}</pre>
                <pre v-else-if="call.status !== 'running' && isKnowledgeTool(call)" class="tl-result tl-result--rag">{{ showProcess ? (call.errorMessage || fmtResult(call.result)) : knowledgeSummary(call.result) }}</pre>
              </div>
            </div>
          </template>
        </div>

        <div v-if="!messages.length" class="msg-empty">
          <div class="msg-empty-orbit" aria-hidden="true"><span /><i /><b /></div>
          <p class="label-group msg-empty-kicker">AGENTFORGE / LIVE CANVAS</p>
          <p class="msg-empty-title">开始一次有轨迹的对话</p>
          <p class="msg-empty-desc">工具、技能与知识检索将沿着生命线留下可追溯的过程。</p>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="composer">
        <div v-if="modelSwitchTip" class="model-switch-tip">
          {{ modelSwitchTip }}
        </div>
        <div class="process-toggle">
          <label class="switch">
            <input type="checkbox" v-model="showProcess" />
            <span class="switch-track"><span class="switch-thumb" /></span>
          </label>
          <span class="process-toggle-label">展开知识检索结果</span>
          <span class="process-toggle-hint">关闭时仅展示检索轨迹，不展开召回内容</span>
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
            :disabled="streaming || agentsLoading"
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
            模型：{{ activeModelName }} · 智能体：{{ activeAgentName }} · Agent 引擎 v0.11 · LangGraph ReAct · SSE 流式
          </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat {
  --side-width: 242px;
  display: flex;
  width: 100%;
  height: 100%;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: transparent;
}

/* ---------- 会话轨 ---------- */
.chat__side {
  width: var(--side-width);
  height: 100%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-surface) 84%, transparent);
}
.chat__side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 17px 14px 12px;
  border-bottom: 1px solid var(--color-border);
}
.chat__side-title,
.chat__side-actions,
.conv-item-main { display: flex; align-items: center; }
.chat__side-title { min-width: 0; gap: 6px; }
.chat__side-actions { gap: 6px; }
.side-action,
.toolbar-link,
.toolbar-delete {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: var(--text-xs);
  white-space: nowrap;
}
.side-action { padding: 5px 6px; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.side-action:hover,
.side-action.active { background: var(--color-surface-2); color: var(--color-text); }
.side-action:disabled,
.toolbar-link:disabled,
.toolbar-delete:disabled { cursor: not-allowed; opacity: 0.45; }
.selection-count { color: var(--color-text-tertiary); font-size: 10px; }
.selection-toolbar { display: flex; align-items: center; gap: 8px; padding: 8px 13px; border-bottom: 1px solid var(--color-border); background: var(--color-surface-2); }
.toolbar-link { color: var(--color-text-secondary); }
.toolbar-link:hover { color: var(--color-text); }
.toolbar-delete { color: var(--color-danger); }
.toolbar-delete:last-child { margin-left: auto; }
.conv-item-main { min-width: 0; gap: 7px; }
.conv-checkbox { flex-shrink: 0; width: 14px; height: 14px; accent-color: var(--color-primary); }
.conv-item.selected { background-color: var(--color-surface-2); }
.conv-item-del:disabled { cursor: not-allowed; opacity: 0.45; }
.conv-list { display: flex; flex: 1; flex-direction: column; gap: 3px; margin: 0; padding: 10px 8px; overflow-y: auto; }
.conv-item {
  position: relative;
  padding: 10px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 5px;
  transition: background-color var(--transition-fast), border-color var(--transition-fast), transform var(--transition-fast);
}
.conv-item:hover { background-color: var(--color-surface-2); transform: translateX(2px); }
.conv-item.active { border-color: var(--color-border); background-color: var(--color-surface-raised); box-shadow: var(--shadow-float); }
.conv-item.active::before { position: absolute; left: 0; top: 12px; bottom: 12px; width: 2px; border-radius: var(--radius-pill); content: ''; background: var(--color-lifeline); }
.conv-item-title { font-size: var(--text-base); color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-item-sub { display: flex; align-items: center; justify-content: space-between; font-size: var(--text-xs); color: var(--color-text-tertiary); }
.conv-item-del { display: flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 6px; background: transparent; color: var(--color-text-tertiary); cursor: pointer; opacity: 0; transition: opacity var(--transition-fast), color var(--transition-fast), background-color var(--transition-fast); }
.conv-item:hover .conv-item-del,
.conv-item.active .conv-item-del { opacity: 1; }
.conv-item-del:hover { color: var(--color-danger); background: var(--color-danger-bg); }
.conv-empty { padding: var(--space-4); font-size: var(--text-sm); color: var(--color-text-tertiary); text-align: center; }
.conv-error { padding: var(--space-3); color: var(--color-danger); font-size: var(--text-sm); line-height: 1.5; }

/* ---------- 主画布 ---------- */
.chat__main { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.msg-area { flex: 1; overflow-y: auto; padding: 36px clamp(24px, 5vw, 74px); display: flex; flex-direction: column; gap: 22px; }
.msg { position: relative; display: flex; flex-direction: column; gap: 6px; width: min(100%, 800px); }
.msg.user { align-self: flex-end; align-items: flex-end; max-width: min(78%, 680px); }
.msg-role { display: flex; align-items: center; gap: 7px; color: var(--color-text-tertiary); font-size: var(--text-xs); letter-spacing: 0.035em; }
.msg-tag { padding: 2px 7px; border-radius: var(--radius-pill); font-size: 10px; }
.msg-tag.running { background: var(--color-warning-bg); color: var(--color-warning); animation: pulse 1.4s ease-in-out infinite; }
.msg-tag.error { background: var(--color-danger-bg); color: var(--color-danger); }
.msg-model { font-family: var(--font-mono); font-size: 10px; color: var(--color-text-tertiary); }
.msg-body { padding: 14px 16px; border-radius: var(--radius-lg); font-size: var(--text-md); line-height: 1.75; }
.user-body { border: 1px solid var(--color-border); background: var(--color-surface-2); white-space: pre-wrap; word-break: break-word; border-top-right-radius: 5px; }
.assistant-body { position: relative; border: 1px solid var(--color-border); background: color-mix(in srgb, var(--color-surface-raised) 92%, transparent); border-top-left-radius: 5px; box-shadow: 0 1px 0 color-mix(in srgb, var(--color-surface-raised) 90%, transparent); }
.assistant-body::before { position: absolute; top: 17px; left: -1px; width: 2px; height: 28px; content: ''; border-radius: var(--radius-pill); background: var(--color-lifeline); }

/* ---------- 安全过程生命线 ---------- */
.thinking-panel { margin-bottom: 13px; padding: 10px 11px 10px 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.thinking-toggle { display: flex; align-items: center; gap: 7px; width: 100%; border: 0; background: transparent; color: var(--color-text-secondary); cursor: pointer; font-size: var(--text-xs); text-align: left; }
.thinking-toggle:hover { color: var(--color-text); }
.thinking-indicator { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-tertiary); }
.thinking-indicator.running { background: var(--color-spectrum-d); box-shadow: 0 0 0 4px color-mix(in srgb, var(--color-spectrum-d) 14%, transparent); animation: pulse 1.2s ease-in-out infinite; }
.thinking-safe-note { padding: 2px 6px; border-radius: var(--radius-pill); background: var(--color-surface-2); color: var(--color-text-tertiary); font-size: 10px; }
.thinking-chevron { margin-left: auto; color: var(--color-text-tertiary); font-size: 10px; }
.thinking-steps { position: relative; display: flex; flex-direction: column; gap: 8px; margin: 11px 0 1px 3px; padding-left: 16px; color: var(--color-text-secondary); font-size: var(--text-xs); }
.thinking-steps::before { position: absolute; top: 5px; bottom: 5px; left: 3px; width: 2px; content: ''; background: var(--color-lifeline); opacity: 0.75; }
.thinking-step { position: relative; display: flex; align-items: center; gap: 7px; min-width: 0; }
.thinking-step-mark { position: relative; z-index: 1; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; width: 13px; height: 13px; margin-left: -22px; border: 2px solid var(--color-surface); border-radius: 50%; background: var(--color-text-tertiary); color: var(--color-surface); font-size: 9px; }
.thinking-step-mark.running { background: var(--color-spectrum-d); animation: pulse 1.2s ease-in-out infinite; }
.thinking-step-mark.done { background: var(--color-success); }
.thinking-step-mark.error { background: var(--color-danger); }
.thinking-step-phase { flex-shrink: 0; min-width: 32px; padding: 1px 4px; border-radius: 4px; background: var(--color-surface-2); color: var(--color-text-tertiary); font-size: 9px; text-align: center; }
.thinking-step-detail { overflow: hidden; color: var(--color-text-tertiary); text-overflow: ellipsis; white-space: nowrap; }
.msg-think { display: flex; align-items: center; gap: 6px; color: var(--color-text-tertiary); font-size: var(--text-base); }
.dots::after { content: '…'; animation: dots 1.2s steps(4, end) infinite; }
@keyframes dots { 0% { content: ''; } 25% { content: '.'; } 50% { content: '..'; } 75% { content: '...'; } }
.caret { display: inline-block; width: 2px; height: 16px; margin-left: 3px; vertical-align: text-bottom; border-radius: var(--radius-pill); background: var(--color-spectrum-d); animation: blink 1s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }
@keyframes pulse { 50% { opacity: 0.45; } }

.msg-empty { margin: auto; max-width: 440px; text-align: center; color: var(--color-text-tertiary); }
.msg-empty-orbit { position: relative; width: 68px; height: 68px; margin: 0 auto 20px; border: 1px solid var(--color-border-strong); border-radius: 50%; }
.msg-empty-orbit::before { position: absolute; inset: 12px; border: 1px solid var(--color-border); border-radius: inherit; content: ''; }
.msg-empty-orbit span,
.msg-empty-orbit i,
.msg-empty-orbit b { position: absolute; display: block; border-radius: 50%; background: var(--color-lifeline); }
.msg-empty-orbit span { top: -3px; left: 17px; width: 7px; height: 7px; }
.msg-empty-orbit i { right: -3px; bottom: 15px; width: 8px; height: 8px; }
.msg-empty-orbit b { bottom: 5px; left: 7px; width: 5px; height: 5px; }
.msg-empty-kicker { margin-bottom: 8px; font-size: 9px; }
.msg-empty-title { margin-bottom: 6px; color: var(--color-text); font-size: 22px; font-weight: var(--weight-semibold); letter-spacing: var(--tracking-tight); }
.msg-empty-desc { font-size: var(--text-base); line-height: 1.8; }

/* ---------- 工具与技能轨迹 ---------- */
.timeline { position: relative; display: flex; flex-direction: column; gap: 7px; max-width: 590px; margin: 1px 0 0 14px; padding-left: 15px; }
.timeline::before { position: absolute; top: 0; bottom: 8px; left: 4px; width: 2px; border-radius: var(--radius-pill); content: ''; background: var(--color-lifeline); opacity: 0.72; }
.tl-card { position: relative; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.tl-card::before { position: absolute; top: 16px; left: -15px; width: 8px; height: 8px; box-sizing: border-box; border: 2px solid var(--color-surface); border-radius: 50%; content: ''; background: var(--color-spectrum-d); }
.tl-card.skill::before { background: var(--color-spectrum-e); }
.tl-card.running { border-color: var(--color-border-strong); }
.tl-card.error,
.tl-card.timeout { border-color: var(--color-danger); }
.tl-card.error::before,
.tl-card.timeout::before { background: var(--color-danger); }
.tl-head { display: flex; align-items: center; gap: 7px; font-size: var(--text-sm); }
.tl-icon { display: flex; align-items: center; justify-content: center; width: 23px; height: 23px; border: 1px solid var(--color-border); border-radius: 7px; background: var(--color-icon-bg); color: var(--color-spectrum-d); }
.tl-card.skill .tl-icon { color: var(--color-spectrum-e); }
.tl-name { font-weight: var(--weight-medium); color: var(--color-text); }
.tl-ver, .tl-code, .tl-dur { font-size: 10px; color: var(--color-text-tertiary); }
.tl-spacer { flex: 1; }
.tl-status { padding: 2px 7px; border-radius: var(--radius-pill); background: var(--color-surface-2); color: var(--color-text-secondary); font-size: 10px; }
.tl-status.running { color: var(--color-spectrum-d); animation: pulse 1.2s ease-in-out infinite; }
.tl-status.success { background: var(--color-success-bg); color: var(--color-success); }
.tl-status.error, .tl-status.timeout { background: var(--color-danger-bg); color: var(--color-danger); }
.tl-args, .tl-result { margin: 8px 0 0; padding: 7px 9px; border-radius: var(--radius-sm); background: var(--color-surface-2); color: var(--color-text-secondary); font-family: var(--font-mono); font-size: 11px; line-height: 1.55; white-space: pre-wrap; word-break: break-all; }
.tl-result { border-left: 2px solid var(--color-border-strong); border-radius: 0; background: transparent; color: var(--color-text); }

/* ---------- 输入控制台 ---------- */
.composer { padding: 13px clamp(24px, 5vw, 74px) 15px; border-top: 1px solid var(--color-border); background: color-mix(in srgb, var(--color-surface) 92%, transparent); display: flex; flex-direction: column; gap: 9px; }
.model-switch-tip { padding: var(--space-2) var(--space-3); border: 1px solid var(--color-warning); border-radius: var(--radius-md); background: var(--color-warning-bg); color: var(--color-warning); font-size: var(--text-sm); }
.suggest { display: flex; flex-wrap: wrap; gap: 6px; }
.process-toggle { display: flex; align-items: center; gap: 9px; padding: 1px 2px; }
.process-toggle-label { color: var(--color-text-secondary); font-size: var(--text-xs); }
.process-toggle-hint { color: var(--color-text-tertiary); font-size: 10px; }
.switch { position: relative; display: inline-flex; width: 34px; height: 19px; cursor: pointer; }
.switch input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.switch-track { position: relative; display: block; width: 34px; height: 19px; border-radius: 999px; background: var(--color-surface-2); border: 1px solid var(--color-border); transition: background-color var(--transition-fast); }
.switch-thumb { position: absolute; top: 2px; left: 2px; width: 13px; height: 13px; border-radius: 50%; background: var(--color-text-tertiary); transition: transform var(--transition-fast), background-color var(--transition-fast); }
.switch input:checked + .switch-track { background: var(--color-primary); border-color: var(--color-primary); }
.switch input:checked + .switch-track .switch-thumb { transform: translateX(15px); background: #fff; }
.tl-result--rag { color: var(--color-text-tertiary); font-style: italic; }
.reasoning-block { margin-top: 10px; border-top: 1px dashed var(--color-border); padding-top: 10px; }
.reasoning-head { font-size: var(--text-xs); color: var(--color-text-secondary); margin-bottom: 6px; font-weight: 600; }
.reasoning-text { white-space: pre-wrap; word-break: break-word; font-size: var(--text-sm); line-height: 1.6; color: var(--color-text-secondary); max-height: 260px; overflow-y: auto; margin: 0; background: var(--color-surface-2); border-radius: 8px; padding: 10px 12px; }
.suggest-chip { padding: 5px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-surface-raised) 78%, transparent); color: var(--color-text-secondary); cursor: pointer; font-size: var(--text-sm); transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast), transform var(--transition-fast); }
.suggest-chip:hover { border-color: var(--color-border-strong); background: var(--color-surface-raised); color: var(--color-text); transform: translateY(-1px); }
.composer-row { display: flex; align-items: flex-end; gap: 8px; padding: 7px; border: 1px solid var(--color-border-strong); border-radius: 15px; background: var(--color-surface-raised); box-shadow: var(--shadow-float); }
.composer-model { align-self: stretch; flex-shrink: 0; width: 146px; min-height: 42px; padding: 0 9px; border: 1px solid transparent; border-radius: 10px; background: var(--color-surface-2); color: var(--color-text-secondary); font: inherit; font-size: var(--text-sm); cursor: pointer; transition: border-color var(--transition-fast), color var(--transition-fast), background-color var(--transition-fast); }
.composer-model:hover:not(:disabled) { color: var(--color-text); border-color: var(--color-border-strong); }
.composer-model:focus { outline: none; border-color: var(--color-focus); }
.composer-model:disabled, .composer-input:disabled { opacity: 0.7; }
.composer-input { flex: 1; resize: none; max-height: 120px; min-height: 42px; padding: 10px 8px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--color-text); font: inherit; font-size: var(--text-md); line-height: 1.6; }
.composer-input:focus { outline: none; border-color: var(--color-border); background: var(--color-surface); }
.composer-hint { padding-left: 5px; color: var(--color-text-tertiary); font-size: 10px; }

@media (max-width: 860px) {
  .chat { --side-width: 210px; }
  .msg-area, .composer { padding-left: 24px; padding-right: 24px; }
  .composer-model { width: 112px; }
}
@media (max-width: 700px) {
  .chat { --side-width: 184px; }
  .chat__side-head { align-items: flex-start; flex-direction: column; }
  .composer-row { flex-wrap: wrap; }
  .composer-model { flex: 1; width: calc(50% - 4px); min-width: 0; }
  .composer-input { min-width: 100%; }
}
@media (max-width: 540px) {
  .chat { --side-width: 0px; }
  .chat__side { display: none; }
  .msg-area, .composer { padding-left: 16px; padding-right: 16px; }
  .msg.user { max-width: 92%; }
  .timeline { margin-left: 9px; }
}
/* ---- 小巧灵动会话画布：把大面板拆成轻量信息单元 ---- */
.chat { --side-width: 218px; }.chat__side { background: color-mix(in srgb, var(--color-surface) 90%, transparent); }.chat__side-head { padding: 13px 11px 10px; }.conv-list { gap: 5px; padding: 9px 7px; }.conv-item { padding: 8px 9px; gap: 4px; border-radius: 12px; }.conv-item.active { box-shadow: 0 4px 14px color-mix(in srgb, var(--color-primary) 7%, transparent); }.conv-item.active::before { top: 10px; bottom: 10px; }.conv-item-title { font-size: var(--text-sm); }
.msg-area { padding: 24px clamp(20px, 4vw, 58px); gap: 16px; }.msg { max-width: 740px; gap: 4px; }.msg-body { padding: 11px 13px; border-radius: 14px; font-size: var(--text-base); line-height: 1.7; }.assistant-body::before { top: 14px; height: 22px; }.thinking-panel { padding: 8px 9px 8px 12px; margin-bottom: 10px; border-radius: 11px; }.thinking-steps { gap: 6px; margin-top: 9px; }.timeline { gap: 6px; margin-left: 11px; padding-left: 13px; }.tl-card { padding: 8px 10px; border-radius: 11px; }.tl-card::before { top: 13px; left: -13px; }.tl-icon { width: 21px; height: 21px; border-radius: 7px; }
.composer { gap: 7px; padding: 10px clamp(20px, 4vw, 58px) 11px; }.suggest { gap: 5px; }.suggest-chip { padding: 4px 8px; font-size: var(--text-xs); }.composer-row { gap: 6px; padding: 5px; border-radius: 13px; }.composer-model { width: 130px; min-height: 36px; border-radius: 9px; font-size: var(--text-xs); }.composer-input { min-height: 36px; padding: 8px 7px; font-size: var(--text-base); }.composer-hint { font-size: 9px; }
@media (max-width: 860px) { .chat { --side-width: 192px; }.msg-area, .composer { padding-left: 18px; padding-right: 18px; }.composer-model { width: 105px; } }
</style>
