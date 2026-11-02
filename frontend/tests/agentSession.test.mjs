import test from 'node:test'
import assert from 'node:assert/strict'
const cache = new Map()
globalThis.localStorage = {
  getItem: key => cache.get(key) ?? null,
  setItem: (key, value) => cache.set(key, value),
  removeItem: key => cache.delete(key)
}
const { useAgentSession, setAgentSessionOwner } = await import('../src/composables/useAgentSession.js')

test('accounts and logout isolate messages and pending capabilities', () => {
  cache.set('threshcore-agent-sessions-v2', JSON.stringify({sessions: [{id: 'legacy', messages: [{role: 'user', content: 'unowned'}]}]}))
  const state = useAgentSession()
  setAgentSessionOwner('alice')
  assert.equal(state.messages.value.length, 0)
  state.messages.value.push({role: 'assistant', content: 'alice secret', pendingConfirmation: {capabilityToken: 'alice-token'}})
  setAgentSessionOwner('bob')
  assert.equal(state.messages.value.length, 0)
  state.messages.value.push({role: 'user', content: 'bob secret'})
  setAgentSessionOwner(null)
  assert.equal(state.messages.value.length, 0)
  setAgentSessionOwner('alice')
  assert.equal(state.messages.value[0].content, 'alice secret')
  assert.equal(state.messages.value[0].pendingConfirmation.capabilityToken, 'alice-token')
  setAgentSessionOwner('bob')
  assert.equal(state.messages.value[0].content, 'bob secret')
  setAgentSessionOwner(null)
})
