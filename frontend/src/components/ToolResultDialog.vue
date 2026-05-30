<template>
  <el-dialog
    v-model="visible"
    title="执行结果"
    width="760px"
    destroy-on-close
    class="tool-result-dialog"
    @closed="onClosed"
  >
    <div v-if="payload" class="result-body">
      <div class="result-header">
        <el-tag size="large" type="success">{{ displayName }}</el-tag>
        <el-tag :type="payload.success ? 'success' : 'danger'">
          {{ payload.success ? '成功' : '失败' }}
        </el-tag>
        <span v-if="payload.duration" class="duration">耗时 {{ payload.duration }}ms</span>
      </div>

      <div class="result-structured">
        <StructuredResultView :data="viewPayload" />
      </div>

      <p v-if="showTrace && payload.traceId" class="trace-line">
        追踪 ID：<code>{{ payload.traceId }}</code>
        <span class="trace-hint">可在「溯源 → 执行链路」中查看完整记录</span>
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="showTrace && payload?.traceId" type="primary" plain @click="goTrace">前往溯源</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StructuredResultView from './StructuredResultView.vue'
import { mcpToolDisplayName } from '../utils/mcpToolsMeta'
import { payloadForStructuredView } from '../utils/structuredDataView'

const visible = ref(false)
const payload = ref(null)
const showTrace = ref(true)

const displayName = computed(() =>
  payload.value?.toolName ? mcpToolDisplayName(payload.value.toolName) : '执行结果'
)

const viewPayload = computed(() =>
  payload.value?.raw != null ? payloadForStructuredView(payload.value.raw) : null
)

function open(result) {
  if (!result) return
  showTrace.value = result.showTrace !== false
  payload.value = {
    toolName: result.toolName,
    success: result.success === true,
    duration: result.duration || 0,
    traceId: result.traceId || result.raw?.traceId || result.raw?.trace_id || '',
    raw: result.raw ?? result.fullResult ?? result
  }
  visible.value = true
}

function onClosed() {
  payload.value = null
}

function goTrace() {
  const tid = payload.value?.traceId
  visible.value = false
  window.dispatchEvent(
    new CustomEvent('ops-navigate-tab', { detail: { tab: 'audit', auditPane: 'trace', traceId: tid } })
  )
  if (tid) {
    ElMessage.info('已打开「溯源」，可在执行链路中查看详情')
  }
}

defineExpose({ open })
</script>

<style scoped>
.result-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.duration {
  color: var(--ops-text-subtle, #909399);
  font-size: 13px;
}

.result-structured {
  max-height: min(56vh, 480px);
  overflow-y: auto;
  padding: 4px 2px;
}

.trace-line {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ops-text-subtle, #64748b);
}

.trace-line code {
  font-family: var(--ops-font-mono, monospace);
  background: var(--ops-panel-soft, #f1f5f9);
  padding: 2px 6px;
  border-radius: 4px;
}

.trace-hint {
  margin-left: 8px;
}
</style>
