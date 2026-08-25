<script setup lang="ts">
/**
 * ModelManageView —— 模型配置管理（#87 生产化能力：大模型可选择 + 可管理）
 *
 * 能力：
 *   - 列表：展示全部模型配置，标识「默认 / 启用 / 禁用」
 *   - 新增 / 编辑：名称、供应商、模型、端点、API Key（脱敏展示 + 明文可改）、
 *     温度、最大 Token、启用、设为默认、备注
 *   - 连通性测试：调用后端 /models/test（草稿）或 /models/{id}/test（已存）
 *   - 设为默认 / 删除（带二次确认）
 *
 * 与后端约定：
 *   - update 为全量替换：编辑时必须回传全部字段，否则会被置空。
 *   - apiKey 传脱敏串（含 *）视为不修改原值；编辑态用现有脱敏值回填，用户改了才覆盖。
 *   - 设为默认：后端会清空同租户其它默认标记，无需前端处理排他。
 */
import { nextTick, onMounted, reactive, ref } from 'vue'
import { modelsApi } from '@/api/models'
import type { ModelConfig, ModelConfigRequest, ModelTestResult } from '@/types/model'
import { PROVIDER_OPTIONS } from '@/types/model'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfModal from '@/components/modal/AfModal.vue'

const models = ref<ModelConfig[]>([])
const loading = ref(false)
const errMsg = ref('')

/* ---------- 新增 / 编辑表单 ---------- */
const showForm = ref(false)
const editing = ref<ModelConfig | null>(null)
const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)
const showPwd = ref(false)
const listFailed = ref(false)

function blankForm() {
  return {
    name: '',
    provider: 'openai-compatible' as string,
    model: '',
    baseUrl: '',
    apiKey: '',
    temperature: '0.7',
    maxTokens: '1024',
    enabled: true,
    isDefault: false,
    description: '',
  }
}
const form = reactive(blankForm())

function providerLabel(v: string): string {
  return PROVIDER_OPTIONS.find((p) => p.value === v)?.label ?? v
}

function openCreate() {
  Object.assign(form, blankForm())
  editing.value = null
  testResult.value = null
  showPwd.value = false
  errMsg.value = ''
  showForm.value = true
}

function openEdit(m: ModelConfig) {
  Object.assign(form, {
    name: m.name,
    provider: m.provider,
    model: m.model,
    baseUrl: m.baseUrl ?? '',
    // 用现有脱敏值回填：未改动则后端视为不修改原值
    apiKey: m.apiKey ?? '',
    temperature: String(m.temperature),
    maxTokens: String(m.maxTokens),
    enabled: m.enabled === 1,
    isDefault: m.isDefault === 1,
    description: m.description ?? '',
  })
  editing.value = m
  testResult.value = null
  showPwd.value = false
  errMsg.value = ''
  showForm.value = true
}

function buildRequest(): ModelConfigRequest {
  return {
    name: form.name.trim(),
    provider: form.provider,
    model: form.model.trim(),
    baseUrl: form.baseUrl.trim() || undefined,
    // 空串 → undefined：新增时落库为 null；编辑时含 * 则后端不覆盖
    apiKey: form.apiKey || undefined,
    temperature: Number(form.temperature),
    maxTokens: Number(form.maxTokens),
    enabled: form.enabled ? 1 : 0,
    isDefault: form.isDefault,
    description: form.description.trim() || undefined,
  }
}

async function save() {
  errMsg.value = ''
  if (!form.name.trim()) return (errMsg.value = '请填写配置名称')
  if (!form.model.trim()) return (errMsg.value = '请填写模型名称')
  saving.value = true
  try {
    const req = buildRequest()
    if (editing.value) {
      await modelsApi.update(editing.value.id, req)
    } else {
      await modelsApi.create(req)
    }
    showForm.value = false
    await load()
  } catch (e) {
    errMsg.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

/* ---------- 连通性测试 ----------
 * 草稿 / 新建：用当前表单内容发到 /models/test
 * 编辑态且 API Key 仍是脱敏串：走 /models/{id}/test，让后端用数据库真实 key 测试
 * 编辑态且用户改了 key：用新 key 测试
 */
function isMaskedKey(v: string): boolean {
  return typeof v === 'string' && v.includes('****')
}

async function testConn() {
  if (testing.value) return
  errMsg.value = ''
  if (!form.model.trim()) return (errMsg.value = '请先填写模型名称再测试')
  testing.value = true
  testResult.value = null
  try {
    let res: ModelTestResult
    if (editing.value && isMaskedKey(form.apiKey)) {
      res = await modelsApi.testId(editing.value.id)
    } else {
      res = await modelsApi.test(buildRequest())
    }
    const detail = typeof res.detail === 'string' && res.detail ? ` (${res.detail})` : ''
    testResult.value = {
      ok: res.ok === true,
      message: `${res.message ?? (res.ok ? '连通成功' : '连通失败，请检查模型配置')}${detail}`,
    }
  } catch (e) {
    testResult.value = {
      ok: false,
      message: e instanceof Error ? `测试失败：${e.message}` : '测试失败，请稍后重试',
    }
  } finally {
    testing.value = false
  }
}

async function testSavedModel(m: ModelConfig) {
  if (testing.value) return
  openEdit(m)
  await nextTick()
  testing.value = true
  testResult.value = null
  try {
    const res = await modelsApi.testId(m.id)
    const detail = typeof res.detail === 'string' && res.detail ? ` (${res.detail})` : ''
    testResult.value = {
      ok: res.ok === true,
      message: `${res.message ?? (res.ok ? '连通成功' : '连通失败，请检查已保存配置')}${detail}`,
    }
  } catch (e) {
    testResult.value = {
      ok: false,
      message: e instanceof Error ? `测试失败：${e.message}` : '测试失败，请稍后重试',
    }
  } finally {
    testing.value = false
  }
}
async function setDefault(m: ModelConfig) {
  try {
    await modelsApi.update(m.id, {
      name: m.name,
      provider: m.provider,
      model: m.model,
      baseUrl: m.baseUrl ?? undefined,
      temperature: m.temperature,
      maxTokens: m.maxTokens,
      enabled: m.enabled,
      isDefault: true,
      description: m.description ?? undefined,
      // apiKey 省略 → 后端保留原值
    })
    await load()
  } catch (e) {
    errMsg.value = e instanceof Error ? e.message : '设为默认失败'
  }
}

/* ---------- 删除（二次确认） ---------- */
const confirmDel = ref<ModelConfig | null>(null)
async function confirmRemove() {
  if (!confirmDel.value) return
  try {
    await modelsApi.remove(confirmDel.value.id)
    confirmDel.value = null
    await load()
  } catch (e) {
    errMsg.value = e instanceof Error ? e.message : '删除失败'
  }
}

/* ---------- 列表 ---------- */
async function load() {
  loading.value = true
  listFailed.value = false
  errMsg.value = ''
  try {
    models.value = await modelsApi.list()
  } catch (e) {
    listFailed.value = true
    models.value = []
    errMsg.value = e instanceof Error ? e.message : '加载模型列表失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="models">
    <header class="models__head">
      <div>
        <h2 class="models__title">模型配置</h2>
        <p class="models__sub">管理对话可用的大模型；聊天页可随时切换，设为默认后新会话自动选用。</p>
      </div>
      <AfButton @click="openCreate">
        <AfIcon name="plus" :size="14" />
        新建模型
      </AfButton>
    </header>

    <p v-if="errMsg" class="models__err">{{ errMsg }}</p>

    <div class="models__table-wrap">
      <table class="models__table">
        <thead>
          <tr>
            <th>名称</th>
            <th>供应商</th>
            <th>模型</th>
            <th>端点</th>
            <th>状态</th>
            <th class="models__ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td>
              <span class="models__name">{{ m.name }}</span>
            </td>
            <td><span class="mono models__muted">{{ providerLabel(m.provider) }}</span></td>
            <td><span class="mono">{{ m.model }}</span></td>
            <td><span class="mono models__muted models__url">{{ m.baseUrl || '—' }}</span></td>
            <td>
              <span v-if="m.isDefault === 1" class="badge badge--default">默认</span>
              <span v-if="m.enabled === 1" class="badge badge--on">启用</span>
              <span v-else class="badge badge--off">禁用</span>
            </td>
            <td class="models__ops">
              <button type="button" class="op" title="测试连通性" :disabled="testing" @click="testSavedModel(m)">
                <AfIcon name="refresh" :size="13" /> 测试
              </button>
              <button type="button" class="op" @click="openEdit(m)">
                <AfIcon name="edit" :size="13" /> 编辑
              </button>
              <button
                v-if="m.isDefault !== 1"
                type="button"
                class="op"
                @click="setDefault(m)"
              >
                <AfIcon name="check" :size="13" /> 默认
              </button>
              <button type="button" class="op op--danger" @click="confirmDel = m">
                <AfIcon name="trash" :size="13" /> 删除
              </button>
            </td>
          </tr>
          <tr v-if="loading">
            <td colspan="6" class="models__empty">加载中…</td>
          </tr>
          <tr v-else-if="listFailed">
            <td colspan="6" class="models__empty">
              <span>模型列表加载失败，请重试。</span>
              <AfButton variant="ghost" size="sm" @click="load">重试</AfButton>
            </td>
          </tr>
          <tr v-else-if="!models.length">
            <td colspan="6" class="models__empty">暂无模型配置，点击「新建模型」添加</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新增 / 编辑 表单 -->
    <AfModal
      :open="showForm"
      :title="editing ? `编辑模型 · ${editing.name}` : '新建模型配置'"
      :width="560"
      @update:open="(v) => (showForm = v)"
    >
      <div class="form">
        <label class="field">
          <span class="field__label">配置名称 <i>*</i></span>
          <input v-model="form.name" class="field__input" placeholder="如：生产-GPT4o" />
        </label>

        <label class="field">
          <span class="field__label">供应商 <i>*</i></span>
          <select v-model="form.provider" class="field__input">
            <option v-for="p in PROVIDER_OPTIONS" :key="p.value" :value="p.value">
              {{ p.label }} — {{ p.hint }}
            </option>
          </select>
        </label>

        <label class="field">
          <span class="field__label">模型名称 <i>*</i></span>
          <input v-model="form.model" class="field__input" placeholder="如：gpt-4o / deepseek-chat" />
        </label>

        <label class="field">
          <span class="field__label">端点 baseUrl</span>
          <input v-model="form.baseUrl" class="field__input mono" placeholder="https://api.openai.com/v1" />
        </label>

        <label class="field">
          <span class="field__label">API Key</span>
          <div class="field__pwd">
            <input
              v-model="form.apiKey"
              :type="showPwd ? 'text' : 'password'"
              class="field__input mono"
              :placeholder="editing ? '留空/脱敏串表示不修改' : 'sk-...（确定性模型可留空）'"
            />
            <button type="button" class="field__pwd-btn" @click="showPwd = !showPwd">
              {{ showPwd ? '隐藏' : '显示' }}
            </button>
          </div>
        </label>

        <div class="field-row">
          <label class="field">
            <span class="field__label">温度</span>
            <input v-model.number="form.temperature" type="number" step="0.1" min="0" max="2" class="field__input" />
          </label>
          <label class="field">
            <span class="field__label">最大 Token</span>
            <input v-model.number="form.maxTokens" type="number" step="1" min="1" class="field__input" />
          </label>
        </div>

        <div class="field-row field-row--toggles">
          <label class="switch">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
          <label class="switch">
            <input v-model="form.isDefault" type="checkbox" />
            <span>设为默认模型</span>
          </label>
        </div>

        <label class="field">
          <span class="field__label">备注</span>
          <textarea v-model="form.description" class="field__input field__textarea" rows="2" placeholder="用途说明（可选）" />
        </label>

        <p v-if="testResult" :class="['test-result', testResult.ok ? 'is-ok' : 'is-err']">
          <AfIcon :name="testResult.ok ? 'check' : 'x'" :size="13" />
          {{ testResult.message }}
        </p>
      </div>

      <template #footer>
        <AfButton variant="ghost" size="sm" :disabled="testing" @click="testConn">
          <AfIcon name="refresh" :size="13" />
          {{ testing ? '测试中…' : '测试连通性' }}
        </AfButton>
        <span class="footer-spacer" />
        <AfButton variant="ghost" size="sm" @click="showForm = false">取消</AfButton>
        <AfButton size="sm" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </AfButton>
      </template>
    </AfModal>

    <!-- 删除确认 -->
    <AfModal
      :open="!!confirmDel"
      title="删除模型配置"
      :width="420"
      @update:open="(v) => !v && (confirmDel = null)"
    >
      <p class="confirm-text">
        确认删除「<b>{{ confirmDel?.name }}</b>」？该操作不可撤销，正在使用它的会话将回退到默认模型。
      </p>
      <template #footer>
        <span class="footer-spacer" />
        <AfButton variant="ghost" size="sm" @click="confirmDel = null">取消</AfButton>
        <AfButton variant="danger" size="sm" @click="confirmRemove">删除</AfButton>
      </template>
    </AfModal>
  </div>
</template>

<style scoped>
.models {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: var(--space-6);
  overflow: auto;
}
.models__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}
.models__title {
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
  margin: 0;
}
.models__sub {
  margin: 4px 0 0;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
  max-width: 640px;
}
.models__err {
  margin: 0 0 var(--space-4);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--color-danger-bg);
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.models__table-wrap {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}
.models__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}
.models__table thead th {
  text-align: left;
  padding: var(--space-3) var(--space-4);
  font-weight: var(--weight-medium);
  color: var(--color-text-tertiary);
  background: var(--color-surface-2);
  border-bottom: 1px solid var(--color-border);
}
.models__table tbody td {
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text);
  vertical-align: middle;
}
.models__table tbody tr:last-child td {
  border-bottom: none;
}
.models__name {
  font-weight: var(--weight-medium);
}
.models__muted {
  color: var(--color-text-tertiary);
}
.models__url {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}
.models__ops {
  white-space: nowrap;
}
.models__ops .op {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-right: var(--space-2);
  padding: 3px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}
.models__ops .op:hover {
  color: var(--color-text);
  border-color: var(--color-border-strong);
}
.models__ops .op--danger:hover {
  color: var(--color-danger);
  border-color: var(--color-danger);
}
.models__empty {
  text-align: center;
  color: var(--color-text-tertiary);
  padding: var(--space-6);
}
.badge {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: var(--weight-medium);
}
.badge--default {
  background: var(--color-primary);
  color: var(--color-on-primary);
}
.badge--on {
  background: var(--color-success-bg);
  color: var(--color-success);
}
.badge--off {
  background: var(--color-surface-2);
  color: var(--color-text-tertiary);
}

/* ---------- 表单 ---------- */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field__label {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}
.field__label i {
  color: var(--color-danger);
  font-style: normal;
}
.field__input {
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  color: var(--color-text);
  font: inherit;
  font-size: var(--text-sm);
  transition: border-color var(--transition-fast);
}
.field__input:focus {
  outline: none;
  border-color: var(--color-text);
}
.field__textarea {
  resize: vertical;
}
.field-row {
  display: flex;
  gap: var(--space-3);
}
.field-row .field {
  flex: 1;
}
.field-row--toggles {
  gap: var(--space-6);
}
.field__pwd {
  display: flex;
  gap: var(--space-2);
}
.field__pwd .field__input {
  flex: 1;
}
.field__pwd-btn {
  flex-shrink: 0;
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
}
.switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
}
.test-result {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
}
.test-result.is-ok {
  background: var(--color-success-bg);
  color: var(--color-success);
}
.test-result.is-err {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.footer-spacer {
  flex: 1;
}
.confirm-text {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.7;
}
</style>
