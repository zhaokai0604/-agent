<template>
  <div class="agent-skills-page">
    <div class="skills-header agent-chat-column">
      <h1>运维工具与诊断</h1>
      <p>系统工具在本机受控执行；诊断分析会调用模型服务生成报告，需配置模型服务。</p>
    </div>

    <el-tabs v-model="activeTab" class="skills-tabs agent-chat-column">
      <el-tab-pane label="系统工具" name="mcp">
        <el-alert
          v-if="!registryConnected"
          type="warning"
          :closable="false"
          show-icon
          class="conn-alert"
          title="未连接运维服务"
          description="请确认后端已启动并已登录。"
        />

        <div v-if="registryConnected" class="ops-stat-row">
          <div class="ops-stat-pill">
            <span class="ops-stat-pill__label">已注册</span>
            <span class="ops-stat-pill__value">{{ tools.length }}</span>
          </div>
          <div class="ops-stat-pill ops-stat-pill--ok">
            <span class="ops-stat-pill__label">在线</span>
            <span class="ops-stat-pill__value">{{ onlineCount }}</span>
          </div>
          <div class="ops-stat-pill">
            <span class="ops-stat-pill__label">离线</span>
            <span class="ops-stat-pill__value">{{ offlineCount }}</span>
          </div>
        </div>

        <div class="skills-toolbar">
          <el-input
            v-model="searchQuery"
            clearable
            placeholder="搜索工具名称或说明…"
            :prefix-icon="Search"
            class="skills-search"
          />
          <el-radio-group v-model="statusFilter" size="small">
            <el-radio-button value="all">全部 {{ tools.length }}</el-radio-button>
            <el-radio-button value="online">在线 {{ onlineCount }}</el-radio-button>
            <el-radio-button value="offline">离线 {{ offlineCount }}</el-radio-button>
          </el-radio-group>
        </div>

        <div v-loading="loading" class="skills-grid">
          <article
            v-for="tool in filteredTools"
            :key="tool.name"
            class="skill-card"
            :class="{ offline: tool.status !== 'online' }"
          >
            <div class="skill-badge">{{ mcpToolBadge(tool.name) }}</div>
            <h3>{{ mcpToolDisplayName(tool.name) }}</h3>
            <p class="skill-cmd">{{ getMcpToolCommandHint(tool.name) }}</p>
            <p class="skill-desc">{{ tool.description }}</p>
            <div class="skill-meta">
              <span class="status-dot" :class="tool.status" />
              {{ tool.statusText }}
            </div>
            <div class="skill-actions">
              <el-button
                size="small"
                type="primary"
                :disabled="tool.status !== 'online' || executing === tool.name"
                :loading="executing === tool.name"
                @click="executeNow(tool)"
              >
                立即执行
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :disabled="tool.status !== 'online'"
                @click="useInChat(tool)"
              >
                交给助手处理
              </el-button>
            </div>
          </article>
          <el-empty v-if="!loading && filteredTools.length === 0" description="没有匹配的工具" class="skills-empty" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="诊断分析" name="analysis">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="conn-alert"
          title="模型分析模板"
          description="此处会调用模型服务生成诊断报告，不执行主机命令；结果取决于输入内容与模型服务状态。"
        />

        <div class="skills-toolbar">
          <el-input
            v-model="analysisSearch"
            clearable
            placeholder="搜索诊断分析…"
            :prefix-icon="Search"
            class="skills-search"
          />
          <el-radio-group v-model="analysisCategory" size="small">
            <el-radio-button value="all">全部 {{ analysisSkills.length }}</el-radio-button>
            <el-radio-button v-for="cat in analysisCategories" :key="cat" :value="cat">
              {{ cat }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <div v-loading="analysisLoading" class="skills-grid analysis-grid">
          <article
            v-for="skill in filteredAnalysisSkills"
            :key="skill.id"
            class="skill-card analysis-card"
          >
            <div class="analysis-card__head">
              <span class="analysis-category">{{ skill.category }}</span>
              <span class="analysis-kind">模型分析</span>
            </div>
            <h3>{{ skill.name }}</h3>
            <p class="skill-desc">{{ skill.description }}</p>
            <ul v-if="skill.outputSections?.length" class="output-sections">
              <li v-for="section in skill.outputSections" :key="section">{{ section }}</li>
            </ul>

            <el-form label-position="top" class="analysis-form" @submit.prevent>
              <el-form-item
                v-for="param in skill.params"
                :key="param.key"
                :label="param.label"
                :required="param.required"
              >
                <el-input
                  v-if="param.type === 'text'"
                  v-model="analysisForms[skill.id][param.key]"
                  :placeholder="param.placeholder"
                  clearable
                />
                <el-input-number
                  v-else-if="param.type === 'number'"
                  v-model="analysisForms[skill.id][param.key]"
                  :controls="false"
                  class="analysis-number"
                />
                <el-select
                  v-else-if="param.type === 'select'"
                  v-model="analysisForms[skill.id][param.key]"
                  :placeholder="param.placeholder || '请选择'"
                  style="width: 100%"
                >
                  <el-option
                    v-for="opt in param.options || []"
                    :key="opt"
                    :label="opt"
                    :value="opt"
                  />
                </el-select>
                <el-input
                  v-else
                  v-model="analysisForms[skill.id][param.key]"
                  type="textarea"
                  :rows="4"
                  :placeholder="param.placeholder"
                />
              </el-form-item>
            </el-form>

            <div class="skill-actions">
              <el-button
                size="small"
                type="primary"
                :loading="analyzing === skill.id"
                @click="invokeAnalysis(skill)"
              >
                立即分析
              </el-button>
              <el-button size="small" plain @click="useAnalysisInChat(skill)">
                交给助手处理
              </el-button>
            </div>
          </article>
          <el-empty
            v-if="!analysisLoading && filteredAnalysisSkills.length === 0"
            description="没有匹配的分析技能"
            class="skills-empty"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="YAML SOP" name="yaml">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="conn-alert"
          title="标准运维流程"
          description="展示当前已发布的运维流程；每次运行固定使用当时的版本。管理员可预览部署版本、发布或回退，执行仍经过受控管线。"
        />

        <el-card v-if="yamlCatalog.durable" shadow="never" class="release-panel">
          <p>当前发布代次：{{ yamlCatalog.generation }} · 目录指纹：{{ yamlCatalog.catalogFingerprint?.slice(0, 12) }}</p>
          <template v-if="canManageReleases">
            <el-input v-model="releaseReason" maxlength="500" show-word-limit placeholder="填写发布或回退原因" />
            <div class="skill-actions">
              <el-button :loading="releaseBusy" @click="previewDeployment">预览部署版本</el-button>
              <el-button type="primary" :disabled="!canPublishRelease || releaseBusy" @click="publishDeployment">发布已预览版本</el-button>
              <el-button type="warning" :disabled="!releaseReason.trim() || yamlCatalog.generation < 2 || releaseBusy" @click="rollbackDeployment">回退上一版本</el-button>
            </div>
            <div v-if="releasePreview">
              <p>契约与安全回归：{{ releasePreview.evaluation?.passed ? '通过' : '未通过，不能发布' }}，共 {{ releasePreview.evaluation?.checks }} 项检查。此评测不包含真实模型或业务效果。</p>
              <ul><li v-for="failure in releasePreview.evaluation?.failures || []" :key="failure">{{ failure }}</li></ul>
              <el-table :data="releasePreview.changes || []" size="small">
                <el-table-column prop="skill" label="Skill" /><el-table-column prop="before" label="当前版本" /><el-table-column prop="after" label="候选版本" />
              </el-table>
            </div>
            <el-collapse><el-collapse-item title="发布与回退记录" name="history">
              <el-table :data="releaseHistory" size="small">
                <el-table-column prop="generation" label="代次" width="70" /><el-table-column prop="action" label="动作" width="110" />
                <el-table-column prop="actor" label="操作者" width="100" /><el-table-column prop="reason" label="原因" />
              </el-table>
            </el-collapse-item></el-collapse>
          </template>
        </el-card>

        <div class="skills-toolbar">
          <el-input
            v-model="yamlSearch"
            clearable
            placeholder="搜索标准运维流程…"
            :prefix-icon="Search"
            class="skills-search"
          />
        </div>

        <div v-loading="yamlLoading" class="skills-grid yaml-grid">
          <article v-for="skill in filteredYamlSkills" :key="skill.id" class="skill-card yaml-card">
            <div class="yaml-card__head">
              <span class="analysis-category">{{ skill.playbook || 'general' }} · v{{ skill.version || '未声明' }}</span>
              <span class="yaml-signals">≥{{ skill.minIndependentSignals ?? 2 }} 信号</span>
            </div>
            <h3>{{ skill.id }}</h3>
            <p class="skill-desc">{{ skill.description }}</p>
            <p v-if="skill.tools?.length" class="yaml-tools">
              工具：{{ skill.tools.join('、') }}
            </p>
            <ul v-if="skill.stopConditions?.length" class="output-sections">
              <li v-for="cond in skill.stopConditions" :key="cond">停止：{{ cond }}</li>
            </ul>
            <ul v-if="skill.redLines?.length" class="yaml-redlines">
              <li v-for="line in skill.redLines" :key="line">{{ line }}</li>
            </ul>
            <div class="skill-actions">
              <el-button size="small" plain @click="useYamlInChat(skill)">交给助手处理</el-button>
            </div>
          </article>
          <el-empty
            v-if="!yamlLoading && filteredYamlSkills.length === 0"
            description="没有匹配的标准运维流程"
            class="skills-empty"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <ToolResultDialog ref="resultDialogRef" />

    <el-dialog
      v-model="analysisDialogVisible"
      width="780px"
      destroy-on-close
      class="analysis-result-dialog"
      :title="analysisResultTitle"
    >
      <div
        ref="analysisResultRef"
        class="analysis-result-body assistant-prose"
        v-html="analysisResultHtml"
      />
      <template #footer>
        <el-button @click="analysisDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 工具与技能页：MCP 系统工具 + 可主动调用的 AI 分析 Skill。
 */
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { formatApiError, getAnalysisSkills, getMcpTools, getPlatformInfo, getYamlSkills, invokeAnalysisSkill, previewSkillRelease, publishSkillRelease, rollbackSkillRelease, getSkillReleaseHistory } from '../../api'
import { mergeToolRegistry, mcpToolDisplayName, mcpToolBadge, getMcpToolCommandHint, defaultParamsForTool, coerceToolParams } from '../../utils/mcpToolsMeta'
import { runMcpToolExecute } from '../../utils/mcpToolExecute'
import { renderAssistantMarkdown, bindProseCopyButtons } from '../../utils/assistantMarkdown'
import ToolResultDialog from '../ToolResultDialog.vue'
import { canPublishSkillRelease, skillReleasePayload } from '../../utils/skillReleaseApproval'

const activeTab = ref('mcp')
const tools = ref([])
const loading = ref(false)
const registryConnected = ref(true)
const searchQuery = ref('')
const statusFilter = ref('all')
const executing = ref(null)
const resultDialogRef = ref(null)

const analysisSkills = ref([])
const analysisLoading = ref(false)
const analysisSearch = ref('')
const analysisCategory = ref('all')
const analysisForms = reactive({})
const analyzing = ref(null)
const analysisDialogVisible = ref(false)
const analysisResultTitle = ref('')
const analysisResultHtml = ref('')
const analysisResultRef = ref(null)

const yamlSkills = ref([])
const yamlLoading = ref(false)
const yamlSearch = ref('')
const yamlCatalog = ref({})
const canManageReleases = ref(false)
const releaseReason = ref('')
const releasePreview = ref(null)
const releaseHistory = ref([])
const releaseBusy = ref(false)
const canPublishRelease = computed(() => canPublishSkillRelease(releasePreview.value, yamlCatalog.value.catalogFingerprint, releaseReason.value))

async function previewDeployment() {
  releaseBusy.value = true
  releasePreview.value = null
  try { releasePreview.value = (await previewSkillRelease()).preview }
  catch (error) { ElMessage.error(error.message || '版本预览失败') }
  finally { releaseBusy.value = false }
}
async function publishDeployment() {
  releaseBusy.value = true
  try {
    await publishSkillRelease(skillReleasePayload(releasePreview.value, yamlCatalog.value.catalogFingerprint, releaseReason.value))
    releasePreview.value = null
    await loadYamlSkills()
    ElMessage.success('Skill 发布完成')
  } catch (error) { releasePreview.value = null; ElMessage.error(error.message || '发布失败，请重新预览') }
  finally { releaseBusy.value = false }
}
async function rollbackDeployment() {
  releaseBusy.value = true
  try {
    await rollbackSkillRelease({ expectedFingerprint: yamlCatalog.value.catalogFingerprint, reason: releaseReason.value.trim() })
    releasePreview.value = null
    await loadYamlSkills()
    ElMessage.success('Skill 已回退，重启后仍保留此版本')
  } catch (error) { ElMessage.error(error.message || '回退失败，请刷新后核对版本') }
  finally { releaseBusy.value = false }
}

const filteredYamlSkills = computed(() => {
  const q = yamlSearch.value.trim().toLowerCase()
  return yamlSkills.value.filter(skill => {
    if (!q) return true
    const hay = `${skill.id || ''} ${skill.description || ''} ${skill.playbook || ''} ${(skill.tools || []).join(' ')}`.toLowerCase()
    return hay.includes(q)
  })
})

const filteredTools = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return tools.value.filter(tool => {
    if (statusFilter.value === 'online' && tool.status !== 'online') return false
    if (statusFilter.value === 'offline' && tool.status === 'online') return false
    if (!q) return true
    const hay = `${tool.name} ${tool.description || ''} ${mcpToolDisplayName(tool.name)}`.toLowerCase()
    return hay.includes(q)
  })
})

const onlineCount = computed(() => tools.value.filter(t => t.status === 'online').length)
const offlineCount = computed(() => tools.value.filter(t => t.status !== 'online').length)

const analysisCategories = computed(() => {
  const set = new Set(analysisSkills.value.map(s => s.category).filter(Boolean))
  return [...set]
})

const filteredAnalysisSkills = computed(() => {
  const q = analysisSearch.value.trim().toLowerCase()
  return analysisSkills.value.filter(skill => {
    if (analysisCategory.value !== 'all' && skill.category !== analysisCategory.value) return false
    if (!q) return true
    const hay = `${skill.id} ${skill.name} ${skill.description || ''} ${skill.category || ''}`.toLowerCase()
    return hay.includes(q)
  })
})

function initAnalysisForm(skill) {
  if (analysisForms[skill.id]) return
  const form = {}
  for (const param of skill.params || []) {
    if (param.type === 'number') {
      form[param.key] = null
    } else if (param.type === 'select' && param.options?.length) {
      form[param.key] = param.options[0]
    } else {
      form[param.key] = ''
    }
  }
  analysisForms[skill.id] = form
}

function buildAnalysisPayload(skill) {
  const form = analysisForms[skill.id] || {}
  const payload = {}
  for (const param of skill.params || []) {
    const v = form[param.key]
    if (param.type === 'number') {
      if (v !== null && v !== undefined && v !== '') payload[param.key] = Number(v)
    } else if (v !== null && v !== undefined && String(v).trim() !== '') {
      payload[param.key] = String(v).trim()
    }
  }
  return payload
}

function validateAnalysisForm(skill) {
  for (const param of skill.params || []) {
    if (!param.required) continue
    const v = analysisForms[skill.id]?.[param.key]
    if (v === null || v === undefined || String(v).trim() === '') {
      ElMessage.warning(`请填写：${param.label}`)
      return false
    }
  }
  return true
}

async function loadYamlSkills() {
  yamlLoading.value = true
  try {
    const data = await getYamlSkills()
    const list = Array.isArray(data?.skills) ? data.skills : []
    yamlSkills.value = list
    yamlCatalog.value = data.catalog || {}
    canManageReleases.value = data.canManage === true
    releaseHistory.value = canManageReleases.value ? ((await getSkillReleaseHistory()).history || []) : []
  } catch {
    yamlSkills.value = []
    yamlCatalog.value = {}
    canManageReleases.value = false
    releasePreview.value = null
    releaseHistory.value = []
  } finally {
    yamlLoading.value = false
  }
}

async function loadTools() {
  loading.value = true
  try {
    const [raw, platform] = await Promise.all([
      getMcpTools(),
      getPlatformInfo().catch(() => null)
    ])
    tools.value = mergeToolRegistry(raw, { platformInfo: platform })
    registryConnected.value = true
  } catch {
    registryConnected.value = false
    tools.value = mergeToolRegistry([], { platformInfo: null })
  } finally {
    loading.value = false
  }
}

async function loadAnalysisSkills() {
  analysisLoading.value = true
  try {
    const list = await getAnalysisSkills()
    analysisSkills.value = Array.isArray(list) ? list : []
    for (const skill of analysisSkills.value) {
      initAnalysisForm(skill)
    }
  } catch {
    analysisSkills.value = []
  } finally {
    analysisLoading.value = false
  }
}

async function executeNow(tool) {
  if (!tool?.name || tool.status !== 'online') return
  executing.value = tool.name
  try {
    const params = coerceToolParams(defaultParamsForTool(tool), tool)
    const data = await runMcpToolExecute(tool.name, params)
    if (!data) return
    resultDialogRef.value?.open({
      toolName: tool.name,
      success: data.success,
      duration: data.duration || 0,
      traceId: data.traceId,
      writeMismatch: data.writeMismatch === true,
      mode: typeof data?.data?.mode === 'string' ? data.data.mode : data.mode,
      raw: data
    })
  } catch (error) {
    // The global interceptor preserves the backend security/error payload;
    // surface it here instead of reducing every execution failure to a
    // generic toast.
    ElMessage.error(formatApiError(error))
  } finally {
    executing.value = null
  }
}

async function invokeAnalysis(skill) {
  if (!skill?.id) return
  if (!validateAnalysisForm(skill)) return
  analyzing.value = skill.id
  try {
    const data = await invokeAnalysisSkill(skill.id, buildAnalysisPayload(skill))
    analysisResultTitle.value = data?.skillName || skill.name
    analysisResultHtml.value = renderAssistantMarkdown(data?.answer || '')
    analysisDialogVisible.value = true
    await nextTick()
    if (analysisResultRef.value) {
      bindProseCopyButtons(analysisResultRef.value)
    }
  } finally {
    analyzing.value = null
  }
}

function useYamlInChat(skill) {
  const tools = Array.isArray(skill.tools) ? skill.tools.join('、') : ''
  const message = `请按 YAML Skill「${skill.id}」执行诊断：${skill.description || ''}${tools ? `\n建议工具：${tools}` : ''}`
  window.dispatchEvent(new CustomEvent('ops-navigate-agent', { detail: { message } }))
}

function useInChat(tool) {
  const hint = getMcpToolCommandHint(tool.name)
  const message = hint && hint !== 'system command'
    ? `请使用 ${tool.name}：${hint}`
    : `请执行 ${mcpToolDisplayName(tool.name)}`
  window.dispatchEvent(new CustomEvent('ops-navigate-agent', { detail: { message } }))
}

function useAnalysisInChat(skill) {
  const sample = buildAnalysisPayload(skill)
  const parts = [`请使用 AI 分析技能「${skill.name}」（${skill.id}）`]
  if (sample.content) parts.push(`内容：\n${sample.content}`)
  if (sample.metricName) parts.push(`指标：${sample.metricName} = ${sample.metricValue ?? ''}`)
  if (sample.period) parts.push(`时间范围：${sample.period}`)
  window.dispatchEvent(new CustomEvent('ops-navigate-agent', { detail: { message: parts.join('\n') } }))
}

onMounted(() => {
  loadTools()
  loadAnalysisSkills()
  loadYamlSkills()
})
</script>

<style scoped>
.agent-skills-page {
  flex: 1;
  min-height: calc(100vh - var(--ops-header-h, 52px));
  overflow-y: auto;
  padding: 24px 0 40px;
  background: var(--agent-surface);
}

.skills-header {
  margin-bottom: 16px;
}

.skills-header h1 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: var(--ops-text);
}

.skills-header p {
  margin: 0;
  font-size: 14px;
  color: var(--agent-muted);
  line-height: 1.5;
}

.skills-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}

.conn-alert {
  margin-bottom: 20px;
}

.skills-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

.skills-search {
  flex: 1;
  min-width: 200px;
  max-width: 420px;
}

.skills-empty {
  grid-column: 1 / -1;
  padding: 24px 0;
}

.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

.analysis-grid {
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
}

.release-panel { margin-bottom: 16px; }

.yaml-grid {
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
}

.yaml-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.yaml-signals {
  font-size: 10px;
  font-weight: 700;
  color: #0d9488;
  background: rgba(13, 148, 136, 0.12);
  padding: 2px 8px;
  border-radius: 999px;
}

.yaml-tools {
  margin: 0 0 8px;
  font-size: 11px;
  font-family: var(--ops-font-mono);
  color: var(--ops-primary);
}

.yaml-redlines {
  margin: 0 0 12px;
  padding-left: 18px;
  font-size: 11px;
  color: var(--el-color-danger);
  line-height: 1.5;
}

.skill-card {
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: var(--agent-surface);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}

.skill-card:hover {
  border-color: rgba(13, 148, 136, 0.35);
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.07);
  transform: translateY(-1px);
}

.skill-card.offline {
  opacity: 0.65;
}

.skill-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  font-size: 11px;
  font-weight: 700;
  margin-bottom: 10px;
}

.analysis-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.analysis-category {
  font-size: 11px;
  font-weight: 600;
  color: var(--ops-primary);
  background: rgba(13, 148, 136, 0.1);
  padding: 2px 8px;
  border-radius: 999px;
}

.analysis-kind {
  font-size: 10px;
  font-weight: 700;
  color: #6366f1;
  background: rgba(99, 102, 241, 0.12);
  padding: 2px 8px;
  border-radius: 999px;
}

.skill-card h3 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ops-text);
}

.skill-cmd {
  margin: 0 0 6px;
  font-family: var(--ops-font-mono);
  font-size: 11px;
  color: var(--ops-primary);
  opacity: 0.85;
}

.skill-desc {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--agent-muted);
}

.output-sections {
  margin: 0 0 12px;
  padding-left: 18px;
  font-size: 11px;
  color: var(--agent-muted);
  line-height: 1.5;
}

.analysis-form {
  margin-bottom: 8px;
}

.analysis-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.analysis-form :deep(.el-form-item__label) {
  font-size: 12px;
  padding-bottom: 2px;
}

.analysis-number {
  width: 100%;
}

.analysis-number :deep(.el-input__inner) {
  text-align: left;
}

.skill-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--agent-muted);
  margin-bottom: 12px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
}

.status-dot.offline {
  background: #a1a1aa;
}

.skill-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
  padding-top: 8px;
}

.analysis-result-body {
  max-height: 60vh;
  overflow-y: auto;
}
</style>
