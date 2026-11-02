import test from 'node:test'
import assert from 'node:assert/strict'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../src/utils/sseStream.js'

function parse(chunks) {
  let buffer = ''; const payloads = []
  for (const chunk of chunks) buffer = appendSsePayloadFromChunk(buffer, chunk, value => payloads.push(value))
  flushSseBuffer(buffer, value => payloads.push(value))
  return payloads
}

test('CRLF split boundaries and trailing spaces preserve payloads', () => {
  const input = ': heartbeat\r\ndata: hello  \r\ndata: world \r\n\r\ndata: [DONE]\r\n\r\n'
  const expected = ['hello  \nworld ']
  for (let i = 0; i <= input.length; i++) {
    assert.deepEqual(parse([input.slice(0, i), input.slice(i)]), expected, `split at ${i}`)
  }
  assert.deepEqual(parse([...input]), expected)
})

test('empty data lines, unicode and unfinished last event are retained', () => {
  assert.deepEqual(parse(['data: 中 文  \ndata:\n\n', 'data: end  ']), ['中 文  \n', 'end  '])
})
