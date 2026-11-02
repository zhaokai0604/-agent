import test from 'node:test'
import assert from 'node:assert/strict'
import { canSubmitTeamDraft, teamApprovalChanged, teamPollRetryDelay } from '../src/utils/teamRevision.js'

test('old drafts never acquire a newer revision without an explicit reload', () => {
  assert.equal(canSubmitTeamDraft(4, { revision: 4 }), true)
  assert.equal(canSubmitTeamDraft(4, { revision: 5 }), false)
  assert.equal(canSubmitTeamDraft(null, { revision: 4 }), false)
  assert.equal(canSubmitTeamDraft(4, null), false)
})

test('temporary disconnect backs off, while access failures require user action', () => {
  assert.equal(teamPollRetryDelay(undefined, 1), 3000)
  assert.equal(teamPollRetryDelay(500, 3), 12000)
  assert.equal(teamPollRetryDelay(429, 10), 30000)
  for (const status of [401, 403, 404]) assert.equal(teamPollRetryDelay(status, 1), null)
})

test('confirmation is reset when the run, revision or plan fingerprint changes', () => {
  const base = { runId: 'a', revision: 4, planHash: 'one' }
  assert.equal(teamApprovalChanged(base, { ...base }), false)
  assert.equal(teamApprovalChanged(base, { ...base, revision: 5 }), true)
  assert.equal(teamApprovalChanged(base, { ...base, planHash: 'two' }), true)
  assert.equal(teamApprovalChanged(base, { ...base, runId: 'b' }), true)
})
