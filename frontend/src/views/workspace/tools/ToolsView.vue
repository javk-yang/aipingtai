<script setup lang="ts">
/** 工具与技能治理：新增、编辑、启停、删除。 */
import { computed, onMounted, reactive, ref } from 'vue'
import { skillsApi, toolsApi, type SkillCreateRequest, type ToolCreateRequest } from '@/api/tools'
import { ExecutorTypeLabels } from '@/types/tools'
import type { SkillResponse, ToolResponse } from '@/types/tools'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfModal from '@/components/modal/AfModal.vue'

const tools = ref<ToolResponse[]>([])
const skills = ref<SkillResponse[]>([])
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const showToolForm = ref(false)
const showSkillForm = ref(false)
const editingTool = ref<ToolResponse | null>(null)
const editingSkill = ref<SkillResponse | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadProgress = ref(0)

const toolForm = reactive({ code: '', name: '', description: '', timeoutMs: '30000', inputSchema: '{}', outputSchema: '{}' })
const skillForm = reactive({ code: '', name: '', description: '', version: '1.0.0', triggers: '[]', content: '{}' })

const toolCount = computed(() => tools.value.length)
const skillCount = computed(() => skills.value.length)
const hasLoadedData = computed(() => tools.value.length > 0 || skills.value.length > 0)

async function load() {
  loading.value = true
  error.value = ''
  const failures: string[] = []
  try {
    const [toolResult, skillResult] = await Promise.allSettled([toolsApi.list(), skillsApi.list()])

    if (toolResult.status === 'fulfilled') {
      tools.value = Array.isArray(toolResult.value) ? toolResult.value : []
    } else {
      tools.value = []
      failures.push(`工具加载失败：${toolResult.reason instanceof Error ? toolResult.reason.message : String(toolResult.reason)}`)
    }

    if (skillResult.status === 'fulfilled') {
      skills.value = Array.isArray(skillResult.value) ? skillResult.value : []
    } else {
      skills.value = []
      failures.push(`技能加载失败：${skillResult.reason instanceof Error ? skillResult.reason.message : String(skillResult.reason)}`)
    }

    error.value = failures.join('；')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function triggerWords(s: SkillResponse): string[] {
  const words: string[] = []
  for (const t of s.triggers ?? []) {
    if (typeof t === 'string') words.push(t)
    else if (t && typeof t === 'object') Object.values(t).forEach((v) => typeof v === 'string' && words.push(v))
  }
  return words.slice(0, 6)
}
function parseObject(raw: string, label: string): Record<string, unknown> {
  const value = JSON.parse(raw || '{}')
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label} 必须是 JSON 对象`)
  return value
}
function parseArray(raw: string, label: string): Array<Record<string, unknown>> {
  const value = JSON.parse(raw || '[]')
  if (!Array.isArray(value)) throw new Error(`${label} 必须是 JSON 数组`)
  return value as Array<Record<string, unknown>>
}
function resetTool() {
  Object.assign(toolForm, { code: '', name: '', description: '', timeoutMs: '30000', inputSchema: '{}', outputSchema: '{}' })
  editingTool.value = null
  showToolForm.value = true
}
function editTool(t: ToolResponse) {
  Object.assign(toolForm, {
    code: t.code, name: t.name, description: t.description ?? '', timeoutMs: String(t.timeoutMs ?? 30000),
    inputSchema: JSON.stringify(t.inputSchema ?? {}, null, 2), outputSchema: JSON.stringify(t.outputSchema ?? {}, null, 2),
  })
  editingTool.value = t
  showToolForm.value = true
}
function resetSkill() {
  Object.assign(skillForm, { code: '', name: '', description: '', version: '1.0.0', triggers: '[]', content: '{}' })
  editingSkill.value = null
  showSkillForm.value = true
}
function editSkill(s: SkillResponse) {
  Object.assign(skillForm, {
    code: s.code, name: s.name, description: s.description ?? '', version: s.version ?? '1.0.0',
    triggers: JSON.stringify(s.triggers ?? [], null, 2), content: JSON.stringify(s.content ?? {}, null, 2),
  })
  editingSkill.value = s
  showSkillForm.value = true
}
async function saveTool() {
  try {
    saving.value = true
    const body: ToolCreateRequest = {
      code: toolForm.code.trim(), name: toolForm.name.trim(), description: toolForm.description.trim(),
      timeoutMs: Number(toolForm.timeoutMs), inputSchema: parseObject(toolForm.inputSchema, '输入 Schema'), outputSchema: parseObject(toolForm.outputSchema, '输出 Schema'),
    }
    if (!body.code || !body.name) throw new Error('工具编码和名称必填')
    if (editingTool.value) await toolsApi.update(editingTool.value.id, body)
    else await toolsApi.create(body)
    showToolForm.value = false
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) } finally { saving.value = false }
}
async function saveSkill() {
  try {
    saving.value = true
    const body: SkillCreateRequest = {
      code: skillForm.code.trim(), name: skillForm.name.trim(), description: skillForm.description.trim(), version: skillForm.version.trim(),
      triggers: parseArray(skillForm.triggers, '触发规则'), content: parseObject(skillForm.content, '技能内容'),
    }
    if (!body.code || !body.name) throw new Error('技能编码和名称必填')
    if (editingSkill.value) await skillsApi.update(editingSkill.value.id, body)
    else await skillsApi.create(body)
    showSkillForm.value = false
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) } finally { saving.value = false }
}
async function uploadSkill(file?: File) {
  const selected = file ?? fileInput.value?.files?.[0]
  if (!selected) return
  if (!selected.name.toLowerCase().endsWith('.skillzip')) {
    error.value = '请选择 .skillzip 技能包'
    return
  }
  try {
    uploading.value = true
    uploadProgress.value = 0
    await skillsApi.upload(selected, (percent) => { uploadProgress.value = percent })
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    uploading.value = false
    uploadProgress.value = 0
    if (fileInput.value) fileInput.value.value = ''
  }
}
async function toggleTool(t: ToolResponse) { try { await toolsApi.setStatus(t.id, !t.enabled); await load() } catch (e) { error.value = e instanceof Error ? e.message : String(e) } }
async function toggleSkill(s: SkillResponse) { try { await skillsApi.setStatus(s.id, !s.enabled); await load() } catch (e) { error.value = e instanceof Error ? e.message : String(e) } }
async function removeTool(t: ToolResponse) {
  if (!window.confirm(`确认删除工具「${t.name}」？`)) return
  try { await toolsApi.remove(t.id); await load() } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
}
async function removeSkill(s: SkillResponse) {
  if (s.builtin) return
  if (!window.confirm(`确认删除技能「${s.name}」？`)) return
  try { await skillsApi.remove(s.id); await load() } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
}

onMounted(load)
</script>

<template>
  <div class="tools-page">
    <div class="tools-hero">
      <div>
        <span class="eyebrow">治理中心</span>
        <h2 class="tools-title">工具与技能</h2>
        <p class="tools-intro">统一管理 Agent 可调用的工具、技能包与运行权限。</p>
      </div>
      <div class="tools-summary">
        <div class="summary-item"><strong>{{ toolCount }}</strong><span>工具</span></div>
        <div class="summary-divider" />
        <div class="summary-item"><strong>{{ skillCount }}</strong><span>技能</span></div>
      </div>
    </div>
    <p v-if="error" class="tools-error">{{ error }}</p>
    <section class="section">
      <div class="section-head">
        <div><h3 class="section-title">工具注册表</h3><span class="section-sub">连接外部能力并控制调用权限</span></div>
        <AfButton size="sm" @click="resetTool"><AfIcon name="plus" :size="13" /> 新建工具</AfButton>
      </div>
      <div class="grid">
        <div v-for="t in tools" :key="t.id" class="card">
          <div class="card-head"><span class="card-icon"><AfIcon name="settings" :size="15" /></span><div class="card-title-wrap"><span class="card-name">{{ t.name }}</span><span class="card-code mono">{{ t.code }}</span></div><span class="card-spacer" /><span :class="['enabled', t.enabled ? 'on' : 'off']"><i />{{ t.enabled ? '已启用' : '已停用' }}</span></div>
          <p class="card-desc">{{ t.description || '暂无描述' }}</p>
          <div class="card-meta"><span class="chip">{{ ExecutorTypeLabels[t.executorType] ?? t.executorType }}</span><span class="chip mono">{{ t.transport }}</span><span class="chip mono">{{ t.timeoutMs }}ms</span></div>
          <div class="card-actions"><button type="button" @click="editTool(t)"><AfIcon name="edit" :size="12" /> 编辑</button><button type="button" @click="toggleTool(t)">{{ t.enabled ? '停用' : '启用' }}</button><button type="button" class="danger" @click="removeTool(t)"><AfIcon name="trash" :size="12" /> 删除</button></div>
        </div>
        <p v-if="!tools.length && !loading" class="grid-empty">暂无工具，先创建一个工具连接。</p>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <div><h3 class="section-title">技能库</h3><span class="section-sub">支持手动创建或直接导入技能包</span></div>
        <div class="section-actions">
          <span class="upload-wrap">
            <input ref="fileInput" class="hidden-file" type="file" accept=".skillzip" @change="uploadSkill()" />
            <AfButton size="sm" variant="ghost" :disabled="uploading" @click="fileInput?.click()">
              <AfIcon name="upload" :size="13" /> {{ uploading ? `导入中 ${uploadProgress}%` : '导入 skillzip' }}
            </AfButton>
          </span>
          <AfButton size="sm" @click="resetSkill"><AfIcon name="plus" :size="13" /> 新建技能</AfButton>
        </div>
      </div>
      <div class="grid">
        <div v-for="s in skills" :key="s.id" class="card skill">
          <div class="card-head"><span class="card-icon skill"><AfIcon name="spark" :size="15" /></span><div class="card-title-wrap"><span class="card-name">{{ s.name }}</span><span class="card-code mono">{{ s.code }}</span></div><span class="card-spacer" /><span v-if="s.builtin" class="chip">内置</span><span :class="['enabled', s.enabled ? 'on' : 'off']"><i />{{ s.enabled ? '已启用' : '已停用' }}</span></div>
          <p class="card-desc">{{ s.description || '暂无描述' }}</p>
          <div class="card-meta"><span class="chip mono">v{{ s.version }}</span><span v-for="(w, i) in triggerWords(s)" :key="i" class="chip trigger mono">{{ w }}</span></div>
          <div class="card-actions"><button type="button" @click="editSkill(s)"><AfIcon name="edit" :size="12" /> 编辑</button><button type="button" @click="toggleSkill(s)">{{ s.enabled ? '停用' : '启用' }}</button><button v-if="!s.builtin" type="button" class="danger" @click="removeSkill(s)"><AfIcon name="trash" :size="12" /> 删除</button></div>
        </div>
        <p v-if="!skills.length && !loading" class="grid-empty">暂无技能，可导入 `.skillzip` 或新建技能。</p>
      </div>
    </section>
    <p v-if="loading" class="tools-loading">加载中…</p>
    <div v-else-if="!error && !hasLoadedData" class="tools-empty-state">
      <strong>暂无治理数据</strong>
      <span>当前账号还没有可展示的工具或技能，请刷新后重试。</span>
      <AfButton size="sm" variant="ghost" @click="load">重新加载</AfButton>
    </div>

    <AfModal :open="showToolForm" :title="editingTool ? '编辑工具' : '新建工具'" :width="620" @update:open="(v) => (showToolForm = v)">
      <div class="form"><label>编码<input v-model="toolForm.code" class="input" :disabled="!!editingTool" /></label><label>名称<input v-model="toolForm.name" class="input" /></label><label>描述<input v-model="toolForm.description" class="input" /></label><label>超时(ms)<input v-model="toolForm.timeoutMs" class="input" type="number" min="100" /></label><label>输入 Schema<textarea v-model="toolForm.inputSchema" class="input code" rows="5" /></label><label>输出 Schema<textarea v-model="toolForm.outputSchema" class="input code" rows="4" /></label></div>
      <template #footer><span class="footer-spacer" /><AfButton variant="ghost" size="sm" @click="showToolForm = false">取消</AfButton><AfButton size="sm" :disabled="saving" @click="saveTool">保存</AfButton></template>
    </AfModal>
    <AfModal :open="showSkillForm" :title="editingSkill ? '编辑技能' : '新建技能'" :width="680" @update:open="(v) => (showSkillForm = v)">
      <div class="form"><label>编码<input v-model="skillForm.code" class="input" :disabled="!!editingSkill" /></label><label>名称<input v-model="skillForm.name" class="input" /></label><label>描述<input v-model="skillForm.description" class="input" /></label><label>版本<input v-model="skillForm.version" class="input" /></label><label>触发规则(JSON 数组)<textarea v-model="skillForm.triggers" class="input code" rows="5" /></label><label>技能内容(JSON 对象)<textarea v-model="skillForm.content" class="input code" rows="8" /></label></div>
      <template #footer><span class="footer-spacer" /><AfButton variant="ghost" size="sm" @click="showSkillForm = false">取消</AfButton><AfButton size="sm" :disabled="saving" @click="saveSkill">保存</AfButton></template>
    </AfModal>
  </div>
</template>

<style scoped>
.tools-page{flex:1;overflow-y:auto;padding:clamp(20px,3vw,36px);display:flex;flex-direction:column;gap:28px;background:var(--color-bg)}
.tools-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;padding:4px 0 2px}
.eyebrow{display:block;margin-bottom:6px;color:var(--color-text-tertiary);font-size:11px;font-weight:var(--weight-medium);letter-spacing:.12em;text-transform:uppercase}
.tools-title{font-size:clamp(24px,3vw,32px);line-height:1.15}
.tools-intro{margin-top:8px;color:var(--color-text-secondary);font-size:var(--text-base)}
.tools-summary{display:flex;align-items:center;gap:18px;padding:12px 16px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface)}
.summary-item{display:flex;align-items:baseline;gap:7px}.summary-item strong{font-size:22px;font-weight:var(--weight-semibold)}.summary-item span{color:var(--color-text-tertiary);font-size:var(--text-sm)}.summary-divider{width:1px;height:24px;background:var(--color-border)}
.section{min-width:0}.section-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:12px}.section-title{font-size:var(--text-md);font-weight:var(--weight-semibold);letter-spacing:var(--tracking-tight)}.section-sub{display:block;margin-top:3px;color:var(--color-text-tertiary);font-size:var(--text-sm)}.section-actions{display:flex;align-items:center;gap:8px}.upload-wrap{display:inline-flex;align-items:center}.hidden-file{display:none}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,360px),1fr));gap:12px}.card{min-width:0;padding:16px;background:var(--color-surface);border:1px solid var(--color-border);border-radius:var(--radius-md);transition:border-color var(--transition-fast),transform var(--transition-fast)}.card:hover{border-color:var(--color-border-strong);transform:translateY(-1px)}
.card-head{display:flex;align-items:center;gap:10px;min-width:0}.card-icon{display:flex;align-items:center;justify-content:center;flex:0 0 30px;width:30px;height:30px;border-radius:8px;background:var(--color-surface-2);color:var(--color-text-secondary)}.card-icon.skill{color:var(--color-warning)}.card-title-wrap{display:flex;align-items:baseline;gap:8px;min-width:0}.card-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:var(--text-base);font-weight:var(--weight-medium)}.card-code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--color-text-tertiary);font-size:10px}.card-spacer{flex:1;min-width:8px}.card-desc{margin:14px 0;min-height:44px;color:var(--color-text-secondary);font-size:var(--text-sm);line-height:1.65}.card-meta{display:flex;flex-wrap:wrap;gap:6px;min-height:24px}.chip{padding:3px 8px;border-radius:999px;background:var(--color-surface-2);font-size:var(--text-xs);color:var(--color-text-secondary)}.chip.trigger{background:transparent;border:1px dashed var(--color-border-strong)}.enabled{display:inline-flex;align-items:center;gap:5px;flex:0 0 auto;font-size:var(--text-xs);white-space:nowrap}.enabled i{width:6px;height:6px;border-radius:50%;background:currentColor}.enabled.on{color:var(--color-success)}.enabled.off{color:var(--color-text-tertiary)}
.grid-empty,
.tools-loading,
.tools-empty-state {
  grid-column: 1 / -1;
  padding: 28px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-tertiary);
  text-align: center;
}
.tools-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.tools-empty-state strong {
  color: var(--color-text);
  font-size: var(--text-md);
}
.tools-error {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--color-danger-bg);
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.form{display:flex;flex-direction:column;gap:var(--space-3)}.form label{display:flex;flex-direction:column;gap:4px;color:var(--color-text-secondary);font-size:var(--text-sm)}.input{padding:8px 10px;border:1px solid var(--color-border);border-radius:var(--radius-sm);background:var(--color-bg);color:var(--color-text);font:inherit;font-size:var(--text-sm)}.input:focus{outline:none;border-color:var(--color-border-strong)}.input.code{font-family:monospace;font-size:11px;resize:vertical}.input:disabled{opacity:.65}.footer-spacer{flex:1}
@media (max-width:720px){.tools-page{padding:20px 16px;gap:22px}.tools-hero{display:block}.tools-summary{width:max-content;margin-top:16px}.section-head{align-items:flex-start;flex-direction:column}.section-actions{width:100%;justify-content:flex-end}.grid{grid-template-columns:1fr}.card-title-wrap{display:block}.card-code{display:block;margin-top:2px}}
</style>
