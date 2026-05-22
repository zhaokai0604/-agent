<template>
  <div class="ops-page">
  <el-card shadow="never" class="ops-surface-card">
    <template #header>
      <div class="header">
        <span>AI 审计中心</span>
        <div>
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </div>
    </template>

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
  </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiAuditRecent } from '../api'

const rows = ref([])
const loading = ref(false)
const limit = ref(100)

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

onMounted(loadData)
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.header > div { display: flex; gap: 8px; align-items: center; }
</style>
