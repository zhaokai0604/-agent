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
            <div class="logo-icon">
              <svg viewBox="0 0 24 24" width="32" height="32"><path fill="currentColor" d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>
            </div>
            <div class="logo-text" v-if="!isSidebarCollapsed">
              <div class="main-title">运维工作台</div>
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
            <!-- 汉堡菜单按钮 -->
            <div class="menu-toggle" @click="toggleSidebar">
              <el-button type="primary" circle size="small" :icon="isSidebarCollapsed ? Menu : Close" />
            </div>
            
            <div class="breadcrumb">
              <el-breadcrumb separator="/">
                <el-breadcrumb-item @click="navigateToHome">首页</el-breadcrumb-item>
                <el-breadcrumb-item @click="navigateToCurrentPage">{{ breadcrumbText }}</el-breadcrumb-item>
              </el-breadcrumb>
            </div>

            <div class="ops-toolbar">
              <el-button type="info" size="small" @click="showAlarmConfig">告警配置</el-button>
              <el-tag size="small">快捷键: 1对话 2MCP 3审计 4链路 5日志 6历史 7仪表盘 8告警</el-tag>
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
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted, watch, defineAsyncComponent } from 'vue'

import Login from './components/Login.vue'
import AppSideNavMenu from './components/AppSideNavMenu.vue'
import { DataLine, Clock, ChatLineSquare, Connection, Menu, Close, PieChart, WarningFilled, ArrowDown, List, Document } from '@element-plus/icons-vue'
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

const tabConfig = {
  'ops-chat': { label: '工作台对话', component: OpsChatInterface, icon: ChatLineSquare },
  'mcp-tools': { label: '工具控制台', component: McpToolsDemo, icon: Connection },
  audit: { label: '执行审计', component: AiAuditCenter, icon: List },
  'ops-audit-trace': { label: '运维链路审计', component: OpsTraceCenter, icon: Document },
  analysis: { label: '日志分析', component: LogAnalysis, icon: DataLine },
  history: { label: '任务记录', component: HistoryList, icon: Clock },
  dashboard: { label: '日志分析仪表盘', component: LogAnalysisDashboard, icon: PieChart },
  'alarm-history': { label: '告警历史分析', component: AlarmHistoryAnalysis, icon: WarningFilled }
}

const defaultTab = 'ops-chat'

const NAV_GROUP_ORDER = [
  { title: '工作台 · 工具 · 审计', keys: ['ops-chat', 'mcp-tools', 'audit', 'ops-audit-trace'] },
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
  '8': 'alarm-history'
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
</script>

<style scoped>
/* 全局布局 */
.app-container { 
  min-height: 100vh; 
  background: #0f172a; 
  font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, 'Roboto', sans-serif; 
  display: flex; 
  overflow: hidden;
}

/* 左侧导航栏 */
.sidebar {
  width: 250px;
  background: #0b1220;
  color: #fff;
  display: flex;
  flex-direction: column;
  position: fixed;
  left: 0;
  top: 0;
  height: 100vh;
  z-index: 1000;
  border-right: 1px solid rgba(148, 163, 184, 0.2);
  transition: all 0.3s ease;
}

.sidebar:hover {
  border-right-color: rgba(56, 189, 248, 0.4);
}

/* 侧边栏折叠状态 */
.sidebar.collapsed {
  width: 80px;
}

.sidebar.collapsed .brand-section {
  padding: 16px;
  justify-content: center;
}

.sidebar.collapsed .logo-icon {
  font-size: 24px;
}

.sidebar.collapsed :deep(.el-menu-item) {
  justify-content: center;
}

.sidebar.collapsed :deep(.el-menu-item .el-icon) {
  margin-right: 0;
  font-size: 20px;
}

.sidebar.collapsed :deep(.el-menu-item:hover) {
  transform: translateX(4px);
}

.sidebar.collapsed :deep(.el-menu-item.is-active) {
  transform: translateX(4px);
}

/* 顶部品牌标识 */
.brand-section {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(15, 23, 42, 0.95);
  transition: all 0.3s ease;
}

.logo-icon {
  color: #38bdf8;
  display: flex;
  font-size: 28px;
  transition: transform 0.3s ease;
}

.logo-icon:hover {
  transform: scale(1.1) rotate(5deg);
}

.logo-text {
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
}

.main-title {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  line-height: 1.2;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}

.sub-title {
  font-size: 12px;
  color: rgba(148, 163, 184, 0.95);
  margin-top: 4px;
  font-weight: 400;
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
  margin-left: 250px;
  display: flex;
  flex-direction: column;
  width: calc(100vw - 250px);
  transition: all 0.3s ease;
}

/* 侧边栏折叠时的主内容区域 */
.main-container.sidebar-collapsed {
  margin-left: 80px;
  width: calc(100vw - 80px);
}

/* 顶部状态栏 */
.top-status-bar {
  height: 64px;
  background: #111827;
  color: #e5e7eb;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
  position: sticky;
  top: 0;
  z-index: 999;
  transition: all 0.3s ease;
}

/* 汉堡菜单按钮 */
.menu-toggle {
  margin-right: 20px;
  display: none;
}

.breadcrumb {
  flex: 1;
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
  padding: 8px 16px;
  border-radius: 25px;
  transition: all 0.3s ease;
  background: rgba(30, 41, 59, 0.85);
  color: #e5e7eb;
}

.user-info:hover {
  background: rgba(51, 65, 85, 1);
}

/* 主内容区域 */
.main-content {
  flex: 1;
  padding: 14px;
  overflow-y: auto;
  background: #f3f4f6;
  transition: all 0.3s ease;
}

/* 功能页统一运维主题（覆盖子组件） */
.main-content :deep(.el-card) {
  border-radius: 10px;
  border: 1px solid #dbe2ea;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}

.main-content :deep(.el-card__header) {
  background: #f8fafc;
  border-bottom: 1px solid #e5e7eb;
  padding: 12px 16px;
}

.main-content :deep(.el-card__body) {
  padding: 14px 16px;
}

.main-content :deep(.el-table) {
  --el-table-header-bg-color: #f8fafc;
  --el-table-tr-bg-color: #ffffff;
  --el-table-row-hover-bg-color: #f1f5f9;
  --el-table-border-color: #e5e7eb;
  font-size: 13px;
}

.main-content :deep(.el-form-item__label) {
  color: #334155;
  font-weight: 600;
}

.main-content :deep(.el-input__wrapper),
.main-content :deep(.el-textarea__inner),
.main-content :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px #d0d7e2 inset;
  border-radius: 8px;
}

.main-content :deep(.el-dialog) {
  border-radius: 10px;
}

.main-content :deep(.el-dialog__header) {
  margin-right: 0;
  padding: 14px 18px 10px;
  border-bottom: 1px solid #e5e7eb;
}

/* 菜单覆盖样式 */
:deep(.el-menu) {
  background: transparent !important;
  border-right: none !important;
}

:deep(.el-menu-item) {
  font-size: 14px;
  font-weight: 500;
  height: 44px;
  line-height: 44px;
  margin: 2px 10px;
  border-radius: 8px;
  transition: all 0.3s ease;
  color: rgba(226, 232, 240, 0.9);
}

:deep(.el-menu-item:hover) {
  background: rgba(30, 41, 59, 0.95) !important;
  color: #38bdf8 !important;
  transform: translateX(4px);
}

:deep(.el-menu-item.is-active) {
  background: #0369a1 !important;
  color: #fff !important;
  box-shadow: none;
  transform: translateX(4px);
}

:deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 24px;
  background: #ffffff;
  border-radius: 0 2px 2px 0;
}

:deep(.el-menu-item .el-icon) {
  font-size: 18px;
  margin-right: 12px;
  transition: color 0.3s ease;
}

:deep(.el-menu-item:hover .el-icon) {
  color: #38bdf8;
}

:deep(.el-menu-item.is-active .el-icon) {
  color: #fff;
}

/* 面包屑样式 */
:deep(.el-breadcrumb) {
  font-size: 14px;
}

:deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #38bdf8;
  font-weight: 600;
}

:deep(.el-breadcrumb__inner) {
  color: #cbd5e1;
  transition: color 0.3s ease;
}

:deep(.el-breadcrumb__inner:hover) {
  color: #38bdf8;
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
    width: 220px;
  }
  
  .main-container {
    margin-left: 220px;
    width: calc(100vw - 220px);
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