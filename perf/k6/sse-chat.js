import http from 'k6/http'
import { Trend, Counter, Rate } from 'k6/metrics'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'
import { buildBaseUrl, getNumber, getProp } from './lib/config.js'
import { loginSession } from './lib/auth.js'

const prompts = new SharedArray('prompts', () =>
  open('perf/data/prompts.csv')
    .trim()
    .split(/\r?\n/)
    .slice(1)
    .map(line => {
      const parts = line.split(',')
      return {
        scenario: parts[0],
        prompt: parts.slice(1).join(',').trim()
      }
    })
)

export const firstChunkTrend = new Trend('sse_first_chunk_ms')
export const fullStreamTrend = new Trend('sse_full_stream_ms')
export const timeoutRate = new Rate('sse_timeout_rate')
export const streamErrorRate = new Rate('sse_error_rate')
export const chunkCounter = new Counter('sse_chunk_count')

const vus = Number(__ENV.VUS || getNumber('ai.stream.users', 10))
const duration = __ENV.DURATION || `${getNumber('duration.sec', 600)}s`
const pauseSec = Number(__ENV.PAUSE_SEC || 1)
const scenarioName = __ENV.SSE_SCENARIO || 'assistant'

export const options = {
  scenarios: {
    sse_streams: {
      executor: 'constant-vus',
      vus,
      duration
    }
  },
  thresholds: {
    sse_first_chunk_ms: ['p(95)<5000'],
    sse_full_stream_ms: ['p(95)<20000'],
    sse_error_rate: ['rate<0.01']
  }
}

function buildRequestPayload(prompt) {
  return JSON.stringify({
    message: prompt,
    history: [],
    useToolAgent: getProp('assistant.useToolAgent', 'true') === 'true',
    confirmRemediation: getProp('assistant.confirmRemediation', 'false') === 'true'
  })
}

function resolveUrl(base, taskId) {
  switch (scenarioName) {
    case 'assistant':
      return `${buildBaseUrl('management')}/api/assistant/chat/stream`
    case 'diagnose':
      if (!taskId) {
        throw new Error('SSE_SCENARIO=diagnose requires TASK_ID')
      }
      return `${base}/log/diagnose/stream/${taskId}`
    default:
      return `${buildBaseUrl('management')}/api/assistant/chat/stream`
  }
}

export default function () {
  const auth = loginSession()
  const base = buildBaseUrl('business')
  const prompt = prompts[(__VU + __ITER) % prompts.length].prompt
  const taskId = __ENV.TASK_ID || getProp('task.id', '')
  const url = resolveUrl(base, taskId)
  const body = scenarioName === 'diagnose' ? null : buildRequestPayload(prompt)
  const startedAt = Date.now()

  const res = http.request(scenarioName === 'diagnose' ? 'GET' : 'POST', url, body, {
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: getProp('allowed.origin', 'http://127.0.0.1:5173'),
      Cookie: auth.cookieHeader
    },
    timeout: `${getNumber('sse.timeout.ms', 300000)}ms`,
    redirects: 0
  })

  const ok = check(res, {
    'sse status is 200': r => r.status === 200,
    'sse body has content': r => !!r.body && r.body.length > 0
  })
  streamErrorRate.add(!ok)

  if (!ok) {
    sleep(pauseSec)
    return
  }

  const bodyText = res.body || ''
  const lines = bodyText.split(/\r?\n/)
  let firstChunkAt = 0
  let dataLines = 0
  for (const line of lines) {
    if (line.startsWith('data:') || (!line.startsWith(':') && line.trim() !== '')) {
      dataLines += 1
      if (!firstChunkAt) {
        firstChunkAt = Date.now()
      }
    }
  }
  chunkCounter.add(dataLines)
  if (!firstChunkAt) {
    timeoutRate.add(true)
  } else {
    timeoutRate.add(false)
    firstChunkTrend.add(firstChunkAt - startedAt)
  }
  fullStreamTrend.add(Date.now() - startedAt)
  sleep(pauseSec)
}
