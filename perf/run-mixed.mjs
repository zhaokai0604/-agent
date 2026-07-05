import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const CONFIG_PATH = process.env.PERF_CONFIG || path.resolve('perf/config/env-dev.properties')
const RESULT_FILE = path.resolve(process.env.MIXED_RESULT_FILE || 'perf/data/results/mixed-result.json')

function parseProperties(text) {
  const map = new Map()
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) {
      continue
    }
    const index = line.indexOf('=')
    if (index <= 0) {
      continue
    }
    map.set(line.slice(0, index).trim(), line.slice(index + 1).trim())
  }
  return map
}

async function loadConfig() {
  return parseProperties(await fs.readFile(CONFIG_PATH, 'utf8'))
}

function baseUrl(config, surface) {
  const protocol = config.get('protocol') || 'http'
  const host = config.get('host') || '127.0.0.1'
  const port = surface === 'management'
    ? (config.get('management.port') || '8089')
    : (config.get('business.port') || '8088')
  const contextPath = config.get('context.path') || '/award-log'
  return `${protocol}://${host}:${port}${contextPath}`
}

function percentile(sorted, p) {
  if (!sorted.length) return null
  const rank = Math.ceil((p / 100) * sorted.length) - 1
  return sorted[Math.min(Math.max(rank, 0), sorted.length - 1)]
}

function summarizeLatencies(values) {
  const sorted = [...values].sort((a, b) => a - b)
  const total = sorted.reduce((sum, item) => sum + item, 0)
  return {
    count: sorted.length,
    avg: sorted.length ? Math.round(total / sorted.length) : null,
    min: sorted[0] ?? null,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted.at(-1) ?? null
  }
}

async function writeJson(fileName, data) {
  await fs.mkdir(path.dirname(fileName), { recursive: true })
  await fs.writeFile(fileName, JSON.stringify(data, null, 2), 'utf8')
  return fileName
}

async function httpText(url, options = {}) {
  const startedAt = performance.now()
  const response = await fetch(url, options)
  const elapsedMs = Math.round(performance.now() - startedAt)
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  return {
    ok: response.ok,
    status: response.status,
    elapsedMs,
    json,
    text
  }
}

async function registerAndLogin(config) {
  const managementBase = baseUrl(config, 'management')
  const username = `mix_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const password = 'PerfRunner#2026'
  const email = `${username}@example.com`

  await httpText(`${managementBase}/admin/user/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password, email })
  })

  const response = await fetch(`${managementBase}/admin/user/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password })
  })
  const text = await response.text()
  const cookieMap = new Map()
  for (const header of response.headers.getSetCookie?.() || []) {
    const [pair] = header.split(';')
    const index = pair.indexOf('=')
    if (index > 0) {
      cookieMap.set(pair.slice(0, index), pair.slice(index + 1))
    }
  }
  return {
    login: text ? JSON.parse(text) : null,
    cookieHeader: [...cookieMap.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  }
}

async function streamFirstChunk(url, cookieHeader, body) {
  const startedAt = performance.now()
  const response = await fetch(url, {
    method: body ? 'POST' : 'GET',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: 'http://127.0.0.1:5173',
      Cookie: cookieHeader
    },
    body: body ? JSON.stringify(body) : undefined
  })
  if (!response.ok || !response.body) {
    return {
      ok: false,
      status: response.status,
      elapsedMs: Math.round(performance.now() - startedAt),
      firstChunkMs: null
    }
  }
  const reader = response.body.getReader()
  let firstChunkMs = null
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (value?.length) {
        firstChunkMs = Math.round(performance.now() - startedAt)
        break
      }
    }
  } finally {
    try {
      await reader.cancel()
    } catch {
    }
  }
  return {
    ok: response.ok,
    status: response.status,
    elapsedMs: Math.round(performance.now() - startedAt),
    firstChunkMs
  }
}

async function uploadAndWait(filePath, businessBase, cookieHeader, config) {
  const form = new FormData()
  const blob = new Blob([await fs.readFile(filePath)], { type: 'text/plain' })
  form.set('file', blob, path.basename(filePath))
  const uploadStartedAt = performance.now()
  const uploadResp = await fetch(`${businessBase}/log/upload`, {
    method: 'POST',
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      Cookie: cookieHeader
    },
    body: form
  })
  const uploadElapsedMs = Math.round(performance.now() - uploadStartedAt)
  const uploadText = await uploadResp.text()
  let uploadJson = null
  try {
    uploadJson = uploadText ? JSON.parse(uploadText) : null
  } catch {
    uploadJson = null
  }

  const taskId = uploadJson?.data
  const pollIntervalMs = Number(config.get('poll.interval.ms') || '3000')
  const timeoutMs = Number(process.env.MIXED_TASK_TIMEOUT_MS || config.get('task.complete.timeout.ms') || '900000')
  const deadline = Date.now() + timeoutMs
  let finalStatus = null
  let pollCount = 0

  while (taskId && Date.now() < deadline) {
    const pollResp = await httpText(`${businessBase}/log/task/${taskId}`, {
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Cookie: cookieHeader
      }
    })
    pollCount += 1
    finalStatus = pollResp.json?.data?.status || null
    if (finalStatus === 'COMPLETED' || finalStatus === 'FAILED' || finalStatus === 'CANCELLED') {
      break
    }
    await new Promise(resolve => setTimeout(resolve, pollIntervalMs))
  }

  return {
    ok: uploadResp.ok && finalStatus === 'COMPLETED',
    status: uploadResp.status,
    elapsedMs: uploadElapsedMs,
    endToEndMs: Math.round((Date.now() + 0) - (Date.now() - 0)), // placeholder overwritten below
    taskId,
    finalStatus,
    pollCount
  }
}

async function runScenario(name, count, concurrency, requestFactory) {
  const latencies = []
  const auxLatencies = []
  const statuses = new Map()
  let errors = 0
  let nextIndex = 0

  async function worker() {
    while (true) {
      const index = nextIndex++
      if (index >= count) return
      const result = await requestFactory(index)
      latencies.push(result.elapsedMs)
      if (typeof result.firstChunkMs === 'number') {
        auxLatencies.push(result.firstChunkMs)
      }
      statuses.set(String(result.status), (statuses.get(String(result.status)) || 0) + 1)
      if (!result.ok) errors += 1
    }
  }

  const startedAt = performance.now()
  await Promise.all(Array.from({ length: concurrency }, () => worker()))
  const totalMs = Math.round(performance.now() - startedAt)

  return {
    scenario: name,
    requests: count,
    concurrency,
    totalMs,
    throughputRps: Number((count / Math.max(totalMs / 1000, 0.001)).toFixed(2)),
    errorRate: Number((errors / Math.max(count, 1)).toFixed(4)),
    statuses: Object.fromEntries(statuses.entries()),
    latency: summarizeLatencies(latencies),
    firstChunkLatency: auxLatencies.length ? summarizeLatencies(auxLatencies) : null
  }
}

async function main() {
  const config = await loadConfig()
  const businessBase = baseUrl(config, 'business')
  const managementBase = baseUrl(config, 'management')
  const auth = await registerAndLogin(config)
  const cookieHeader = auth.cookieHeader
  const logFile = path.resolve(config.get('log.file.small') || 'perf/data/logs/log-small-mixed-1mb.log')

  const scenarios = [
    {
      name: 'A_upload_report',
      count: Number(process.env.MIXED_A_COUNT || 4),
      concurrency: Number(process.env.MIXED_A_CONCURRENCY || 2),
      run: async () => {
        const startedAt = performance.now()
        const form = new FormData()
        const blob = new Blob([await fs.readFile(logFile)], { type: 'text/plain' })
        form.set('file', blob, path.basename(logFile))

        const uploadResp = await fetch(`${businessBase}/log/upload`, {
          method: 'POST',
          headers: {
            'X-Requested-With': 'XMLHttpRequest',
            Cookie: cookieHeader
          },
          body: form
        })
        const uploadText = await uploadResp.text()
        let uploadJson = null
        try {
          uploadJson = uploadText ? JSON.parse(uploadText) : null
        } catch {
          uploadJson = null
        }
        const taskId = uploadJson?.data
        let finalStatus = null
        let status = uploadResp.status
        const deadline = Date.now() + Number(process.env.MIXED_TASK_TIMEOUT_MS || 300000)
        while (taskId && Date.now() < deadline) {
          const pollResp = await httpText(`${businessBase}/log/task/${taskId}`, {
            headers: {
              Accept: 'application/json',
              'X-Requested-With': 'XMLHttpRequest',
              Cookie: cookieHeader
            }
          })
          status = pollResp.status
          finalStatus = pollResp.json?.data?.status || null
          if (finalStatus === 'COMPLETED' || finalStatus === 'FAILED' || finalStatus === 'CANCELLED') {
            break
          }
          await new Promise(resolve => setTimeout(resolve, Number(config.get('poll.interval.ms') || '3000')))
        }
        if (taskId) {
          await httpText(`${businessBase}/log/report/${taskId}`, {
            headers: {
              Accept: 'application/json',
              'X-Requested-With': 'XMLHttpRequest',
              Cookie: cookieHeader
            }
          })
        }
        return {
          ok: uploadResp.ok && finalStatus === 'COMPLETED',
          status,
          elapsedMs: Math.round(performance.now() - startedAt)
        }
      }
    },
    {
      name: 'B_ai_chat_sse',
      count: Number(process.env.MIXED_B_COUNT || 6),
      concurrency: Number(process.env.MIXED_B_CONCURRENCY || 3),
      run: async (index) => streamFirstChunk(`${managementBase}/api/assistant/chat/stream`, cookieHeader, {
        message: `请总结告警处置建议 #${index + 1}`
      })
    },
    {
      name: 'D_query_dashboard',
      count: Number(process.env.MIXED_D_COUNT || 12),
      concurrency: Number(process.env.MIXED_D_CONCURRENCY || 4),
      run: async (index) => {
        const routes = [
          `${managementBase}/api/performance/baseline?dataType=cpu&days=7`,
          `${businessBase}/api/alarm/history/list?pageNum=1&pageSize=10`,
          `${businessBase}/api/v1/knowledge/search?query=${encodeURIComponent(`database timeout ${index}`)}&topK=5`,
          `${managementBase}/api/platform/info`
        ]
        const url = routes[index % routes.length]
        return httpText(url, {
          headers: {
            Accept: 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
            Cookie: cookieHeader
          }
        })
      }
    }
  ]

  const startedAt = new Date().toISOString()
  const results = []
  for (const scenario of scenarios) {
    results.push(await runScenario(scenario.name, scenario.count, scenario.concurrency, scenario.run))
  }

  const output = {
    startedAt,
    finishedAt: new Date().toISOString(),
    businessBase,
    managementBase,
    scenarios: results
  }
  await writeJson(RESULT_FILE, output)
  console.log(JSON.stringify(output, null, 2))
}

main().catch(async error => {
  await writeJson(path.resolve('perf/data/results/mixed-error.json'), {
    at: new Date().toISOString(),
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  })
  console.error(error)
  process.exitCode = 1
})
