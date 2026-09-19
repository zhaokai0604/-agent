# ThreshCore 固定演示：日志清理受控处置闭环
# 预置目录必须位于当前项目 logs 根目录，才能与 Windows 本地路径策略保持一致。
# 服务启动后运行：.\scripts\demo\smoke_fixed_log_cleanup.ps1

$ErrorActionPreference = "Stop"
$Base = if ($env:BASE) { $env:BASE } else { "http://127.0.0.1:8088/award-log" }
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$DemoRelative = "logs/threshcore-demo-logs-v1"
$DemoRoot = Join-Path $RepoRoot "logs\threshcore-demo-logs-v1"
$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$Headers = @{ "X-Requested-With" = "XMLHttpRequest" }
$ExpectedFiles = @("app.log.20240101", "mysql-slow.log", "audit.log")

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "验收失败：$Message" }
}

function Invoke-JsonPost([string]$Uri, [hashtable]$Body) {
    return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $Session `
        -ContentType "application/json" -Headers $Headers `
        -Body ($Body | ConvertTo-Json -Depth 12)
}

function Parse-BusinessData($Response) {
    if ($null -ne $Response.businessData) { return $Response.businessData }
    $Current = $Response.data
    for ($i = 0; $i -lt 3 -and $null -ne $Current; $i++) {
        if ($Current -is [string]) {
            try { $Current = $Current | ConvertFrom-Json } catch { break }
        }
        if ($null -ne $Current.businessData) { return $Current.businessData }
        if ($null -ne $Current.filesFound -or $null -ne $Current.restored) { return $Current }
        if ($null -ne $Current.data) { $Current = $Current.data } else { break }
    }
    return $null
}

function Get-FileNames() {
    return @(Get-ChildItem -LiteralPath $DemoRoot -File | Select-Object -ExpandProperty Name | Sort-Object)
}

Write-Host "== ThreshCore fixed log-cleanup closure smoke =="
Write-Host "BASE=$Base"
Write-Host "DEMO=$DemoRelative"

if (-not (Test-Path -LiteralPath $DemoRoot)) {
    New-Item -ItemType Directory -Path $DemoRoot | Out-Null
}
$Existing = @(Get-FileNames)
if ($Existing.Count -gt 0) {
    Assert-That (($Existing -join "|") -eq (($ExpectedFiles | Sort-Object) -join "|")) `
        "演示目录不是预期基线，当前文件：$($Existing -join ', ')"
}
foreach ($Name in $ExpectedFiles) {
    $Path = Join-Path $DemoRoot $Name
    if (-not (Test-Path -LiteralPath $Path)) {
        Set-Content -LiteralPath $Path -Value ("fixed demo log: " + $Name)
    }
    (Get-Item -LiteralPath $Path).LastWriteTime = (Get-Date).AddDays(-90)
}

$User = "phase1_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$Password = "Phase1Pass123!"
try {
    Invoke-JsonPost "$Base/admin/user/register" @{ username = $User; password = $Password; email = "$User@smoke.local" } | Out-Null
} catch { }
$Login = Invoke-JsonPost "$Base/admin/user/login" @{ username = $User; password = $Password }
Assert-That ($Login.code -eq 200) "登录失败"

$Preview = Invoke-JsonPost "$Base/api/mcp/execute" @{
    toolName = "LogCleanupTool"
    parameters = @{ path = $DemoRelative; days = 30; dryRun = $true }
    userMessage = "固定演示：预览过期日志清理并保护审计、慢查询日志"
    previewOnly = $true
}
$PreviewData = Parse-BusinessData $Preview
Assert-That ($Preview.success -eq $true) "预览接口未成功"
Assert-That ($PreviewData.filesFound -eq 3) "预览 filesFound 应为 3，实际为 $($PreviewData.filesFound)"
Assert-That ($PreviewData.deletableCount -eq 1) "预览 deletableCount 应为 1，实际为 $($PreviewData.deletableCount)"
Assert-That ($PreviewData.protectedSkipped -eq 2) "预览 protectedSkipped 应为 2，实际为 $($PreviewData.protectedSkipped)"

$Proposal = Invoke-JsonPost "$Base/api/mcp/execute" @{
    toolName = "LogCleanupTool"
    parameters = @{ path = $DemoRelative; days = 30; dryRun = $false; confirmDelete = $false }
    userMessage = "固定演示：请求执行过期日志清理"
}
Assert-That ($Proposal.needConfirm -eq $true) "写操作没有进入确认阶段"
Assert-That (-not [string]::IsNullOrWhiteSpace($Proposal.confirmationId)) "缺少 confirmationId"
Assert-That (-not [string]::IsNullOrWhiteSpace($Proposal.capabilityToken)) "缺少 capabilityToken"
Assert-That (-not [string]::IsNullOrWhiteSpace($Proposal.confirmToken)) "缺少 confirmToken"

$Execute = Invoke-JsonPost "$Base/api/mcp/confirmExecute" @{
    confirmationId = $Proposal.confirmationId
    capabilityToken = $Proposal.capabilityToken
    confirmToken = $Proposal.confirmToken
    confirmCode = "确认执行"
}
$ExecuteData = Parse-BusinessData $Execute
Assert-That ($Execute.success -eq $true) "确认执行失败"
Assert-That ($Execute.verified -eq $true) "执行后验证未通过"
Assert-That ($Execute.verifyOutcome -eq "VERIFIED") "verifyOutcome 不是 VERIFIED"
Assert-That ($Execute.metricsBefore.deletableCount -eq 1) "执行前指标不符合固定数据"
Assert-That ($Execute.metricsAfter.deletableCount -eq 0) "执行后指标未归零"
Assert-That ($Execute.metricsDelta.deletableCount -eq -1) "指标差值不符合预期"
Assert-That ($ExecuteData.recycledCount -eq 1) "未产生 1 个回收站条目"
Assert-That ($ExecuteData.protectedFilesPreserved -eq $true) "受保护文件未保持"
$Handle = [string]$ExecuteData.recycleHandles[0]
Assert-That (-not [string]::IsNullOrWhiteSpace($Handle)) "缺少回收句柄"

$Restore = Invoke-JsonPost "$Base/api/mcp/execute" @{
    toolName = "RecycleBinTool"
    parameters = @{ operation = "restore"; handle = $Handle }
    userMessage = "固定演示：恢复刚刚回收的日志文件"
}
$RestoreData = Parse-BusinessData $Restore
Assert-That ($Restore.success -eq $true) "恢复接口失败"
Assert-That ($RestoreData.restored -eq $true) "回收站恢复结果不是 restored=true"
Assert-That ($RestoreData.recoveryVerified -eq $true) "恢复后原路径验证失败"
Assert-That ((Get-FileNames) -contains "app.log.20240101") "恢复后原日志文件不存在"
Assert-That ((Get-FileNames) -contains "audit.log") "恢复后审计日志不存在"
Assert-That ((Get-FileNames) -contains "mysql-slow.log") "恢复后慢查询日志不存在"

[pscustomobject]@{
    dataset = "threshcore-demo-v1"
    scenario = "日志清理受控处置闭环"
    preview = "filesFound=3, deletableCount=1, protectedSkipped=2"
    approval = "needConfirm=true"
    execute = "verified=true, verifyOutcome=VERIFIED, recycledCount=1, protectedFilesPreserved=true"
    metrics = "deletableCount 1 -> 0, delta=-1"
    restore = "restored=true, recoveryVerified=true"
    finalFiles = ((Get-FileNames) -join ",")
    traceId = $Execute.traceId
} | ConvertTo-Json -Depth 6

Write-Host "[PASS] fixed log-cleanup closure"
