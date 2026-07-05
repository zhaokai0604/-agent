import fs from 'node:fs/promises'
import path from 'node:path'

const RESULT_DIR = path.resolve('perf/data/results')
const SCORE_FILE = path.join(RESULT_DIR, 'performance-score.json')

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, 'utf8'))
}

function clamp01(value) {
  return Math.max(0, Math.min(1, value))
}

function ratioScore(actual, target) {
  if (actual == null || Number.isNaN(actual)) return 0
  return clamp01(target / Math.max(actual, target))
}

function passFail(value, predicate, pass = 1, fail = 0) {
  return predicate(value) ? pass : fail
}

async function main() {
  const smoke = await readJson(path.join(RESULT_DIR, 'smoke-result.json'))
  const baseline = await readJson(path.join(RESULT_DIR, 'baseline-result.json'))
  const upload5 = await readJson(path.join(RESULT_DIR, 'upload-benchmark.json'))
  const upload10 = await readJson(path.join(RESULT_DIR, 'upload-benchmark-10.json'))
  const upload10mb = await readJson(path.join(RESULT_DIR, 'upload-benchmark-10mb.json'))
  const upload50mb = await readJson(path.join(RESULT_DIR, 'upload-benchmark-50mb.json'))
  const sse5 = await readJson(path.join(RESULT_DIR, 'sse-benchmark.json'))
  const sse10 = await readJson(path.join(RESULT_DIR, 'sse-benchmark-10.json'))
  const ws = await readJson(path.join(RESULT_DIR, 'ws-benchmark.json'))
  const mixed = await readJson(path.join(RESULT_DIR, 'mixed-result.json'))
  const stability = await readJson(path.join(RESULT_DIR, 'stability-result.json'))

  const baseline20x5 = Object.fromEntries(
    baseline.results
      .filter(item => item.requests === 20 && item.concurrency === 5)
      .map(item => [item.scenario, item])
  )

  const baselineP95 = Object.values(baseline20x5).map(item => item.latency?.p95).filter(v => typeof v === 'number')
  const baselineWorstP95 = baselineP95.length ? Math.max(...baselineP95) : null
  const queryScore = ratioScore(baselineWorstP95, 1000)

  const upload5P95 = upload5.p95Ms ?? upload5.summary?.p95Ms
  const upload10P95 = upload10.p95Ms ?? upload10.summary?.p95Ms
  const upload5Score = ratioScore(upload5P95, 12000)
  const upload10Score = ratioScore(upload10P95, 15000)
  const upload10mbScore = ratioScore(upload10mb.summary?.p95Ms, 60000)
  const upload50mbScore = ratioScore(upload50mb.summary?.p95Ms, 120000)
  const uploadCompletions = [
    upload5.completed === 5,
    upload10.completed === 10,
    upload10mb.completed === upload10mb.requests,
    upload50mb.completed === upload50mb.requests
  ].filter(Boolean).length / 4
  const uploadScore = clamp01(
    (upload5Score * 0.20) +
    (upload10Score * 0.20) +
    (upload10mbScore * 0.25) +
    (upload50mbScore * 0.20) +
    (uploadCompletions * 0.15)
  )

  const sse5Score = ratioScore(sse5.firstChunkP95Ms, 5000)
  const sse10Score = ratioScore(sse10.firstChunkP95Ms, 5000)
  const sseScore = clamp01((sse5Score * 0.45) + (sse10Score * 0.45) + ((sse10.success === 10 ? 1 : 0) * 0.10))

  const wsScore = clamp01((ws.successRate * 0.65) + (ratioScore(ws.firstMessageLatency?.p95Ms, 1500) * 0.35))

  const healthScore = clamp01(
    (smoke.prometheus?.status === 200 ? 0.25 : 0) +
    (smoke.managementHealth?.json?.status === 'UP' ? 0.45 : 0) +
    (smoke.uploadFlow?.finalTask?.status === 'COMPLETED' ? 0.15 : 0) +
    (smoke.knowledgeSearch?.status === 200 ? 0.15 : 0)
  )

  const platformData = smoke.platformInfo?.json?.data || {}
  const components = platformData.components || {}
  const dependencySignals = [
    components.ai?.enabled === true,
    components.mysql?.reachable === true || components.mariadb?.reachable === true,
    components.knowledge?.enabled === true && smoke.knowledgeSearch?.status === 200,
    components.redis?.enabled === true,
    components.elasticsearch?.enabled === true,
    components.kafka?.enabled === true
  ]
  const dependencyScore = dependencySignals.filter(Boolean).length / dependencySignals.length

  const mixedScores = (mixed.scenarios || []).map(scenario => {
    if (scenario.scenario === 'A_upload_report') return ratioScore(scenario.latency?.p95, 15000)
    if (scenario.scenario === 'B_ai_chat_sse') return ratioScore(scenario.firstChunkLatency?.p95, 5000)
    return ratioScore(scenario.latency?.p95, 2000)
  })
  const mixedScore = mixedScores.length
    ? clamp01(mixedScores.reduce((sum, item) => sum + item, 0) / mixedScores.length)
    : 0

  const stabilitySamples = stability.samples || []
  const stabilityHealthPasses = stabilitySamples.filter(sample => sample.health?.status === 200).length
  const firstWs = stabilitySamples[0]?.process?.WS
  const lastWs = stabilitySamples.at(-1)?.process?.WS
  const memoryGrowthRatio = typeof firstWs === 'number' && typeof lastWs === 'number' && firstWs > 0
    ? (lastWs - firstWs) / firstWs
    : null
  const stabilityScore = clamp01(
    (stabilityHealthPasses / Math.max(stabilitySamples.length, 1)) * 0.6 +
    ((memoryGrowthRatio != null && memoryGrowthRatio <= 0.05) ? 0.4 : 0)
  )

  const weighted = {
    healthReadiness: { weight: 0.18, score: healthScore },
    queryLatency: { weight: 0.14, score: queryScore },
    uploadCapacity: { weight: 0.22, score: uploadScore },
    sseExperience: { weight: 0.14, score: sseScore },
    websocketCapacity: { weight: 0.10, score: wsScore },
    dependencyReadiness: { weight: 0.05, score: dependencyScore },
    mixedWorkload: { weight: 0.12, score: mixedScore },
    stability: { weight: 0.05, score: stabilityScore }
  }

  let total = 0
  for (const item of Object.values(weighted)) {
    total += item.weight * item.score
  }

  const result = {
    generatedAt: new Date().toISOString(),
    score: Number(total.toFixed(4)),
    grade: total >= 0.85 ? 'A' : total >= 0.70 ? 'B' : total >= 0.55 ? 'C' : total >= 0.40 ? 'D' : 'E',
    dimensions: Object.fromEntries(
      Object.entries(weighted).map(([key, value]) => [
        key,
        {
          weight: value.weight,
          score: Number(value.score.toFixed(4)),
          contribution: Number((value.weight * value.score).toFixed(4))
        }
      ])
    ),
    evidence: {
      healthStatus: smoke.managementHealth?.json?.status || null,
      baselineWorstP95Ms: baselineWorstP95,
      uploadP95Ms: {
        concurrency5: upload5P95,
        concurrency10: upload10P95,
        file10mb: upload10mb.summary?.p95Ms ?? null,
        file50mb: upload50mb.summary?.p95Ms ?? null
      },
      sseFirstChunkP95Ms: {
        concurrency5: sse5.firstChunkP95Ms,
        concurrency10: sse10.firstChunkP95Ms
      },
      ws: {
        successRate: ws.successRate,
        firstMessageP95Ms: ws.firstMessageLatency?.p95Ms ?? null
      },
      stability: {
        sampleCount: stabilitySamples.length,
        memoryGrowthRatio: memoryGrowthRatio != null ? Number(memoryGrowthRatio.toFixed(4)) : null
      }
    }
  }

  await fs.writeFile(SCORE_FILE, JSON.stringify(result, null, 2), 'utf8')
  console.log(JSON.stringify(result, null, 2))
}

main().catch(async error => {
  await fs.writeFile(path.join(RESULT_DIR, 'performance-score-error.json'), JSON.stringify({
    at: new Date().toISOString(),
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  }, null, 2), 'utf8')
  console.error(error)
  process.exitCode = 1
})
