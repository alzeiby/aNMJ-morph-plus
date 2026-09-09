param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [string]$MacroPath,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $runtimeDir '..\..')).Path
$workDir = Join-Path $runtimeDir '_work\native_headless'

if (-not $MacroPath) {
    $MacroPath = Join-Path $repoRoot 'aNMJ-morph macro.txt'
}
$MacroPath = (Resolve-Path -LiteralPath $MacroPath).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
if (-not (Test-Path -LiteralPath $fiji) -or -not (Test-Path -LiteralPath $fijiLauncher)) {
    throw "Fresh Fiji launcher not found under $FijiRoot"
}

$existing = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
if ($existing) {
    throw 'The validation Fiji installation is already running. Close it before headless validation.'
}

$builder = Join-Path $runtimeDir 'build_fixture_probes.py'
$validator = Join-Path $runtimeDir 'validate_fixture_probe.py'
& python $builder --macro $MacroPath --work-dir $workDir --native-only
if ($LASTEXITCODE -ne 0) { throw 'Native fixture-probe generation failed' }

$probe = Join-Path $workDir 'fixture_probe_native.ijm'
$trace = Join-Path $workDir 'fixture_probe.txt'
if (Test-Path -LiteralPath $trace) { Remove-Item -LiteralPath $trace -Force }

$quotedProbe = '"' + $probe + '"'
$process = Start-Process -FilePath $fijiLauncher -ArgumentList @('--headless', '--console', '-port0', '-macro', $quotedProbe) -PassThru -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$complete = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $trace) {
        $traceText = Get-Content -LiteralPath $trace -Raw
        if ($traceText -like '*DONE native fixtures*') {
            $complete = $true
            break
        }
    }
    $process.Refresh()
    if ($process.HasExited) { break }
    Start-Sleep -Milliseconds 250
}

if (-not $process.HasExited) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}
$running = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
foreach ($item in $running) {
    Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
}

if (-not $complete) {
    $tail = if (Test-Path -LiteralPath $trace) { (Get-Content -LiteralPath $trace -Tail 8) -join "`n" } else { '<no trace>' }
    throw "Headless native fixture probe did not complete within $TimeoutSeconds seconds.`n$tail"
}

& python $validator --trace $trace --native-only
if ($LASTEXITCODE -ne 0) { throw 'Headless native fixture validation failed' }
Write-Output 'aNMJ-morph+ headless native fixture validation passed.'
