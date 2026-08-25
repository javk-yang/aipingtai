<script setup lang="ts">
/**
 * AfMarkdown —— 轻量 Markdown 渲染组件（P11）
 *
 * 决策：不引 markdown-it / highlight.js。
 * 平台展示的消息内容是可信任的（Agent 引擎输出），但仍是用户输入派生内容，
 * 所以先整体 escapeHTML 再在安全 token 上做格式替换，杜绝 XSS。
 *
 * 支持：
 *  - 代码块 ```lang（深色底 + 轻量语法高亮 + 行号 + 复制）
 *  - 标题 #/##/### / 无序列表 / 有序列表 / 引用 / 分隔线
 *  - 行内：**粗体** / `code` / [链接](url)
 *
 * 流式场景：父组件把 delta 追加进 content，本组件整体重渲染——
 * 代码块未闭合时按普通文本显示，闭合后自动变高亮块。
 */
import { computed } from 'vue'

const props = defineProps<{ content: string }>()

/** 转义 HTML，杜绝注入 */
function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 行内解析：在已转义文本上做安全替换 */
function inline(text: string): string {
  let out = text
  // 行内代码 `code`（先处理，避免和粗体标记冲突）
  out = out.replace(/`([^`]+)`/g, (_, code: string) => `<code class="md-inline">${code}</code>`)
  // 粗体 **x**
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 链接 [text](url)
  out = out.replace(
    /\[([^\]]+)\]\(([^)\s]+)\)/g,
    (_, label: string, url: string) =>
      `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`,
  )
  return out
}

/* ---------- 轻量语法高亮（按语言选关键词集） ---------- */
const KEYWORDS: Record<string, string[]> = {
  python: ['def', 'return', 'import', 'from', 'class', 'if', 'elif', 'else', 'for', 'while', 'with', 'as', 'None', 'True', 'False', 'print', 'async', 'await', 'try', 'except', 'finally', 'lambda', 'pass', 'yield', 'in', 'not', 'and', 'or', 'is', 'global', 'raise', 'del'],
  java: ['public', 'private', 'protected', 'class', 'interface', 'static', 'final', 'void', 'return', 'new', 'if', 'else', 'for', 'while', 'extends', 'implements', 'import', 'package', 'throw', 'throws', 'try', 'catch', 'this', 'super', 'enum', 'record', 'var', 'boolean', 'int', 'long', 'String', 'double', 'float', 'null', 'true', 'false'],
  typescript: ['const', 'let', 'var', 'function', 'return', 'import', 'export', 'from', 'if', 'else', 'for', 'while', 'class', 'async', 'await', 'new', 'type', 'interface', 'extends', 'implements', 'enum', 'null', 'undefined', 'true', 'false', 'keyof', 'typeof', 'readonly', 'public', 'private'],
  javascript: ['const', 'let', 'var', 'function', 'return', 'import', 'export', 'from', 'if', 'else', 'for', 'while', 'class', 'async', 'await', 'new', 'typeof', 'null', 'undefined', 'true', 'false', 'try', 'catch', 'throw'],
  json: [],
  bash: ['if', 'then', 'else', 'fi', 'for', 'in', 'do', 'done', 'echo', 'export', 'cd', 'function', 'return'],
  sql: ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'FROM', 'WHERE', 'AND', 'OR', 'JOIN', 'ON', 'GROUP', 'BY', 'ORDER', 'LIMIT', 'CREATE', 'TABLE', 'INTO', 'VALUES', 'SET', 'AS', 'NULL', 'PRIMARY', 'KEY', 'INDEX'],
}

/** 高亮单行代码，返回 HTML（入参必须已转义） */
function highlightLine(line: string, keywords: string[]): string {
  // 注释（# 或 // 起始）整行处理
  if (/^\s*(#|\/\/)/.test(line)) return `<span class="tk-comment">${line}</span>`
  const kw = new Set(keywords)
  let out = ''
  let i = 0
  while (i < line.length) {
    const ch = line[i]
    // 字符串
    if (ch === '"' || ch === "'") {
      const quote = ch
      let j = i + 1
      while (j < line.length && line[j] !== quote) j++
      out += `<span class="tk-string">${esc(line.slice(i, j + 1))}</span>`
      i = j + 1
      continue
    }
    // 数字
    if (/[0-9]/.test(ch)) {
      let j = i
      while (j < line.length && /[0-9a-fA-FxX_.]/.test(line[j])) j++
      out += `<span class="tk-number">${esc(line.slice(i, j))}</span>`
      i = j
      continue
    }
    // 标识符：关键字 / 函数名 / 原样
    if (/[A-Za-z_\u4e00-\u9fa5]/.test(ch)) {
      let j = i
      while (j < line.length && /[A-Za-z0-9_\u4e00-\u9fa5]/.test(line[j])) j++
      const word = line.slice(i, j)
      const isKw = kw.has(word)
      const isFunc = j < line.length && line[j] === '('
      out += isKw
        ? `<span class="tk-keyword">${esc(word)}</span>`
        : isFunc
          ? `<span class="tk-func">${esc(word)}</span>`
          : esc(word)
      i = j
      continue
    }
    out += esc(ch)
    i++
  }
  return out
}

/** 代码块高亮：按行处理，输出带行号栅格 */
function highlightCode(code: string, lang: string): string {
  const keywords = KEYWORDS[lang.toLowerCase()] ?? KEYWORDS.python
  const lines = code.replace(/\n$/, '').split('\n')
  return lines
    .map((line, idx) => {
      const body = highlightLine(esc(line), keywords)
      return `<div class="code-row"><span class="code-ln">${idx + 1}</span><span class="code-line">${body || ' '}</span></div>`
    })
    .join('')
}

/** 渲染为 HTML：块级解析 + 行内解析 */
const html = computed(() => {
  const src = props.content ?? ''
  const lines = src.split('\n')
  const blocks: string[] = []
  let buf: string[] = [] // 段落缓冲
  let i = 0

  const flushPara = () => {
    if (!buf.length) return
    blocks.push(`<p>${inline(esc(buf.join('\n')))}</p>`)
    buf = []
  }

  while (i < lines.length) {
    const line = lines[i]
    // 代码块
    const fence = line.match(/^```(\w*)/)
    if (fence) {
      flushPara()
      const lang = fence[1] || ''
      const codeLines: string[] = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i])
        i++
      }
      i++ // 跳过闭合 fence
      blocks.push(
        `<div class="md-code" data-lang="${esc(lang || 'code')}">` +
          `<div class="md-code-head"><span>${esc(lang || 'code')}</span>` +
          `<button type="button" class="md-copy" data-copy>复制</button></div>` +
          `<div class="md-code-body">${highlightCode(codeLines.join('\n'), lang)}</div>` +
          `</div>`,
      )
      continue
    }
    // 标题
    const head = line.match(/^(#{1,3})\s+(.*)/)
    if (head) {
      flushPara()
      const level = head[1].length
      blocks.push(`<h${level} class="md-h${level}">${inline(esc(head[2]))}</h${level}>`)
      i++
      continue
    }
    // 分隔线
    if (/^\s*(---|\*\*\*)\s*$/.test(line)) {
      flushPara()
      blocks.push('<hr class="md-hr" />')
      i++
      continue
    }
    // 无序列表
    if (/^\s*[-*]\s+/.test(line)) {
      flushPara()
      const items: string[] = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        items.push(`<li>${inline(esc(lines[i].replace(/^\s*[-*]\s+/, '')))}</li>`)
        i++
      }
      blocks.push(`<ul class="md-ul">${items.join('')}</ul>`)
      continue
    }
    // 有序列表
    if (/^\s*\d+\.\s+/.test(line)) {
      flushPara()
      const items: string[] = []
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(`<li>${inline(esc(lines[i].replace(/^\s*\d+\.\s+/, '')))}</li>`)
        i++
      }
      blocks.push(`<ol class="md-ol">${items.join('')}</ol>`)
      continue
    }
    // 引用
    if (/^\s*>\s?/.test(line)) {
      flushPara()
      const items: string[] = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        items.push(inline(esc(lines[i].replace(/^\s*>\s?/, ''))))
        i++
      }
      blocks.push(`<blockquote class="md-quote">${items.join('<br/>')}</blockquote>`)
      continue
    }
    // 空行 → 段落截断
    if (!line.trim()) {
      flushPara()
      i++
      continue
    }
    buf.push(line)
    i++
  }
  flushPara()
  return blocks.join('')
})

/** 复制代码块（事件委托：v-html 内按钮通过 data-copy 命中） */
function onRootClick(e: MouseEvent) {
  const btn = (e.target as HTMLElement).closest('[data-copy]')
  if (!btn) return
  const codeEl = btn.closest('.md-code')
  const bodyEl = codeEl?.querySelector('.md-code-body')
  const text = bodyEl?.textContent ?? ''
  navigator.clipboard?.writeText(text.trim()).catch(() => {})
  const raw = (btn as HTMLElement).textContent
  ;(btn as HTMLElement).textContent = '已复制'
  setTimeout(() => ((btn as HTMLElement).textContent = raw), 1600)
}
</script>

<template>
  <div class="af-md" v-html="html" @click="onRootClick" />
</template>

<style scoped>
/* 通用排版（消息气泡内 14px 正文基线） */
.af-md {
  font-size: var(--text-md);
  line-height: 1.7;
  word-break: break-word;
}
.af-md :deep(p) {
  margin: 0 0 8px;
}
.af-md :deep(p:last-child) {
  margin-bottom: 0;
}
.af-md :deep(.md-h1),
.af-md :deep(.md-h2),
.af-md :deep(.md-h3) {
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
  margin: 12px 0 6px;
}
.af-md :deep(.md-h1) { font-size: 17px; }
.af-md :deep(.md-h2) { font-size: 15px; }
.af-md :deep(.md-h3) { font-size: 14px; }
.af-md :deep(strong) { font-weight: var(--weight-semibold); }
.af-md :deep(a) {
  color: var(--color-text);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.af-md :deep(.md-inline) {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--color-surface-2);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  padding: 1px 5px;
}
.af-md :deep(.md-ul),
.af-md :deep(.md-ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}
.af-md :deep(li) {
  margin: 3px 0;
}
.af-md :deep(.md-quote) {
  margin: 8px 0;
  padding: 6px 12px;
  border-left: 2px solid var(--color-border-strong);
  color: var(--color-text-secondary);
  background: var(--color-surface-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}
.af-md :deep(.md-hr) {
  border: none;
  border-top: 1px solid var(--color-border);
  margin: 12px 0;
}

/* ---------- 代码块 ---------- */
.af-md :deep(.md-code) {
  margin: 8px 0;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  overflow: hidden;
  background: var(--code-bg);
}
.af-md :deep(.md-code-head) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 12px;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}
.af-md :deep(.md-code-body) {
  padding: 10px 0 10px 0;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.65;
}
.af-md :deep(.code-row) {
  display: flex;
  min-width: max-content;
}
.af-md :deep(.code-ln) {
  width: 40px;
  flex-shrink: 0;
  text-align: right;
  padding-right: 12px;
  color: var(--color-text-tertiary);
  user-select: none;
}
.af-md :deep(.code-line) {
  padding-right: 16px;
  white-space: pre;
}
.af-md :deep(.md-copy) {
  font-family: var(--font-sans);
  font-size: 11px;
  letter-spacing: normal;
  text-transform: none;
  color: var(--color-text-tertiary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0 2px;
  transition: color var(--transition-fast);
}
.af-md :deep(.md-copy:hover) {
  color: var(--color-text);
}
.af-md :deep(.tk-keyword) { color: var(--code-keyword); }
.af-md :deep(.tk-string) { color: var(--code-string); }
.af-md :deep(.tk-number) { color: var(--code-number); }
.af-md :deep(.tk-comment) { color: var(--code-comment); font-style: italic; }
.af-md :deep(.tk-func) { color: var(--code-func); }
</style>
