<template>
  <div class="ops-page mcp-tools-demo">
    <div class="hero-section">
      <h1>本地工具控制台</h1>
      <p class="subtitle">受控执行 · Agentic 风险分 · 思维链审计 · Windows / Linux 节点</p>
      <div class="tech-tags">
        <el-tag type="success">Spring AI 1.1.0</el-tag>
        <el-tag type="info">MCP Protocol</el-tag>
        <el-tag type="warning">Drain-Plus Parser</el-tag>
        <el-tag type="danger">Security Guardrail</el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" type="border-card" class="tools-tabs">
      <el-tab-pane label="工具概览" name="overview">
        <div class="tools-grid">
          <el-card
            v-for="tool in tools"
            :key="tool.name"
            class="tool-card"
            :class="{ active: selectedTool === tool.name, offline: tool.status === 'offline' }"
            @click="selectTool(tool)"
          >
            <div class="tool-icon-badge">{{ mcpToolBadge(tool.name) }}</div>
            <div class="tool-name">{{ tool.name }}</div>
            <div class="tool-desc">{{ tool.description }}</div>
            <div class="tool-status" :class="tool.status">
              <span class="status-dot"></span>
              {{ tool.statusText }}
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="工具执行" name="execute">
        <div class="execute-panel">
          <div class="selected-tool-info" v-if="selectedTool">
            <el-tag size="large" type="success">{{ selectedTool }}</el-tag>
            <span class="tool-description">{{ getToolByName(selectedTool)?.description }}</span>
          </div>

          <div v-if="!selectedTool" class="no-selection">
            <el-empty description="请从左侧选择一个工具进行演示" />
          </div>

          <div v-else class="tool-execute-area">
            <div class="tool-params" v-if="getToolByName(selectedTool)?.params?.length">
              <h4>参数配置</h4>
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
                执行工具
              </el-button>
              <el-button size="large" @click="resetParams">重置</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="执行历史" name="history">
        <el-table :data="executionHistory" stripe style="width: 100%">
          <el-table-column prop="toolName" label="工具名称" width="150" />
          <el-table-column prop="timestamp" label="执行时间" width="180" />
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
          <el-table-column label="操作" width="120">
            <template #default="scope">
              <el-button type="primary" link @click="viewDetail(scope.row)">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="resultDialogVisible" title="工具执行结果" width="800px" center>
      <div v-if="currentResult" class="result-detail">
        <div class="result-header">
          <el-tag size="large" type="success">{{ currentResult.toolName }}</el-tag>
          <el-tag :type="currentResult.success ? 'success' : 'danger'">
            {{ currentResult.success ? '执行成功' : '执行失败' }}
          </el-tag>
          <span class="duration">耗时: {{ currentResult.duration }}ms</span>
        </div>

        <el-divider content-position="left">完整返回数据</el-divider>

        <div class="json-viewer">
          <pre>{{ formatFullResultJson(currentResult.fullResult) }}</pre>
        </div>
      </div>
    </el-dialog>

    <div v-if="executing" class="execution-overlay">
      <div class="execution-animation">
        <div class="spinner"></div>
        <p>正在执行 {{ selectedTool }}...</p>
        <p class="hint">调用底层系统命令: {{ getToolCommand(selectedTool) }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu } from '@element-plus/icons-vue'
import { executeMcpTool, confirmMcpExecute, getMcpTools } from '../api'
import { normalizeMcpToolResponse } from '../utils/mcpToolResult'
import { mergeToolRegistry, getMcpToolCommandHint, applyMcpWriteConfirmParams, mcpToolBadge } from '../utils/mcpToolsMeta'
import { buildMcpRiskConfirmMessage } from '../utils/mcpRiskConfirm'

const activeTab = ref('overview')
const selectedTool = ref(null)
const executing = ref(false)
const resultDialogVisible = ref(false)
const currentResult = ref(null)
const executionHistory = ref([])

const params = reactive({})

const tools = ref([])

onMounted(async () => {
  try {
    const raw = await getMcpTools()
    tools.value = mergeToolRegistry(raw)
  } catch {
    tools.value = mergeToolRegistry(null)
    ElMessage.warning('未能拉取服务端工具列表，已显示本地定义（状态可能不准）')
  }
})

const getToolByName = (name) => {
  return tools.value.find(t => t.name === name)
}

const selectTool = (tool) => {
  selectedTool.value = tool.name
  activeTab.value = 'execute'
  resetParams()
}

const resetParams = () => {
  Object.keys(params).forEach(key => delete params[key])
}

const getToolCommand = (toolName) => getMcpToolCommandHint(toolName)

const executeTool = async () => {
  if (!selectedTool.value) return

  executing.value = true

  try {
    let data = await executeMcpTool(selectedTool.value, { ...params })

    if (data && data.needConfirm === true) {
      try {
        await ElMessageBox.confirm(buildMcpRiskConfirmMessage(data), '安全护栏 · Agentic 风险 · 二次确认', {
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
          resultPreview: '用户取消中等风险二次确认',
          fullResult: { success: false, needConfirm: true, cancelled: true }
        })
        return
      }
      data = await confirmMcpExecute(
        selectedTool.value,
        applyMcpWriteConfirmParams(selectedTool.value, params),
        '确认执行'
      )
    }

    data = normalizeMcpToolResponse(data)

    const previewRaw = data.success ? data.data : (data.data?.error ?? data.error ?? data.message)
    const previewStr =
      typeof previewRaw === 'string'
        ? previewRaw
        : previewRaw != null
          ? JSON.stringify(previewRaw)
          : ''

    const result = {
      toolName: selectedTool.value,
      timestamp: new Date().toLocaleString('zh-CN'),
      duration: data.duration || 0,
      success: data.success,
      resultPreview: (previewStr || '').length > 140 ? previewStr.slice(0, 140) + '…' : previewStr || '-',
      fullResult: data
    }

    executionHistory.value.unshift(result)

    if (data.success) {
      ElMessage.success(`${selectedTool.value} 执行成功，耗时 ${result.duration}ms`)
    } else {
      const errMsg = data.error || data.message || data.data?.error || '未知错误'
      ElMessage.error(`${selectedTool.value} 执行失败: ${errMsg}`)
    }

  } catch (error) {
    ElMessage.error('工具执行失败: ' + error.message)
    executionHistory.value.unshift({
      toolName: selectedTool.value,
      timestamp: new Date().toLocaleString('zh-CN'),
      duration: 0,
      success: false,
      resultPreview: error.message,
      fullResult: { success: false, error: error.message }
    })
  } finally {
    executing.value = false
  }
}

const viewDetail = (row) => {
  currentResult.value = row
  resultDialogVisible.value = true
}

const formatFullResultJson = (fr) => {
  if (fr == null) return ''
  try {
    const obj = typeof fr === 'string' ? JSON.parse(fr) : fr
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(fr)
  }
}
</script>

<style scoped>
.mcp-tools-demo {
  padding: 0;
  background: transparent;
  min-height: auto;
}

.hero-section {
  text-align: left;
  padding: 16px 18px;
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  margin-bottom: 14px;
  box-shadow: var(--ops-shadow-sm);
}

.hero-section h1 {
  font-size: 17px;
  font-weight: 600;
  color: var(--ops-text);
  margin: 0 0 6px;
  letter-spacing: -0.02em;
}

.subtitle {
  font-size: 13px;
  color: var(--ops-text-subtle);
  margin: 0 0 12px;
  line-height: 1.5;
}

.tech-tags {
  display: flex;
  justify-content: flex-start;
  gap: 8px;
  flex-wrap: wrap;
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
  transform: none;
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

.tool-card.offline .status-dot {
  background: #909399;
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

.tool-status.online .status-dot {
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
  background: #f5f7fa;
  border-radius: 8px;
}

.tool-description {
  color: #606266;
}

.no-selection {
  padding: 60px 20px;
}

.tool-params {
  margin-bottom: 20px;
}

.tool-params h4 {
  margin-bottom: 15px;
  color: #303133;
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

.result-detail {
  padding: 10px;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
}

.duration {
  color: #909399;
  font-size: 14px;
}

.json-viewer {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 20px;
  overflow-x: auto;
}

.json-viewer pre {
  color: #d4d4d4;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  margin: 0;
}

.execution-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.execution-animation {
  background: white;
  padding: 40px 60px;
  border-radius: 16px;
  text-align: center;
}

.spinner {
  width: 60px;
  height: 60px;
  border: 4px solid #f3f3f3;
  border-top: 4px solid #409eff;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 20px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.execution-animation p {
  margin: 10px 0;
  font-size: 16px;
  color: #303133;
}

.execution-animation .hint {
  font-size: 12px;
  color: #909399;
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
