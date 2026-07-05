<template>
  <div v-if="visible" class="assistant-evidence-bar">
    <div v-if="toolsUsed?.length" class="evidence-section">
      <span class="evidence-label">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        真实工具
      </span>
      <div class="tool-chips">
        <span
          v-for="(tool, i) in toolsUsed.slice(0, 6)"
          :key="tool + i"
          class="tool-chip"
          :title="tool"
        >
          {{ displayToolName(tool) }}
        </span>
        <span v-if="toolsUsed.length > 6" class="tool-more">+{{ toolsUsed.length - 6 }}</span>
      </div>
    </div>
    <div v-if="awmLabel" class="evidence-section">
      <span class="evidence-label">AWM</span>
      <span class="awm-chip" :title="awmWorkflowId">{{ awmLabel }}</span>
    </div>
    <div v-if="outcomeLabel" class="evidence-section">
      <span class="evidence-label">结果</span>
      <span class="outcome-chip" :class="outcomeClass">{{ outcomeLabel }}</span>
      <span v-if="traceId" class="trace-chip" :title="traceId">溯源 {{ shortTrace(traceId) }}</span>
    </div>
    <div v-if="replyModeLabel" class="evidence-section evidence-section--muted">
      <span class="evidence-label">{{ replyModeLabel }}</span>
    </div>
    <div v-if="ragHits?.length" class="evidence-section">
      <span class="evidence-label">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 6v12M8 10h8M8 14h5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          <rect x="4" y="3" width="16" height="18" rx="2" stroke="currentColor" stroke-width="1.6" />
        </svg>
        参考
      </span>
      <div class="rag-chips">
        <button
          v-for="(hit, i) in ragHits.slice(0, 4)"
          :key="hit.id || i"
          type="button"
          class="rag-chip"
          :title="hit.content"
          @click="$emit('open-knowledge')"
        >
          {{ hit.title || '未命名' }}
        </button>
        <span v-if="ragHits.length > 4" class="rag-more">+{{ ragHits.length - 4 }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { mcpToolDisplayName } from '../utils/mcpToolsMeta'
import { securityOutcomeLabel } from '../utils/assistantSseMeta'

const props = defineProps({
  ragHits: { type: Array, default: () => [] },
  toolsUsed: { type: Array, default: () => [] },
  replyMode: { type: String, default: '' },
  securityOutcome: { type: String, default: '' },
  awmWorkflowId: { type: String, default: '' },
  awmWorkflowTitle: { type: String, default: '' },
  traceId: { type: String, default: '' }
})

defineEmits(['open-knowledge'])

const outcomeLabel = computed(() => securityOutcomeLabel(props.securityOutcome))

const outcomeClass = computed(() => {
  switch (props.securityOutcome) {
    case 'EXECUTED':
      return 'outcome-chip--ok'
    case 'DIAGNOSED':
      return 'outcome-chip--info'
    case 'PREVIEW':
      return 'outcome-chip--warn'
    case 'ERROR':
      return 'outcome-chip--err'
    default:
      return ''
  }
})

const awmLabel = computed(() => {
  if (props.awmWorkflowTitle) return props.awmWorkflowTitle
  if (props.awmWorkflowId) return props.awmWorkflowId
  return ''
})

const visible = computed(
  () =>
    (props.toolsUsed && props.toolsUsed.length > 0) ||
    (props.ragHits && props.ragHits.length > 0) ||
    !!replyModeLabel.value ||
    !!outcomeLabel.value ||
    !!awmLabel.value
)

const replyModeLabel = computed(() => {
  switch (props.replyMode) {
    case 'ORCHESTRATE':
      return '巡检报告 · 本机真实采集'
    case 'TOOL_AGENT':
      return '工具采集 · 实时数据'
    default:
      return ''
  }
})

function displayToolName (tool) {
  return mcpToolDisplayName(tool) || tool
}

function shortTrace (id) {
  if (!id) return ''
  return id.length > 12 ? `${id.slice(0, 8)}…` : id
}
</script>

<style scoped>
.assistant-evidence-bar {
  margin-top: 12px;
  padding-top: 0;
  border-top: none;
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

.evidence-section--muted .evidence-label {
  color: #6b7280;
  font-weight: 500;
}

.evidence-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #9ca3af;
  flex-shrink: 0;
  font-weight: 500;
}

.evidence-label svg {
  width: 14px;
  height: 14px;
}

.tool-chips,
.rag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.tool-chip {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #ccfbf1;
  background: #f0fdfa;
  color: #0f766e;
  font-size: 12px;
  line-height: 1.2;
  font-weight: 600;
}

.awm-chip {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #dbeafe;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outcome-chip {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #4b5563;
}

.outcome-chip--ok {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #15803d;
}

.outcome-chip--info {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.outcome-chip--warn {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}

.outcome-chip--err {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.trace-chip {
  color: #9ca3af;
  font-family: ui-monospace, monospace;
  font-size: 11px;
}

.tool-more,
.rag-more {
  color: #9ca3af;
  font-size: 12px;
}

.rag-chip {
  cursor: pointer;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #4b5563;
  font-size: 12px;
  line-height: 1.2;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}

.rag-chip:hover {
  background: #f0fdfa;
  border-color: #99f6e4;
  color: #0f766e;
}
</style>
