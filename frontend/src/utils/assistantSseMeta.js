export const ASSISTANT_META_PREFIX = 'ASSISTANT_META:'

/** 解析助手 SSE：首帧元数据不入正文 */
export function handleAssistantSsePayload (payload, { onMeta, onContent }) {
  if (!payload) return
  if (payload.startsWith(ASSISTANT_META_PREFIX)) {
    try {
      const meta = JSON.parse(payload.slice(ASSISTANT_META_PREFIX.length))
      onMeta?.(meta)
    } catch {
      // ignore malformed meta
    }
    return
  }
  onContent?.(payload)
}
