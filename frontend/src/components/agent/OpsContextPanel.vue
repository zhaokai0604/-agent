<template>
  <aside class="ops-context-panel" :class="{ 'ops-context-panel--embedded': embedded }">
    <div v-if="!embedded" class="ops-context-panel__header">本机态势</div>
    <div class="ops-context-panel__body">
      <TopPerformanceBar layout="vertical" />
    </div>
  </aside>
</template>

<script setup>
import TopPerformanceBar from '../TopPerformanceBar.vue'

defineProps({
  embedded: { type: Boolean, default: false }
})
</script>

<style scoped>
.ops-context-panel--embedded {
  width: 100%;
  border: none;
  background: transparent;
}

.ops-context-panel--embedded .ops-context-panel__body {
  padding: 0;
}
</style>
