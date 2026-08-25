<script setup lang="ts">
// P6.3 验证页: 最小可跑的 SSE 消费演示(完整工作台在 P11)
// 验证三件事: ① 会话列表/新建 ② 流式打字机渲染 ③ 断线重连(刷新/点会话拉回已落库消息)
import { onMounted, nextTick, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import { conversationApi, chatStream } from '@/api/session'
import type { ConversationResponse, MessageResponse, ChatStreamEvent, ToolCallView } from '@/types/chat'
import AfButton from '@/components/button/AfButton.vue'

const userStore = useUserStore()
const conversations = ref<ConversationResponse[]>([])
const activeId = ref<string | null>(null)
const messages = ref<MessageResponse[]>([])
const toolCalls = ref<Record<number, ToolCallView[]>>({})
const input = ref('')
const streaming = ref(false)
const scrollEl = ref<HTMLElement | null>(null)

async function loadConversations() {
  const page = await conversationApi.list(1, 50)
  conversations.value = page.records
}
async function openConversation(id: string) {
  activeId.value = id
  messages.value = await conversationApi.messages(id) // 恢复点: 拉回已落库(含流式中)消息
  scrollBottom()
}
async function newConversation() {
  const conv = await conversationApi.create()
  await loadConversations()
  await openConversation(conv.id)
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  // 本地先渲染 user 消息
  messages.value.push({
    id: -Date.now(), conversationId: activeId.value ?? '', role: 'user', seq: messages.value.length + 1,
    content: text, contentType: 'text', status: 1, model: null, tokenInput: 0, tokenOutput: 0, parentId: null,
    createdAt: new Date().toISOString(),
  })
  // assistant 占位(status=0 流式中)
  const assistant: MessageResponse = {
    id: -1, conversationId: '', role: 'assistant', seq: 0, content: '', contentType: 'text',
    status: 0, model: null, tokenInput: 0, tokenOutput: 0, parentId: null, createdAt: '',
  }
  messages.value.push(assistant)
  streaming.value = true
  const hadConv = !!activeId.value
  try {
    await chatStream({ content: text, conversationId: activeId.value ?? undefined }, (e: ChatStreamEvent) => {
      if (e.type === 'content_delta' && e.data?.delta) {
        assistant.content = (assistant.content ?? '') + e.data.delta
        if (e.seq) assistant.seq = e.seq
      } else if (e.type === 'message_start') {
        if (e.data?.messageId) assistant.id = e.data.messageId
        if (e.seq) assistant.seq = e.seq
      } else if (e.type === 'tool_call_start' && e.data?.callId && e.data.toolCode) {
        const list = toolCalls.value[assistant.id] ?? []
        list.push({
          callId: e.data.callId,
          toolCode: e.data.toolCode,
          toolName: e.data.toolName ?? e.data.toolCode,
          arguments: e.data.arguments ?? {},
          status: 'running',
          durationMs: 0,
        })
        toolCalls.value[assistant.id] = list
      } else if ((e.type === 'tool_call_result' || e.type === 'tool_call_error') && e.data?.callId) {
        const call = (toolCalls.value[assistant.id] ?? []).find((item) => item.callId === e.data?.callId)
        if (call) {
          call.status = e.data.status ?? (e.type === 'tool_call_result' ? 'success' : 'error')
          call.result = e.data.result
          call.durationMs = e.data.durationMs ?? 0
          call.errorMessage = e.data.errorMessage
        }
      } else if (e.type === 'message_done') {
        assistant.status = 1
        if (e.data?.model) assistant.model = e.data.model
        assistant.tokenOutput = e.data?.tokenOutput ?? 0
        if (!hadConv && e.conversationId) activeId.value = e.conversationId
      } else if (e.type === 'error') {
        assistant.status = 2
        assistant.content = `[错误] ${e.data?.message ?? '生成失败'}`
      }
      scrollBottom()
    })
  } catch (err) {
    assistant.status = 2
    assistant.content = `[请求失败] ${err instanceof Error ? err.message : String(err)}`
  } finally {
    streaming.value = false
    await loadConversations()
    scrollBottom()
  }
}

function scrollBottom() {
  nextTick(() => { if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight })
}
function fmtTime(t?: string | null) {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(loadConversations)
</script>

<template>
  <div class="demo">
    <aside class="side">
      <div class="side-head">
        <span class="side-title">会话</span>
        <AfButton @click="newConversation">新建</AfButton>
      </div>
      <ul class="conv-list">
        <li v-for="c in conversations" :key="c.id"
            :class="['conv-item', { active: c.id === activeId }]" @click="openConversation(c.id)">
          <span class="conv-name">{{ c.title || '新会话' }}</span>
          <span class="conv-time">{{ fmtTime(c.updatedAt) }}</span>
        </li>
        <li v-if="!conversations.length" class="conv-empty">暂无会话</li>
      </ul>
    </aside>

    <main class="chat">
      <div class="chat-head">
        <span>AgentForge · SSE 流式验证</span>
        <span class="hint">{{ userStore.user?.username }}</span>
      </div>
      <div ref="scrollEl" class="msg-area">
        <div v-for="m in messages" :key="m.id" :class="['msg', m.role]">
          <div class="msg-role">{{ m.role === 'user' ? '你' : '助手' }}</div>
          <div class="msg-body">
            <span v-if="m.status === 0" class="typing">{{ m.content || '正在输入' }}<i class="caret" /></span>
            <span v-else>{{ m.content }}</span>
          </div>
          <div v-if="toolCalls[m.id]?.length" class="tool-stack">
            <div v-for="call in toolCalls[m.id]" :key="call.callId" class="tool-call">
              <div class="tool-head">
                <span class="tool-name">{{ call.toolName }}</span>
                <span :class="['tool-status', call.status]">
                  {{ call.status === 'running' ? '调用中' : call.status === 'success' ? '已完成' : call.status === 'timeout' ? '已超时' : '失败' }}
                </span>
              </div>
              <code>{{ JSON.stringify(call.arguments) }}</code>
              <div v-if="call.status !== 'running'" class="tool-foot">
                <span>{{ call.errorMessage || JSON.stringify(call.result) }}</span>
                <span>{{ call.durationMs }}ms</span>
              </div>
            </div>
          </div>
          <div v-if="m.status === 2" class="msg-err">生成失败</div>
        </div>
        <div v-if="!messages.length" class="msg-empty">在下方输入消息，体验流式输出</div>
      </div>
      <div class="composer">
        <textarea v-model="input" class="composer-input" placeholder="输入消息，Enter 发送"
                  @keydown.enter.exact.prevent="send" />
        <AfButton :disabled="!input.trim() || streaming" @click="send">
          {{ streaming ? '生成中…' : '发送' }}
        </AfButton>
      </div>
    </main>
  </div>
</template>

<style scoped>
.demo { display: flex; height: calc(100vh - 56px); }
.side { width: 248px; border-right: 1px solid var(--color-border); display: flex; flex-direction: column; }
.side-head { display: flex; align-items: center; justify-content: space-between; padding: 16px; border-bottom: 1px solid var(--color-border); }
.side-title { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--color-text-tertiary); }
.conv-list { list-style: none; margin: 0; padding: 8px; overflow-y: auto; flex: 1; }
.conv-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
.conv-item:hover { background: var(--color-surface); }
.conv-item.active { background: var(--color-surface); box-shadow: inset 2px 0 0 var(--color-text); }
.conv-name { font-size: 13px; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-time { font-size: 11px; color: var(--color-text-tertiary); }
.conv-empty { padding: 16px; font-size: 12px; color: var(--color-text-tertiary); text-align: center; }

.chat { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid var(--color-border); font-size: 13px; color: var(--color-text); }
.hint { font-size: 12px; color: var(--color-text-tertiary); }
.msg-area { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 18px; }
.msg { display: flex; flex-direction: column; gap: 4px; max-width: 720px; }
.msg.user { align-self: flex-end; align-items: flex-end; }
.msg-role { font-size: 11px; color: var(--color-text-tertiary); }
.msg-body { padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.6; white-space: pre-wrap; }
.msg.user .msg-body { background: var(--color-surface); border: 1px solid var(--color-border); }
.msg.assistant .msg-body { background: transparent; border: 1px solid var(--color-border); }
.tool-stack { display: flex; flex-direction: column; gap: 6px; margin-top: 4px; }
.tool-call { width: min(520px, 100%); padding: 10px 12px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface); }
.tool-head, .tool-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.tool-name { font-size: 12px; font-weight: 600; color: var(--color-text); }
.tool-status { font-size: 10px; color: var(--color-text-tertiary); }
.tool-status.running { animation: pulse 1.2s ease-in-out infinite; }
.tool-status.error, .tool-status.timeout { color: var(--color-danger); }
.tool-call code { display: block; margin-top: 6px; font-size: 11px; color: var(--color-text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-foot { margin-top: 7px; font-size: 10px; color: var(--color-text-tertiary); }
@keyframes pulse { 50% { opacity: .4; } }
.msg-err { font-size: 11px; color: var(--color-danger); }
.msg-empty { margin: auto; font-size: 13px; color: var(--color-text-tertiary); }

.typing .caret { display: inline-block; width: 6px; height: 14px; background: var(--color-text); margin-left: 2px; vertical-align: text-bottom; animation: blink 1s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }

.composer { display: flex; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--color-border); }
.composer-input { flex: 1; resize: none; height: 44px; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-bg); color: var(--color-text); font: inherit; font-size: 14px; }
.composer-input:focus { outline: none; border-color: var(--color-text); }
</style>
