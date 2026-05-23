<template>
  <div class="ops-page ops-effect-dashboard">
    <el-card class="effect-card">
      <template #header>
        <div class="card-header">
          <div class="card-header-left">
            <span>运维效果评分</span>
            <span class="card-sub">量化展示安全、巡检、处置与自愈带来的价值</span>
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
          <div class="hero-row">
            <div ref="gaugeRef" class="gauge-chart" />
            <div class="hero-text">
              <div class="hero-grade">
                <span class="grade-label">{{ data.overallGrade }}</span>
                <el-tag type="success" effect="plain">{{ data.periodDays }} 日窗口</el-tag>
              </div>
              <p class="hero-headline">{{ summaryHeadline }}</p>
              <ul class="hero-bullets">
                <li v-for="(b, i) in summaryBullets" :key="i">{{ b }}</li>
              </ul>
              <div v-if="highlights.length" class="highlight-tags">
                <el-tag
                  v-for="(h, i) in highlights"
                  :key="i"
                  :type="highlightTagType(h.type)"
                  effect="light"
                  class="hl-tag"
                >
                  {{ h.title }}
                </el-tag>
              </div>
            </div>
          </div>

          <div class="dim-row">
            <div
              v-for="dim in dimensionList"
              :key="dim.id"
              class="dim-card"
            >
              <div class="dim-head">
                <span class="dim-name">{{ dim.name }}</span>
                <span class="dim-grade">{{ dim.grade }}</span>
              </div>
              <el-progress
                :percentage="dim.score"
                :stroke-width="10"
                :color="scoreColor(dim.score)"
              />
              <div class="dim-score">{{ dim.score }} 分</div>
              <div class="dim-detail">{{ dim.detail }}</div>
            </div>
          </div>

          <div class="kpi-row">
            <div v-for="k in kpiCards" :key="k.key" class="kpi-card">
              <div class="kpi-val">{{ k.value }}</div>
              <div class="kpi-label">{{ k.label }}</div>
            </div>
          </div>

          <div class="charts-row">
            <el-card class="chart-card" shadow="never">
              <template #header>五维能力雷达</template>
              <div ref="radarRef" class="chart-box" />
            </el-card>
            <el-card class="chart-card" shadow="never">
              <template #header>审计处置趋势</template>
              <div ref="auditTrendRef" class="chart-box" />
            </el-card>
            <el-card class="chart-card" shadow="never">
              <template #header>巡检告警趋势</template>
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
  return ['security', 'execution', 'patrol', 'healing', 'efficiency']
    .map((id) => dims[id])
    .filter(Boolean)
})
const recentHealing = computed(() => {
  const r = data.value?.recentEffectRuns
  return Array.isArray(r) ? r : []
})

const kpiCards = computed(() => {
  const k = data.value?.kpis || {}
  return [
    { key: 'audit', label: '审计链路', value: k.auditTotal ?? 0 },
    { key: 'ok', label: '成功执行', value: k.auditSuccessExec ?? 0 },
    { key: 'block', label: '安全拦截', value: k.auditBlocked ?? 0 },
    { key: 'confirm', label: '二次确认', value: k.auditNeedConfirm ?? 0 },
    { key: 'patrol', label: '巡检轮次', value: k.patrolRuns ?? 0 },
    { key: 'find', label: '巡检线索', value: k.patrolFindings ?? 0 },
    { key: 'heal', label: '自愈均分', value: k.healingSamples > 0 ? Math.round(k.avgHealingScore) : '—' }
  ]
})

const resourceLine = computed(() => {
  const r = data.value?.currentResource
  if (!r) return ''
  const fmt = (v) => (v != null && v !== '' ? `${Number(v).toFixed(1)}%` : '—')
  return `CPU ${fmt(r.cpuUsagePct)} · 内存 ${fmt(r.memoryUsagePct)} · 磁盘 ${fmt(r.diskUsagePct)}`
})

function scoreColor (score) {
  if (score >= 85) return '#22c55e'
  if (score >= 70) return '#3b82f6'
  if (score >= 55) return '#f59e0b'
  return '#ef4444'
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
  gaugeChart.setOption({
    series: [{
      type: 'gauge',
      startAngle: 200,
      endAngle: -20,
      min: 0,
      max: 100,
      splitNumber: 5,
      itemStyle: { color: scoreColor(score) },
      progress: { show: true, width: 14 },
      pointer: { show: true, length: '55%', width: 5 },
      axisLine: { lineStyle: { width: 14, color: [[0.55, '#f59e0b'], [0.7, '#3b82f6'], [1, '#22c55e']] } },
      axisTick: { show: false },
      splitLine: { length: 8, lineStyle: { width: 2, color: '#999' } },
      axisLabel: { distance: 20, fontSize: 10 },
      detail: {
        valueAnimation: true,
        fontSize: 28,
        fontWeight: 700,
        offsetCenter: [0, '20%'],
        formatter: '{value}'
      },
      data: [{ value: score, name: '综合分' }]
    }]
  })
}

function renderRadar () {
  if (!radarRef.value || !data.value) return
  if (!radarChart) radarChart = echarts.init(radarRef.value)
  const radar = data.value.trends?.dimensionRadar || []
  const indicators = radar.map((r) => ({ name: r.name, max: 100 }))
  const values = radar.map((r) => r.value)
  radarChart.setOption({
    tooltip: {},
    radar: { indicator: indicators, radius: '62%' },
    series: [{
      type: 'radar',
      data: [{ value: values, name: '能力分', areaStyle: { opacity: 0.2 } }],
      itemStyle: { color: '#3b82f6' }
    }]
  })
}

function renderAuditTrend () {
  if (!auditTrendRef.value || !data.value) return
  if (!auditChart) auditChart = echarts.init(auditTrendRef.value)
  const rows = data.value.trends?.auditByDay || []
  auditChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['审计条数', '成功执行', '安全拦截'], bottom: 0 },
    grid: { left: 40, right: 16, top: 24, bottom: 48 },
    xAxis: { type: 'category', data: rows.map((r) => r.day) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '审计条数', type: 'bar', data: rows.map((r) => r.total), itemStyle: { color: '#94a3b8' } },
      { name: '成功执行', type: 'line', smooth: true, data: rows.map((r) => r.successExec), itemStyle: { color: '#22c55e' } },
      { name: '安全拦截', type: 'line', smooth: true, data: rows.map((r) => r.blocked), itemStyle: { color: '#ef4444' } }
    ]
  })
}

function renderPatrolTrend () {
  if (!patrolTrendRef.value || !data.value) return
  if (!patrolChart) patrolChart = echarts.init(patrolTrendRef.value)
  const rows = data.value.trends?.patrolByDay || []
  patrolChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['告警线索', '巡检轮次'], bottom: 0 },
    grid: { left: 40, right: 16, top: 24, bottom: 48 },
    xAxis: { type: 'category', data: rows.map((r) => r.day) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '告警线索', type: 'line', smooth: true, areaStyle: { opacity: 0.15 }, data: rows.map((r) => r.alertCount), itemStyle: { color: '#f59e0b' } },
      { name: '巡检轮次', type: 'bar', data: rows.map((r) => r.runCount), itemStyle: { color: '#6366f1' } }
    ]
  })
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
  border-radius: 12px;
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
.card-sub {
  font-size: 12px;
  color: #64748b;
  font-weight: normal;
}
.card-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.hero-row {
  display: flex;
  gap: 24px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.gauge-chart {
  width: 280px;
  height: 220px;
  flex-shrink: 0;
}
.hero-text {
  flex: 1;
  min-width: 260px;
}
.hero-grade {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.grade-label {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}
.hero-headline {
  font-size: 15px;
  color: #334155;
  margin: 0 0 10px;
  line-height: 1.5;
}
.hero-bullets {
  margin: 0 0 12px;
  padding-left: 18px;
  color: #475569;
  font-size: 13px;
  line-height: 1.6;
}
.highlight-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.hl-tag {
  max-width: 100%;
}
.dim-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 20px;
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
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px;
}
.dim-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}
.dim-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.dim-grade {
  font-size: 11px;
  color: #64748b;
}
.dim-score {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 6px 0 4px;
}
.dim-detail {
  font-size: 11px;
  color: #64748b;
  line-height: 1.4;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px;
  margin-bottom: 20px;
}
@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(4, 1fr);
  }
}
.kpi-card {
  text-align: center;
  padding: 12px 8px;
  background: linear-gradient(180deg, #f0f9ff 0%, #fff 100%);
  border: 1px solid #e0f2fe;
  border-radius: 8px;
}
.kpi-val {
  font-size: 22px;
  font-weight: 700;
  color: #0369a1;
}
.kpi-label {
  font-size: 11px;
  color: #64748b;
  margin-top: 4px;
}
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
.chart-card :deep(.el-card__header) {
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
}
.chart-box {
  height: 260px;
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
  color: #64748b;
  padding-top: 8px;
}
</style>
