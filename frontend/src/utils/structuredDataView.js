/**
 * 将 API / 工具返回的结构化数据转为卡片、表格、标签、进度条等视图模型（避免堆砌 JSON）。
 */

const FIELD_LABELS = {
  mode: '模式',
  path: '路径',
  plan: '将执行',
  note: '说明',
  filesFound: '发现文件数',
  filesDeleted: '已删除',
  deletableCount: '可删除',
  protectedSkipped: '跳过受保护',
  previewCount: '预览条数',
  success: '成功',
  error: '错误',
  message: '消息',
  service: '服务',
  stderr: '错误输出',
  command: '命令',
  tool: '工具',
  output: '输出',
  root: '扫描根',
  entries: '条目',
  traceId: 'traceId',
  duration: '耗时(ms)',
  durationMs: '耗时(ms)',
  riskScore: '风险分',
  riskLevel: '风险等级',
  securityOutcome: '安全结论',
  securityCode: '安全码',
  needConfirm: '需确认',
  cancelled: '已取消',
  channel: '通道',
  phase: '阶段',
  title: '标题',
  detail: '详情',
  step: '步骤',
  status: '状态',
  type: '类型',
  target: '目标',
  cpuUsagePercent: 'CPU 使用率',
  memUsagePercent: '内存使用率',
  loadAvg1min: '1分钟负载',
  loadAvg5min: '5分钟负载',
  loadAvg15min: '15分钟负载',
  mountedOn: '挂载点',
  usePercent: '使用率',
  used: '已用',
  size: '容量',
  pid: 'PID',
  cpu: 'CPU%',
  mem: '内存%',
  severity: '级别',
  logPath: '日志路径',
  linesAnalyzed: '分析行数',
  errorCount: '错误数',
  rootCause: '根因',
  recommendation: '建议',
  executionOk: '执行成功',
  toolName: '工具名',
  resultSummary: '结果摘要',
  riskDimensions: '风险维度',
  riskExplanation: '风险说明',
  data: '数据',
  preview: '预览',
  protectedSamples: '受保护样例',
  hops: '路由跳',
  hopCount: '跳数',
  transmitted: '发送包',
  received: '接收包',
  packetLossPercent: '丢包率',
  avgLatencyMs: '平均延迟',
  hasPrivilege: '有权限',
  needsSudo: '需 sudo',
  currentUser: '当前用户',
  action: '操作',
  resource: '资源',
  suggestion: '建议',
  configType: '配置类型',
  configPath: '配置路径',
  passed: '通过',
  exitCode: '退出码',
  kb: '大小(KB)',
  maxDepth: '深度',
  overview: '概览',
  hotspotsRank: '热点目录',
  hint: '提示'
}

const METRIC_KEY_RE = /percent|usage|ratio|score|progress|level|risk/i
const TABLE_ARRAY_KEYS = new Set([
  'preview',
  'entries',
  'protectedSamples',
  'hops',
  'steps',
  'rows',
  'items',
  'list',
  'records'
])

export function fieldLabel(key) {
  if (key == null) return ''
  return FIELD_LABELS[key] || String(key)
}

export function looksLikeJsonString(s) {
  const t = String(s).trim()
  return (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))
}

export function coerceStructuredInput(val) {
  if (val == null) return null
  if (typeof val === 'string') {
    const t = val.trim()
    if (!t) return null
    if (looksLikeJsonString(t)) {
      try {
        return coerceStructuredInput(JSON.parse(t))
      } catch {
        return t
      }
    }
    return t
  }
  return val
}

function isPlainObject(v) {
  return v != null && typeof v === 'object' && !Array.isArray(v)
}

function parsePercentValue(val) {
  if (val == null || val === '') return null
  if (typeof val === 'number' && Number.isFinite(val)) {
    if (val >= 0 && val <= 1) return Math.round(val * 100)
    if (val >= 0 && val <= 100) return Math.round(val)
    if (val > 0 && val <= 10) return Math.round(val * 10)
    return null
  }
  const m = String(val).match(/(\d+(?:\.\d+)?)\s*%?/)
  if (!m) return null
  const n = parseFloat(m[1])
  if (!Number.isFinite(n)) return null
  if (String(val).includes('%')) return Math.min(100, Math.round(n))
  if (n >= 0 && n <= 1) return Math.round(n * 100)
  if (n >= 0 && n <= 100) return Math.round(n)
  if (n > 0 && n <= 10) return Math.round(n * 10)
  return null
}

function metricStatus(percent, key) {
  if (percent == null) return undefined
  const k = String(key || '').toLowerCase()
  if (k.includes('risk') || k.includes('error') || k.includes('anomaly')) {
    if (percent >= 70) return 'exception'
    if (percent >= 40) return 'warning'
    return 'success'
  }
  if (percent >= 90) return 'exception'
  if (percent >= 75) return 'warning'
  return 'success'
}

function formatScalar(val) {
  if (val == null) return '—'
  if (typeof val === 'boolean') return val ? '是' : '否'
  if (typeof val === 'number') return Number.isInteger(val) ? String(val) : val.toFixed(2)
  const s = String(val)
  return s.length > 500 ? s.slice(0, 500) + '…' : s
}

function tagTypeForField(key, val) {
  const k = String(key).toLowerCase()
  if (typeof val === 'boolean') return val ? 'success' : 'info'
  if (k === 'success' || k === 'executionok' || k === 'passed') {
    return val === true || val === 'true' ? 'success' : 'danger'
  }
  if (k.includes('error') || k.includes('fail') || k === 'severity') return 'danger'
  if (k.includes('warn') || k === 'risklevel') return 'warning'
  if (k.includes('status') || k === 'mode') return 'info'
  return undefined
}

function inferTableColumns(rows) {
  const keys = new Set()
  for (const row of rows.slice(0, 20)) {
    if (!isPlainObject(row)) continue
    Object.keys(row).forEach((k) => keys.add(k))
  }
  const ordered = [...keys]
  const priority = ['path', 'kb', 'mountedOn', 'usePercent', 'pid', 'cpu', 'mem', 'command', 'severity', 'logTime']
  ordered.sort((a, b) => {
    const ia = priority.indexOf(a)
    const ib = priority.indexOf(b)
    if (ia === -1 && ib === -1) return a.localeCompare(b)
    if (ia === -1) return 1
    if (ib === -1) return -1
    return ia - ib
  })
  return ordered.map((prop) => ({ prop, label: fieldLabel(prop), minWidth: prop === 'path' ? 200 : 100 }))
}

function buildTableSection(key, arr, depth) {
  if (!arr.length) return null
  if (arr.every(isPlainObject)) {
    return {
      kind: 'table',
      title: fieldLabel(key),
      columns: inferTableColumns(arr),
      rows: arr.slice(0, 50)
    }
  }
  if (arr.every((x) => typeof x !== 'object')) {
    return {
      kind: 'tags',
      title: fieldLabel(key),
      items: arr.slice(0, 40).map((x) => formatScalar(x))
    }
  }
  if (depth >= 3) {
    return { kind: 'text', title: fieldLabel(key), text: `（${arr.length} 项，层级过深已折叠）` }
  }
  return {
    kind: 'cards',
    title: fieldLabel(key),
    cards: arr.slice(0, 12).map((item, i) => ({
      title: `${fieldLabel(key)} #${i + 1}`,
      view: buildStructuredViewModel(item, depth + 1)
    }))
  }
}

/**
 * @param {*} raw
 * @param {number} depth
 * @returns {{ kind: string, text?: string, tags?: string[], metrics?: object[], fields?: object[], table?: object, sections?: object[] }}
 */
export function buildStructuredViewModel(raw, depth = 0) {
  const val = coerceStructuredInput(raw)

  if (val == null || val === '') {
    return { kind: 'empty' }
  }

  if (typeof val === 'string' || typeof val === 'number' || typeof val === 'boolean') {
    return { kind: 'text', text: formatScalar(val) }
  }

  if (Array.isArray(val)) {
    if (!val.length) return { kind: 'empty' }
    const tableSec = buildTableSection('items', val, depth)
    if (tableSec) return tableSec
    return { kind: 'text', text: formatScalar(val) }
  }

  if (!isPlainObject(val)) {
    return { kind: 'text', text: formatScalar(val) }
  }

  const metrics = []
  const fields = []
  const sections = []

  for (const [key, v] of Object.entries(val)) {
    if (v == null || v === '') continue

    const pct = parsePercentValue(v)
    if (pct != null && (METRIC_KEY_RE.test(key) || key === 'riskScore' || String(v).includes('%'))) {
      metrics.push({
        key,
        label: fieldLabel(key),
        percent: Math.min(100, Math.max(0, pct)),
        status: metricStatus(pct, key),
        hint:
          key === 'riskScore'
            ? `${pct / 10}/10`
            : undefined
      })
      continue
    }

    if (Array.isArray(v) && (TABLE_ARRAY_KEYS.has(key) || v.every(isPlainObject))) {
      const sec = buildTableSection(key, v, depth)
      if (sec) sections.push(sec)
      continue
    }

    if (typeof v === 'object') {
      if (depth >= 3) {
        fields.push({ key, label: fieldLabel(key), value: '（已折叠）', tagType: 'info' })
        continue
      }
      sections.push({
        kind: 'nested',
        title: fieldLabel(key),
        view: buildStructuredViewModel(v, depth + 1)
      })
      continue
    }

    fields.push({
      key,
      label: fieldLabel(key),
      value: formatScalar(v),
      tagType: tagTypeForField(key, v)
    })
  }

  if (metrics.length || fields.length || sections.length) {
    return { kind: 'composite', metrics, fields, sections }
  }

  return { kind: 'empty' }
}

/** 历史列表 / 表格预览：单行摘要，不用 JSON */
export function formatResultPreview(raw, maxLen = 140) {
  const vm = buildStructuredViewModel(raw)
  const parts = []

  const walk = (m) => {
    if (!m || parts.length > 6) return
    if (m.kind === 'text' && m.text) parts.push(m.text)
    if (m.kind === 'composite') {
      for (const f of m.fields || []) {
        parts.push(`${f.label}: ${f.value}`)
      }
      for (const met of m.metrics || []) {
        parts.push(`${met.label}: ${met.percent}%`)
      }
    }
    if (m.kind === 'table' && m.rows?.length) {
      parts.push(`${m.title || '列表'} ${m.rows.length} 条`)
    }
    if (m.kind === 'tags' && m.items?.length) {
      parts.push(`${m.title || '项'}: ${m.items.slice(0, 3).join('、')}`)
    }
    for (const s of m.sections || []) walk(s.view || s)
  }

  walk(vm)
  const text = parts.filter(Boolean).join(' · ') || '—'
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text
}

/** 风险维度对象 → 可读行（用于确认框） */
export function formatRiskDimensions(dimensions) {
  if (!dimensions || typeof dimensions !== 'object') return ''
  return Object.entries(dimensions)
    .map(([k, v]) => `· ${fieldLabel(k)}: ${formatScalar(v)}`)
    .join('\n')
}
