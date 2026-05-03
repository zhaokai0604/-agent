/**
 * MCP HTTP 返回：外层常有 success:true，真正工具结果是 data 里的 ToolResult JSON 字符串。
 * 统一解析后得到真实的 success / data / duration，供运维对话与 MCP 演示页展示。
 */

function tryParseJsonDeep(s) {
  if (typeof s !== 'string') return s
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}

export function normalizeMcpToolResponse(raw) {
  if (!raw || typeof raw !== 'object') {
    return { success: false, data: null, duration: 0 }
  }
  let success = raw.success === true
  let data = raw.data
  let duration = Number(raw.duration) || 0

  if (typeof data === 'string') {
    try {
      const inner = JSON.parse(data)
      if (inner && typeof inner === 'object' && typeof inner.success === 'boolean') {
        success = inner.success === true
        if (Number.isFinite(Number(inner.durationMs))) {
          duration = Number(inner.durationMs)
        }
        if (inner.success && inner.data != null) {
          const payload = inner.data
          data = typeof payload === 'string' ? tryParseJsonDeep(payload) : payload
        } else {
          data = { success: false, error: inner.error || inner.message || '工具返回失败' }
        }
      }
    } catch {
      /* 保留原始字符串 */
    }
  }
  return { ...raw, success, data, duration }
}
