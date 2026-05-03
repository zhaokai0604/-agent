/**
 * 解析 Spring SseEmitter / 标准 text/event-stream，只拼接 data 行中的正文（去掉 data: 前缀）。
 * 忽略注释行、心跳与 [DONE]。
 */
export function appendSsePayloadFromChunk(buffer, chunkText, onPayload) {
  let buf = buffer + chunkText
  const lines = buf.split('\n')
  const rest = lines.pop()
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (!line || line.startsWith(':')) continue
    if (!line.startsWith('data:')) continue
    let payload = line.slice(5).replace(/^\s/, '')
    if (payload === '[DONE]') continue
    if (payload) onPayload(payload)
  }
  return rest ?? ''
}

export function flushSseBuffer(buffer, onPayload) {
  if (!buffer || !buffer.trim()) return ''
  const line = buffer.trimEnd()
  if (line.startsWith('data:')) {
    let payload = line.slice(5).replace(/^\s/, '')
    if (payload && payload !== '[DONE]') onPayload(payload)
  }
  return ''
}
