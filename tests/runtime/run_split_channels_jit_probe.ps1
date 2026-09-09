param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
$probe = (Resolve-Path -LiteralPath (Join-Path $runtimeDir 'probes\split_channels_jit.ijm')).Path
$workDir = Join-Path $runtimeDir '_work\split-channels-jit'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$trace = Join-Path $workDir 'trace.txt'
$installedPlugin = Join-Path (Join-Path $FijiRoot 'plugins') ("anmj-morph-plus-split-probe-" + [Guid]::NewGuid().ToString('N') + '.jar')

function Get-PinnedFijiProcesses {
    @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji })
}

if ((Get-PinnedFijiProcesses).Count -ne 0) {
    throw 'The validation Fiji installation is already running. Close it before Split Channels JIT validation.'
}
if (Test-Path -LiteralPath $trace) { Remove-Item -LiteralPath $trace -Force }

Copy-Item -LiteralPath $PluginJar -Destination $installedPlugin
try {
    $quotedProbe = '"' + $probe + '"'
    $quotedTrace = '"' + $trace + '"'
    $wrapper = Start-Process -FilePath $fijiLauncher -ArgumentList @('--console', '-port0', '-macro', $quotedProbe, $quotedTrace) -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $complete = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $trace) {
            $text = Get-Content -LiteralPath $trace -Raw
            if ($text -like '*DONE split channels jit*') {
                $complete = $true
                break
            }
        }
        Start-Sleep -Milliseconds 200
    }

    $cleanupDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $cleanupDeadline -and (Get-PinnedFijiProcesses).Count -ne 0) {
        Start-Sleep -Milliseconds 200
    }
    foreach ($item in (Get-PinnedFijiProcesses)) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if (-not $wrapper.HasExited) {
        Stop-Process -Id $wrapper.Id -Force -ErrorAction SilentlyContinue
        $wrapper.WaitForExit()
    }
    if (-not $complete) {
        $tail = if (Test-Path -LiteralPath $trace) { (Get-Content -LiteralPath $trace -Tail 12) -join "`n" } else { '<no trace>' }
        throw "Split Channels JIT probe did not complete within $TimeoutSeconds seconds.`n$tail"
    }
    $text = Get-Content -LiteralPath $trace -Raw
    foreach ($marker in @('ORIGINAL|', 'TEMPLATE|', 'FAIL-CLOSED|ERROR: ', 'DONE split channels jit')) {
        if ($text -notlike "*$marker*") { throw "Split Channels JIT probe missing marker: $marker" }
    }
    if ((Get-PinnedFijiProcesses).Count -ne 0) { throw 'Split Channels JIT probe left Fiji running' }
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

Write-Output 'aNMJ-morph+ Split Channels JIT fresh-Fiji probe passed.'
