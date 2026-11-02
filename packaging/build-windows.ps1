[CmdletBinding()]
param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipTests,
    [switch]$PortableOnly,
    [string]$WixBin = ''
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendRoot = Join-Path $projectRoot 'frontend'
$targetRoot = Join-Path $projectRoot 'target'
$packageRoot = Join-Path $targetRoot 'windows-package'
$inputRoot = Join-Path $packageRoot 'input'
$runtimeRoot = Join-Path $packageRoot 'runtime'
$appImageRoot = Join-Path $packageRoot 'ThreshCore'
$installerRoot = Join-Path $targetRoot 'installer'
$jarName = 'award-log-1.0-SNAPSHOT.jar'
$jarPath = Join-Path $targetRoot $jarName
$iconPath = Join-Path $PSScriptRoot 'ThreshCore.ico'
$desktopConfigTemplate = Join-Path $PSScriptRoot 'application-local.yml'
$version = '1.0.0'
$jpackageName = 'ThreshCore'

function Invoke-Step {
    param(
        [string]$Label,
        [scriptblock]$Action
    )

    Write-Host "[packaging] $Label" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "Build step failed: $Label (exit code $LASTEXITCODE)"
    }
}

function Remove-BuildDirectory {
    param([string]$Path)
    $absolute = [System.IO.Path]::GetFullPath($Path)
    $allowedRoot = [System.IO.Path]::GetFullPath($targetRoot) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside target: $absolute"
    }
    if (Test-Path -LiteralPath $absolute) {
        if ((Get-Item -LiteralPath $absolute).Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw "Refusing linked cleanup target: $absolute" }
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
}

function Resolve-Executable {
    param([string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "Executable not found: $Name. Install JDK 17, Node.js 20.19+ or 22.12+, and Maven."
    }
    return $command.Source
}

Push-Location $projectRoot
try {
    $null = Resolve-Executable 'java'
    $null = Resolve-Executable 'jlink'
    $null = Resolve-Executable 'jpackage'
    $null = Resolve-Executable 'mvn'
    $null = Resolve-Executable 'npm'

    if (-not (Test-Path $iconPath)) {
        throw "Windows icon was not found: $iconPath"
    }
    if (-not (Test-Path $desktopConfigTemplate)) {
        throw "Desktop config template was not found: $desktopConfigTemplate"
    }

    if (Test-Path $packageRoot) {
        Remove-BuildDirectory -Path $packageRoot
    }
    if (Test-Path $installerRoot) {
        Remove-BuildDirectory -Path $installerRoot
    }
    New-Item -ItemType Directory -Path $inputRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $installerRoot -Force | Out-Null

    if (-not $SkipFrontendBuild) {
        Push-Location $frontendRoot
        try {
            if (-not $SkipTests) { Invoke-Step 'Test frontend' { npm test } }
            Invoke-Step 'Build Vue frontend' { npm run build }
        }
        finally {
            Pop-Location
        }
    }

    $mavenArgs = @('--no-transfer-progress')
    $packagePhase = 'verify'
    if ($SkipTests) {
        Write-Warning 'Explicit SkipTests: this package is not release-verified.'
        $mavenArgs += @('-DskipTests', '-Djacoco.skip=true')
        $packagePhase = 'package'
    }

    Invoke-Step 'Compile backend and prepare classes' { mvn @mavenArgs compile }

    $staticRoot = Join-Path $targetRoot 'classes\static'
    if (Test-Path $staticRoot) {
        Remove-BuildDirectory -Path $staticRoot
    }
    New-Item -ItemType Directory -Path $staticRoot -Force | Out-Null
    Copy-Item -Path (Join-Path $frontendRoot 'dist\*') -Destination $staticRoot -Recurse -Force

    Invoke-Step 'Verify and package Spring Boot JAR with frontend assets' { mvn @mavenArgs $packagePhase }
    if (-not (Test-Path $jarPath)) {
        throw "Backend JAR was not generated: $jarPath"
    }

    $staticEntry = & jar tf $jarPath | Select-String 'BOOT-INF/classes/static/index.html'
    if (-not $staticEntry) {
        throw 'JAR does not contain BOOT-INF/classes/static/index.html; stopping packaging.'
    }

    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputRoot 'award-log.jar') -Force
    $configRoot = Join-Path $appImageRoot 'config'
    $inputConfigRoot = Join-Path $inputRoot 'config'
    New-Item -ItemType Directory -Path $inputConfigRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\application-local.example.yml') -Destination (Join-Path $inputConfigRoot 'application-local.example.yml') -Force

    $jlink = Resolve-Executable 'jlink'
    Invoke-Step 'Create bundled Java 17 runtime' {
        & $jlink `
            --add-modules 'java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.management,jdk.naming.dns,jdk.naming.rmi,jdk.unsupported,jdk.zipfs' `
            --strip-debug `
            --no-header-files `
            --no-man-pages `
            --compress=2 `
            --output $runtimeRoot
    }

    $jpackage = Resolve-Executable 'jpackage'
    Invoke-Step 'Create runnable Windows app image' {
        & $jpackage `
            --type app-image `
            --name $jpackageName `
            --app-version $version `
            --input $inputRoot `
            --main-jar 'award-log.jar' `
            --main-class 'org.springframework.boot.loader.launch.JarLauncher' `
            --runtime-image $runtimeRoot `
            --dest $packageRoot `
            --icon $iconPath `
            --java-options '-Dfile.encoding=UTF-8' `
            --java-options '-Xms256m' `
            --java-options '-Xmx768m' `
            --java-options '-XX:MaxMetaspaceSize=256m' `
            --java-options '-Dthreshcore.desktop.open-browser=true' `
            --java-options '-Dthreshcore.config.dir=$APPDIR\..\config' `
            --java-options '-Daward.config.dir=$APPDIR\..\config'
    }

    if (-not (Test-Path (Join-Path $appImageRoot 'ThreshCore.exe'))) {
        throw "Application launcher was not generated: $appImageRoot\ThreshCore.exe"
    }

    New-Item -ItemType Directory -Path $configRoot -Force | Out-Null
    Copy-Item -LiteralPath $desktopConfigTemplate -Destination (Join-Path $configRoot 'application-local.yml') -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\application-local.example.yml') -Destination (Join-Path $configRoot 'application-local.example.yml') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $appImageRoot 'INSTALL.md') -Force

    if ($PortableOnly) {
        Write-Host "[packaging] Portable app image generated: $appImageRoot" -ForegroundColor Green
        exit 0
    }

    if ([string]::IsNullOrWhiteSpace($WixBin)) {
        $wixCommand = Get-Command candle.exe -ErrorAction SilentlyContinue
        if ($wixCommand) {
            $WixBin = Split-Path $wixCommand.Source -Parent
        }
    }

    if ([string]::IsNullOrWhiteSpace($WixBin) -or -not (Test-Path (Join-Path $WixBin 'candle.exe'))) {
        $wixCache = Join-Path $targetRoot 'tools\wix314'
        $wixZip = Join-Path $targetRoot 'tools\wix314-binaries.zip'
        $wixUrl = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
        if (-not (Test-Path (Join-Path $wixCache 'candle.exe'))) {
            New-Item -ItemType Directory -Path (Split-Path $wixZip -Parent) -Force | Out-Null
            Write-Host '[packaging] WiX was not found; downloading the portable WiX 3.14 build tools.' -ForegroundColor Yellow
            Invoke-WebRequest -Uri $wixUrl -OutFile $wixZip
            if (Test-Path $wixCache) {
                Remove-Item -LiteralPath $wixCache -Recurse -Force
            }
            Expand-Archive -LiteralPath $wixZip -DestinationPath $wixCache -Force
        }
        $wixCandle = Get-ChildItem -LiteralPath $wixCache -Filter 'candle.exe' -Recurse | Select-Object -First 1
        if (-not $wixCandle) {
            throw 'candle.exe was not found in the portable WiX archive.'
        }
        $WixBin = $wixCandle.Directory.FullName
    }

    $env:PATH = "$WixBin;$env:PATH"
    Invoke-Step 'Create Windows EXE installer' {
        & $jpackage `
            --type exe `
            --app-image $appImageRoot `
            --name $jpackageName `
            --app-version $version `
            --dest $installerRoot `
            --win-menu `
            --win-menu-group 'ThreshCore' `
            --win-shortcut `
            --win-per-user-install `
            --win-dir-chooser
    }

    $installer = Get-ChildItem -LiteralPath $installerRoot -Filter '*.exe' | Select-Object -First 1
    if (-not $installer) {
        throw "EXE installer was not generated: $installerRoot"
    }

    Write-Host "[packaging] Installer generated: $($installer.FullName)" -ForegroundColor Green
    Write-Host "[packaging] Portable app image: $appImageRoot" -ForegroundColor Green
}
finally {
    Pop-Location
}
