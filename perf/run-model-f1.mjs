import fs from 'node:fs/promises'
import path from 'node:path'

const RESULT_FILE = path.resolve('perf/data/results/model-f1.json')

async function createSession() {
  const managementBase = 'http://127.0.0.1:8089/award-log'
  const username = `f1_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
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

  const response = await fetch(`${managementBase}/admin/user/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body: JSON.stringify({ username, password })
  })
  const cookies = []
  for (const header of response.headers.getSetCookie?.() || []) {
    cookies.push(header.split(';')[0])
  }
  return {
    managementBase,
    cookieHeader: cookies.join('; ')
  }
}

function buildSyntheticDataset() {
  return [
    { features: [0.92, 0.88, 0.79], label: 1 },
    { features: [0.86, 0.77, 0.73], label: 1 },
    { features: [0.61, 0.59, 0.66], label: 1 },
    { features: [0.57, 0.56, 0.54], label: 0 },
    { features: [0.48, 0.44, 0.41], label: 0 },
    { features: [0.32, 0.28, 0.35], label: 0 },
    { features: [0.12, 0.21, 0.18], label: 0 },
    { features: [0.42, 0.41, 0.44], label: 1 },
    { features: [0.39, 0.46, 0.42], label: 0 },
    { features: [0.81, 0.83, 0.79], label: 1 },
    { features: [0.22, 0.31, 0.27], label: 0 }
  ]
}

async function main() {
  const session = await createSession()
  const payload = {
    modelVersion: 'rf-onnx-v2-demo',
    dataset: buildSyntheticDataset()
  }
  const response = await fetch(`${session.managementBase}/api/v1/model/evaluate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      Cookie: session.cookieHeader
    },
    body: JSON.stringify(payload)
  })
  const json = await response.json()
  const result = {
    generatedAt: new Date().toISOString(),
    source: 'synthetic-demo-dataset',
    requestSampleSize: payload.dataset.length,
    httpStatus: response.status,
    response: json
  }
  await fs.mkdir(path.dirname(RESULT_FILE), { recursive: true })
  await fs.writeFile(RESULT_FILE, JSON.stringify(result, null, 2), 'utf8')
  console.log(JSON.stringify(result, null, 2))
}

main().catch(async error => {
  await fs.mkdir(path.resolve('perf/data/results'), { recursive: true })
  await fs.writeFile(path.resolve('perf/data/results/model-f1-error.json'), JSON.stringify({
    at: new Date().toISOString(),
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  }, null, 2), 'utf8')
  console.error(error)
  process.exitCode = 1
})
