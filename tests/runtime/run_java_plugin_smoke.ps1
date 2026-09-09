param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workDir = Join-Path $runtimeDir '_work\java-plugin-smoke'
$harness = (Resolve-Path -LiteralPath (Join-Path $runtimeDir 'java_plugin_smoke.ijm')).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
$pluginsDir = Join-Path $FijiRoot 'plugins'
$installedJar = Join-Path $pluginsDir 'aNMJ-morph-plus.jar'
$trace = Join-Path $workDir 'trace.txt'

if (-not (Test-Path -LiteralPath $fiji)) {
    throw "Could not find fresh Fiji executable: $fiji"
}
if (-not (Test-Path -LiteralPath $fijiLauncher)) {
    throw "Could not find Fiji launcher: $fijiLauncher"
}

New-Item -ItemType Directory -Force -Path $workDir | Out-Null
Copy-Item -LiteralPath $PluginJar -Destination $installedJar -Force
if (Test-Path -LiteralPath $trace) {
    Remove-Item -LiteralPath $trace -Force
}

$existing = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
if ($existing) {
    throw 'The validation Fiji installation is already running. Close it before automated validation.'
}

$quotedHarness = '"' + $harness + '"'
$quotedTrace = '"' + $trace + '"'
$process = Start-Process -FilePath $fijiLauncher -ArgumentList @('--headless', '--console', '-port0', '-macro', $quotedHarness, $quotedTrace) -PassThru -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$complete = $false

try {
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $trace) {
            $traceText = Get-Content -LiteralPath $trace -Raw
            if ($traceText -like '*DONE java plugin smoke*') {
                $complete = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
} finally {
    $runningFiji = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
    foreach ($item in $runningFiji) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit()
    }
}

if (-not $complete) {
    $tail = if (Test-Path -LiteralPath $trace) { (Get-Content -LiteralPath $trace -Tail 8) -join "`n" } else { '<no trace>' }
    throw "Java plugin smoke test did not complete within $TimeoutSeconds seconds.`n$tail"
}

Write-Output 'aNMJ-morph+ Java plugin fresh-Fiji smoke passed.'
