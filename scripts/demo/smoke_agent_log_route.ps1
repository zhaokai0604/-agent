# ThreshCore 第二阶段：Agent 自然语言路由验收
# 服务启动后运行：.\scripts\demo\smoke_agent_log_route.ps1

$ErrorActionPreference = "Stop"
$Base = if ($env:BASE) { $env:BASE } else { "http://127.0.0.1:8088/award-log" }
$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$Headers = @{ "X-Requested-With" = "XMLHttpRequest" }

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "验收失败：$Message" }
}

function Post-Json([string]$Uri, [hashtable]$Body) {
    return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $Session `
        -ContentType "application/json" -Headers $Headers `
        -Body ($Body | ConvertTo-Json -Depth 12)
}

$User = "phase2_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$Password = "Phase2Pass123!"
try {
    Post-Json "$Base/admin/user/register" @{ username = $User; password = $Password; email = "$User@smoke.local" } | Out-Null
} catch { }
$Login = Post-Json "$Base/admin/user/login" @{ username = $User; password = $Password }
Assert-That ($Login.code -eq 200) "登录失败"

$Message = "帮我检查并清理过期日志，先给出方案，暂不执行"
$Preview = Post-Json "$Base/api/assistant/state/preview" @{
    message = $Message
    history = @()
    useToolAgent = $true
    confirmRemediation = $false
}
$State = $Preview.data
$Planned = @($State.plannedTools)
$Pending = @($State.pendingWriteTools)
$Execution = $State.executionState

Assert-That ($Preview.code -eq 200) "Agent 状态预览接口失败"
Assert-That ($State.replyMode -eq "TOOL_AGENT") "没有路由到 TOOL_AGENT"
Assert-That ($State.intentCategory -eq "OPS_DIAGNOSIS") "意图分类不是 OPS_DIAGNOSIS"
Assert-That ($State.useToolAgentPath -eq $true) "Agent 工具路径未启用"
Assert-That ($State.planPhase -eq "DIAGNOSE_THEN_CONFIRM") "没有进入先诊断后确认阶段"
Assert-That ($State.awaitingConfirm -eq $true) "没有等待写操作确认"
Assert-That ($Execution.writeToolsMounted -eq $false) "确认前写工具不应挂载"
Assert-That ($Planned -contains "LogCleanupTool") "计划中缺少 LogCleanupTool"
Assert-That ($Pending -contains "LogCleanupTool") "待确认写工具中缺少 LogCleanupTool"
Assert-That (-not ($Planned -contains "CleanTempTool")) "日志场景不应混入 CleanTempTool"

[pscustomobject]@{
    scenario = "Agent 自然语言日志清理路由"
    message = $Message
    replyMode = $State.replyMode
    intentCategory = $State.intentCategory
    planPhase = $State.planPhase
    awaitingConfirm = $State.awaitingConfirm
    writeToolsMounted = $Execution.writeToolsMounted
    plannedTools = ($Planned -join ",")
    pendingWriteTools = ($Pending -join ",")
    nextAction = $Execution.nextAction
} | ConvertTo-Json -Depth 6

Write-Host "[PASS] Agent natural-language route"
