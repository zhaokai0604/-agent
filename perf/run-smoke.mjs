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
  const text = await fs.readFile(CONFIG_PATH, 'utf8')
  return parseProperties(text)
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

function buildCookieHeader(cookieMap) {
  return [...cookieMap.entries()].map(([key, value]) => `${key}=${value}`).join('; ')
}

async function writeJson(fileName, data) {
  await fs.mkdir(RESULT_DIR, { recursive: true })
  const target = path.join(RESULT_DIR, fileName)
  await fs.writeFile(target, JSON.stringify(data, null, 2), 'utf8')
  return target
}

async function httpJson(url, options = {}) {
  const startedAt = performance.now()
  const response = await fetch(url, options)
  const elapsedMs = Math.round(performance.now() - startedAt)
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch (error) {
    json = null
  }
  return {
    ok: response.ok,
    status: response.status,
    elapsedMs,
    text,
    json
  }
}

async function registerAndLogin(config) {
  const managementBase = baseUrl(config, 'management')
  const username = `perf_${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(8, 14)}`
  const password = 'PerfRunner#2026'
  const email = `${username}@example.com`
  const cookieJar = new Map()

  const registerResp = await httpJson(`${managementBase}/admin/user/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password, email })
  })

  const loginResponse = await fetch(`${managementBase}/admin/user/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password })
  })
  const loginText = await loginResponse.text()
  let loginJson = null
  try {
    loginJson = loginText ? JSON.parse(loginText) : null
  } catch (error) {
    loginJson = null
  }
  const setCookie = loginResponse.headers.getSetCookie?.() || []
  for (const header of setCookie) {
    const [pair] = header.split(';')
    const index = pair.indexOf('=')
    if (index > 0) {
      cookieJar.set(pair.slice(0, index), pair.slice(index + 1))
    }
  }

  return {
    username,
    password,
    registerResp,
    loginResp: {
      ok: loginResponse.ok,
      status: loginResponse.status,
      json: loginJson,
      text: loginText
    },
    cookieHeader: buildCookieHeader(cookieJar)
  }
}

async function uploadAndPoll(config, cookieHeader) {
  const businessBase = baseUrl(config, 'business')
  const uploadPath = path.resolve(config.get('log.file.small') || 'perf/data/logs/log-small-mixed-1mb.log')
  const blob = new Blob([await fs.readFile(uploadPath)], { type: 'text/plain' })
  const form = new FormData()
  form.set('file', blob, path.basename(uploadPath))

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
  } catch (error) {
    uploadJson = null
  }
  const taskId = uploadJson?.data || ''

  const polls = []
  let finalTask = null
  if (taskId) {
    const pollTimeoutMs = Number(config.get('task.complete.timeout.ms') || '900000')
    const pollIntervalMs = Number(config.get('poll.interval.ms') || '3000')
    const deadline = Date.now() + pollTimeoutMs
    while (Date.now() < deadline) {
      const poll = await httpJson(`${businessBase}/log/task/${taskId}`, {
        headers: {
          Accept: 'application/json',
          'X-Requested-With': 'XMLHttpRequest',
          Cookie: cookieHeader
        }
      })
      polls.push({
        at: new Date().toISOString(),
        status: poll.status,
        elapsedMs: poll.elapsedMs,
        taskStatus: poll.json?.data?.status || null
      })
      const status = poll.json?.data?.status
      if (status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') {
        finalTask = poll.json?.data || null
        break
      }
      await new Promise(resolve => setTimeout(resolve, pollIntervalMs))
    }
  }

  let reportResp = null
  if (taskId) {
    reportResp = await httpJson(`${businessBase}/log/report/${taskId}`, {
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Cookie: cookieHeader
      }
    })
  }

  return {
    upload: {
      status: uploadResp.status,
      elapsedMs: uploadElapsedMs,
      json: uploadJson,
      text: uploadText
    },
    taskId,
    polls,
    finalTask,
    report: reportResp
  }
}

async function streamSse(url, cookieHeader, body) {
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
      firstChunkMs: null,
      completedMs: null,
      preview: await response.text()
    }
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let firstChunkMs = null
  let preview = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    if (firstChunkMs === null) {
      firstChunkMs = Math.round(performance.now() - startedAt)
    }
    preview += decoder.decode(value, { stream: true })
    if (preview.length >= 240) {
      preview = preview.slice(0, 240)
      break
    }
  }
  try {
    await reader.cancel()
  } catch {
    // Ignore cancel errors after preview capture.
  }
  return {
    ok: true,
    status: response.status,
    firstChunkMs,
    completedMs: Math.round(performance.now() - startedAt),
    preview
  }
}

async function checkWebSocket(config, cookieHeader) {
  const url = baseUrl(config, 'management').replace(/^http/, 'ws') + '/ws/performance'
  const startedAt = performance.now()
  return await new Promise(resolve => {
    let settled = false
    const socket = new WebSocket(url, {
      headers: {
        Cookie: cookieHeader,
        Origin: 'http://127.0.0.1:5173'
      }
    })
    const timeout = setTimeout(() => {
      if (!settled) {
        settled = true
        try {
          socket.close()
        } catch {
          // ignore
        }
        resolve({
          ok: false,
          connectMs: null,
          messageMs: null,
          message: 'timeout'
        })
      }
    }, 12000)

    socket.addEventListener('open', () => {
      try {
        socket.send('refresh')
      } catch {
        // ignore
      }
    })

    socket.addEventListener('message', event => {
      if (!settled) {
        settled = true
        clearTimeout(timeout)
        const elapsedMs = Math.round(performance.now() - startedAt)
        resolve({
          ok: true,
          connectMs: elapsedMs,
          messageMs: elapsedMs,
          message: String(event.data).slice(0, 240)
        })
        socket.close()
      }
    })

    socket.addEventListener('error', () => {
      if (!settled) {
        settled = true
        clearTimeout(timeout)
        resolve({
          ok: false,
          connectMs: null,
          messageMs: null,
          message: 'error'
        })
      }
    })
  })
}

async function main() {
  const config = await loadConfig()
  const managementBase = baseUrl(config, 'management')
  const businessBase = baseUrl(config, 'business')
  const auth = await registerAndLogin(config)

  const output = {
    startedAt: new Date().toISOString(),
    environment: {
      businessBase,
      managementBase
    },
    auth
  }

  output.managementHealth = await httpJson(`${managementBase}/actuator/health`)
  output.prometheus = {
    status: (await fetch(`${managementBase}/actuator/prometheus`)).status
  }
  output.acceptance = await httpJson(`${businessBase}/api/platform/acceptance`)
  output.platformInfo = await httpJson(`${managementBase}/api/platform/info`, {
    headers: {
      Accept: 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Cookie: auth.cookieHeader
    }
  })
  output.mcpTools = await httpJson(`${managementBase}/api/mcp/tools`, {
    headers: {
      Accept: 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Cookie: auth.cookieHeader
    }
  })
  output.uploadFlow = await uploadAndPoll(config, auth.cookieHeader)
  output.chatSse = await streamSse(`${managementBase}/api/assistant/chat/stream`, auth.cookieHeader, {
    message: '请简要总结当前平台运行状态'
  })
  output.assistantSse = await streamSse(`${managementBase}/api/assistant/chat/stream`, auth.cookieHeader, {
    message: '请给出当前平台健康检查摘要',
    history: [],
    useToolAgent: false,
    confirmRemediation: false
  })
  output.diagnoseSse = output.uploadFlow.taskId
    ? await streamSse(`${businessBase}/log/diagnose/stream/${output.uploadFlow.taskId}`, auth.cookieHeader)
    : { ok: false, reason: 'missing task id' }
  output.websocket = await checkWebSocket(config, auth.cookieHeader)
  output.knowledgeSearch = await httpJson(
    `${businessBase}/api/v1/knowledge/search?query=${encodeURIComponent(config.get('knowledge.query') || 'database connection timeout')}&topK=${encodeURIComponent(config.get('knowledge.topk') || '5')}`,
    {
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Cookie: auth.cookieHeader
      }
    }
  )
  output.finishedAt = new Date().toISOString()

  const file = await writeJson('smoke-result.json', output)
  console.log(JSON.stringify({ resultFile: file, summary: {
    loginOk: auth.loginResp?.json?.code === 200,
    managementHealthStatus: output.managementHealth.status,
    uploadStatus: output.uploadFlow.upload.status,
    finalTaskStatus: output.uploadFlow.finalTask?.status || null,
    chatSseFirstChunkMs: output.chatSse.firstChunkMs,
    assistantSseFirstChunkMs: output.assistantSse.firstChunkMs,
    wsOk: output.websocket.ok,
    knowledgeStatus: output.knowledgeSearch.status
  } }, null, 2))
}

main().catch(async error => {
  const target = await writeJson('smoke-error.json', {
    at: new Date().toISOString(),
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  })
  console.error(`Smoke test failed. Details: ${target}`)
  process.exitCode = 1
})
