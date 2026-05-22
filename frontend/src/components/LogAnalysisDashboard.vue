<template>
  <div class="ops-page log-analysis-dashboard">
    <el-card class="dashboard-card">
      <template #header>
        <div class="card-header">
          <span>日志分析仪表盘</span>
          <div class="card-header-actions">
            <el-button type="success" plain size="small" @click="goToOpsAgent">
              打开工作台对话
            </el-button>
            <el-button type="primary" @click="refreshData">刷新数据</el-button>
          </div>
        </div>
      </template>

      <!-- 统计卡片 -->
      <div class="stats-row">
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总日志数</div>
            <div class="stat-value">{{ logSummary.totalLogs || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">异常日志数</div>
            <div class="stat-value">{{ logSummary.anomalyLogs || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">异常率</div>
            <div class="stat-value">{{ (logSummary.anomalyRate || 0).toFixed(2) }}%</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总任务数</div>
            <div class="stat-value">{{ logSummary.totalTasks || 0 }}</div>
          </div>
        </el-card>
      </div>

      <!-- 图表区域 -->
      <div class="charts-row">
        <!-- 日志分析趋势 -->
        <el-card class="chart-card">
          <template #header>
            <span>日志分析趋势</span>
          </template>
          <div ref="trendChartRef" class="chart-container"></div>
        </el-card>

        <!-- 异常类型分布 -->
        <el-card class="chart-card">
          <template #header>
            <span>异常类型分布</span>
          </template>
          <div ref="distributionChartRef" class="chart-container"></div>
        </el-card>
      </div>

      <div class="charts-row">
        <!-- 任务状态统计 -->
        <el-card class="chart-card">
          <template #header>
            <span>任务状态统计</span>
          </template>
          <div ref="taskStatusChartRef" class="chart-container"></div>
        </el-card>

        <!-- 系统性能 -->
        <el-card class="chart-card">
          <template #header>
            <span>系统性能</span>
          </template>
          <div ref="performanceChartRef" class="chart-container"></div>
        </el-card>
      </div>

      <!-- 最近分析任务 -->
      <el-card class="table-card">
        <template #header>
          <span>最近分析任务</span>
        </template>
        <el-table :data="recentTasks" style="width: 100%">
          <el-table-column prop="taskId" label="任务ID" width="200">
            <template #default="scope">
              <span class="task-id">{{ scope.row.taskId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件名"></el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="scope">
              <el-tag :type="getStatusType(scope.row.status)">
                {{ scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="progress" label="进度" width="120">
            <template #default="scope">
              <el-progress :percentage="scope.row.progress" :stroke-width="10"></el-progress>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="180"></el-table-column>
          <el-table-column label="操作" width="280">
            <template #default="scope">
              <el-button size="small" type="primary" @click="viewTask(scope.row.taskId)">
                查看详情
              </el-button>
              <el-button
                v-if="scope.row.status === 'FAILED'"
                size="small"
                type="warning"
                plain
                @click="opsAgentForFailedTask(scope.row)"
              >
                对话复核
              </el-button>
              <el-button
                v-if="shouldOfferAgentDeepDive(scope.row)"
                size="small"
                type="success"
                plain
                @click="opsAgentDeepDiveTask(scope.row)"
              >
                对话深挖
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, defineEmits } from 'vue'
import * as echarts from 'echarts'
import { getLogSummary, getTaskStatusStatistics, getPerformance, getHistory } from '../api/index.js'
import {
  dispatchOpsAgentPrefill,
  shouldOfferAgentDeepDive,
  agentPrefillHighAnomaly,
  agentPrefillFailedTask
} from '../utils/opsAgentNavigate'

// 定义事件
const emit = defineEmits(['view-task'])

// 响应式数据
const logSummary = ref({})
const recentTasks = ref([])
const trendChartRef = ref(null)
const distributionChartRef = ref(null)
const taskStatusChartRef = ref(null)
const performanceChartRef = ref(null)

// 图表实例
let trendChart = null
let distributionChart = null
let taskStatusChart = null
let performanceChart = null

// 初始化图表
const initCharts = () => {
  // 日志分析趋势图表
  if (trendChartRef.value) {
    trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: {
        trigger: 'axis'
      },
      xAxis: {
        type: 'category',
        data: []
      },
      yAxis: {
        type: 'value'
      },
      series: [{
        data: [],
        type: 'line',
        smooth: true
      }]
    })
  }

  // 异常类型分布图表
  if (distributionChartRef.value) {
    distributionChart = echarts.init(distributionChartRef.value)
    distributionChart.setOption({
      tooltip: {
        trigger: 'item'
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      series: [{
        type: 'pie',
        radius: '60%',
        data: [],
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }]
    })
  }

  // 任务状态统计图表
  if (taskStatusChartRef.value) {
    taskStatusChart = echarts.init(taskStatusChartRef.value)
    taskStatusChart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        }
      },
      xAxis: {
        type: 'category',
        data: []
      },
      yAxis: {
        type: 'value'
      },
      series: [{
        data: [],
        type: 'bar'
      }]
    })
  }

  // 系统性能图表
  if (performanceChartRef.value) {
    performanceChart = echarts.init(performanceChartRef.value)
    performanceChart.setOption({
      tooltip: {
        trigger: 'axis'
      },
      legend: {
        data: ['CPU使用率', '内存使用率', '磁盘使用率', '网络使用率']
      },
      xAxis: {
        type: 'category',
        data: ['性能指标']
      },
      yAxis: {
        type: 'value',
        max: 100
      },
      series: [
        {
          name: 'CPU使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '内存使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '磁盘使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '网络使用率',
          type: 'bar',
          data: [0]
        }
      ]
    })
  }
}

// 刷新数据
const refreshData = async () => {
  try {
    // 获取日志汇总
    const summaryData = await getLogSummary(7)
    logSummary.value = summaryData

    // 获取任务状态统计
    const taskStatusData = await getTaskStatusStatistics()
    updateTaskStatusChart(taskStatusData)

    // 获取系统性能
    const performanceData = await getPerformance()
    updatePerformanceChart(performanceData)

    // 更新趋势图表
    updateTrendChart(summaryData.taskTrend)

    // 更新分布图表
    updateDistributionChart(summaryData.anomalyDistribution)

    const historyData = await getHistory(1, 10)
    recentTasks.value = historyData.records || historyData.list || []
  } catch (error) {
    console.error('获取数据失败:', error)
  }
}

// 更新趋势图表
const updateTrendChart = (taskTrend) => {
  if (trendChart && taskTrend) {
    const dates = taskTrend.map(item => item.date)
    const counts = taskTrend.map(item => item.count)

    trendChart.setOption({
      xAxis: {
        data: dates
      },
      series: [{
        data: counts,
        type: 'line',
        smooth: true,
        areaStyle: {}
      }]
    })
  }
}

// 更新分布图表
const updateDistributionChart = (anomalyDistribution) => {
  if (!distributionChart || !Array.isArray(anomalyDistribution)) return
  const data = anomalyDistribution.map(item => ({
    name: item.type ?? item.name ?? 'UNKNOWN',
    value: Number(item.count ?? item.value ?? 0)
  }))

  distributionChart.setOption({
    series: [{
      data: data
    }]
  })
}

// 更新任务状态图表
const updateTaskStatusChart = (taskStatusData) => {
  if (!taskStatusChart || !taskStatusData || !taskStatusData.statusCount) {
    return
  }
  const statuses = Object.keys(taskStatusData.statusCount)
  const counts = statuses.map(status => taskStatusData.statusCount[status])

  taskStatusChart.setOption({
    xAxis: {
      data: statuses
    },
    series: [{
      data: counts
    }]
  })
}

// 更新性能图表
const updatePerformanceChart = (performanceData) => {
  if (performanceChart && performanceData) {
    performanceChart.setOption({
      series: [
        {
          name: 'CPU使用率',
          data: [performanceData.cpuUsage || 0]
        },
        {
          name: '内存使用率',
          data: [performanceData.memoryUsage || 0]
        },
        {
          name: '磁盘使用率',
          data: [performanceData.diskUsage || 0]
        },
        {
          name: '网络使用率',
          data: [performanceData.networkUsage || 0]
        }
      ]
    })
  }
}

// 获取状态类型
const getStatusType = (status) => {
  const statusMap = {
    'COMPLETED': 'success',
    'PROCESSING': 'warning',
    'FAILED': 'danger',
    'PENDING': 'info'
  }
  return statusMap[status] || 'info'
}

const goToOpsAgent = () => {
  window.dispatchEvent(
    new CustomEvent('ops-navigate-agent', {
      detail: {
        message: '结合仪表盘异常趋势，分析最近系统日志并扫描 /tmp 与 /var/log 的磁盘占用热点（预览清理策略）'
      }
    })
  )
}

const opsAgentForFailedTask = (row) => {
  dispatchOpsAgentPrefill(`【仪表盘】${agentPrefillFailedTask(row)}`)
}

const opsAgentDeepDiveTask = (row) => {
  dispatchOpsAgentPrefill(`【仪表盘】${agentPrefillHighAnomaly(row)}`)
}

// 查看任务详情
const viewTask = (taskId) => {
  // 触发查看任务事件，跳转到日志分析页面查看详情
  console.log('查看任务详情:', taskId)
  emit('view-task', taskId)
}

// 响应式调整图表大小
const handleResize = () => {
  trendChart?.resize()
  distributionChart?.resize()
  taskStatusChart?.resize()
  performanceChart?.resize()
}

// 生命周期钩子
onMounted(() => {
  initCharts()
  refreshData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  distributionChart?.dispose()
  taskStatusChart?.dispose()
  performanceChart?.dispose()
})
</script>

<style scoped>
.log-analysis-dashboard {
  padding: 0;
}

.dashboard-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stats-row {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

.stat-card {
  flex: 1;
  min-width: 150px;
}

.stat-item {
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: var(--ops-text-subtle);
  margin-bottom: 6px;
  font-weight: 500;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--ops-text);
  font-variant-numeric: tabular-nums;
}

.charts-row {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

.chart-card {
  flex: 1;
  min-height: 300px;
}

.chart-container {
  width: 100%;
  height: 280px;
}

.table-card {
  margin-top: 20px;
}

.task-id {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}

@media (max-width: 1200px) {
  .stats-row {
    flex-wrap: wrap;
  }

  .stat-card {
    flex: 1 1 calc(50% - 10px);
  }

  .charts-row {
    flex-direction: column;
  }
}
</style>
