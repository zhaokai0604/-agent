# V1 Golden Path smoke (PowerShell, server must be running)
# Usage: .\scripts\deploy\smoke_v1_golden_path.ps1
# Optional: $env:BASE = "http://127.0.0.1:8088/award-log"

$ErrorActionPreference = "Stop"
$Base = if ($env:BASE) { $env:BASE } else { "http://127.0.0.1:8088/award-log" }
$SmokeUser = if ($env:SMOKE_USER) { $env:SMOKE_USER } else { "smoke_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
$SmokePass = if ($env:SMOKE_PASS) { $env:SMOKE_PASS } else { "SmokePass123!" }
$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Get-Code($obj) {
    if ($null -eq $obj) { return $null }
    if ($obj.code) { return $obj.code }
    try { return ($obj | ConvertFrom-Json).code } catch { return $null }
}

Write-Host "== V1 Golden Path Smoke =="
Write-Host "BASE=$Base USER=$SmokeUser"

Write-Host "== 1. Actuator health =="
try {
    Invoke-RestMethod -Uri "$Base/actuator/health" -WebSession $Session | Out-Null
    Pass "actuator/health"
} catch { Fail "actuator/health unreachable: $_" }

Write-Host "== 2. Platform acceptance =="
try {
    Invoke-RestMethod -Uri "$Base/api/platform/acceptance" -WebSession $Session | Out-Null
    Pass "platform/acceptance"
} catch { Fail "platform/acceptance: $_" }

Write-Host "== 3. Security self-check (public) =="
try {
    Invoke-RestMethod -Uri "$Base/api/security/self-check" -WebSession $Session | Out-Null
    Pass "security/self-check public"
} catch { Warn "self-check public: $_" }

Write-Host "== 4. Register + login =="
$headers = @{ "X-Requested-With" = "XMLHttpRequest" }
try {
    Invoke-RestMethod -Method Post -Uri "$Base/admin/user/register" -WebSession $Session `
        -ContentType "application/json" `
        -Body (@{ username = $SmokeUser; password = $SmokePass; email = "$SmokeUser@smoke.local" } | ConvertTo-Json) | Out-Null
} catch { }

$login = Invoke-RestMethod -Method Post -Uri "$Base/admin/user/login" -WebSession $Session `
    -ContentType "application/json" -Headers $headers `
    -Body (@{ username = $SmokeUser; password = $SmokePass } | ConvertTo-Json)
if ((Get-Code $login) -eq 200) { Pass "login code=200" } else { Fail "login failed" }

Write-Host "== 4b. Security self-check (session) =="
try {
    Invoke-RestMethod -Uri "$Base/api/security/self-check" -WebSession $Session | Out-Null
    Pass "security/self-check session"
} catch { Warn "self-check session: $_" }

Write-Host "== 5. MCP tools =="
$tools = Invoke-RestMethod -Uri "$Base/api/mcp/tools" -WebSession $Session
$toolNames = @()
if ($tools.data) {
    foreach ($t in $tools.data) {
        if ($t.name) { $toolNames += $t.name }
        if ($t.toolName) { $toolNames += $t.toolName }
    }
}
if ($toolNames -contains "DiskTool") { Pass "DiskTool registered" } else { Fail "DiskTool missing" }

Write-Host "== 6. DiskTool read-only =="
$disk = Invoke-RestMethod -Method Post -Uri "$Base/api/mcp/execute" -WebSession $Session `
    -ContentType "application/json" -Headers $headers `
    -Body '{"toolName":"DiskTool","parameters":{},"userMessage":"golden path disk check"}'
if ((Get-Code $disk) -eq 200 -or $disk.success -eq $true) { Pass "DiskTool ok" } else { Warn "DiskTool response needs review" }

Write-Host "== 7. CleanTemp preview =="
Invoke-RestMethod -Method Post -Uri "$Base/api/mcp/execute" -WebSession $Session `
    -ContentType "application/json" -Headers $headers `
    -Body '{"toolName":"CleanTempTool","parameters":{"dryRun":true},"userMessage":"golden path","previewOnly":true}' | Out-Null
Pass "CleanTemp preview invoked"

Write-Host "== 8-10. Patrol / audit / skills =="
$p = Invoke-RestMethod -Uri "$Base/api/ops/patrol/history" -WebSession $Session
if ((Get-Code $p) -eq 200) { Pass "patrol/history" } else { Warn "patrol/history" }
$a = Invoke-RestMethod -Uri "$Base/api/audit/feed?pageNum=1&pageSize=5" -WebSession $Session
if ((Get-Code $a) -eq 200) { Pass "audit/feed" } else { Warn "audit/feed" }
try {
    Invoke-RestMethod -Uri "$Base/api/ops/skills/yaml" -WebSession $Session | Out-Null
    Pass "ops/skills/yaml"
} catch { Warn "skills/yaml: $_" }

Write-Host "== Done =="
