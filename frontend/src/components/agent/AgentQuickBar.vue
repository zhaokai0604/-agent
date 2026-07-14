<template>
  <div v-if="visible" class="agent-quick-bar">
    <span class="agent-quick-bar__label">常用</span>
    <button
      v-for="item in actions"
      :key="item.cmd"
      type="button"
      class="agent-quick-bar__chip"
      :disabled="loading"
      @click="$emit('select', item.cmd)"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  loading: { type: Boolean, default: false },
  show: { type: Boolean, default: true }
})

defineEmits(['select'])

/** 只保留能真实落到工具链、答辩常演示的短指令；去掉空口号与过窄场景 */
const actions = [
  { label: '一键巡检', cmd: '一键巡检本机健康状态' },
  { label: '查磁盘', cmd: '检查磁盘使用情况并扫描占用热点' },
  { label: '查负载进程', cmd: '查看系统负载和占用最高的进程' },
  { label: '看最近日志', cmd: '分析最近的系统日志' },
  { label: '确认执行', cmd: '确认执行' }
]

const visible = computed(() => props.show)
</script>

<style scoped>
.agent-quick-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 0 4px;
}

.agent-quick-bar__label {
  font-size: 12px;
  color: var(--agent-muted, #6b7280);
  font-weight: 500;
  flex-shrink: 0;
}

.agent-quick-bar__chip {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(13, 148, 136, 0.25);
  background: rgba(240, 253, 250, 0.9);
  color: #0f766e;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.agent-quick-bar__chip:hover:not(:disabled) {
  background: #ccfbf1;
  border-color: rgba(13, 148, 136, 0.45);
}

.agent-quick-bar__chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
