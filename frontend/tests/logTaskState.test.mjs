import test from 'node:test'
import assert from 'node:assert/strict'
import { logTaskStatusText, canRecoverLogTask, shouldPollLogTask } from '../src/utils/logTaskState.js'

test('recovery requires both interrupted state and retained recoverable input', () => {
  assert.equal(canRecoverLogTask({ status: 'RECOVERY_REQUIRED', runtime: { recoverable: true } }), true)
  for (const task of [null, { status: 'RECOVERY_REQUIRED' },
    { status: 'RECOVERY_REQUIRED', runtime: { recoverable: false } },
    { status: 'COMPLETED', runtime: { recoverable: true } }]) assert.equal(canRecoverLogTask(task), false)
})

test('interrupted and unknown tasks are never treated as completed or running', () => {
  assert.equal(logTaskStatusText('RECOVERY_REQUIRED'), '中断待恢复')
  assert.equal(logTaskStatusText('CANCELLED'), '已取消')
  assert.equal(logTaskStatusText('NEW_STATE'), 'NEW_STATE')
  for (const status of ['RECOVERY_REQUIRED', 'COMPLETED', 'PAUSED', 'FAILED', 'CANCELLED', 'NEW_STATE'])
    assert.equal(shouldPollLogTask(status), false)
  assert.equal(shouldPollLogTask('PENDING'), true)
  assert.equal(shouldPollLogTask('PROCESSING'), true)
})
