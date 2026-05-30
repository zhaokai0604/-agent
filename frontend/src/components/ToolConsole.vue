<template>
  <div class="ops-page tool-console">
    <div class="page-header">
      <h1>工具箱</h1>
      <p class="subtitle">连接本机运维服务执行能力；写操作经确认后才会落地</p>
    </div>

    <el-alert
      v-if="!registryConnected"
      type="warning"
      :closable="false"
      show-icon
      class="conn-alert"
      title="未连接运维服务"
      description="请确认后端已启动并已登录。工具状态与执行记录均来自真实环境，不会在本地伪造。"
    />

    <el-tabs v-model="activeTab" type="border-card" class="tools-tabs">
      <el-tab-pane label="能力列表" name="overview">
        <div class="tools-grid">
          <el-card
            v-for="tool in tools"
            :key="tool.name"
            class="tool-card"
            :class="{ active: selectedTool === tool.name, offline: tool.status !== 'online' }"
            @click="selectTool(tool)"
          >
            <div class="tool-icon-badge">{{ mcpToolBadge(tool.name) }}</div>
            <div class="tool-name">{{ tool.displayName || mcpToolDisplayName(tool.name) }}</div>
            <div class="tool-desc">{{ tool.description }}</div>
            <div class="tool-status" :class="tool.status">
              <span class="status-dot"></span>
              {{ tool.statusText }}
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="立即执行" name="execute">
        <div class="execute-panel">
          <div class="selected-tool-info" v-if="selectedTool">
            <el-tag size="large" type="success">{{ selectedToolLabel }}</el-tag>
            <span class="tool-description">{{ getToolByName(selectedTool)?.description }}</span>
          </div>

          <div v-if="!selectedTool" class="no-selection">
            <el-empty description="请先在「能力列表」中选择一项" />
          </div>

          <div v-else class="tool-execute-area">
            <div class="tool-params" v-if="getToolByName(selectedTool)?.params?.length">
              <h4>参数</h4>
              <el-form :model="params" label-width="120px">
                <el-form-item
                  v-for="param in getToolByName(selectedTool)?.params"
                  :key="param.name"
                  :label="param.label"
                >
                  <el-input
                    v-model="params[param.name]"
                    :placeholder="param.placeholder"
                    :type="param.type || 'text'"
                  />
                  <span class="param-hint">{{ param.hint }}</span>
                </el-form-item>
              </el-form>
            </div>

            <div class="execute-actions">
              <el-button
                type="primary"
                size="large"
                @click="executeTool"
                :loading="executing"
                :icon="Cpu"
              >
                执行
              </el-button>
              <el-button size="large" @click="resetParams">重置</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="执行记录" name="history">
        <div class="history-toolbar">
          <span class="history-hint">来自溯源库的真实 MCP 执行记录</span>
          <el-button size="small" :loading="historyLoading" @click="loadExecutionHistory">刷新</el-button>
        </div>
        <el-table :data="executionHistory" stripe style="width: 100%" v-loading="historyLoading">
          <el-table-column prop="toolName" label="工具" width="150">
            <template #default="scope">
              {{ mcpToolDisplayName(scope.row.toolName) }}
            </template>
          </el-table-column>
          <el-table-column prop="timestamp" label="执行时间" width="180">
            <template #default="scope">
              {{ formatExecTime(scope.row.timestamp) }}
            </template>
          </el-table-column>
          <el-table-column prop="duration" label="耗时" width="100">
            <template #default="scope">
              <el-tag>{{ scope.row.duration }}ms</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="scope">
              <el-tag :type="scope.row.success ? 'success' : 'danger'">
                {{ scope.row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resultPreview" label="结果预览" show-overflow-tooltip />
          <el-table-column label="操作" width="180">
            <template #default="scope">
              <el-button type="primary" link @click="viewDetail(scope.row)">查看详情</el-button>
              <el-button v-if="scope.row.traceId" type="success" link @click="openTrace(scope.row)">查看溯源</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <ToolResultDialog ref="resultDialogRef" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu } from '@element-plus/icons-vue'
import { executeMcpTool, confirmMcpExecute, getMcpTools, getOpsTraceRecent } from '../api'
import { normalizeMcpToolResponse } from '../utils/mcpToolResult'
import {
  mergeToolRegistry,
  applyMcpWriteConfirmParams,
  mcpToolBadge,
  mcpToolDisplayName
} from '../utils/mcpToolsMeta'
import { buildMcpRiskConfirmMessage } from '../utils/mcpRiskConfirm'
import { formatResultPreview } from '../utils/structuredDataView'
import ToolResultDialog from './ToolResultDialog.vue'

const activeTab = ref('overview')
const selectedTool = ref(null)
const executing = ref(false)
const resultDialogRef = ref(null)
const executionHistory = ref([])
const registryConnected = ref(false)
const historyLoading = ref(false)

const params = reactive({})
const tools = ref([])

const selectedToolLabel = computed(() =>
  selectedTool.value ? mcpToolDisplayName(selectedTool.value) : ''
)

const loadTools = async () => {
  try {
    const raw = await getMcpTools()
    tools.value = mergeToolRegistry(raw, { registryLoaded: true })
    registryConnected.value = true
  } catch {
    tools.value = mergeToolRegistry(null, { registryLoaded: false })
    registryConnected.value = false
    ElMessage.warning('未能连接运维服务，请确认已登录')
  }
}

function formatExecTime (ts) {
  if (!ts) return '—'
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? String(ts) : d.toLocaleString('zh-CN')
}

function openTrace (row) {
  const tid = row?.traceId
  if (!tid) return
  window.dispatchEvent(
    new CustomEvent('ops-navigate-tab', { detail: { tab: 'audit', auditPane: 'trace', traceId: tid } })
  )
}

const loadExecutionHistory = async () => {
  historyLoading.value = true
  try {
    const rows = await getOpsTraceRecent(80)
    const list = (Array.isArray(rows) ? rows : [])
      .filter((r) => r?.toolName && r.toolName !== 'NONE')
      .map((r) => ({
        toolName: r.toolName,
        timestamp: r.createdAt || '',
        duration: r.durationMs || 0,
        success: r.executionOk === true,
        resultPreview: formatResultPreview(r.resultSummary),
        traceId: r.traceId,
        fullResult: r
      }))
    executionHistory.value = list
  } catch {
    ElMessage.error('加载执行记录失败')
  } finally {
    historyLoading.value = false
  }
}

onMounted(async () => {
  await loadTools()
  await loadExecutionHistory()
})

const getToolByName = (name) => tools.value.find((t) => t.name === name)

const selectTool = (tool) => {
  selectedTool.value = tool.name
  activeTab.value = 'execute'
  resetParams()
}

const resetParams = () => {
  Object.keys(params).forEach((key) => delete params[key])
}

const presentResult = (result) => {
  resultDialogRef.value?.open({
    toolName: result.toolName,
    success: result.success,
    duration: result.duration,
    traceId: result.traceId || result.fullResult?.traceId,
    raw: result.fullResult?.resultSummary ?? result.fullResult ?? result.raw
  })
}

const executeTool = async () => {
  if (!selectedTool.value) return
  if (!registryConnected.value) {
    ElMessage.error('未连接运维服务，无法执行')
    return
  }
  const toolMeta = getToolByName(selectedTool.value)
  if (toolMeta?.status !== 'online') {
    ElMessage.warning('所选工具当前不可用')
    return
  }

  executing.value = true

  try {
    let data = await executeMcpTool(selectedTool.value, { ...params })

    if (data && data.needConfirm === true) {
      try {
        await ElMessageBox.confirm(buildMcpRiskConfirmMessage(data), '操作确认', {
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          type: 'warning',
          closeOnClickModal: false,
          customClass: 'mcp-risk-confirm-box'
        })
      } catch {
        ElMessage.info('已取消')
        executionHistory.value.unshift({
          toolName: selectedTool.value,
          timestamp: new Date().toLocaleString('zh-CN'),
          duration: 0,
          success: false,
          resultPreview: '用户取消确认',
          fullResult: { success: false, needConfirm: true, cancelled: true }
        })
        return
      }
      data = await confirmMcpExecute(
        selectedTool.value,
        applyMcpWriteConfirmParams(selectedTool.value, params, ''),
        '确认执行'
      )
    }

    data = normalizeMcpToolResponse(data)

    const previewRaw = data.success ? data.data : (data.data?.error ?? data.error ?? data.message)

    const result = {
      toolName: selectedTool.value,
      timestamp: new Date().toLocaleString('zh-CN'),
      duration: data.duration || 0,
      success: data.success,
      resultPreview: formatResultPreview(previewRaw != null ? previewRaw : data),
      fullResult: data
    }

    executionHistory.value.unshift(result)
    presentResult(result)
    await loadExecutionHistory()
    const label = mcpToolDisplayName(selectedTool.value)
    if (!data.success) {
      const errMsg = data.error || data.message || data.data?.error || '未知错误'
      ElMessage.error(`${label} 执行失败：${errMsg}`)
    }
  } catch (error) {
    const failResult = {
      toolName: selectedTool.value,
      timestamp: new Date().toLocaleString('zh-CN'),
      duration: 0,
      success: false,
      resultPreview: error.message,
      fullResult: { success: false, error: error.message }
    }
    executionHistory.value.unshift(failResult)
    presentResult(failResult)
    ElMessage.error('执行失败：' + error.message)
  } finally {
    executing.value = false
  }
}

const viewDetail = (row) => {
  presentResult(row)
}
</script>

<style scoped>
.tool-console {
  padding: 0;
  background: transparent;
  min-height: auto;
}

.conn-alert {
  margin-bottom: 14px;
}

.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 0;
  gap: 12px;
}

.history-hint {
  font-size: 12px;
  color: var(--ops-text-subtle);
}

.page-header {
  text-align: left;
  padding: 16px 18px;
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  margin-bottom: 14px;
  box-shadow: var(--ops-shadow-sm);
}

.page-header h1 {
  font-size: 17px;
  font-weight: 600;
  color: var(--ops-text);
  margin: 0 0 6px;
  letter-spacing: -0.02em;
}

.subtitle {
  font-size: 13px;
  color: var(--ops-text-subtle);
  margin: 0;
  line-height: 1.5;
}

.tools-tabs {
  background: var(--ops-panel);
  border-radius: var(--ops-radius);
  overflow: hidden;
  border: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
}

.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  padding: 20px;
}

.tool-card {
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  border: 1px solid var(--ops-border) !important;
  background: var(--ops-panel) !important;
}

.tool-card:hover {
  box-shadow: var(--ops-shadow-md);
  border-color: #94a3b8 !important;
}

.tool-card.active {
  border-color: var(--ops-primary) !important;
  box-shadow: 0 0 0 1px rgba(13, 148, 136, 0.2);
}

.tool-card.offline {
  opacity: 0.72;
}

.tool-icon-badge {
  width: 44px;
  height: 44px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--ops-font-mono);
}

.tool-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ops-text);
  margin-bottom: 6px;
}

.tool-desc {
  font-size: 12px;
  color: var(--ops-text-subtle);
  margin-bottom: 12px;
  min-height: 36px;
  line-height: 1.45;
}

.tool-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #67c23a;
}

.execute-panel {
  padding: 20px;
}

.selected-tool-info {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
  padding: 15px;
  background: var(--ops-panel-soft, #f5f7fa);
  border-radius: 8px;
}

.tool-description {
  color: var(--ops-text-subtle, #606266);
}

.no-selection {
  padding: 60px 20px;
}

.tool-params h4 {
  margin-bottom: 15px;
  color: var(--ops-text, #303133);
}

.param-hint {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.execute-actions {
  display: flex;
  gap: 15px;
  justify-content: center;
  margin-top: 30px;
}
</style>

<style>
.mcp-risk-confirm-box .el-message-box__message {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: min(48vh, 420px);
  overflow-y: auto;
}
</style>
