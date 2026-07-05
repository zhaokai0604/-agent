param(
    [string]$OutputDir = "perf/data/logs"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Write-SizedLog {
    param(
        [string]$Path,
        [int64]$TargetBytes,
        [string]$Variant
    )

    $lines = switch ($Variant) {
        "normal" { @(
            "INFO  [app] requestId={0} module=collector action=scan message=collector heartbeat ok durationMs=12",
            "INFO  [db] requestId={0} module=task action=query message=task status fetched rows=20 durationMs=18",
            "WARN  [cache] requestId={0} module=dashboard action=refresh message=cache miss recovered in 43ms",
            "INFO  [agent] requestId={0} module=patrol action=report message=patrol completed findings=1"
        ) }
        "anomaly" { @(
            "ERROR [db] requestId={0} module=analysis action=insert message=lock wait timeout exceeded durationMs=4212 sqlState=40001",
            "ERROR [api] requestId={0} module=chat action=stream message=upstream ai timeout retry=1 durationMs=9231",
            "WARN  [sys] requestId={0} module=runtime action=probe message=cpu usage spike value=93.4 queueDepth=18",
            "ERROR [mq] requestId={0} module=kafka action=send message=broker unavailable retries=3 durationMs=812"
        ) }
        default { @(
            "INFO  [mixed] requestId={0} module=upload action=parse message=partial line accepted durationMs=15",
            "WARN  [mixed] requestId={0} module=parser action=decode message=unexpected token near column 219 recovered=true",
            "ERROR [mixed] requestId={0} module=storage action=write message=temp file busy retry=2 durationMs=204",
            "TRACE [mixed] requestId={0} module=agent action=context message=raw payload chunk dropped due to size limit"
        ) }
    }

    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    try {
        $writer = [System.IO.StreamWriter]::new($stream, $utf8)
        try {
            $i = 0
            while ($stream.Length -lt $TargetBytes) {
                $template = $lines[$i % $lines.Count]
                $requestId = "{0:D8}" -f $i
                $timestamp = (Get-Date).AddSeconds($i).ToString("yyyy-MM-dd HH:mm:ss.fff")
                $line = "$timestamp " + ($template -f $requestId)
                $writer.WriteLine($line)
                $i++
            }
            $writer.Flush()
        }
        finally {
            $writer.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

Ensure-Directory -Path $OutputDir

$targets = @(
    @{ Name = "log-small-normal-1mb.log"; Bytes = 1MB; Variant = "normal" },
    @{ Name = "log-small-anomaly-1mb.log"; Bytes = 1MB; Variant = "anomaly" },
    @{ Name = "log-small-mixed-1mb.log"; Bytes = 1MB; Variant = "dirty" },
    @{ Name = "log-medium-normal-10mb.log"; Bytes = 10MB; Variant = "normal" },
    @{ Name = "log-medium-anomaly-10mb.log"; Bytes = 10MB; Variant = "anomaly" },
    @{ Name = "log-medium-dirty-10mb.log"; Bytes = 10MB; Variant = "dirty" },
    @{ Name = "log-large-normal-50mb.log"; Bytes = 50MB; Variant = "normal" },
    @{ Name = "log-large-anomaly-50mb.log"; Bytes = 50MB; Variant = "anomaly" },
    @{ Name = "log-large-dirty-50mb.log"; Bytes = 50MB; Variant = "dirty" }
)

foreach ($item in $targets) {
    $path = Join-Path $OutputDir $item.Name
    Write-Host "Generating $($item.Name)..."
    Write-SizedLog -Path $path -TargetBytes $item.Bytes -Variant $item.Variant
}

Write-Host "Generated $($targets.Count) log samples under $OutputDir"
