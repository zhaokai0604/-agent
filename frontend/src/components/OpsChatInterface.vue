<template>
  <div class="ops-page ops-chat-container">
    <div class="chat-header">
      <div class="header-left">
        <div class="header-mark">
          <el-icon :size="20"><ChatLineRound /></el-icon>
        </div>
        <div class="header-info">
          <h3>工作台助手</h3>
          <span class="status-text">
            <span class="online-dot"></span>
            Agent · MCP 工具 · 系统配置(Tab9) · 延时任务
          </span>
        </div>
      </div>
      <div class="header-actions">
        <el-button text :icon="Refresh" @click="clearChat">清空会话</el-button>
      </div>
    </div>

    <div v-if="patrolPending?.hasPending" class="patrol-pending-banner">
      <div class="patrol-pending-text">
        <strong>巡检待处理</strong>
        <span class="patrol-summary">{{ patrolPending.summary || '有待确认修复步骤' }}</span>
      </div>
      <el-button type="primary" size="small" :loading="patrolContinueLoading" @click="continuePatrolPending">
        继续处理
      </el-button>
    </div>

    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="welcome-area">
        <div class="welcome-intro">
          <h2>运维工作台</h2>
          <p>
            基于当前宿主的上下文进行多轮对话；工具在<strong>服务端操作系统</strong>上执行，写操作默认预览并受路径白名单约束。
          </p>
        </div>
        <div
          v-for="group in workbenchQuickGroups"
          :key="group.name"
          class="quick-group"
        >
          <div class="quick-group-title">{{ group.name }}</div>
          <div class="capability-list">
            <button
              v-for="(item, idx) in group.items"
              :key="group.name + idx"
              type="button"
              class="capability-item"
              @click="sendQuickCommand(item.cmd)"
            >
              <el-icon class="cap-icon"><component :is="item.icon" /></el-icon>
              <span class="cap-label">{{ item.label }}</span>
            </button>
            <button
              v-if="group.name === '处置与自动化' && patrolPending?.hasPending"
              type="button"
              class="capability-item capability-item--alert"
              @click="continuePatrolPending"
            >
              <el-icon class="cap-icon"><component :is="patrolQuickItem.icon" /></el-icon>
              <span class="cap-label">{{ patrolQuickItem.label }}</span>
            </button>
          </div>
        </div>
      </div>

      <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
        <div class="message-avatar" v-if="msg.role === 'assistant'">
          <el-icon :size="18"><Service /></el-icon>
        </div>
        <div class="message-content">
          <div class="message-header">
            <span class="sender-name">{{ msg.role === 'user' ? '你' : '助手' }}</span>
            <span class="timestamp">{{ msg.timestamp }}</span>
          </div>

          <div v-if="msg.isToolCalling" class="tool-calling">
            <div class="tool-calling-header">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>正在调用 {{ msg.toolName }} 工具...</span>
            </div>
            <div class="tool-command" v-if="msg.toolCommand">
              <code>{{ msg.toolCommand }}</code>
            </div>
          </div>

          <div v-else-if="msg.role === 'assistant'" class="message-bubble assistant-bubble">
            <div v-if="msg.isHtml" class="markdown-content assistant-markdown" v-html="msg.content"></div>
            <div
              v-else-if="msg.content"
              class="markdown-content assistant-markdown"
              v-html="renderAssistantMarkdown(msg.content)"
            />
            <div v-if="msg.traceId" class="trace-id-hint">
              链路 traceId: <code>{{ msg.traceId }}</code>
              <span class="trace-hint">（可在「运维链路审计」Tab 查询）</span>
            </div>
          </div>
          <div v-else class="message-bubble user-bubble">
            {{ msg.content }}
          </div>

        </div>
        <div class="message-avatar message-avatar--user" v-if="msg.role === 'user'">
          <el-icon :size="16"><User /></el-icon>
        </div>
      </div>

      <div v-if="loading" class="message-wrapper assistant">
        <div class="message-avatar">
          <el-icon :size="18"><Service /></el-icon>
        </div>
        <div class="message-content">
          <div class="message-header">
            <span class="sender-name">助手</span>
          </div>
          <div class="message-bubble assistant-bubble typing">
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input-area">
      <div v-if="pendingAttachment" class="attachment-chip">
        <span>已选文本附件：{{ pendingAttachment.name }}</span>
        <el-button link type="danger" size="small" @click="clearAttachment">移除</el-button>
      </div>
      <div class="input-wrapper">
        <input
          ref="fileInputRef"
          type="file"
          class="hidden-file-input"
          accept=".txt,.log,.csv,.json,.md,text/plain"
          @change="onAttachFile"
        />
        <el-button class="icon-btn" :icon="FolderOpened" circle title="附加文本文件" @click="openFilePicker" />
        <el-button
          class="icon-btn"
          :icon="Microphone"
          circle
          :type="listeningVoice ? 'danger' : 'default'"
          title="语音输入（需浏览器支持）"
          @click="toggleVoiceInput"
        />
        <el-input
          v-model="inputMessage"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="Windows / Linux 均可提问；可说「20分钟后检查磁盘」；支持附件与语音"
          @keydown.enter.exact.prevent="handleSend"
          @keydown.shift.enter="handleNewLine"
        />
        <el-button type="primary" :loading="loading" @click="handleSend" class="send-btn">
          <el-icon v-if="!loading"><Promotion /></el-icon>
          <span v-else>发送中</span>
        </el-button>
      </div>
      <div class="input-hints">
        <span class="hint">Enter 发送 · Shift+Enter 换行 · 语音为浏览器识别（Chrome 较稳）· 附件限文本类，并入本条消息</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Promotion, Loading, Microphone, FolderOpened, ChatLineRound, Service, User } from '@element-plus/icons-vue'
import { WORKBENCH_QUICK_GROUPS, PATROL_QUICK_ITEM } from '../constants/workbenchQuickActions'
import {
  executeMcpTool,
  confirmMcpExecute,
  assistantChatStream,
  getPatrolRemediationPending,
  scheduleDeferredOpsTask
} from '../api'
import { normalizeMcpToolResponse } from '../utils/mcpToolResult'
import { generateHumanReadableResponse } from '../utils/mcpHumanReadable'
import { getMcpToolCommandHint, applyMcpWriteConfirmParams } from '../utils/mcpToolsMeta'
import { buildMcpRiskConfirmMessage } from '../utils/mcpRiskConfirm'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import { isWorkbenchHighRiskMessage, workbenchHighRiskBlockMarkdown } from '../utils/opsIntentSafety'
import { formatErrorMarkdown } from '../utils/apiError.js'
import MarkdownIt from 'markdown-it'

const workbenchQuickGroups = WORKBENCH_QUICK_GROUPS
const patrolQuickItem = PATROL_QUICK_ITEM

const assistantMd = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false
})

/** 助手正文统一走 Markdown，避免模型输出 ###、** 等符号时整段挤成「一坨」 */
function renderAssistantMarkdown(raw) {
  if (raw == null || raw === '') return ''
  return assistantMd.render(String(raw))
}

const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const messagesContainer = ref(null)
const fileInputRef = ref(null)
const pendingAttachment = ref(null)
const patrolPending = ref({ hasPending: false })
const patrolContinueLoading = ref(false)
const listeningVoice = ref(false)
let speechRecognition = null

const refreshPatrolPending = async () => {
  try {
    const data = await getPatrolRemediationPending()
    patrolPending.value = data && typeof data === 'object' ? data : { hasPending: false }
  } catch {
    patrolPending.value = { hasPending: false }
  }
}

const continuePatrolPending = async () => {
  patrolContinueLoading.value = true
  try {
    await sendQuickCommand('继续处理巡检待办')
    await refreshPatrolPending()
  } finally {
    patrolContinueLoading.value = false
  }
}

let patrolPollTimer = null

onMounted(() => {
  refreshPatrolPending()
  patrolPollTimer = setInterval(refreshPatrolPending, 120000)
})

const openFilePicker = () => fileInputRef.value?.click()

const clearAttachment = () => {
  pendingAttachment.value = null
}

const onAttachFile = (e) => {
  const f = e.target.files?.[0]
  if (!f) return
  const maxBytes = 48 * 1024
  if (f.size > maxBytes) {
    ElMessage.warning('文本附件请勿超过 48KB')
    e.target.value = ''
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    let text = typeof reader.result === 'string' ? reader.result : ''
    if (text.length > 32000) {
      text = text.slice(0, 32000) + '\n...[truncated]'
    }
    pendingAttachment.value = { name: f.name, text }
    ElMessage.success(`已载入：${f.name}`)
  }
  reader.onerror = () => ElMessage.error('读取附件失败')
  reader.readAsText(f, 'UTF-8')
  e.target.value = ''
}

const toggleVoiceInput = () => {
  const SR = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition)
  if (!SR) {
    ElMessage.warning('当前浏览器不支持语音识别，请换用 Chrome 或手动输入')
    return
  }
  if (!speechRecognition) {
    speechRecognition = new SR()
    speechRecognition.lang = 'zh-CN'
    speechRecognition.continuous = false
    speechRecognition.interimResults = false
    speechRecognition.onresult = (ev) => {
      const t = ev.results?.[0]?.[0]?.transcript?.trim()
      if (t) {
        inputMessage.value = (inputMessage.value ? `${inputMessage.value} ${t}` : t).trim()
      }
      listeningVoice.value = false
    }
    speechRecognition.onerror = () => {
      listeningVoice.value = false
      ElMessage.error('语音识别出错，请重试')
    }
    speechRecognition.onend = () => {
      listeningVoice.value = false
    }
  }
  if (listeningVoice.value) {
    try {
      speechRecognition.stop()
    } catch {
      /* noop */
    }
    listeningVoice.value = false
    return
  }
  try {
    listeningVoice.value = true
    speechRecognition.start()
    ElMessage.info('请说话…')
  } catch {
    listeningVoice.value = false
    ElMessage.error('无法启动语音识别')
  }
}

const getToolCommand = (toolName) => getMcpToolCommandHint(toolName)

const parseUserIntent = (message) => {
  if (isWorkbenchHighRiskMessage(message)) {
    return null
  }
  const lowerMsg = message.toLowerCase()

  if (
    /重启|重新启动/i.test(message) &&
    (/nginx|rsyslog|\.service/i.test(message) || (/服务/.test(lowerMsg) && /nginx|rsyslog/i.test(message)))
  ) {
    return 'ServiceRestartTool'
  }

  if (
    (lowerMsg.includes('/var/log') ||
      lowerMsg.includes('日志目录') ||
      lowerMsg.includes('陈旧日志') ||
      (lowerMsg.includes('日志') && lowerMsg.includes('清理'))) &&
    (lowerMsg.includes('清理') || lowerMsg.includes('删除') || lowerMsg.includes('清除'))
  ) {
    return 'LogCleanupTool'
  }

  if (
    lowerMsg.includes('磁盘压力') ||
    lowerMsg.includes('磁盘综合') ||
    lowerMsg.includes('磁盘诊断') ||
    (lowerMsg.includes('磁盘') &&
      (lowerMsg.includes('满了') || lowerMsg.includes('占满') || lowerMsg.includes('空间不足'))) ||
    /io\s*异常|i\s*\/\s*o/i.test(lowerMsg)
  ) {
    return 'DiskAnalyzeTool'
  }

  if (
    lowerMsg.includes('热点') ||
    lowerMsg.includes('大目录') ||
    lowerMsg.includes('谁占用') ||
    lowerMsg.includes('空间占用') ||
    lowerMsg.includes('磁盘占用') ||
    (lowerMsg.includes('磁盘') && lowerMsg.includes('占'))
  ) {
    return 'DiskInsightTool'
  }
  if (lowerMsg.includes('磁盘') || lowerMsg.includes('硬盘') || lowerMsg.includes('df')) {
    return 'DiskTool'
  }
  if (
    /\bjournalctl\b/i.test(lowerMsg) ||
    (lowerMsg.includes('systemd') && lowerMsg.includes('日志')) ||
    lowerMsg.includes('内核日志')
  ) {
    return 'OsInsightTool'
  }
  if (
    lowerMsg.includes('监听端口') ||
    lowerMsg.includes('网络套接字') ||
    (lowerMsg.includes('套接字') && lowerMsg.includes('监听'))
  ) {
    return 'OsInsightTool'
  }
  if (/\blsof\b/i.test(lowerMsg) || (lowerMsg.includes('打开文件') && lowerMsg.includes('进程'))) {
    return 'OsInsightTool'
  }
  if (lowerMsg.includes('进程') || lowerMsg.includes('cpu') || lowerMsg.includes('内存') || lowerMsg.includes('ps aux')) {
    if (lowerMsg.includes('内存') && !lowerMsg.includes('系统')) {
      return 'ProcessTool'
    }
    return lowerMsg.includes('负载') || lowerMsg.includes('系统负载') || lowerMsg.includes('load') ? 'SystemLoadTool' : 'ProcessTool'
  }
  if (lowerMsg.includes('日志') || lowerMsg.includes('log') || lowerMsg.includes('分析')) {
    return 'LogAnalysisTool'
  }
  if (lowerMsg.includes('清理') || lowerMsg.includes('临时文件') || lowerMsg.includes('clean')) {
    return 'CleanTempTool'
  }
  if (lowerMsg.includes('配置') || lowerMsg.includes('config') || lowerMsg.includes('语法')) {
    return 'ConfigCheckTool'
  }
  if (lowerMsg.includes('网络') || lowerMsg.includes('ping') || lowerMsg.includes('连通') || lowerMsg.includes('traceroute')) {
    return 'NetworkTool'
  }
  if (lowerMsg.includes('权限') || lowerMsg.includes('privilege') || lowerMsg.includes('sudo')) {
    return 'PrivilegeTool'
  }
  if (
    (lowerMsg.includes('系统') || lowerMsg.includes('状态') || lowerMsg.includes('概览')) &&
    !/删除|删掉|清除|破坏|格式化|rm\s/i.test(message)
  ) {
    return 'SystemLoadTool'
  }

  return null
}

const extractParameters = (message, toolName) => {
  const params = {}
  const lowerMsg = message.toLowerCase()

  if (toolName === 'NetworkTool') {
    const pingMatch = message.match(/ping\s+(\S+)/i)
    if (pingMatch) {
      params.target = pingMatch[1]
      params.type = 'ping'
    }
    const traceMatch = message.match(/traceroute\s+(\S+)/i)
    if (traceMatch) {
      params.target = traceMatch[1]
      params.type = 'traceroute'
    }
    if (lowerMsg.includes('ping') && !params.target) {
      const ipMatch = message.match(/\d+\.\d+\.\d+\.\d+/)
      if (ipMatch) params.target = ipMatch[0]
    }
  }

  if (toolName === 'CleanTempTool') {
    const daysMatch = message.match(/(\d+)\s*天/)
    if (daysMatch) {
      params.days = parseInt(daysMatch[1])
    } else {
      params.days = 7
    }
    params.dryRun = lowerMsg.includes('预览') || lowerMsg.includes('dry-run')
  }

  if (toolName === 'ProcessTool') {
    if (lowerMsg.includes('内存')) {
      params.minMem = 0
    }
  }

  if (toolName === 'LogAnalysisTool') {
    const linesMatch = message.match(/(\d+)\s*行/)
    if (linesMatch) {
      params.lines = parseInt(linesMatch[1])
    } else {
      params.lines = 100
    }
  }

  if (toolName === 'ConfigCheckTool') {
    const pathMatch = message.match(/\/[\w\/\.-]+/)
    if (pathMatch) {
      params.configPath = pathMatch[0]
    }
  }

  if (toolName === 'DiskInsightTool') {
    const pathMatch = message.match(/\/[\w\/\.-]+/)
    params.rootPath = pathMatch ? pathMatch[0] : '/tmp'
    const depthMatch = message.match(/深度\s*(\d+)/)
    if (depthMatch) {
      params.maxDepth = parseInt(depthMatch[1], 10)
    }
    const topMatch = message.match(/前\s*(\d+)\s*条/)
    if (topMatch) {
      params.topN = parseInt(topMatch[1], 10)
    }
  }

  if (toolName === 'DiskAnalyzeTool') {
    const pathMatch = message.match(/\/[\w\/\.-]+/)
    if (pathMatch) {
      params.rootPath = pathMatch[0]
    }
    params.includeHotspots = !/不要热点|仅\s*df|只看分区/i.test(message)
    const topMatch = message.match(/前\s*(\d+)\s*条/)
    if (topMatch) {
      params.topN = parseInt(topMatch[1], 10)
    }
  }

  if (toolName === 'LogCleanupTool') {
    const pathMatch = message.match(/\/[\w\/\.-]+/)
    if (pathMatch) {
      params.path = pathMatch[0]
    }
    const daysMatch = message.match(/(\d+)\s*天/)
    params.days = daysMatch ? parseInt(daysMatch[1], 10) : 30
    params.dryRun = !/真实删除|执行删除|立即删除/i.test(lowerMsg)
    params.confirmDelete = /真实删除|执行删除|立即删除/i.test(lowerMsg)
  }

  if (toolName === 'ServiceRestartTool') {
    let svc = null
    if (/nginx/i.test(message)) {
      svc = 'nginx'
    }
    if (/rsyslog/i.test(message)) {
      svc = 'rsyslog'
    }
    params.serviceName = svc || 'nginx'
    params.dryRun = !/真实重启|立即重启|执行重启/i.test(lowerMsg)
    params.confirmRestart = /真实重启|立即重启|执行重启/i.test(lowerMsg)
  }

  if (toolName === 'OsInsightTool') {
    params.operation = 'journal'
    if (/\blsof\b/i.test(lowerMsg) || /pid\s*[:：]?\s*\d+/i.test(message)) {
      params.operation = 'lsof'
      const pm = message.match(/pid\s*[:：]?\s*(\d+)/i)
      params.pid = pm ? parseInt(pm[1], 10) : 1
    } else if (lowerMsg.includes('监听') || lowerMsg.includes('套接字') || lowerMsg.includes('端口')) {
      params.operation = 'ss'
    }
    const minMatch = message.match(/(\d+)\s*分钟/)
    if (minMatch) {
      params.sinceMinutes = parseInt(minMatch[1], 10)
    }
    const linesMatch = message.match(/(\d+)\s*行/)
    if (linesMatch) {
      params.maxLines = parseInt(linesMatch[1], 10)
    }
  }

  return params
}

/** 将工具返回对象转成可读中文，避免对话里出现大段 JSON */
const formatMcpFailureBlock = (result) => formatErrorMarkdown(result)

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

/** Shift+Enter 换行：依赖 textarea 默认行为，仅需占位函数避免模板绑定 undefined 导致 withModifiers 报错 */
const handleNewLine = () => {}

/** 解析「半小时后 / N分钟后 / N小时后」等为分钟数，上限 24h */
const parseDelayMinutesFromText = (raw) => {
  const t = raw.replace(/\s+/g, '')
  if (/半小时[后後]?|半个小时[后後]?/.test(t)) return 30
  let m = t.match(/(?:再过|再等)?(\d+)\s*(?:个)?(?:小时|小時|钟头|鐘頭)(?:后|後)?/)
  if (m) return Math.min(parseInt(m[1], 10) * 60, 24 * 60)
  m = t.match(/(?:再过|再等)?(\d+)\s*分钟(?:钟|鐘)?(?:后|後)?/)
  if (m) return Math.min(parseInt(m[1], 10), 24 * 60)
  m = t.match(/(\d+)\s*分钟(?:钟|鐘)?/)
  if (m && /后|後|之后|之後|以后|以後|过后|過後/.test(t)) {
    return Math.min(parseInt(m[1], 10), 24 * 60)
  }
  const cn = { 十五: 15, 三十: 30, 四十五: 45, 六十: 60, 二十: 20, 四十: 40, 五十: 50 }
  for (const [word, val] of Object.entries(cn)) {
    if (t.includes(word + '分钟') || t.includes(word + '分鐘')) {
      if (/后|後|之后|之後/.test(t)) return Math.min(val, 24 * 60)
    }
  }
  return null
}

/** 与后端 OpsDeferredTaskService.ALLOWED_DEFERRED_TOOLS 保持一致 */
const DEFERRED_ALLOWED_TOOLS = new Set([
  'DiskTool',
  'DiskAnalyzeTool',
  'DiskInsightTool',
  'ProcessTool',
  'SystemLoadTool',
  'LogAnalysisTool',
  'CleanTempTool',
  'ConfigCheckTool',
  'NetworkTool',
  'PrivilegeTool',
  'OsInsightTool'
])

const isDeferredCleanupIntent = (text) =>
  /清理|清除|删掉|刪掉|垃圾|临时文件|臨時文件|缓存|緩存|^tmp$/i.test(text) &&
  !/删除.{0,16}(整个|全部|所有|系统)|删.{0,4}(整个|全部).{0,12}系统/i.test(text)

const scheduleDeferredCleanupFlow = async (plan, rawMessage) => {
  const params = extractParameters(rawMessage, 'CleanTempTool')
  const wantRealDelete = /真实删除|彻底删除|真正删除|不要预览|不要dry-run|實際刪除/i.test(rawMessage)
  let dryRun = !wantRealDelete
  let confirmDelete = false

  if (wantRealDelete) {
    await ElMessageBox.confirm(
      `将在 ${plan.delayMinutes} 分钟后真实删除白名单目录内满足条件的临时文件（非预览）。请确认已了解风险。`,
      '高危确认 · 延时任务',
      {
        type: 'warning',
        confirmButtonText: '确认创建定时任务',
        cancelButtonText: '取消',
        closeOnClickModal: false
      }
    )
    dryRun = false
    confirmDelete = true
  } else {
    await ElMessageBox.confirm(
      `将在 ${plan.delayMinutes} 分钟后执行临时文件清理的预览（find 列出候选，不执行 rm）。是否创建？`,
      '延时任务',
      {
        type: 'info',
        confirmButtonText: '创建',
        cancelButtonText: '取消',
        closeOnClickModal: false
      }
    )
  }

  const data = await scheduleDeferredOpsTask({
    delayMinutes: plan.delayMinutes,
    toolName: plan.toolName,
    parameters: {
      ...params,
      dryRun,
      confirmDelete
    }
  })
  const taskId = data?.taskId ?? ''
  const executeAt = data?.executeAt ?? ''
  const mode = dryRun ? '预览（不删除）' : '真实删除'
  messages.value.push({
    role: 'assistant',
    content:
      `已创建延时任务。\n` +
      `- 任务 ID：${taskId}\n` +
      `- 计划执行（服务器时间）：${executeAt}\n` +
      `- 工具：CleanTempTool · 模式：${mode}\n` +
      `说明：任务保存在服务端内存，进程重启后会丢失（需已登录）。`,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  })
  ElMessage.success('延时任务已创建')
  scrollToBottom()
}

const scheduleDeferredToolFlow = async (plan, rawMessage) => {
  if (!DEFERRED_ALLOWED_TOOLS.has(plan.toolName)) {
    ElMessage.warning(`延时执行暂不支持「${plan.toolName}」，请使用清理/巡检类工具或在工作台即时执行`)
    return
  }
  if (plan.toolName === 'CleanTempTool') {
    await scheduleDeferredCleanupFlow(plan, rawMessage)
    return
  }
  await ElMessageBox.confirm(
    `将在 ${plan.delayMinutes} 分钟后执行「${plan.toolName}」（参数由当前输入解析）。是否创建延时任务？`,
    '延时任务',
    {
      type: 'info',
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      closeOnClickModal: false
    }
  )
  const params = extractParameters(rawMessage, plan.toolName)
  const data = await scheduleDeferredOpsTask({
    delayMinutes: plan.delayMinutes,
    toolName: plan.toolName,
    parameters: params
  })
  const taskId = data?.taskId ?? ''
  const executeAt = data?.executeAt ?? ''
  messages.value.push({
    role: 'assistant',
    content:
      `已创建延时任务。\n` +
      `- 任务 ID：${taskId}\n` +
      `- 计划执行（服务器时间）：${executeAt}\n` +
      `- 工具：${plan.toolName}\n` +
      `说明：任务保存在服务端内存，进程重启后会丢失（需已登录）。`,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  })
  ElMessage.success('延时任务已创建')
  scrollToBottom()
}

/** 用户明确要求不要走本地 MCP 工具时，禁止工具路由（避免「系统状态 不调用工具」仍触发 SystemLoadTool） */
const userDeclinesTools = (text) => {
  const t = (text || '').trim()
  if (!t) return false
  return /不调用工具|不要调用工具|不用工具|别用工具|禁止工具|不要工具|不要执行工具|别执行工具|仅文字|只要文字|纯对话|不要跑命令|别跑命令|不调工具|别用mcp|不用mcp/i.test(
    t
  )
}

/** 偏解释、追问、总结 → 走大模型多轮对话；简短直达指令 → 仍可走 MCP */
const shouldUseNaturalLanguageAssistant = (text) => {
  const t = text.trim()
  if (userDeclinesTools(t)) return true
  if (t.length > 140) return true
  if (/[？?]|为什么|怎么|如何|含义|说明一下|是什么意思|总结|讨论|建议|是否|能否|有没有|什么意思/.test(t)) return true
  if (/刚才|之前|上面|这个结果|那条|接着|还有/.test(t)) return true
  return false
}

const buildConversationHistory = (priorMessages) => {
  const out = []
  for (const msg of priorMessages) {
    if (msg.isToolCalling) continue
    if (msg.role === 'user') {
      const text = typeof msg.content === 'string' ? msg.content.trim() : ''
      if (text) out.push({ role: 'user', content: text.slice(0, 4000) })
      continue
    }
    if (msg.role === 'assistant') {
      let text = typeof msg.content === 'string' ? msg.content.trim() : ''
      if (!text && msg.toolResult != null && msg.toolName) {
        text = generateHumanReadableResponse(msg.toolName, msg.toolResult) || `[${msg.toolName}] 已执行`
      }
      if (text) out.push({ role: 'assistant', content: text.slice(0, 4000) })
    }
  }
  return out.slice(-24)
}

const runNaturalLanguageStream = async (message) => {
  const prior = messages.value.slice(0, -1)
  const history = buildConversationHistory(prior)
  const assistantIdx =
    messages.value.push({
      role: 'assistant',
      content: '',
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }) - 1
  loading.value = true

  try {
    const response = await assistantChatStream(message, history, { useToolAgent: true })
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        messages.value[assistantIdx].content =
          '助手暂时不可用: 未登录或会话已失效（/api/assistant 受登录保护）。请重新登录后再试；若前后端不同端口，请确认请求带 Cookie（credentials）且后端 CORS 允许当前页面来源。'
        return
      }
      const errText = await response.text().catch(() => '')
      let errMsg = `请求失败 (${response.status})`
      try {
        const j = JSON.parse(errText)
        if (j.message) errMsg = j.message
      } catch {
        if (errText) errMsg = errText.slice(0, 400)
      }
      messages.value[assistantIdx].content = '助手暂时不可用: ' + errMsg
      return
    }
    const reader = response.body?.getReader()
    if (!reader) {
      messages.value[assistantIdx].content = '服务端未返回流式数据，请确认 AI 已配置。'
      return
    }
    const decoder = new TextDecoder()
    let sseBuf = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      sseBuf = appendSsePayloadFromChunk(sseBuf, chunk, (payload) => {
        messages.value[assistantIdx].content += payload
      })
    }
    flushSseBuffer(sseBuf, (payload) => {
      messages.value[assistantIdx].content += payload
    })
    if (!messages.value[assistantIdx].content.trim()) {
      messages.value[assistantIdx].content = '（未返回内容，请检查模型服务）'
    }
    const traceMatch = messages.value[assistantIdx].content.match(/>\s*\*\*traceId:\*\*\s*`([^`]+)`/i)
    if (traceMatch) {
      messages.value[assistantIdx].traceId = traceMatch[1]
    }
  } catch (e) {
    console.error('自然语言对话失败', e)
    messages.value[assistantIdx].content =
      '连接助手失败: ' + (e.message || String(e)) + '\n请确认后端已启动且 Spring AI 模型已正确配置。'
  } finally {
    loading.value = false
  }
  scrollToBottom()
}

const handleSend = async () => {
  const rawInput = inputMessage.value.trim()
  let message = rawInput
  if (pendingAttachment.value) {
    message = `${rawInput}\n\n--- 附件: ${pendingAttachment.value.name} ---\n${pendingAttachment.value.text}`.trim()
  }
  if (!message || loading.value) return

  inputMessage.value = ''
  pendingAttachment.value = null

  messages.value.push({
    role: 'user',
    content: message,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  })
  scrollToBottom()

  if (isWorkbenchHighRiskMessage(message)) {
    loading.value = true
    try {
      messages.value.push({
        role: 'assistant',
        content: workbenchHighRiskBlockMarkdown(message),
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      })
      scrollToBottom()
    } finally {
      loading.value = false
    }
    return
  }

  const delayMinutes = parseDelayMinutesFromText(message)
  if (delayMinutes != null) {
    const toolFromIntent = parseUserIntent(message)
    const nl = shouldUseNaturalLanguageAssistant(message)
    if (toolFromIntent && !nl) {
      loading.value = true
      try {
        await scheduleDeferredToolFlow({ delayMinutes, toolName: toolFromIntent }, message)
      } catch (e) {
        const cancelled = e === undefined || e === null || e === 'cancel' || e === 'close'
        if (!cancelled) {
          console.error(e)
          ElMessage.error(e?.message || '创建延时任务失败')
        }
      } finally {
        loading.value = false
      }
      return
    }
    if (isDeferredCleanupIntent(message)) {
      loading.value = true
      try {
        await scheduleDeferredCleanupFlow({ delayMinutes, toolName: 'CleanTempTool' }, message)
      } catch (e) {
        const cancelled = e === undefined || e === null || e === 'cancel' || e === 'close'
        if (!cancelled) {
          console.error(e)
          ElMessage.error(e?.message || '创建延时任务失败')
        }
      } finally {
        loading.value = false
      }
      return
    }
  }

  loading.value = true

  try {
    const toolName = parseUserIntent(message)
    const useMcpPath = toolName && !shouldUseNaturalLanguageAssistant(message) && !userDeclinesTools(message)

    if (!useMcpPath) {
      await runNaturalLanguageStream(message)
      return
    }

    const toolCallingIndex = messages.value.push({
      role: 'assistant',
      isToolCalling: true,
      toolName: toolName,
      toolCommand: getToolCommand(toolName),
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }) - 1

    scrollToBottom()

    const params = extractParameters(message, toolName)
    console.log(`执行工具: ${toolName}, 参数:`, params)

    let result = await executeMcpTool(toolName, params, message)

    if (result && result.needConfirm === true) {
      messages.value.splice(toolCallingIndex, 1)
      loading.value = false
      try {
        await ElMessageBox.confirm(buildMcpRiskConfirmMessage(result), '安全护栏 · Agentic 风险 · 二次确认', {
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          type: 'warning',
          closeOnClickModal: false,
          customClass: 'mcp-risk-confirm-box'
        })
      } catch {
        messages.value.push({
          role: 'assistant',
          content: '已取消执行（未通过中等风险二次确认）。',
          timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        })
        scrollToBottom()
        return
      }
      loading.value = true
      const confirmCallingIndex = messages.value.push({
        role: 'assistant',
        isToolCalling: true,
        toolName: toolName,
        toolCommand: getToolCommand(toolName),
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      }) - 1
      scrollToBottom()
      result = await confirmMcpExecute(
        toolName,
        applyMcpWriteConfirmParams(toolName, params, message),
        '确认执行',
        message
      )
      messages.value.splice(confirmCallingIndex, 1)
    } else {
      messages.value.splice(toolCallingIndex, 1)
    }

    result = normalizeMcpToolResponse(result)

    const humanReadable = generateHumanReadableResponse(toolName, result.data)
    const traceId = result.traceId ? String(result.traceId) : ''
    const traceLine = traceId
      ? `\n\n---\n**追踪 ID**（与「运维链路 / 审计」关联）：\`${traceId}\`\n`
      : ''

    let summaryMd = humanReadable && String(humanReadable).trim() ? humanReadable : ''
    if (!summaryMd) {
      summaryMd = result.success
        ? '工具已执行完成，但未解析到可展示字段。'
        : `**失败说明**\n\n${formatMcpFailureBlock(result)}`
    }
    if (!result.success && (result.securityCode != null || result.riskScore != null)) {
      const gate = []
      if (result.error) gate.push(`- ${String(result.error)}`)
      if (result.securityCode) gate.push(`- 安全码: \`${result.securityCode}\``)
      if (result.riskScore != null) gate.push(`- Agentic 风险分: **${result.riskScore} / 10**`)
      if (result.riskExplanation) gate.push(`- ${result.riskExplanation}`)
      summaryMd = `### 安全门拦截\n\n${gate.join('\n')}\n\n---\n\n` + summaryMd
    }

    const statusTitle =
      result.success
        ? '执行成功'
        : result.securityCode
          ? '安全拦截'
          : '执行失败'
    const durationLine =
      result.duration != null && result.duration > 0 ? `\n\n*耗时约 ${result.duration} ms*` : ''
    const fullMd =
      `## ${toolName}\n\n### ${statusTitle}\n\n` + summaryMd + durationLine + traceLine

    messages.value.push({
      role: 'assistant',
      content: fullMd,
      toolName,
      success: result.success,
      duration: result.duration || 0,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })

    scrollToBottom()

  } catch (error) {
    console.error('执行失败:', error)
    messages.value.push({
      role: 'assistant',
      content: `❌ 执行出错: ${error.message}\n\n请检查输入是否正确，或稍后重试。`,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })
    scrollToBottom()
  } finally {
    loading.value = false
  }
}

const sendQuickCommand = (command) => {
  inputMessage.value = command
  handleSend()
}

function applyPrefill(text) {
  if (typeof text !== 'string' || !text.trim()) return
  inputMessage.value = text.trim()
  nextTick(() => scrollToBottom())
}

defineExpose({ applyPrefill })

onUnmounted(() => {
  if (patrolPollTimer) {
    clearInterval(patrolPollTimer)
  }
  if (speechRecognition) {
    try {
      speechRecognition.stop()
    } catch {
      /* noop */
    }
  }
})

const clearChat = () => {
  messages.value = []
}
</script>

<style scoped>
.ops-chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  box-shadow: var(--ops-shadow-sm);
  overflow: hidden;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  background: var(--ops-panel-soft);
  border-bottom: 1px solid var(--ops-border);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-mark {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.header-info h3 {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ops-text);
}

.status-text {
  font-size: 12px;
  color: var(--ops-text-subtle);
  display: flex;
  align-items: center;
  gap: 6px;
}

.online-dot {
  width: 6px;
  height: 6px;
  background: #10b981;
  border-radius: 50%;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
  background: var(--ops-bg);
}

.welcome-area {
  max-width: 920px;
  margin: 0 auto;
  padding: 8px 4px 24px;
  text-align: left;
}

.welcome-intro h2 {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--ops-text);
  letter-spacing: -0.02em;
}

.welcome-intro p {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ops-text-subtle);
  max-width: 56ch;
}

.quick-group {
  margin-bottom: 18px;
}

.quick-group-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ops-text-subtle);
  margin-bottom: 8px;
}

.capability-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}

.capability-item {
  appearance: none;
  border: 1px solid var(--ops-border);
  background: var(--ops-panel);
  padding: 10px 12px;
  border-radius: var(--ops-radius-sm);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ops-text);
  text-align: left;
}

.capability-item:hover {
  border-color: #94a3b8;
  background: var(--ops-panel-soft);
}

.capability-item--alert {
  border-color: rgba(245, 158, 11, 0.45);
  background: #fffbeb;
}

.capability-item--alert:hover {
  border-color: #f59e0b;
}

.cap-icon {
  font-size: 16px;
  color: var(--ops-primary);
  flex-shrink: 0;
}

.cap-label {
  line-height: 1.3;
  font-weight: 500;
}

.message-wrapper {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  align-items: flex-start;
}

.message-wrapper.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.message-avatar--user {
  background: #e2e8f0;
  color: #475569;
}

.message-content {
  max-width: 75%;
  min-width: 200px;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
}

.message-wrapper.user .message-header {
  flex-direction: row-reverse;
}

.sender-name {
  color: #303133;
  font-weight: 600;
}

.timestamp {
  color: #909399;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

/* Markdown 气泡：不用 pre-wrap，避免标题/列表排版塌成一团 */
.message-bubble.assistant-bubble:has(.markdown-content) {
  white-space: normal;
}

.user-bubble {
  background: var(--ops-primary);
  color: #f8fafc;
  border-bottom-right-radius: 4px;
}

.assistant-bubble {
  background: var(--ops-panel);
  color: var(--ops-text);
  border: 1px solid var(--ops-border);
  border-bottom-left-radius: 4px;
  box-shadow: var(--ops-shadow-sm);
}

.typing {
  display: flex;
  gap: 4px;
  padding: 16px 20px;
}

.typing-dot {
  width: 8px;
  height: 8px;
  background: #909399;
  border-radius: 50%;
  animation: typingBounce 1.4s infinite ease-in-out;
}

.typing-dot:nth-child(1) { animation-delay: 0s; }
.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typingBounce {
  0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
  40% { transform: scale(1.2); opacity: 1; }
}

.tool-calling {
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: var(--ops-radius-sm);
  padding: 10px 12px;
  margin-bottom: 10px;
}

.tool-calling-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #b45309;
  font-weight: 500;
  font-size: 13px;
}

.tool-command {
  margin-top: 8px;
  background: #1e1e1e;
  border-radius: 6px;
  padding: 8px 12px;
}

.tool-command code {
  color: #d4d4d4;
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.tool-result {
  background: #f5f7fa;
  border-radius: 12px;
  margin-top: 8px;
  overflow: hidden;
  border: 1px solid #e4e8eb;
}

.tool-result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #e4e8eb;
  border-bottom: 1px solid #dcdfe6;
}

.tool-badge {
  background: #409eff;
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.duration-badge {
  color: #606266;
  font-size: 12px;
}

.tool-result-content {
  padding: 12px;
  max-height: 300px;
  overflow-y: auto;
}

.tool-result-content pre {
  margin: 0;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
}

.chat-input-area {
  padding: 12px 16px 14px;
  background: var(--ops-panel);
  border-top: 1px solid var(--ops-border);
}

.attachment-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 12px;
  background: #f4f4f5;
  border-radius: 8px;
  font-size: 13px;
  color: #606266;
}

.hidden-file-input {
  display: none;
}

.icon-btn {
  flex-shrink: 0;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.input-wrapper :deep(.el-textarea__inner) {
  border-radius: var(--ops-radius-sm);
  padding: 10px 12px;
  resize: none;
}

.send-btn {
  border-radius: var(--ops-radius-sm);
  padding: 10px 16px;
}

.input-hints {
  margin-top: 8px;
  text-align: center;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.markdown-content {
  line-height: 1.8;
}

.assistant-markdown :deep(h2) {
  font-size: 1.12rem;
  font-weight: 600;
  margin: 1em 0 0.5em;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 0.25em;
}

.assistant-markdown :deep(h3) {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0.65em 0 0.35em;
  color: #606266;
}

.assistant-markdown :deep(p) {
  margin: 0.35em 0;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  margin: 0.35em 0 0.5em;
  padding-left: 1.25em;
}

.assistant-markdown :deep(li) {
  margin: 0.2em 0;
}

.assistant-markdown :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.5em 0;
  font-size: 0.9rem;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  border: 1px solid #dcdfe6;
  padding: 6px 8px;
  text-align: left;
}

.assistant-markdown :deep(th) {
  background: #f5f7fa;
}

.assistant-markdown :deep(blockquote) {
  margin: 0.5em 0;
  padding: 0.35em 0.75em;
  border-left: 3px solid #409eff;
  background: #ecf5ff;
  color: #606266;
}

.assistant-markdown :deep(hr) {
  border: none;
  border-top: 1px solid #ebeef5;
  margin: 0.75em 0;
}

.markdown-content :deep(code) {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
}

.assistant-markdown :deep(pre code) {
  display: block;
  padding: 10px 12px;
  overflow-x: auto;
  white-space: pre;
}

.plain-content {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
  line-height: 1.6;
}

.trace-id-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.trace-id-hint code {
  font-size: 11px;
}

.trace-hint {
  opacity: 0.85;
}

.patrol-pending-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 16px 8px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9, #fdf6ec);
  border: 1px solid var(--el-color-warning-light-5, #f5dab1);
}

.patrol-pending-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  min-width: 0;
}

.patrol-summary {
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.capability-item.patrol-cap {
  border-color: var(--el-color-warning);
}
</style>

<style>
/* MessageBox 挂载在 body，须非 scoped */
.mcp-risk-confirm-box .el-message-box__message {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: min(48vh, 420px);
  overflow-y: auto;
}
</style>
