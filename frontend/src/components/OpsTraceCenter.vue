<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>运维链路审计（接收 → 安全校验 → 执行）</span>
        <div>
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table :data="rows" border stripe height="560">
      <el-table-column prop="traceId" label="traceId" min-width="200" show-overflow-tooltip />
      <el-table-column prop="channel" label="通道" width="80" />
      <el-table-column prop="riskLevel" label="风险" width="90" />
      <el-table-column prop="operatorUserId" label="操作者" width="100" show-overflow-tooltip />
      <el-table-column prop="policyVersion" label="策略版本" width="120" show-overflow-tooltip />
      <el-table-column prop="securityOutcome" label="安全结论" width="140" />
      <el-table-column prop="toolName" label="工具/阶段" width="140" show-overflow-tooltip />
      <el-table-column prop="executionOk" label="成功" width="70">
        <template #default="scope">
          <el-tag :type="scope.row.executionOk ? 'success' : 'danger'" size="small">
            {{ scope.row.executionOk ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时ms" width="100" />
      <el-table-column prop="resultSummary" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="时间" min-width="180" />
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getOpsTraceRecent } from '../api'

const rows = ref([])
const loading = ref(false)
const limit = ref(100)

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

onMounted(loadData)
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
</style>
