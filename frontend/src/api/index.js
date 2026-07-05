import axios from 'axios'
import { ElMessage } from 'element-plus'
import { formatApiError } from '../utils/apiError.js'

/** 生产/同源部署：构建或 .env 中设置 VITE_AWARD_LOG_BASE_URL（须含 /award-log 后缀，无末尾斜杠） */
const viteAwardBase =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_BASE_URL
    ? String(import.meta.env.VITE_AWARD_LOG_BASE_URL).trim().replace(/\/$/, '')
    : ''

/** 管理面可显式指向单独端口；不填时按当前 host + 管理端口推导 */
const viteManagementBase =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_MANAGEMENT_BASE_URL
    ? String(import.meta.env.VITE_AWARD_LOG_MANAGEMENT_BASE_URL).trim().replace(/\/$/, '')
    : ''

const viteManagementPort =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_MANAGEMENT_PORT
    ? String(import.meta.env.VITE_AWARD_LOG_MANAGEMENT_PORT).trim()
    : ''

/** 解析 API 基址：dev 始终与当前页同源（走 Vite 代理）；生产用 VITE_AWARD_LOG_BASE_URL */
function buildRuntimeBaseUrl() {
  if (typeof window !== 'undefined' && import.meta.env?.DEV) {
    const { protocol, hostname, port } = window.location
    const portPart = port ? `:${port}` : ''
    return `${protocol}//${hostname}${portPart}/award-log`
  }
  if (viteAwardBase) {
    return viteAwardBase.replace(/\/$/, '')
  }
  if (typeof window === 'undefined') {
    return 'http://localhost:8088/award-log'
  }
  const { protocol, hostname, port } = window.location
  if (port === '8088') {
    return `${protocol}//${hostname}:8088/award-log`
  }
  const portPart = port ? `:${port}` : ''
  return `${protocol}//${hostname}${portPart}/award-log`
}

function buildManagementBaseUrl() {
  if (viteManagementBase) {
    return viteManagementBase
  }
  if (viteManagementPort) {
    if (typeof window === 'undefined') {
      return `http://localhost:${viteManagementPort}/award-log`
    }
    const { protocol, hostname } = window.location
    return `${protocol}//${hostname}:${viteManagementPort}/award-log`
  }
  // dev / prod-kylin：管理面与业务面同走 Vite 代理或 Nginx → /award-log（默认单端口 8088）
  return buildRuntimeBaseUrl()
}

const API_TIMEOUT_MS = (() => {
  const raw = typeof import.meta !== 'undefined' ? import.meta.env?.VITE_API_TIMEOUT_MS : undefined
  const n = parseInt(String(raw ?? '600000'), 10)
  return Number.isFinite(n) && n >= 30000 ? n : 600000
})()

/** fetch 流式请求须与 axios 一致带头，否则 SessionCsrfGuardFilter 在非 localhost 来源下返回 403 */
const FETCH_JSON_HEADERS = {
  'Content-Type': 'application/json',
  'X-Requested-With': 'XMLHttpRequest'
}

function createClient(baseUrlResolver) {
  const client = axios.create({
    timeout: API_TIMEOUT_MS,
    withCredentials: true,
    headers: {
      'X-Requested-With': 'XMLHttpRequest'
    }
  })
  client.interceptors.request.use((config) => {
    config.baseURL = typeof baseUrlResolver === 'function' ? baseUrlResolver() : baseUrlResolver
    return config
  })
  return client
}

const request = createClient(buildRuntimeBaseUrl)
const managementRequest = createClient(buildManagementBaseUrl)

const MANAGEMENT_ACCESS_PATHS = [
  '/admin/',
  '/api/mcp/',
  '/api/assistant/',
  '/api/audit/',
  '/api/security/',
  '/api/system-config/',
  '/api/agent/path-policy',
  '/api/runbook/',
  '/api/ops/',
  '/api/ops-',
  '/api/ops-trace/',
  '/api/performance/',
  '/api/collector/',
  '/api/kafka/',
  '/api/v1/model/',
  '/api/platform/info',
  '/api/platform/readiness',
  '/api/platform/backend-probe'
]

const BUSINESS_ACCESS_HINTS = [
  '/log/',
  '/api/profile/',
  '/api/alarm/',
  '/api/alarm-rule/',
  '/api/elasticsearch/',
  '/api/v1/knowledge/',
  '/api/decision-feedback/',
  '/api/platform/acceptance'
]

function isManagementPath(url = '') {
  return MANAGEMENT_ACCESS_PATHS.some(prefix => url.startsWith(prefix))
}

function isBusinessHint(url = '') {
  return BUSINESS_ACCESS_HINTS.some(prefix => url.startsWith(prefix))
}

function buildApiError(message, data) {
  const wrapped = new Error(message || '请求失败')
  wrapped.securityCode = data?.securityCode
  wrapped.raw = data
  return wrapped
}

function attachInterceptors(client, surface) {
  client.interceptors.response.use(
    res => {
      if (res.config?.responseType === 'blob') {
        return res.data
      }
      const reqUrl = String(res.config?.url || '')
      // MCP 工具接口返回 Spring Map JSON（success / needConfirm / error），无统一 { code, data }
      if (reqUrl.includes('/mcp/')) {
        return res.data
      }
      const { code, data, message } = res.data
      if (code === 200) return data
      const errMsg = formatApiError({ response: { data: res.data } }) || message || '请求失败'
      if (code === 401) {
        localStorage.removeItem('user')
        ElMessage.error(errMsg)
        setTimeout(() => window.location.reload(), 300)
        return Promise.reject(buildApiError(errMsg, res.data))
      }
      ElMessage.error(errMsg)
      return Promise.reject(buildApiError(errMsg, res.data))
    },
    error => {
      const status = error.response?.status
      const securityCode = error.response?.data?.securityCode
      const reqUrl = String(error.config?.url || '')
      if (status === 403 && securityCode === 'MANAGEMENT_PORT_REQUIRED' && surface === 'business' && isManagementPath(reqUrl)) {
        return managementRequest.request(error.config)
      }
      if ((status === 403 || status === 404) && securityCode === 'BUSINESS_PORT_REQUIRED' && surface === 'management' && isBusinessHint(reqUrl)) {
        return request.request(error.config)
      }
      const errMsg = formatApiError(error)
      if (status === 401) {
        localStorage.removeItem('user')
        ElMessage.error('登录已过期，请重新登录')
        setTimeout(() => window.location.reload(), 300)
        return Promise.reject(buildApiError(errMsg, error.response?.data))
      }
      ElMessage.error(errMsg)
      return Promise.reject(buildApiError(errMsg, error.response?.data))
    }
  )
}

attachInterceptors(request, 'business')
attachInterceptors(managementRequest, 'management')

export { formatApiError }

export const uploadLog = (formData) => request.post('/log/upload', formData)

export const uploadLogs = (formData) => request.post('/log/upload/multi', formData)

export const getTaskStatus = (taskId) => request.get(`/log/task/${taskId}`)
export const getReport = (taskId) => request.get(`/log/report/${taskId}`)
export const getHistory = (pageNum = 1, pageSize = 10, filters = {}) => {
  const params = { pageNum, pageSize }
  for (const [k, v] of Object.entries(filters || {})) {
    if (v != null && String(v).trim() !== '') {
      params[k] = typeof v === 'string' ? v.trim() : v
    }
  }
  return request.get('/log/history', { params })
}
export const performDiagnosis = (taskId) => request.post(`/log/diagnose/${taskId}`)
export const pauseTask = (taskId) => request.post(`/log/pause/${taskId}`)
export const resumeTask = (taskId) => request.post(`/log/resume/${taskId}`)
export const cancelTask = (taskId) => request.post(`/log/cancel/${taskId}`)
export const deleteTask = (taskId) => request.delete(`/log/delete/${taskId}`)
export const downloadReport = (taskId, type) =>
  request.get(`/log/download/${encodeURIComponent(taskId)}/${encodeURIComponent(type)}`, { responseType: 'blob' })

// 用户管理相关接口
export const login = (user) => managementRequest.post('/admin/user/login', user)
export const logout = () => managementRequest.post('/admin/user/logout')
export const register = (user) => request.post('/admin/user/register', user)
export const getUserById = (userId) => managementRequest.get(`/admin/user/${userId}`)
export const updateUser = (user) => managementRequest.put('/admin/user', user)
export const deleteUser = (userId) => managementRequest.delete(`/admin/user/${userId}`)
export const getUsers = () => managementRequest.get('/admin/user/list')
export const getUsersPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/user/page', { params: { pageNum, pageSize } })
// 忘记密码相关接口
export const checkUserExists = (userInfo) => request.post('/admin/user/check-user', userInfo)
export const resetPassword = (passwordInfo) => managementRequest.post('/admin/user/reset-password', passwordInfo)

// 数据统计相关接口
export const getLogSummary = (days = 7) =>
  managementRequest.get('/admin/statistics/log-summary', { params: { days } })
export const getPerformance = (networkInterface = '') =>
  managementRequest.get('/admin/statistics/performance', { params: { networkInterface } })
export const getAnomalyStatistics = (days = 7) =>
  managementRequest.get('/admin/statistics/anomaly-statistics', { params: { days } })
export const getTaskStatusStatistics = () => managementRequest.get('/admin/statistics/task-status')

// 性能分析相关 API
export const getPerformanceData = () => managementRequest.get('/admin/statistics/performance')
export const getPatrolAlertsRecent = (limit = 12) =>
  managementRequest.get('/api/ops/patrol/alerts/recent', { params: { limit } })

export const getPatrolCorrelationLatest = () =>
  managementRequest.get('/api/ops/patrol/correlation/latest')

export const getPatrolRemediationPending = () =>
  managementRequest.get('/api/ops/patrol/remediation/pending')

export const confirmPatrolRemediation = (proposalId, confirmCode = '确认执行') =>
  managementRequest.post('/api/ops/patrol/remediation/confirm', { proposalId, confirmCode })

export const getPatrolRemediationCoverage = () =>
  managementRequest.get('/api/ops/patrol/remediation/coverage')

export const collectSystemPerformanceData = () => managementRequest.post('/api/performance/collect-system-data')

export const getPerformanceTrend = (dataType, startTime, endTime, interval = 5) =>
  managementRequest.get('/api/performance/trend', {
    params: { dataType, startTime, endTime, interval }
  })

export const getPerformanceMultiDimension = (startTime, endTime, interval = 10) =>
  managementRequest.get('/api/performance/multi-dimension', {
    params: { startTime, endTime, interval }
  })

export const getAssistantContext = () => managementRequest.get('/api/assistant/context')

/** 统一运维助手：流式自然语言对话（含可选多轮 history） */
/** 延时执行本地工具任务（与 /api/mcp 同源；服务重启后任务丢失） */
export const scheduleDeferredOpsTask = (payload) =>
  managementRequest.post('/api/ops-schedule/tasks', payload)

export const listDeferredOpsTasks = () => managementRequest.get('/api/ops-schedule/tasks')

export const cancelDeferredOpsTask = (taskId) =>
  managementRequest.delete(`/api/ops-schedule/tasks/${taskId}`)

export const assistantChatStream = (message, history = [], options = {}) =>
  fetch(`${getManagementApiBaseUrl()}/api/assistant/chat/stream`, {
    method: 'POST',
    headers: FETCH_JSON_HEADERS,
    credentials: 'include',
    signal: options.signal,
    body: JSON.stringify({
      message,
      history: Array.isArray(history) ? history : [],
      useToolAgent: options.useToolAgent !== false,
      confirmRemediation: options.confirmRemediation === true,
      targetHostId: options.targetHostId != null && options.targetHostId !== ''
        ? Number(options.targetHostId)
        : undefined
    })
  })
export const getApiBaseUrl = () => buildRuntimeBaseUrl()
export const getManagementApiBaseUrl = () => buildManagementBaseUrl()

export function buildManagementWsUrl(pathname = '/ws/performance') {
  const wsPath = pathname.startsWith('/') ? pathname : `/${pathname}`
  if (typeof window === 'undefined') {
    const base = getManagementApiBaseUrl().replace(/^http/, 'ws')
    return `${base}${wsPath}`
  }
  const base = new URL(getManagementApiBaseUrl())
  const wsScheme = base.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsScheme}//${base.host}${base.pathname}${wsPath}`
}

export const getPerformanceBaseline = (dataType, days = 7) =>
  managementRequest.get('/api/performance/baseline', {
    params: { dataType, days }
  })

// 告警配置相关 API（支持默认配置与任务级配置）
export const getAlarmConfig = (taskId = '') =>
  taskId ? request.get(`/api/alarm/config/${taskId}`) : request.get('/api/alarm/config')

export const saveAlarmConfig = (config, taskId = '') =>
  taskId ? request.post(`/api/alarm/config/${taskId}`, config) : request.post('/api/alarm/config', config)

/** MCP 路径/服务白名单（控制台「路径白名单」弹窗） */
export const getAgentPathPolicy = () => managementRequest.get('/api/agent/path-policy')
export const saveAgentPathPolicy = (body) => managementRequest.put('/api/agent/path-policy', body)

// 角色管理相关接口
export const getRoleById = (roleId) => managementRequest.get(`/admin/role/${roleId}`)
export const getRoleByRoleName = (roleName) => managementRequest.get(`/admin/role/name/${roleName}`)
export const getRoles = () => managementRequest.get('/admin/role/list')
export const getRolesPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/role/page', { params: { pageNum, pageSize } })
export const addRole = (role) => managementRequest.post('/admin/role', role)
export const updateRole = (role) => managementRequest.put('/admin/role', role)
export const deleteRole = (roleId) => managementRequest.delete(`/admin/role/${roleId}`)

// 权限管理相关接口
export const getPermissionById = (permissionId) => managementRequest.get(`/admin/permission/${permissionId}`)
export const getPermissionByPermissionCode = (permissionCode) => managementRequest.get(`/admin/permission/code/${permissionCode}`)
export const getPermissions = () => managementRequest.get('/admin/permission/list')
export const getPermissionsPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/permission/page', { params: { pageNum, pageSize } })
export const getPermissionsByRoleId = (roleId) => managementRequest.get(`/admin/permission/role/${roleId}`)
export const addPermission = (permission) => managementRequest.post('/admin/permission', permission)
export const updatePermission = (permission) => managementRequest.put('/admin/permission', permission)
export const deletePermission = (permissionId) => managementRequest.delete(`/admin/permission/${permissionId}`)
export const saveRolePermissions = (roleId, permissionIds) => managementRequest.post(`/admin/role/${roleId}/permissions`, permissionIds)

// 个人中心相关接口
export const getUserInfo = () => request.get('/api/profile/user-info')
export const updateUserInfo = (user) => request.put('/api/profile/user-info', user)
export const changeUserPassword = (passwordInfo) => request.post('/api/profile/change-password', passwordInfo)
export const getAccessTrail = (page = 1, pageSize = 10) =>
  request.get('/api/profile/access-trail', { params: { page, pageSize } })
export const getLoginHistory = (page = 1, pageSize = 10) =>
  request.get('/api/profile/login-history', { params: { page, pageSize } })
export const generateApiKey = () => request.post('/api/profile/generate-api-key')
export const getUserStats = () => request.get('/api/profile/user-stats')
export const getUserApiKeys = () => request.get('/api/profile/api-keys')
export const createUserApiKey = (payload) => request.post('/api/profile/api-keys', payload)
export const rotateUserApiKey = (id) => request.post(`/api/profile/api-keys/${id}/rotate`)
export const revokeUserApiKey = (id) => request.post(`/api/profile/api-keys/${id}/revoke`)
export const getSystemConfigEffective = () => managementRequest.get('/api/system-config/effective')
export const saveSystemConfigEffective = (payload) => managementRequest.put('/api/system-config/effective', payload)
export const reconcileSystemBootstrap = () => managementRequest.post('/api/system-config/bootstrap/reconcile')
export const getPlatformInfo = () => managementRequest.get('/api/platform/info')

/** 公开验收探针（无需登录） */
export const getPlatformAcceptance = () => request.get('/api/platform/acceptance')

// 告警历史相关接口
export const getAlarmHistory = (pageNum = 1, pageSize = 10, level = '', taskId = '') =>
  request.get('/api/alarm/history/list', { params: { pageNum, pageSize, level, taskId } })

const alarmFilterParams = (days, level, taskId) => {
  const p = { days }
  if (level) p.level = level
  if (taskId) p.taskId = taskId
  return p
}

export const getAlarmStatistics = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/statistics', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmTrend = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/trend', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmLevelDistribution = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/level-distribution', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmRootCauseStatistics = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/root-cause-statistics', { params: alarmFilterParams(days, level, taskId) })

export const processAlarmsByTaskId = (taskId) =>
  request.get('/api/alarm/history/process-by-task', { params: { taskId } })

// 告警规则管理相关接口
export const addAlarmRule = (rule) => request.post('/api/alarm-rule/add', rule)
export const updateAlarmRule = (rule) => request.post('/api/alarm-rule/update', rule)
export const deleteAlarmRule = (id) => request.delete(`/api/alarm-rule/delete/${id}`)
export const getAlarmRuleById = (id) => request.get(`/api/alarm-rule/get/${id}`)
export const getAllAlarmRules = () => request.get('/api/alarm-rule/list')
export const getEnabledAlarmRules = () => request.get('/api/alarm-rule/enabled')
export const enableAlarmRule = (id, enabled) => request.post('/api/alarm-rule/enable', null, { params: { id, enabled } })
export const testAlarmRule = (ruleId, testContent) => request.post('/api/alarm-rule/test', null, { params: { ruleId, testContent } })

// 日志清洗相关 API
export const getCleanRules = () => request.get('/api/log/clean/rules')
export const saveCleanRules = (rules) => request.post('/api/log/clean/rules', rules)
export const cleanLog = (logContent, rules) => request.post('/api/log/clean', { logContent, rules })

// Elasticsearch 相关 API
export const getESLogs = (pageNum = 1, pageSize = 10) =>
  request.get('/api/elasticsearch/logs', { params: { pageNum, pageSize } })

export const indexESLogs = (logs) => request.post('/api/elasticsearch/index', logs)

export const searchESLogs = (
  query,
  severity = '',
  pageNum = 1,
  pageSize = 10,
  startTime = '',
  endTime = '',
  anomaly = ''
) => request.get('/api/elasticsearch/search', {
  params: { query, severity, pageNum, pageSize, startTime, endTime, anomaly }
})

export const getESLogById = (id) => request.get(`/api/elasticsearch/get/${id}`)

// 运维状态相关 API
export const getModelRfHealth = () => managementRequest.get('/api/v1/model/health')
export const getCollectorStatus = () => managementRequest.get('/api/collector/status')
export const getKafkaStatus = () => managementRequest.get('/api/kafka/status')

// AI 审计与 Runbook 审批
export const getAiAuditRecent = (limit = 100) =>
  managementRequest.get('/admin/audit/ai/recent', { params: { limit } })

/** 运维侧全链路审计（ops_audit_trace） */
export const getAuditFeed = (limit = 100, kind = '') =>
  managementRequest.get('/api/audit/feed', {
    params: {
      limit,
      ...(kind && kind !== 'all' ? { kind } : {})
    }
  })

export const getAuditDetail = ({ entryId = '', traceId = '' } = {}) =>
  managementRequest.get('/api/audit/detail', {
    params: {
      ...(entryId ? { entryId } : {}),
      ...(traceId ? { traceId } : {})
    }
  })

export const getOpsTraceRecent = (limit = 100) =>
  managementRequest.get('/api/ops-trace/recent', { params: { limit } })

/** 单条审计含 steps_json（思维链） */
export const getOpsTraceDetail = (traceId) =>
  managementRequest.get('/api/ops-trace/detail', { params: { traceId } })

export const getRunbookList = () => managementRequest.get('/api/runbook/list')
export const submitRunbook = (payload) => managementRequest.post('/api/runbook/submit', payload)
export const approveRunbook = (id) => managementRequest.post(`/api/runbook/${id}/approve`)
export const rejectRunbook = (id, reason = '') => managementRequest.post(`/api/runbook/${id}/reject`, { reason })
export const executeRunbook = (id) => managementRequest.post(`/api/runbook/${id}/execute`)

// 决策反馈相关 API
export const submitDecisionFeedback = (feedback) => request.post('/api/decision-feedback/submit', feedback)
export const submitDecisionFeedbackBatch = (feedbacks) => request.post('/api/decision-feedback/submit/batch', feedbacks)
export const getUntrainedSamples = (pageNum = 1, pageSize = 20) =>
  request.get('/api/decision-feedback/untrained', { params: { pageNum, pageSize } })
export const getUntrainedCount = () => request.get('/api/decision-feedback/untrained/count')
export const triggerManualTraining = () => request.post('/api/decision-feedback/train/manual')
export const markAsTrained = (ids) => request.post('/api/decision-feedback/mark-trained', ids)

// MCP 工具相关 API
export const executeMcpTool = (toolName, parameters, userMessage = '') => {
  const body = { toolName, parameters }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return managementRequest.post('/api/mcp/execute', body)
}

/** 中等风险二次确认后执行（confirmCode 须与后端一致：确认执行） */
export const confirmMcpExecute = (
  toolName,
  parameters,
  confirmCode = '确认执行',
  userMessage = '',
  confirmationId = ''
) => {
  const body = { toolName, parameters, confirmCode }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  const cid = typeof confirmationId === 'string' ? confirmationId.trim() : ''
  if (cid) body.confirmationId = cid
  return managementRequest.post('/api/mcp/confirmExecute', body)
}

export const getMcpTools = () => managementRequest.get('/api/mcp/tools')

/** 一键安全自检（探针走真实安全门，不执行系统命令） */
export const getSecuritySelfCheck = () => managementRequest.get('/api/security/self-check')

export const getPatrolHistory = (days = 7, limit = 50) =>
  managementRequest.get('/api/ops/patrol/history', { params: { days, limit } })

export const getPatrolHistoryTrend = (days = 7) =>
  managementRequest.get('/api/ops/patrol/history/trend', { params: { days } })

/** 运维效果评分仪表盘 */
export const getOpsEffectDashboard = (days = 7) =>
  managementRequest.get('/api/ops/effect/dashboard', { params: { days } })

/** AWM 已沉淀 workflow 套路 */
export const getOpsWorkflowMemory = (domain = 'all', q = '') =>
  managementRequest.get('/api/ops/workflow/memory', {
    params: { domain, ...(q ? { q } : {}) }
  })

export const getOpsWorkflowRuns = (workflowId, limit = 8) =>
  managementRequest.get('/api/ops/workflow/runs', {
    params: { workflowId, limit }
  })

/** Reflexion 安全教训 */
export const getOpsFailureInsights = (q = '', limit = 12) =>
  managementRequest.get('/api/ops/workflow/failure-insights', {
    params: { limit, ...(q ? { q } : {}) }
  })

/** 从近期审计轨迹离线诱导 workflow */
export const induceOpsWorkflowFromAudit = (limit = 20) =>
  managementRequest.post('/api/ops/workflow/induce-from-audit', null, { params: { limit } })

/** 兼容旧 autonomous 入口，现统一触发巡检自动修复单轨 */
export const runAutonomousOps = (forceRemediate = false, readOnly = false) =>
  managementRequest.post('/api/ops/autonomous/run', null, {
    params: { forceRemediate, readOnly }
  })

// 知识库（RAG）
export const getKnowledgeStatus = () => request.get('/api/v1/knowledge/status')

export const listKnowledgeDocuments = (page = 1, pageSize = 20) =>
  request.get('/api/v1/knowledge/documents', { params: { page, pageSize } })

export const uploadKnowledgeText = (payload) =>
  request.post('/api/v1/knowledge/upload', payload)

export const uploadKnowledgeFile = (formData) =>
  request.post('/api/v1/knowledge/upload/file', formData)

export const searchKnowledge = (query, topK = 5) =>
  request.get('/api/v1/knowledge/search', { params: { query, topK } })

export const deleteKnowledgeDocument = (documentId) =>
  request.delete(`/api/v1/knowledge/document/${encodeURIComponent(documentId)}`)
