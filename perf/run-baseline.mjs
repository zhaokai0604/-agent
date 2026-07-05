import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const CONFIG_PATH = process.env.PERF_CONFIG || path.resolve('perf/config/env-dev.properties')
const RESULT_DIR = path.resolve('perf/data/results')

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
  await fs.mkdir(RESULT_DIR, { recursive: true })
  const file = path.join(RESULT_DIR, fileName)
  await fs.writeFile(file, JSON.stringify(data, null, 2), 'utf8')
  return file
}

async function httpJson(url, options = {}) {
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
  const username = `perf_${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(8, 14)}`
  const password = 'PerfRunner#2026'
  const email = `${username}@example.com`
  await httpJson(`${managementBase}/admin/user/register`, {
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

async function runScenario(name, count, workerCount, requestFactory) {
  const latencies = []
  let errors = 0
  let statusCounts = new Map()
  let nextIndex = 0

  async function worker() {
    while (true) {
      const index = nextIndex++
      if (index >= count) {
        return
      }
      const result = await requestFactory(index)
      latencies.push(result.elapsedMs)
      statusCounts.set(String(result.status), (statusCounts.get(String(result.status)) || 0) + 1)
      if (!result.ok) {
        errors += 1
      }
    }
  }

  const startedAt = performance.now()
  await Promise.all(Array.from({ length: workerCount }, () => worker()))
  const totalMs = Math.round(performance.now() - startedAt)
  return {
    scenario: name,
    requests: count,
    concurrency: workerCount,
    totalMs,
    throughputRps: Number((count / (totalMs / 1000)).toFixed(2)),
    errorRate: Number((errors / count).toFixed(4)),
    statuses: Object.fromEntries(statusCounts.entries()),
    latency: summarizeLatencies(latencies)
  }
}

async function main() {
  const config = await loadConfig()
  const businessBase = baseUrl(config, 'business')
  const managementBase = baseUrl(config, 'management')
  const auth = await registerAndLogin(config)
  const cookieHeader = auth.cookieHeader

  const scenarios = [
    {
      name: 'platform_info',
      surface: 'management',
      url: `${managementBase}/api/platform/info`
    },
    {
      name: 'mcp_tools',
      surface: 'management',
      url: `${managementBase}/api/mcp/tools`
    },
    {
      name: 'alarm_history_list',
      surface: 'business',
      url: `${businessBase}/api/alarm/history/list?pageNum=1&pageSize=10`
    },
    {
      name: 'performance_baseline',
      surface: 'management',
      url: `${managementBase}/api/performance/baseline?dataType=cpu&days=7`
    },
    {
      name: 'knowledge_search',
      surface: 'business',
      url: `${businessBase}/api/v1/knowledge/search?query=${encodeURIComponent(config.get('knowledge.query') || 'database connection timeout')}&topK=5`
    }
  ]

  const output = {
    startedAt: new Date().toISOString(),
    counts: [5, 20],
    concurrencyLevels: [1, 5],
    results: []
  }

  for (const scenario of scenarios) {
    for (const count of output.counts) {
      for (const concurrency of output.concurrencyLevels) {
        const result = await runScenario(
          scenario.name,
          count,
          Math.min(concurrency, count),
          async () => httpJson(scenario.url, {
            headers: {
              Accept: 'application/json',
              'X-Requested-With': 'XMLHttpRequest',
              Cookie: cookieHeader
            }
          })
        )
        output.results.push(result)
      }
    }
  }

  output.finishedAt = new Date().toISOString()
  const file = await writeJson('baseline-result.json', output)
  console.log(JSON.stringify({
    resultFile: file,
    scenarios: output.results.length
  }, null, 2))
}

main().catch(async error => {
  const file = await writeJson('baseline-error.json', {
    at: new Date().toISOString(),
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  })
  console.error(`Baseline test failed. Details: ${file}`)
  process.exitCode = 1
})
