<template>
  <div class="ops-page audit-center">
    <el-tabs v-model="activeTab" class="audit-tabs">
      <el-tab-pane label="访问记录" name="api">
        <AiAuditCenter embedded />
      </el-tab-pane>
      <el-tab-pane label="执行链路" name="trace">
        <OpsTraceCenter ref="traceRef" embedded />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import AiAuditCenter from './AiAuditCenter.vue'
import OpsTraceCenter from './OpsTraceCenter.vue'

const activeTab = ref('trace')
const traceRef = ref(null)

function focusTrace(traceId) {
  activeTab.value = 'trace'
  if (!traceId) return
  nextTick(() => traceRef.value?.openTrace?.(traceId))
}

function onNavigateTab(ev) {
  const d = ev.detail
  if (!d || d.tab !== 'audit') return
  if (d.auditPane) activeTab.value = d.auditPane
  if (d.traceId) focusTrace(d.traceId)
}

onMounted(() => {
  window.addEventListener('ops-navigate-tab', onNavigateTab)
})

onUnmounted(() => {
  window.removeEventListener('ops-navigate-tab', onNavigateTab)
})

defineExpose({ focusTrace })
</script>

<style scoped>
.audit-center {
  padding: 0;
}

.audit-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.audit-tabs :deep(.el-tabs__content) {
  overflow: visible;
}
</style>
