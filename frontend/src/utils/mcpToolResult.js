/**
 * MCP HTTP 返回：外层常有 success:true，真正工具结果是 data 里的 ToolResult JSON 字符串。
 * 统一解析后得到真实的 success / data / duration，供运维对话与工具箱展示。
 */

function tryParseJsonDeep(s) {
  if (typeof s !== 'string') return s
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}

function normalizeDurationMs(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return 0
  const now = Date.now()
  const earliestEpochMs = 946684800000 // 2000-01-01
  if (n >= earliestEpochMs && n <= now + 60000) {
    return Math.max(0, now - n)
  }
  return n
}

export function normalizeMcpToolResponse(raw) {
  if (!raw || typeof raw !== 'object') {
    return { success: false, data: null, duration: 0 }
  }
  let success = raw.success === true
  let data = raw.data
  let duration = normalizeDurationMs(raw.duration)

  if (typeof data === 'string') {
    try {
      const inner = JSON.parse(data)
      if (inner && typeof inner === 'object' && typeof inner.success === 'boolean') {
        success = inner.success === true
        if (inner.durationMs != null) {
          duration = normalizeDurationMs(inner.durationMs)
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
  const riskScore = raw.riskScore != null ? Number(raw.riskScore) : undefined
  const verifyOutcome = raw.verifyOutcome || raw.verify?.verifyOutcome || ''
  const verified = raw.verified === true || raw.verify?.verified === true
  const verifyBlocked = raw.verifyBlocked === true
  const remediationClosed = raw.remediationClosed === true
  if (verifyBlocked) {
    success = false
  }
  return {
    ...raw,
    success,
    data,
    duration,
    statusCode: raw.statusCode,
    traceId: raw.traceId ?? raw.trace_id,
    needConfirm: raw.needConfirm === true,
    confirmationId: raw.confirmationId ?? '',
    capabilityToken: raw.capabilityToken ?? '',
    effectFingerprint: raw.effectFingerprint ?? '',
    toolEffect: raw.toolEffect && typeof raw.toolEffect === 'object' ? raw.toolEffect : null,
    riskBudget: raw.riskBudget && typeof raw.riskBudget === 'object' ? raw.riskBudget : null,
    expiresAtMs: raw.expiresAtMs ?? null,
    writeMismatch: raw.writeMismatch === true,
    writeMismatchMessage: raw.writeMismatchMessage,
    evidenceIncomplete: raw.evidenceIncomplete === true || verifyOutcome === 'EVIDENCE_INCOMPLETE',
    evidenceMessage: raw.evidenceMessage || (verifyOutcome === 'EVIDENCE_INCOMPLETE' ? raw.message || raw.error : undefined),
    evidenceContractId: raw.evidenceContractId,
    evidenceMissingFields: Array.isArray(raw.evidenceMissingFields) ? raw.evidenceMissingFields : [],
    riskScore: Number.isFinite(riskScore) ? riskScore : raw.riskScore,
    riskDimensions: raw.riskDimensions,
    riskExplanation: raw.riskExplanation,
    securityCode: raw.securityCode,
    warnings: Array.isArray(raw.warnings) ? raw.warnings : [],
    platformSupport: raw.platformSupport && typeof raw.platformSupport === 'object'
      ? raw.platformSupport
      : null,
    verifyOutcome,
    verified,
    verifyBlocked,
    remediationClosed,
    expectedEffect: raw.expectedEffect || raw.verify?.expectedEffect || raw.remediationContract?.expectedEffect || null,
    recover: raw.recover || raw.verify?.recover || raw.remediationContract?.recover || null,
    approval: raw.approval || raw.remediationContract?.approval || null,
    irreversibleWarning: raw.irreversibleWarning || '',
    verifyMessage: raw.verify?.message || (verifyBlocked ? raw.error : '') || ''
  }
}
