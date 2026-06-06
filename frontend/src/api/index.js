import axios from 'axios'
import { ElMessage } from 'element-plus'
import { formatApiError } from '../utils/apiError.js'

const runtimeHost = typeof window !== 'undefined' ? window.location.hostname : 'localhost'
const runtimeProtocol = typeof window !== 'undefined' ? window.location.protocol : 'http:'

/** 鐢熶骇/鍚屾簮閮ㄧ讲锛氭瀯寤烘垨 .env 涓缃?VITE_AWARD_LOG_BASE_URL锛堥』鍚?/award-log 鍚庣紑锛屾棤鏈熬鏂滄潬锛?*/
const viteAwardBase =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_BASE_URL
    ? String(import.meta.env.VITE_AWARD_LOG_BASE_URL).trim().replace(/\/$/, '')
    : ''

/** 解析 API 基址：dev(3000) 须带当前端口走 Vite proxy；8088 直连后端；生产同域 /award-log */
function buildRuntimeBaseUrl() {
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

const runtimeBaseUrl = buildRuntimeBaseUrl()

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

function buildApiError(message, data) {
  const wrapped = new Error(message || '璇锋眰澶辫触')
  wrapped.securityCode = data?.securityCode
  wrapped.raw = data
  return wrapped
}

request.interceptors.response.use(
  res => {
    if (res.config?.responseType === 'blob') {
      return res.data
    }
    const reqUrl = String(res.config?.url || '')
    // MCP 宸ュ叿鎺ュ彛杩斿洖 Spring Map JSON锛坰uccess / needConfirm / error锛夛紝鏃犵粺涓€ { code, data }
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

// 鐢ㄦ埛绠＄悊鐩稿叧鎺ュ彛
export const login = (user) => request.post('/admin/user/login', user)
export const logout = () => request.post('/admin/user/logout')
export const register = (user) => request.post('/admin/user/register', user)
export const getUserById = (userId) => request.get(`/admin/user/${userId}`)
export const updateUser = (user) => request.put('/admin/user', user)
export const deleteUser = (userId) => request.delete(`/admin/user/${userId}`)
export const getUsers = () => request.get('/admin/user/list')
export const getUsersPage = (pageNum = 1, pageSize = 10) => 
  request.get('/admin/user/page', { params: { pageNum, pageSize } })
// 蹇樿瀵嗙爜鐩稿叧鎺ュ彛
export const checkUserExists = (userInfo) => request.post('/admin/user/check-user', userInfo)
export const resetPassword = (passwordInfo) => request.post('/admin/user/reset-password', passwordInfo)

// 鏁版嵁缁熻鐩稿叧鎺ュ彛
export const getLogSummary = (days = 7) => 
  request.get('/admin/statistics/log-summary', { params: { days } })
export const getPerformance = (networkInterface = '') => {
  console.log('璋冪敤getPerformance API...')
  return request.get('/admin/statistics/performance', { params: { networkInterface } }).then(response => {
    console.log('getPerformance API杩斿洖鏁版嵁:', response)
    return response
  }).catch(error => {
    console.error('getPerformance API璋冪敤澶辫触:', error)
    throw error
  })
}
export const getAnomalyStatistics = (days = 7) => 
  request.get('/admin/statistics/anomaly-statistics', { params: { days } })
export const getTaskStatusStatistics = () => {
  console.log('璋冪敤getTaskStatusStatistics API...')
  return request.get('/admin/statistics/task-status').then(response => {
    console.log('getTaskStatusStatistics API杩斿洖鏁版嵁:', response)
    return response
  }).catch(error => {
    console.error('getTaskStatusStatistics API璋冪敤澶辫触:', error)
    throw error
  })
}

// 鎬ц兘鍒嗘瀽鐩稿叧API
export const getPerformanceData = () => request.get('/admin/statistics/performance')
export const getPatrolAlertsRecent = (limit = 12) =>
  request.get('/api/ops/patrol/alerts/recent', { params: { limit } })

export const getPatrolCorrelationLatest = () =>
  request.get('/api/ops/patrol/correlation/latest')

export const getPatrolRemediationPending = () =>
  request.get('/api/ops/patrol/remediation/pending')

export const confirmPatrolRemediation = (proposalId, confirmCode = '纭鎵ц') =>
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

/** 缁熶竴杩愮淮鍔╂墜锛氭祦寮忚嚜鐒惰瑷€瀵硅瘽锛堝惈鍙€夊杞?history锛?*/
/** 寤舵椂鎵ц鏈湴宸ュ叿浠诲姟锛堜笌 /api/mcp 鍚屾簮锛涙湇鍔￠噸鍚悗浠诲姟涓㈠け锛?*/
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
      useToolAgent: options.useToolAgent !== false,
      confirmRemediation: options.confirmRemediation === true
    })
  })
export const getApiBaseUrl = () => runtimeBaseUrl

export const getPerformanceBaseline = (dataType, days = 7) => 
  request.get('/api/performance/baseline', {
    params: { dataType, days }
  })



// 鍛婅閰嶇疆鐩稿叧API锛堟敮鎸侀粯璁ら厤缃笌浠诲姟绾ч厤缃級
export const getAlarmConfig = (taskId = '') =>
  taskId ? request.get(`/api/alarm/config/${taskId}`) : request.get('/api/alarm/config')

export const saveAlarmConfig = (config, taskId = '') =>
  taskId ? request.post(`/api/alarm/config/${taskId}`, config) : request.post('/api/alarm/config', config)

/** MCP 璺緞/鏈嶅姟鐧藉悕鍗曪紙鎺у埗鍙般€岃矾寰勭櫧鍚嶅崟銆嶅脊绐楋級 */
export const getAgentPathPolicy = () => request.get('/api/agent/path-policy')
export const saveAgentPathPolicy = (body) => request.put('/api/agent/path-policy', body)

// 瑙掕壊绠＄悊鐩稿叧鎺ュ彛
export const getRoleById = (roleId) => request.get(`/admin/role/${roleId}`)
export const getRoleByRoleName = (roleName) => request.get(`/admin/role/name/${roleName}`)
export const getRoles = () => request.get('/admin/role/list')
export const getRolesPage = (pageNum = 1, pageSize = 10) => 
  request.get('/admin/role/page', { params: { pageNum, pageSize } })
export const addRole = (role) => request.post('/admin/role', role)
export const updateRole = (role) => request.put('/admin/role', role)
export const deleteRole = (roleId) => request.delete(`/admin/role/${roleId}`)

// 鏉冮檺绠＄悊鐩稿叧鎺ュ彛
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

// 涓汉涓績鐩稿叧鎺ュ彛
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
export const getSystemConfigEffective = () => request.get('/api/system-config/effective')
export const saveSystemConfigEffective = (payload) => request.put('/api/system-config/effective', payload)
export const getPlatformInfo = () => request.get('/api/platform/info')



// 鍛婅鍘嗗彶鐩稿叧鎺ュ彛
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



// 鍛婅瑙勫垯绠＄悊鐩稿叧鎺ュ彛
export const addAlarmRule = (rule) => request.post('/api/alarm-rule/add', rule)
export const updateAlarmRule = (rule) => request.post('/api/alarm-rule/update', rule)
export const deleteAlarmRule = (id) => request.delete(`/api/alarm-rule/delete/${id}`)
export const getAlarmRuleById = (id) => request.get(`/api/alarm-rule/get/${id}`)
export const getAllAlarmRules = () => request.get('/api/alarm-rule/list')
export const getEnabledAlarmRules = () => request.get('/api/alarm-rule/enabled')
export const enableAlarmRule = (id, enabled) => request.post('/api/alarm-rule/enable', null, { params: { id, enabled } })
export const testAlarmRule = (ruleId, testContent) => request.post('/api/alarm-rule/test', null, { params: { ruleId, testContent } })

// 鏃ュ織娓呮礂鐩稿叧API
export const getCleanRules = () => request.get('/api/log/clean/rules')
export const saveCleanRules = (rules) => request.post('/api/log/clean/rules', rules)
export const cleanLog = (logContent, rules) => request.post('/api/log/clean', { logContent, rules })

// Elasticsearch鐩稿叧API
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

// 杩愮淮鐘舵€佺浉鍏?API
export const getModelRfHealth = () => request.get('/api/v1/model/health')
export const getCollectorStatus = () => request.get('/api/collector/status')
export const getKafkaStatus = () => request.get('/api/kafka/status')

// 鑱婂ぉ寮忓垎鏋愮浉鍏矨PI
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

// AI瀹¤涓嶳unbook瀹℃壒
export const getAiAuditRecent = (limit = 100) =>
  request.get('/admin/audit/ai/recent', { params: { limit } })

/** 杩愮淮渚у叏閾捐矾瀹¤锛坥ps_audit_trace锛?*/
export const getOpsTraceRecent = (limit = 100) =>
  request.get('/api/ops-trace/recent', { params: { limit } })

/** 鍗曟潯瀹¤鍚?steps_json锛堟€濈淮閾撅級 */
export const getOpsTraceDetail = (traceId) =>
  request.get('/api/ops-trace/detail', { params: { traceId } })

export const getRunbookList = () => request.get('/api/runbook/list')
export const submitRunbook = (payload) => request.post('/api/runbook/submit', payload)
export const approveRunbook = (id) => request.post(`/api/runbook/${id}/approve`)
export const rejectRunbook = (id, reason = '') => request.post(`/api/runbook/${id}/reject`, { reason })
export const executeRunbook = (id) => request.post(`/api/runbook/${id}/execute`)

// 鍐崇瓥鍙嶉鐩稿叧API
export const submitDecisionFeedback = (feedback) => request.post('/decision-feedback/submit', feedback)
export const submitDecisionFeedbackBatch = (feedbacks) => request.post('/decision-feedback/submit/batch', feedbacks)
export const getUntrainedSamples = (pageNum = 1, pageSize = 20) => 
  request.get('/decision-feedback/untrained', { params: { pageNum, pageSize } })
export const getUntrainedCount = () => request.get('/decision-feedback/untrained/count')
export const triggerManualTraining = () => request.post('/decision-feedback/train/manual')
export const markAsTrained = (ids) => request.post('/decision-feedback/mark-trained', ids)

// MCP宸ュ叿鐩稿叧API
export const executeMcpTool = (toolName, parameters, userMessage = '') => {
  const body = { toolName, parameters }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return request.post('/api/mcp/execute', body)
}

/** 涓瓑椋庨櫓浜屾纭鍚庢墽琛岋紙confirmCode 椤讳笌鍚庣涓€鑷达細纭鎵ц锛?*/
export const confirmMcpExecute = (toolName, parameters, confirmCode = '纭鎵ц', userMessage = '') => {
  const body = { toolName, parameters, confirmCode }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return request.post('/api/mcp/confirmExecute', body)
}

export const getMcpTools = () => request.get('/api/mcp/tools')

/** 涓€閿畨鍏ㄨ嚜妫€锛堟帰閽堣蛋鐪熷疄瀹夊叏闂紝涓嶆墽琛岀郴缁熷懡浠わ級 */
export const getSecuritySelfCheck = () => request.get('/api/security/self-check')

/** 平台与运行环境（本机 OS、profile、演练模式等） */
export const getPlatformInfo = () => request.get('/api/platform/info')

export const getPatrolHistory = (days = 7, limit = 50) =>
  request.get('/api/ops/patrol/history', { params: { days, limit } })

export const getPatrolHistoryTrend = (days = 7) =>
  request.get('/api/ops/patrol/history/trend', { params: { days } })

/** 杩愮淮鏁堟灉璇勫垎浠〃鏉?*/
export const getOpsEffectDashboard = (days = 7) =>
  request.get('/api/ops/effect/dashboard', { params: { days } })

/** AWM 宸叉矇娣€ workflow 濂楄矾 */
export const getOpsWorkflowMemory = (domain = 'all', q = '') =>
  request.get('/api/ops/workflow/memory', {
    params: { domain, ...(q ? { q } : {}) }
  })

/** Reflexion 瀹夊叏鏁欒 */
export const getOpsFailureInsights = (q = '', limit = 12) =>
  request.get('/api/ops/workflow/failure-insights', {
    params: { limit, ...(q ? { q } : {}) }
  })

/** 浠庤繎鏈熷璁¤建杩圭绾胯瀵?workflow */
export const induceOpsWorkflowFromAudit = (limit = 20) =>
  request.post('/api/ops/workflow/induce-from-audit', null, { params: { limit } })

/** 鍏ㄥ伐鍏疯瀺鍚堣嚜涓昏繍缁达紙涓嶇粡杩?LLM锛?*/
export const runAutonomousOps = (forceRemediate = false, readOnly = false) =>
  request.post('/api/ops/autonomous/run', null, {
    params: { forceRemediate, readOnly }
  })

