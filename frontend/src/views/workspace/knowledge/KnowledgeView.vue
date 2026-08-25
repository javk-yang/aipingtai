<script setup lang="ts">
/**
 * KnowledgeView —— 知识库页（P11 / P12 RAG）
 *
 * 能力：
 *   1. 文档列表：标题 / 分块数 / 状态 / 创建时间，可删除
 *   2. 新建文档：标题 + 正文粘贴（服务端分块入库）
 *   3. 检索溯源：输入问题 → 命中 chunk 列表（相似度分数 + 来源文档）
 *
 * 数据源：/api/knowledge（CRUD）+ /api/knowledge/search（检索）
 */
import { onMounted, ref } from 'vue'
import { knowledgeApi } from '@/api/knowledge'
import type { ChunkHit, KnowledgeResponse, KnowledgeSearchResult } from '@/types/knowledge'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'

const docs = ref<KnowledgeResponse[]>([])
const loading = ref(false)
const error = ref('')

/* ---------- 新建弹窗 ---------- */
const showCreate = ref(false)
const creating = ref(false)
const createTitle = ref('')
const createText = ref('')
const createError = ref('')

/* ---------- 检索 ---------- */
const query = ref('')
const topK = ref(3)
const searching = ref(false)
const searchResult = ref<KnowledgeSearchResult | null>(null)

async function loadDocs() {
  loading.value = true
  error.value = ''
  try {
    docs.value = await knowledgeApi.list()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  createTitle.value = ''
  createText.value = ''
  createError.value = ''
  showCreate.value = true
}

async function submitCreate() {
  const title = createTitle.value.trim()
  const text = createText.value.trim()
  if (!title) {
    createError.value = '标题必填'
    return
  }
  if (!text) {
    createError.value = '内容必填'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    await knowledgeApi.create({ title, text })
    showCreate.value = false
    await loadDocs()
  } catch (e) {
    createError.value = e instanceof Error ? e.message : String(e)
  } finally {
    creating.value = false
  }
}

async function removeDoc(docId: string) {
  await knowledgeApi.remove(docId)
  await loadDocs()
}

async function doSearch() {
  const q = query.value.trim()
  if (!q) return
  searching.value = true
  try {
    searchResult.value = await knowledgeApi.search({ query: q, topK: topK.value })
  } catch (e) {
    searchResult.value = {
      query: q,
      count: 0,
      results: [],
    }
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    searching.value = false
  }
}

function fmtTime(t?: string | null): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function pct(score: number): string {
  return `${Math.round(score * 100)}%`
}

function highlightChunk(hit: ChunkHit): string {
  const q = query.value.trim()
  const text = hit.text
  if (!q) return text
  const idx = text.toLowerCase().indexOf(q.toLowerCase())
  if (idx === -1) return text
  const start = Math.max(0, idx - 30)
  const end = Math.min(text.length, idx + q.length + 60)
  return `${start > 0 ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}`
}

onMounted(loadDocs)
</script>

<template>
  <div class="kb">
    <!-- 顶部工具栏 -->
    <div class="kb-toolbar">
      <div class="kb-search">
        <AfIcon name="search" :size="15" class="kb-search-icon" />
        <input
          v-model="query"
          class="kb-search-input"
          placeholder="检索知识库（RAG 溯源）…"
          @keydown.enter.prevent="doSearch"
        />
        <select v-model="topK" class="kb-topk">
          <option :value="1">top 1</option>
          <option :value="3">top 3</option>
          <option :value="5">top 5</option>
        </select>
        <AfButton :disabled="!query.trim()" @click="doSearch">
          <AfIcon name="arrow-right" :size="13" />
          检索
        </AfButton>
      </div>
      <AfButton variant="secondary" @click="openCreate">
        <AfIcon name="plus" :size="14" />
        新建文档
      </AfButton>
    </div>

    <p v-if="error" class="kb-error">{{ error }}</p>

    <div class="kb-body">
      <!-- 检索结果 -->
      <section v-if="searchResult" class="kb-col search-col">
        <div class="kb-col-head">
          <h2 class="kb-col-title">检索结果</h2>
          <span class="kb-col-sub">{{ searchResult.count }} 个命中 chunk</span>
        </div>
        <div class="hit-list">
          <div v-for="hit in searchResult.results" :key="hit.chunkId" class="hit-card">
            <div class="hit-head">
              <span class="hit-title">{{ hit.title }}</span>
              <span class="hit-score">{{ pct(hit.score) }}</span>
            </div>
            <p class="hit-text">{{ highlightChunk(hit) }}</p>
            <p class="hit-meta mono">doc {{ hit.docId }} · chunk {{ hit.chunkId }}</p>
          </div>
          <p v-if="!searchResult.results.length" class="hit-empty">没有命中，换个问法试试</p>
        </div>
      </section>

      <!-- 文档列表 -->
      <section class="kb-col">
        <div class="kb-col-head">
          <h2 class="kb-col-title">文档库</h2>
          <span class="kb-col-sub">{{ docs.length }} 个文档</span>
        </div>
        <div class="doc-list">
          <div v-for="doc in docs" :key="doc.docId" class="doc-card">
            <div class="doc-head">
              <span class="doc-title">{{ doc.title }}</span>
              <button type="button" class="doc-del" title="删除文档" @click="removeDoc(doc.docId)">
                <AfIcon name="trash" :size="13" />
              </button>
            </div>
            <div class="doc-meta">
              <span class="doc-chunks">{{ doc.chunkCount }} chunks</span>
              <span :class="['doc-status', doc.status === 1 ? 'ok' : 'pending']">
                {{ doc.status === 1 ? '已就绪' : '处理中' }}
              </span>
            </div>
            <p class="doc-time mono">{{ fmtTime(doc.createdAt) }}</p>
          </div>
          <p v-if="!docs.length && !loading" class="doc-empty">
            暂无文档，点击右上角「新建文档」粘贴内容分块入库
          </p>
          <p v-if="loading" class="doc-empty">加载中…</p>
        </div>
      </section>
    </div>

    <!-- 新建文档弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <div class="modal-head">
          <h3 class="modal-title">新建知识文档</h3>
          <button type="button" class="modal-close" @click="showCreate = false">
            <AfIcon name="x" :size="15" />
          </button>
        </div>
        <div class="modal-body">
          <input
            v-model="createTitle"
            class="kb-input"
            placeholder="文档标题（必填）"
            maxlength="128"
          />
          <textarea
            v-model="createText"
            class="kb-textarea"
            placeholder="粘贴文档正文，保存后按语义分块入库…"
          />
          <p class="kb-note">服务端自动分块（chunk）并向量化，供 Agent 检索溯源</p>
          <p v-if="createError" class="kb-error">{{ createError }}</p>
        </div>
        <div class="modal-foot">
          <AfButton variant="ghost" @click="showCreate = false">取消</AfButton>
          <AfButton :loading="creating" @click="submitCreate">
            保存入库
          </AfButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* 工具栏 */
.kb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
.kb-search {
  flex: 1;
  max-width: 560px;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 0 0 var(--space-3);
  height: var(--control-height);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
}
.kb-search:focus-within {
  border-color: var(--color-text);
}
.kb-search-icon {
  color: var(--color-text-tertiary);
}
.kb-search-input {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--color-text);
  font: inherit;
  font-size: var(--text-md);
}
.kb-search-input:focus {
  outline: none;
}
.kb-topk {
  height: 26px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
}

/* 主体两列 */
.kb-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  min-height: 0;
}
.kb-col {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.kb-col-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.kb-col-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
}
.kb-col-sub {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}
.search-col {
  border-right: 1px solid var(--color-border);
  padding-right: var(--space-4);
}

/* 检索命中 */
.hit-list,
.doc-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.hit-card {
  padding: var(--space-3);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.hit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.hit-title {
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.hit-score {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-success);
}
.hit-text {
  font-size: var(--text-base);
  line-height: 1.7;
  color: var(--color-text-secondary);
  margin: 0;
}
.hit-meta {
  margin-top: 6px;
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.hit-empty,
.doc-empty {
  padding: var(--space-8);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-base);
}

/* 文档卡片 */
.doc-card {
  padding: var(--space-3);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: border-color var(--transition-fast);
}
.doc-card:hover {
  border-color: var(--color-border-strong);
}
.doc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.doc-title {
  font-size: var(--text-base);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.doc-del {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast);
}
.doc-card:hover .doc-del {
  opacity: 1;
}
.doc-del:hover {
  color: var(--color-danger);
}
.doc-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: 4px;
}
.doc-chunks {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}
.doc-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
}
.doc-status.ok {
  color: var(--color-success);
  background: transparent;
}
.doc-status.pending {
  color: var(--color-warning);
  background: var(--color-surface-2);
}
.doc-time {
  margin-top: 6px;
  font-size: 10px;
  color: var(--color-text-tertiary);
}

/* 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal {
  width: 520px;
  max-width: calc(100vw - 48px);
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-modal);
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}
.modal-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
}
.modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
}
.modal-close:hover {
  background: var(--color-surface-2);
}
.modal-body {
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-4);
  border-top: 1px solid var(--color-border);
}
.kb-input {
  height: var(--control-height);
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  color: var(--color-text);
  font: inherit;
  font-size: var(--text-md);
}
.kb-input:focus,
.kb-textarea:focus {
  outline: none;
  border-color: var(--color-text);
}
.kb-textarea {
  height: 160px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  color: var(--color-text);
  font: inherit;
  font-size: var(--text-base);
  line-height: 1.7;
  resize: vertical;
}
.kb-note {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}
.kb-error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
</style>
