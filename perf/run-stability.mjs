import fs from 'node:fs/promises'
import path from 'node:path'
import { performance } from 'node:perf_hooks'

const RESULT_FILE = path.resolve(process.env.STABILITY_RESULT_FILE || 'perf/data/results/stability-result.json')

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

async function main() {
  const sampleCount = Number(process.env.STABILITY_SAMPLES || 6)
  const sampleIntervalMs = Number(process.env.STABILITY_INTERVAL_MS || 10000)
  const discoveredPid = await import('node:child_process').then(({ execFile }) =>
    new Promise(resolve => {
      execFile('powershell', [
        '-NoProfile',
        '-Command',
        "(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'award-log-1.0-SNAPSHOT.jar' } | Select-Object -First 1 -ExpandProperty ProcessId)"
      ], (error, stdout) => {
        if (error) {
          resolve(null)
          return
        }
        const parsed = Number(String(stdout).trim())
        resolve(Number.isFinite(parsed) ? parsed : null)
      })
    }))
  const targetPid = Number(process.env.STABILITY_PID || discoveredPid || 0)
  const output = {
    startedAt: new Date().toISOString(),
    sampleIntervalMs,
    targetPid,
    samples: []
  }

  for (let i = 0; i < sampleCount; i += 1) {
    const proc = await fetch(`http://127.0.0.1:8089/award-log/actuator/health`).then(async response => {
      const text = await response.text()
      let json = null
      try {
        json = text ? JSON.parse(text) : null
      } catch {
        json = null
      }
      return {
        status: response.status,
        json
      }
    })

    const memSnapshot = await import('node:child_process').then(({ execFile }) =>
      new Promise(resolve => {
        execFile('powershell', [
          '-NoProfile',
          '-Command',
          `Get-Process -Id ${targetPid} | Select-Object Id,CPU,WS,PM,Handles,@{Name='ThreadCount';Expression={$_.Threads.Count}} | ConvertTo-Json -Compress`
        ], (error, stdout) => {
          if (error) {
            resolve({ error: error.message })
            return
          }
          try {
            resolve(JSON.parse(stdout))
          } catch (parseError) {
            resolve({ error: parseError.message, raw: stdout })
          }
        })
      }))

    output.samples.push({
      at: new Date().toISOString(),
      health: proc,
      process: memSnapshot
    })

    if (i < sampleCount - 1) {
      await new Promise(resolve => setTimeout(resolve, sampleIntervalMs))
    }
  }

  output.finishedAt = new Date().toISOString()
  await fs.mkdir(path.dirname(RESULT_FILE), { recursive: true })
  await fs.writeFile(RESULT_FILE, JSON.stringify(output, null, 2), 'utf8')
  console.log(JSON.stringify(output, null, 2))
}

main().catch(async error => {
  await fs.mkdir(path.resolve('perf/data/results'), { recursive: true })
  await fs.writeFile(
    path.resolve('perf/data/results/stability-error.json'),
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
