param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar,
    [int]$Runs = 2,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $runtimeDir '..\..')).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$source = (Resolve-Path -LiteralPath (Join-Path $runtimeDir 'DirectJavaAnalysisRuntime.java')).Path
$validator = (Resolve-Path -LiteralPath (Join-Path $runtimeDir 'validate_direct_java_outputs.py')).Path
$reference = (Resolve-Path -LiteralPath (Join-Path $repoRoot 'Reference Images\NMJ_1.lsm')).Path
$workDir = Join-Path $runtimeDir '_work\direct-java-analysis'
$classesDir = Join-Path $workDir 'classes'
$strippedJar = Join-Path $workDir 'anmj-morph-plus-no-legacy-macro.jar'
$stdout = Join-Path $workDir 'stdout.txt'
$stderr = Join-Path $workDir 'stderr.txt'

if (Test-Path -LiteralPath $workDir) {
    $resolvedWork = (Resolve-Path -LiteralPath $workDir).Path
    $runtimePrefix = $runtimeDir.TrimEnd('\') + '\'
    if (-not $resolvedWork.StartsWith($runtimePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean direct-Java work directory outside runtime tree: $resolvedWork"
    }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$java = Get-ChildItem (Join-Path $FijiRoot 'java\win64') -Recurse -Filter 'java.exe' | Select-Object -First 1
$javac = Get-ChildItem (Join-Path $FijiRoot 'java\win64') -Recurse -Filter 'javac.exe' | Select-Object -First 1
if (-not $java -or -not $javac) {
    throw 'Could not find the Java runtime bundled with the pinned Fiji installation.'
}

$sourceText = Get-Content -LiteralPath $source -Raw
foreach ($forbidden in @('IJ.runMacro', 'LegacyMacroRunner', 'WaitForUserDialog', 'GenericDialog', '.ijm')) {
    if ($sourceText.Contains($forbidden)) {
        throw "Direct-Java runtime harness contains forbidden macro/dialog dependency: $forbidden"
    }
}

# Build a test-only copy of the production JAR with the canonical IJM resource physically removed.
# A successful analysis with this JAR proves the direct scientific path has no runtime macro dependency.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inputArchive = [System.IO.Compression.ZipFile]::OpenRead($PluginJar)
$outputArchive = $null
try {
    $outputArchive = [System.IO.Compression.ZipFile]::Open($strippedJar, [System.IO.Compression.ZipArchiveMode]::Create)
    foreach ($entry in $inputArchive.Entries) {
        if ($entry.FullName -eq 'legacy/aNMJ-morph macro.txt') { continue }
        $newEntry = $outputArchive.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        if ($entry.FullName.EndsWith('/')) { continue }
        $inputStream = $entry.Open()
        $outputStream = $newEntry.Open()
        try {
            $inputStream.CopyTo($outputStream)
        } finally {
            $outputStream.Dispose()
            $inputStream.Dispose()
        }
    }
} finally {
    if ($outputArchive) { $outputArchive.Dispose() }
    $inputArchive.Dispose()
}

$checkArchive = [System.IO.Compression.ZipFile]::OpenRead($strippedJar)
try {
    if ($checkArchive.GetEntry('legacy/aNMJ-morph macro.txt')) {
        throw 'Failed to remove legacy macro resource from direct-Java runtime JAR'
    }
} finally {
    $checkArchive.Dispose()
}

$separator = [IO.Path]::PathSeparator
$fijiPluginJars = Get-ChildItem (Join-Path $FijiRoot 'plugins') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'anmj-morph-plus-*.jar' } |
    ForEach-Object { $_.FullName }
$classpath = @(
    $strippedJar,
    (Join-Path $FijiRoot 'jars\*'),
    $fijiPluginJars,
    $classesDir
) | ForEach-Object { $_ }
$classpath = $classpath -join $separator

& $javac.FullName '-proc:none' '-cp' $classpath '-d' $classesDir $source
if ($LASTEXITCODE -ne 0) {
    throw 'Could not compile direct-Java analysis runtime harness against pinned Fiji.'
}

$arguments = @(
    "-Dplugins.dir=$FijiRoot",
    '-Djava.awt.headless=false',
    '-cp',
    ('"' + $classpath + '"'),
    'io.github.alzeiby.anmjmorphplus.DirectJavaAnalysisRuntime',
    ('"' + $reference + '"'),
    ('"' + $workDir + '"'),
    "$Runs"
)
$process = Start-Process -FilePath $java.FullName -ArgumentList $arguments -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while (-not $process.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 250
    $process.Refresh()
}
if (-not $process.HasExited) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    $process.WaitForExit()
    $tail = if (Test-Path -LiteralPath $stderr) { (Get-Content -LiteralPath $stderr -Tail 20) -join "`n" } else { '<no stderr>' }
    throw "Direct-Java analysis runtime timed out after $TimeoutSeconds seconds.`n$tail"
}
$process.WaitForExit()
$process.Refresh()
$exitCode = $process.ExitCode
if ($null -ne $exitCode -and $exitCode -ne 0) {
    $out = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Raw } else { '<no stdout>' }
    $err = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { '<no stderr>' }
    throw "Direct-Java analysis runtime failed with exit code $exitCode.`nSTDOUT:`n$out`nSTDERR:`n$err"
}

$output = Get-Content -LiteralPath $stdout -Raw
if ($output -notlike '*DONE direct Java analysis runtime*') {
    throw "Direct-Java analysis runtime did not report completion.`n$output"
}

& python $validator --work-dir $workDir --runs $Runs
if ($LASTEXITCODE -ne 0) {
    throw 'Direct-Java output validation failed.'
}

Write-Output "aNMJ-morph+ direct-Java fresh-Fiji analysis passed ($Runs square + $Runs rectangular + $Runs anisotropic, unchanged isotropic NMJ_1 oracle, no legacy macro resource)."
