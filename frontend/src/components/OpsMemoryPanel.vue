<template>
  <div class="ops-memory-panel">
    <div class="memory-panel-head">
      <div class="memory-panel-title">
        <span>Agent 记忆层</span>
        <el-tag v-if="awmEnabled" size="small" type="success" effect="plain">AWM 开</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">AWM 关</el-tag>
        <el-tag v-if="reflexionEnabled" size="small" type="warning" effect="plain">Reflexion 开</el-tag>
      </div>
      <div class="memory-panel-actions">
        <el-select v-model="domain" size="small" style="width: 110px" @change="loadWorkflows">
          <el-option label="全部域" value="all" />
          <el-option label="磁盘 disk" value="disk" />
          <el-option label="CPU cpu" value="cpu" />
          <el-option label="服务 service" value="service" />
        </el-select>
        <el-button size="small" :loading="loadingWorkflows" @click="loadWorkflows">刷新套路</el-button>
        <el-button size="small" type="primary" plain :loading="inducing" @click="onInduce">
          离线诱导
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="memory-tabs">
      <el-tab-pane name="awm">
        <template #label>
          处置套路
          <el-badge v-if="workflowCount" :value="workflowCount" class="tab-badge" />
        </template>
        <div v-loading="loadingWorkflows" class="memory-tab-body">
          <el-empty v-if="!workflows.length && !loadingWorkflows" description="暂无 workflow，启动后会自动 seed" />
          <el-collapse v-else accordion>
            <el-collapse-item
              v-for="wf in workflows"
              :key="wf.workflowId"
              :name="wf.workflowId"
            >
              <template #title>
                <div class="wf-title-row">
                  <el-tag size="small" effect="plain">{{ wf.domain }}</el-tag>
                  <span class="wf-title">{{ wf.title }}</span>
                  <span class="wf-id">{{ wf.workflowId }}</span>
                  <el-tag v-if="wf.utilityCount" size="small" type="info">命中 {{ wf.utilityCount }}</el-tag>
                </div>
              </template>
              <p v-if="wf.description" class="wf-desc">{{ wf.description }}</p>
              <ol v-if="wf.steps?.length" class="wf-steps">
                <li v-for="(step, idx) in wf.steps" :key="idx">
                  <span class="wf-env">[{{ step.envDesc || 'env' }}]</span>
                  {{ step.reason || '步骤' }}
                  <code v-if="step.toolName">{{ step.toolName }}</code>
                </li>
              </ol>
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-tab-pane>

      <el-tab-pane name="reflexion">
        <template #label>
          安全教训
          <el-badge v-if="lessonCount" :value="lessonCount" class="tab-badge" />
        </template>
        <div v-loading="loadingLessons" class="memory-tab-body">
          <p v-if="reflexionNote" class="reflexion-note">{{ reflexionNote }}</p>
          <el-empty v-if="!lessons.length && !loadingLessons" description="暂无教训；在 MCP 触发一次安全拦截后会沉淀" />
          <div v-else class="lesson-list">
            <el-card
              v-for="(lesson, idx) in lessons"
              :key="idx"
              shadow="never"
              class="lesson-card"
              body-style="padding: 10px 12px"
            >
              <div class="lesson-head">
                <el-tag size="small" type="danger" effect="plain">{{ lesson.securityCode }}</el-tag>
                <span v-if="lesson.toolName" class="lesson-tool">{{ lesson.toolName }}</span>
                <span v-if="lesson.hitCount > 1" class="lesson-hits">×{{ lesson.hitCount }}</span>
              </div>
              <p class="lesson-reflection">{{ lesson.reflection }}</p>
              <p v-if="lesson.intentHint" class="lesson-hint">意图片段：{{ lesson.intentHint }}</p>
            </el-card>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getOpsWorkflowMemory,
  getOpsFailureInsights,
  induceOpsWorkflowFromAudit
} from '../api'

const activeTab = ref('awm')
const domain = ref('all')
const workflows = ref([])
const lessons = ref([])
const awmEnabled = ref(true)
const reflexionEnabled = ref(true)
const reflexionNote = ref('')
const loadingWorkflows = ref(false)
const loadingLessons = ref(false)
const inducing = ref(false)

const workflowCount = computed(() => workflows.value.length)
const lessonCount = computed(() => lessons.value.length)

const loadWorkflows = async () => {
  loadingWorkflows.value = true
  try {
    const data = await getOpsWorkflowMemory(domain.value)
    awmEnabled.value = data?.enabled !== false
    workflows.value = Array.isArray(data?.workflows) ? data.workflows : []
  } catch (e) {
    ElMessage.error(e.message || '加载 workflow 失败')
    workflows.value = []
  } finally {
    loadingWorkflows.value = false
  }
}

const loadLessons = async () => {
  loadingLessons.value = true
  try {
    const data = await getOpsFailureInsights('', 12)
    reflexionEnabled.value = data?.enabled !== false
    reflexionNote.value = data?.note || ''
    lessons.value = Array.isArray(data?.lessons) ? data.lessons : []
  } catch (e) {
    ElMessage.error(e.message || '加载教训失败')
    lessons.value = []
  } finally {
    loadingLessons.value = false
  }
}

const onInduce = async () => {
  inducing.value = true
  try {
    const data = await induceOpsWorkflowFromAudit(20)
    ElMessage.success(`离线诱导完成，新增 ${data?.created ?? 0} 条，库内共 ${data?.storedCount ?? 0} 条`)
    await loadWorkflows()
  } catch (e) {
    ElMessage.error(e.message || '诱导失败')
  } finally {
    inducing.value = false
  }
}

const refreshAll = async () => {
  await Promise.all([loadWorkflows(), loadLessons()])
}

onMounted(refreshAll)

defineExpose({ refreshAll })
</script>

<style scoped>
.ops-memory-panel {
  margin-bottom: 14px;
  padding: 12px 14px;
  background: var(--ops-panel-soft, #f8fafc);
  border: 1px solid var(--ops-border-soft, #e4e7ed);
  border-radius: var(--ops-radius-sm, 8px);
}
.memory-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.memory-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
}
.memory-panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.memory-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}
.tab-badge {
  margin-left: 6px;
}
.memory-tab-body {
  min-height: 80px;
  max-height: 220px;
  overflow-y: auto;
}
.wf-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
  padding-right: 12px;
}
.wf-title {
  font-weight: 500;
}
.wf-id {
  color: #909399;
  font-size: 12px;
  font-family: ui-monospace, monospace;
}
.wf-desc {
  margin: 0 0 8px;
  color: #606266;
  font-size: 13px;
}
.wf-steps {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
}
.wf-env {
  color: #409eff;
}
.wf-steps code {
  margin-left: 4px;
  padding: 0 4px;
  background: #f0f2f5;
  border-radius: 3px;
  font-size: 12px;
}
.reflexion-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: #909399;
}
.lesson-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lesson-card {
  border: 1px solid #fde2e2;
  background: #fff;
}
.lesson-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.lesson-tool {
  font-size: 12px;
  color: #606266;
}
.lesson-hits {
  font-size: 12px;
  color: #909399;
}
.lesson-reflection {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}
.lesson-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
