<template>
  <div v-if="visible" class="assistant-evidence-bar">
    <div v-if="ragHits?.length" class="evidence-section">
      <span class="evidence-label">参考知识库</span>
      <div class="rag-chips">
        <el-tag
          v-for="(hit, i) in ragHits.slice(0, 4)"
          :key="hit.id || i"
          size="small"
          type="info"
          class="rag-chip"
          :title="hit.content"
          @click="$emit('open-knowledge')"
        >
          {{ hit.title || '未命名' }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  ragHits: { type: Array, default: () => [] }
})

defineEmits(['open-knowledge'])

const visible = computed(() => props.ragHits && props.ragHits.length > 0)
</script>

<style scoped>
.assistant-evidence-bar {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--ops-border-soft, #e4e7ed);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-section {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
}

.evidence-label {
  color: var(--ops-text-muted, #888);
  flex-shrink: 0;
}

.rag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rag-chip {
  cursor: pointer;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
