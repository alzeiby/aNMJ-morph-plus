param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar,
    [ValidateSet('original', 'template')]
    [string]$Site = 'original',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $runtimeDir '..\..')).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
$builder = Join-Path $runtimeDir 'build_split_channels_failclosed_probe.py'
$workDir = Join-Path $runtimeDir ("_work\split-channels-failclosed\" + $Site)
$installedPlugin = Join-Path (Join-Path $FijiRoot 'plugins') ("anmj-morph-plus-split-failclosed-" + [Guid]::NewGuid().ToString('N') + '.jar')

function Get-PinnedFijiProcesses {
    @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji })
}

if ((Get-PinnedFijiProcesses).Count -ne 0) {
    throw 'The validation Fiji installation is already running. Close it before Java Split fail-closed validation.'
}

& python $builder --macro (Join-Path $repoRoot 'aNMJ-morph macro.txt') --work-dir $workDir --site $Site
if ($LASTEXITCODE -ne 0) { throw 'Could not generate Java Split fail-closed probe' }
$probe = Join-Path $workDir 'split-channels-failclosed.ijm'
$trace = Join-Path $workDir 'trace.txt'
if (Test-Path -LiteralPath $trace) { Remove-Item -LiteralPath $trace -Force }

Copy-Item -LiteralPath $PluginJar -Destination $installedPlugin
try {
    $quotedProbe = '"' + $probe + '"'
    $wrapper = Start-Process -FilePath $fijiLauncher -ArgumentList @('--console', '-port0', '-macro', $quotedProbe) -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $reached = $false
    $failureMarker = if ($Site -eq 'original') { 'FAIL-CLOSED JAVA ORIGINAL SPLIT: ERROR: ' } else { 'FAIL-CLOSED JAVA TEMPLATE SPLIT: ERROR: ' }
    $attemptMarker = if ($Site -eq 'original') { 'ATTEMPT JAVA ORIGINAL SPLIT' } else { 'ATTEMPT JAVA TEMPLATE SPLIT' }
    $fallbackMarker = if ($Site -eq 'original') { 'LEGACY ORIGINAL SPLIT FALLBACK RAN' } else { 'LEGACY TEMPLATE SPLIT FALLBACK RAN' }
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $trace) {
            $text = Get-Content -LiteralPath $trace -Raw
            if ($text -like ("*" + $failureMarker + "*")) {
                $reached = $true
                break
            }
        }
        Start-Sleep -Milliseconds 200
    }
    $text = if (Test-Path -LiteralPath $trace) { Get-Content -LiteralPath $trace -Raw } else { '' }
    if (-not $reached) { throw "Java Split fail-closed probe did not reach exact error marker.`n$text" }
    if ($text -notlike ("*" + $attemptMarker + "*")) { throw "Java Split fail-closed probe did not attempt the $Site Java call" }
    if ($text -like ("*" + $fallbackMarker + "*")) { throw "Java $Site Split failure incorrectly entered legacy fallback" }
    if ($text -like '*UNEXPECTED RETURN*') { throw 'Java Split fail-closed parser unexpectedly returned' }
} finally {
    foreach ($item in (Get-PinnedFijiProcesses)) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
    $cleanupDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $cleanupDeadline -and (Get-PinnedFijiProcesses).Count -ne 0) {
        Start-Sleep -Milliseconds 200
    }
    Start-Sleep -Milliseconds 300
    if (Test-Path -LiteralPath $installedPlugin) { Remove-Item -LiteralPath $installedPlugin -Force }
}

if ((Get-PinnedFijiProcesses).Count -ne 0) { throw 'Java Split fail-closed probe left Fiji running' }
Write-Output "aNMJ-morph+ Java $Site Split Channels fail-closed fresh-Fiji probe passed."
