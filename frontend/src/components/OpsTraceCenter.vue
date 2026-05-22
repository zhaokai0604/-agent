<template>
  <div class="ops-page">
  <el-card shadow="never" class="ops-surface-card">
    <template #header>
      <div class="header">
        <span>运维链路审计（接收 → 感知 → 推理 → 校验 → 执行）</span>
        <div class="header-actions">
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>
    </template>

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

    <el-dialog v-model="cotVisible" title="思维链审计（CoT）" width="720px" destroy-on-close @opened="onCotOpened">
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
        <ol class="cot-steps">
          <li v-for="(step, idx) in cotStepsNormalized" :key="idx" class="cot-step">
            <div class="cot-step-title">{{ step.title }}</div>
            <pre class="cot-step-body">{{ step.body }}</pre>
          </li>
        </ol>
        <el-divider v-if="cotDetail.resultSummary" content-position="left">结果摘要</el-divider>
        <pre v-if="cotDetail.resultSummary" class="cot-summary">{{ cotDetail.resultSummary }}</pre>
      </template>
      <div v-else class="cot-empty">暂无数据</div>
    </el-dialog>
  </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getOpsTraceRecent, getOpsTraceDetail } from '../api'

const rows = ref([])
const loading = ref(false)
const limit = ref(100)

const cotVisible = ref(false)
const cotLoading = ref(false)
const cotDetail = ref({})
const pendingTraceId = ref('')

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
    const detail = s?.detail != null ? String(s.detail) : ''
    const title = s?.title || s?.phase || 'step'
    const stepNum = s?.step != null ? `[${s.step}] ` : ''
    return {
      title: `${stepNum}${title}`,
      body: detail || JSON.stringify(s, null, 2)
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
  margin: 0;
  padding-left: 1.2rem;
}
.cot-step {
  margin-bottom: 12px;
}
.cot-step-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
}
.cot-step-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.5;
  background: var(--el-fill-color-light);
  padding: 10px 12px;
  border-radius: 8px;
  max-height: 220px;
  overflow: auto;
}
.cot-summary {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 8px;
  max-height: 280px;
  overflow: auto;
}
</style>
