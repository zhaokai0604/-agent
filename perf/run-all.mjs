#!/usr/bin/env node
/**
 * 一键跑核心 perf 批次：冒烟 → 基线 → WS → 1MB 上传 → 综合评分
 * 前提：后端 dev 已启动（8088 + 8089）
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(__dirname, '..')

function runNode(script, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(__dirname, script)], {
      cwd: root,
      env: { ...process.env, ...env },
      stdio: 'inherit',
      shell: false
    })
    child.on('exit', code => (code === 0 ? resolve() : reject(new Error(`${script} exited ${code}`))))
  })
}

async function main() {
  console.log('== perf: smoke ==')
  await runNode('run-smoke.mjs')

  console.log('== perf: baseline ==')
  await runNode('run-baseline.mjs')

  console.log('== perf: websocket (20 connections) ==')
  await runNode('run-ws-benchmark.mjs', {
    WS_CONNECTIONS: '20',
    WS_RESULT_FILE: 'perf/data/results/ws-benchmark.json'
  })

  console.log('== perf: upload 1MB x5 ==')
  await runNode('run-upload-benchmark.mjs', {
    UPLOAD_FILE: 'perf/data/logs/log-small-mixed-1mb.log',
    UPLOAD_CONCURRENCY: '5',
    UPLOAD_REQUESTS: '5',
    UPLOAD_RESULT_FILE: 'perf/data/results/upload-benchmark.json'
  })

  console.log('== perf: score ==')
  await runNode('score-results.mjs')

  console.log('== perf:all complete ==')
}

main().catch(err => {
  console.error(err.message || err)
  process.exit(1)
})
