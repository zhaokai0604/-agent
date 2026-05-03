<template>
  <div class="quick-actions">
    <el-button 
      v-for="action in quickActions" 
      :key="action.id"
      size="small"
      @click="onActionClick(action.id)"
      class="action-button"
    >
      {{ action.label }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
// Props
const props = defineProps<{
  quickActions: Array<{ id: string; label: string }>;
}>();

// Emits
const emit = defineEmits<{
  (e: 'action', id: string): void;
}>();

// 处理按钮点击
const onActionClick = (id: string) => {
  emit('action', id);
};
</script>

<style scoped>
.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
  padding: 12px;
  background-color: #fafafa;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
}

.action-button {
  flex: 1;
  min-width: 120px;
  max-width: 180px;
}

/* 响应式 */
@media (max-width: 768px) {
  .action-button {
    min-width: 100px;
    font-size: 0.85em;
  }
}
</style>