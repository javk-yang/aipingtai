<script setup lang="ts">
/**
 * ObservabilityView —— 可观测页（P11 / P13 看板）
 *
 * 三段：
 *   1. 今日用量卡片：调用次数 / 输入 Token / 输出 Token / 成本（¥）
 *   2. 配额进度条：已用 / 上限 / 剩余，软阈值预警（黄）与超限（红）
 *   3. 最近 7 天趋势：自绘 SVG 面积折线（Token 总量）
 *   4. 审计日志：分页表格 + action 过滤 + traceId / IP / UA 展示
 *
 * 数据源：GET /api/usage/stats + GET /api/audit/logs（权限 agent:usage:read / agent:audit:read）
 */
import { computed, onMounted, ref } from 'vue'
import { auditApi, usageApi } from '@/api/usage'
import { AuditActionLabels } from '@/types/usage'
import type { AuditLogResponse, UsageStatsResponse } from '@/types/usage'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'

const stats = ref<UsageStatsResponse | null>(null)
const statsLoading = ref(false)
const statsError = ref('')

/* ---------- 审计日志 ---------- */
const logs = ref<AuditLogResponse[]>([])
const total = ref(0)
const page = ref(1)
const size = 15
const actionFilter = ref('')
const logsLoading = ref(false)
const actions = ['', 'user.login', 'user.register', 'chat.message.complete', 'tool.call', 'skill.call']

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  try {
    stats.value = await usageApi.stats()
  } catch (e) {
    statsError.value = e instanceof Error ? e.message : String(e)
  } finally {
    statsLoading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const resp = await auditApi.logs({
      action: actionFilter.value || undefined,
      page: page.value,
      size,
    })
    logs.value = resp.records
    total.value = resp.total
  } finally {
    logsLoading.value = false
  }
}

function onFilter() {
  page.value = 1
  loadLogs()
}

function fmtCost(cost?: string | null): string {
  if (cost == null) return '¥0.000000'
  const n = Number(cost)
  return `¥${n.toFixed(6)}`
}

function fmtNum(n: number | undefined | null): string {
  return (n ?? 0).toLocaleString('zh-CN')
}

function fmtDateTime(t?: string | null): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function actionLabel(a: string): string {
  return AuditActionLabels[a] ?? a
}

/* ---------- 7 天趋势 SVG（自绘，不引图表库） ---------- */
const W = 560
const H = 160
const PAD = { top: 16, right: 12, bottom: 24, left: 40 }

const trend = computed(() => {
  const daily = stats.value?.daily ?? []
  // 后端倒序（最新在前），图表从左到右按时间正序
  const data = [...daily].reverse()
  const tokens = data.map((d) => Number(d.tokenInput) + Number(d.tokenOutput))
  const max = Math.max(...tokens, 1)
  const innerW = W - PAD.left - PAD.right
  const innerH = H - PAD.top - PAD.bottom
  const stepX = data.length > 1 ? innerW / (data.length - 1) : 0

  const points = data.map((d, i) => ({
    x: PAD.left + i * stepX,
    y: PAD.top + innerH - (tokens[i] / max) * innerH,
    label: d.date.slice(5).replace('-', '/'),
    tokens: tokens[i],
  }))

  const line = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
  const area = points.length
    ? `${PAD.left},${PAD.top + innerH} ${line} ${points[points.length - 1].x.toFixed(1)},${PAD.top + innerH}`
    : ''
  const gridY = Array.from({ length: 4 }, (_, i) => PAD.top + (innerH / 3) * i)

  return { points, line, area, gridY, max, hasData: data.length > 0 }
})

/* ---------- 配额展示 ---------- */
const quota = computed(() => stats.value?.quota ?? null)
const quotaPct = computed(() => {
  const q = quota.value
  if (!q) return 0
  return Math.min(100, Math.max(0, Math.round(q.usedPercent * 10) / 10))
})
const quotaBarCls = computed(() => {
  if (!quota.value) return ''
  if (quota.value.exceeded) return 'is-exceeded'
  if (quota.value.softAlert) return 'is-alert'
  return ''
})

onMounted(() => {
  loadStats()
  loadLogs()
})
</script>

<template>
  <div class="obs">
    <!-- 今日用量 -->
    <section class="obs-grid">
      <div class="stat-card">
        <p class="stat-label label-group">今日调用</p>
        <p class="stat-value">{{ fmtNum(stats?.today.calls) }}</p>
        <p class="stat-sub">次请求</p>
      </div>
      <div class="stat-card">
        <p class="stat-label label-group">输入 Token</p>
        <p class="stat-value">{{ fmtNum(stats?.today.tokenInput) }}</p>
        <p class="stat-sub">prompt 累计</p>
      </div>
      <div class="stat-card">
        <p class="stat-label label-group">输出 Token</p>
        <p class="stat-value">{{ fmtNum(stats?.today.tokenOutput) }}</p>
        <p class="stat-sub">completion 累计</p>
      </div>
      <div class="stat-card">
        <p class="stat-label label-group">今日成本</p>
        <p class="stat-value cost">{{ fmtCost(stats?.today.cost) }}</p>
        <p class="stat-sub">¥1/M 输入 · ¥2/M 输出</p>
      </div>
    </section>

    <!-- 配额 -->
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">Token 配额</h2>
        <span v-if="quota?.softAlert" class="quota-alert">已超过软阈值 {{ quota.softThreshold }}%，仅预警不阻断</span>
        <span v-else-if="quota?.exceeded" class="quota-alert is-danger">配额已超限，新请求将被阻断</span>
      </div>
      <template v-if="quota">
        <div class="quota-bar">
          <div class="quota-fill" :class="quotaBarCls" :style="{ width: quotaPct + '%' }" />
        </div>
        <div class="quota-meta">
          <span>已用 <b>{{ fmtNum(quota.tokenUsed) }}</b> / {{ fmtNum(quota.tokenLimit) }} tokens（{{ quotaPct }}%）</span>
          <span>剩余 <b>{{ fmtNum(quota.remaining) }}</b> tokens</span>
        </div>
      </template>
      <p v-else class="quota-empty">未配置配额（api_quota 无记录）</p>
    </section>

    <!-- 7 天趋势 -->
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">最近 7 天 Token 趋势</h2>
        <span class="panel-sub">输入 + 输出，按天聚合</span>
      </div>
      <div class="trend-wrap">
        <svg v-if="trend.hasData" :viewBox="`0 0 ${W} ${H}`" class="trend-svg">
          <defs>
            <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="currentColor" stop-opacity="0.18" />
              <stop offset="100%" stop-color="currentColor" stop-opacity="0.02" />
            </linearGradient>
          </defs>
          <!-- 网格线 -->
          <line
            v-for="(gy, i) in trend.gridY"
            :key="i"
            :x1="PAD.left"
            :x2="W - PAD.right"
            :y1="gy"
            :y2="gy"
            class="trend-grid"
          />
          <!-- 面积 -->
          <polygon :points="trend.area" fill="url(#trendFill)" />
          <!-- 折线 -->
          <polyline :points="trend.line" class="trend-line" fill="none" />
          <!-- 数据点 + 标签 -->
          <g v-for="(p, i) in trend.points" :key="i">
            <circle :cx="p.x" :cy="p.y" r="2.6" class="trend-dot" />
            <text :x="p.x" :y="H - 8" text-anchor="middle" class="trend-x">{{ p.label }}</text>
            <title>{{ p.tokens.toLocaleString('zh-CN') }} tokens</title>
          </g>
        </svg>
        <p v-else class="trend-empty">暂无趋势数据，发起一次对话后生成</p>
      </div>
    </section>

    <!-- 审计日志 -->
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">审计日志</h2>
        <div class="panel-tools">
          <select v-model="actionFilter" class="af-select" @change="onFilter">
            <option v-for="a in actions" :key="a" :value="a">
              {{ a ? actionLabel(a) : '全部动作' }}
            </option>
          </select>
          <AfButton variant="secondary" size="sm" @click="loadLogs">
            <AfIcon name="refresh" :size="13" />
            刷新
          </AfButton>
        </div>
      </div>

      <div class="table-wrap">
        <table class="audit-table">
          <thead>
            <tr>
              <th class="col-id">ID</th>
              <th class="col-action">动作</th>
              <th class="col-user">用户</th>
              <th class="col-trace">traceId</th>
              <th class="col-ip">IP</th>
              <th class="col-status">状态</th>
              <th class="col-time">时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td class="mono">{{ log.id }}</td>
              <td>
                <span class="action-chip">{{ actionLabel(log.action) }}</span>
                <span class="action-code mono">{{ log.action }}</span>
              </td>
              <td class="mono">{{ log.userId ?? '-' }}</td>
              <td class="mono trace-cell" :title="log.traceId ?? ''">{{ log.traceId?.slice(0, 16) ?? '-' }}</td>
              <td class="mono">{{ log.ip ?? '-' }}</td>
              <td>
                <span :class="['status-chip', log.status === 1 ? 'ok' : 'fail']">
                  {{ log.status === 1 ? '成功' : '失败' }}
                </span>
              </td>
              <td class="mono">{{ fmtDateTime(log.createdAt) }}</td>
            </tr>
            <tr v-if="!logs.length && !logsLoading">
              <td colspan="7" class="table-empty">暂无审计记录</td>
            </tr>
            <tr v-if="logsLoading">
              <td colspan="7" class="table-empty">加载中…</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span class="pager-total">共 {{ total }} 条</span>
        <AfButton
          variant="secondary"
          size="sm"
          :disabled="page <= 1"
          @click="page--; loadLogs()"
        >
          上一页
        </AfButton>
        <span class="pager-page">第 {{ page }} 页</span>
        <AfButton
          variant="secondary"
          size="sm"
          :disabled="page * size >= total"
          @click="page++; loadLogs()"
        >
          下一页
        </AfButton>
      </div>
    </section>

    <p v-if="statsError" class="obs-error">用量统计加载失败：{{ statsError }}</p>
  </div>
</template>

<style scoped>
.obs {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* 统计卡片 */
.obs-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}
.stat-card {
  padding: var(--space-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.stat-label {
  margin-bottom: var(--space-2);
}
.stat-value {
  font-size: 26px;
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
  font-variant-numeric: tabular-nums;
}
.stat-value.cost {
  font-size: 20px;
}
.stat-sub {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

/* 面板 */
.panel {
  padding: var(--space-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}
.panel-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
}
.panel-sub {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

/* 配额 */
.quota-bar {
  height: 8px;
  border-radius: 999px;
  background: var(--color-surface-2);
  overflow: hidden;
}
.quota-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--color-text);
  transition: width 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}
.quota-fill.is-alert {
  background: var(--color-warning);
}
.quota-fill.is-exceeded {
  background: var(--color-danger);
}
.quota-meta {
  display: flex;
  justify-content: space-between;
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}
.quota-meta b {
  color: var(--color-text);
  font-weight: var(--weight-medium);
}
.quota-alert {
  font-size: var(--text-sm);
  color: var(--color-warning);
}
.quota-alert.is-danger {
  color: var(--color-danger);
}
.quota-empty {
  font-size: var(--text-base);
  color: var(--color-text-tertiary);
}

/* 趋势 */
.trend-wrap {
  position: relative;
}
.trend-svg {
  width: 100%;
  height: auto;
  color: var(--color-text);
}
.trend-grid {
  stroke: var(--color-border);
  stroke-width: 1;
}
.trend-line {
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.trend-dot {
  fill: var(--color-surface);
  stroke: currentColor;
  stroke-width: 1.6;
}
.trend-x {
  fill: var(--color-text-tertiary);
  font-size: 10px;
}
.trend-empty {
  padding: var(--space-6);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-base);
}

/* 审计表格 */
.table-wrap {
  overflow-x: auto;
}
.audit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-base);
}
.audit-table th {
  text-align: left;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
  padding: 6px 10px;
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
}
.audit-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text);
  vertical-align: middle;
}
.audit-table tr:hover td {
  background: var(--color-surface-2);
}
.col-id { width: 56px; }
.col-action { min-width: 180px; }
.col-trace { min-width: 130px; }
.col-ip { width: 110px; }
.col-status { width: 70px; }
.col-time { width: 150px; }
.action-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-surface-2);
  font-size: var(--text-sm);
  margin-right: 6px;
}
.action-code {
  font-size: 10px;
  color: var(--color-text-tertiary);
}
.trace-cell {
  font-size: 11px;
  color: var(--color-text-secondary);
}
.status-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: var(--text-sm);
}
.status-chip.ok {
  background: transparent;
  color: var(--color-success);
}
.status-chip.fail {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.table-empty {
  text-align: center;
  color: var(--color-text-tertiary);
  padding: var(--space-6) !important;
}

/* 分页 */
.pager {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.pager-total {
  flex: 1;
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}
.pager-page {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.af-select {
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: var(--text-sm);
}
.af-select:focus {
  outline: none;
  border-color: var(--color-text);
}
.panel-tools {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.obs-error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
/* ---- 观测暗房：让数据成为一条可读的信号轨迹 ---- */
.obs { padding: clamp(20px, 3vw, 36px); gap: 20px; background: var(--color-bg); }
.obs-grid { gap: 12px; }
.stat-card { position: relative; min-height: 132px; padding: 18px; overflow: hidden; border-radius: var(--radius-lg); background: var(--color-surface-raised); box-shadow: 0 1px 0 rgba(255,255,255,.35) inset; transition: transform var(--transition-fast), box-shadow var(--transition-fast); }
.stat-card::after { content: ''; position: absolute; right: -18px; bottom: -23px; width: 84px; height: 84px; border: 1px solid var(--color-border); border-radius: 50%; opacity: .75; }
.stat-card:nth-child(1)::before,.stat-card:nth-child(2)::before,.stat-card:nth-child(3)::before,.stat-card:nth-child(4)::before { content: ''; position: absolute; top: 0; left: 18px; width: 34px; height: 3px; background: var(--color-spectrum-a); }
.stat-card:nth-child(2)::before { background: var(--color-spectrum-b); }.stat-card:nth-child(3)::before { background: var(--color-spectrum-c); }.stat-card:nth-child(4)::before { background: var(--color-spectrum-e); }
.stat-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-float); }
.stat-value { font-size: clamp(25px, 2.7vw, 34px); }.stat-value.cost { font-size: clamp(19px, 2.3vw, 28px); }
.panel { position: relative; padding: 18px; border-radius: var(--radius-lg); background: var(--color-surface-raised); box-shadow: 0 1px 0 rgba(255,255,255,.35) inset; }
.panel-head { padding-bottom: 12px; margin-bottom: 14px; border-bottom: 1px solid var(--color-border); }.panel-title { font-size: var(--text-lg); }
.quota-bar { height: 10px; background: var(--color-surface-2); }.quota-fill { background: var(--color-lifeline); }
.trend-svg { color: var(--color-spectrum-d); }.trend-line { stroke-width: 2.2; }.trend-dot { fill: var(--color-surface-raised); stroke-width: 2; }
.table-wrap { margin: 0 -6px; padding: 0 6px; }.audit-table th { padding: 9px 10px; background: var(--color-surface-2); }.audit-table tbody tr { transition: background var(--transition-fast); }.audit-table tr:hover td { background: color-mix(in srgb, var(--color-spectrum-d) 6%, var(--color-surface-raised)); }
.action-chip { background: var(--color-bg-elevated); }.status-chip.ok { background: var(--color-success-bg); }.pager { padding-top: 10px; }
@media (max-width: 900px) { .obs-grid { grid-template-columns: repeat(2, 1fr); } .quota-meta { gap: 8px; flex-direction: column; } }
@media (max-width: 520px) { .obs-grid { grid-template-columns: 1fr; } .panel-head { align-items: flex-start; gap: 10px; flex-direction: column; } .panel-tools { width: 100%; justify-content: space-between; } }
</style>
