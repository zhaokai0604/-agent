<template>
  <div>
      <!-- 1. 初始上传状态 -->
      <transition name="el-fade-in-linear">
        <div v-if="!reportData && taskStatus.status !== 'PROCESSING' && taskStatus.status !== 'FAILED'" class="upload-section">
          <div class="hero-text">
            <h1>系统故障智能诊断</h1>
            <p>基于 Drain 算法与 AI 知识库，一键解析系统日志，精准定位系统隐患。</p>
          </div>
          
          <div class="upload-box" @click="triggerUpload" @drop.prevent="handleDrop" @dragover.prevent>
            <input type="file" ref="fileInput" class="hidden-input" @change="handleFileSelect">
            <el-icon class="upload-icon"><upload-filled /></el-icon>
            <div class="upload-hint">点击或拖拽系统日志到此处</div>
            <div class="upload-sub">支持所有日志格式，包括 Windows 事件日志、Linux 系统日志等</div>
          </div>
        </div>
      </transition>

      <!-- 2. 分析进度加载状态 -->
      <transition name="el-zoom-in-center">
        <div v-if="taskStatus.status === 'PROCESSING'" class="processing-section">
          <div class="radar-scan"></div>
          <h2 class="scan-text">正在进行全系统深度扫描...</h2>
          <div class="current-step">{{ taskStatus.currentStep }}</div>
          <el-progress 
            :percentage="taskStatus.progress" 
            :stroke-width="12" 
            :show-text="false"
            status="success"
            class="scan-progress"
          />
          <div class="scan-metrics">
            <span>任务进度: {{ taskStatus.progress }}%</span>
            <span>当前状态: {{ taskStatus.status }}</span>
          </div>
          <div class="task-controls">
            <el-button 
              type="danger" 
              size="large" 
              @click="handleCancelTask"
              :icon="CircleClose"
            >
              取消任务
            </el-button>
          </div>
        </div>
      </transition>

      <!-- 分析失败：跳转工作台对话 -->
      <transition name="el-fade-in-linear">
        <div v-if="taskStatus.status === 'FAILED' && !reportData" class="failure-section">
          <el-result icon="error" title="日志分析失败" :sub-title="taskStatus.errorMsg || '引擎处理异常，可到工作台对话做关联排查'">
            <template #extra>
              <el-button type="primary" @click="goOpsAgentAfterFailure">打开工作台对话复核</el-button>
              <el-button @click="resetTask">重新上传</el-button>
            </template>
          </el-result>
        </div>
      </transition>

      <!-- 3. 分析报告仪表盘 -->
      <transition name="el-zoom-in-bottom">
        <div v-if="reportData" class="dashboard-container">
          <!-- 顶部核心指标栏 -->
          <div class="kpi-board">
            <div class="kpi-card health-score">
              <div class="gauge-chart" id="healthGauge"></div>
              <div class="kpi-info">
                <div class="kpi-title">系统健康度</div>
                <div class="kpi-desc">{{ healthStatusText }}</div>
              </div>
            </div>
            
            <div class="kpi-card" v-for="item in stats" :key="item.label">
              <div class="stat-icon-wrapper" :style="{ background: item.bg }">
                 <el-icon :size="24" :color="item.color"><component :is="item.icon" /></el-icon>
              </div>
              <div class="stat-content">
                <div class="kpi-value" :style="{ color: item.color }">{{ item.value }}</div>
                <div class="kpi-label">{{ item.label }}</div>
              </div>
            </div>
            
            <div class="kpi-card action-card">
              <el-button type="primary" size="large" @click="resetTask" :icon="Refresh">上传新日志</el-button>
              <el-button
                v-if="showAgentDeepDiveOnReport"
                type="success"
                size="large"
                @click="goOpsAgentDeepDiveFromReport"
              >对话深挖</el-button>
              <el-button type="warning" size="large" @click="handleGlobalDiagnosis" :icon="Cpu">AI 智能全盘诊断</el-button>
              
              <el-dropdown @command="handleDownload">
                <el-button plain size="large" :icon="Download">
                  导出报告<el-icon class="el-icon--right"><arrow-down /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="csv">CSV 数据表格</el-dropdown-item>
                    <el-dropdown-item command="html">可视化 HTML 报告</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <el-row :gutter="24" class="content-row">
            <!-- 左侧：日志详情表 -->
            <el-col :span="16">
              <el-card class="detail-card" shadow="never">
                <template #header>
                  <div class="card-header">
                    <span class="title">关键事件追踪 (Top Risks)</span>
                    <el-tag type="danger" effect="plain" size="small">{{ anomalyLogs.length }} 个异常项</el-tag>
                  </div>
                </template>
                <el-table :data="anomalyLogs" stripe style="width: 100%" height="500px">
                  <el-table-column label="事件 ID" width="100">
                    <template #default="scope">
                       <span class="event-id-tag" @click="openDetail(scope.row)">{{ scope.$index + 1 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="severity" label="级别" width="90">
                    <template #default="scope">
                      <el-tag :type="getSeverityType(scope.row.severity)" size="small" effect="dark">{{ scope.row.severity }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="protocol" label="来源/组件" width="150" show-overflow-tooltip />
                  <el-table-column prop="desensitizedLog" label="消息内容" show-overflow-tooltip />
                  <el-table-column prop="logTime" label="时间" width="160" sortable />
                  <el-table-column label="AI 诊断" width="120" fixed="right">
                    <template #default="scope">
                      <el-button 
                        type="primary" 
                        link 
                        :icon="Cpu" 
                        @click.stop="handleSingleRowDiagnosis(scope.row)"
                      >智能分析</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>

            <!-- 右侧：分布图与建议 -->
            <el-col :span="8">
              <el-card class="chart-card" shadow="never">
                <template #header><span class="title">风险等级分布</span></template>
                <div class="severity-chart-wrapper">
                  <div id="severityChart" style="height: 220px; flex: 1.5;"></div>
                  <div class="severity-legend">
                    <div class="legend-item" v-for="item in legendData" :key="item.name">
                      <span class="dot" :style="{ backgroundColor: item.color }"></span>
                      <span class="label">{{ item.name }}</span>
                    </div>
                  </div>
                </div>
              </el-card>
              
              <el-card class="advice-card" shadow="never">
                <template #header><span class="title">系统优化建议</span></template>
                <div class="advice-list">
                  <div class="advice-item" v-for="(adv, index) in adviceList" :key="index">
                    <el-icon :color="adv.type === 'warn' ? '#E6A23C' : '#409EFF'"><warning v-if="adv.type==='warn'" /><circle-check v-else /></el-icon>
                    <span>{{ adv.text }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </transition>

    <!-- 详情弹窗 -->
    <el-dialog v-model="logDetailDialogVisible" title="日志详情" width="800px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="日志时间">{{ currentLogDetail.logTime }}</el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag :type="getSeverityType(currentLogDetail.severity || '')" size="small">{{ currentLogDetail.severity }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源/组件">{{ currentLogDetail.protocol }}</el-descriptions-item>
        <el-descriptions-item label="事件ID (PID)">{{ currentLogDetail.pid }}</el-descriptions-item>
        <el-descriptions-item label="模板ID">{{ currentLogDetail.templateId }}</el-descriptions-item>
        <el-descriptions-item label="异常得分">{{ currentLogDetail.anomalyScore }}</el-descriptions-item>
      </el-descriptions>

      <div style="margin-top: 20px;">
        <h4>消息内容</h4>
        <div class="log-content-box">{{ currentLogDetail.desensitizedLog }}</div>
      </div>

      <div v-if="currentLogDetail.anomalyReasons && currentLogDetail.anomalyReasons.length" style="margin-top: 20px;">
        <h4 style="color: #F56C6C;">异常原因</h4>
        <ul style="color: #F56C6C;">
          <li v-for="(reason, idx) in currentLogDetail.anomalyReasons" :key="idx">{{ reason }}</li>
        </ul>
      </div>

      <div v-if="currentLogDetail.stackTrace && currentLogDetail.stackTrace !== '无异常栈'" style="margin-top: 20px;">
        <h4>堆栈信息</h4>
        <div class="stack-trace-box">{{ currentLogDetail.stackTrace }}</div>
      </div>
    </el-dialog>

    <!-- AI 诊断弹窗 -->
    <el-dialog v-model="aiDialogVisible" title="AI 系统故障智能诊断助手" width="700px" center custom-class="ai-dialog">
      <div class="ai-content">
        <div class="ai-header">
          <div class="ai-avatar">🤖</div>
          <div class="ai-bubble">
            <p v-if="aiThinking">正在深度分析关键异常日志，生成专业诊断报告...</p>
            <p v-else>基于 <strong>AI大模型 (Qwen-Max)</strong> 的诊断结果如下：</p>
          </div>
        </div>
        
        <div class="analysis-result" v-if="aiThinking">
           <el-skeleton :rows="5" animated />
        </div>
        <div class="analysis-result fade-in" v-else>
           <!-- 使用 v-html 渲染 Markdown -->
           <div class="ai-markdown-body" v-html="renderedAiDiagnosis"></div>
        </div>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="aiDialogVisible = false">关闭</el-button>
          <el-button type="primary" @click="copySolution">复制诊断报告</el-button>
        </span>
      </template>
    </el-dialog>
    
    <!-- 告警配置弹窗 -->
    <el-dialog v-model="alarmConfigVisible" title="告警配置" width="600px">
      <el-form :model="alarmConfig" :rules="alarmConfigRules" ref="alarmConfigRef">
        <el-form-item label="启用告警">
          <el-switch v-model="alarmConfig.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        
        <el-form-item label="推送渠道" required>
          <el-checkbox-group v-model="alarmConfig.pushChannels">
            <el-checkbox label="wechat" border>企业微信</el-checkbox>
            <el-checkbox label="email" border>邮箱</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        
        <el-form-item label="告警级别" required>
          <el-select v-model="alarmConfig.minSeverity" placeholder="请选择最低告警级别" style="width: 100%;">
            <el-option label="INFO" value="INFO_LEVEL" />
            <el-option label="WARNING" value="WARNING_LEVEL" />
            <el-option label="ERROR" value="ERROR_LEVEL" />
            <el-option label="FATAL" value="FATAL_LEVEL" />
          </el-select>
        </el-form-item>
        
        <el-form-item label="错误数量阈值" required>
          <el-input-number v-model="alarmConfig.errorThreshold" :min="1" :max="1000" placeholder="同一任务中错误数量超过此值才推送" />
        </el-form-item>
        
        <el-form-item label="企业微信用户ID">
          <el-input v-model="alarmConfig.wechatUserId" placeholder="企业微信用户ID" clearable />
        </el-form-item>
        
        <el-form-item label="邮箱地址">
          <el-input v-model="alarmConfig.email" type="email" placeholder="邮箱地址" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="alarmConfigVisible = false">取消</el-button>
          <el-button type="primary" @click="saveAlarmConfig">保存配置</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, defineExpose } from 'vue'
import { UploadFilled, WarningFilled, Warning, CircleCheck, Refresh, Download, Cpu, Timer, CircleCheckFilled, ArrowDown, CircleClose, Setting } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { uploadLog, uploadLogs, getTaskStatus, getReport, performDiagnosis, cancelTask, getAlarmConfig as getAlarmConfigApi, saveAlarmConfig as saveAlarmConfigApi, downloadReport, getApiBaseUrl } from '../api'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import {
  dispatchOpsAgentPrefill,
  agentPrefillEngineFailure,
  agentPrefillHighAnomaly,
  OPS_AGENT_ANOMALY_THRESHOLD
} from '../utils/opsAgentNavigate'

// Props to accept taskId from parent (history navigation)
const props = defineProps(['initialTaskId'])

// Markdown parser
const md = new MarkdownIt()

// --- State ---
const fileInput = ref(null)
const taskId = ref('')
const reportData = ref(null)
const anomalyLogs = ref([])
const stats = ref([])
const adviceList = ref([])

const legendData = [
  { name: '致命故障', color: '#B71C1C' },
  { name: '运行错误', color: '#FF5722' },
  { name: '风险警告', color: '#FFC107' },
  { name: '系统信息', color: '#1E88E5' },
  { name: '未知等级', color: '#9E9E9E' }
]

// Log Detail Dialog State
const logDetailDialogVisible = ref(false)
const currentLogDetail = ref({})

// AI Dialog State
const aiDialogVisible = ref(false)
const aiThinking = ref(false)
const currentEventId = ref('')
const aiResult = reactive({ summary: '', reasons: [], solution: '' })
const aiResultText = ref('') // Store the global diagnosis text

const renderedAiDiagnosis = computed(() => {
  return md.render(aiResultText.value)
})

// 告警配置弹窗状态
const alarmConfigVisible = ref(false)
const alarmConfigRef = ref()

// 告警配置表单数据
const alarmConfig = reactive({
  enabled: true, // 是否启用告警
  pushChannels: ['wechat', 'email'], // 默认推送渠道
  minSeverity: 'ERROR_LEVEL', // 默认最低告警级别
  errorThreshold: 5, // 默认错误数量阈值
  wechatUserId: '', // 企业微信用户ID
  email: '' // 邮箱地址
})

// 告警配置表单验证规则
const alarmConfigRules = {
  pushChannels: [
    { required: true, message: '请至少选择一个推送渠道', trigger: 'change' }
  ],
  minSeverity: [
    { required: true, message: '请选择最低告警级别', trigger: 'change' }
  ],
  errorThreshold: [
    { required: true, message: '请输入错误数量阈值', trigger: 'blur' },
    { type: 'number', min: 1, max: 1000, message: '错误数量阈值在1到1000之间', trigger: 'blur' }
  ]
}

const taskStatus = reactive({
  status: 'IDLE', // IDLE, PROCESSING, COMPLETED, FAILED
  progress: 0,
  currentStep: '',
  errorMsg: ''
})

let timer = null

// Initialize with prop if available
onMounted(() => {
    if (props.initialTaskId) {
        taskId.value = props.initialTaskId
        // 标记为已完成以触发历史任务报告加载
        taskStatus.status = 'COMPLETED'
        fetchReport()
    }
    
    // 初始化告警配置
    initAlarmConfig()
})

// Expose a method to load specific task
const loadTask = async (id) => {
    taskId.value = id
    taskStatus.status = 'COMPLETED'
    try {
        // 直接调用getReport，捕获错误
        const data = await getReport(id)
        reportData.value = data
        
        // 确保data.result不是null，否则使用空数组
        const result = data.result || []
        
        anomalyLogs.value = result.filter(i => 
          i.severity.includes('ERROR') || i.severity.includes('FATAL') || i.anomaly
        ).slice(0, 100)

        const s = data.summary
        const healthScore = Math.max(0, 100 - (s.anomalyCount * 5))

        stats.value = [
          { label: '扫描耗时', value: (s.costTime / 1000).toFixed(2) + 's', color: '#606266', icon: Timer, bg: '#f2f6fc' },
          { label: '风险条目', value: s.anomalyCount, color: '#F56C6C', icon: WarningFilled, bg: '#fef0f0' },
          { label: '安全威胁', value: '无', color: '#67C23A', icon: CircleCheckFilled, bg: '#f0f9eb' }
        ]
        
        generateAdvice(anomalyLogs.value)

        nextTick(() => {
          initCharts(result, healthScore)
        })
    } catch (error) {
        console.error('加载任务失败:', error)
        ElMessage.error('加载任务失败：未找到相关任务')
        // 重置任务状态，显示上传界面
        taskStatus.status = 'IDLE'
        reportData.value = null
    }
}


// --- Log Detail Logic ---
const openDetail = (row) => {
  currentLogDetail.value = row
  logDetailDialogVisible.value = true
}

const handleSingleRowDiagnosis = async (row) => {
    aiDialogVisible.value = true
    aiThinking.value = true
    aiResultText.value = ''
    
    let payload
    try {
        payload = JSON.parse(JSON.stringify(row))
    } catch (e) {
        payload = row
    }

    try {
        const response = await fetch(`${getApiBaseUrl()}/log/quick-diagnose`, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        })

        const text = await response.text()
        if (!response.ok) {
            let msg = `请求失败 (${response.status})`
            try {
                const errJson = JSON.parse(text)
                if (errJson.message) msg = errJson.message
            } catch {
                if (text) msg = text.slice(0, 200)
            }
            aiResultText.value = '诊断失败: ' + msg
            return
        }

        const resData = JSON.parse(text)
        if (resData.code === 200) {
             aiResultText.value = resData.data
        } else {
             aiResultText.value = '诊断失败: ' + (resData.message || '未知错误')
        }
    } catch (error) {
        aiResultText.value = '智能诊断服务连接失败，请稍后再试。'
        console.error('Diagnosis error:', error)
    } finally {
        aiThinking.value = false
    }
}

// --- Global AI Diagnosis ---
const handleGlobalDiagnosis = async () => {
    if (!taskId.value) {
        ElMessage.warning('请先上传并分析日志')
        return
    }
    
    // Check if result is already cached in reportData
    if (reportData.value && reportData.value.aiDiagnosis) {
        aiResultText.value = reportData.value.aiDiagnosis
        aiDialogVisible.value = true
        return
    }

    // Check task status before calling AI diagnosis
    try {
        const taskStatus = await getTaskStatus(taskId.value)
        if (taskStatus.status !== 'COMPLETED') {
            ElMessage.warning('请等待日志分析完成后再进行AI诊断')
            return
        }
    } catch (error) {
        ElMessage.error('获取任务状态失败，请稍后再试')
        return
    }

    aiDialogVisible.value = true
    aiThinking.value = true
    aiResultText.value = ''

    // 与「统一 AI 助手」一致：用 fetch + ReadableStream 读流。浏览器 EventSource 对 Spring SSE 兼容性差，易导致无输出或立即断开。
    try {
        const response = await fetch(`${getApiBaseUrl()}/log/diagnose/stream/${taskId.value}`, {
            method: 'GET',
            credentials: 'include'
        })
        if (!response.ok) {
            const errText = await response.text().catch(() => '')
            let msg = `请求失败 (${response.status})`
            try {
                const j = JSON.parse(errText)
                if (j.message) msg = j.message
            } catch {
                if (errText) msg = errText.slice(0, 300)
            }
            aiResultText.value = '诊断失败: ' + msg
            return
        }
        const reader = response.body?.getReader()
        if (!reader) {
            aiResultText.value = '诊断失败: 服务端未返回流式数据'
            return
        }
        const decoder = new TextDecoder()
        let sseBuf = ''
        while (true) {
            const { done, value } = await reader.read()
            if (done) break
            aiThinking.value = false
            const chunk = decoder.decode(value, { stream: true })
            sseBuf = appendSsePayloadFromChunk(sseBuf, chunk, (payload) => {
                aiResultText.value += payload
            })
        }
        sseBuf = flushSseBuffer(sseBuf, (payload) => {
            aiResultText.value += payload
        })
        if (reportData.value) {
            reportData.value.aiDiagnosis = aiResultText.value
        }
    } catch (error) {
        console.error('Global diagnosis error:', error)
        if (aiResultText.value.length === 0) {
            aiResultText.value = '诊断服务连接中断，请确认任务存在且AI服务可用。'
        } else if (reportData.value) {
            reportData.value.aiDiagnosis = aiResultText.value
        }
    } finally {
        aiThinking.value = false
    }
}

// --- Upload Logic ---
const triggerUpload = () => fileInput.value.click()
const handleFileSelect = (e) => processFiles(e.target.files)
const handleDrop = (e) => processFiles(e.dataTransfer.files)

const processFiles = async (files) => {
  if (!files || files.length === 0) return
  
  // 只处理第一个文件，回归到单文件上传
  const file = files[0]
  if (!file) {
    ElMessage.warning('请选择有效的日志文件')
    return
  }
  
  try {
    taskStatus.status = 'PROCESSING'
    taskStatus.progress = 0
    taskStatus.currentStep = '正在建立安全连接...'
    taskStatus.errorMsg = ''
    
    // 单文件上传
    const formData = new FormData()
    formData.append('file', file)
    const id = await uploadLog(formData)
    taskId.value = id
    
    timer = setInterval(async () => {
      try {
        const res = await getTaskStatus(taskId.value)
        taskStatus.progress = res.progress
        taskStatus.currentStep = res.currentStep
        taskStatus.status = res.status
        
        if (res.status === 'COMPLETED') {
          clearInterval(timer)
          fetchReport()
        } else if (res.status === 'FAILED') {
          clearInterval(timer)
          taskStatus.errorMsg = res.errorMsg || '分析失败'
          ElMessage.error(taskStatus.errorMsg)
        }
      } catch (err) {
      }
    }, 1000)

  } catch (err) {
    taskStatus.status = 'FAILED'
    taskStatus.errorMsg = err.message || '上传失败'
    ElMessage.error('上传失败：' + err.message)
  }
}

// --- Report Logic ---
const fetchReport = async () => {
  const data = await getReport(taskId.value)
  reportData.value = data
  
  // 确保data.result不是null，否则使用空数组
  const result = data.result || []
  
  anomalyLogs.value = result.filter(i => 
    i.severity.includes('ERROR') || i.severity.includes('FATAL') || i.anomaly
  ).slice(0, 100)

  const s = data.summary
  const healthScore = Math.max(0, 100 - (s.anomalyCount * 5))

  stats.value = [
    { label: '扫描耗时', value: (s.costTime / 1000).toFixed(2) + 's', color: '#606266', icon: Timer, bg: '#f2f6fc' },
    { label: '风险条目', value: s.anomalyCount, color: '#F56C6C', icon: WarningFilled, bg: '#fef0f0' },
    { label: '安全威胁', value: '无', color: '#67C23A', icon: CircleCheckFilled, bg: '#f0f9eb' }
  ]
  
  generateAdvice(anomalyLogs.value)

  nextTick(() => {
    initCharts(result, healthScore)
  })
}

// --- Health Score Logic ---
const healthStatusText = computed(() => {
  const score = stats.value[1] ? 100 - (stats.value[1].value * 2) : 100
  if(score >= 90) return '系统状态极佳'
  if(score >= 70) return '系统状态良好'
  return '系统存在隐患'
})

const showAgentDeepDiveOnReport = computed(() => {
  const s = reportData.value?.summary
  if (!s) return false
  const n = Number(s.anomalyCount)
  return Number.isFinite(n) && n >= OPS_AGENT_ANOMALY_THRESHOLD
})

const goOpsAgentDeepDiveFromReport = () => {
  const d = reportData.value
  if (!d?.summary) return
  dispatchOpsAgentPrefill(
    agentPrefillHighAnomaly({
      taskId: d.taskId || taskId.value,
      fileName: d.fileName,
      status: 'COMPLETED',
      summary: d.summary
    })
  )
}

const copySolution = async () => {
  try {
    // 使用浏览器的 Clipboard API 复制诊断报告
    await navigator.clipboard.writeText(aiResultText.value)
    ElMessage.success('诊断报告已成功复制到剪贴板')
    aiDialogVisible.value = false
  } catch (err) {
    console.error('复制到剪贴板失败:', err)
    ElMessage.error('复制到剪贴板失败，请手动复制')
  }
}

const generateAdvice = (logs) => {
    adviceList.value = []
    if (logs.length > 0) {
        adviceList.value.push({ type: 'warn', text: `发现 ${logs.length} 个异常事件，建议优先处理高危项。` })
        adviceList.value.push({ type: 'info', text: '建议定期清理 C:\\Windows\\Temp 目录。' })
    } else {
        adviceList.value.push({ type: 'success', text: '暂未发现明显系统风险。' })
    }
}

// --- Visuals ---
const initCharts = (result, healthScore) => {
  // 确保result是数组，否则使用空数组
  const chartData = Array.isArray(result) ? result : []
  
  const gaugeChart = echarts.init(document.getElementById('healthGauge'))
  gaugeChart.setOption({
    series: [{
      type: 'gauge',
      startAngle: 90, endAngle: -270,
      pointer: { show: false },
      progress: { 
        show: true, 
        overlap: false, 
        roundCap: true, 
        clip: false
      },
      axisLine: { lineStyle: { width: 10, color: [[1, '#E6EBF8']] } },
      splitLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      data: [{
        value: healthScore,
        itemStyle: { color: healthScore > 80 ? '#67C23A' : '#E6A23C' }
      }],
      detail: {
        width: 50, height: 14,
        fontSize: 24, color: '#303133',
        formatter: '{value}',
        offsetCenter: ['0%', '0%']
      }
    }]
  })

  // 2. Pie Chart
  const pieChart = echarts.init(document.getElementById('severityChart'))
  
  const severityMap = {
    'FATAL_LEVEL': { name: '致命故障', color: '#B71C1C' },
    'ERROR_LEVEL': { name: '运行错误', color: '#FF5722' },
    'WARNING_LEVEL': { name: '风险警告', color: '#FFC107' },
    'INFO_LEVEL': { name: '系统信息', color: '#1E88E5' },
    'DEBUG_LEVEL': { name: '调试跟踪', color: '#4CAF50' },
    'UNKNOWN_LEVEL': { name: '未知等级', color: '#9E9E9E' }
  }

  const counts = {}
  chartData.forEach(r => {
    const key = r.severity
    counts[key] = (counts[key] || 0) + 1
  })

  const data = Object.keys(counts).map(k => {
    const config = severityMap[k] || { name: k, color: '#909399' }
    return { 
      name: config.name, 
      value: counts[k],
      itemStyle: { color: config.color }
    }
  })
  
  pieChart.setOption({
    tooltip: { 
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)'
    },
    series: [{
      type: 'pie',
      radius: ['50%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 6,
        borderColor: '#fff',
        borderWidth: 2
      },
      label: { show: false },
      data: data
    }]
  })
}

const getSeverityType = (sev) => {
  if (sev.includes('FATAL')) return 'danger'
  if (sev.includes('ERROR')) return 'danger'
  if (sev.includes('WARN')) return 'warning'
  return 'info'
}

const resetTask = () => {
  reportData.value = null
  taskStatus.status = 'IDLE'
  taskStatus.progress = 0
  taskStatus.currentStep = ''
  taskStatus.errorMsg = ''
  // 只有当fileInput.value存在时才重置其值
  if (fileInput.value) {
    fileInput.value.value = '' // reset input
  }
}

const goOpsAgentAfterFailure = () => {
  dispatchOpsAgentPrefill(agentPrefillEngineFailure(taskId.value, taskStatus.errorMsg))
}


// 取消任务方法
const handleCancelTask = async () => {
  if (!taskId.value) return
  
  try {
    // 先清除定时器，避免继续轮询任务状态
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    
    await cancelTask(taskId.value)
    // 调用后端API成功后，重置前端状态
    resetTask()
    ElMessage.success('任务已取消')
  } catch (error) {
    ElMessage.error('取消任务失败: ' + error.message)
    console.error('取消任务失败:', error)
  }
}

// 显示告警配置弹窗
const showAlarmConfig = () => {
  initAlarmConfig()
  alarmConfigVisible.value = true
}

// 保存告警配置
const saveAlarmConfig = async () => {
  if (!alarmConfigRef.value) return
  
  try {
    await alarmConfigRef.value.validate()
    
    const payload = {
      enabled: alarmConfig.enabled,
      pushChannels: (alarmConfig.pushChannels || []).map((item) => String(item).toUpperCase()),
      alarmLevel: String(alarmConfig.minSeverity || 'ERROR_LEVEL').replace('_LEVEL', ''),
      errorThreshold: alarmConfig.errorThreshold,
      wechatUserId: alarmConfig.wechatUserId || '',
      emailAddress: alarmConfig.email || '',
      cooldownMs: 300000
    }
    await saveAlarmConfigApi(payload, taskId.value || '')
    
    // 关闭弹窗
    alarmConfigVisible.value = false
    
    ElMessage.success('告警配置保存成功')
  } catch (error) {
    console.error('保存告警配置失败:', error)
    ElMessage.error('保存告警配置失败')
  }
}

const handleDownload = async (type) => {
  try {
    if (!taskId.value) {
      ElMessage.error('缺少任务 ID，无法导出')
      return
    }
    const blob = await downloadReport(taskId.value, type)
    const fileName = type === 'csv' ? 'log_analysis_result.csv' : 'log_analysis_report.html'
    const url = window.URL.createObjectURL(new Blob([blob]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', fileName)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success(`已下载 ${fileName}`)
  } catch (error) {
    console.error('下载报告失败:', error)
    ElMessage.error('下载报告失败，请先确认已登录且报告已生成')
  }
}

// 初始化告警配置（优先任务级，否则默认配置）
const initAlarmConfig = async () => {
  try {
    const data = await getAlarmConfigApi(taskId.value || '')
    if (!data) return
    alarmConfig.enabled = data.enabled ?? true
    alarmConfig.pushChannels = data.pushChannels || ['WECHAT', 'EMAIL']
    alarmConfig.minSeverity = `${(data.alarmLevel || 'ERROR').toUpperCase()}_LEVEL`
    alarmConfig.errorThreshold = data.errorThreshold ?? 5
    alarmConfig.wechatUserId = data.wechatUserId || ''
    alarmConfig.email = data.emailAddress || ''
  } catch (error) {
    console.error('加载告警配置失败:', error)
  }
}

// 暴露所有需要给父组件访问的方法
defineExpose({
  loadTask,
  showAlarmConfig
})
</script>

<style scoped>
/* 上传区域 */
.upload-section {
  text-align: center;
  margin: 36px 0;
  padding: 0 20px;
  animation: fadeInUp 0.8s ease;
}

.failure-section {
  max-width: 560px;
  margin: 48px auto;
  padding: 24px;
}

.hero-text {
  margin-bottom: 24px;
  animation: fadeIn 0.8s ease 0.2s both;
}

.hero-text h1 {
  font-size: 32px;
  color: #0f172a;
  margin-bottom: 10px;
  font-weight: 700;
  text-shadow: none;
  line-height: 1.2;
}

.hero-text p {
  font-size: 14px;
  color: #64748b;
  margin-bottom: 0;
  font-weight: 400;
  max-width: 720px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

.upload-box {
  width: min(760px, 100%);
  height: 240px;
  margin: 0 auto;
  background: #ffffff;
  border: 2px dashed #cbd5e1;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
  position: relative;
  overflow: hidden;
}

.upload-box::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: linear-gradient(45deg, transparent, rgba(79, 195, 247, 0.1), transparent);
  transform: rotate(45deg);
  animation: shimmer 2s infinite;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.upload-box:hover {
  border-color: #0ea5e9;
  background: #f8fbff;
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(14, 165, 233, 0.12);
}

.upload-box:hover::before {
  opacity: 1;
}

.upload-icon {
  font-size: 56px;
  color: #0ea5e9;
  margin-bottom: 14px;
  transition: all 0.3s ease;
  opacity: 0.8;
}

.upload-box:hover .upload-icon {
  transform: scale(1.1) rotate(5deg);
  opacity: 1;
}

.upload-hint {
  font-size: 16px;
  color: #1e293b;
  font-weight: 500;
  margin-bottom: 12px;
  transition: color 0.3s ease;
}

.upload-box:hover .upload-hint {
  color: #1a237e;
}

.upload-sub {
  font-size: 12px;
  color: #64748b;
  margin-top: 0;
  transition: color 0.3s ease;
}

.upload-box:hover .upload-sub {
  color: #64748b;
}

.hidden-input { display: none; }

/* 处理进度区域 */
.processing-section {
  text-align: center;
  margin: 36px 0;
  padding: 28px;
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid #dbe2ea;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  animation: fadeIn 0.8s ease;
}

.radar-scan {
  width: 120px;
  height: 120px;
  margin: 0 auto 40px;
  border: 4px solid rgba(79, 195, 247, 0.2);
  border-radius: 50%;
  position: relative;
  animation: radarScan 2s linear infinite;
}

.radar-scan::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 0;
  height: 0;
  border-top: 2px solid #4fc3f7;
  border-right: 60px solid #4fc3f7;
  border-bottom: 2px solid transparent;
  border-left: 60px solid transparent;
  transform-origin: left center;
  animation: radarRotate 2s linear infinite;
}

.scan-text {
  color: #0f172a;
  margin-bottom: 12px;
  font-size: 20px;
  font-weight: 600;
  text-shadow: 0 2px 4px rgba(26, 35, 126, 0.1);
}

.current-step {
  color: #0284c7;
  font-size: 14px;
  margin-bottom: 16px;
  font-weight: 500;
}

.scan-progress {
  width: min(720px, 100%);
  margin: 16px auto;
  height: 16px;
  border-radius: 8px;
  overflow: hidden;
}

.scan-progress .el-progress__bar {
  border-radius: 8px;
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}

.scan-progress .el-progress__bar__inner {
  border-radius: 8px;
  background: linear-gradient(90deg, #4fc3f7, #29b6f6);
  box-shadow: 0 0 10px rgba(79, 195, 247, 0.5);
}

.scan-metrics {
  display: flex;
  justify-content: center;
  gap: 30px;
  margin: 20px 0;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
}

.task-controls {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-top: 40px;
  flex-wrap: wrap;
}

.task-controls .el-button {
  min-width: 140px;
  height: 48px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.task-controls .el-button:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.15);
}

/* KPI仪表盘 */
.kpi-board {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
  animation: fadeInUp 0.8s ease;
}

.kpi-card {
  background: #ffffff;
  border-radius: 10px;
  flex: 1;
  min-width: 180px;
  padding: 16px;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid #dbe2ea;
}

.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.1);
}

.health-score {
  flex: 1.8;
  justify-content: flex-start;
  gap: 20px;
  background: #f8fbff;
}

.stat-icon-wrapper {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
}

.kpi-card:hover .stat-icon-wrapper {
  transform: scale(1.1);
}

.stat-content {
  text-align: left;
  flex: 1;
}

.gauge-chart {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
}

.kpi-info {
  text-align: left;
  flex: 1;
}

.kpi-title {
  font-size: 14px;
  color: #607d8b;
  font-weight: 500;
  margin-bottom: 8px;
}

.kpi-desc {
  font-size: 24px;
  font-weight: 700;
  color: #1a237e;
  margin-top: 0;
  line-height: 1.2;
}

.kpi-value {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 8px;
  line-height: 1;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.kpi-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.action-card {
  flex-direction: row;
  gap: 16px;
  background: transparent;
  box-shadow: none;
  padding: 0;
  justify-content: flex-end;
  flex-wrap: wrap;
  min-width: 400px;
}

.action-card .el-button {
  border-radius: 12px;
  padding: 12px 24px;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.action-card .el-button:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.15);
}

/* 卡片样式 */
.detail-card,
.chart-card,
.advice-card {
  border-radius: 10px;
  overflow: hidden;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  background: #ffffff;
  border: 1px solid #dbe2ea;
}

.detail-card:hover,
.chart-card:hover,
.advice-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.1);
}

.detail-card .el-card__header,
.chart-card .el-card__header,
.advice-card .el-card__header {
  background: linear-gradient(135deg, #f8fafc 0%, #ffffff 100%);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  padding: 20px 24px;
  border-radius: 16px 16px 0 0;
}

.detail-card .title,
.chart-card .title,
.advice-card .title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  border-left: 3px solid #0ea5e9;
  padding-left: 10px;
  margin: 0;
}

/* 表格样式 */
.detail-card .el-table {
  border-radius: 0 0 16px 16px;
  overflow: hidden;
}

.detail-card .el-table th {
  background: #f8fafc;
  font-weight: 600;
  color: #334155;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.detail-card .el-table td {
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  transition: background-color 0.3s ease;
}

.detail-card .el-table tr:hover td {
  background: #f0f9ff !important;
}

.event-id-tag {
  color: #4fc3f7;
  cursor: pointer;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: rgba(79, 195, 247, 0.1);
  border-radius: 16px;
  transition: all 0.3s ease;
}

.event-id-tag:hover {
  background: rgba(79, 195, 247, 0.2);
  transform: translateX(4px);
  text-decoration: none;
}

/* 图表样式 */
.severity-chart-wrapper {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px;
}

.severity-legend {
  flex: 1;
  padding-left: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #64748b;
  font-weight: 500;
  transition: all 0.3s ease;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 8px;
}

.legend-item:hover {
  background: rgba(79, 195, 247, 0.1);
  transform: translateX(4px);
}

.legend-item .dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

/* 建议列表 */
.advice-list {
  margin-top: 20px;
  padding: 0 20px 20px;
}

.advice-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 20px;
  font-size: 14px;
  color: #1e293b;
  line-height: 1.6;
  padding: 16px;
  background: rgba(255, 255, 255, 0.8);
  border-radius: 8px;
  transition: all 0.3s ease;
  border-left: 4px solid transparent;
}

.advice-item:hover {
  transform: translateX(4px);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.advice-item:nth-child(odd) {
  border-left-color: #4fc3f7;
  background: rgba(79, 195, 247, 0.05);
}

.advice-item:nth-child(even) {
  border-left-color: #43a047;
  background: rgba(67, 160, 71, 0.05);
}

/* AI诊断弹窗 */
.ai-dialog {
  border-radius: 20px;
  overflow: hidden;
}

.ai-dialog .el-dialog__header {
  background: linear-gradient(135deg, #1a237e 0%, #283593 100%);
  color: white;
  padding: 24px;
  border-radius: 20px 20px 0 0;
}

.ai-dialog .el-dialog__title {
  color: white;
  font-size: 20px;
  font-weight: 600;
}

.ai-content {
  padding: 32px;
  background: #f8fafc;
}

.ai-header {
  display: flex;
  gap: 20px;
  margin-bottom: 24px;
  align-items: flex-start;
}

.ai-avatar {
  width: 60px;
  height: 60px;
  background: linear-gradient(135deg, #4fc3f7 0%, #29b6f6 100%);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  box-shadow: 0 4px 16px rgba(79, 195, 247, 0.3);
  flex-shrink: 0;
}

.ai-bubble {
  background: white;
  padding: 20px;
  border-radius: 16px;
  font-size: 16px;
  color: #334155;
  font-weight: 500;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  flex: 1;
  line-height: 1.6;
}

.analysis-result {
  background: white;
  padding: 24px;
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  min-height: 300px;
}

.ai-markdown-body {
  white-space: pre-wrap;
  font-size: 15px;
  color: #334155;
  line-height: 1.8;
}

.ai-markdown-body h1,
.ai-markdown-body h2,
.ai-markdown-body h3 {
  color: #1a237e;
  margin-top: 24px;
  margin-bottom: 16px;
}

.ai-markdown-body code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 14px;
}

.ai-markdown-body pre {
  background: #f1f5f9;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 16px 0;
}

.ai-markdown-body pre code {
  background: transparent;
  padding: 0;
}

/* 日志详情弹窗 */
.log-content-box {
  background: #f8fafc;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  font-family: 'Courier New', Consolas, Monaco, monospace;
  font-size: 14px;
  color: #334155;
  max-height: 250px;
  overflow-y: auto;
  white-space: pre-wrap;
  line-height: 1.6;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.05);
}

.stack-trace-box {
  background: linear-gradient(135deg, #1e1e1e 0%, #2d2d2d 100%);
  color: #e0e0e0;
  padding: 20px;
  border-radius: 12px;
  font-family: 'Courier New', Consolas, Monaco, monospace;
  font-size: 13px;
  max-height: 250px;
  overflow-y: auto;
  white-space: pre;
  line-height: 1.5;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.3);
}

.stack-trace-box::-webkit-scrollbar {
  width: 8px;
}

.stack-trace-box::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
}

.stack-trace-box::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 4px;
}

/* 动画效果 */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(40px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes radarScan {
  0% {
    border-color: rgba(79, 195, 247, 0.2);
  }
  50% {
    border-color: rgba(79, 195, 247, 0.6);
  }
  100% {
    border-color: rgba(79, 195, 247, 0.2);
  }
}

@keyframes radarRotate {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}

@keyframes shimmer {
  0% {
    transform: translateX(-100%) rotate(45deg);
  }
  100% {
    transform: translateX(100%) rotate(45deg);
  }
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .hero-text h1 {
    font-size: 36px;
  }
  
  .upload-box {
    width: 600px;
    height: 280px;
  }
  
  .scan-progress {
    width: 500px;
  }
  
  .kpi-board {
    flex-direction: column;
  }
  
  .kpi-card {
    width: 100%;
    flex: none;
  }
  
  .action-card {
    min-width: 100%;
    justify-content: center;
  }
}

@media (max-width: 768px) {
  .upload-section {
    margin: 40px 0;
    padding: 0 20px;
  }
  
  .hero-text h1 {
    font-size: 28px;
  }
  
  .hero-text p {
    font-size: 16px;
  }
  
  .upload-box {
    width: 100%;
    height: 240px;
  }
  
  .processing-section {
    padding: 40px 20px;
    margin: 40px 0;
  }
  
  .scan-progress {
    width: 100%;
  }
  
  .scan-metrics {
    flex-direction: column;
    gap: 16px;
  }
  
  .task-controls {
    flex-direction: column;
    align-items: center;
  }
  
  .task-controls .el-button {
    width: 200px;
  }
  
  .kpi-card {
    padding: 20px;
  }
  
  .ai-content {
    padding: 20px;
  }
  
  .ai-avatar {
    width: 48px;
    height: 48px;
    font-size: 24px;
  }
}

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}

::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 4px;
  transition: background 0.3s ease;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.3);
}
</style>