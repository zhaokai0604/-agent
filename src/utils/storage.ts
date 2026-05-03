// LocalStorage 工具类
const STORAGE_KEY = 'chat_history';
const MAX_MESSAGES = 50;

/**
 * 保存聊天历史到 LocalStorage
 */
export const saveChatHistory = (messages: any[]): void => {
  try {
    // 只保留最近 MAX_MESSAGES 条消息
    const recentMessages = messages.slice(-MAX_MESSAGES);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(recentMessages));
  } catch (error) {
    console.error('保存聊天历史失败:', error);
  }
};

/**
 * 从 LocalStorage 加载聊天历史
 */
export const loadChatHistory = (): any[] => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? JSON.parse(stored) : [];
  } catch (error) {
    console.error('加载聊天历史失败:', error);
    return [];
  }
};

/**
 * 清空聊天历史
 */
export const clearChatHistory = (): void => {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch (error) {
    console.error('清空聊天历史失败:', error);
  }
};