import axios from 'axios'
import { ElMessage } from 'element-plus'
import { formatApiError } from '../utils/apiError.js'

const runtimeHost = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
const runtimeProtocol = typeof window !== 'undefined' ? window.location.protocol : 'http:'

/** 生产/同源部署：构建或 .env 中设置 VITE_AWARD_LOG_BASE_URL（须含 /award-log 后缀，无末尾斜杠） */
const viteAwardBase =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_BASE_URL
    ? String(import.meta.env.VITE_AWARD_LOG_BASE_URL).trim().replace(/\/$/, '')
    : ''

const runtimeBaseUrl =
  viteAwardBase ||
  `${runtimeProtocol}//${runtimeHost}:8088/award-log`

const API_TIMEOUT_MS = (() => {
  const raw = typeof import.meta !== 'undefined' ? import.meta.env?.VITE_API_TIMEOUT_MS : undefined
  const n = parseInt(String(raw ?? '600000'), 10)
  return Number.isFinite(n) && n >= 30000 ? n : 600000
})()

const request = axios.create({
  baseURL: runtimeBaseUrl,
  timeout: API_TIMEOUT_MS,
  withCredentials: true
})

request.interceptors.response.use(
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
    if (code === 401 || code === 403) {
      localStorage.removeItem('user')
      ElMessage.error(message || '无权限访问')
      setTimeout(() => window.location.reload(), 300)
      return Promise.reject(new Error(message || 'Unauthorized'))
    }
  const errMsg = formatApiError({ response: { data: res.data } }) || message || '请求失败'
    ElMessage.error(errMsg)
    return Promise.reject(new Error(errMsg))
  },
  error => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('user')
      ElMessage.error('登录已过期或权限不足，请重新登录')
      setTimeout(() => window.location.reload(), 300)
      return Promise.reject(error)
    }
    const errMsg = formatApiError(error)
    ElMessage.error(errMsg)
    const wrapped = new Error(errMsg)
    wrapped.securityCode = error.response?.data?.securityCode
    wrapped.raw = error.response?.data
    return Promise.reject(wrapped)
  }
)

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
export const login = (user) => request.post('/admin/user/login', user)
export const logout = () => request.post('/admin/user/logout')
export const register = (user) => request.post('/admin/user/register', user)
export const getUserById = (userId) => request.get(`/admin/user/${userId}`)
export const updateUser = (user) => request.put('/admin/user', user)
export const deleteUser = (userId) => request.delete(`/admin/user/${userId}`)
export const getUsers = () => request.get('/admin/user/list')
export const getUsersPage = (pageNum = 1, pageSize = 10) => 
  request.get('/admin/user/page', { params: { pageNum, pageSize } })
// 忘记密码相关接口
export const checkUserExists = (userInfo) => request.post('/admin/user/check-user', userInfo)
export const resetPassword = (passwordInfo) => request.post('/admin/user/reset-password', passwordInfo)

// 数据统计相关接口
export const getLogSummary = (days = 7) => 
  request.get('/admin/statistics/log-summary', { params: { days } })
export const getPerformance = (networkInterface = '') => {
  console.log('调用getPerformance API...')
  return request.get('/admin/statistics/performance', { params: { networkInterface } }).then(response => {
    console.log('getPerformance API返回数据:', response)
    return response
  }).catch(error => {
    console.error('getPerformance API调用失败:', error)
    throw error
  })
}
export const getAnomalyStatistics = (days = 7) => 
  request.get('/admin/statistics/anomaly-statistics', { params: { days } })
export const getTaskStatusStatistics = () => {
  console.log('调用getTaskStatusStatistics API...')
  return request.get('/admin/statistics/task-status').then(response => {
    console.log('getTaskStatusStatistics API返回数据:', response)
    return response
  }).catch(error => {
    console.error('getTaskStatusStatistics API调用失败:', error)
    throw error
  })
}

// 性能分析相关API
export const getPerformanceData = () => request.get('/admin/statistics/performance')
export const getPatrolAlertsRecent = (limit = 12) =>
  request.get('/api/ops/patrol/alerts/recent', { params: { limit } })

export const getPatrolCorrelationLatest = () =>
  request.get('/api/ops/patrol/correlation/latest')

export const getPatrolRemediationPending = () =>
  request.get('/api/ops/patrol/remediation/pending')

export const confirmPatrolRemediation = (proposalId, confirmCode = '确认执行') =>
  request.post('/api/ops/patrol/remediation/confirm', { proposalId, confirmCode })

export const getPatrolRemediationCoverage = () =>
  request.get('/api/ops/patrol/remediation/coverage')

export const collectSystemPerformanceData = () => request.post('/api/performance/collect-system-data')

export const getPerformanceTrend = (dataType, startTime, endTime, interval = 5) => 
  request.get('/api/performance/trend', {
    params: { dataType, startTime, endTime, interval }
  })

export const getPerformanceMultiDimension = (startTime, endTime, interval = 10) =>
  request.get('/api/performance/multi-dimension', {
    params: { startTime, endTime, interval }
  })

export const getAssistantContext = () => request.get('/api/assistant/context')

/** 统一运维助手：流式自然语言对话（含可选多轮 history） */
/** 延时执行本地工具任务（与 /api/mcp 同源；服务重启后任务丢失） */
export const scheduleDeferredOpsTask = (payload) =>
  request.post('/api/ops-schedule/tasks', payload)

export const listDeferredOpsTasks = () => request.get('/api/ops-schedule/tasks')

export const cancelDeferredOpsTask = (taskId) =>
  request.delete(`/api/ops-schedule/tasks/${taskId}`)

export const assistantChatStream = (message, history = [], options = {}) =>
  fetch(`${runtimeBaseUrl}/api/assistant/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({
      message,
      history: Array.isArray(history) ? history : [],
      useToolAgent: options.useToolAgent !== false
    })
  })
export const getApiBaseUrl = () => runtimeBaseUrl

export const getPerformanceBaseline = (dataType, days = 7) => 
  request.get('/api/performance/baseline', {
    params: { dataType, days }
  })



// 告警配置相关API（支持默认配置与任务级配置）
export const getAlarmConfig = (taskId = '') =>
  taskId ? request.get(`/api/alarm/config/${taskId}`) : request.get('/api/alarm/config')

export const saveAlarmConfig = (config, taskId = '') =>
  taskId ? request.post(`/api/alarm/config/${taskId}`, config) : request.post('/api/alarm/config', config)

/** MCP 路径/服务白名单（控制台「路径白名单」弹窗） */
export const getAgentPathPolicy = () => request.get('/api/agent/path-policy')
export const saveAgentPathPolicy = (body) => request.put('/api/agent/path-policy', body)

// 角色管理相关接口
export const getRoleById = (roleId) => request.get(`/admin/role/${roleId}`)
export const getRoleByRoleName = (roleName) => request.get(`/admin/role/name/${roleName}`)
export const getRoles = () => request.get('/admin/role/list')
export const getRolesPage = (pageNum = 1, pageSize = 10) => 
  request.get('/admin/role/page', { params: { pageNum, pageSize } })
export const addRole = (role) => request.post('/admin/role', role)
export const updateRole = (role) => request.put('/admin/role', role)
export const deleteRole = (roleId) => request.delete(`/admin/role/${roleId}`)

// 权限管理相关接口
export const getPermissionById = (permissionId) => request.get(`/admin/permission/${permissionId}`)
export const getPermissionByPermissionCode = (permissionCode) => request.get(`/admin/permission/code/${permissionCode}`)
export const getPermissions = () => request.get('/admin/permission/list')
export const getPermissionsPage = (pageNum = 1, pageSize = 10) => 
  request.get('/admin/permission/page', { params: { pageNum, pageSize } })
export const getPermissionsByRoleId = (roleId) => request.get(`/admin/permission/role/${roleId}`)
export const addPermission = (permission) => request.post('/admin/permission', permission)
export const updatePermission = (permission) => request.put('/admin/permission', permission)
export const deletePermission = (permissionId) => request.delete(`/admin/permission/${permissionId}`)
export const saveRolePermissions = (roleId, permissionIds) => request.post(`/admin/role/${roleId}/permissions`, permissionIds)

// 个人中心相关接口
export const getUserInfo = () => request.get('/api/profile/user-info')
export const updateUserInfo = (user) => request.put('/api/profile/user-info', user)
export const changeUserPassword = (passwordInfo) => request.post('/api/profile/change-password', passwordInfo)
export const getLoginHistory = () => request.get('/api/profile/login-history')
export const generateApiKey = () => request.post('/api/profile/generate-api-key')
export const getNotificationSettings = () => request.get('/api/profile/notification-settings')
export const updateNotificationSettings = (settings) => request.put('/api/profile/notification-settings', settings)
export const getUserStats = () => request.get('/api/profile/user-stats')



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

// 日志清洗相关API
export const getCleanRules = () => request.get('/api/log/clean/rules')
export const saveCleanRules = (rules) => request.post('/api/log/clean/rules', rules)
export const cleanLog = (logContent, rules) => request.post('/api/log/clean', { logContent, rules })

// Elasticsearch相关API
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
export const getModelRfHealth = () => request.get('/api/v1/model/health')
export const getCollectorStatus = () => request.get('/api/collector/status')
export const getKafkaStatus = () => request.get('/api/kafka/status')

// 聊天式分析相关API
export const chatAnalyze = (message) => request.post('/api/chat/analyze', { message })

export const chatAnalyzeStream = (message) => {
  return fetch(`${runtimeBaseUrl}/api/chat/analyze/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    body: JSON.stringify({ message })
  })
}

export const chatStream = (message) => {
  return fetch(`${runtimeBaseUrl}/api/chat/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    body: JSON.stringify({ message })
  })
}

export const getChatHistory = () => request.get('/api/chat/history')

export const clearChatHistory = () => request.delete('/api/chat/history')

// AI审计与Runbook审批
export const getAiAuditRecent = (limit = 100) =>
  request.get('/admin/audit/ai/recent', { params: { limit } })

/** 运维侧全链路审计（ops_audit_trace） */
export const getOpsTraceRecent = (limit = 100) =>
  request.get('/api/ops-trace/recent', { params: { limit } })

/** 单条审计含 steps_json（思维链） */
export const getOpsTraceDetail = (traceId) =>
  request.get('/api/ops-trace/detail', { params: { traceId } })

export const getRunbookList = () => request.get('/api/runbook/list')
export const submitRunbook = (payload) => request.post('/api/runbook/submit', payload)
export const approveRunbook = (id) => request.post(`/api/runbook/${id}/approve`)
export const rejectRunbook = (id, reason = '') => request.post(`/api/runbook/${id}/reject`, { reason })
export const executeRunbook = (id) => request.post(`/api/runbook/${id}/execute`)

// 决策反馈相关API
export const submitDecisionFeedback = (feedback) => request.post('/decision-feedback/submit', feedback)
export const submitDecisionFeedbackBatch = (feedbacks) => request.post('/decision-feedback/submit/batch', feedbacks)
export const getUntrainedSamples = (pageNum = 1, pageSize = 20) => 
  request.get('/decision-feedback/untrained', { params: { pageNum, pageSize } })
export const getUntrainedCount = () => request.get('/decision-feedback/untrained/count')
export const triggerManualTraining = () => request.post('/decision-feedback/train/manual')
export const markAsTrained = (ids) => request.post('/decision-feedback/mark-trained', ids)

// MCP工具相关API
export const executeMcpTool = (toolName, parameters, userMessage = '') => {
  const body = { toolName, parameters }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return request.post('/api/mcp/execute', body)
}

/** 中等风险二次确认后执行（confirmCode 须与后端一致：确认执行） */
export const confirmMcpExecute = (toolName, parameters, confirmCode = '确认执行', userMessage = '') => {
  const body = { toolName, parameters, confirmCode }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return request.post('/api/mcp/confirmExecute', body)
}

export const getMcpTools = () => request.get('/api/mcp/tools')

export const getPatrolHistory = (days = 7, limit = 50) =>
  request.get('/api/ops/patrol/history', { params: { days, limit } })

export const getPatrolHistoryTrend = (days = 7) =>
  request.get('/api/ops/patrol/history/trend', { params: { days } })

/** 全工具融合自主运维（不经过 LLM） */
export const runAutonomousOps = (forceRemediate = false, readOnly = false) =>
  request.post('/api/ops/autonomous/run', null, {
    params: { forceRemediate, readOnly }
  })
