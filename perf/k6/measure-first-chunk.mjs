import fs from 'node:fs/promises'
import path from 'node:path'

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
  const configPath = process.env.PERF_CONFIG || path.resolve('perf/config/env-dev.properties')
  const content = await fs.readFile(configPath, 'utf8')
  return parseProperties(content)
}

async function login(config) {
  const protocol = config.get('protocol') || 'http'
  const host = config.get('host') || '127.0.0.1'
  const managementPort = config.get('management.port') || '8089'
  const contextPath = config.get('context.path') || '/award-log'
  const username = process.env.LOGIN_USERNAME || config.get('login.username') || 'test'
  const password = process.env.LOGIN_PASSWORD || config.get('login.password') || 'test'
  const url = `${protocol}://${host}:${managementPort}${contextPath}/admin/user/login`
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password })
  })
  if (!response.ok) {
    throw new Error(`Login failed with HTTP ${response.status}`)
  }
  const body = await response.json()
  if (body.code !== 200) {
    throw new Error(`Login failed: ${body.message}`)
  }
  const rawCookie = response.headers.get('set-cookie') || ''
  const cookie = rawCookie.split(',').map(part => part.split(';')[0].trim()).filter(Boolean).join('; ')
  if (!cookie) {
    throw new Error('No session cookie returned from login')
  }
  return {
    cookie,
    businessBase: `${protocol}://${host}:${config.get('business.port') || '8088'}${contextPath}`,
    managementBase: `${protocol}://${host}:${managementPort}${contextPath}`,
    origin: config.get('allowed.origin') || 'http://127.0.0.1:5173'
  }
}

function resolveScenarioUrl(auth, scenario, taskId) {
  switch (scenario) {
    case 'assistant':
      return `${auth.managementBase}/api/assistant/chat/stream`
    case 'diagnose':
      if (!taskId) {
        throw new Error('TASK_ID is required for diagnose scenario')
      }
      return `${auth.businessBase}/log/diagnose/stream/${taskId}`
    default:
      return `${auth.managementBase}/api/assistant/chat/stream`
  }
}

function buildBody(scenario, prompt) {
  if (scenario === 'diagnose') {
    return undefined
  }
  return JSON.stringify({
    message: prompt,
    history: [],
    useToolAgent: true,
    confirmRemediation: false
  })
}

async function main() {
  const config = await loadConfig()
  const auth = await login(config)
  const scenario = process.env.SSE_SCENARIO || 'assistant'
  const taskId = process.env.TASK_ID || config.get('task.id') || ''
  const prompt = process.env.PROMPT || '请总结最近平台性能中最值得关注的问题'
  const url = resolveScenarioUrl(auth, scenario, taskId)
  const startedAt = performance.now()
  const response = await fetch(url, {
    method: scenario === 'diagnose' ? 'GET' : 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Origin: auth.origin,
      Cookie: auth.cookie
    },
    body: buildBody(scenario, prompt)
  })
  if (!response.ok || !response.body) {
    throw new Error(`SSE request failed with HTTP ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let firstChunkMs = null
  let totalBytes = 0
  let finalText = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    if (firstChunkMs === null) {
      firstChunkMs = performance.now() - startedAt
    }
    totalBytes += value.byteLength
    finalText += decoder.decode(value, { stream: true })
  }
  finalText += decoder.decode()
  const completedMs = performance.now() - startedAt
  console.log(JSON.stringify({
    scenario,
    taskId: taskId || null,
    firstChunkMs,
    completedMs,
    totalBytes,
    preview: finalText.slice(0, 200)
  }, null, 2))
}

main().catch(error => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
})
