<template>
  <div v-if="plan" class="remediation-plan-card">
    <div class="plan-head">
      <el-icon><List /></el-icon>
      <span class="plan-title">{{ plan.title }}</span>
      <el-tag v-if="plan.previewOnly" size="small" type="warning">预览模式</el-tag>
    </div>
    <ul v-if="plan.items?.length" class="plan-items">
      <li v-for="(item, i) in plan.items" :key="i">{{ item }}</li>
    </ul>
    <p v-else class="plan-body">{{ plan.body }}</p>
    <div v-if="showConfirm" class="plan-actions">
      <el-button type="primary" size="small" :loading="loading" @click="$emit('confirm')">
        确认执行
      </el-button>
      <span class="plan-hint">将在路径白名单与策略允许范围内落地写操作</span>
    </div>
  </div>
</template>

<script setup>
import { List } from '@element-plus/icons-vue'

defineProps({
  plan: { type: Object, default: null },
  showConfirm: { type: Boolean, default: true },
  loading: { type: Boolean, default: false }
})

defineEmits(['confirm'])
</script>

<style scoped>
.remediation-plan-card {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}

.plan-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 600;
  font-size: 14px;
}

.plan-items {
  margin: 0 0 10px;
  padding-left: 1.2em;
  font-size: 13px;
  line-height: 1.55;
}

.plan-body {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.plan-hint {
  font-size: 12px;
  color: var(--ops-text-muted, #888);
}
</style>
