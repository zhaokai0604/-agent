function parseProperties(text) {
  const result = {}
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) {
      continue
    }
    const index = line.indexOf('=')
    if (index <= 0) {
      continue
    }
    const key = line.slice(0, index).trim()
    const value = line.slice(index + 1).trim()
    result[key] = value
  }
  return result
}

const DEFAULT_CONFIG_PATH = __ENV.PERF_CONFIG || 'perf/config/env-dev.properties'
const rawProperties = open(DEFAULT_CONFIG_PATH)
const props = parseProperties(rawProperties)

export function getProp(key, fallback = '') {
  if (Object.prototype.hasOwnProperty.call(props, key) && props[key] !== '') {
    return props[key]
  }
  return fallback
}

export function getNumber(key, fallback) {
  const raw = getProp(key, '')
  if (raw === '') {
    return fallback
  }
  const value = Number(raw)
  return Number.isFinite(value) ? value : fallback
}

export function buildBaseUrl(surface = 'business') {
  const protocol = getProp('protocol', 'http')
  const host = getProp('host', '127.0.0.1')
  const port = surface === 'management'
    ? getProp('management.port', '8089')
    : getProp('business.port', '8088')
  const contextPath = getProp('context.path', '/award-log')
  return `${protocol}://${host}:${port}${contextPath}`
}

export function getAllowedOrigin() {
  return getProp('allowed.origin', 'http://127.0.0.1:5173')
}

export function getConfigSnapshot() {
  return { ...props }
}
