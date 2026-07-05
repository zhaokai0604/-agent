param(
    [int]$Connections = 20,
    [int]$ReceiveTimeoutMs = 15000,
    [string]$ResultFile = ""
)

$ErrorActionPreference = 'Stop'

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percent
    )

    if (-not $Values -or $Values.Count -eq 0) {
        return $null
    }

    $sorted = $Values | Sort-Object
    $index = [Math]::Ceiling(($Percent / 100.0) * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    if ($index -ge $sorted.Count) { $index = $sorted.Count - 1 }
    return [double]$sorted[$index]
}

function Get-LatencySummary {
    param([double[]]$Values)

    if (-not $Values -or $Values.Count -eq 0) {
        return @{
            count = 0
            minMs = $null
            p50Ms = $null
            p95Ms = $null
            p99Ms = $null
            maxMs = $null
            avgMs = $null
        }
    }

    $sum = 0.0
    foreach ($value in $Values) {
        $sum += [double]$value
    }

    $sorted = $Values | Sort-Object
    return @{
        count = $sorted.Count
        minMs = [Math]::Round([double]$sorted[0], 2)
        p50Ms = [Math]::Round((Get-Percentile -Values $sorted -Percent 50), 2)
        p95Ms = [Math]::Round((Get-Percentile -Values $sorted -Percent 95), 2)
        p99Ms = [Math]::Round((Get-Percentile -Values $sorted -Percent 99), 2)
        maxMs = [Math]::Round([double]$sorted[$sorted.Count - 1], 2)
        avgMs = [Math]::Round(($sum / $sorted.Count), 2)
    }
}

function New-PerfSession {
    param(
        [string]$ManagementBase
    )

    $username = 'perfws_' + (Get-Date -Format 'HHmmssfff') + '_' + ([Guid]::NewGuid().ToString('N').Substring(0, 6))
    $password = 'PerfRunner#2026'
    $email = "$username@example.com"
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $headers = @{
        'X-Requested-With' = 'XMLHttpRequest'
    }

    $registerBody = @{
        username = $username
        password = $password
        email = $email
    } | ConvertTo-Json

    Invoke-RestMethod -Method Post -Uri "$ManagementBase/admin/user/register" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $registerBody | Out-Null

    $loginBody = @{
        username = $username
        password = $password
    } | ConvertTo-Json

    Invoke-RestMethod -Method Post -Uri "$ManagementBase/admin/user/login" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $loginBody | Out-Null

    return @{
        Session = $session
        Username = $username
    }
}

$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $PSScriptRoot 'config\env-dev.properties'
$configLines = Get-Content $configPath
$config = @{}
foreach ($rawLine in $configLines) {
    $line = $rawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
        continue
    }
    $index = $line.IndexOf('=')
    if ($index -le 0) {
        continue
    }
    $key = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim()
    $config[$key] = $value
}

$protocol = if ($config.ContainsKey('protocol')) { $config['protocol'] } else { 'http' }
$targetHost = if ($config.ContainsKey('host')) { $config['host'] } else { '127.0.0.1' }
$managementPort = if ($config.ContainsKey('management.port')) { $config['management.port'] } else { '8089' }
$contextPath = if ($config.ContainsKey('context.path')) { $config['context.path'] } else { '/award-log' }
$origin = if ($config.ContainsKey('allowed.origin')) { $config['allowed.origin'] } else { 'http://127.0.0.1:5173' }
$managementBase = '{0}://{1}:{2}{3}' -f $protocol, $targetHost, $managementPort, $contextPath
$wsScheme = if ($protocol -eq 'https') { 'wss' } else { 'ws' }
$wsUrl = '{0}://{1}:{2}{3}/ws/performance' -f $wsScheme, $targetHost, $managementPort, $contextPath

if ([string]::IsNullOrWhiteSpace($ResultFile)) {
    $ResultFile = Join-Path $PSScriptRoot 'data\results\ws-benchmark.json'
}

$resultDir = Split-Path -Parent $ResultFile
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

$jobs = @()

for ($i = 0; $i -lt $Connections; $i++) {
    $jobs += Start-Job -ScriptBlock {
        param($managementBase, $wsUrl, $origin, $timeoutMs)

        $username = 'perfws_' + (Get-Date -Format 'HHmmssfff') + '_' + ([Guid]::NewGuid().ToString('N').Substring(0, 6))
        $password = 'PerfRunner#2026'
        $email = "$username@example.com"
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $headers = @{
            'X-Requested-With' = 'XMLHttpRequest'
        }
        $registerBody = @{
            username = $username
            password = $password
            email = $email
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$managementBase/admin/user/register" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $registerBody | Out-Null
        $loginBody = @{
            username = $username
            password = $password
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$managementBase/admin/user/login" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $loginBody | Out-Null

        $ws = [System.Net.WebSockets.ClientWebSocket]::new()
        $ws.Options.Cookies = $session.Cookies
        $ws.Options.SetRequestHeader('Origin', $origin)
        $uri = [Uri]$wsUrl
        $cts = [System.Threading.CancellationTokenSource]::new()
        $cts.CancelAfter($timeoutMs)
        $clock = [System.Diagnostics.Stopwatch]::StartNew()

        try {
            $ws.ConnectAsync($uri, $cts.Token).GetAwaiter().GetResult()
            $connectMs = $clock.Elapsed.TotalMilliseconds
            $payload = [System.Text.Encoding]::UTF8.GetBytes('refresh')
            $segment = [ArraySegment[byte]]::new($payload)
            $ws.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
            $buffer = New-Object byte[] 8192
            $receive = [ArraySegment[byte]]::new($buffer)
            $result = $ws.ReceiveAsync($receive, $cts.Token).GetAwaiter().GetResult()
            $messageMs = $clock.Elapsed.TotalMilliseconds
            $message = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
            if ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                try {
                    $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'done', [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
                } catch {
                }
            }
            [pscustomobject]@{
                ok = $true
                connectMs = [Math]::Round($connectMs, 2)
                firstMessageMs = [Math]::Round($messageMs, 2)
                preview = $message.Substring(0, [Math]::Min($message.Length, 200))
            }
        } catch {
            [pscustomobject]@{
                ok = $false
                connectMs = $null
                firstMessageMs = $null
                error = $_.Exception.Message
            }
        } finally {
            $ws.Dispose()
            $cts.Dispose()
        }
    } -ArgumentList $managementBase, $wsUrl, $origin, $ReceiveTimeoutMs
}

$completed = Wait-Job -Job $jobs
$results = $completed | Receive-Job
$jobs | Remove-Job -Force | Out-Null

$successes = @($results | Where-Object { $_.ok })
$failures = @($results | Where-Object { -not $_.ok })
$connectLatencies = @($successes | ForEach-Object { [double]$_.connectMs })
$messageLatencies = @($successes | ForEach-Object { [double]$_.firstMessageMs })

$report = [ordered]@{
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
    connections = $Connections
    managementBase = $managementBase
    wsUrl = $wsUrl
    successCount = $successes.Count
    failureCount = $failures.Count
    successRate = if ($Connections -gt 0) { [Math]::Round($successes.Count / [double]$Connections, 4) } else { 0.0 }
    connectLatency = Get-LatencySummary -Values $connectLatencies
    firstMessageLatency = Get-LatencySummary -Values $messageLatencies
    failures = @($failures | Select-Object -First 10)
    results = @($results)
}

$json = $report | ConvertTo-Json -Depth 8
Set-Content -Path $ResultFile -Value $json -Encoding UTF8
Write-Output $json
