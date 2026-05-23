/** 本地日历日 YYYY-MM-DD（避免 toISOString 时区偏移） */
export function formatLocalDateKey(date) {
  const d = date instanceof Date ? date : new Date(date)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 展示 createTime：兼容数组 / ISO / 普通字符串 */
export function formatDateTime(val) {
  if (!val) return '-'
  if (Array.isArray(val)) {
    const [y, m, d, h, min, s] = val
    const pad = (n) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s || 0)}`
  }
  if (typeof val === 'string' && val.includes('T')) {
    return val.replace('T', ' ').substring(0, 19)
  }
  return String(val)
}
