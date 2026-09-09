param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar,
    [string]$MacroPath,
    [int]$Runs = 2,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$installedPlugin = Join-Path (Join-Path $FijiRoot 'plugins') ("anmj-morph-plus-runtime-" + [Guid]::NewGuid().ToString('N') + '.jar')

function Get-PinnedFijiProcesses {
    @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji })
}

Copy-Item -LiteralPath $PluginJar -Destination $installedPlugin
$validationError = $null
try {
    $runner = Join-Path $runtimeDir 'run_validation.ps1'
    if ($MacroPath) {
        & $runner -FijiRoot $FijiRoot -MacroPath $MacroPath -Runs $Runs -TimeoutSeconds $TimeoutSeconds
    } else {
        & $runner -FijiRoot $FijiRoot -Runs $Runs -TimeoutSeconds $TimeoutSeconds
    }
    if ($LASTEXITCODE -ne 0) { throw 'Fresh-Fiji runtime validation failed' }
} catch {
    $validationError = $_
} finally {
    foreach ($item in (Get-PinnedFijiProcesses)) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
    $cleanupDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $cleanupDeadline -and (Get-PinnedFijiProcesses).Count -ne 0) {
        Start-Sleep -Milliseconds 200
    }
    $deleteDeadline = (Get-Date).AddSeconds(5)
    while ((Test-Path -LiteralPath $installedPlugin) -and (Get-Date) -lt $deleteDeadline) {
        Remove-Item -LiteralPath $installedPlugin -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $installedPlugin) {
            Start-Sleep -Milliseconds 200
        }
    }
}

if ($validationError) { throw $validationError }
if ((Get-PinnedFijiProcesses).Count -ne 0) { throw 'Plugin-backed runtime validation left Fiji running' }
if (Test-Path -LiteralPath $installedPlugin) { throw 'Could not remove temporary plugin JAR after runtime validation' }
