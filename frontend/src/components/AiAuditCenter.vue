<template>
  <div class="ops-page">
  <el-card shadow="never" class="ops-surface-card">
    <template #header>
      <div class="header">
        <span>AI 审计中心</span>
        <div class="header-actions">
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
          <el-button size="small" type="info" plain :disabled="!rows.length" @click="onExportPng">导出 PNG</el-button>
          <el-button size="small" type="info" plain :disabled="!rows.length" @click="onExportPdf">导出 PDF</el-button>
        </div>
      </div>
    </template>

    <div ref="exportWrapRef" class="audit-export-wrap">
    <el-table :data="rows" border stripe height="560">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="user_id" label="用户ID" width="100" />
      <el-table-column prop="user_role" label="角色" width="80" />
      <el-table-column prop="remote_ip" label="IP" width="140" />
      <el-table-column prop="method" label="方法" width="80" />
      <el-table-column prop="path" label="路径" min-width="240" />
      <el-table-column prop="status" label="状态码" width="90" />
      <el-table-column prop="duration_ms" label="耗时(ms)" width="110" />
      <el-table-column prop="request_bytes" label="请求字节" width="100" />
      <el-table-column prop="created_at" label="时间" min-width="180" />
    </el-table>
    </div>
  </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiAuditRecent } from '../api'
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

const loadData = async () => {
  loading.value = true
  try {
    rows.value = await getAiAuditRecent(limit.value)
  } catch (e) {
    ElMessage.error(e.message || '加载AI审计失败')
  } finally {
    loading.value = false
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
      `ai-audit-${formatLocalDateKey(new Date())}.png`
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
    await exportTableAsPdf({
      element: exportWrapRef.value,
      title: 'AI 审计中心',
      summaryLine: buildTableSummaryLine(rows.value.length, `最近 ${limit.value} 条`),
      filename: `ai-audit-${formatLocalDateKey(new Date())}.pdf`
    })
    ElMessage.success('已导出 PDF')
  } catch (e) {
    ElMessage.error(e?.message || 'PDF 导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.audit-export-wrap { background: #fff; }
</style>
