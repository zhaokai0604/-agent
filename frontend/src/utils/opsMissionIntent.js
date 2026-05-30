/** 用户明确要求不走工具 / 仅文字 */
export function userDeclinesTools(text) {
  const t = (text || '').trim()
  if (!t) return false
  return /不调用工具|不要调用工具|不用工具|别用工具|禁止工具|不要工具|不要执行工具|仅文字|只要文字|纯对话|不要跑命令|不调工具|不用mcp/i.test(
    t
  )
}

/** 一句话自主运维 / 全面检查 → 走后端编排器（感知→诊断→处置→验证） */
export function isOpsMissionIntent(text) {
  const t = (text || '').trim()
  if (!t || userDeclinesTools(t)) return false
  return (
    /自动运维|自主运维|一键运维|智能运维|全自动|察觉.*问题|发现.*解决/i.test(t) ||
    /(检查|排查|诊断|体检|巡检|看看).*(系统|服务器|主机|本机|机器|环境)/i.test(t) ||
    /(系统|服务器|主机|本机).*(检查|排查|诊断|健康|状态|怎么样|如何)/i.test(t) ||
    /健康检查|运维检查|全面检查|帮我运维|系统状态/i.test(t) ||
    /检查系统|排查问题|发现问题.*(修复|处理|建议)/i.test(t)
  )
}

/** 用户在看完计划后确认落地写操作 */
export function userConfirmedRemediation(text) {
  const t = (text || '').trim()
  return /确认执行|执行修复|按预览执行|开始清理|执行删除|真实删除|立即执行修复|执行处置/i.test(t)
}

/** 偏解释、追问 → 纯 LLM；运维任务 → 编排或 Tool Agent */
export function shouldUseNaturalLanguageAssistant(text) {
  const t = (text || '').trim()
  if (userDeclinesTools(t)) return true
  if (isOpsMissionIntent(t)) return true
  if (userConfirmedRemediation(t)) return true
  if (t.length > 140) return true
  if (/[？?]|为什么|怎么|如何|含义|说明一下|是什么意思|总结|讨论|建议|是否|能否|有没有/.test(t)) return true
  if (/刚才|之前|上面|这个结果|那条|接着|还有/.test(t)) return true
  return false
}
