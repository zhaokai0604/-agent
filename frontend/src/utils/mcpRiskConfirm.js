/**
 * MCP 安全门「需二次确认」时的可读文案（与后端 riskScore / riskDimensions 对齐）。
 */

export function buildMcpRiskConfirmMessage(result) {
  if (!result || typeof result !== 'object') {
    return '该操作需在前端二次确认后才会真正下发到本机工具（等同口令「确认执行」）。'
  }
  const parts = []
  if (result.message) parts.push(String(result.message))
  if (result.riskScore != null && Number.isFinite(Number(result.riskScore))) {
    parts.push(`Agentic 风险分：${result.riskScore} / 10`)
  }
  if (result.riskDimensions && typeof result.riskDimensions === 'object') {
    try {
      parts.push('评分明细：' + JSON.stringify(result.riskDimensions))
    } catch {
      parts.push('评分明细：(无法序列化)')
    }
  }
  if (result.riskExplanation) parts.push(String(result.riskExplanation))
  if (parts.length === 0) {
    return '该操作存在风险，需在界面二次确认后执行（等同口令「确认执行」）。'
  }
  return parts.join('\n\n')
}
