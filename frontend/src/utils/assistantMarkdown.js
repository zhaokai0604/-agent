import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false
})

function escapeHtml (s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function isTableSeparator (line) {
  return /^\s*\|?[\s:-]+\|[\s|:-]*$/.test(line.trim())
}

function isTableRow (line) {
  const t = line.trim()
  return t.includes('|') && !t.startsWith('```')
}

function parseCells (line) {
  let row = line.trim()
  if (row.startsWith('|')) row = row.slice(1)
  if (row.endsWith('|')) row = row.slice(0, -1)
  return row.split('|').map((c) => c.trim())
}

/** 将 GFM 表格块转为 HTML，markdown-it 默认不支持 pipe table */
function convertGfmTables (text) {
  const lines = text.split('\n')
  const out = []
  let i = 0
  while (i < lines.length) {
    if (isTableRow(lines[i]) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headerCells = parseCells(lines[i])
      i += 2
      const bodyRows = []
      while (i < lines.length && isTableRow(lines[i])) {
        bodyRows.push(parseCells(lines[i]))
        i++
      }
      let html = '<table><thead><tr>'
      for (const cell of headerCells) {
        html += `<th>${escapeHtml(cell)}</th>`
      }
      html += '</tr></thead><tbody>'
      for (const row of bodyRows) {
        html += '<tr>'
        for (let c = 0; c < headerCells.length; c++) {
          html += `<td>${escapeHtml(row[c] ?? '')}</td>`
        }
        html += '</tr>'
      }
      html += '</tbody></table>'
      out.push(html)
    } else {
      out.push(lines[i])
      i++
    }
  }
  return out.join('\n')
}

/** 标题前补空行，避免与上文粘连 */
function normalizeHeadings (text) {
  return text.replace(/([^\n])\n(#{1,6}\s)/g, '$1\n\n$2')
}

export function renderAssistantMarkdown (raw) {
  if (raw == null || raw === '') return ''
  let text = String(raw)
  text = normalizeHeadings(text)
  const withTables = convertGfmTables(text)
  return md.render(withTables)
}
