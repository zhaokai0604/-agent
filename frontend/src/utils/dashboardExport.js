import { jsPDF } from 'jspdf'

/**
 * 将多张图表 PNG 与摘要写入 A4 横向 PDF。
 */
export async function exportDashboardPdfDocument ({
  title,
  summaryLine,
  chartImages,
  filename
}) {
  const pdf = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const margin = 36

  pdf.setFont('helvetica', 'bold')
  pdf.setFontSize(16)
  pdf.text(title || '日志分析仪表盘', margin, margin)

  pdf.setFont('helvetica', 'normal')
  pdf.setFontSize(10)
  if (summaryLine) {
    pdf.text(summaryLine, margin, margin + 18)
  }

  const cols = 2
  const chartW = (pageW - margin * 2 - 16) / cols
  const chartH = (pageH - margin * 2 - 50) / 2 - 8
  const top = margin + 36

  const slots = chartImages.filter(Boolean)
  for (let i = 0; i < slots.length; i++) {
    const dataUrl = slots[i]
    const col = i % cols
    const row = Math.floor(i / cols)
    const x = margin + col * (chartW + 16)
    const y = top + row * (chartH + 12)
    try {
      pdf.addImage(dataUrl, 'PNG', x, y, chartW, chartH)
    } catch {
      /* 单图失败不阻断 */
    }
  }

  pdf.setFontSize(8)
  pdf.setTextColor(120, 120, 120)
  pdf.text(`Generated ${new Date().toLocaleString()}`, margin, pageH - 16)

  pdf.save(filename || `dashboard-report-${Date.now()}.pdf`)
}

export function chartToPngDataUrl (chart) {
  if (!chart) return null
  try {
    return chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  } catch {
    return null
  }
}
