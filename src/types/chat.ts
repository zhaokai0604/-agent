// 聊天相关类型定义
export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH';
  traceId?: string;
}

export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  reply: string;
  traceId: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  success: boolean;
}