<template>
  <div>
    <!-- 未登录状态显示登录组件 -->
    <Login v-if="!isLoggedIn" @login-success="handleLoginSuccess" />
    
    <!-- 登录状态显示主应用 -->
    <div v-else class="app-container">
        <!-- 左侧导航栏 -->
        <div class="sidebar" :class="{ collapsed: isSidebarCollapsed }">
          <!-- 顶部品牌标识 -->
          <div class="brand-section">
            <div class="brand-logo-wrap" aria-hidden="true">
              <img
                src="/threshcore-logo.png"
                alt=""
                class="brand-logo"
              />
            </div>
            <div class="logo-text" v-if="!isSidebarCollapsed">
              <div class="main-title">ThreshCore</div>
              <div class="sub-title">日志 · 工具 · 审计 · 告警</div>
            </div>
          </div>
          
          <!-- 功能菜单 -->
          <div class="nav-menu">
            <AppSideNavMenu
              :groups="visibleNavGroups"
              :active-tab="activeTab"
              :collapse="isSidebarCollapsed"
              :structure-key="navMenuStructureKey"
              @select="handleSelect"
            />
          </div>
        </div>

        <!-- 右侧主要内容区域 -->
        <div class="main-container" :class="{ 'sidebar-collapsed': isSidebarCollapsed }">
          <!-- 顶部状态栏 -->
          <div class="top-status-bar">
            <div class="top-status-left">
              <div class="menu-toggle" @click="toggleSidebar">
                <el-button text circle size="small" :icon="isSidebarCollapsed ? Menu : Close" />
              </div>
              <div class="page-head">
                <h1 class="page-title">{{ breadcrumbText }}</h1>
                <span class="page-sub">ThreshCore 智能运维控制台</span>
              </div>
            </div>

            <TopPerformanceBar />

            <div class="top-status-right">
            <div class="ops-toolbar">
              <el-button text size="small" @click="showAlarmConfig">告警策略</el-button>
              <el-button text size="small" @click="showPathPolicy">路径白名单</el-button>
              <el-tooltip
                placement="bottom"
                content="快捷键：1 对话 · 2 MCP · 3 审计 · 4 链路 · 5 日志 · 6 历史 · 7 仪表盘 · 8 告警 · 9 效果评分"
              >
                <el-button text circle size="small" :icon="QuestionFilled" aria-label="快捷键说明" />
              </el-tooltip>
            </div>

            <div class="user-profile">
              <el-dropdown>
                <span class="user-info">
                  <el-avatar size="small" :src="userAvatar"></el-avatar>
                  <span style="margin-left: 8px;">{{ username }}</span>
                  <el-icon class="el-icon--right"><ArrowDown /></el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            </div>
          </div>

          <!-- 主内容：不用 Suspense 包裹（轮询触发 App 更新时 patchSuspense 易与 async 子组件竞态）；lazy 页使用 suspensible:false -->
          <div class="main-content">
            <component
              :is="currentComponent"
              :key="activeTab"
              ref="componentRef"
              :initial-task-id="targetTaskId"
              @view-task="handleViewTask"
            />
          </div>
        </div>
      </div>
    <AgentPathPolicyDialog ref="pathPolicyDialogRef" />
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted, watch, defineAsyncComponent } from 'vue'

import Login from './components/Login.vue'
import AppSideNavMenu from './components/AppSideNavMenu.vue'
import TopPerformanceBar from './components/TopPerformanceBar.vue'
import AgentPathPolicyDialog from './components/AgentPathPolicyDialog.vue'
import { DataLine, Clock, ChatLineSquare, Connection, Menu, Close, PieChart, WarningFilled, ArrowDown, List, Document, QuestionFilled, TrendCharts } from '@element-plus/icons-vue'
import { logout } from './api'

/** Tab 懒加载：不参与父级 Suspense，减少定时刷新整树 patch 时的 emitsOptions / vnode null */
const defineTabAsyncComponent = (loader) =>
  defineAsyncComponent({
    loader,
    suspensible: false
  })

// 登录状态管理
const isLoggedIn = ref(false)
const activeTab = ref('ops-chat')
const componentRef = ref(null)
const targetTaskId = ref('')
const userAvatar = ref('')
const username = ref('本机管理员')
const isSidebarCollapsed = ref(false)
const isMobile = ref(window.innerWidth < 768)
const LogAnalysis = defineTabAsyncComponent(() => import('./components/LogAnalysis.vue'))
const HistoryList = defineTabAsyncComponent(() => import('./components/HistoryList.vue'))
const LogAnalysisDashboard = defineTabAsyncComponent(() => import('./components/LogAnalysisDashboard.vue'))
const AlarmHistoryAnalysis = defineTabAsyncComponent(() => import('./components/AlarmHistoryAnalysis.vue'))
const McpToolsDemo = defineTabAsyncComponent(() => import('./components/McpToolsDemo.vue'))
const OpsChatInterface = defineTabAsyncComponent(() => import('./components/OpsChatInterface.vue'))
const AiAuditCenter = defineTabAsyncComponent(() => import('./components/AiAuditCenter.vue'))
const OpsTraceCenter = defineTabAsyncComponent(() => import('./components/OpsTraceCenter.vue'))
const OpsEffectDashboard = defineTabAsyncComponent(() => import('./components/OpsEffectDashboard.vue'))
const pathPolicyDialogRef = ref(null)

const tabConfig = {
  'ops-chat': { label: '工作台对话', component: OpsChatInterface, icon: ChatLineSquare },
  'mcp-tools': { label: '工具控制台', component: McpToolsDemo, icon: Connection },
  audit: { label: '执行审计', component: AiAuditCenter, icon: List },
  'ops-audit-trace': { label: '运维链路审计', component: OpsTraceCenter, icon: Document },
  'ops-effect': { label: '运维效果评分', component: OpsEffectDashboard, icon: TrendCharts },
  analysis: { label: '日志分析', component: LogAnalysis, icon: DataLine },
  history: { label: '任务记录', component: HistoryList, icon: Clock },
  dashboard: { label: '日志分析仪表盘', component: LogAnalysisDashboard, icon: PieChart },
  'alarm-history': { label: '告警历史分析', component: AlarmHistoryAnalysis, icon: WarningFilled }
}

const defaultTab = 'ops-chat'

const NAV_GROUP_ORDER = [
  { title: '工作台 · 工具 · 审计', keys: ['ops-chat', 'mcp-tools', 'audit', 'ops-audit-trace', 'ops-effect'] },
  { title: '日志与分析', keys: ['analysis', 'history', 'dashboard', 'alarm-history'] }
]

const visibleNavGroups = computed(() =>
  NAV_GROUP_ORDER.map((group) => ({
    title: group.title,
    items: group.keys
      .map((key) => {
        const cfg = tabConfig[key]
        if (!cfg) return null
        return { key, label: cfg.label, icon: cfg.icon }
      })
      .filter(Boolean)
  })).filter((g) => g.items.length > 0)
)

/** 菜单项集合变化时强制 remount ElMenu，避免 EP 内部 items/activeIndex 与动态插槽竞态导致 patch 时 component 为 null（reading 'exposed'） */
const navMenuStructureKey = computed(() =>
  visibleNavGroups.value.map((g) => g.items.map((i) => i.key).join(',')).join('||')
)

const shortcutTabMap = {
  '1': 'ops-chat',
  '2': 'mcp-tools',
  '3': 'audit',
  '4': 'ops-audit-trace',
  '5': 'analysis',
  '6': 'history',
  '7': 'dashboard',
  '8': 'alarm-history',
  '9': 'ops-effect'
}

// 切换侧边栏
const toggleSidebar = () => {
  isSidebarCollapsed.value = !isSidebarCollapsed.value
}

// 监听窗口大小变化
const handleResize = () => {
  isMobile.value = window.innerWidth < 768
  // 在移动设备上默认折叠侧边栏
  if (isMobile.value) {
    isSidebarCollapsed.value = true
  }
}

// 检查登录状态
const checkLoginStatus = () => {
  const user = localStorage.getItem('user')
  if (user) {
    isLoggedIn.value = true
    const userData = JSON.parse(user)
    username.value = userData.username || '本机管理员'
    if (!tabConfig[activeTab.value]) {
      activeTab.value = defaultTab
    }
  }
}

// 登录成功处理
const handleLoginSuccess = () => {
  checkLoginStatus()
  activeTab.value = defaultTab
}

// 退出登录处理
const handleLogout = async () => {
  try {
    await logout()
  } catch (_) {
    // ignore logout api error and clear local state
  }
  localStorage.removeItem('user')
  isLoggedIn.value = false
}

const callComponentMethod = (methodName, args = [], attempts = 12) => {
  nextTick(() => {
    const targetMethod = componentRef.value?.[methodName]
    if (typeof targetMethod === 'function') {
      targetMethod(...args)
      return
    }

    if (attempts <= 0) return
    setTimeout(() => callComponentMethod(methodName, args, attempts - 1), 100)
  })
}

const handleOpsNavigateAgent = (ev) => {
  const msg = ev.detail?.message
  if (typeof msg !== 'string' || !msg.trim()) return
  activeTab.value = 'ops-chat'
  callComponentMethod('applyPrefill', [msg.trim()])
}

// 组件挂载时检查登录状态
onMounted(() => {
  checkLoginStatus()

  // 添加窗口大小变化监听
  window.addEventListener('resize', handleResize)
  window.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('ops-navigate-agent', handleOpsNavigateAgent)
  // 初始化时检查一次
  handleResize()
})

onUnmounted(() => {
  // 移除窗口大小变化监听
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('ops-navigate-agent', handleOpsNavigateAgent)
})

watch(isMobile, (newValue) => {
  isSidebarCollapsed.value = newValue
})

watch(
  visibleNavGroups,
  (groups) => {
    const keys = new Set(groups.flatMap((g) => g.items.map((i) => i.key)))
    if (!keys.has(activeTab.value)) {
      activeTab.value = defaultTab
    }
  },
  { flush: 'sync' }
)

// 根据当前激活的标签计算面包屑文本
const breadcrumbText = computed(() => {
    return tabConfig[activeTab.value]?.label || tabConfig[defaultTab].label
})

const currentComponent = computed(() => {
    return tabConfig[activeTab.value]?.component || tabConfig[defaultTab].component
})

const handleSelect = (key) => {
    activeTab.value = key
}

const handleGlobalKeydown = (event) => {
    const target = event.target
    const tagName = target?.tagName?.toLowerCase?.() || ''
    const isTyping = target?.isContentEditable || tagName === 'input' || tagName === 'textarea'
    if (isTyping || event.ctrlKey || event.altKey || event.metaKey) return

    const pressedKey = event.key?.toLowerCase?.() || ''
    const targetTab = shortcutTabMap[pressedKey]
    if (targetTab) {
      event.preventDefault()
      activeTab.value = targetTab
    }
}

// 导航到首页（默认工作台）
const navigateToHome = () => {
    activeTab.value = defaultTab
}

// 导航到当前页面（刷新当前页面内容）
const navigateToCurrentPage = () => {
    // 可以在这里添加刷新当前页面内容的逻辑
    // 当前版本仅保留入口，避免刷新导致状态丢失
}

const handleViewTask = (taskId) => {
    targetTaskId.value = taskId
    activeTab.value = 'analysis'
    callComponentMethod('loadTask', [taskId])
}

// 显示告警配置弹窗
const showAlarmConfig = () => {
    activeTab.value = 'analysis'
    callComponentMethod('showAlarmConfig')
}

const showPathPolicy = () => {
    pathPolicyDialogRef.value?.open()
}
</script>

<style scoped>
/* 全局布局 */
.app-container {
  min-height: 100vh;
  background: var(--shell-bg);
  font-family: var(--ops-font);
  display: flex;
  overflow: hidden;
}

/* 左侧导航栏 */
.sidebar {
  width: 248px;
  background: var(--shell-sidebar);
  color: var(--shell-text);
  display: flex;
  flex-direction: column;
  position: fixed;
  left: 0;
  top: 0;
  height: 100vh;
  z-index: 1000;
  border-right: 1px solid var(--shell-border);
  box-shadow: none;
  transition: width 0.28s ease;
}

/* 侧边栏折叠状态 */
.sidebar.collapsed {
  width: 80px;
}

.sidebar.collapsed .brand-section {
  padding: 12px 8px;
  justify-content: center;
}

.sidebar.collapsed .brand-logo-wrap {
  width: 44px;
  height: 44px;
}

.sidebar.collapsed .brand-logo {
  width: 44px;
}

.sidebar.collapsed :deep(.el-menu-item) {
  justify-content: center;
}

.sidebar.collapsed :deep(.el-menu-item .el-icon) {
  margin-right: 0;
  font-size: 20px;
}

.sidebar.collapsed :deep(.el-menu-item:hover),
.sidebar.collapsed :deep(.el-menu-item.is-active) {
  transform: none;
}

/* 顶部品牌标识：Logo 左、文案右，同一行 */
.brand-section {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  padding: 16px 14px 14px;
  border-bottom: 1px solid var(--shell-border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04) 0%, transparent 100%);
  transition: all 0.3s ease;
}

.brand-logo-wrap {
  flex-shrink: 0;
  width: 60px;
  height: 60px;
  overflow: hidden;
  border-radius: 8px;
  filter: drop-shadow(0 0 10px var(--shell-glow));
}

.brand-logo {
  display: block;
  width: 60px;
  height: auto;
  margin-top: -2px;
  object-fit: contain;
  object-position: top center;
  transition: opacity 0.2s ease;
}

.brand-section:hover .brand-logo {
  opacity: 0.92;
}

.logo-text {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  flex: 1;
  transition: all 0.3s ease;
}

.main-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--shell-text);
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.sub-title {
  font-size: 11px;
  color: var(--shell-muted);
  margin-top: 4px;
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: 0.02em;
}

/* 导航菜单 */
.nav-menu {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0;
}

/* 右侧主要内容区域 */
.main-container {
  flex: 1;
  margin-left: 248px;
  display: flex;
  flex-direction: column;
  width: calc(100vw - 248px);
  transition: margin-left 0.28s ease, width 0.28s ease;
}

/* 侧边栏折叠时的主内容区域 */
.main-container.sidebar-collapsed {
  margin-left: 80px;
  width: calc(100vw - 80px);
}

/* 顶栏：浅色产品级 Chrome */
.top-status-bar {
  height: var(--ops-header-h);
  background: var(--ops-panel);
  color: var(--ops-text);
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 0;
  border-bottom: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
  position: sticky;
  top: 0;
  z-index: 999;
}

.top-status-left {
  display: flex;
  align-items: center;
  flex: 0 1 auto;
  min-width: 0;
}

.top-status-right {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  margin-left: auto;
}

/* 汉堡菜单按钮 */
.menu-toggle {
  margin-right: 20px;
  display: none;
}

.page-head {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ops-text);
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.page-sub {
  font-size: 11px;
  color: var(--ops-text-subtle);
  font-weight: 500;
}

.ops-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 12px;
  transition: all 0.3s ease;
}

.user-profile {
  display: flex;
  align-items: center;
  transition: all 0.3s ease;
}

.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 10px 4px 4px;
  border-radius: 8px;
  transition: background 0.15s ease;
  background: var(--ops-panel-soft);
  border: 1px solid var(--ops-border);
  color: var(--ops-text);
  font-size: 13px;
  font-weight: 500;
}

.user-info:hover {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

/* 主内容区域 */
.main-content {
  flex: 1;
  padding: 16px 20px 20px;
  overflow-y: auto;
  background: var(--ops-bg);
  transition: padding 0.2s ease;
}

/* 功能页卡片（与 ops-console-theme 变量对齐） */
.main-content :deep(.el-card) {
  border-radius: var(--ops-radius);
  border: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
  backdrop-filter: blur(10px);
}

.main-content :deep(.el-card__header) {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), var(--ops-panel-soft));
  border-bottom: 1px solid var(--ops-border-soft);
  padding: 14px 18px;
}

.main-content :deep(.el-card__body) {
  padding: 16px 18px;
}

.main-content :deep(.el-table) {
  --el-table-header-bg-color: rgba(245, 247, 251, 0.98);
  --el-table-tr-bg-color: #ffffff;
  --el-table-row-hover-bg-color: rgba(8, 145, 178, 0.05);
  --el-table-border-color: var(--ops-border-soft);
  font-size: 13px;
}

.main-content :deep(.el-form-item__label) {
  color: var(--ops-text);
  font-weight: 600;
}

.main-content :deep(.el-input__wrapper),
.main-content :deep(.el-textarea__inner),
.main-content :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.1) inset;
  border-radius: var(--ops-radius-sm);
}

.main-content :deep(.el-dialog) {
  border-radius: var(--ops-radius);
}

.main-content :deep(.el-dialog__header) {
  margin-right: 0;
  padding: 16px 20px 12px;
  border-bottom: 1px solid var(--ops-border-soft);
}

/* 菜单覆盖样式 */
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
  transition: background 0.22s ease, color 0.22s ease, transform 0.22s ease, box-shadow 0.22s ease;
  color: rgba(226, 232, 240, 0.88);
}

:deep(.el-menu-item:hover) {
  background: var(--shell-nav-hover) !important;
  color: #f0fdfa !important;
  transform: none;
}

:deep(.el-menu-item.is-active) {
  background: var(--shell-nav-active) !important;
  color: #f0fdfa !important;
  box-shadow: inset 3px 0 0 var(--shell-accent);
  transform: none;
}

:deep(.el-menu-item.is-active::before) {
  display: none;
}

:deep(.el-menu-item .el-icon) {
  font-size: 18px;
  margin-right: 12px;
  transition: color 0.3s ease;
}

:deep(.el-menu-item:hover .el-icon) {
  color: var(--shell-accent);
}

:deep(.el-menu-item.is-active .el-icon) {
  color: var(--shell-accent);
}

/* 面包屑样式 */
:deep(.el-breadcrumb) {
  font-size: 13px;
}

:deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--shell-accent);
  font-weight: 600;
}

:deep(.el-breadcrumb__inner) {
  color: rgba(203, 213, 225, 0.75);
  transition: color 0.22s ease;
}

:deep(.el-breadcrumb__inner:hover) {
  color: var(--shell-accent);
}

/* 系统状态标签样式 */
:deep(.el-tag) {
  border-radius: 6px;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.3s ease;
}

:deep(.el-tag:hover) {
  transform: none;
  box-shadow: none;
}

/* 按钮样式 */
:deep(.el-button) {
  border-radius: 6px;
  font-weight: 500;
  transition: all 0.3s ease;
}

:deep(.el-button:hover) {
  transform: none;
  box-shadow: none;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .sidebar {
    width: 240px;
  }

  .main-container {
    margin-left: 240px;
    width: calc(100vw - 240px);
  }
  
  .main-container.sidebar-collapsed {
    margin-left: 80px;
    width: calc(100vw - 80px);
  }
  
  .brand-section {
    padding: 14px 18px;
  }
  
  .main-content {
    padding: 12px;
  }
}

@media (max-width: 768px) {
  .menu-toggle {
    display: block;
  }
  
  .sidebar {
    width: 250px;
    transform: translateX(-100%);
  }
  
  .sidebar:not(.collapsed) {
    transform: translateX(0);
  }
  
  .main-container {
    margin-left: 0;
    width: 100vw;
  }
  
  .main-container.sidebar-collapsed {
    margin-left: 0;
    width: 100vw;
  }
  
  .top-status-bar {
    padding: 0 16px;
    height: 64px;
  }
  
  .breadcrumb {
    display: none;
  }

  .ops-toolbar {
    display: none;
  }
  
  .main-content {
    padding: 14px;
  }
  
  .user-info span:not(.el-avatar) {
    display: none;
  }
  
  .user-info {
    padding: 8px;
  }
}

@media (max-width: 480px) {
  .main-content {
    padding: 10px;
  }
  
  .top-status-bar {
    padding: 0 12px;
  }
}

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
}

::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 4px;
  transition: background 0.3s ease;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.5);
}

/* 主内容区滚动条 */
.main-content::-webkit-scrollbar-track {
  background: rgba(0, 0, 0, 0.05);
}

.main-content::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.2);
}

.main-content::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.3);
}
</style>