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
  OsInsightTool: 'journalctl | ss | lsof',
  PortHealthTool: 'TCP Socket 连通性',
  DockerTool: 'docker ps / inspect',
  CronJobTool: 'crontab -l | schtasks',
  FirewallTool: 'ufw | firewalld | netsh',
  SslCertTool: 'TLS 握手读证书',
  SystemdTool: 'systemctl --failed | status',
  AutonomousOpsTool: '全工具融合：感知→诊断→修复→验证',
  ConfigDriftTool: '配置 checksum 漂移（只读）',
  DiskOpsTool: 'df|hotspots|analyze|clean-temp（读写合一）',
  LogOpsTool: 'analyze|cleanup（读写合一）',
  ServiceOpsTool: 'failed|status|restart（读写合一）',
  ContainerOpsTool: 'list|inspect|restart|stop（读写合一）',
  ProcessOpsTool: 'list|kill（读写合一）'
}

export function getMcpToolCommandHint(toolName) {
  return MCP_TOOL_COMMAND_HINT[toolName] || 'system command'
}

const REAL_WRITE_RE =
  /真实删除|彻底删除|真正删除|不要预览|不要dry-?run|执行删除|真实重启|立即重启|confirm\s*[:=]\s*true/i

/** 用户/参数是否已明确要求真实写入（非预览） */
export function userRequestedRealWrite(parameters, userMessage) {
  const p = parameters || {}
  if (p.confirmDelete || p.confirmRestart || p.confirmKill || p.confirmStop) return true
  if (p.dryRun === false) return true
  const t = (userMessage || '').trim()
  return t.length > 0 && REAL_WRITE_RE.test(t)
}

/** 写类工具在 MCP 二次确认通过后，仅在已请求真实写入时关闭 dryRun */
export function applyMcpWriteConfirmParams(toolName, parameters, userMessage) {
  const p = { ...(parameters || {}) }
  if (!userRequestedRealWrite(p, userMessage)) {
    return p
  }
  const op = (p.operation || '').toString().trim().toLowerCase()
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
  } else if (
    (toolName === 'DockerTool' || toolName === 'ContainerOpsTool') &&
    op === 'restart'
  ) {
    p.dryRun = false
    p.confirmRestart = true
  } else if (
    (toolName === 'DockerTool' || toolName === 'ContainerOpsTool') &&
    op === 'stop'
  ) {
    p.dryRun = false
    p.confirmStop = true
  } else if (
    toolName === 'ProcessOpsTool' ||
    (toolName === 'ProcessTool' && ['kill', 'terminate', 'stop'].includes(op))
  ) {
    p.dryRun = false
    p.confirmKill = true
  } else if (toolName === 'SystemdTool' && op === 'restart') {
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
    OsInsightTool: 'OSI',
    PortHealthTool: 'PRT',
    DockerTool: 'DKR',
    CronJobTool: 'CRN',
    FirewallTool: 'FWL',
    SslCertTool: 'SSL',
    SystemdTool: 'SVC',
    AutonomousOpsTool: 'AOP',
    ConfigDriftTool: 'CFG+',
    DiskOpsTool: 'DOP',
    LogOpsTool: 'LOP',
    ServiceOpsTool: 'SOP',
    ContainerOpsTool: 'COP',
    ProcessOpsTool: 'POP'
  }
  return map[name] || (name ? String(name).replace(/Tool$/, '').slice(0, 3).toUpperCase() : '—')
}

/** 演示页表单：名称须与后端 toolName 一致 */
export const MCP_TOOL_DEFINITIONS = [
  {
    name: 'DiskOpsTool',
    description: '磁盘读写合一（推荐）',
    params: [
      {
        name: 'operation',
        label: '操作',
        placeholder: 'df',
        hint: 'df | hotspots | analyze | clean-temp'
      },
      { name: 'path', label: '路径', placeholder: '/tmp', hint: 'clean-temp' },
      { name: 'days', label: '天数', placeholder: '7', hint: 'clean-temp' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: '写操作默认 true' },
      { name: 'confirmDelete', label: '确认删除', placeholder: 'false', hint: '真实删除须 true' },
      { name: 'rootPath', label: '扫描根', placeholder: '/var/log', hint: 'hotspots/analyze' },
      { name: 'maxDepth', label: '深度', placeholder: '2', hint: 'hotspots' },
      { name: 'topN', label: '条数', placeholder: '15', hint: 'hotspots/analyze' }
    ]
  },
  {
    name: 'LogOpsTool',
    description: '日志读写合一（推荐）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'analyze', hint: 'analyze | cleanup' },
      { name: 'logPath', label: '日志路径', placeholder: '/var/log/syslog', hint: 'analyze' },
      { name: 'lines', label: '行数', placeholder: '100', hint: 'analyze' },
      { name: 'path', label: '清理根路径', placeholder: '/var/log', hint: 'cleanup' },
      { name: 'days', label: '天数', placeholder: '30', hint: 'cleanup' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'cleanup 写操作' },
      { name: 'confirmDelete', label: '确认删除', placeholder: 'false', hint: 'cleanup 真实删除' }
    ]
  },
  {
    name: 'ServiceOpsTool',
    description: '服务读写合一（推荐）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'failed', hint: 'failed | status | restart' },
      { name: 'serviceName', label: '服务名', placeholder: 'nginx', hint: 'status/restart' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'restart' },
      { name: 'confirmRestart', label: '确认重启', placeholder: 'false', hint: 'restart 真实执行' }
    ]
  },
  {
    name: 'ContainerOpsTool',
    description: 'Docker 读写合一（推荐）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'list', hint: 'list | inspect | restart | stop' },
      { name: 'containerName', label: '容器名', placeholder: 'my-app', hint: 'inspect/restart/stop' },
      { name: 'includeStopped', label: '含已停止', placeholder: 'false', hint: 'list' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'restart/stop' },
      { name: 'confirmRestart', label: '确认重启', placeholder: 'false', hint: 'restart' },
      { name: 'confirmStop', label: '确认停止', placeholder: 'false', hint: 'stop' }
    ]
  },
  {
    name: 'ProcessOpsTool',
    description: '进程读写合一（推荐）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'list', hint: 'list | kill' },
      { name: 'minCpu', label: '最小CPU%', placeholder: '5', hint: 'list' },
      { name: 'minMem', label: '最小内存%', placeholder: '5', hint: 'list' },
      { name: 'pid', label: 'PID', placeholder: '1234', hint: 'kill 必填' },
      { name: 'signal', label: '信号', placeholder: 'TERM', hint: 'TERM | KILL' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'kill' },
      { name: 'confirmKill', label: '确认结束', placeholder: 'false', hint: 'kill 真实执行' }
    ]
  },
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
    description: '进程（兼容旧名，建议 ProcessOpsTool）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'list', hint: 'list | kill' },
      { name: 'minCpu', label: '最小CPU%', placeholder: '5.0', hint: 'list' },
      { name: 'minMem', label: '最小内存%', placeholder: '5.0', hint: 'list' },
      { name: 'pid', label: 'PID', placeholder: '1234', hint: 'kill' },
      { name: 'signal', label: '信号', placeholder: 'TERM', hint: 'kill' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'kill' },
      { name: 'confirmKill', label: '确认结束', placeholder: 'false', hint: 'kill' }
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
  },
  {
    name: 'PortHealthTool',
    description: 'TCP 端口连通性探测',
    params: [
      { name: 'host', label: '主机', placeholder: '127.0.0.1', hint: '必填' },
      { name: 'port', label: '端口', placeholder: '8080', hint: '必填，1-65535' },
      { name: 'timeoutMs', label: '超时(ms)', placeholder: '3000', hint: '可选' }
    ]
  },
  {
    name: 'DockerTool',
    description: 'Docker 容器（兼容旧名，建议 ContainerOpsTool）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'list', hint: 'list | inspect | restart | stop' },
      { name: 'includeStopped', label: '含已停止', placeholder: 'false', hint: 'list 时 -a' },
      { name: 'containerName', label: '容器名', placeholder: 'my-app', hint: 'inspect/restart/stop' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'restart/stop' },
      { name: 'confirmRestart', label: '确认重启', placeholder: 'false', hint: 'restart' },
      { name: 'confirmStop', label: '确认停止', placeholder: 'false', hint: 'stop' }
    ]
  },
  {
    name: 'CronJobTool',
    description: '计划任务只读列表',
    params: [
      { name: 'scope', label: '范围', placeholder: 'user', hint: 'user | system' }
    ]
  },
  {
    name: 'FirewallTool',
    description: '防火墙状态检查',
    params: []
  },
  {
    name: 'SslCertTool',
    description: 'TLS 证书有效期检查',
    params: [
      { name: 'host', label: '主机', placeholder: 'example.com', hint: '必填' },
      { name: 'port', label: '端口', placeholder: '443', hint: '默认 443' },
      { name: 'timeoutMs', label: '超时(ms)', placeholder: '5000', hint: '可选' }
    ]
  },
  {
    name: 'SystemdTool',
    description: 'systemd 服务（兼容旧名，建议 ServiceOpsTool）',
    params: [
      { name: 'operation', label: '操作', placeholder: 'failed', hint: 'failed | status | restart' },
      { name: 'serviceName', label: '服务名', placeholder: 'nginx', hint: 'status/restart' },
      { name: 'dryRun', label: 'Dry Run', placeholder: 'true', hint: 'restart' },
      { name: 'confirmRestart', label: '确认重启', placeholder: 'false', hint: 'restart' }
    ]
  },
  {
    name: 'AutonomousOpsTool',
    description: '一键自主运维（融合全部工具）',
    params: [
      { name: 'userIntent', label: '意图', placeholder: '自动运维', hint: '可选' },
      { name: 'forceRemediate', label: '强制修复', placeholder: 'false', hint: 'true=策略内直接执行写操作' }
    ]
  },
  {
    name: 'ConfigDriftTool',
    description: '配置文件漂移检测（只读）',
    params: [
      { name: 'configPath', label: '配置文件', placeholder: '/etc/nginx/nginx.conf', hint: '须在白名单内' }
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
  for (const t of serverList) {
    if (t?.name && t.commandHint) {
      MCP_TOOL_COMMAND_HINT[t.name] = t.commandHint
    }
  }
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
