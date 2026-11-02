import test from 'node:test'
import assert from 'node:assert/strict'
import { canPublishSkillRelease, skillReleasePayload } from '../src/utils/skillReleaseApproval.js'

test('release approval binds the exact preview and requires passing fixtures and a reason', () => {
  const current = 'a'.repeat(64)
  const preview = { expectedFingerprint: current, candidateFingerprint: 'b'.repeat(64), evaluation: { passed: true }, changes: [{ skill: 'cpu-pressure' }] }
  assert.equal(canPublishSkillRelease(preview, current, 'reviewed'), true)
  assert.deepEqual(skillReleasePayload(preview, current, ' reviewed '), { expectedFingerprint: current, candidateFingerprint: 'b'.repeat(64), reason: 'reviewed' })
  for (const invalid of [null, { ...preview, evaluation: { passed: false } }, { ...preview, evaluation: {} },
    { ...preview, changes: [] }, { ...preview, candidateFingerprint: 'bad' }, { ...preview, expectedFingerprint: 'c'.repeat(64) }]) {
    assert.equal(canPublishSkillRelease(invalid, current, 'reviewed'), false)
    assert.throws(() => skillReleasePayload(invalid, current, 'reviewed'))
  }
  for (const reason of ['', ' ', 'x'.repeat(501)]) assert.equal(canPublishSkillRelease(preview, current, reason), false)
})
