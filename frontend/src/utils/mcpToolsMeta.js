export const MCP_TOOL_COMMAND_HINT = {
  DiskTool: 'df -h',
  DiskInsightTool: 'du -xk --max-depth=N',
  DiskAnalyzeTool: 'df + du hotspot scan',
  ProcessTool: 'ps aux --sort=-%cpu',
  SystemLoadTool: 'top -bn1 + /proc/loadavg',
  LogAnalysisTool: 'log sample + Drain-Plus',
  CleanTempTool: 'find temp dir + optional rm',
  LogCleanupTool: 'find old logs + optional rm',
  ServiceRestartTool: 'systemctl restart',
  ConfigCheckTool: 'config syntax check',
  NetworkTool: 'ping / traceroute',
  PrivilegeTool: 'permission probe',
  OsInsightTool: 'journalctl | ss | lsof',
  PortHealthTool: 'tcp connect probe',
  DockerTool: 'docker ps / inspect',
  CronJobTool: 'crontab -l | schtasks',
  FirewallTool: 'ufw | firewalld | netsh',
  SslCertTool: 'TLS certificate inspect',
  SystemdTool: 'systemctl --failed | status',
  AutonomousOpsTool: 'perceive -> diagnose -> remediate -> verify',
  ConfigDriftTool: 'config checksum drift check',
  DiskOpsTool: 'df | hotspots | clean-temp',
  LogOpsTool: 'analyze | cleanup',
  ServiceOpsTool: 'failed | status | restart',
  ContainerOpsTool: 'list | inspect | restart | stop',
  ProcessOpsTool: 'list | kill'
}

export function getMcpToolCommandHint(toolName) {
  return MCP_TOOL_COMMAND_HINT[toolName] || 'system command'
}

const REAL_WRITE_RE =
  /真实删除|彻底删除|真正删除|不要预览|不要dry-?run|执行删除|真实重启|立即重启|确认执行|confirm\s*[:=]\s*true/i

export function userRequestedRealWrite(parameters, userMessage) {
  const p = parameters || {}
  if (p.confirmDelete || p.confirmRestart || p.confirmKill || p.confirmStop) return true
  if (p.dryRun === false) return true
  const text = (userMessage || '').trim()
  return text.length > 0 && REAL_WRITE_RE.test(text)
}

export function applyMcpWriteConfirmParams(toolName, parameters, userMessage) {
  const p = { ...(parameters || {}) }
  if (!userRequestedRealWrite(p, userMessage)) {
    return p
  }

  const op = String(p.operation || '').trim().toLowerCase()
  if (toolName === 'CleanTempTool' || toolName === 'LogCleanupTool') {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'DiskOpsTool' && ['clean-temp', 'clean', 'cleanup'].includes(op)) {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'LogOpsTool' && ['cleanup', 'clean', 'prune'].includes(op)) {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'ServiceRestartTool' || (toolName === 'ServiceOpsTool' && op === 'restart')) {
    p.dryRun = false
    p.confirmRestart = true
  } else if ((toolName === 'DockerTool' || toolName === 'ContainerOpsTool') && op === 'restart') {
    p.dryRun = false
    p.confirmRestart = true
  } else if ((toolName === 'DockerTool' || toolName === 'ContainerOpsTool') && op === 'stop') {
    p.dryRun = false
    p.confirmStop = true
  } else if (toolName === 'ProcessOpsTool' || (toolName === 'ProcessTool' && ['kill', 'terminate', 'stop'].includes(op))) {
    p.dryRun = false
    p.confirmKill = true
  } else if (toolName === 'SystemdTool' && op === 'restart') {
    p.dryRun = false
    p.confirmRestart = true
  }

  return p
}

const BADGE_MAP = {
  DiskTool: 'DSK',
  DiskInsightTool: 'DIR',
  DiskAnalyzeTool: 'DIA',
  ProcessTool: 'PRC',
  SystemLoadTool: 'LOD',
  LogAnalysisTool: 'LOG',
  CleanTempTool: 'TMP',
  LogCleanupTool: 'LGC',
  ServiceRestartTool: 'RST',
  ConfigCheckTool: 'CFG',
  NetworkTool: 'NET',
  PrivilegeTool: 'PRV',
  OsInsightTool: 'OSI',
  PortHealthTool: 'PRT',
  DockerTool: 'DKR',
  CronJobTool: 'CRN',
  FirewallTool: 'FWL',
  SslCertTool: 'SSL',
  SystemdTool: 'SVC',
  AutonomousOpsTool: 'AOP',
  ConfigDriftTool: 'DRF',
  DiskOpsTool: 'DOP',
  LogOpsTool: 'LOP',
  ServiceOpsTool: 'SOP',
  ContainerOpsTool: 'COP',
  ProcessOpsTool: 'POP'
}

export function mcpToolBadge(name) {
  if (BADGE_MAP[name]) return BADGE_MAP[name]
  return String(name || 'MCP').slice(0, 3).toUpperCase()
}

function param(name, label, placeholder, hint, type = 'text') {
  return { name, label, placeholder, hint, type }
}

export const MCP_TOOL_DEFINITIONS = [
  { name: 'DiskTool', description: '磁盘分区使用率巡检', params: [] },
  { name: 'DiskInsightTool', description: '目录磁盘热点扫描', params: [param('path', '目录', '/var/log', '白名单路径'), param('maxDepth', '深度', '2', '可选')] },
  { name: 'DiskAnalyzeTool', description: '磁盘总览与热点联合分析', params: [param('path', '目录', '/var/log', '可选'), param('topN', '热点数量', '10', '可选')] },
  { name: 'ProcessTool', description: '进程资源占用查询或结束预览', params: [param('operation', '操作', 'list', 'list | kill'), param('pid', 'PID', '1234', 'kill 时必填'), param('signal', '信号', 'TERM', 'kill 时可选'), param('dryRun', 'Dry Run', 'true', 'kill 时建议开启'), param('confirmKill', '确认结束', 'false', '真实结束时使用')] },
  { name: 'SystemLoadTool', description: '系统负载与资源概览', params: [] },
  { name: 'LogAnalysisTool', description: '日志采样与异常模式分析', params: [param('logPath', '日志路径', '/var/log/syslog', '可选'), param('lines', '分析行数', '100', '可选')] },
  { name: 'CleanTempTool', description: '临时目录清理，默认预览', params: [param('path', '路径', '/tmp', '白名单目录'), param('days', '早于天数', '7', '正整数'), param('dryRun', 'Dry Run', 'true', 'false 时需 confirmDelete=true'), param('confirmDelete', '确认删除', 'false', '真实删除时使用')] },
  { name: 'LogCleanupTool', description: '日志目录旧文件清理，默认预览', params: [param('path', '日志路径', '/var/log', '白名单目录'), param('days', '早于天数', '30', '正整数'), param('dryRun', 'Dry Run', 'true', 'false 时需 confirmDelete=true'), param('confirmDelete', '确认删除', 'false', '真实删除时使用')] },
  { name: 'ServiceRestartTool', description: '白名单服务重启，默认预览', params: [param('serviceName', '服务单元', 'nginx', '需在 allowlist 中'), param('dryRun', 'Dry Run', 'true', 'false 时需 confirmRestart=true'), param('confirmRestart', '确认重启', 'false', '真实重启时使用')] },
  { name: 'ConfigCheckTool', description: '配置文件语法检查', params: [param('configPath', '配置文件', '/etc/nginx/nginx.conf', '必填')] },
  { name: 'NetworkTool', description: '网络连通性诊断', params: [param('target', '目标', '留空时使用系统配置默认目标', '可选'), param('type', '类型', 'ping', 'ping | traceroute'), param('count', '次数', '4', '可选')] },
  { name: 'PrivilegeTool', description: '权限验证', params: [param('resource', '资源路径', '/usr/bin/nginx', '必填'), param('action', '操作', 'execute', 'read/write/execute')] },
  { name: 'OsInsightTool', description: 'journalctl / ss / lsof 查询', params: [param('operation', '操作', 'journal', 'journal | ss | lsof'), param('sinceMinutes', '最近分钟', '30', 'journal 可选'), param('maxLines', '最大行数', '200', 'journal 可选'), param('pid', 'PID', '1', 'lsof 可选')] },
  { name: 'PortHealthTool', description: 'TCP 端口探测', params: [param('host', '主机', '127.0.0.1', '必填'), param('port', '端口', '8080', '1-65535'), param('timeoutMs', '超时(ms)', '3000', '可选')] },
  { name: 'DockerTool', description: 'Docker 容器管理', params: [param('operation', '操作', 'list', 'list | inspect | restart | stop'), param('includeStopped', '包含已停止', 'false', 'list 可选'), param('containerName', '容器名', 'my-app', 'inspect/restart/stop'), param('dryRun', 'Dry Run', 'true', 'restart/stop 可选'), param('confirmRestart', '确认重启', 'false', 'restart 时使用'), param('confirmStop', '确认停止', 'false', 'stop 时使用')] },
  { name: 'CronJobTool', description: '计划任务只读列表', params: [param('scope', '范围', 'user', 'user | system')] },
  { name: 'FirewallTool', description: '防火墙状态检查', params: [] },
  { name: 'SslCertTool', description: 'TLS 证书有效期检查', params: [param('host', '主机', 'example.com', '必填'), param('port', '端口', '443', '默认 443'), param('timeoutMs', '超时(ms)', '5000', '可选')] },
  { name: 'SystemdTool', description: 'systemd 服务状态或重启预览', params: [param('operation', '操作', 'failed', 'failed | status | restart'), param('serviceName', '服务名', 'nginx', 'status/restart'), param('dryRun', 'Dry Run', 'true', 'restart 可选'), param('confirmRestart', '确认重启', 'false', 'restart 时使用')] },
  { name: 'AutonomousOpsTool', description: '一键自主运维闭环', params: [param('userIntent', '意图', '自动运维', '可选'), param('forceRemediate', '强制修复', 'false', 'true 时允许策略内写操作')] },
  { name: 'ConfigDriftTool', description: '配置漂移只读检查', params: [param('configPath', '配置文件', '/etc/nginx/nginx.conf', '白名单路径')] },
  { name: 'DiskOpsTool', description: '磁盘组合工具', params: [param('operation', '操作', 'overview', 'overview | hotspots | clean-temp'), param('path', '路径', '/var/log', '可选'), param('dryRun', 'Dry Run', 'true', 'clean-temp 时使用'), param('confirmDelete', '确认删除', 'false', '真实删除时使用')] },
  { name: 'LogOpsTool', description: '日志组合工具', params: [param('operation', '操作', 'analyze', 'analyze | cleanup'), param('path', '路径', '/var/log', '可选'), param('days', '早于天数', '30', 'cleanup 时可选'), param('dryRun', 'Dry Run', 'true', 'cleanup 时使用'), param('confirmDelete', '确认删除', 'false', '真实删除时使用')] },
  { name: 'ServiceOpsTool', description: '服务组合工具', params: [param('operation', '操作', 'failed', 'failed | status | restart'), param('serviceName', '服务名', 'nginx', 'status/restart'), param('dryRun', 'Dry Run', 'true', 'restart 时使用'), param('confirmRestart', '确认重启', 'false', '真实重启时使用')] },
  { name: 'ContainerOpsTool', description: '容器组合工具', params: [param('operation', '操作', 'list', 'list | inspect | restart | stop'), param('containerName', '容器名', 'my-app', 'inspect/restart/stop'), param('dryRun', 'Dry Run', 'true', 'restart/stop 可选'), param('confirmRestart', '确认重启', 'false', 'restart 时使用'), param('confirmStop', '确认停止', 'false', 'stop 时使用')] },
  { name: 'ProcessOpsTool', description: '进程组合工具', params: [param('operation', '操作', 'list', 'list | kill'), param('pid', 'PID', '1234', 'kill 时必填'), param('signal', '信号', 'TERM', 'kill 时可选'), param('dryRun', 'Dry Run', 'true', 'kill 时建议开启'), param('confirmKill', '确认结束', 'false', '真实结束时使用')] }
]

export function mergeToolRegistry(serverResponse) {
  const serverList = Array.isArray(serverResponse?.data)
    ? serverResponse.data
    : Array.isArray(serverResponse)
      ? serverResponse
      : []

  for (const tool of serverList) {
    if (tool?.name && tool.commandHint) {
      MCP_TOOL_COMMAND_HINT[tool.name] = tool.commandHint
    }
  }

  const byServer = Object.fromEntries(serverList.filter(tool => tool?.name).map(tool => [tool.name, tool]))
  const known = new Set(MCP_TOOL_DEFINITIONS.map(tool => tool.name))

  const merged = MCP_TOOL_DEFINITIONS.map(definition => {
    const server = byServer[definition.name]
    const online = serverList.length === 0 || Boolean(server)
    return {
      ...definition,
      summary: server?.summary || definition.summary || definition.description,
      description: server?.description || definition.description,
      endpoint: server?.endpoint,
      status: online ? 'online' : 'offline',
      statusText: online ? '在线' : '未在服务端注册'
    }
  })

  for (const tool of serverList) {
    if (!tool?.name || known.has(tool.name)) continue
    merged.push({
      name: tool.name,
      summary: tool.summary || tool.description || '服务端注册工具',
      description: tool.description || tool.summary || tool.name,
      params: [],
      endpoint: tool.endpoint,
      status: 'online',
      statusText: '在线'
    })
  }

  return merged
}
