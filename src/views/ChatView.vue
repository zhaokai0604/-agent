<template>
  <div class="chat-view">
    <!-- 头部 -->
    <div class="chat-header">
      <h1>智能运维 Agent</h1>
      <div class="header-info">
        <span class="api-url">API: {{ API_BASE_URL }}</span>
        <el-button size="small" @click="clearMessages" type="danger" plain>
          清空对话
        </el-button>
      </div>
    </div>
    
    <!-- 聊天窗口 -->
    <div class="chat-window" ref="chatWindowRef">
      <!-- 消息列表 -->
      <div class="message-list">
        <MessageBubble 
          v-for="message in messages" 
          :key="message.id" 
          :message="message"
        />
        
        <!-- 加载中 -->
        <div v-if="isLoading" class="loading-message">
          <el-icon class="is-loading"><component :is="Loading" /></el-icon>
          思考中...
        </div>
      </div>
      
      <!-- 快捷操作 -->
      <QuickActions 
        :quick-actions="QUICK_ACTIONS" 
        @action="quickAction"
      />
      
      <!-- 输入区域 -->
      <div class="input-area">
        <el-input
          v-model="inputMessage"
          type="textarea"
          :rows="3"
          placeholder="请输入指令，例如：查看磁盘使用率"
          :disabled="isLoading"
          @keydown.ctrl.enter="handleSend"
          resize="none"
        />
        <div class="input-actions">
          <span v-if="currentTraceId" class="trace-id-display">
            TraceId: {{ currentTraceId }}
          </span>
          <el-button 
            type="primary" 
            @click="handleSend" 
            :disabled="isLoading || !inputMessage.trim()"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue';
import { ElMessage, ElIcon, Loading } from 'element-plus';
import MessageBubble from '../components/chat/MessageBubble.vue';
import QuickActions from '../components/chat/QuickActions.vue';
import { useChat } from '../composables/useChat';

// 聊天逻辑
const { 
  messages, 
  isLoading, 
  error, 
  currentTraceId, 
  sendMessage, 
  clearMessages, 
  quickAction, 
  QUICK_ACTIONS, 
  API_BASE_URL 
} = useChat();

// 输入消息
const inputMessage = ref('');

// 聊天窗口引用
const chatWindowRef = ref<HTMLElement | null>(null);

// 滚动到底部
const scrollToBottom = () => {
  nextTick(() => {
    if (chatWindowRef.value) {
      const messageList = chatWindowRef.value.querySelector('.message-list');
      if (messageList) {
        messageList.scrollTop = messageList.scrollHeight;
      }
    }
  });
};

// 处理发送
const handleSend = () => {
  if (inputMessage.value.trim()) {
    sendMessage(inputMessage.value);
    inputMessage.value = '';
    scrollToBottom();
  }
};

// 监听错误
if (error.value) {
  ElMessage.error(error.value);
}

// 初始滚动到底部
scrollToBottom();
</script>

<style scoped>
.chat-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #f5f5f5;
}

/* 头部 */
.chat-header {
  background-color: #1890ff;
  color: white;
  padding: 16px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.chat-header h1 {
  margin: 0;
  font-size: 1.2em;
  font-weight: 500;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.api-url {
  font-size: 0.85em;
  opacity: 0.8;
  background-color: rgba(255, 255, 255, 0.2);
  padding: 4px 8px;
  border-radius: 4px;
}

/* 聊天窗口 */
.chat-window {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}

/* 消息列表 */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  margin-bottom: 16px;
  min-height: 400px;
}

/* 加载消息 */
.loading-message {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  color: #1890ff;
  gap: 8px;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
  100% {
    opacity: 0.6;
  }
}

/* 输入区域 */
.input-area {
  background-color: white;
  padding: 16px;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.trace-id-display {
  font-size: 0.8em;
  color: #666;
  background-color: #f0f0f0;
  padding: 4px 8px;
  border-radius: 4px;
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 300px;
}

/* 滚动条样式 */
.message-list::-webkit-scrollbar {
  width: 6px;
}

.message-list::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.message-list::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.message-list::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 响应式 */
@media (max-width: 768px) {
  .chat-window {
    padding: 12px;
  }
  
  .chat-header {
    padding: 12px 16px;
  }
  
  .chat-header h1 {
    font-size: 1em;
  }
  
  .api-url {
    display: none;
  }
  
  .trace-id-display {
    max-width: 200px;
  }
}
</style>