<template>
  <div>
    <Login v-if="!isLoggedIn" @login-success="handleLoginSuccess" />

    <div v-else class="app-shell">
      <aside class="shell-sidebar" :class="{ collapsed: isSidebarCollapsed }">
        <div class="brand-block">
          <div class="brand-logo-wrap" aria-hidden="true">
            <img src="/threshcore-logo.png" alt="" class="brand-logo">
          </div>
          <div v-if="!isSidebarCollapsed" class="brand-copy">
            <strong>ThreshCore</strong>
            <span>日志 · 工具 · 审计 · 治理</span>
          </div>
        </div>

        <div class="sidebar-menu">
          <AppSideNavMenu
            :groups="visibleNavGroups"
            :active-tab="activeTab"
            :collapse="isSidebarCollapsed"
            :structure-key="navMenuStructureKey"
            @select="handleSelect"
          />
        </div>
      </aside>

      <div class="shell-main" :class="{ collapsed: isSidebarCollapsed }">
        <header class="shell-topbar">
          <div class="topbar-left">
            <el-button text circle size="small" :icon="isSidebarCollapsed ? Menu : Close" @click="toggleSidebar" />
            <div class="topbar-title">
              <h1>{{ breadcrumbText }}</h1>
              <span>ThreshCore 智能运维控制台</span>
            </div>
          </div>

          <TopPerformanceBar />

          <div class="topbar-right">
            <div class="toolbar-actions">
              <el-tooltip placement="bottom" content="快捷键：1 对话 · 2 MCP · 3 审计 · 4 链路 · 5 日志 · 6 历史 · 7 仪表盘 · 8 效果 · 9 配置">
                <el-button text circle size="small" :icon="QuestionFilled" aria-label="快捷键说明" />
              </el-tooltip>
            </div>

            <el-dropdown @command="handleUserMenuCommand">
              <span class="user-info">
                <el-avatar size="small" :src="userAvatar" />
                <span class="user-text">
                  <span class="user-name">{{ username }}</span>
                  <el-tag size="small" effect="plain" class="role-pill">{{ roleBadgeText }}</el-tag>
                </span>
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>{{ username }} · {{ roleBadgeText }}</el-dropdown-item>
                  <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                  <el-dropdown-item command="system-config">系统配置</el-dropdown-item>
                  <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <main class="shell-content">
          <component
            :is="currentComponent"
            :key="activeTab"
            ref="componentRef"
            :initial-task-id="targetTaskId"
            @view-task="handleViewTask"
            @profile-updated="handleProfileUpdated"
          />
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  ArrowDown,
  ChatLineSquare,
  Clock,
  Close,
  Connection,
  DataLine,
  Document,
  List,
  Menu,
  PieChart,
  QuestionFilled,
  Setting,
  TrendCharts,
  User
} from '@element-plus/icons-vue'
import Login from './components/Login.vue'
import AppSideNavMenu from './components/AppSideNavMenu.vue'
import TopPerformanceBar from './components/TopPerformanceBar.vue'
import { logout } from './api'

const defineTabAsyncComponent = (loader) =>
  defineAsyncComponent({
    loader,
    suspensible: false
  })

const LogAnalysis = defineTabAsyncComponent(() => import('./components/LogAnalysis.vue'))
const HistoryList = defineTabAsyncComponent(() => import('./components/HistoryList.vue'))
const LogAnalysisDashboard = defineTabAsyncComponent(() => import('./components/LogAnalysisDashboard.vue'))
const McpToolsDemo = defineTabAsyncComponent(() => import('./components/McpToolsDemo.vue'))
const OpsChatInterface = defineTabAsyncComponent(() => import('./components/OpsChatInterface.vue'))
const AiAuditCenter = defineTabAsyncComponent(() => import('./components/AiAuditCenter.vue'))
const OpsTraceCenter = defineTabAsyncComponent(() => import('./components/OpsTraceCenter.vue'))
const OpsEffectDashboard = defineTabAsyncComponent(() => import('./components/OpsEffectDashboard.vue'))
const SystemConfigCenter = defineTabAsyncComponent(() => import('./components/SystemConfigCenter.vue'))
const UserProfileCenter = defineTabAsyncComponent(() => import('./components/UserProfileCenter.vue'))

const isLoggedIn = ref(false)
const activeTab = ref('ops-chat')
const componentRef = ref(null)
const targetTaskId = ref('')
const userAvatar = ref('')
const username = ref('本地管理员')
const userRole = ref(0)
const isSidebarCollapsed = ref(false)
const isMobile = ref(typeof window !== 'undefined' ? window.innerWidth < 768 : false)

const tabConfig = {
  'ops-chat': { label: '工作台对话', component: OpsChatInterface, icon: ChatLineSquare },
  'mcp-tools': { label: '工具控制台', component: McpToolsDemo, icon: Connection },
  audit: { label: '执行审计', component: AiAuditCenter, icon: List },
  'ops-audit-trace': { label: '运维链路审计', component: OpsTraceCenter, icon: Document },
  'ops-effect': { label: '运维效果评分', component: OpsEffectDashboard, icon: TrendCharts },
  analysis: { label: '日志分析', component: LogAnalysis, icon: DataLine },
  history: { label: '任务记录', component: HistoryList, icon: Clock },
  dashboard: { label: '日志分析仪表盘', component: LogAnalysisDashboard, icon: PieChart },
  'system-config': { label: '系统配置与效果', component: SystemConfigCenter, icon: Setting },
  'profile-center': { label: '个人中心', component: UserProfileCenter, icon: User }
}

const defaultTab = 'ops-chat'
const NAV_GROUP_ORDER = [
  { title: '工作台 · 工具 · 审计', keys: ['ops-chat', 'mcp-tools', 'audit', 'ops-audit-trace', 'ops-effect'] },
  { title: '日志与分析', keys: ['analysis', 'history', 'dashboard'] }
]

const visibleNavGroups = computed(() =>
  NAV_GROUP_ORDER.map(group => ({
    title: group.title,
    items: group.keys
      .map(key => {
        const cfg = tabConfig[key]
        if (!cfg) return null
        return { key, label: cfg.label, icon: cfg.icon }
      })
      .filter(Boolean)
  }))
)

const navMenuStructureKey = computed(() =>
  visibleNavGroups.value.map(group => group.items.map(item => item.key).join(',')).join('||')
)

const shortcutTabMap = {
  '1': 'ops-chat',
  '2': 'mcp-tools',
  '3': 'audit',
  '4': 'ops-audit-trace',
  '5': 'analysis',
  '6': 'history',
  '7': 'dashboard',
  '8': 'ops-effect',
  '9': 'system-config'
}

const roleBadgeText = computed(() => (userRole.value === 1 ? '管理员' : '普通用户'))
const breadcrumbText = computed(() => tabConfig[activeTab.value]?.label || tabConfig[defaultTab].label)
const currentComponent = computed(() => tabConfig[activeTab.value]?.component || tabConfig[defaultTab].component)

function toggleSidebar() {
  isSidebarCollapsed.value = !isSidebarCollapsed.value
}

function handleResize() {
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) {
    isSidebarCollapsed.value = true
  }
}

function syncUserState(userData = {}) {
  username.value = userData.username || '本地管理员'
  userRole.value = Number(userData.role ?? 0)
}

function checkLoginStatus() {
  const rawUser = localStorage.getItem('user')
  if (!rawUser) {
    isLoggedIn.value = false
    return
  }
  try {
    const userData = JSON.parse(rawUser)
    isLoggedIn.value = true
    syncUserState(userData)
    if (!tabConfig[activeTab.value]) {
      activeTab.value = defaultTab
    }
  } catch {
    localStorage.removeItem('user')
    isLoggedIn.value = false
  }
}

function handleLoginSuccess() {
  checkLoginStatus()
  activeTab.value = defaultTab
}

async function handleLogout() {
  try {
    await logout()
  } catch (_) {
    // ignore
  }
  localStorage.removeItem('user')
  isLoggedIn.value = false
}

function handleUserMenuCommand(command) {
  if (command === 'profile') {
    activeTab.value = 'profile-center'
    return
  }
  if (command === 'system-config') {
    activeTab.value = 'system-config'
    return
  }
  if (command === 'logout') {
    handleLogout()
  }
}

function callComponentMethod(methodName, args = [], attempts = 12) {
  nextTick(() => {
    const fn = componentRef.value?.[methodName]
    if (typeof fn === 'function') {
      fn(...args)
      return
    }
    if (attempts <= 0) return
    setTimeout(() => callComponentMethod(methodName, args, attempts - 1), 100)
  })
}

function handleOpsNavigateAgent(event) {
  const message = event.detail?.message
  if (typeof message !== 'string' || !message.trim()) return
  activeTab.value = 'ops-chat'
  callComponentMethod('applyPrefill', [message.trim()])
}

function handleSelect(key) {
  activeTab.value = key
}

function handleGlobalKeydown(event) {
  const target = event.target
  const tagName = target?.tagName?.toLowerCase?.() || ''
  const isTyping = target?.isContentEditable || tagName === 'input' || tagName === 'textarea'
  if (isTyping || event.ctrlKey || event.altKey || event.metaKey) return

  const targetTab = shortcutTabMap[event.key?.toLowerCase?.() || '']
  if (!targetTab) return
  event.preventDefault()
  activeTab.value = targetTab
}

function handleViewTask(taskId) {
  targetTaskId.value = taskId
  activeTab.value = 'analysis'
  callComponentMethod('loadTask', [taskId])
}

function handleOpsNavigateTab(event) {
  const tab = event?.detail?.tab
  if (tab && tabConfig[tab]) {
    activeTab.value = tab
  }
}

function handleProfileUpdated(user) {
  syncUserState(user || {})
}

onMounted(() => {
  checkLoginStatus()
  handleResize()
  window.addEventListener('resize', handleResize)
  window.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('ops-navigate-agent', handleOpsNavigateAgent)
  window.addEventListener('ops-navigate-tab', handleOpsNavigateTab)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('ops-navigate-agent', handleOpsNavigateAgent)
  window.removeEventListener('ops-navigate-tab', handleOpsNavigateTab)
})

watch(isMobile, value => {
  isSidebarCollapsed.value = value
})

watch(activeTab, (tab) => {
  if (tab === 'live-wall' && !isMobile.value) {
    isSidebarCollapsed.value = true
  }
})

watch(
  visibleNavGroups,
  groups => {
    const visibleKeys = new Set(groups.flatMap(group => group.items.map(item => item.key)))
    if (
      !visibleKeys.has(activeTab.value)
      && activeTab.value !== 'profile-center'
      && activeTab.value !== 'system-config'
    ) {
      activeTab.value = defaultTab
    }
  },
  { flush: 'sync' }
)
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  background: var(--shell-bg);
  font-family: var(--ops-font);
}

.shell-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  width: 252px;
  background: var(--shell-sidebar);
  border-right: 1px solid var(--shell-border);
  color: var(--shell-text);
  display: flex;
  flex-direction: column;
  z-index: 1000;
  transition: width 0.28s ease, transform 0.28s ease;
}

.shell-sidebar.collapsed {
  width: 82px;
}

.brand-block {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 14px;
  border-bottom: 1px solid var(--shell-border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent);
}

.brand-logo-wrap {
  width: 56px;
  height: 56px;
  border-radius: 10px;
  overflow: hidden;
  flex-shrink: 0;
  filter: drop-shadow(0 0 10px var(--shell-glow));
}

.brand-logo {
  width: 56px;
  display: block;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.brand-copy strong {
  font-size: 16px;
  color: var(--shell-text);
}

.brand-copy span {
  font-size: 11px;
  color: var(--shell-muted);
}

.sidebar-menu {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0;
}

.shell-main {
  flex: 1;
  margin-left: 252px;
  width: calc(100vw - 252px);
  display: flex;
  flex-direction: column;
  transition: margin-left 0.28s ease, width 0.28s ease;
}

.shell-main.collapsed {
  margin-left: 82px;
  width: calc(100vw - 82px);
}

.shell-topbar {
  position: sticky;
  top: 0;
  z-index: 999;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 20px;
  min-height: var(--ops-header-h);
  background: var(--ops-panel);
  border-bottom: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
}

.topbar-left,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.topbar-left {
  min-width: 0;
}

.topbar-right {
  margin-left: auto;
}

.topbar-title {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar-title h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ops-text);
}

.topbar-title span {
  font-size: 11px;
  color: var(--ops-text-subtle);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px 4px 4px;
  border-radius: 10px;
  cursor: pointer;
  background: var(--ops-panel-soft);
  border: 1px solid var(--ops-border);
  color: var(--ops-text);
}

.user-text {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
}

.role-pill {
  border-color: rgba(15, 118, 110, 0.18);
  color: #0f766e;
}

.shell-content {
  flex: 1;
  padding: 16px 20px 20px;
  overflow-y: auto;
  background: var(--ops-bg);
}

.shell-content :deep(.el-card) {
  border-radius: var(--ops-radius);
  border: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
}

.shell-content :deep(.el-card__header) {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), var(--ops-panel-soft));
  border-bottom: 1px solid var(--ops-border-soft);
}

.shell-content :deep(.el-table) {
  --el-table-header-bg-color: rgba(245, 247, 251, 0.98);
  --el-table-tr-bg-color: #ffffff;
  --el-table-row-hover-bg-color: rgba(8, 145, 178, 0.05);
  --el-table-border-color: var(--ops-border-soft);
}

:deep(.el-menu) {
  background: transparent !important;
  border-right: none !important;
}

:deep(.el-menu-item-group__title) {
  padding: 16px 20px 8px !important;
  font-size: 10px !important;
  font-weight: 700 !important;
  letter-spacing: 0.12em !important;
  text-transform: uppercase !important;
  color: rgba(148, 163, 184, 0.55) !important;
  pointer-events: none;
}

:deep(.el-menu-item) {
  font-size: 13px;
  font-weight: 500;
  height: 42px;
  line-height: 42px;
  margin: 3px 12px;
  border-radius: 10px;
  color: rgba(226, 232, 240, 0.88);
  transition: background 0.22s ease, color 0.22s ease;
}

:deep(.el-menu-item:hover) {
  background: var(--shell-nav-hover) !important;
  color: #f0fdfa !important;
}

:deep(.el-menu-item.is-active) {
  background: var(--shell-nav-active) !important;
  color: #f0fdfa !important;
  box-shadow: inset 3px 0 0 var(--shell-accent);
}

:deep(.el-menu-item .el-icon) {
  font-size: 18px;
  margin-right: 12px;
}

@media (max-width: 1200px) {
  .shell-sidebar {
    width: 240px;
  }

  .shell-main {
    margin-left: 240px;
    width: calc(100vw - 240px);
  }

  .shell-main.collapsed {
    margin-left: 82px;
    width: calc(100vw - 82px);
  }
}

@media (max-width: 768px) {
  .shell-sidebar {
    transform: translateX(-100%);
    width: 250px;
  }

  .shell-sidebar:not(.collapsed) {
    transform: translateX(0);
  }

  .shell-main,
  .shell-main.collapsed {
    margin-left: 0;
    width: 100vw;
  }

  .shell-topbar {
    padding: 0 14px;
  }

  .toolbar-actions {
    display: none;
  }

  .user-text {
    display: none;
  }

  .shell-content {
    padding: 14px;
  }
}
</style>
