import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const CONFIG_PATH = process.env.PERF_CONFIG || path.resolve('perf/config/env-dev.properties')
const FILE_PATH = path.resolve(process.env.UPLOAD_FILE || 'perf/data/logs/log-small-mixed-1mb.log')
const CONCURRENCY = Number(process.env.UPLOAD_CONCURRENCY || 5)
const REQUESTS = Number(process.env.UPLOAD_REQUESTS || CONCURRENCY)
const RESULT_FILE = path.resolve(
  process.env.UPLOAD_RESULT_FILE || `perf/data/results/upload-benchmark-${path.basename(FILE_PATH)}-${CONCURRENCY}.json`
)

function parseProperties(text) {
  const map = new Map()
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const index = line.indexOf('=')
    if (index <= 0) continue
    map.set(line.slice(0, index).trim(), line.slice(index + 1).trim())
  }
  return map
}

async function loadConfig() {
  return parseProperties(await fs.readFile(CONFIG_PATH, 'utf8'))
}

function percentile(sorted, p) {
  if (!sorted.length) return null
  const rank = Math.ceil((p / 100) * sorted.length) - 1
  return sorted[Math.min(Math.max(rank, 0), sorted.length - 1)]
}

function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b)
  const total = sorted.reduce((sum, v) => sum + v, 0)
  return {
    count: sorted.length,
    minMs: sorted[0] ?? null,
    p50Ms: percentile(sorted, 50),
    p95Ms: percentile(sorted, 95),
    p99Ms: percentile(sorted, 99),
    maxMs: sorted.at(-1) ?? null,
    avgMs: sorted.length ? Math.round(total / sorted.length) : null
  }
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

async function registerAndLogin(config) {
  const managementBase = baseUrl(config, 'management')
  const username = `upload_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const password = 'PerfRunner#2026'
  const email = `${username}@example.com`

  await fetch(`${managementBase}/admin/user/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password, email })
  })

  const loginResp = await fetch(`${managementBase}/admin/user/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password })
  })
  const cookieHeader = (loginResp.headers.getSetCookie?.() || [])
    .map(header => header.split(';')[0])
    .join('; ')
  return {
    cookieHeader
  }
}

async function uploadOnce(fileBuffer, fileName, businessBase, cookieHeader, pollIntervalMs, timeoutMs) {
  const startedAt = performance.now()
  const form = new FormData()
  form.set('file', new Blob([fileBuffer], { type: 'text/plain' }), fileName)

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
  let pollCount = 0
  const deadline = Date.now() + timeoutMs

  while (taskId && Date.now() < deadline) {
    const pollResp = await fetch(`${businessBase}/log/task/${taskId}`, {
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Cookie: cookieHeader
      }
    })
    const pollText = await pollResp.text()
    let pollJson = null
    try {
      pollJson = pollText ? JSON.parse(pollText) : null
    } catch {
      pollJson = null
    }
    pollCount += 1
    finalStatus = pollJson?.data?.status || null
    if (finalStatus === 'COMPLETED' || finalStatus === 'FAILED' || finalStatus === 'CANCELLED') {
      break
    }
    await new Promise(resolve => setTimeout(resolve, pollIntervalMs))
  }

  if (taskId) {
    await fetch(`${businessBase}/log/report/${taskId}`, {
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Cookie: cookieHeader
      }
    })
  }

  return {
    taskId,
    status: finalStatus,
    elapsedMs: Math.round(performance.now() - startedAt),
    pollCount,
    ok: uploadResp.ok && finalStatus === 'COMPLETED'
  }
}

async function main() {
  const config = await loadConfig()
  const businessBase = baseUrl(config, 'business')
  const auth = await registerAndLogin(config)
  const fileBuffer = await fs.readFile(FILE_PATH)
  const fileName = path.basename(FILE_PATH)
  const pollIntervalMs = Number(config.get('poll.interval.ms') || '3000')
  const timeoutMs = Number(process.env.UPLOAD_TIMEOUT_MS || config.get('task.complete.timeout.ms') || '900000')

  let nextIndex = 0
  const results = []

  async function worker() {
    while (true) {
      const index = nextIndex++
      if (index >= REQUESTS) return
      results[index] = await uploadOnce(
        fileBuffer,
        fileName,
        businessBase,
        auth.cookieHeader,
        pollIntervalMs,
        timeoutMs
      )
    }
  }

  const startedAt = new Date().toISOString()
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, REQUESTS) }, () => worker()))
  const elapsedValues = results.map(item => item.elapsedMs)
  const completed = results.filter(item => item.ok).length

  const output = {
    startedAt,
    finishedAt: new Date().toISOString(),
    file: fileName,
    fileSizeBytes: fileBuffer.length,
    concurrency: CONCURRENCY,
    requests: REQUESTS,
    completed,
    failed: REQUESTS - completed,
    summary: summarize(elapsedValues),
    results
  }

  await fs.mkdir(path.dirname(RESULT_FILE), { recursive: true })
  await fs.writeFile(RESULT_FILE, JSON.stringify(output, null, 2), 'utf8')
  console.log(JSON.stringify(output, null, 2))
}

main().catch(async error => {
  await fs.mkdir(path.resolve('perf/data/results'), { recursive: true })
  await fs.writeFile(
    path.resolve('perf/data/results/upload-benchmark-error.json'),
    JSON.stringify({
      at: new Date().toISOString(),
      message: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : null
    }, null, 2),
    'utf8'
  )
  console.error(error)
  process.exitCode = 1
})
