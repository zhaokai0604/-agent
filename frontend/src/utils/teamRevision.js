/** Bind human input to the exact run revision that was displayed while editing. */
export function canSubmitTeamDraft(draftRevision, run) {
  return Number.isInteger(draftRevision) && Number.isInteger(run?.revision) && draftRevision === run.revision
}

/** Confirmation and reconciliation acknowledgements must not carry across a changed plan. */
export function teamApprovalChanged(before, after) {
  return Boolean(before && after && (before.runId !== after.runId || before.revision !== after.revision || before.planHash !== after.planHash))
}

export function teamPollRetryDelay(status, failures) {
  if ([401, 403, 404].includes(Number(status))) return null
  return Math.min(30000, 3000 * 2 ** Math.min(Math.max(failures - 1, 0), 4))
}
