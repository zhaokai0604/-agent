<template>
  <div class="message-bubble" :class="[message.role, { 'high-risk': message.riskLevel === 'HIGH' }]">
    <div class="message-content">
      <div class="message-text" v-if="message.role === 'assistant'" v-html="parsedContent"></div>
      <div class="message-text" v-else>{{ message.content }}</div>
      
      <!-- 风险警告 -->
      <div v-if="message.riskLevel === 'HIGH'" class="risk-warning">
        ⚠️ 该操作存在安全风险，已被拦截
      </div>
      
      <!-- 时间戳和 TraceId -->
      <div class="message-meta">
        <span class="timestamp">{{ formatTime(message.timestamp) }}</span>
        <span v-if="message.traceId" class="trace-id">
          TraceId: {{ message.traceId }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { Message } from '../../types/chat';
import { parseMarkdown } from '../../utils/markdown';

// Props
const props = defineProps<{
  message: Message;
}>();

// 计算属性：解析后的 Markdown 内容
const parsedContent = computed(() => {
  if (props.message.role === 'assistant') {
    return parseMarkdown(props.message.content);
  }
  return props.message.content;
});

// 格式化时间
const formatTime = (timestamp: number): string => {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
};
</script>

<style scoped>
.message-bubble {
  display: flex;
  margin: 10px 0;
  animation: fadeIn 0.3s ease;
}

.user {
  justify-content: flex-end;
}

.assistant {
  justify-content: flex-start;
}

.message-content {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 18px;
  position: relative;
  word-wrap: break-word;
}

.user .message-content {
  background-color: #95ec69;
  border-bottom-right-radius: 4px;
}

.assistant .message-content {
  background-color: #f0f0f0;
  border-bottom-left-radius: 4px;
}

.high-risk .message-content {
  border: 1px solid #ff4d4f;
}

.message-text {
  line-height: 1.5;
  white-space: pre-wrap;
}

/* Markdown 样式 */
.message-text :deep(pre) {
  background-color: #f5f5f5;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  margin: 8px 0;
}

.message-text :deep(code) {
  background-color: #f0f0f0;
  padding: 2px 4px;
  border-radius: 3px;
  font-family: 'Courier New', Courier, monospace;
  font-size: 0.9em;
}

.message-text :deep(pre code) {
  background-color: transparent;
  padding: 0;
}

.message-text :deep(ul),
.message-text :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}

.message-text :deep(li) {
  margin: 4px 0;
}

.message-text :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}

.message-text :deep(th),
.message-text :deep(td) {
  border: 1px solid #ddd;
  padding: 4px 8px;
  text-align: left;
}

.message-text :deep(th) {
  background-color: #f0f0f0;
  font-weight: bold;
}

/* 风险警告 */
.risk-warning {
  margin-top: 8px;
  padding: 8px;
  background-color: #fff1f0;
  border: 1px solid #ffccc7;
  border-radius: 4px;
  color: #cf1322;
  font-size: 0.9em;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 元信息 */
.message-meta {
  margin-top: 8px;
  font-size: 0.8em;
  color: #999;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.trace-id {
  background-color: #f5f5f5;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

/* 动画 */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .message-content {
    max-width: 85%;
  }
  
  .trace-id {
    max-width: 150px;
  }
}
</style>