<template>
  <section class="team-page">
    <div class="team-heading"><div><h2>协作处置任务</h2><p>诊断、计划、验证分别交接。先查看证据和预览，批准后才执行。</p></div><el-button @click="refresh">刷新任务</el-button></div>
    <el-card shadow="never">
      <el-form label-position="top" @submit.prevent="start">
        <el-form-item label="要解决的问题"><el-input v-model="goal" type="textarea" :rows="2" placeholder="描述故障、目标和允许的处置范围" maxlength="1000" /></el-form-item>
        <el-form-item label="处置能力"><el-select v-model="skillId" @change="suggestProbes"><el-option v-for="skill in skills" :key="skill.id" :label="`${skill.description}（${skill.id}）`" :value="skill.id" /></el-select></el-form-item>
        <el-alert type="info" :closable="false" title="证据采集只读或预览，不执行清理、重启。请核对目标路径和服务名。" />
        <el-form-item label="证据采集工具及参数（JSON）"><el-input v-model="probesText" type="textarea" :rows="6" spellcheck="false" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!goal.trim() || !skillId">采集证据并生成计划</el-button>
      </el-form>
    </el-card>
    <div class="team-layout">
      <el-card shadow="never" class="team-history"><template #header>最近任务</template>
        <p v-if="!history.length">尚无协作任务</p>
        <button v-for="item in history" :key="item.run_id" class="history-item" :class="{selected: run?.runId === item.run_id}" @click="select(item.run_id)">
          <strong>{{ item.goal }}</strong><span>{{ stateLabel(item.state) }}</span>
        </button>
      </el-card>
      <el-card v-if="run" shadow="never" class="team-detail">
        <template #header><div class="team-heading"><strong>{{ stateLabel(run.state) }}</strong><el-button v-if="!['COMPLETED', 'CANCELLED'].includes(run.state)" @click="cancel" :loading="busy">停止任务</el-button></div></template>
        <p>{{ run.goal }}</p><p class="muted">任务 {{ run.runId }} · 计划版本 {{ run.revision }} · 模型调用 {{ run.modelCalls }} 次</p>
        <el-alert v-if="run.errorCode" type="warning" :closable="false" :title="errorLabel(run.errorCode)" />
        <el-alert v-if="run.state === 'NEEDS_HUMAN' && run.writeStarted" type="warning" :closable="false" title="此任务已记录写入意图。停止任务不代表外部操作已撤销，请核对回执和实际状态后再接管。" />
        <h3 v-if="run.diagnosis">诊断交接</h3><p v-if="run.diagnosis">{{ run.diagnosis.summary }}</p>
        <template v-if="run.plan"><h3>处置计划</h3><p>{{ run.plan.summary }}</p>
          <ol><li v-for="(step, i) in run.plan.steps" :key="i"><strong>{{ step.tool }}</strong>：{{ step.expectedOutcome }}<pre>{{ pretty(step.parameters) }}</pre></li></ol>
          <p>停止条件：{{ run.plan.stopConditions.join('；') }}</p>
        </template>
        <el-collapse v-if="run.previews?.length"><el-collapse-item title="核对工具预览、影响范围与恢复说明" name="previews"><pre>{{ pretty(run.previews) }}</pre></el-collapse-item></el-collapse>
        <div v-if="run.state === 'WAITING_APPROVAL'" class="approval-box">
          <template v-if="run.approvalReady"><p>核对以上计划后，输入“确认执行”。</p><el-input v-model="confirmCode" placeholder="确认执行" /><el-button type="danger" :disabled="confirmCode !== '确认执行'" :loading="busy" @click="approve">批准当前计划</el-button></template>
          <el-alert v-else type="warning" :closable="false" title="批准凭证已过期或服务已重启。请使用下方人工接管重新预览。" />
        </div>
        <el-collapse v-if="['WAITING_APPROVAL', 'NEEDS_HUMAN'].includes(run.state)"><el-collapse-item title="人工接管：修改计划或重新预览" name="edit">
          <el-button @click="fillEditor">载入当前计划</el-button><el-input v-model="planText" type="textarea" :rows="12" spellcheck="false" placeholder="输入符合契约的计划 JSON" @input="capturePlanRevision" />
          <el-input v-model="reason" placeholder="填写接管原因与已核对的情况" />
          <el-checkbox v-if="run.writeStarted" v-model="reviewed">我已核对外部实际状态和既有回执，允许重新预览</el-checkbox>
          <el-alert v-if="planText.trim() && !canSubmitTeamDraft(planRevision, run)" type="warning" :closable="false" title="任务已发生变化，请重新载入计划并核对。" />
          <el-button :loading="busy" :disabled="!reason.trim() || !planText.trim() || !canSubmitTeamDraft(planRevision, run)" @click="edit">保存新计划并重新预览</el-button>
          <template v-if="run.state === 'NEEDS_HUMAN'"><p>需要补充证据时，可修改采集工具与参数，再重新诊断和规划。</p><el-button @click="fillEvidence">载入当前采集配置</el-button><el-input v-model="evidenceText" type="textarea" :rows="6" spellcheck="false" placeholder="输入采集配置 JSON" @input="captureEvidenceRevision" /><el-alert v-if="evidenceText.trim() && !canSubmitTeamDraft(evidenceRevision, run)" type="warning" :closable="false" title="任务已发生变化，请重新载入采集配置并核对。" /><el-button :loading="busy" :disabled="!reason.trim() || !evidenceText.trim() || !canSubmitTeamDraft(evidenceRevision, run)" @click="retryEvidence">重新采集并诊断</el-button></template>
        </el-collapse-item></el-collapse>
        <h3>协作与执行时间线</h3>
        <el-timeline><el-timeline-item v-for="event in run.events" :key="event.event_no" :timestamp="String(event.created_at)">
          <strong>{{ event.actor }} · {{ event.kind }}</strong><details><summary>查看交接、证据或回执</summary><pre>{{ eventPayload(event.payload) }}</pre></details>
        </el-timeline-item></el-timeline>
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { listAgentTeams, getAgentTeamSkills, getAgentTeam, startAgentTeam, approveAgentTeam, editAgentTeamPlan, cancelAgentTeam, retryAgentTeamEvidence } from '../../api'
import { canSubmitTeamDraft, teamApprovalChanged, teamPollRetryDelay } from '../../utils/teamRevision.js'
const skills = ref([]), skillId = ref(''), goal = ref(''), probesText = ref('[]'), history = ref([]), run = ref(null)
const busy = ref(false), confirmCode = ref(''), planText = ref(''), evidenceText = ref(''), reason = ref(''), reviewed = ref(false)
const planRevision = ref(null), evidenceRevision = ref(null)
const active = new Set(['QUEUED', 'COLLECTING', 'DIAGNOSING', 'PLANNING', 'PREVIEWING', 'EXECUTING', 'VERIFYING'])
let timer, controller, generation = 0
const pretty = value => JSON.stringify(value, null, 2)
const eventPayload = text => { try { return pretty(JSON.parse(text)) } catch { return text } }
const stateLabel = state => ({QUEUED:'等待采集',COLLECTING:'采集证据',DIAGNOSING:'诊断角色处理中',PLANNING:'计划角色处理中',PREVIEWING:'核对执行预览',WAITING_APPROVAL:'等待人工批准',EXECUTING:'受控执行中',VERIFYING:'独立验证中',COMPLETED:'验证完成',NEEDS_HUMAN:'需要人工接管',CANCELLED:'任务已停止'}[state] || state)
const errorLabel = code => ({RUNTIME_SHUTDOWN:'服务关闭导致任务中断，请核对状态后接管',RUNTIME_DEADLINE_OR_INTERRUPTION:'运行已超时或中断，请核对状态后接管',QUEUE_REJECTED:'执行队列已满，请稍后重新预览',CANCELLED_AFTER_WRITE_INTENT:'已停止后续步骤；已有写入意图需要核对',USER_CANCELLED:'用户已停止任务',ROLE_OR_TOOL_CONTRACT_INVALID:'角色输出或工具参数未通过契约校验',ROLE_TOOL_OR_BUDGET_FAILED:'角色、工具或运行预算未通过，请查看时间线',EXECUTION_INTERRUPTED:'执行已中断，请核对回执',DIAGNOSIS_NEEDS_EVIDENCE:'诊断角色认为证据不足，请补充证据后接管',DIAGNOSIS_EMPTY:'诊断角色未给出结论，请检查模型输出',EVIDENCE_REFERENCES_MISSING:'角色未提供有效的证据引用',EVIDENCE_REFERENCE_UNKNOWN:'角色引用了不存在的证据，请核对交接记录',EVIDENCE_INSUFFICIENT:'独立证据来源不足，请补充证据',TOOL_EFFECT_NOT_VERIFIED:'工具执行效果未通过验证，请核对回执和实际状态',VERIFICATION_EMPTY:'验证角色未给出结论，请核对执行结果',VERIFIER_REQUESTED_TAKEOVER:'验证角色要求人工接管，请查看验证意见'}[code] || code)
async function loadHistory () { try { history.value = await listAgentTeams() } catch { /* API reports the error */ } }
async function refresh () { const id = run.value?.runId; await loadHistory(); if (id) await select(id) }
function suggestProbes () {
  const skill = skills.value.find(s => s.id === skillId.value)
  probesText.value = pretty((skill?.tools || []).slice(0, 2).map(tool => ({ tool, parameters: tool === 'CleanTempTool' || tool === 'LogCleanupTool' ? {path:'请填写允许处置的目录',days:7} : tool === 'DiskAnalyzeTool' ? {rootPath:'请填写检查目录',includeHotspots:true,topN:5} : tool === 'ServiceRestartTool' ? {serviceName:'请填写服务名'} : {} })))
}
function stopPolling () { generation++; clearTimeout(timer); controller?.abort(); controller = null }
async function select (id) {
  stopPolling(); const epoch = generation; controller = new AbortController(); run.value = null; confirmCode.value = ''; planText.value = ''; evidenceText.value = ''; planRevision.value = null; evidenceRevision.value = null; reason.value = ''; reviewed.value = false
  let failures = 0
  const poll = async () => {
    try {
      const latest = await getAgentTeam(id, controller.signal)
      if (epoch !== generation) return
      failures = 0
      const previous = run.value
      if (teamApprovalChanged(previous, latest)) { confirmCode.value = ''; reviewed.value = false }
      run.value = latest
      if (previous?.state !== latest.state) loadHistory()
      if (active.has(latest.state)) timer = setTimeout(poll, 1500)
      else if (['WAITING_APPROVAL', 'NEEDS_HUMAN'].includes(latest.state)) timer = setTimeout(poll, 5000)
      else loadHistory()
    }
    catch (error) {
      if (epoch !== generation) return
      const delay = teamPollRetryDelay(error?.status ?? error?.raw?.code ?? error?.response?.status, ++failures)
      if (failures === 1) ElMessage.warning(delay === null ? '无法读取任务，请检查登录和访问权限' : '连接中断，正在尝试恢复任务状态')
      if (delay !== null) timer = setTimeout(poll, delay)
    }
  }
  await poll()
}
async function action (work) { if (busy.value) return; busy.value = true; try { const result = await work(); await select(result.runId); await loadHistory() } catch (error) { if (error instanceof SyntaxError) ElMessage.error('JSON 格式不正确，请检查后重试'); else if (run.value) await select(run.value.runId) } finally { busy.value = false } }
function start () { return action(() => startAgentTeam({requestId: globalThis.crypto?.randomUUID?.() || `team-${Date.now()}-${Math.random().toString(36).slice(2)}`, goal: goal.value, skillId: skillId.value, observations: JSON.parse(probesText.value)})) }
function approve () { const current = run.value; return action(() => approveAgentTeam(current.runId, {revision:current.revision,planHash:current.planHash,confirmCode:confirmCode.value})) }
function fillEditor () { planText.value = pretty(run.value.plan || {summary:'填写人工处置计划',steps:[],verification:[],stopConditions:['证据不足或验证不符时停止']}); planRevision.value = run.value.revision }
function fillEvidence () { evidenceText.value = pretty(run.value.observations); evidenceRevision.value = run.value.revision }
function capturePlanRevision () { if (planRevision.value === null) planRevision.value = run.value.revision }
function captureEvidenceRevision () { if (evidenceRevision.value === null) evidenceRevision.value = run.value.revision }
function edit () { const current = run.value; if (!canSubmitTeamDraft(planRevision.value, current)) return; return action(() => editAgentTeamPlan(current.runId,{revision:planRevision.value,plan:JSON.parse(planText.value),reason:reason.value,externalEffectsReviewed:reviewed.value})) }
function retryEvidence () { const current = run.value; if (!canSubmitTeamDraft(evidenceRevision.value, current)) return; return action(() => retryAgentTeamEvidence(current.runId,{revision:evidenceRevision.value,observations:JSON.parse(evidenceText.value),reason:reason.value,externalEffectsReviewed:reviewed.value})) }
function cancel () { return action(() => cancelAgentTeam(run.value.runId)) }
onMounted(async () => { try { skills.value = await getAgentTeamSkills(); skillId.value = skills.value.find(s => s.id === 'temp-cleanup')?.id || skills.value[0]?.id || ''; suggestProbes(); await loadHistory(); if(history.value.length) await select(history.value[0].run_id) } catch { /* API reports the error */ } })
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.team-page{display:grid;gap:18px;max-width:1400px;margin:auto;padding:20px}.team-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.team-heading h2{margin:0}.team-heading p,.muted{color:var(--el-text-color-secondary);overflow-wrap:anywhere}.team-layout{display:grid;grid-template-columns:260px minmax(0,1fr);gap:18px}.history-item{display:grid;gap:8px;text-align:left;width:100%;padding:12px;margin-bottom:8px;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-primary);cursor:pointer}.history-item strong{overflow-wrap:anywhere}.history-item span{font-size:12px;color:var(--el-text-color-secondary)}.history-item.selected{border-color:var(--el-color-primary)}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:300px;overflow:auto;background:var(--el-fill-color-light);padding:10px;border-radius:6px}.approval-box{padding:16px;background:var(--el-fill-color-light);margin:16px 0}.approval-box .el-input{max-width:240px;margin-right:12px}.el-textarea,.el-collapse .el-input{margin:10px 0}.team-detail{min-width:0}.el-form-item .el-select{width:100%}@media(max-width:800px){.team-layout{grid-template-columns:1fr}.team-page{padding:10px}.team-heading{align-items:flex-start}}
</style>
