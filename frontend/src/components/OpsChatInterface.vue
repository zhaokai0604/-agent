<template>
  <div class="ops-page ops-chat-container ops-chat-container--agent">
    <div v-if="patrolPending?.hasPending" class="patrol-pending-banner agent-chat-column">
      <div class="patrol-pending-text">
        <strong>巡检待处理</strong>
        <span class="patrol-summary">{{ patrolPending.summary || '有待确认修复步骤' }}</span>
      </div>
      <el-button type="primary" size="small" :loading="patrolContinueLoading" @click="continuePatrolPending">
        继续处理
      </el-button>
    </div>

    <div class="chat-messages" ref="messagesContainer" @scroll="onMessagesScroll">
      <div v-if="messages.length === 0" class="agent-chat-column">
        <AgentWelcome
          :patrol-pending="patrolPending?.hasPending"
          @select="sendQuickCommand"
          @patrol="continuePatrolPending"
          @open-skills="openSkills"
        />
      </div>

      <div class="agent-chat-column messages-inner" v-if="messages.length > 0">
        <section v-for="(turn, ti) in chatTurns" :key="ti" class="chat-turn">
          <div v-if="turn.user" class="chat-turn__user">
            <div class="chat-turn__user-bubble">{{ turn.user.content }}</div>
          </div>

          <div
            v-for="{ msg, index } in turn.assistants"
            :key="index"
            class="chat-turn__assistant"
          >
            <div v-if="msg.isToolCalling" class="tool-calling">
              <div class="tool-calling-header">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>正在执行 {{ msg.toolName ? mcpToolDisplayName(msg.toolName) : '工具' }}…</span>
              </div>
              <div class="tool-command" v-if="msg.toolCommand">
                <code>{{ msg.toolCommand }}</code>
              </div>
            </div>

            <template v-else>
              <AssistantProse
                v-if="msg.content"
                :content="msg.content"
                :streaming="loading && streaming && index === messages.length - 1"
              />
              <RemediationPlanCard
                v-if="msg.remediationPlan && !msg.writeConfirmed"
                :plan="msg.remediationPlan"
                :loading="loading && confirmPlanIndex === index"
                @confirm="confirmPlanFromMessage(index)"
              />
              <AssistantEvidenceBar
                :rag-hits="msg.ragHits"
                :tools-used="msg.toolsUsed || (msg.toolName ? [msg.toolName] : [])"
                :reply-mode="msg.meta?.replyMode || msg.streamEvent?.replyMode"
                :security-outcome="msg.meta?.securityOutcome || msg.streamEvent?.securityOutcome"
                :awm-workflow-id="msg.meta?.awmWorkflowId || msg.streamEvent?.awmWorkflowId"
                :awm-workflow-title="msg.meta?.awmWorkflowTitle || msg.streamEvent?.awmWorkflowTitle"
                :trace-id="msg.traceId || msg.streamEvent?.traceId"
                @open-knowledge="openKnowledge"
              />
            </template>
          </div>
        </section>

        <div v-if="loading && streaming" class="chat-turn agent-stream-status">
          <div class="agent-stream-status__inner">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ streamStatusLabel }}</span>
          </div>
        </div>
        <div v-else-if="loading" class="chat-turn">
          <div class="prose-typing">
            <span class="prose-typing-dot"></span>
            <span class="prose-typing-dot"></span>
            <span class="prose-typing-dot"></span>
          </div>
        </div>
      </div>
    </div>

    <button
      v-if="showScrollDown"
      type="button"
      class="scroll-down-btn"
      title="回到底部"
      @click="scrollToBottom(true)"
    >
      <el-icon><ArrowDown /></el-icon>
    </button>

    <div class="agent-chat-column composer-sticky">
      <AgentQuickBar
        v-if="messages.length > 0"
        :loading="loading"
        @select="sendQuickCommand"
      />
      <AgentComposer
        v-model="inputMessage"
        :loading="loading"
        :stoppable="loading"
        :listening-voice="listeningVoice"
        :pending-attachment="pendingAttachment"
        :route-hint="routeHint"
        @send="handleSend"
        @stop="stopGeneration"
        @newline="handleNewLine"
        @attach="onAttachFileFromComposer"
        @toggle-voice="toggleVoiceInput"
        @clear-attachment="clearAttachment"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, ArrowDown } from '@element-plus/icons-vue'
import AgentWelcome from './agent/AgentWelcome.vue'
import AgentComposer from './agent/AgentComposer.vue'
import AgentQuickBar from './agent/AgentQuickBar.vue'
import AssistantProse from './agent/AssistantProse.vue'
import { useComposerRouteHint } from '../composables/useComposerRouteHint'
import { useAgentSession } from '../composables/useAgentSession'
import {
  assistantChatStream,
  getPatrolRemediationPending
} from '../api'
import { generateHumanReadableResponse } from '../utils/mcpHumanReadable'
import { mcpToolDisplayName, extractToolsUsedFromText } from '../utils/mcpToolsMeta'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import { isWorkbenchHighRiskMessage, workbenchHighRiskBlockMarkdown } from '../utils/opsIntentSafety'
import {
  userDeclinesTools,
  userConfirmedRemediation
} from '../utils/opsMissionIntent'
import RemediationPlanCard from './RemediationPlanCard.vue'
import AssistantEvidenceBar from './AssistantEvidenceBar.vue'
import { parseRemediationPlan, hasActionableRemediationPlan } from '../utils/remediationPlan'
import { handleAssistantSsePayload } from '../utils/assistantSseMeta'

const { messages, loadSession, clearSession } = useAgentSession()
const inputMessage = ref('')
const routeHint = useComposerRouteHint(inputMessage)
const loading = ref(false)
const streaming = ref(false)
const showScrollDown = ref(false)
let generationToken = 0
let streamAbortController = null
let streamReader = null
const messagesContainer = ref(null)
const pendingAttachment = ref(null)
const patrolPending = ref({ hasPending: false })
const patrolContinueLoading = ref(false)
const listeningVoice = ref(false)
const confirmPlanIndex = ref(-1)
const skipRemediationConfirm = ref(false)
let speechRecognition = null

const chatTurns = computed(() => {
  const turns = []
  let current = null
  for (let i = 0; i < messages.value.length; i++) {
    const msg = messages.value[i]
    if (msg.role === 'user') {
      if (current) turns.push(current)
      current = { user: msg, assistants: [] }
    } else if (current) {
      current.assistants.push({ msg, index: i })
    } else {
      turns.push({ user: null, assistants: [{ msg, index: i }] })
    }
  }
  if (current) turns.push(current)
  return turns
})

const streamStatusLabel = computed(() => {
  const last = messages.value[messages.value.length - 1]
  const mode = last?.meta?.replyMode || last?.streamEvent?.replyMode
  if (mode === 'ORCHESTRATE') return '正在全面巡检…'
  if (mode === 'TOOL_AGENT') return '正在调用运维工具采集数据…'
  return '正在思考…'
})

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
  loadSession()
  refreshPatrolPending()
  patrolPollTimer = setInterval(refreshPatrolPending, 120000)
})

const clearAttachment = () => {
  pendingAttachment.value = null
}

function readAttachmentFile(f) {
  if (!f) return
  const maxBytes = 48 * 1024
  if (f.size > maxBytes) {
    ElMessage.warning('文本附件请勿超过 48KB')
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
}

const onAttachFileFromComposer = (file) => {
  readAttachmentFile(file)
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

const scrollToBottom = (force = false) => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      if (force) showScrollDown.value = false
    }
  })
}

const onMessagesScroll = () => {
  const el = messagesContainer.value
  if (!el) return
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollDown.value = dist > 100
}

const isGenerationCancelled = (token) => token !== generationToken

const stopGeneration = () => {
  generationToken += 1
  streaming.value = false
  loading.value = false
  if (streamAbortController) {
    streamAbortController.abort()
    streamAbortController = null
  }
  if (streamReader) {
    streamReader.cancel().catch(() => {})
    streamReader = null
  }
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant') {
    if (!String(last.content || '').trim()) {
      messages.value.pop()
    } else if (!String(last.content).includes('（已停止生成）')) {
      last.content = `${last.content}\n\n（已停止生成）`
    }
  }
}

/** Shift+Enter 换行：依赖 textarea 默认行为，仅需占位函数避免模板绑定 undefined 导致 withModifiers 报错 */
const handleNewLine = () => {}

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

function applySseToAssistant (assistantIdx, payload) {
  handleAssistantSsePayload(payload, {
    onMeta: (meta) => {
      const msg = messages.value[assistantIdx]
      if (!msg) return
      msg.meta = { ...(msg.meta || {}), ...meta }
      if (Array.isArray(meta.ragHits)) msg.ragHits = meta.ragHits
      if (meta.writeConfirmed) msg.writeConfirmed = true
    },
    onEvent: (event) => {
      const msg = messages.value[assistantIdx]
      if (!msg || !event) return
      msg.streamEvent = event
      if (event.traceId) msg.traceId = event.traceId
      if (Array.isArray(event.toolsUsed) && event.toolsUsed.length) {
        msg.toolsUsed = event.toolsUsed
      }
      if (event.replyMode || event.securityOutcome || event.awmWorkflowId) {
        msg.meta = {
          ...(msg.meta || {}),
          ...(event.replyMode ? { replyMode: event.replyMode } : {}),
          ...(event.securityOutcome ? { securityOutcome: event.securityOutcome } : {}),
          ...(event.awmWorkflowId ? {
            awmWorkflowId: event.awmWorkflowId,
            awmWorkflowTitle: event.awmWorkflowTitle
          } : {})
        }
      }
    },
    onContent: (text) => {
      if (messages.value[assistantIdx]) {
        messages.value[assistantIdx].content += text
        if (!showScrollDown.value) scrollToBottom()
      }
    }
  })
}

function finalizeAssistantMessage (assistantIdx) {
  const msg = messages.value[assistantIdx]
  if (!msg) return
  if (msg.content) {
    msg.content = msg.content
      .replace(/^>\s*\*\*traceId:\*\*\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^###\s*追踪信息\s*\n\s*- 追踪 ID:\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^\*\*追踪 ID：\*\*\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^追踪\s*ID[：:]\s*`?[a-f0-9-]{8,}`?\s*\n?/gim, '')
  }
  if (!msg.ragHits?.length && msg.meta?.ragHits?.length) msg.ragHits = msg.meta.ragHits
  if (msg.meta?.writeConfirmed) msg.writeConfirmed = true
  if (!msg.writeConfirmed && hasActionableRemediationPlan(msg.content)) {
    msg.remediationPlan = parseRemediationPlan(msg.content)
  }
  if (!msg.toolsUsed?.length) {
    const extractedTools = extractToolsUsedFromText(msg.content)
    if (extractedTools.length) {
      msg.toolsUsed = extractedTools
    }
  }
}

async function confirmRemediationDialog () {
  await ElMessageBox.confirm(
    '将按上一轮处置计划在路径白名单与策略允许范围内执行写操作（清理、重启等）。此操作可能改变系统状态，是否继续？',
    '确认执行处置',
    {
      type: 'warning',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      closeOnClickModal: false
    }
  )
}

function openKnowledge () {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'knowledge' } }))
}

function openSkills () {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'agent-skills' } }))
}

const confirmPlanFromMessage = async (index) => {
  confirmPlanIndex.value = index
  try {
    await confirmRemediationDialog()
    skipRemediationConfirm.value = true
    inputMessage.value = '确认执行'
    await handleSend()
  } catch {
    // cancelled
  } finally {
    confirmPlanIndex.value = -1
    skipRemediationConfirm.value = false
  }
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
  const token = generationToken
  loading.value = true
  streaming.value = true
  streamAbortController = new AbortController()

  try {
    const response = await assistantChatStream(message, history, {
      useToolAgent: !userDeclinesTools(message),
      confirmRemediation: userConfirmedRemediation(message),
      signal: streamAbortController.signal
    })
    if (isGenerationCancelled(token)) return
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        const errText = await response.text().catch(() => '')
        let hint = '未登录或会话已失效，请退出后重新登录。'
        try {
          const j = JSON.parse(errText)
          if (j.securityCode === 'CSRF_ORIGIN_DENIED') {
            hint = '请求被 CSRF 防护拦截（常见于通过局域网 IP 访问）。请刷新页面或重新构建前端；管理员可在 application.yml 的 app.cors.allowed-origin-patterns 加入当前访问地址。'
          } else if (j.message) {
            hint = j.message
          }
        } catch {
          /* ignore */
        }
        messages.value[assistantIdx].content = `助手暂时不可用: ${hint}`
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
    streamReader = reader
    const decoder = new TextDecoder('utf-8')
    let sseBuf = ''
    while (true) {
      if (isGenerationCancelled(token)) break
      const { done, value } = await reader.read()
      if (done) break
      if (isGenerationCancelled(token)) break
      const chunk = decoder.decode(value, { stream: true })
      sseBuf = appendSsePayloadFromChunk(sseBuf, chunk, (payload) => {
        applySseToAssistant(assistantIdx, payload)
      })
    }
    if (!isGenerationCancelled(token)) {
      flushSseBuffer(sseBuf, (payload) => {
        applySseToAssistant(assistantIdx, payload)
      })
      if (!messages.value[assistantIdx].content.trim()) {
        messages.value[assistantIdx].content = '（未返回内容，请检查模型服务）'
      }
      finalizeAssistantMessage(assistantIdx)
    }
  } catch (e) {
    if (isGenerationCancelled(token) || e?.name === 'AbortError') return
    console.error('自然语言对话失败', e)
    messages.value[assistantIdx].content =
      '连接助手失败: ' + (e.message || String(e)) + '\n请确认后端服务已启动且模型配置正确。'
  } finally {
    if (!isGenerationCancelled(token)) {
      streaming.value = false
      loading.value = false
    }
    streamAbortController = null
    streamReader = null
  }
  scrollToBottom()
}

const handleSend = async () => {
  const rawInput = inputMessage.value.trim()
  let message = rawInput
  if (pendingAttachment.value) {
    message = `${rawInput}\n\n> 附件: ${pendingAttachment.value.name}\n${pendingAttachment.value.text}`.trim()
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

  if (userConfirmedRemediation(message) && !skipRemediationConfirm.value) {
    try {
      await confirmRemediationDialog()
    } catch {
      messages.value.push({
        role: 'assistant',
        content: '已取消执行。',
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      })
      scrollToBottom()
      return
    }
  }

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

  try {
    await runNaturalLanguageStream(message)
  } catch (error) {
    console.error('助手流式对话失败:', error)
    messages.value.push({
      role: 'assistant',
      content: `连接助手失败：${error.message || String(error)}\n\n请检查后端服务、AI 配置和当前登录状态。`,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })
    scrollToBottom()
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

onUnmounted(() => {
  stopGeneration()
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
  clearSession()
}

defineExpose({ applyPrefill, clearChat })
</script>

<style scoped>
.ops-chat-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  box-shadow: var(--ops-shadow-sm);
  overflow: hidden;
}

.ops-chat-container--agent {
  position: relative;
  flex: 1;
  min-height: calc(100vh - var(--ops-header-h, 52px));
  width: 100%;
  background: var(--agent-surface);
  border: none;
  border-radius: 0;
  box-shadow: none;
  overflow: hidden;
}

.composer-sticky {
  flex-shrink: 0;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0 20px;
  background: #fff;
}

.messages-inner {
  display: flex;
  flex-direction: column;
}

.composer-sticky {
  flex-shrink: 0;
  padding-bottom: 8px;
  background: linear-gradient(180deg, transparent, var(--agent-surface) 24%);
}

.scroll-down-btn {
  position: absolute;
  left: 50%;
  bottom: 140px;
  transform: translateX(-50%);
  z-index: 5;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--agent-border);
  background: var(--agent-surface);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--agent-muted);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.scroll-down-btn:hover {
  transform: translateX(-50%) translateY(-2px);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.16);
  color: var(--ops-primary);
}

.tool-calling {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  padding: 12px 14px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.tool-calling-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #374151;
  font-weight: 500;
  font-size: 14px;
}

.tool-calling-header::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #14b8a6;
  animation: prosePulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}

.tool-command {
  margin-top: 0;
  background: #1a1b1e;
  border-radius: 10px;
  padding: 10px 12px;
  border: 1px solid #2d2d2d;
}

.tool-command code {
  color: #d4d4d4;
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.patrol-pending-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 8px auto;
  padding: 10px 14px;
  border-radius: 10px;
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
  max-width: 100%;
}

.agent-stream-status {
  padding: 4px 0 8px;
}

.agent-stream-status__inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(240, 253, 250, 0.95);
  border: 1px solid rgba(13, 148, 136, 0.2);
  color: #0f766e;
  font-size: 13px;
  font-weight: 500;
}

</style>

