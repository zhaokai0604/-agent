<template>
  <div class="ops-page ops-effect-dashboard">
    <el-card class="effect-card">
      <template #header>
        <div class="card-header">
          <div class="card-header-left">
            <span>运维效果评分</span>
            <span class="card-sub">综合分 + 五维雷达：安全拦截 · 成功执行 · 巡检闭环 · 资源优化 · 自愈效果</span>
          </div>
          <div class="card-header-actions">
            <el-radio-group v-model="periodDays" size="small" @change="loadData">
              <el-radio-button :value="7">近7日</el-radio-button>
              <el-radio-button :value="14">近14日</el-radio-button>
              <el-radio-button :value="30">近30日</el-radio-button>
            </el-radio-group>
            <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="effect-body">
        <template v-if="data">
          <section class="score-hero-panel">
            <div class="gauge-panel">
              <div class="gauge-ring-bg" aria-hidden="true" />
              <div ref="gaugeRef" class="gauge-chart" />
              <div class="gauge-meta">
                <span class="gauge-grade" :style="{ color: overallGradeColor }">{{ data.overallGrade }}</span>
                <span class="gauge-window">{{ data.periodDays }} 日评估窗口</span>
              </div>
            </div>

            <div class="hero-text">
              <div class="hero-score-line">
                <span class="hero-score-num">{{ Math.round(Number(data.overallScore) || 0) }}</span>
                <span class="hero-score-unit">分</span>
                <el-tag class="hero-grade-tag" :style="gradeTagStyle(data.overallGrade)" effect="dark" size="small">
                  {{ data.overallGrade }}
                </el-tag>
              </div>
              <p class="hero-headline">{{ summaryHeadline }}</p>
              <ul class="hero-bullets">
                <li v-for="(b, i) in summaryBullets" :key="i">{{ b }}</li>
              </ul>
              <div v-if="valueStatement" class="value-kpi-row">
                <div
                  v-for="vk in valueKpiItems"
                  :key="vk.key"
                  class="value-kpi"
                  :class="{ accent: vk.accent }"
                >
                  <span class="vk-num">{{ vk.value }}</span>
                  <span class="vk-label">{{ vk.label }}</span>
                </div>
              </div>
              <div v-if="highlights.length" class="highlight-tags">
                <el-tag
                  v-for="(h, i) in highlights"
                  :key="i"
                  :type="highlightTagType(h.type)"
                  effect="light"
                  round
                  class="hl-tag"
                >
                  {{ h.title }}
                </el-tag>
              </div>
            </div>
          </section>

          <div class="dim-row">
            <div
              v-for="dim in dimensionList"
              :key="dim.id"
              class="dim-card"
              :style="{ '--dim-accent': scoreColor(dim.score) }"
            >
              <div class="dim-head">
                <span class="dim-name">{{ dim.name }}</span>
                <span class="dim-grade-badge" :style="gradeTagStyle(dim.grade)">{{ dim.grade }}</span>
              </div>
              <el-progress
                :percentage="dim.score"
                :stroke-width="8"
                :show-text="false"
                :color="scoreColor(dim.score)"
              />
              <div class="dim-score-row">
                <span class="dim-score">{{ dim.score }}</span>
                <span class="dim-score-unit">分</span>
              </div>
              <div class="dim-detail">{{ dim.detail }}</div>
            </div>
          </div>

          <div class="kpi-strip">
            <div v-for="k in kpiCards" :key="k.key" class="kpi-chip">
              <span class="kpi-val">{{ k.value }}</span>
              <span class="kpi-label">{{ k.label }}</span>
            </div>
          </div>

          <div class="charts-row">
            <el-card class="chart-card" shadow="never">
              <template #header>
                <span class="chart-title">五维能力雷达</span>
              </template>
              <div ref="radarRef" class="chart-box" />
            </el-card>
            <el-card class="chart-card" shadow="never">
              <template #header>
                <span class="chart-title">审计处置趋势</span>
              </template>
              <div ref="auditTrendRef" class="chart-box" />
            </el-card>
            <el-card class="chart-card" shadow="never">
              <template #header>
                <span class="chart-title">巡检告警趋势</span>
              </template>
              <div ref="patrolTrendRef" class="chart-box" />
            </el-card>
          </div>

          <el-card v-if="recentHealing.length" class="table-card" shadow="never">
            <template #header>近期自愈评分记录</template>
            <el-table :data="recentHealing" size="small" stripe>
              <el-table-column prop="traceId" label="Trace ID" min-width="200" show-overflow-tooltip />
              <el-table-column prop="channel" label="通道" width="100" />
              <el-table-column prop="healingScore" label="评分" width="90">
                <template #default="{ row }">
                  <el-tag :type="healingTagType(row.healingScore)">{{ row.healingScore }}/100</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" min-width="160" />
            </el-table>
          </el-card>

          <div v-if="resourceLine" class="resource-foot">
            <el-icon><Odometer /></el-icon>
            <span>当前资源：{{ resourceLine }}</span>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="暂无评分数据" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { Odometer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getOpsEffectDashboard } from '../api/index.js'

const loading = ref(false)
const data = ref(null)
const periodDays = ref(7)

const gaugeRef = ref(null)
const radarRef = ref(null)
const auditTrendRef = ref(null)
const patrolTrendRef = ref(null)
let gaugeChart = null
let radarChart = null
let auditChart = null
let patrolChart = null

const summaryHeadline = computed(() => data.value?.summary?.headline || '')
const summaryBullets = computed(() => {
  const b = data.value?.summary?.bullets
  return Array.isArray(b) ? b : []
})
const highlights = computed(() => {
  const h = data.value?.valueHighlights
  return Array.isArray(h) ? h : []
})
const dimensionList = computed(() => {
  const dims = data.value?.dimensions
  if (!dims || typeof dims !== 'object') return []
  return ['security', 'execution', 'patrol', 'resource', 'healing']
    .map((id) => dims[id])
    .filter(Boolean)
})
const valueStatement = computed(() => data.value?.valueStatement || null)
const overallGradeColor = computed(() => gradeColor(data.value?.overallGrade))

const valueKpiItems = computed(() => {
  const v = valueStatement.value
  if (!v) return []
  return [
    { key: 'block', value: v.securityBlocked ?? 0, label: '安全拦截（次）', accent: false },
    { key: 'ok', value: v.executionSuccess ?? 0, label: '成功执行（次）', accent: false },
    { key: 'patrol', value: v.patrolClosedLoops ?? 0, label: '巡检闭环（轮）', accent: false },
    { key: 'space', value: formatReleasedGb(v.releasedSpaceGb), label: '释放空间（GB）', accent: true }
  ]
})
const recentHealing = computed(() => {
  const r = data.value?.recentEffectRuns
  return Array.isArray(r) ? r : []
})

const kpiCards = computed(() => {
  const k = data.value?.kpis || {}
  return [
    { key: 'block', label: '安全拦截', value: k.auditBlocked ?? 0 },
    { key: 'ok', label: '成功执行', value: k.auditSuccessExec ?? 0 },
    { key: 'patrol', label: '巡检闭环', value: k.patrolClosedLoops ?? k.patrolRuns ?? 0 },
    { key: 'space', label: '释放空间 GB', value: formatReleasedGb(k.releasedSpaceGb) },
    { key: 'confirm', label: '二次确认', value: k.auditNeedConfirm ?? 0 },
    { key: 'heal', label: '自愈均分', value: k.healingSamples > 0 ? Math.round(k.avgHealingScore) : '—' },
    { key: 'audit', label: '审计总量', value: k.auditTotal ?? 0 }
  ]
})

function formatReleasedGb (v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '0'
  return n < 0.01 ? '<0.01' : n.toFixed(2)
}

const resourceLine = computed(() => {
  const r = data.value?.currentResource
  if (!r) return ''
  const fmt = (v) => (v != null && v !== '' ? `${Number(v).toFixed(1)}%` : '—')
  return `CPU ${fmt(r.cpuUsagePct)} · 内存 ${fmt(r.memoryUsagePct)} · 磁盘 ${fmt(r.diskUsagePct)}`
})

function scoreColor (score) {
  if (score >= 85) return '#16a34a'
  if (score >= 70) return '#0d9488'
  if (score >= 55) return '#d97706'
  return '#dc2626'
}

function gradeColor (grade) {
  if (grade === '优秀') return '#16a34a'
  if (grade === '良好') return '#0d9488'
  if (grade === '合格') return '#2563eb'
  if (grade === '待提升') return '#dc2626'
  return '#64748b'
}

function gradeTagStyle (grade) {
  const c = gradeColor(grade)
  return {
    backgroundColor: c,
    borderColor: c,
    color: '#fff'
  }
}

function highlightTagType (type) {
  if (type === 'security') return 'danger'
  if (type === 'healing') return 'success'
  if (type === 'patrol') return 'warning'
  return 'info'
}

function healingTagType (score) {
  const n = Number(score)
  if (n >= 70) return 'success'
  if (n >= 45) return 'warning'
  return 'info'
}

function renderGauge () {
  if (!gaugeRef.value || !data.value) return
  if (!gaugeChart) gaugeChart = echarts.init(gaugeRef.value)
  const score = Number(data.value.overallScore) || 0
  const accent = scoreColor(score)
  gaugeChart.setOption({
    animationDuration: 900,
    animationEasing: 'cubicOut',
    series: [{
      type: 'gauge',
      center: ['50%', '54%'],
      radius: '92%',
      startAngle: 215,
      endAngle: -35,
      min: 0,
      max: 100,
      splitNumber: 5,
      progress: {
        show: true,
        width: 16,
        roundCap: true,
        itemStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: accent },
              { offset: 1, color: '#2dd4bf' }
            ]
          },
          shadowBlur: 8,
          shadowColor: 'rgba(13, 148, 136, 0.35)'
        }
      },
      pointer: {
        show: true,
        length: '58%',
        width: 5,
        roundCap: true,
        itemStyle: {
          color: '#0f172a',
          shadowBlur: 4,
          shadowColor: 'rgba(15, 23, 42, 0.2)'
        }
      },
      axisLine: {
        roundCap: true,
        lineStyle: {
          width: 16,
          color: [
            [0.55, '#fecaca'],
            [0.7, '#fde68a'],
            [0.85, '#bae6fd'],
            [1, '#bbf7d0']
          ]
        }
      },
      axisTick: { show: false },
      splitLine: {
        length: 6,
        distance: -20,
        lineStyle: { width: 2, color: 'rgba(148, 163, 184, 0.6)' }
      },
      axisLabel: {
        distance: 24,
        fontSize: 11,
        color: '#94a3b8',
        formatter: (v) => (v % 20 === 0 ? String(v) : '')
      },
      anchor: {
        show: true,
        size: 10,
        itemStyle: {
          color: '#0f172a',
          borderWidth: 2,
          borderColor: '#fff'
        }
      },
      title: {
        show: true,
        offsetCenter: [0, '78%'],
        fontSize: 12,
        color: '#64748b'
      },
      detail: {
        valueAnimation: true,
        fontSize: 36,
        fontWeight: 800,
        color: accent,
        offsetCenter: [0, '8%'],
        formatter: '{value|{value}}\n{sub|综合}',
        rich: {
          value: {
            fontSize: 40,
            fontWeight: 800,
            color: accent,
            lineHeight: 44,
            fontFamily: 'Inter, PingFang SC, sans-serif'
          },
          sub: {
            fontSize: 12,
            color: '#64748b',
            lineHeight: 18,
            fontWeight: 500
          }
        }
      },
      data: [{ value: score, name: '综合得分' }]
    }]
  }, true)
}

function renderRadar () {
  if (!radarRef.value || !data.value) return
  if (!radarChart) radarChart = echarts.init(radarRef.value)
  const radar = data.value.trends?.dimensionRadar || []
  const indicators = radar.map((r) => ({ name: r.name, max: 100 }))
  const values = radar.map((r) => r.value)
  radarChart.setOption({
    tooltip: {},
    radar: {
      indicator: indicators,
      radius: '62%',
      splitNumber: 4,
      axisName: { color: '#64748b', fontSize: 11 },
      splitArea: { areaStyle: { color: ['rgba(241,245,249,0.8)', 'rgba(248,250,252,0.4)'] } },
      splitLine: { lineStyle: { color: '#e2e8f0' } },
      axisLine: { lineStyle: { color: '#cbd5e1' } }
    },
    series: [{
      type: 'radar',
      data: [{
        value: values,
        name: '能力分',
        areaStyle: {
          color: {
            type: 'radial',
            x: 0.5,
            y: 0.5,
            r: 0.5,
            colorStops: [
              { offset: 0, color: 'rgba(13, 148, 136, 0.35)' },
              { offset: 1, color: 'rgba(13, 148, 136, 0.05)' }
            ]
          }
        },
        lineStyle: { width: 2, color: '#0d9488' },
        itemStyle: { color: '#0d9488' }
      }]
    }]
  }, true)
}

function renderAuditTrend () {
  if (!auditTrendRef.value || !data.value) return
  if (!auditChart) auditChart = echarts.init(auditTrendRef.value)
  const rows = data.value.trends?.auditByDay || []
  auditChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['审计条数', '成功执行', '安全拦截'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 44, right: 16, top: 28, bottom: 52 },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.day),
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#64748b', fontSize: 10 }
    },
    series: [
      {
        name: '审计条数',
        type: 'bar',
        barMaxWidth: 28,
        data: rows.map((r) => r.total),
        itemStyle: { color: '#cbd5e1', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '成功执行',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: rows.map((r) => r.successExec),
        itemStyle: { color: '#16a34a' },
        lineStyle: { width: 2 }
      },
      {
        name: '安全拦截',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: rows.map((r) => r.blocked),
        itemStyle: { color: '#dc2626' },
        lineStyle: { width: 2 }
      }
    ]
  }, true)
}

function renderPatrolTrend () {
  if (!patrolTrendRef.value || !data.value) return
  if (!patrolChart) patrolChart = echarts.init(patrolTrendRef.value)
  const rows = data.value.trends?.patrolByDay || []
  patrolChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['告警线索', '巡检轮次'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 44, right: 16, top: 28, bottom: 52 },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.day),
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#64748b', fontSize: 10 }
    },
    series: [
      {
        name: '告警线索',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(217, 119, 6, 0.25)' },
              { offset: 1, color: 'rgba(217, 119, 6, 0.02)' }
            ]
          }
        },
        data: rows.map((r) => r.alertCount),
        itemStyle: { color: '#d97706' },
        lineStyle: { width: 2 }
      },
      {
        name: '巡检轮次',
        type: 'bar',
        barMaxWidth: 28,
        data: rows.map((r) => r.runCount),
        itemStyle: { color: '#0d9488', borderRadius: [4, 4, 0, 0] }
      }
    ]
  }, true)
}

function renderCharts () {
  nextTick(() => {
    renderGauge()
    renderRadar()
    renderAuditTrend()
    renderPatrolTrend()
    gaugeChart?.resize()
    radarChart?.resize()
    auditChart?.resize()
    patrolChart?.resize()
  })
}

async function loadData () {
  loading.value = true
  try {
    data.value = await getOpsEffectDashboard(periodDays.value)
    renderCharts()
  } catch (e) {
    ElMessage.error(e?.message || '加载运维效果评分失败')
    data.value = null
  } finally {
    loading.value = false
  }
}

function onResize () {
  gaugeChart?.resize()
  radarChart?.resize()
  auditChart?.resize()
  patrolChart?.resize()
}

watch(data, () => renderCharts())

onMounted(() => {
  loadData()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  gaugeChart?.dispose()
  radarChart?.dispose()
  auditChart?.dispose()
  patrolChart?.dispose()
})
</script>

<style scoped>
.ops-effect-dashboard {
  padding: 0;
}
.effect-card {
  border-radius: var(--ops-radius, 12px);
  border: 1px solid var(--ops-border, #e2e8f0);
}
.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.card-header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-header-left > span:first-child {
  font-size: 16px;
  font-weight: 700;
  color: var(--ops-text, #0f172a);
}
.card-sub {
  font-size: 12px;
  color: var(--ops-text-subtle, #64748b);
  font-weight: normal;
}
.card-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* ── 计分盘 Hero ── */
.score-hero-panel {
  display: flex;
  gap: 28px;
  align-items: stretch;
  flex-wrap: wrap;
  margin-bottom: 22px;
  padding: 20px 22px;
  border-radius: 14px;
  border: 1px solid var(--ops-border, #e2e8f0);
  background:
    radial-gradient(ellipse 120% 80% at 0% 0%, rgba(45, 212, 191, 0.12) 0%, transparent 55%),
    linear-gradient(135deg, #f8fafc 0%, #ffffff 48%, #f0fdfa 100%);
  box-shadow: var(--ops-shadow-sm, 0 1px 2px rgba(15, 23, 42, 0.05));
}

.gauge-panel {
  position: relative;
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.gauge-ring-bg {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(13, 148, 136, 0.06) 0%, transparent 70%);
  pointer-events: none;
}

.gauge-chart {
  width: 300px;
  height: 240px;
  position: relative;
  z-index: 1;
}

.gauge-meta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  margin-top: -8px;
  z-index: 1;
}

.gauge-grade {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.gauge-window {
  font-size: 11px;
  color: var(--ops-text-subtle, #64748b);
}

.hero-text {
  flex: 1;
  min-width: 280px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.hero-score-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 10px;
}

.hero-score-num {
  font-size: 48px;
  font-weight: 800;
  line-height: 1;
  color: var(--ops-text, #0f172a);
  font-variant-numeric: tabular-nums;
}

.hero-score-unit {
  font-size: 18px;
  font-weight: 600;
  color: var(--ops-text-subtle, #64748b);
  margin-right: 8px;
}

.hero-grade-tag {
  border: none;
}

.hero-headline {
  font-size: 15px;
  color: #334155;
  margin: 0 0 10px;
  line-height: 1.55;
}

.hero-bullets {
  margin: 0 0 14px;
  padding-left: 18px;
  color: #475569;
  font-size: 13px;
  line-height: 1.65;
}

.hero-bullets li::marker {
  color: var(--ops-primary, #0d9488);
}

.highlight-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.hl-tag {
  max-width: 100%;
}

/* ── 五维卡片 ── */
.dim-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}

@media (max-width: 1200px) {
  .dim-row {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .dim-row {
    grid-template-columns: 1fr 1fr;
  }
}

.dim-card {
  background: var(--ops-panel, #fff);
  border: 1px solid var(--ops-border, #e2e8f0);
  border-left: 3px solid var(--dim-accent, #0d9488);
  border-radius: var(--ops-radius, 10px);
  padding: 14px 14px 12px;
  transition: box-shadow 0.2s, transform 0.2s;
}

.dim-card:hover {
  box-shadow: var(--ops-shadow-md, 0 4px 16px rgba(15, 23, 42, 0.06));
  transform: translateY(-1px);
}

.dim-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.dim-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ops-text, #1e293b);
}

.dim-grade-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  color: #fff;
  line-height: 1.4;
}

.dim-score-row {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin: 8px 0 4px;
}

.dim-score {
  font-size: 26px;
  font-weight: 800;
  color: var(--ops-text, #0f172a);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.dim-score-unit {
  font-size: 12px;
  color: var(--ops-text-subtle, #64748b);
  font-weight: 500;
}

.dim-detail {
  font-size: 11px;
  color: var(--ops-text-subtle, #64748b);
  line-height: 1.45;
}

/* ── 快速 KPI ── */
.value-kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 0 0 12px;
}

@media (max-width: 768px) {
  .value-kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

.value-kpi {
  text-align: center;
  padding: 12px 8px;
  border-radius: var(--ops-radius, 10px);
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #ccfbf1;
  backdrop-filter: blur(4px);
}

.value-kpi.accent {
  border-color: #99f6e4;
  background: rgba(240, 253, 250, 0.9);
}

.vk-num {
  display: block;
  font-size: 24px;
  font-weight: 800;
  color: var(--ops-primary, #0d9488);
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.value-kpi.accent .vk-num {
  color: #0f766e;
}

.vk-label {
  font-size: 11px;
  color: var(--ops-text-subtle, #64748b);
  margin-top: 4px;
}

/* ── 底部 KPI 条 ── */
.kpi-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border-radius: var(--ops-radius, 10px);
  background: var(--ops-panel-soft, #f8fafc);
  border: 1px solid var(--ops-border-soft, #f1f5f9);
}

.kpi-chip {
  flex: 1 1 calc(14.28% - 8px);
  min-width: 88px;
  text-align: center;
  padding: 10px 6px;
  border-radius: var(--ops-radius-sm, 6px);
  background: var(--ops-panel, #fff);
  border: 1px solid var(--ops-border, #e2e8f0);
}

@media (max-width: 900px) {
  .kpi-chip {
    flex: 1 1 calc(25% - 8px);
  }
}

.kpi-val {
  display: block;
  font-size: 20px;
  font-weight: 700;
  color: #0f766e;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.kpi-label {
  display: block;
  font-size: 10px;
  color: var(--ops-text-subtle, #64748b);
  margin-top: 3px;
}

/* ── 图表区 ── */
.charts-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 1100px) {
  .charts-row {
    grid-template-columns: 1fr;
  }
}

.chart-card {
  border: 1px solid var(--ops-border, #e2e8f0);
  border-radius: var(--ops-radius, 10px);
}

.chart-card :deep(.el-card__header) {
  padding: 10px 14px;
  background: var(--ops-panel-soft, #f8fafc);
  border-bottom: 1px solid var(--ops-border-soft, #f1f5f9);
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ops-text, #0f172a);
}

.chart-box {
  height: 268px;
  width: 100%;
}

.table-card {
  margin-bottom: 12px;
}

.resource-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ops-text-subtle, #64748b);
  padding-top: 8px;
  border-top: 1px dashed var(--ops-border, #e2e8f0);
  margin-top: 4px;
}
</style>
