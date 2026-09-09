param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [string]$MacroPath,
    [int]$Runs = 2,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $runtimeDir '..\..')).Path
$workDir = Join-Path $runtimeDir '_work'

if (-not $MacroPath) {
    $MacroPath = Join-Path $repoRoot 'aNMJ-morph macro.txt'
}
$MacroPath = (Resolve-Path -LiteralPath $MacroPath).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$fiji = Join-Path $FijiRoot 'fiji-windows-x64.exe'
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
if (-not (Test-Path -LiteralPath $fiji)) {
    throw "Could not find fresh Fiji executable: $fiji"
}
if (-not (Test-Path -LiteralPath $fijiLauncher)) {
    throw "Could not find Fiji launcher: $fijiLauncher"
}

$reference = Join-Path $repoRoot 'Reference Images\NMJ_1.lsm'
$builder = Join-Path $runtimeDir 'build_harnesses.py'
$fixtureBuilder = Join-Path $runtimeDir 'build_fixture_probes.py'
$fixtureValidator = Join-Path $runtimeDir 'validate_fixture_probe.py'
$validator = Join-Path $runtimeDir 'validate_outputs.py'
& python $builder --macro $MacroPath --reference $reference --work-dir $workDir
if ($LASTEXITCODE -ne 0) { throw 'Harness generation failed' }
& python $fixtureBuilder --macro $MacroPath --work-dir (Join-Path $workDir 'fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Fixture-probe generation failed' }

function Assert-NoFijiProcess {
    $existing = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
    if ($existing) {
        throw 'The validation Fiji installation is already running. Close it before automated validation.'
    }
}

function Stop-ValidationFijiProcesses {
    $running = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
    foreach ($item in $running) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Run-Harness([string]$Harness, [string]$Trace, [string]$Label, [string]$CompletionMarker) {
    Assert-NoFijiProcess
    if (Test-Path -LiteralPath $Trace) { Remove-Item -LiteralPath $Trace -Force }

    $quotedHarness = '"' + $Harness + '"'
    $process = Start-Process -FilePath $fijiLauncher -ArgumentList @('--console', '-port0', '-macro', $quotedHarness) -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $complete = $false

    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $Trace) {
            $traceText = Get-Content -LiteralPath $Trace -Raw
            if ($traceText -like "*$CompletionMarker*") {
                $complete = $true
                break
            }
        }
        $process.Refresh()
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 250
    }

    $process.Refresh()
    $cleanupDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $cleanupDeadline) {
        $runningFiji = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fiji }
        if (-not $runningFiji) { break }
        Start-Sleep -Milliseconds 250
    }
    Stop-ValidationFijiProcesses
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit()
    }
    if (-not $complete) {
        $tail = if (Test-Path -LiteralPath $Trace) { (Get-Content -LiteralPath $Trace -Tail 8) -join "`n" } else { '<no trace>' }
        throw "$Label did not reach completion marker '$CompletionMarker' within $TimeoutSeconds seconds.`n$tail"
    }
}

$fixtureHarness = Join-Path $workDir 'fixtures\fixture_probe.ijm'
$fixtureTrace = Join-Path $workDir 'fixtures\fixture_probe.txt'
$squareHarness = Join-Path $workDir 'square\aNMJ-morph-plus-e2e.ijm'
$squareTrace = Join-Path $workDir 'square\trace.txt'
$rectHarness = Join-Path $workDir 'rectangular\aNMJ-morph-plus-rect-e2e.ijm'
$rectTrace = Join-Path $workDir 'rectangular\trace.txt'
$bridgeHarness = Join-Path $workDir 'java-bridge\aNMJ-morph-plus-java-bridge-e2e.ijm'
$bridgeTrace = Join-Path $workDir 'java-bridge\trace.txt'

Run-Harness $fixtureHarness $fixtureTrace "fixture metadata probe" "DONE fixtures"
& python $fixtureValidator --trace $fixtureTrace
if ($LASTEXITCODE -ne 0) { throw 'Runtime fixture metadata validation failed' }

for ($i = 1; $i -le $Runs; $i++) {
    Run-Harness $squareHarness $squareTrace "square run $i" "DONE stage 7"
}
for ($i = 1; $i -le $Runs; $i++) {
    Run-Harness $rectHarness $rectTrace "rectangular run $i" "DONE stage 7"
}
Run-Harness $bridgeHarness $bridgeTrace "Java batch-argument bridge run" "DONE stage 7"

& python $validator --work-dir $workDir --runs $Runs
if ($LASTEXITCODE -ne 0) { throw 'Runtime output validation failed' }

Write-Output "aNMJ-morph+ fresh-Fiji runtime validation passed (fixture matrix + $Runs square + $Runs rectangular + Java batch-argument bridge run)."
