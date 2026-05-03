<template>
  <div class="ops-chat-container">
    <div class="chat-header">
      <div class="header-left">
        <span class="robot-icon">🤖</span>
        <div class="header-info">
          <h3>工作台助手</h3>
          <span class="status-text">
            <span class="online-dot"></span>
            Windows / Linux · 本地工具 · 延时任务
          </span>
        </div>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" circle @click="clearChat" title="清空对话" />
      </div>
    </div>

    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="welcome-area">
        <div class="welcome-icon">🛡️</div>
        <h2>本地工作台</h2>
        <p>
          支持 <strong>Windows 与 Linux</strong> 部署节点：多轮对话（含当前机器上下文），工具在
          <strong>服务端所在操作系统</strong> 上执行；支持语音与文本附件。
        </p>
        <div class="capability-list">
          <div class="capability-item" @click="sendQuickCommand('检查磁盘使用情况')">
            <span class="cap-icon">💾</span>
            <span>磁盘检查</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('扫描 /tmp 磁盘占用热点')">
            <span class="cap-icon">📂</span>
            <span>磁盘热点 (Linux)</span>
          </div>
          <div
            class="capability-item"
            @click="
              sendQuickCommand(
                '请对比说明 Linux 与 Windows 服务器上如何排查磁盘占满：常用命令、临时目录路径与安全注意点？'
              )
            "
          >
            <span class="cap-icon">📁</span>
            <span>磁盘排查 (Win/Linux)</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('查看系统负载')">
            <span class="cap-icon">📈</span>
            <span>系统负载</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('分析最近的系统日志')">
            <span class="cap-icon">📝</span>
            <span>日志分析</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('查看 journalctl 最近30分钟日志')">
            <span class="cap-icon">📡</span>
            <span>OS 日志 (Linux)</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('查看本机监听端口与网络套接字概况')">
            <span class="cap-icon">🔌</span>
            <span>端口 / 套接字</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('检查网络连通性 ping 8.8.8.8')">
            <span class="cap-icon">🌐</span>
            <span>网络诊断</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('查看占用资源最多的进程')">
            <span class="cap-icon">⚙️</span>
            <span>进程管理</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('清理7天前的临时文件')">
            <span class="cap-icon">🧹</span>
            <span>文件清理</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('磁盘压力综合分析（含热点）')">
            <span class="cap-icon">📊</span>
            <span>磁盘综合诊断</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('预览清理 /var/log 下 30 天前的陈旧日志文件')">
            <span class="cap-icon">🗂️</span>
            <span>日志清理预览</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('预览重启 nginx 服务（dry-run）')">
            <span class="cap-icon">🔁</span>
            <span>服务重启预览</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('30分钟之后帮我预览清理临时文件')">
            <span class="cap-icon">⏱️</span>
            <span>延时清理</span>
          </div>
          <div class="capability-item" @click="sendQuickCommand('20分钟之后检查磁盘使用情况')">
            <span class="cap-icon">⏲️</span>
            <span>延时巡检</span>
          </div>
        </div>
      </div>

      <div v-for="(msg, index) in messages" :key="index" :class="['message-wrapper', msg.role]">
        <div class="message-avatar" v-if="msg.role === 'assistant'">🤖</div>
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
            <pre v-else class="plain-content">{{ msg.content }}</pre>
          </div>
          <div v-else class="message-bubble user-bubble">
            {{ msg.content }}
          </div>

          <div v-if="msg.toolResult" class="tool-result">
            <div class="tool-result-header">
              <span class="tool-badge">{{ msg.toolName }}</span>
              <span class="duration-badge">{{ msg.duration }}ms</span>
              <el-tag v-if="msg.success" type="success" size="small">成功</el-tag>
              <el-tag v-else type="danger" size="small">失败</el-tag>
            </div>
            <div class="tool-result-content">
              <pre>{{ formatToolResult(msg.toolResult) }}</pre>
            </div>
          </div>
        </div>
        <div class="message-avatar" v-if="msg.role === 'user'">👤</div>
      </div>

      <div v-if="loading" class="message-wrapper assistant">
        <div class="message-avatar">🤖</div>
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
import { ref, nextTick, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Promotion, Loading, Microphone, FolderOpened } from '@element-plus/icons-vue'
import {
  executeMcpTool,
  confirmMcpExecute,
  assistantChatStream,
  scheduleDeferredOpsTask
} from '../api'
import { normalizeMcpToolResponse } from '../utils/mcpToolResult'
import { getMcpToolCommandHint } from '../utils/mcpToolsMeta'
import { buildMcpRiskConfirmMessage } from '../utils/mcpRiskConfirm'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import MarkdownIt from 'markdown-it'

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
const listeningVoice = ref(false)
let speechRecognition = null

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

const TOOLS_UNWRAP_INNER = new Set([
  'DiskTool',
  'ProcessTool',
  'SystemLoadTool',
  'LogAnalysisTool',
  'CleanTempTool',
  'LogCleanupTool',
  'ServiceRestartTool',
  'ConfigCheckTool',
  'NetworkTool',
  'PrivilegeTool'
])

function parseMaybeJsonString(val) {
  if (typeof val !== 'string') return val
  const t = val.trim()
  if (!t.startsWith('{') && !t.startsWith('[')) return val
  try {
    return JSON.parse(t)
  } catch {
    return val
  }
}

const getToolCommand = (toolName) => getMcpToolCommandHint(toolName)

const parseUserIntent = (message) => {
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
  if (lowerMsg.includes('系统') || lowerMsg.includes('状态') || lowerMsg.includes('概览')) {
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

const formatToolResult = (result) => {
  if (typeof result === 'string') {
    try {
      return JSON.stringify(JSON.parse(result), null, 2)
    } catch {
      return result
    }
  }
  return JSON.stringify(result, null, 2)
}

const generateHumanReadableResponse = (toolName, result) => {
  try {
    let data = typeof result === 'string' ? JSON.parse(result) : result

    if (TOOLS_UNWRAP_INNER.has(toolName) && data && data.success === true && data.data != null) {
      const rawInner = data.data
      data = typeof rawInner === 'string' ? parseMaybeJsonString(rawInner) : rawInner
    }

    switch (toolName) {
      case 'DiskTool': {
        const diskArr = Array.isArray(data) ? data : null
        if (diskArr?.length) {
          const highUsage = diskArr.filter(d => {
            const percent = parseInt(String(d.usePercent || '').replace('%', ''), 10)
            return percent > 80
          })
          if (highUsage.length > 0) {
            return `发现 ${highUsage.length} 个分区使用率超过 80%:\n` +
              highUsage.map(d => `• ${d.mountedOn}: ${d.usePercent} (已用 ${d.used}/共 ${d.size})`).join('\n')
          }
          return '所有磁盘分区使用率正常，最高为 ' + (diskArr[0].usePercent || '-')
        }
        break
      }

      case 'ProcessTool':
        if (Array.isArray(data)) {
          return `当前有 ${data.length} 个进程 CPU/内存占用超过阈值:\n` +
            data.slice(0, 5).map((p, i) =>
              `${i + 1}. ${p.command?.substring(0, 30) || 'unknown'} (PID: ${p.pid}, CPU: ${p.cpu}%, MEM: ${p.mem}%)`
            ).join('\n')
        }
        break

      case 'SystemLoadTool': {
        if (data.cpuUsagePercent === undefined) break
        const badCpu = data.cpuUsagePercent < 0
        const badMem = data.memUsagePercent < 0
        const cpuLine = badCpu
          ? '• CPU 使用率: 未采集（常见于 Windows 或 top 输出格式与 Linux 不一致；可用上下文中的 performance 或改用自然语言说明）'
          : `• CPU 使用率: ${data.cpuUsagePercent}%`
        const memLine = badMem
          ? '• 内存使用率: 未采集（同上）'
          : `• 内存使用率: ${data.memUsagePercent}%`
        return `系统负载概况:\n${cpuLine}\n${memLine}\n` +
          `• 1分钟负载: ${data.loadAvg1min}\n` +
          `• 5分钟负载: ${data.loadAvg5min}\n` +
          `• 15分钟负载: ${data.loadAvg15min}`
      }

      case 'LogAnalysisTool':
        if (data.status) {
          const statusText = data.status === 'HEALTHY' ? '✅ 健康' : '⚠️ 存在异常'
          let response = `日志分析完成 (${data.logPath})\n状态: ${statusText}\n分析行数: ${data.linesAnalyzed}`
          if (data.errorCount > 0) {
            response += `\n检测到 ${data.errorCount} 条异常`
          }
          if (data.rootCause) {
            response += `\n\n根因分析:\n${data.rootCause}`
          }
          if (data.recommendation) {
            response += `\n\n修复建议:\n${data.recommendation}`
          }
          return response
        }
        break

      case 'CleanTempTool':
        if (data.mode === 'DRY-RUN') {
          return `Dry-Run 预览:\n` +
            `• 路径: ${data.path}\n` +
            `• 将删除 ${data.filesFound} 个文件\n` +
            `• 预览前 ${data.previewCount} 个:\n` +
            (data.preview || []).map(f => `  - ${f}`).join('\n')
        } else {
          return `清理完成:\n` +
            `• 路径: ${data.path}\n` +
            `• 发现文件: ${data.filesFound}\n` +
            `• 实际删除: ${data.filesDeleted} 个文件`
        }

      case 'LogCleanupTool':
        if (data.mode === 'DRY-RUN') {
          return `日志清理 Dry-Run:\n` +
            `• 路径: ${data.path}\n` +
            `• 候选文件: ${data.filesFound}\n` +
            `• 预览 ${data.previewCount ?? '-'} 条:\n` +
            (data.preview || []).map(f => `  - ${f}`).join('\n')
        }
        return `日志清理执行:\n• 路径: ${data.path}\n• 候选: ${data.filesFound}\n• 已删: ${data.filesDeleted}`

      case 'ServiceRestartTool':
        if (data.mode === 'DRY-RUN') {
          return `服务重启预览:\n• ${data.service}\n• ${data.plan || ''}`
        }
        return `服务重启结果:\n• ${data.service}\n• success=${data.success}\n• ${data.stderr || ''}`

      case 'DiskAnalyzeTool': {
        let d = data
        if (typeof d === 'string') {
          try {
            d = JSON.parse(d)
          } catch {
            break
          }
        }
        const ov = d?.overview
        const hot = d?.hotspotsRank
        let text = '磁盘综合诊断\n'
        text += typeof ov === 'string' ? ov : JSON.stringify(ov ?? {}, null, 2)
        if (hot != null) {
          text += '\n\n热点扫描\n'
          text += typeof hot === 'string' ? hot : JSON.stringify(hot, null, 2)
        }
        if (d?.hint) {
          text += '\n\n' + d.hint
        }
        return text
      }

      case 'ConfigCheckTool':
        const passed = data.passed ? '✅ 通过' : '❌ 失败'
        return `配置文件检查 (${data.configType}):\n` +
          `• 路径: ${data.configPath}\n` +
          `• 结果: ${passed}\n` +
          `• 退出码: ${data.exitCode}\n` +
          (data.message ? `• 信息: ${data.message}` : '')

      case 'NetworkTool':
        if (data.type === 'ping') {
          return `Ping 诊断 (${data.target}):\n` +
            `• 发送: ${data.transmitted} 个包\n` +
            `• 接收: ${data.received} 个包\n` +
            `• 丢包率: ${data.packetLossPercent}%\n` +
            `• 平均延迟: ${data.avgLatencyMs} ms\n` +
            `• 状态: ${data.success ? '✅ 网络正常' : '❌ 网络异常'}`
        } else if (data.type === 'traceroute') {
          return `路由追踪 (${data.target}):\n` +
            `• 经过 ${data.hopCount} 跳\n` +
            (data.hops || []).map(h =>
              `  ${h.hop}. ${h.rtt1} | ${h.rtt2} | ${h.rtt3}`
            ).join('\n')
        }
        break

      case 'PrivilegeTool':
        const hasPriv = data.hasPrivilege ? '✅ 有权限' : (data.needsSudo ? '⚠️ 需要 sudo' : '❌ 无权限')
        return `权限检查 (${data.resource}):\n` +
          `• 当前用户: ${data.currentUser}\n` +
          `• 操作类型: ${data.action}\n` +
          `• 权限状态: ${hasPriv}\n` +
          (data.suggestion ? `• 建议: ${data.suggestion}` : '')

      case 'DiskInsightTool': {
        if (data && data.success === false) {
          return `磁盘热点扫描失败: ${data.error || ''}`
        }
        let inner = data
        if (inner && inner.data != null && typeof inner.entries === 'undefined') {
          inner = typeof inner.data === 'string' ? JSON.parse(inner.data) : inner.data
        }
        if (!inner || typeof inner !== 'object') {
          return '磁盘热点：返回数据格式异常'
        }
        const entries = inner.entries || []
        if (!entries.length) {
          return `目录 ${inner.root || ''} 下未返回占用条目（可能为空或无权限）。`
        }
        const fmtKb = (kb) => {
          const n = Number(kb) || 0
          if (n >= 1048576) return `${(n / 1048576).toFixed(2)} GB`
          if (n >= 1024) return `${(n / 1024).toFixed(2)} MB`
          return `${n} KB`
        }
        return `磁盘热点 (${inner.root || inner.rootPath || ''})，深度 ${inner.maxDepth ?? '-'}，前 ${entries.length} 条：\n` +
          entries.map((e, i) => `${i + 1}. ${fmtKb(e.kb)} — ${e.path}`).join('\n')
      }

      case 'OsInsightTool': {
        let p = typeof result === 'string' ? JSON.parse(result) : result
        if (p && p.success === true && p.data != null) {
          p = typeof p.data === 'string' ? parseMaybeJsonString(p.data) : p.data
        }
        if (typeof p === 'string') {
          p = parseMaybeJsonString(p)
        }
        if (!p || typeof p !== 'object') {
          return 'OS 感知：返回结构无法解析'
        }
        if (p.success === false) {
          return `OS 感知失败 (${p.tool || ''}): ${p.error || ''}`
        }
        const out = p.output != null ? String(p.output) : ''
        const head = out.length > 1600 ? out.slice(0, 1600) + '\n...[truncated]' : out
        return `OS 感知 [${p.tool || ''}]${p.command ? `\n命令: ${p.command}` : ''}\n${head || '(无输出)'}`
      }

      default:
        break
    }

    try {
      const shown =
        typeof data === 'string'
          ? data
          : JSON.stringify(data, null, 2)
      const clipped = shown.length > 10000 ? shown.slice(0, 10000) + '\n…(已截断)' : shown
      return `### 结构化结果\n\n\`\`\`json\n${clipped}\n\`\`\``
    } catch {
      return String(data)
    }
  } catch (e) {
    console.error('格式化结果失败:', e)
  }
  return null
}

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

const isDeferredCleanupIntent = (text) =>
  /清理|清除|删掉|刪掉|删除|刪除|垃圾|临时文件|臨時文件|缓存|緩存|^tmp$/i.test(text)

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
        text = `[${msg.toolName}] ${formatToolResult(msg.toolResult)}`.trim()
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
  loading.value = false

  try {
    const response = await assistantChatStream(message, history)
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
  } catch (e) {
    console.error('自然语言对话失败', e)
    messages.value[assistantIdx].content =
      '连接助手失败: ' + (e.message || String(e)) + '\n请确认后端已启动且 Spring AI 模型已正确配置。'
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

    let result = await executeMcpTool(toolName, params)

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
      result = await confirmMcpExecute(toolName, params, '确认执行')
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
        ? '工具已正常返回，结构化数据见下方 **原始输出**。'
        : `**失败说明**\n\n\`\`\`\n${formatToolResult(result.data)}\n\`\`\``
    }
    if (!result.success && (result.securityCode != null || result.riskScore != null)) {
      const gate = []
      if (result.error) gate.push(`- ${String(result.error)}`)
      if (result.securityCode) gate.push(`- 安全码: \`${result.securityCode}\``)
      if (result.riskScore != null) gate.push(`- Agentic 风险分: **${result.riskScore} / 10**`)
      if (result.riskExplanation) gate.push(`- ${result.riskExplanation}`)
      summaryMd = `### 安全门拦截\n\n${gate.join('\n')}\n\n---\n\n` + summaryMd
    }

    const statusTitle = result.success ? '执行成功' : '执行失败'
    const fullMd =
      `## ${toolName}\n\n### ${statusTitle}\n\n` +
      summaryMd +
      traceLine +
      `\n\n> **答辩提示**：摘要已在上方；完整 JSON 在下方灰色卡片中可滚动，无需单独打开审计日志。`

    messages.value.push({
      role: 'assistant',
      content: fullMd,
      toolName,
      toolResult: result.data,
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
  background: linear-gradient(180deg, #f5f7fa 0%, #e4e8eb 100%);
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.robot-icon {
  font-size: 40px;
}

.header-info h3 {
  margin: 0 0 4px 0;
  font-size: 18px;
}

.status-text {
  font-size: 12px;
  opacity: 0.9;
  display: flex;
  align-items: center;
  gap: 6px;
}

.online-dot {
  width: 8px;
  height: 8px;
  background: #67c23a;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.welcome-area {
  text-align: center;
  padding: 60px 20px;
  max-width: 600px;
  margin: 0 auto;
}

.welcome-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.welcome-area h2 {
  color: #303133;
  margin-bottom: 10px;
}

.welcome-area p {
  color: #606266;
  margin-bottom: 30px;
}

.capability-list {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.capability-item {
  background: white;
  padding: 16px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.capability-item:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.3);
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.cap-icon {
  font-size: 28px;
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
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #f0f2f5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
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
  background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.assistant-bubble {
  background: white;
  color: #303133;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
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
  background: linear-gradient(135deg, #fdf6ec 0%, #fef0e6 100%);
  border: 1px solid #f5dab3;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.tool-calling-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e6a23c;
  font-weight: 500;
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
  padding: 16px 24px;
  background: white;
  border-top: 1px solid #e4e8eb;
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
  border-radius: 20px;
  padding: 10px 16px;
  resize: none;
}

.send-btn {
  border-radius: 20px;
  padding: 10px 20px;
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
