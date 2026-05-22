/**
 * MCP 工具元数据：与后端 McpExecuteController / Spring AI Bean 名称对齐。
 * 供「工具控制台」与「工作台」共用，避免前端散落硬编码。
 */

export const MCP_TOOL_COMMAND_HINT = {
  DiskTool: 'df -h',
  DiskInsightTool: 'du -xk --max-depth=N（白名单路径）',
  DiskAnalyzeTool: 'df + 可选 du 热点（白名单）',
  ProcessTool: 'ps aux --sort=-%cpu',
  SystemLoadTool: 'top -bn1 + /proc/loadavg',
  LogAnalysisTool: '日志采样 + Drain-Plus',
  CleanTempTool: 'find 白名单临时目录 + 可选 rm',
  LogCleanupTool: 'find /var/log 类目录 + 可选 rm（白名单）',
  ServiceRestartTool: 'systemctl restart（白名单单元）',
  ConfigCheckTool: '配置语法探测（因类型而异）',
  NetworkTool: 'ping / traceroute',
  PrivilegeTool: '权限探测',
  OsInsightTool: 'journalctl | ss | lsof'
}

export function getMcpToolCommandHint(toolName) {
  return MCP_TOOL_COMMAND_HINT[toolName] || 'system command'
}

/** 写类工具在 MCP 二次确认通过后，须同时关闭预览并打开确认位 */
export function applyMcpWriteConfirmParams(toolName, parameters) {
  const p = { ...(parameters || {}) }
  if (toolName === 'CleanTempTool' || toolName === 'LogCleanupTool') {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'ServiceRestartTool') {
    p.dryRun = false
    p.confirmRestart = true
  }
  return p
}

/** 工具卡片角标（替代 emoji，保持控制台专业观感） */
export function mcpToolBadge(name) {
  const map = {
    DiskTool: 'DSK',
    DiskInsightTool: 'DIR',
    DiskAnalyzeTool: 'DIA',
    ProcessTool: 'PRC',
    SystemLoadTool: 'LOD',
    LogAnalysisTool: 'LOG',
    CleanTempTool: 'CLN',
    LogCleanupTool: 'LGC',
    ServiceRestartTool: 'RST',
    ConfigCheckTool: 'CFG',
    NetworkTool: 'NET',
    PrivilegeTool: 'PRV',
    OsInsightTool: 'OSI'
  }
  return map[name] || (name ? String(name).replace(/Tool$/, '').slice(0, 3).toUpperCase() : '—')
}

/** 演示页表单：名称须与后端 toolName 一致 */
export const MCP_TOOL_DEFINITIONS = [
  {
    name: 'DiskTool',
    description: '检查磁盘使用情况',
    params: []
  },
  {
    name: 'DiskInsightTool',
    description: '目录占用热点：du 排序（白名单路径）',
    params: [
      { name: 'rootPath', label: '扫描根路径', placeholder: '/tmp', hint: '如 /tmp、/var/log' },
      { name: 'maxDepth', label: '深度', placeholder: '2', hint: '1–4，默认 2' },
      { name: 'topN', label: '前 N 条', placeholder: '15', hint: '5–40' }
    ]
  },
  {
    name: 'DiskAnalyzeTool',
    description: '磁盘压力综合诊断（df + 可选热点）',
    params: [
      { name: 'rootPath', label: '热点根路径', placeholder: '/var/log', hint: '可选；热点扫描时用' },
      {
        name: 'includeHotspots',
        label: '热点扫描',
        placeholder: 'true',
        hint: 'true/false；true 时执行 du 排序'
      },
      { name: 'topN', label: '热点条数', placeholder: '12', hint: '3–40' }
    ]
  },
  {
    name: 'ProcessTool',
    description: '列出系统进程，支持 CPU/内存过滤',
    params: [
      { name: 'minCpu', label: '最小CPU%', placeholder: '5.0', hint: '可选' },
      { name: 'minMem', label: '最小内存%', placeholder: '5.0', hint: '可选' }
    ]
  },
  {
    name: 'SystemLoadTool',
    description: '系统负载与资源概况',
    params: []
  },
  {
    name: 'LogAnalysisTool',
    description: '日志分析与 Drain-Plus',
    params: [
      { name: 'logPath', label: '日志路径', placeholder: '/var/log/syslog', hint: '可选' },
      { name: 'lines', label: '分析行数', placeholder: '100', hint: '可选' }
    ]
  },
  {
    name: 'CleanTempTool',
    description: '临时目录清理（默认预览）',
    params: [
      { name: 'path', label: '路径', placeholder: '/tmp', hint: '白名单目录' },
      { name: 'days', label: '早于 N 天', placeholder: '7', hint: '正整数' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'false 删除须 confirmDelete=true' },
      { name: 'confirmDelete', label: '确认删除', placeholder: 'false', hint: '真实删除须 true' }
    ]
  },
  {
    name: 'LogCleanupTool',
    description: '日志目录陈旧文件清理（默认预览；路径白名单）',
    params: [
      { name: 'path', label: '日志根路径', placeholder: '/var/log', hint: 'Linux 默认 /var/log' },
      { name: 'days', label: '早于 N 天', placeholder: '30', hint: '未修改超过 N 天' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'false 须 confirmDelete=true' },
      { name: 'confirmDelete', label: '确认删除', placeholder: 'false', hint: '真实删除须 true' }
    ]
  },
  {
    name: 'ServiceRestartTool',
    description: '白名单服务重启（默认预览）',
    params: [
      { name: 'serviceName', label: '服务单元', placeholder: 'nginx', hint: '须在 agent.service-restart.allowlist' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'false 须 confirmRestart=true' },
      { name: 'confirmRestart', label: '确认重启', placeholder: 'false', hint: '真实重启须 true' }
    ]
  },
  {
    name: 'ConfigCheckTool',
    description: '配置文件语法检查',
    params: [{ name: 'configPath', label: '配置文件', placeholder: '/etc/nginx/nginx.conf', hint: '必填' }]
  },
  {
    name: 'NetworkTool',
    description: 'ping / traceroute',
    params: [
      { name: 'target', label: '目标', placeholder: '8.8.8.8', hint: '必填' },
      { name: 'type', label: '类型', placeholder: 'ping', hint: 'ping | traceroute' },
      { name: 'count', label: '次数', placeholder: '4', hint: '可选' }
    ]
  },
  {
    name: 'PrivilegeTool',
    description: '权限验证',
    params: [
      { name: 'resource', label: '资源路径', placeholder: '/usr/bin/nginx', hint: '必填' },
      { name: 'action', label: '操作', placeholder: 'execute', hint: 'read/write/execute' }
    ]
  },
  {
    name: 'OsInsightTool',
    description: 'journalctl / ss / lsof',
    params: [
      { name: 'operation', label: '操作', placeholder: 'journal', hint: 'journal | ss | lsof' },
      { name: 'sinceMinutes', label: '最近分钟', placeholder: '30', hint: 'journal' },
      { name: 'maxLines', label: '最大行数', placeholder: '200', hint: 'journal' },
      { name: 'pid', label: 'PID', placeholder: '1', hint: 'lsof' }
    ]
  }
]

/**
 * 将服务端 /api/mcp/tools 与本地定义合并：标注在线/缺失。
 */
export function mergeToolRegistry(serverResponse) {
  const serverList = Array.isArray(serverResponse?.data)
    ? serverResponse.data
    : Array.isArray(serverResponse)
      ? serverResponse
      : []
  const serverNames = new Set(serverList.map((t) => t?.name).filter(Boolean))
  const byServer = Object.fromEntries(serverList.map((t) => [t.name, t]))

  return MCP_TOOL_DEFINITIONS.map((def) => {
    const s = byServer[def.name]
    const online = serverNames.size === 0 || serverNames.has(def.name)
    return {
      ...def,
      summary: s?.summary || def.summary,
      description: s?.description || def.description,
      endpoint: s?.endpoint,
      status: online ? 'online' : 'offline',
      statusText: online ? '在线' : '未在服务端注册'
    }
  })
}
