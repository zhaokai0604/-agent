export function canPublishSkillRelease(preview, currentFingerprint, reason) {
  return Boolean(preview?.evaluation?.passed === true
    && preview.expectedFingerprint === currentFingerprint
    && /^[a-f0-9]{64}$/.test(preview.candidateFingerprint || '')
    && Array.isArray(preview.changes) && preview.changes.length > 0
    && typeof reason === 'string' && reason.trim().length > 0 && reason.length <= 500)
}

export function skillReleasePayload(preview, currentFingerprint, reason) {
  if (!canPublishSkillRelease(preview, currentFingerprint, reason)) throw new Error('请重新预览并填写发布原因')
  return { expectedFingerprint: preview.expectedFingerprint, candidateFingerprint: preview.candidateFingerprint, reason: reason.trim() }
}
