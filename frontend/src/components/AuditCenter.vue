<template>
  <div class="ops-page unified-audit-center">
    <OpsPageHeader
      title="统一审计中心"
      subtitle="统一查看访问、对话、工具、修复、拦截与确认记录，并按 trace 串联详情时间线"
    >
      <template #actions>
        <el-select v-model="kind" size="small" class="audit-kind-select">
          <el-option
            v-for="option in kindOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-input-number v-model="limit" :min="20" :max="300" :step="20" size="small" />
        <el-button size="small" :loading="loading" @click="loadFeed">刷新</el-button>
        <el-button size="small" type="success" plain :disabled="!rows.length" @click="exportCsv">
          导出 CSV
        </el-button>
      </template>
    </OpsPageHeader>

    <div class="audit-summary-row">
      <article v-for="option in summaryCards" :key="option.key" class="audit-summary-card">
        <span class="audit-summary-card__label">{{ option.label }}</span>
        <strong>{{ option.count }}</strong>
      </article>
    </div>

    <div class="audit-layout">
      <el-card shadow="never" class="audit-list-card">
        <el-table
          :data="rows"
          border
          stripe
          height="620"
          v-loading="loading"
          row-key="entryId"
          highlight-current-row
          @row-click="openRow"
        >
          <el-table-column prop="auditKindLabel" label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="kindTagType(row.auditKind)">{{ row.auditKindLabel || row.auditKind }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetName" label="目标" min-width="220" show-overflow-tooltip />
          <el-table-column prop="decision" label="决策" width="130" />
          <el-table-column prop="requestChannel" label="通道" width="110" />
          <el-table-column prop="operatorUserId" label="操作人" width="110" />
          <el-table-column prop="durationMs" label="耗时(ms)" width="110" />
          <el-table-column prop="createdAt" label="时间" min-width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click.stop="openRow(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="audit-detail-card" v-loading="detailLoading">
        <template #header>
          <div class="audit-detail-card__header">
            <div>
              <h3>时间线详情</h3>
              <p>{{ selected?.targetName || '选择左侧记录查看完整链路' }}</p>
            </div>
            <el-tag v-if="selected?.auditKindLabel" size="small" :type="kindTagType(selected.auditKind)">
              {{ selected.auditKindLabel }}
            </el-tag>
          </div>
        </template>

        <el-empty v-if="!selected" description="选择一条审计记录后，在这里查看完整时间线" />

        <template v-else>
          <div class="audit-detail-meta">
            <div class="meta-item">
              <span class="meta-item__label">Trace ID</span>
              <code>{{ selected.traceId || '--' }}</code>
            </div>
            <div class="meta-item">
              <span class="meta-item__label">决策</span>
              <strong>{{ selected.decision || '--' }}</strong>
            </div>
            <div class="meta-item">
              <span class="meta-item__label">通道</span>
              <strong>{{ selected.requestChannel || '--' }}</strong>
            </div>
            <div class="meta-item">
              <span class="meta-item__label">时间</span>
              <strong>{{ formatTime(selected.createdAt) }}</strong>
            </div>
          </div>

          <div v-if="selected.resultSummary" class="audit-detail-block">
            <div class="audit-detail-block__title">结果摘要</div>
            <div class="audit-detail-block__body">{{ selected.resultSummary }}</div>
          </div>

          <div v-if="selected.effectSummary" class="audit-detail-block">
            <div class="audit-detail-block__title">效果摘要</div>
            <div class="audit-detail-block__body">{{ selected.effectSummary }}</div>
          </div>

          <div class="audit-detail-block">
            <div class="audit-detail-block__title">步骤时间线</div>
            <div v-if="selectedSteps.length" class="timeline-list">
              <div v-for="(step, index) in selectedSteps" :key="index" class="timeline-step">
                <span class="timeline-step__index">{{ index + 1 }}</span>
                <div class="timeline-step__content">
                  <strong>{{ step.phase || step.title || `步骤 ${index + 1}` }}</strong>
                  <pre>{{ stringifyStep(step) }}</pre>
                </div>
              </div>
            </div>
            <el-empty v-else description="当前记录没有步骤快照" />
          </div>

          <div class="audit-detail-block">
            <div class="audit-detail-block__title">原始记录</div>
            <pre class="raw-json">{{ JSON.stringify(selected.raw || {}, null, 2) }}</pre>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuditDetail, getAuditFeed } from '../api'
import { formatLocalDateKey } from '../utils/formatDate.js'
import { exportRowsAsCsv } from '../utils/tableExport.js'
import OpsPageHeader from './OpsPageHeader.vue'

const kind = ref('all')
const limit = ref(120)
const loading = ref(false)
const detailLoading = ref(false)
const rows = ref([])
const selected = ref(null)

const kindOptions = [
  { value: 'all', label: '全部' },
  { value: 'access', label: '访问' },
  { value: 'dialogue', label: '对话' },
  { value: 'tool', label: '工具' },
  { value: 'remediation', label: '修复' },
  { value: 'block', label: '拦截' },
  { value: 'confirm', label: '确认' }
]

const summaryCards = computed(() => {
  const counters = rows.value.reduce((acc, row) => {
    const key = row.auditKind || 'unknown'
    acc[key] = (acc[key] || 0) + 1
    return acc
  }, {})
  return [
    { key: 'total', label: '当前列表', count: rows.value.length },
    { key: 'access', label: '访问', count: counters.access || 0 },
    { key: 'dialogue', label: '对话', count: counters.dialogue || 0 },
    { key: 'tool', label: '工具', count: counters.tool || 0 },
    { key: 'confirm', label: '确认', count: counters.confirm || 0 },
    { key: 'block', label: '拦截', count: counters.block || 0 }
  ]
})

const selectedSteps = computed(() => {
  const steps = selected.value?.steps
  return Array.isArray(steps) ? steps : []
})

async function loadFeed() {
  loading.value = true
  try {
    const data = await getAuditFeed(limit.value, kind.value)
    rows.value = Array.isArray(data) ? data : []
    if (selected.value?.entryId) {
      const latest = rows.value.find(item => item.entryId === selected.value.entryId)
      if (latest) {
        selected.value = { ...selected.value, ...latest }
      }
    }
  } catch (error) {
    ElMessage.error(error.message || '加载统一审计列表失败')
  } finally {
    loading.value = false
  }
}

async function openRow(row) {
  const entryId = row?.entryId || ''
  const traceId = row?.traceId || ''
  if (!entryId && !traceId) return
  detailLoading.value = true
  try {
    selected.value = await getAuditDetail({ entryId, traceId })
  } catch (error) {
    ElMessage.error(error.message || '加载审计详情失败')
  } finally {
    detailLoading.value = false
  }
}

function kindTagType(auditKind) {
  switch (auditKind) {
    case 'access':
      return 'info'
    case 'dialogue':
      return ''
    case 'tool':
      return 'success'
    case 'remediation':
      return 'warning'
    case 'block':
      return 'danger'
    case 'confirm':
      return 'warning'
    default:
      return 'info'
  }
}

function formatTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN')
}

function stringifyStep(step) {
  if (!step || typeof step !== 'object') return String(step || '')
  if (typeof step.detail === 'string') return step.detail
  if (step.detail && typeof step.detail === 'object') return JSON.stringify(step.detail, null, 2)
  return JSON.stringify(step, null, 2)
}

function exportCsv() {
  if (!rows.value.length) {
    ElMessage.warning('当前没有可导出的审计数据')
    return
  }
  exportRowsAsCsv({
    rows: rows.value,
    columns: [
      { key: 'entryId', label: 'Entry ID' },
      { key: 'traceId', label: 'Trace ID' },
      { key: 'auditKindLabel', label: '类型' },
      { key: 'targetName', label: '目标' },
      { key: 'decision', label: '决策' },
      { key: 'requestChannel', label: '通道' },
      { key: 'operatorUserId', label: '操作人' },
      { key: 'durationMs', label: '耗时(ms)' },
      { key: 'createdAt', label: '时间' }
    ],
    filename: `unified-audit-${formatLocalDateKey(new Date())}.csv`
  })
  ElMessage.success('统一审计数据已导出')
}

function openTrace(traceId) {
  if (!traceId) return
  const row = rows.value.find(item => item.traceId === traceId)
  if (row) {
    openRow(row)
    return
  }
  openRow({ traceId })
}

function onNavigateTab(event) {
  const detail = event?.detail || {}
  if (!['audit', 'ops-audit-trace'].includes(detail.tab)) return
  if (detail.traceId) {
    openTrace(detail.traceId)
  }
}

onMounted(() => {
  loadFeed()
  window.addEventListener('ops-navigate-tab', onNavigateTab)
})

onUnmounted(() => {
  window.removeEventListener('ops-navigate-tab', onNavigateTab)
})

defineExpose({ openTrace })
</script>

<style scoped>
.unified-audit-center {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.audit-kind-select {
  width: 128px;
}

.audit-summary-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.audit-summary-card {
  padding: 14px 16px;
  border-radius: 14px;
  background: linear-gradient(180deg, #fff, #f8fafc);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.05);
}

.audit-summary-card__label {
  display: block;
  font-size: 12px;
  color: #64748b;
}

.audit-summary-card strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  color: #0f172a;
}

.audit-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 0.8fr);
  gap: 16px;
  align-items: start;
}

.audit-list-card,
.audit-detail-card {
  border-radius: 18px;
}

.audit-detail-card__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: start;
}

.audit-detail-card__header h3 {
  margin: 0;
  font-size: 16px;
}

.audit-detail-card__header p {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.5;
}

.audit-detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.meta-item {
  padding: 12px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.meta-item__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: #64748b;
}

.meta-item code {
  font-family: var(--ops-font-mono, ui-monospace, monospace);
}

.audit-detail-block {
  margin-top: 14px;
}

.audit-detail-block__title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.audit-detail-block__body,
.raw-json,
.timeline-step pre {
  margin: 0;
  padding: 12px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: #334155;
}

.timeline-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.timeline-step {
  display: flex;
  gap: 10px;
  align-items: start;
}

.timeline-step__index {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.12);
  color: #0f766e;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.timeline-step__content {
  flex: 1;
  min-width: 0;
}

.timeline-step__content strong {
  display: block;
  margin-bottom: 6px;
  color: #0f172a;
}

@media (max-width: 1100px) {
  .audit-layout {
    grid-template-columns: 1fr;
  }

  .audit-detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
