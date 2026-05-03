<template>
  <div class="alarm-history-analysis">
    <el-card class="analysis-card">
      <template #header>
        <div class="card-header">
          <span>告警历史分析</span>
          <div class="card-header-actions">
            <el-button type="success" plain size="small" @click="goToOpsAgentFromAlarmPage">
              打开工作台对话
            </el-button>
            <el-button type="primary" @click="refreshData">刷新数据</el-button>
          </div>
        </div>
      </template>

      <!-- 筛选条件 -->
      <div class="filter-row">
        <el-form :inline="true" :model="filterForm" class="filter-form">
          <el-form-item label="告警级别">
            <el-select v-model="filterForm.level" placeholder="请选择告警级别">
              <el-option label="全部" value=""></el-option>
              <el-option label="INFO" value="INFO"></el-option>
              <el-option label="WARNING" value="WARNING"></el-option>
              <el-option label="ERROR" value="ERROR"></el-option>
              <el-option label="FATAL" value="FATAL"></el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="任务ID">
            <el-input v-model="filterForm.taskId" placeholder="请输入任务ID" style="width: 200px;"></el-input>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="applyFilters">查询</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 统计卡片 -->
      <div class="stats-row">
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">总告警数</div>
            <div class="stat-value">{{ alarmStatistics.totalAlarms || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">成功推送数</div>
            <div class="stat-value">{{ alarmStatistics.successAlarms || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">失败推送数</div>
            <div class="stat-value">{{ alarmStatistics.failedAlarms || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">推送成功率</div>
            <div class="stat-value">{{ (alarmStatistics.successRate || 0).toFixed(2) }}%</div>
          </div>
        </el-card>
      </div>

      <!-- 图表区域 -->
      <div class="charts-row">
        <!-- 告警趋势 -->
        <el-card class="chart-card">
          <template #header>
            <span>告警趋势</span>
          </template>
          <div ref="trendChartRef" class="chart-container"></div>
        </el-card>

        <!-- 告警级别分布 -->
        <el-card class="chart-card">
          <template #header>
            <span>告警级别分布</span>
          </template>
          <div ref="levelChartRef" class="chart-container"></div>
        </el-card>
      </div>

      <div class="charts-row">
        <!-- 告警根因分析 -->
        <el-card class="chart-card full-width">
          <template #header>
            <span>告警根因分析</span>
          </template>
          <div ref="rootCauseChartRef" class="chart-container"></div>
        </el-card>
      </div>

      <!-- 告警历史列表 -->
      <el-card class="table-card">
        <template #header>
          <span>告警历史列表</span>
        </template>
        <el-table :data="alarmHistory.list" style="width: 100%">
          <el-table-column prop="alarmId" label="告警ID" width="200">
            <template #default="scope">
              <span class="alarm-id">{{ scope.row.alarmId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="taskId" label="任务ID" width="200">
            <template #default="scope">
              <span class="task-id">{{ scope.row.taskId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="level" label="告警级别" width="100">
            <template #default="scope">
              <el-tag :type="getLevelType(scope.row.level)">
                {{ scope.row.level }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rootCause" label="根因分析" show-overflow-tooltip></el-table-column>
          <el-table-column prop="pushStatus" label="推送状态" width="120">
            <template #default="scope">
              <el-tag :type="getStatusType(scope.row.pushStatus)">
                {{ scope.row.pushStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="180"></el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="scope">
              <el-button size="small" type="primary" @click="viewAlarmDetail(scope.row.alarmId)">
                查看
              </el-button>
              <el-button
                v-if="scope.row.level === 'ERROR' || scope.row.level === 'FATAL'"
                size="small"
                type="warning"
                plain
                @click="opsAgentFromAlarmRow(scope.row)"
              >
                协助排查
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-row">
          <el-pagination
            v-model:current-page="pagination.pageNum"
            v-model:page-size="pagination.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="alarmHistory.total"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, defineEmits } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import {
  getAlarmHistory,
  getAlarmStatistics,
  getAlarmTrend,
  getAlarmLevelDistribution,
  getAlarmRootCauseStatistics,
  processAlarmsByTaskId,
  getHistory
} from '../api/index.js'
import { dispatchOpsAgentPrefill } from '../utils/opsAgentNavigate'

// 定义事件
const emit = defineEmits(['view-task'])

// 响应式数据
const filterForm = ref({
  level: '',
  taskId: ''
})

const pagination = ref({
  pageNum: 1,
  pageSize: 10
})

const alarmHistory = ref({
  list: [],
  total: 0
})

const alarmStatistics = ref({
  totalAlarms: 0,
  successAlarms: 0,
  failedAlarms: 0,
  successRate: 0
})

const trendChartRef = ref(null)
const levelChartRef = ref(null)
const rootCauseChartRef = ref(null)

// 图表实例
let trendChart = null
let levelChart = null
let rootCauseChart = null

// 初始化图表
const initCharts = () => {
  // 告警趋势图表
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

  // 告警级别分布图表
  if (levelChartRef.value) {
    levelChart = echarts.init(levelChartRef.value)
    levelChart.setOption({
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

  // 告警根因分析图表
  if (rootCauseChartRef.value) {
    rootCauseChart = echarts.init(rootCauseChartRef.value)
    rootCauseChart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        }
      },
      xAxis: {
        type: 'category',
        data: [],
        axisLabel: {
          rotate: 45
        }
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
}

// 刷新数据（图表与统计卡片与当前筛选条件口径一致，由后端聚合）
const refreshData = async () => {
  try {
    const level = filterForm.value.level || ''
    const taskId = filterForm.value.taskId || ''

    let statisticsData = await getAlarmStatistics(7, level, taskId)
    alarmStatistics.value = {
      totalAlarms: statisticsData.totalAlarms ?? 0,
      successAlarms: statisticsData.successAlarms ?? 0,
      failedAlarms: statisticsData.failedAlarms ?? 0,
      successRate: statisticsData.successRate ?? 0
    }

    // 无筛选且尚无告警时：尝试按最近完成任务回填告警记录
    if (!level && !taskId && (alarmStatistics.value.totalAlarms || 0) === 0) {
      await backfillGlobalAlarmHistory()
      statisticsData = await getAlarmStatistics(7, level, taskId)
      alarmStatistics.value = {
        totalAlarms: statisticsData.totalAlarms ?? 0,
        successAlarms: statisticsData.successAlarms ?? 0,
        failedAlarms: statisticsData.failedAlarms ?? 0,
        successRate: statisticsData.successRate ?? 0
      }
    }

    await searchAlarmHistory()

    const trendData = await getAlarmTrend(7, level, taskId)
    updateTrendChart(trendData)

    const levelData = await getAlarmLevelDistribution(7, level, taskId)
    updateLevelChart(levelData)

    const rootCauseData = await getAlarmRootCauseStatistics(7, level, taskId)
    updateRootCauseChart(rootCauseData)
  } catch (error) {
    console.error('获取告警历史数据失败:', error)
    ElMessage.error('刷新告警历史分析失败')
  }
}

const backfillGlobalAlarmHistory = async () => {
  try {
    const history = await getHistory(1, 20, { status: 'COMPLETED' })
    const tasks = history?.records || history?.list || []
    if (!tasks.length) return

    const taskIds = tasks.map((t) => t.taskId).filter(Boolean)
    for (const taskId of taskIds) {
      await processAlarmsByTaskId(taskId)
    }
    ElMessage.info(`已触发最近 ${taskIds.length} 个任务的告警回填`)
  } catch (error) {
    console.error('全局告警回填失败:', error)
  }
}

// 查询告警历史
const searchAlarmHistory = async () => {
  try {
    const historyData = await getAlarmHistory(
      pagination.value.pageNum,
      pagination.value.pageSize,
      filterForm.value.level,
      filterForm.value.taskId
    )
    const list = historyData?.list || historyData?.records || historyData?.content || []
    const total = historyData?.total || historyData?.totalElements || list.length
    alarmHistory.value = {
      list: list.map((item) => ({
        alarmId: item.alarmId || item.id || '',
        taskId: item.taskId || '',
        level: item.level || item.alarmLevel || '',
        rootCause: item.rootCause || item.reason || '',
        pushStatus: item.pushStatus || item.status || '',
        createTime: item.createTime || item.create_time || item.timestamp || ''
      })),
      total
    }

    // 任务级兜底：若指定 taskId 且暂无告警，触发后端处理后再拉取一次
    if (filterForm.value.taskId && alarmHistory.value.total === 0) {
      await processAlarmsByTaskId(filterForm.value.taskId)
      const retryData = await getAlarmHistory(
        pagination.value.pageNum,
        pagination.value.pageSize,
        filterForm.value.level,
        filterForm.value.taskId
      )
      const retryList = retryData?.list || retryData?.records || retryData?.content || []
      const retryTotal = retryData?.total || retryData?.totalElements || retryList.length
      alarmHistory.value = {
        list: retryList.map((item) => ({
          alarmId: item.alarmId || item.id || '',
          taskId: item.taskId || '',
          level: item.level || item.alarmLevel || '',
          rootCause: item.rootCause || item.reason || '',
          pushStatus: item.pushStatus || item.status || '',
          createTime: item.createTime || item.create_time || item.timestamp || ''
        })),
        total: retryTotal
      }
      if (retryTotal > 0) {
        ElMessage.success('已按任务触发告警处理并刷新历史')
      }
    }
  } catch (error) {
    console.error('查询告警历史失败:', error)
    ElMessage.error('查询告警历史失败')
  }
}

const applyFilters = async () => {
  pagination.value.pageNum = 1
  await refreshData()
}

// 重置筛选条件
const resetFilter = () => {
  filterForm.value = {
    level: '',
    taskId: ''
  }
  refreshData()
}

// 更新趋势图表
const updateTrendChart = (trendData) => {
  if (trendChart && trendData) {
    const dates = trendData.map(item => item.date || item.day || '')
    const counts = trendData.map(item => item.count || item.total || 0)

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

// 更新级别分布图表
const updateLevelChart = (levelData) => {
  if (levelChart && levelData) {
    const data = levelData.map(item => ({
      name: item.level || item.name || 'UNKNOWN',
      value: item.count || item.value || 0
    }))

    levelChart.setOption({
      series: [{
        data: data
      }]
    })
  }
}

// 更新根因分析图表
const updateRootCauseChart = (rootCauseData) => {
  if (rootCauseChart && rootCauseData) {
    const rootCauses = rootCauseData.map(item => item.root_cause || item.rootCause || item.name || '')
    const counts = rootCauseData.map(item => item.count || item.value || 0)

    rootCauseChart.setOption({
      xAxis: {
        data: rootCauses
      },
      series: [{
        data: counts
      }]
    })
  }
}

// 处理分页大小变化
const handleSizeChange = (size) => {
  pagination.value.pageSize = size
  refreshData()
}

// 处理当前页码变化
const handleCurrentChange = (current) => {
  pagination.value.pageNum = current
  refreshData()
}

// 获取告警级别类型
const getLevelType = (level) => {
  const levelMap = {
    'INFO': 'info',
    'WARNING': 'warning',
    'ERROR': 'danger',
    'FATAL': 'danger'
  }
  return levelMap[level] || 'info'
}

// 获取推送状态类型
const getStatusType = (status) => {
  const s = String(status || '').toUpperCase()
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'PENDING') return 'info'
  if (s === 'RETRYING') return 'warning'
  if (s === 'AGGREGATED') return 'info'
  if (s.startsWith('SKIPPED')) return 'info'
  return 'info'
}

const goToOpsAgentFromAlarmPage = () => {
  dispatchOpsAgentPrefill(
    '【告警历史】结合当前页的告警统计与趋势，请协助复核：对应时段 journalctl、磁盘热点、/tmp dry-run，并说明如何与告警级别联动处置。'
  )
}

const opsAgentFromAlarmRow = (row) => {
  const rc = row.rootCause ? String(row.rootCause).slice(0, 500) : '（无根因摘要）'
  const lines = [
    '【告警结构化上下文】',
    `alarmId=${row.alarmId}`,
    `taskId=${row.taskId}`,
    `level=${row.level}`,
    `pushStatus=${row.pushStatus}`,
    `createTime=${row.createTime}`,
    `rootCause=${rc}`,
    '请结合上述字段做 journalctl/磁盘/tmp 等关联排查，并给出与告警级别匹配的处置建议。'
  ]
  dispatchOpsAgentPrefill(lines.join('\n'))
}

// 查看告警详情
const viewAlarmDetail = (alarmId) => {
  // 触发查看任务事件，跳转到日志分析页面查看详情
  console.log('查看告警详情:', alarmId)
  // 假设告警ID中包含任务ID信息，这里简化处理，直接传递alarmId
  emit('view-task', alarmId)
}

// 响应式调整图表大小
const handleResize = () => {
  trendChart?.resize()
  levelChart?.resize()
  rootCauseChart?.resize()
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
  levelChart?.dispose()
  rootCauseChart?.dispose()
})
</script>

<style scoped>
.alarm-history-analysis {
  padding: 20px;
}

.analysis-card {
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

.filter-row {
  margin-bottom: 20px;
}

.filter-form {
  width: 100%;
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
  font-size: 14px;
  color: #606266;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
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

.chart-card.full-width {
  flex: 1 1 100%;
}

.chart-container {
  width: 100%;
  height: 280px;
}

.table-card {
  margin-top: 20px;
}

.pagination-row {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

.alarm-id,
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
