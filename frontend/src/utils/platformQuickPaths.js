/** 按后端 OS 或浏览器 UA 返回本机可用的路径与话术 */
export function detectWindows(platformInfo) {
  const os = String(platformInfo?.platform?.osName || platformInfo?.platform?.os || '').toLowerCase()
  if (os.includes('win')) return true
  if (typeof navigator !== 'undefined' && /windows/i.test(navigator.userAgent)) return true
  return false
}

export function resolveOpsPaths(platformInfo) {
  const win = detectWindows(platformInfo)
  if (win) {
    return {
      win: true,
      tempPath: 'C:/Users/Administrator/AppData/Local/Temp',
      logPath: 'logs',
      hotspotPath: 'C:/Users/Administrator/AppData/Local/Temp',
      journalLabel: 'Windows 事件日志',
      journalCmd: '分析 Windows 系统最近错误日志',
      logCleanupCmd: '预览清理 logs 目录中 30 天前的旧日志文件',
      hotspotCmd: '扫描 C:/Users/Administrator/AppData/Local/Temp 磁盘占用热点'
    }
  }
  return {
    win: false,
    tempPath: '/tmp',
    logPath: '/var/log',
    hotspotPath: '/tmp',
    journalLabel: 'journalctl',
    journalCmd: '查看 journalctl 最近30分钟日志',
    logCleanupCmd: '预览清理 /var/log 中 30 天前的旧日志文件',
    hotspotCmd: '扫描 /tmp 磁盘占用热点'
  }
}

export function buildWelcomeQuickChips(platformInfo) {
  const p = resolveOpsPaths(platformInfo)
  return [
    { label: '全面检查', icon: 'DataAnalysis', cmd: '帮我全面检查系统状态，发现问题并给出修复计划' },
    { label: '磁盘检查', icon: 'Coin', cmd: '检查磁盘使用情况' },
    { label: '系统负载', icon: 'TrendCharts', cmd: '查看系统负载' },
    { label: '最近日志', icon: 'Document', cmd: p.journalCmd },
    { label: '临时清理预览', icon: 'Delete', cmd: `清理 7 天前的临时文件（路径 ${p.tempPath}）` },
    { label: '网络诊断', icon: 'Link', cmd: '检查网络连通性' }
  ]
}
