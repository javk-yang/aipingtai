<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { agentsApi, type AgentResponse, type AgentUpsertRequest } from '@/api/agents'
import { modelsApi } from '@/api/models'
import { toolsApi, skillsApi } from '@/api/tools'
import type { ModelConfig } from '@/types/model'
import type { ToolResponse, SkillResponse } from '@/types/tools'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfModal from '@/components/modal/AfModal.vue'

const agents = ref<AgentResponse[]>([])
const models = ref<ModelConfig[]>([])
const tools = ref<ToolResponse[]>([])
const skills = ref<SkillResponse[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const showForm = ref(false)
const editing = ref<AgentResponse | null>(null)

const form = reactive({
  code: '', name: '', description: '', agentType: 'chat', systemPrompt: '', modelConfigId: '',
  toolIds: [] as number[], skillIds: [] as number[], enabled: false, defaultAgent: false,
})

function resetForm() {
  Object.assign(form, { code: '', name: '', description: '', agentType: 'chat', systemPrompt: '', modelConfigId: '', toolIds: [], skillIds: [], enabled: false, defaultAgent: false })
  editing.value = null
  showForm.value = true
}
function editAgent(agent: AgentResponse) {
  Object.assign(form, { code: agent.code, name: agent.name, description: agent.description ?? '', agentType: agent.agentType, systemPrompt: agent.systemPrompt ?? '', modelConfigId: agent.modelConfigId ? String(agent.modelConfigId) : '', toolIds: [...agent.toolIds], skillIds: [...agent.skillIds], enabled: agent.status === 2, defaultAgent: agent.isDefault === 1 })
  editing.value = agent
  showForm.value = true
}
function toggleId(list: number[], id: number) {
  const index = list.indexOf(id)
  if (index >= 0) list.splice(index, 1)
  else list.push(id)
}
function buildBody(): AgentUpsertRequest {
  const body: AgentUpsertRequest = { code: form.code.trim(), name: form.name.trim(), description: form.description.trim(), agentType: form.agentType, systemPrompt: form.systemPrompt, toolIds: form.toolIds, skillIds: form.skillIds, enabled: form.enabled, defaultAgent: form.defaultAgent }
  if (form.modelConfigId) body.modelConfigId = Number(form.modelConfigId)
  return body
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const [a, m, t, s] = await Promise.all([agentsApi.list(), modelsApi.list(), toolsApi.list(), skillsApi.list()])
    agents.value = a; models.value = m.filter((x) => x.enabled === 1); tools.value = t.filter((x) => x.enabled); skills.value = s.filter((x) => x.enabled)
  } catch (e) { error.value = e instanceof Error ? e.message : '智能体加载失败' } finally { loading.value = false }
}
async function save() {
  if (!form.code.trim() || !form.name.trim()) { error.value = '智能体编码和名称必填'; return }
  saving.value = true; error.value = ''
  try { if (editing.value) await agentsApi.update(editing.value.id, buildBody()); else await agentsApi.create(buildBody()); showForm.value = false; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '保存智能体失败' } finally { saving.value = false }
}
async function publish(agent: AgentResponse) { try { await agentsApi.publish(agent.id); await load() } catch (e) { error.value = e instanceof Error ? e.message : '发布失败' } }
async function toggle(agent: AgentResponse) { try { await agentsApi.setStatus(agent.id, agent.status !== 2); await load() } catch (e) { error.value = e instanceof Error ? e.message : '更新状态失败' } }
async function remove(agent: AgentResponse) { if (!window.confirm(`确认删除智能体「${agent.name}」？`)) return; try { await agentsApi.remove(agent.id); await load() } catch (e) { error.value = e instanceof Error ? e.message : '删除失败' } }
function statusLabel(status: number) { return status === 2 ? '已发布' : status === 3 ? '已停用' : '草稿' }

onMounted(load)
</script>

<template>
  <div class="agents-page">
    <div class="agents-head">
      <div><div class="eyebrow mono">AGENT BUILDER / P11</div><h2>智能体管理</h2><p>创建可复用的角色、提示词与能力组合，并发布到会话工作台。</p></div>
      <AfButton size="sm" @click="resetForm"><AfIcon name="plus" :size="13" /> 新建智能体</AfButton>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="loading" class="empty">加载中…</p>
    <section v-else class="agent-grid">
      <article v-for="agent in agents" :key="agent.id" class="agent-card">
        <div class="card-top"><span class="agent-mark"><AfIcon name="robot" :size="17" /></span><div class="card-title"><strong>{{ agent.name }}</strong><span class="mono">{{ agent.code }}</span></div><span class="status" :class="`status-${agent.status}`">{{ statusLabel(agent.status) }}</span></div>
        <p class="desc">{{ agent.description || '暂无描述。' }}</p>
        <div class="meta"><span class="chip">{{ agent.agentType }}</span><span class="chip">v{{ agent.version || 'draft' }}</span><span v-if="agent.modelConfigId" class="chip">模型 #{{ agent.modelConfigId }}</span><span v-if="agent.isDefault" class="chip default">默认</span></div>
        <div class="bindings"><span>{{ agent.toolIds.length }} 工具</span><span>{{ agent.skillIds.length }} 技能</span><span>{{ agent.knowledgeDocIds.length }} 知识文档</span></div>
        <div class="actions"><button type="button" @click="editAgent(agent)"><AfIcon name="edit" :size="12" /> 编辑</button><button type="button" @click="publish(agent)">{{ agent.status === 2 ? '重新发布' : '发布' }}</button><button type="button" @click="toggle(agent)">{{ agent.status === 2 ? '停用' : '启用' }}</button><button type="button" class="danger" @click="remove(agent)">删除</button></div>
      </article>
      <p v-if="!agents.length" class="empty">还没有智能体，先创建一个角色配置。</p>
    </section>

    <AfModal :open="showForm" :title="editing ? '编辑智能体' : '新建智能体'" :width="760" :mask-closable="false" @update:open="(v) => (showForm = v)">
      <div class="form-grid">
        <label>编码<input v-model="form.code" class="input" :disabled="!!editing" placeholder="例如 research_assistant" /></label>
        <label>名称<input v-model="form.name" class="input" placeholder="例如 研究助理" /></label>
        <label>类型<select v-model="form.agentType" class="input"><option value="chat">对话型</option><option value="workflow">工作流</option><option value="autonomous">自主型</option></select></label>
        <label>默认模型<select v-model="form.modelConfigId" class="input"><option value="">跟随平台默认</option><option v-for="m in models" :key="m.id" :value="String(m.id)">{{ m.name }} · {{ m.model }}</option></select></label>
        <label class="wide">描述<input v-model="form.description" class="input" placeholder="说明这个智能体适合处理什么问题" /></label>
        <label class="wide">系统提示词<textarea v-model="form.systemPrompt" class="input code" rows="7" placeholder="定义角色、目标、边界和回答风格" /></label>
        <fieldset class="wide"><legend>绑定工具</legend><div class="check-grid"><label v-for="t in tools" :key="t.id" class="check"><input type="checkbox" :checked="form.toolIds.includes(t.id)" @change="toggleId(form.toolIds, t.id)" /> {{ t.name }} <span class="mono">{{ t.code }}</span></label><span v-if="!tools.length" class="muted">暂无可用工具</span></div></fieldset>
        <fieldset class="wide"><legend>绑定技能</legend><div class="check-grid"><label v-for="s in skills" :key="s.id" class="check"><input type="checkbox" :checked="form.skillIds.includes(s.id)" @change="toggleId(form.skillIds, s.id)" /> {{ s.name }} <span class="mono">{{ s.code }}</span></label><span v-if="!skills.length" class="muted">暂无可用技能</span></div></fieldset>
        <label class="toggle wide"><input v-model="form.enabled" type="checkbox" /> 保存后直接启用</label>
        <label class="toggle wide"><input v-model="form.defaultAgent" type="checkbox" /> 设为默认智能体</label>
      </div>
      <template #footer><span class="spacer" /><AfButton variant="ghost" size="sm" @click="showForm = false">取消</AfButton><AfButton size="sm" :disabled="saving" @click="save">保存配置</AfButton></template>
    </AfModal>
  </div>
</template>

<style scoped>
.agents-page{flex:1;overflow-y:auto;padding:var(--space-6);display:flex;flex-direction:column;gap:var(--space-5)}.agents-head{display:flex;justify-content:space-between;align-items:flex-start;gap:var(--space-4)}h2{font-size:var(--text-xl);letter-spacing:var(--tracking-tight);margin:4px 0}.agents-head p,.desc{color:var(--color-text-secondary);line-height:1.6}.eyebrow{color:var(--color-text-tertiary);font-size:11px}.agent-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(350px,1fr));gap:var(--space-3)}.agent-card{padding:var(--space-4);background:var(--color-surface);border:1px solid var(--color-border);border-radius:var(--radius-md)}.card-top{display:flex;align-items:center;gap:var(--space-2)}.agent-mark{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;background:var(--color-primary);color:var(--color-on-primary)}.card-title{display:flex;flex-direction:column;gap:2px;min-width:0}.card-title strong{font-size:var(--text-md)}.card-title span{font-size:10px;color:var(--color-text-tertiary)}.status{margin-left:auto;font-size:var(--text-xs)}.status-2{color:var(--color-success)}.status-1{color:var(--color-warning)}.status-3{color:var(--color-text-tertiary)}.desc{min-height:46px;margin:var(--space-3) 0}.meta,.bindings,.actions{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.chip{padding:2px 8px;border-radius:99px;background:var(--color-surface-2);font-size:var(--text-xs);color:var(--color-text-secondary)}.chip.default{color:var(--color-primary);border:1px solid var(--color-border-strong)}.bindings{padding:var(--space-3) 0;color:var(--color-text-tertiary);font-size:var(--text-xs);border-top:1px solid var(--color-border);margin-top:var(--space-3)}.actions{padding-top:var(--space-2);border-top:1px solid var(--color-border)}.actions button{display:inline-flex;gap:3px;align-items:center;border:0;background:transparent;color:var(--color-text-secondary);font-size:var(--text-xs);cursor:pointer}.actions button:hover{color:var(--color-text)}.actions .danger:hover{color:var(--color-danger)}.error{padding:var(--space-2) var(--space-3);background:var(--color-danger-bg);color:var(--color-danger);border-radius:var(--radius-sm)}.empty{text-align:center;color:var(--color-text-tertiary);padding:var(--space-8)}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:var(--space-3)}.form-grid label{display:flex;flex-direction:column;gap:4px;font-size:var(--text-sm);color:var(--color-text-secondary)}.wide{grid-column:1/-1}.input{padding:8px 10px;border:1px solid var(--color-border);border-radius:var(--radius-sm);background:var(--color-bg);color:var(--color-text);font:inherit;font-size:var(--text-sm)}.input:focus{outline:none;border-color:var(--color-text)}.input.code{font-family:var(--font-mono);resize:vertical}fieldset{border:1px solid var(--color-border);border-radius:var(--radius-sm);padding:var(--space-3)}legend{padding:0 4px;color:var(--color-text-secondary);font-size:var(--text-sm)}.check-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}.check{display:block!important;font-size:var(--text-xs)!important}.check input{margin-right:4px}.check .mono{color:var(--color-text-tertiary);font-size:10px}.muted{color:var(--color-text-tertiary);font-size:var(--text-xs)}.toggle{display:block!important}.toggle input{margin-right:6px}.spacer{flex:1}@media(max-width:700px){.agents-head{flex-direction:column}.form-grid{grid-template-columns:1fr}.wide{grid-column:auto}.check-grid{grid-template-columns:1fr}}
</style>
