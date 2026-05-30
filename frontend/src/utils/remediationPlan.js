/**
 * 从助手 Markdown 回复中解析「处置计划」小节，供产品化卡片展示。
 */
export function parseRemediationPlan (markdown) {
  if (!markdown || typeof markdown !== 'string') return null
  const match = markdown.match(/##\s*处置计划\s*\n([\s\S]*?)(?=\n##\s+|\n---\s*\n\*\*数据依据|$)/i)
  if (!match) return null
  const body = match[1].trim()
  if (!body) return null
  const lines = body.split('\n').map((l) => l.trim()).filter(Boolean)
  const items = lines
    .filter((l) => /^[-*•]\s+/.test(l) || /^\d+[.)]\s+/.test(l))
    .map((l) => l.replace(/^[-*•]\s+/, '').replace(/^\d+[.)]\s+/, '').trim())
  const hasPreview = /预览|dry[- ]?run|待确认|不会真实|仅预览/i.test(body)
  return {
    title: '处置计划',
    body,
    items: items.length ? items : [body.slice(0, 280)],
    previewOnly: hasPreview
  }
}

/** 判断助手回复是否包含可确认的处置计划 */
export function hasActionableRemediationPlan (markdown) {
  const plan = parseRemediationPlan(markdown)
  if (!plan) return false
  return plan.previewOnly || /清理|删除|重启|修复|执行|remediat/i.test(plan.body)
}
