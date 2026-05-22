<template>
  <div class="top-perf-bar">
    <div class="top-perf-title">
      <el-icon class="pulse"><Odometer /></el-icon>
      <span>本机负载</span>
    </div>
    <div
      v-for="m in metrics"
      :key="m.key"
      class="metric"
    >
      <el-tooltip placement="bottom" effect="dark" :show-after="200">
        <template #content>
          <div class="tip-line">{{ m.label }}</div>
          <div class="tip-line">当前 {{ formatPct(metricsMap[m.key]) }}</div>
          <div class="tip-line subtle">数据源：{{ perfDataSourceHint }}</div>
        </template>
        <div class="metric-inner">
          <span class="label">{{ m.short }}</span>
          <span class="val" :class="heatClass(metricsMap[m.key])">{{ formatPct(metricsMap[m.key]) }}</span>
          <el-progress
            :percentage="clampPct(metricsMap[m.key])"
            :stroke-width="5"
            :show-text="false"
            :color="progressColor(metricsMap[m.key])"
            class="mini-progress"
          />
        </div>
      </el-tooltip>
    </div>
    <span v-if="errorHint" class="err-hint" :title="errorHint">{{ errorHint }}</span>
    <span v-else-if="updatedAt" class="updated">{{ updatedAt }}</span>

    <el-popover
      v-if="showPatrolPopover"
      placement="bottom-end"
      :width="440"
      trigger="click"
      popper-class="patrol-popper-root"
    >
      <template #reference>
        <div class="patrol-hint clickable" :title="patrolHint">
          <el-badge :value="patrolCount" :hidden="patrolCount <= 0" class="patrol-badge">
            <el-tag size="small" type="warning" effect="dark">巡检</el-tag>
          </el-badge>
          <span class="patrol-text">{{ patrolHint }}</span>
        </div>
      </template>
      <div class="patrol-pop-inner">
        <el-alert
          v-if="pendingRemediation?.hasPending"
          type="warning"
          :closable="false"
          class="rem-pending-alert"
          title="待确认自动修复（安全护栏 · 高分项由您决策）"
        >
          <div v-if="pendingRemediation.riskPatrolAutoMax != null" class="rem-threshold">
            自动执行阈值：风险分 &lt; {{ Number(pendingRemediation.riskPatrolAutoMax).toFixed(1) }} 的步骤已尝试自动执行（若有）。
          </div>
          <div class="rem-pend-body">{{ pendingRemediation.summary }}</div>
          <ul v-if="remediationStepLines.length" class="rem-steps">
            <li v-for="(ln, i) in remediationStepLines" :key="i">{{ ln }}</li>
          </ul>
          <el-button
            size="small"
            type="primary"
            class="rem-btn"
            :loading="remConfirmLoading"
            @click="onConfirmRemediation"
          >
            确认并执行（口令与 MCP 一致：确认执行）
          </el-button>
        </el-alert>

        <template v-if="remediationCoverage.length">
          <el-divider content-position="left">处置车道（自动 vs 确认后）</el-divider>
          <ul class="cov-list">
            <li v-for="(row, i) in remediationCoverage" :key="'cov-' + i" class="cov-li">
              <div class="cov-li-head">
                <el-tag size="small" :type="laneTagType(row.remediation && row.remediation.lane)">
                  {{ (row.remediation && row.remediation.lane) || '—' }}
                </el-tag>
                <span class="cov-title">{{ row.title || row.code }}</span>
              </div>
              <div class="cov-hint">{{ row.remediation && row.remediation.hint }}</div>
            </li>
          </ul>
        </template>

        <div class="patrol-pop-title">最近告警线索</div>
        <ul v-if="patrolList.length" class="patrol-list">
          <li v-for="(it, idx) in patrolList" :key="patrolItemKey(it, idx)" class="patrol-li">
            <div class="patrol-li-head">
              <el-tag size="small" :type="tagType(it.level)">{{ it.code || '—' }}</el-tag>
              <span class="patrol-li-title">{{ it.title }}</span>
            </div>
            <div class="patrol-li-detail">{{ it.detail }}</div>
            <div v-if="it.timestamp" class="patrol-li-ts">{{ it.timestamp }}</div>
          </li>
        </ul>
        <div v-else class="patrol-empty">暂无未过期的告警条目（仍可有下方关联快照）</div>

        <el-divider content-position="left">多维关联快照</el-divider>
        <div v-if="corrSummaryLines.length" class="corr-lines">
          <div v-for="(ln, i) in corrSummaryLines" :key="i" class="corr-line">{{ ln }}</div>
        </div>
        <div v-else class="patrol-empty">关联数据尚未采集</div>

        <template v-if="hotspots.length">
          <el-divider content-position="left">目录热点 Top</el-divider>
          <ul class="hotspot-list">
            <li v-for="(h, i) in hotspots" :key="i">
              <code class="hot-path">{{ h.path }}</code>
              <span class="hot-mib">{{ formatMiB(h.approxMiB) }}</span>
            </li>
          </ul>
        </template>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onUnmounted } from 'vue'
import { Odometer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getPerformanceData,
  getPatrolAlertsRecent,
  getPatrolCorrelationLatest,
  getPatrolRemediationPending,
  confirmPatrolRemediation,
  getPatrolRemediationCoverage
} from '../api/index.js'

const runtimeHost = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
const runtimeProtocol = typeof window !== 'undefined' ? window.location.protocol : 'http:'
/** 完整 ws/wss 地址时优先使用（前后端不同域部署时配置 .env） */
const VITE_AWARD_LOG_WS_URL = typeof import.meta.env?.VITE_AWARD_LOG_WS_URL === 'string'
  ? import.meta.env.VITE_AWARD_LOG_WS_URL.trim()
  : ''

const INTERVAL_MS = 5000
const PATROL_POLL_MS = 12000
const WS_RECONNECT_CAP = 10
/** WS 曾成功推送 performance 后，在此时间内跳过 HTTP 轮询（与后端 5s 推送对齐并留余量） */
const HTTP_SKIP_AFTER_WS_MS = 12000

const metrics = [
  { key: 'cpuUsage', label: 'CPU 使用率（采集周期内均值）', short: 'CPU' },
  { key: 'memoryUsage', label: '内存使用率', short: 'MEM' },
  { key: 'diskUsage', label: '工作盘使用率（后端进程所在盘）', short: '磁盘' },
  { key: 'networkUsage', label: '网络使用率（估算）', short: '网络' }
]

const metricsMap = reactive({
  cpuUsage: null,
  memoryUsage: null,
  diskUsage: null,
  networkUsage: null
})

const updatedAt = ref('')
const errorHint = ref('')
const patrolHint = ref('')
const patrolCount = ref(0)
const patrolList = ref([])
const correlation = ref({})
const pendingRemediation = ref({ hasPending: false })
const remConfirmLoading = ref(false)
const remediationCoverage = ref([])
let timer = null
let patrolTimer = null
let perfWs = null
let wsReconnectTimer = null
let wsReconnectAttempt = 0
let perfWsUnmounting = false
const lastPerfWsAt = ref(0)

const perfDataSourceHint = computed(() => {
  if (lastPerfWsAt.value && Date.now() - lastPerfWsAt.value < HTTP_SKIP_AFTER_WS_MS) {
    return 'WebSocket /award-log/ws/performance（热数据）'
  }
  return 'GET /admin/statistics/performance（轮询兜底）'
})

function performanceWsUrl () {
  if (VITE_AWARD_LOG_WS_URL) {
    return VITE_AWARD_LOG_WS_URL
  }
  const wsScheme = runtimeProtocol === 'https:' ? 'wss:' : 'ws:'
  const host = typeof window !== 'undefined' ? window.location.host : `${runtimeHost}:8088`
  return `${wsScheme}//${host}/award-log/ws/performance`
}

async function fetchRemediationCoverage () {
  try {
    const rows = await getPatrolRemediationCoverage()
    remediationCoverage.value = Array.isArray(rows) ? rows : []
  } catch {
    remediationCoverage.value = []
  }
}

function laneTagType (lane) {
  const u = String(lane || '').toUpperCase()
  if (u === 'AUTO') return 'success'
  if (u === 'CONFIRM') return 'danger'
  if (u === 'MIXED') return 'warning'
  if (u === 'MANUAL') return 'info'
  return 'info'
}

function applyRemediationPendingWs (data) {
  if (!data || typeof data !== 'object') {
    pendingRemediation.value = { hasPending: false }
    return
  }
  pendingRemediation.value = {
    hasPending: !!data.hasPending,
    proposalId: data.proposalId,
    summary: data.summary || '',
    steps: Array.isArray(data.steps) ? data.steps : [],
    expiresAtMs: data.expiresAtMs,
    riskPatrolAutoMax: data.riskPatrolAutoMax
  }
}

async function fetchRemediationPending () {
  try {
    const p = await getPatrolRemediationPending()
    applyRemediationPendingWs(p)
    refreshPatrolHint()
  } catch {
    pendingRemediation.value = { hasPending: false }
    refreshPatrolHint()
  }
}

async function onConfirmRemediation () {
  const id = pendingRemediation.value?.proposalId
  if (!id) {
    ElMessage.warning('无待确认方案')
    return
  }
  remConfirmLoading.value = true
  try {
    const res = await confirmPatrolRemediation(id, '确认执行')
    if (res && res.success) {
      ElMessage.success('已按方案执行修复')
      pendingRemediation.value = { hasPending: false }
      refreshPatrolHint()
    } else {
      ElMessage.error((res && res.error) || '执行失败')
    }
  } catch (e) {
    ElMessage.error('请求失败')
    console.warn('[TopPerformanceBar] remediation confirm', e)
  } finally {
    remConfirmLoading.value = false
  }
}

function patrolItemKey (it, idx) {
  const id = it && (it.id ?? it.traceId ?? it.fingerprint)
  return id != null && String(id) !== '' ? String(id) : `idx-${idx}`
}

function refreshPatrolHint () {
  if (pendingRemediation.value?.hasPending) {
    const s = (pendingRemediation.value.summary || '').replace(/\s+/g, ' ')
    patrolHint.value = s
      ? `待确认修复：${s.slice(0, 96)}${s.length > 96 ? '…' : ''}`
      : '待确认自动修复 — 点开巡检确认'
    return
  }
  if (patrolList.value.length > 0) {
    const a = patrolList.value[0]
    const title = a.title || a.code || '告警'
    const detail = (a.detail || '').replace(/\s+/g, ' ')
    patrolHint.value = detail ? `${title} — ${detail.slice(0, 100)}${detail.length > 100 ? '…' : ''}` : title
  } else {
    const c = correlation.value
    if (c && c.timestamp) {
      patrolHint.value = `快照 CPU ${num(c.cpuUsagePct)}% 磁盘 ${num(c.diskUsagePct)}% · 点阅详情`
    } else {
      patrolHint.value = ''
    }
  }
}

function applyPatrolWsEnvelope (data) {
  if (data.correlation && typeof data.correlation === 'object') {
    correlation.value = { ...data.correlation }
  }
  const ts = data.timestamp || ''
  const findings = Array.isArray(data.findings) ? data.findings : []
  if (findings.length) {
    const rows = findings.map((f) => ({ ...f, timestamp: f.timestamp || ts }))
    patrolList.value = [...rows, ...patrolList.value].slice(0, 24)
    patrolCount.value = patrolList.value.length
  }
  refreshPatrolHint()
}

function applyPerformanceWsPayload (data) {
  if (data.cpuUsage != null) metricsMap.cpuUsage = data.cpuUsage
  if (data.memoryUsage != null) metricsMap.memoryUsage = data.memoryUsage
  if (data.diskUsage != null) metricsMap.diskUsage = data.diskUsage
  if (data.networkUsage != null) metricsMap.networkUsage = data.networkUsage
  if (data.timestamp) {
    const parts = String(data.timestamp).split(' ')
    updatedAt.value = parts.length >= 2 ? parts[1] : data.timestamp
  }
  errorHint.value = ''
  lastPerfWsAt.value = Date.now()
}

function handlePerfWsMessage (ev) {
  let data
  try {
    data = JSON.parse(ev.data)
  } catch {
    return
  }
  const ch = data.channel
  if (ch === 'performance') {
    applyPerformanceWsPayload(data)
    return
  }
  if (ch === 'patrol_alert') {
    applyPatrolWsEnvelope(data)
    return
  }
  if (ch === 'patrol_remediation_pending') {
    applyRemediationPendingWs(data)
    refreshPatrolHint()
    return
  }
  if (ch === 'patrol_remediation') {
    fetchRemediationPending()
    return
  }
}

function schedulePerfWsReconnect () {
  if (wsReconnectAttempt >= WS_RECONNECT_CAP) {
    return
  }
  wsReconnectAttempt += 1
  const delay = Math.min(30000, 2000 * 2 ** (wsReconnectAttempt - 1))
  wsReconnectTimer = window.setTimeout(() => {
    wsReconnectTimer = null
    connectPerformanceWs()
  }, delay)
}

function disconnectPerformanceWs (resetAttempt, forUnmount) {
  if (forUnmount) {
    perfWsUnmounting = true
  }
  if (wsReconnectTimer != null) {
    clearTimeout(wsReconnectTimer)
    wsReconnectTimer = null
  }
  if (perfWs) {
    perfWs.onopen = null
    perfWs.onmessage = null
    perfWs.onclose = null
    perfWs.onerror = null
    try {
      perfWs.close()
    } catch {
      /* noop */
    }
    perfWs = null
  }
  if (resetAttempt) {
    wsReconnectAttempt = 0
  }
}

function connectPerformanceWs () {
  if (typeof WebSocket === 'undefined') {
    return
  }
  perfWsUnmounting = false
  disconnectPerformanceWs(false, false)
  try {
    perfWs = new WebSocket(performanceWsUrl())
  } catch {
    schedulePerfWsReconnect()
    return
  }
  perfWs.onopen = () => {
    wsReconnectAttempt = 0
    try {
      perfWs.send('refresh')
    } catch {
      /* 首帧由服务端定时推送，refresh 为可选加速 */
    }
  }
  perfWs.onmessage = handlePerfWsMessage
  perfWs.onerror = () => {
    /* onclose 会负责重连 */
  }
  perfWs.onclose = () => {
    perfWs = null
    if (perfWsUnmounting) {
      return
    }
    schedulePerfWsReconnect()
  }
}

const hotspots = computed(() => {
  const top = correlation.value?.diskHotspotsTop
  return Array.isArray(top) ? top : []
})

const remediationStepLines = computed(() => {
  const steps = pendingRemediation.value?.steps
  if (!Array.isArray(steps) || !steps.length) return []
  return steps.map((st) => {
    const rs = st.riskScore != null && !Number.isNaN(Number(st.riskScore))
      ? ` 风险分 ${Number(st.riskScore).toFixed(1)}`
      : ''
    const k = st.kind || ''
    if (k === 'CLEAN_TEMP') return `临时清理 ${st.path || ''}${rs}`
    if (k === 'CLEAN_LOG') return `日志裁剪 ${st.path || ''}${rs}`
    if (k === 'RESTART_SERVICE') return `重启服务 ${st.serviceName || ''}${rs}`
    return `${k}${rs}`
  })
})

const showPatrolPopover = computed(() => {
  return !!(patrolHint.value || correlation.value?.timestamp || pendingRemediation.value?.hasPending)
})

const corrSummaryLines = computed(() => {
  const c = correlation.value || {}
  const lines = []
  if (c.timestamp) lines.push(`时间 ${c.timestamp}`)
  if (c.cpuUsagePct != null) lines.push(`CPU 使用率约 ${num(c.cpuUsagePct)}%`)
  if (c.memoryUsagePct != null) lines.push(`内存使用率约 ${num(c.memoryUsagePct)}%`)
  if (c.diskUsagePct != null) lines.push(`磁盘使用率约 ${num(c.diskUsagePct)}%`)
  if (c.alarmTotal24h != null) lines.push(`告警总数(24h 窗) ${c.alarmTotal24h}（ERROR≈${c.alarmErrorApprox ?? 0} FATAL≈${c.alarmFatalApprox ?? 0}）`)
  if (c.anomalyLogsDay1 != null && c.anomalyLogsDay1 !== -1) lines.push(`异常日志(近 1 天统计) ${c.anomalyLogsDay1}`)
  if (c.zombieProcesses != null) lines.push(`僵尸进程 ${c.zombieProcesses}`)
  if (c.novelDrainTemplateKinds1h != null) lines.push(`近 1h 新 Drain 模板种类 ${c.novelDrainTemplateKinds1h}`)
  return lines
})

function num (v) {
  const n = Number(v)
  return Number.isNaN(n) ? '—' : n.toFixed(1)
}

function formatMiB (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return `${n.toFixed(1)} MiB`
}

function tagType (level) {
  const s = String(level || '').toUpperCase()
  if (s.includes('WARN')) return 'warning'
  if (s.includes('INFO')) return 'info'
  return ''
}

function clampPct (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return 0
  return Math.round(Math.min(100, Math.max(0, n)))
}

function formatPct (v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return `${Number(v).toFixed(1)}%`
}

function heatClass (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return ''
  if (n >= 85) return 'hot'
  if (n >= 60) return 'warm'
  return ''
}

function progressColor (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '#64748b'
  if (n >= 85) return '#f87171'
  if (n >= 60) return '#fbbf24'
  return '#34d399'
}

async function patrolTick () {
  try {
    const [list, corr] = await Promise.all([
      getPatrolAlertsRecent(12),
      getPatrolCorrelationLatest()
    ])
    await fetchRemediationPending()
    await fetchRemediationCoverage()
    correlation.value = corr && typeof corr === 'object' ? { ...corr } : {}
    patrolList.value = Array.isArray(list) ? list : []

    patrolCount.value = patrolList.value.length
    refreshPatrolHint()
  } catch {
    /* 静默 */
  }
}

async function tick () {
  const wsFresh = lastPerfWsAt.value && (Date.now() - lastPerfWsAt.value < HTTP_SKIP_AFTER_WS_MS)
  if (wsFresh) {
    return
  }
  try {
    const data = await getPerformanceData()
    errorHint.value = ''
    metrics.forEach(({ key }) => {
      metricsMap[key] = data[key] ?? null
    })
    const now = new Date()
    updatedAt.value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  } catch (e) {
    errorHint.value = '性能拉取失败'
    console.warn('[TopPerformanceBar]', e)
  }
}

onMounted(() => {
  tick()
  timer = setInterval(tick, INTERVAL_MS)
  patrolTick()
  patrolTimer = setInterval(patrolTick, PATROL_POLL_MS)
  connectPerformanceWs()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (patrolTimer) clearInterval(patrolTimer)
  disconnectPerformanceWs(true, true)
})
</script>

<style scoped>
.top-perf-bar {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-width: 0;
  padding: 0 12px;
  margin: 0 8px;
  border-radius: var(--ops-radius-sm);
  border: 1px solid var(--ops-border);
  background: var(--ops-panel-soft);
}

.top-perf-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--ops-text-subtle);
  flex-shrink: 0;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
}

.pulse {
  color: var(--ops-primary);
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.75;
  }
  50% {
    opacity: 1;
  }
}

.metric {
  flex-shrink: 0;
  min-width: 72px;
  max-width: 100px;
}

.metric-inner {
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: default;
}

.metric .label {
  font-size: 10px;
  color: var(--ops-text-subtle);
}

.metric .val {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ops-text);
}

.metric .val.warm {
  color: #fcd34d;
}

.metric .val.hot {
  color: #fca5a5;
}

.mini-progress :deep(.el-progress-bar__outer) {
  background: #e2e8f0;
}

.err-hint {
  font-size: 11px;
  color: #f87171;
  flex-shrink: 0;
}

.updated {
  font-size: 10px;
  color: #64748b;
  flex-shrink: 0;
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
}

.patrol-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 300px;
  min-width: 0;
  margin-left: 8px;
  flex-shrink: 1;
}

.patrol-hint.clickable {
  cursor: pointer;
  border-radius: 8px;
  padding: 2px 4px;
  margin: -2px -4px;
  transition: background 0.15s ease;
}

.patrol-hint.clickable:hover {
  background: rgba(15, 23, 42, 0.04);
}

.patrol-badge :deep(.el-badge__content) {
  font-size: 10px;
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
}

.patrol-text {
  font-size: 11px;
  color: var(--ops-text-subtle);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tip-line {
  line-height: 1.4;
}

.tip-line.subtle {
  opacity: 0.75;
  font-size: 12px;
  margin-top: 4px;
}

@media (max-width: 1200px) {
  .top-perf-bar {
    display: none;
  }
}
</style>

<style>
.patrol-popper-root {
  --el-popover-padding: 12px;
}
.patrol-pop-inner {
  max-height: 70vh;
  overflow: auto;
}
.rem-pending-alert {
  margin-bottom: 12px;
}
.rem-pending-alert :deep(.el-alert__title) {
  font-size: 13px;
}
.rem-pend-body {
  font-size: 11px;
  color: #cbd5e1;
  line-height: 1.45;
  margin-bottom: 10px;
}
.rem-threshold {
  font-size: 10px;
  color: #64748b;
  margin-bottom: 8px;
  line-height: 1.35;
}
.rem-btn {
  margin-top: 4px;
}
.rem-steps {
  list-style: none;
  margin: 0 0 10px;
  padding: 0;
  font-size: 10px;
  color: #94a3b8;
  line-height: 1.4;
}
.rem-steps li {
  padding: 2px 0;
}
.patrol-pop-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #e2e8f0;
}
.patrol-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 240px;
  overflow: auto;
}
.patrol-li {
  padding: 8px 0;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}
.patrol-li:last-child {
  border-bottom: none;
}
.patrol-li-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.patrol-li-title {
  font-size: 12px;
  font-weight: 600;
  color: #f1f5f9;
}
.patrol-li-detail {
  font-size: 11px;
  color: #cbd5e1;
  margin-top: 4px;
  line-height: 1.45;
}
.patrol-li-ts {
  font-size: 10px;
  color: #64748b;
  margin-top: 4px;
}
.patrol-empty {
  font-size: 11px;
  color: #94a3b8;
}
.corr-lines {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.corr-line {
  font-size: 11px;
  color: #cbd5e1;
  font-variant-numeric: tabular-nums;
}
.hotspot-list {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 11px;
}
.hotspot-list li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px solid rgba(51, 65, 85, 0.35);
}
.hot-path {
  color: #93c5fd;
  word-break: break-all;
  flex: 1;
  font-size: 10px;
}
.hot-mib {
  color: #fcd34d;
  flex-shrink: 0;
}
.cov-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 200px;
  overflow: auto;
}
.cov-li {
  padding: 8px 0;
  border-bottom: 1px solid rgba(51, 65, 85, 0.35);
}
.cov-li:last-child {
  border-bottom: none;
}
.cov-li-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cov-title {
  font-size: 11px;
  font-weight: 600;
  color: #e2e8f0;
}
.cov-hint {
  font-size: 10px;
  color: #94a3b8;
  margin-top: 4px;
  line-height: 1.4;
}
</style>
