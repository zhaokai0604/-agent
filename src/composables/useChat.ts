// 聊天业务逻辑组合式函数
import { ref, computed, onMounted, onUnmounted } from 'vue';
import axios from 'axios';
import type { Message, ChatRequest, ChatResponse } from '../types/chat';
import { saveChatHistory, loadChatHistory, clearChatHistory } from '../utils/storage';

// API 基础地址
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

// 快捷操作指令
const QUICK_ACTIONS = [
  { id: 'disk', label: '查看磁盘使用率' },
  { id: 'load', label: '检查系统负载' },
  { id: 'process', label: '列出高CPU进程' },
  { id: 'clean', label: '清理临时文件(dry-run)' },
  { id: 'network', label: '网络诊断' },
  { id: 'logs', label: '分析系统日志' }
];

export function useChat() {
  // 响应式状态
  const messages = ref<Message[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const currentTraceId = ref<string | null>(null);
  
  // 取消请求控制器
  let abortController: AbortController | null = null;
  
  // 计算属性：最新的助手消息
  const latestAssistantMessage = computed(() => {
    return messages.value.findLast(msg => msg.role === 'assistant');
  });
  
  // 加载历史消息
  const loadHistory = () => {
    const history = loadChatHistory();
    messages.value = history;
  };
  
  // 保存消息到本地存储
  const saveMessages = () => {
    saveChatHistory(messages.value);
  };
  
  // 发送消息
  const sendMessage = async (content: string) => {
    if (!content.trim()) return;
    
    // 取消之前的请求
    if (abortController) {
      abortController.abort();
    }
    
    // 创建新的取消控制器
    abortController = new AbortController();
    
    // 添加用户消息
    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: content.trim(),
      timestamp: Date.now()
    };
    messages.value.push(userMessage);
    saveMessages();
    
    // 发送请求
    isLoading.value = true;
    error.value = null;
    
    try {
      const response = await axios.post<ChatResponse>(
        `${API_BASE_URL}/chat`,
        { message: content.trim() } as ChatRequest,
        {
          signal: abortController.signal,
          headers: {
            'Content-Type': 'application/json'
          }
        }
      );
      
      const { reply, traceId, riskLevel, success } = response.data;
      
      if (success) {
        // 添加助手消息
        const assistantMessage: Message = {
          id: (Date.now() + 1).toString(),
          role: 'assistant',
          content: reply,
          timestamp: Date.now(),
          riskLevel,
          traceId
        };
        messages.value.push(assistantMessage);
        currentTraceId.value = traceId;
      } else {
        // 处理后端错误
        const errorMessage: Message = {
          id: (Date.now() + 1).toString(),
          role: 'assistant',
          content: '抱歉，处理请求时发生错误，请稍后重试。',
          timestamp: Date.now(),
          traceId
        };
        messages.value.push(errorMessage);
        error.value = '后端处理失败';
      }
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        // 网络错误
        const errorMessage: Message = {
          id: (Date.now() + 1).toString(),
          role: 'assistant',
          content: '网络错误，请检查后端服务是否正常运行。',
          timestamp: Date.now()
        };
        messages.value.push(errorMessage);
        error.value = err.message || '网络错误';
      }
    } finally {
      isLoading.value = false;
      saveMessages();
    }
  };
  
  // 清空对话
  const clearMessages = () => {
    messages.value = [];
    clearChatHistory();
    currentTraceId.value = null;
  };
  
  // 快捷操作
  const quickAction = (actionId: string) => {
    const action = QUICK_ACTIONS.find(a => a.id === actionId);
    if (action) {
      sendMessage(action.label);
    }
  };
  
  // 组件卸载时取消请求
  onUnmounted(() => {
    if (abortController) {
      abortController.abort();
    }
  });
  
  // 组件挂载时加载历史
  onMounted(() => {
    loadHistory();
  });
  
  return {
    messages,
    isLoading,
    error,
    currentTraceId,
    latestAssistantMessage,
    sendMessage,
    clearMessages,
    quickAction,
    QUICK_ACTIONS,
    API_BASE_URL
  };
}