param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $runtimeDir '..\..')).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$fijiLauncher = Join-Path $FijiRoot 'fiji.bat'
$builder = Join-Path $runtimeDir 'build_template_copy_failclosed_probes.py'
$workDir = Join-Path $runtimeDir '_work\template-copy-failclosed'
$macro = Join-Path $repoRoot 'aNMJ-morph macro.txt'

function Get-PinnedFijiProcesses {
    $prefix = $FijiRoot.TrimEnd('\') + '\'
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    })
}

function Assert-NoPinnedFijiProcess {
    $running = Get-PinnedFijiProcesses
    if ($running.Count -ne 0) {
        throw "Pinned Fiji runtime still has $($running.Count) process(es) running"
    }
}

function Stop-PinnedFijiProcesses {
    foreach ($item in (Get-PinnedFijiProcesses)) {
        Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Run-FailClosedProbe(
    [string]$Harness,
    [string]$Trace,
    [string]$ExpectedMarker,
    [string]$SuppliedMarker,
    [string]$Label
) {
    Assert-NoPinnedFijiProcess
    if (Test-Path -LiteralPath $Trace) { Remove-Item -LiteralPath $Trace -Force }
    $quotedHarness = '"' + $Harness + '"'
    $wrapper = Start-Process -FilePath $fijiLauncher -ArgumentList @('--headless', '--console', '-port0', '-macro', $quotedHarness) -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $reached = $false
        while ((Get-Date) -lt $deadline) {
            if (Test-Path -LiteralPath $Trace) {
                $traceText = Get-Content -LiteralPath $Trace -Raw
                if ($traceText -like "*$ExpectedMarker*") {
                    $reached = $true
                    break
                }
            }
            Start-Sleep -Milliseconds 200
        }
        if (-not $reached) {
            $tail = if (Test-Path -LiteralPath $Trace) { (Get-Content -LiteralPath $Trace -Tail 10) -join "`n" } else { '<no trace>' }
            throw "$Label did not reach exact fail-closed marker within $TimeoutSeconds seconds.`n$tail"
        }
        $traceText = Get-Content -LiteralPath $Trace -Raw
        if ($traceText -notlike "*$SuppliedMarker*") { throw "$Label did not exercise the requested supplied-ID case" }
        if ($traceText -like '*TEMPLATE FALLBACK DUPLICATE RAN*') { throw "$Label incorrectly entered the legacy template-copy fallback" }
        if ($traceText -like '*UNEXPECTED RETURN*') { throw "$Label returned from the fail-closed production parser" }

        foreach ($item in (Get-PinnedFijiProcesses)) {
            $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
            if ($process -and $process.MainWindowHandle -ne 0) {
                throw "$Label exposed a visible Fiji runtime window"
            }
        }
    } finally {
        Stop-PinnedFijiProcesses
        if (-not $wrapper.HasExited) {
            Stop-Process -Id $wrapper.Id -Force -ErrorAction SilentlyContinue
            $wrapper.WaitForExit()
        }
    }
    Assert-NoPinnedFijiProcess
    Write-Output "$Label passed exact fail-closed/fallback/hidden cleanup checks."
}

& python $builder --macro $macro --work-dir $workDir
if ($LASTEXITCODE -ne 0) { throw 'Could not generate template-copy fail-closed probes' }

Run-FailClosedProbe `
    (Join-Path $workDir 'template-copy-unresolved.ijm') `
    (Join-Path $workDir 'unresolved.txt') `
    'FAIL-CLOSED UNRESOLVED: Error: Invalid template-copy-id supplied by Java' `
    'SUPPLIED UNRESOLVED TEMPLATE ID' `
    'unresolved template-copy-id probe'

Run-FailClosedProbe `
    (Join-Path $workDir 'template-copy-wrong-title.ijm') `
    (Join-Path $workDir 'wrong-title.txt') `
    'FAIL-CLOSED WRONG-TITLE: Error: Invalid template-copy-id supplied by Java' `
    'SUPPLIED WRONG-TITLE TEMPLATE ID' `
    'wrong-title template-copy-id probe'

Write-Output 'aNMJ-morph+ template-copy fail-closed fresh-Fiji probes passed.'
