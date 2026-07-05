import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const RESULT_FILE = path.resolve(process.env.WS_RESULT_FILE || 'perf/data/results/ws-benchmark.json')
const CONNECTIONS = Number(process.env.WS_CONNECTIONS || 20)
const TIMEOUT_MS = Number(process.env.WS_TIMEOUT_MS || 15000)

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
    avgMs: sorted.length ? Number((total / sorted.length).toFixed(2)) : null,
    minMs: sorted[0] ?? null,
    p50Ms: percentile(sorted, 50),
    p95Ms: percentile(sorted, 95),
    p99Ms: percentile(sorted, 99),
    maxMs: sorted.at(-1) ?? null
  }
}

async function createSession() {
  const managementBase = 'http://127.0.0.1:8089/award-log'
  const username = `ws_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
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
    managementBase,
    cookieHeader
  }
}

async function measureOne(cookieHeader, index) {
  const url = 'ws://127.0.0.1:8089/award-log/ws/performance'
  const startedAt = performance.now()

  return await new Promise(resolve => {
    let settled = false
    const ws = new WebSocket(url, {
      headers: {
        Cookie: cookieHeader,
        Origin: 'http://127.0.0.1:5173'
      }
    })

    const timeout = setTimeout(() => {
      if (settled) return
      settled = true
      try {
        ws.close()
      } catch {
      }
      resolve({
        ok: false,
        index,
        connectMs: null,
        firstMessageMs: null,
        error: 'timeout'
      })
    }, TIMEOUT_MS)

    ws.addEventListener('open', () => {
      try {
        ws.send('refresh')
      } catch (error) {
        if (settled) return
        settled = true
        clearTimeout(timeout)
        resolve({
          ok: false,
          index,
          connectMs: null,
          firstMessageMs: null,
          error: error instanceof Error ? error.message : String(error)
        })
      }
    })

    ws.addEventListener('message', event => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      const elapsedMs = Number((performance.now() - startedAt).toFixed(2))
      try {
        ws.close()
      } catch {
      }
      resolve({
        ok: true,
        index,
        connectMs: elapsedMs,
        firstMessageMs: elapsedMs,
        preview: String(event.data).slice(0, 200)
      })
    })

    ws.addEventListener('error', () => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      resolve({
        ok: false,
        index,
        connectMs: null,
        firstMessageMs: null,
        error: 'ws error'
      })
    })
  })
}

async function main() {
  const session = await createSession()
  const results = await Promise.all(
    Array.from({ length: CONNECTIONS }, (_, index) => measureOne(session.cookieHeader, index))
  )

  const successes = results.filter(item => item.ok)
  const failures = results.filter(item => !item.ok)
  const connectLatencies = successes.map(item => item.connectMs).filter(v => typeof v === 'number')
  const firstMessageLatencies = successes.map(item => item.firstMessageMs).filter(v => typeof v === 'number')

  const output = {
    generatedAt: new Date().toISOString(),
    connections: CONNECTIONS,
    timeoutMs: TIMEOUT_MS,
    wsUrl: 'ws://127.0.0.1:8089/award-log/ws/performance',
    successCount: successes.length,
    failureCount: failures.length,
    successRate: Number((successes.length / Math.max(CONNECTIONS, 1)).toFixed(4)),
    connectLatency: summarizeLatencies(connectLatencies),
    firstMessageLatency: summarizeLatencies(firstMessageLatencies),
    failures: failures.slice(0, 10),
    results
  }

  await fs.mkdir(path.dirname(RESULT_FILE), { recursive: true })
  await fs.writeFile(RESULT_FILE, JSON.stringify(output, null, 2), 'utf8')
  console.log(JSON.stringify(output, null, 2))
}

main().catch(async error => {
  await fs.mkdir(path.resolve('perf/data/results'), { recursive: true })
  await fs.writeFile(
    path.resolve('perf/data/results/ws-benchmark-error.json'),
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
