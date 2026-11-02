/**
 * Agent 多会话状态：消息列表写入 localStorage（防抖），会话数/条数有上限。
 * 供 App 壳、侧栏与运维对话页共用。
 */
import { ref, watch } from 'vue'

const STORAGE_PREFIX = 'threshcore-agent-sessions-v3:'
let ownerId = null
let changingOwner = false
const storageKey = () => STORAGE_PREFIX + encodeURIComponent(ownerId)
const MAX_SESSIONS = 24
const MAX_MESSAGES = 80
const PERSIST_DEBOUNCE_MS = 800
const SESSION_SCHEMA_VERSION = 2

const sessions = ref([])
const activeSessionId = ref('')
const messages = ref([])
let storeLoaded = false
let persistTimer = null

function createSessionId() {
  const uuid = globalThis.crypto?.randomUUID?.()
  return `s-${uuid || `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`}`
}

function normalizeMessage(message, sessionId, index) {
  if (!message || typeof message !== 'object') return null
  const role = ['user', 'assistant', 'system'].includes(message.role) ? message.role : 'assistant'
  const content = message.content == null ? '' : String(message.content)
  return {
    ...message,
    id: String(message.id || `m-${sessionId}-${index}-${Date.now()}`),
    role,
    content,
    timestamp: message.timestamp || new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
    status: message.status || 'completed'
  }
}

function normalizeSession(raw) {
  const id = String(raw?.id || createSessionId())
  const sourceMessages = Array.isArray(raw?.messages) ? raw.messages : []
  const normalizedMessages = sourceMessages
    .map((message, index) => normalizeMessage(message, id, index))
    .filter(Boolean)
    .slice(-MAX_MESSAGES)
  return {
    ...raw,
    id,
    title: raw?.title || deriveTitle(normalizedMessages),
    kind: raw?.kind || 'OPS_AGENT',
    schemaVersion: SESSION_SCHEMA_VERSION,
    createdAt: raw?.createdAt || raw?.updatedAt || Date.now(),
    updatedAt: raw?.updatedAt || Date.now(),
    messages: normalizedMessages
  }
}

function deriveTitle(msgs) {
  const first = (msgs || []).find(m => m.role === 'user' && m.content)
  if (!first) return '新任务'
  const text = String(first.content).replace(/\s+/g, ' ').trim()
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

function trimSessionMessages(session) {
  if (!session || !Array.isArray(session.messages)) return
  if (session.messages.length > MAX_MESSAGES) {
    session.messages.splice(0, session.messages.length - MAX_MESSAGES)
  }
}

function persistNow() {
  if (ownerId == null || changingOwner) return
  try {
    localStorage.setItem(
      storageKey(),
      JSON.stringify({
        schemaVersion: SESSION_SCHEMA_VERSION,
        activeId: activeSessionId.value,
        sessions: sessions.value.slice(0, MAX_SESSIONS).map(s => {
          trimSessionMessages(s)
          return {
            ...s,
            messages: (s.messages || []).slice(-MAX_MESSAGES)
          }
        })
      })
    )
  } catch {
    /* quota */
  }
}

function schedulePersist() {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    persistNow()
  }, PERSIST_DEBOUNCE_MS)
}

function activeSession() {
  return sessions.value.find(s => s.id === activeSessionId.value) || null
}

function touchActiveSession() {
  if (changingOwner) return
  const session = activeSession()
  if (!session) return
  trimSessionMessages(session)
  session.updatedAt = Date.now()
  session.title = deriveTitle(session.messages)
  schedulePersist()
}

function syncMessagesFromActive() {
  const session = activeSession()
  if (!session) {
    messages.value = []
    return
  }
  if (!Array.isArray(session.messages)) {
    session.messages = []
  }
  trimSessionMessages(session)
  messages.value = session.messages
}

function loadFromStorage() {
  try {
    if (ownerId == null) return false
    const raw = localStorage.getItem(storageKey())
    if (!raw) return false
    const data = JSON.parse(raw)
    if (!Array.isArray(data?.sessions) || data.sessions.length === 0) return false
    sessions.value = data.sessions.map(normalizeSession)
    activeSessionId.value = data.activeId || sessions.value[0].id
    syncMessagesFromActive()
    return true
  } catch {
    return false
  }
}

function ensureDefaultSession() {
  if (sessions.value.length === 0) {
    const id = createSessionId()
    const session = {
      id,
      title: '新任务',
      kind: 'OPS_AGENT',
      schemaVersion: SESSION_SCHEMA_VERSION,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: []
    }
    sessions.value = [session]
    activeSessionId.value = id
    messages.value = session.messages
    persistNow()
    return
  }
  if (!activeSessionId.value || !sessions.value.some(s => s.id === activeSessionId.value)) {
    activeSessionId.value = sessions.value[0].id
    syncMessagesFromActive()
  }
}

let watchBound = false

function bindWatchers() {
  if (watchBound) return
  watchBound = true
  watch(messages, () => touchActiveSession(), { deep: true, flush: 'sync' })
}

/** 由登录/会话校验结果切换账户；无归属的旧缓存不自动导入任一账户。 */
export function setAgentSessionOwner(userId) {
  const next = userId == null || String(userId).trim() === '' ? null : String(userId)
  if (next === ownerId) return
  if (typeof window !== 'undefined') window.dispatchEvent(new Event('ops-agent-session-changing'))
  if (persistTimer) { clearTimeout(persistTimer); persistTimer = null }
  persistNow()
  changingOwner = true
  ownerId = next
  sessions.value = []
  activeSessionId.value = ''
  messages.value = []
  if (!loadFromStorage()) ensureDefaultSession()
  changingOwner = false
  ensureDefaultSession()
}

export function useAgentSession() {
  if (!storeLoaded) {
    storeLoaded = true
    if (!loadFromStorage()) {
      ensureDefaultSession()
    } else {
      ensureDefaultSession()
    }
    bindWatchers()
  }

  function loadSession() {
    if (!loadFromStorage()) ensureDefaultSession()
    else ensureDefaultSession()
  }

  function createSession() {
    const id = createSessionId()
    const session = {
      id,
      title: '新任务',
      kind: 'OPS_AGENT',
      schemaVersion: SESSION_SCHEMA_VERSION,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: []
    }
    sessions.value.unshift(session)
    if (sessions.value.length > MAX_SESSIONS) {
      sessions.value = sessions.value.slice(0, MAX_SESSIONS)
    }
    activeSessionId.value = id
    messages.value = session.messages
    persistNow()
    return id
  }

  function switchSession(id) {
    if (!sessions.value.some(s => s.id === id)) return
    touchActiveSession()
    activeSessionId.value = id
    syncMessagesFromActive()
    persistNow()
  }

  function deleteSession(id) {
    const idx = sessions.value.findIndex(s => s.id === id)
    if (idx < 0) return
    sessions.value.splice(idx, 1)
    if (activeSessionId.value === id) {
      if (sessions.value.length === 0) {
        createSession()
      } else {
        activeSessionId.value = sessions.value[0].id
        syncMessagesFromActive()
      }
    }
    persistNow()
  }

  function clearSession() {
    if (messages.value) {
      messages.value.splice(0, messages.value.length)
    }
    persistNow()
  }

  return {
    sessions,
    activeSessionId,
    messages,
    loadSession,
    clearSession,
    createSession,
    switchSession,
    deleteSession
  }
}
