<template>
  <div class="ops-page">
  <el-card shadow="never" class="ops-surface-card">
    <template #header>
      <div class="header">
        <span>运维链路审计（接收 → 感知 → 推理 → 校验 → 执行）</span>
        <div class="header-actions">
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
          <el-button size="small" type="info" plain :disabled="!rows.length" @click="onExportPng">导出 PNG</el-button>
          <el-button size="small" type="info" plain :disabled="!rows.length" @click="onExportPdf">导出 PDF</el-button>
        </div>
      </div>
    </template>

    <div ref="exportWrapRef" class="audit-export-wrap">
    <el-table :data="rows" border stripe height="520">
      <el-table-column prop="traceId" label="traceId" min-width="200" show-overflow-tooltip />
      <el-table-column prop="channel" label="通道" width="76" />
      <el-table-column prop="riskLevel" label="风险" width="88" />
      <el-table-column prop="securityOutcome" label="安全结论" width="130" show-overflow-tooltip />
      <el-table-column prop="toolName" label="工具" width="140" show-overflow-tooltip />
      <el-table-column prop="executionOk" label="成功" width="72">
        <template #default="scope">
          <el-tag :type="scope.row.executionOk ? 'success' : 'danger'" size="small">
            {{ scope.row.executionOk ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时ms" width="92" />
      <el-table-column prop="resultSummary" label="摘要" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="时间" min-width="170" show-overflow-tooltip />
      <el-table-column label="思维链" width="100" fixed="right">
        <template #default="scope">
          <el-button type="primary" link size="small" @click="openCot(scope.row.traceId)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog
      v-model="cotVisible"
      width="720px"
      destroy-on-close
      class="cot-dialog"
      @opened="onCotOpened"
    >
      <template #header>
        <div class="cot-dialog-header">
          <span class="cot-dialog-title">思维链审计（CoT）</span>
          <div v-if="cotDetail.traceId && !cotLoading" class="cot-dialog-actions">
            <el-button size="small" type="info" plain :loading="cotExporting" @click="onExportCotPng">
              导出 PNG
            </el-button>
            <el-button size="small" type="info" plain :loading="cotExporting" @click="onExportCotPdf">
              导出 PDF
            </el-button>
          </div>
        </div>
      </template>
      <div ref="cotExportRef" class="cot-export-wrap">
      <div v-if="cotLoading" class="cot-loading">加载中…</div>
      <template v-else-if="cotDetail.traceId">
        <el-descriptions :column="1" border size="small" class="cot-meta">
          <el-descriptions-item label="traceId">{{ cotDetail.traceId }}</el-descriptions-item>
          <el-descriptions-item label="通道">{{ cotDetail.channel }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ cotDetail.toolName }}</el-descriptions-item>
          <el-descriptions-item label="安全结论">{{ cotDetail.securityOutcome }}</el-descriptions-item>
          <el-descriptions-item label="风险">{{ cotDetail.riskLevel }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">四步闭环</el-divider>
        <div class="cot-steps">
          <el-card
            v-for="(step, idx) in cotStepsNormalized"
            :key="idx"
            shadow="never"
            class="cot-step-card"
            body-style="padding: 12px 14px"
          >
            <template #header>
              <div class="cot-step-header">
                <el-tag size="small" type="primary" effect="plain">步骤 {{ idx + 1 }}</el-tag>
                <span class="cot-step-title">{{ step.title }}</span>
              </div>
            </template>
            <StructuredResultView :view-model="step.view" />
          </el-card>
        </div>
        <el-divider v-if="cotDetail.resultSummary" content-position="left">结果摘要</el-divider>
        <el-card
          v-if="cotDetail.resultSummary"
          shadow="never"
          class="cot-summary-card export-scroll-unlock"
          body-style="padding: 12px 14px"
        >
          <StructuredResultView :data="cotDetail.resultSummary" />
        </el-card>
      </template>
      <div v-else class="cot-empty">暂无数据</div>
      </div>
    </el-dialog>
  </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getOpsTraceRecent, getOpsTraceDetail } from '../api'
import { buildStructuredViewModel, coerceStructuredInput } from '../utils/structuredDataView'
import StructuredResultView from './StructuredResultView.vue'
import { formatLocalDateKey } from '../utils/formatDate.js'
import {
  exportTableAsPng,
  exportTableAsPdf,
  buildTableSummaryLine
} from '../utils/tableExport.js'

const rows = ref([])
const loading = ref(false)
const limit = ref(100)
const exportWrapRef = ref(null)
const exporting = ref(false)

const cotVisible = ref(false)
const cotLoading = ref(false)
const cotDetail = ref({})
const pendingTraceId = ref('')
const cotExportRef = ref(null)
const cotExporting = ref(false)

const loadData = async () => {
  loading.value = true
  try {
    rows.value = await getOpsTraceRecent(limit.value)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

const cotStepsNormalized = computed(() => {
  const steps = cotDetail.value?.steps
  if (!Array.isArray(steps)) return []
  return steps.map((s) => {
    const title = s?.title || s?.phase || '步骤'
    const stepNum = s?.step != null ? `[${s.step}] ` : ''
    const rawBody = s?.detail != null ? s.detail : s
    const parsed = coerceStructuredInput(rawBody)
    return {
      title: `${stepNum}${title}`,
      view: buildStructuredViewModel(parsed)
    }
  })
})

const openCot = (traceId) => {
  if (!traceId) return
  pendingTraceId.value = traceId
  cotDetail.value = {}
  cotVisible.value = true
}

const onCotOpened = async () => {
  const id = pendingTraceId.value
  if (!id) return
  cotLoading.value = true
  try {
    cotDetail.value = await getOpsTraceDetail(id)
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
    cotDetail.value = {}
  } finally {
    cotLoading.value = false
  }
}

const cotExportBasename = () => {
  const tid = String(cotDetail.value?.traceId || 'unknown').replace(/[^\w-]/g, '').slice(0, 24)
  return `ops-cot-${tid}-${formatLocalDateKey(new Date())}`
}

const cotExportSummary = () => {
  const d = cotDetail.value || {}
  return `traceId ${d.traceId || '—'} · 工具 ${d.toolName || '—'} · ${d.securityOutcome || '—'} · 风险 ${d.riskLevel || '—'} · ${cotStepsNormalized.value.length} 步 · 导出 ${new Date().toLocaleString()}`
}

const onExportCotPng = async () => {
  if (!cotExportRef.value || !cotDetail.value?.traceId) {
    ElMessage.warning('请先等待思维链加载完成')
    return
  }
  cotExporting.value = true
  try {
    await exportTableAsPng(cotExportRef.value, `${cotExportBasename()}.png`)
    ElMessage.success('思维链已导出 PNG')
  } catch (e) {
    ElMessage.error(e?.message || 'PNG 导出失败')
  } finally {
    cotExporting.value = false
  }
}

const onExportCotPdf = async () => {
  if (!cotExportRef.value || !cotDetail.value?.traceId) {
    ElMessage.warning('请先等待思维链加载完成')
    return
  }
  cotExporting.value = true
  try {
    await exportTableAsPdf({
      element: cotExportRef.value,
      title: '运维链路思维链（CoT）',
      summaryLine: cotExportSummary(),
      filename: `${cotExportBasename()}.pdf`
    })
    ElMessage.success('思维链已导出 PDF')
  } catch (e) {
    ElMessage.error(e?.message || 'PDF 导出失败')
  } finally {
    cotExporting.value = false
  }
}

const onExportPng = async () => {
  if (!exportWrapRef.value || !rows.value.length) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    await exportTableAsPng(
      exportWrapRef.value,
      `ops-trace-audit-${formatLocalDateKey(new Date())}.png`
    )
    ElMessage.success('已导出 PNG')
  } catch (e) {
    ElMessage.error(e?.message || 'PNG 导出失败')
  } finally {
    exporting.value = false
  }
}

const onExportPdf = async () => {
  if (!exportWrapRef.value || !rows.value.length) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const okCount = rows.value.filter((r) => r.executionOk).length
    await exportTableAsPdf({
      element: exportWrapRef.value,
      title: '运维链路审计',
      summaryLine: buildTableSummaryLine(
        rows.value.length,
        `成功 ${okCount} 条 · 最近 ${limit.value} 条`
      ),
      filename: `ops-trace-audit-${formatLocalDateKey(new Date())}.pdf`
    })
    ElMessage.success('已导出 PDF')
  } catch (e) {
    ElMessage.error(e?.message || 'PDF 导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.audit-export-wrap {
  background: #fff;
}
.cot-loading,
.cot-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.cot-meta {
  margin-bottom: 8px;
}
.cot-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cot-step-card {
  border: 1px solid var(--el-border-color-lighter);
}
.cot-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cot-step-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.cot-summary-card {
  border: 1px solid var(--el-border-color-lighter);
  max-height: 320px;
  overflow-y: auto;
}
.cot-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 8px;
}
.cot-dialog-title {
  font-weight: 600;
  font-size: 16px;
}
.cot-dialog-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.cot-export-wrap {
  background: #fff;
  padding: 4px 0;
}
</style>
