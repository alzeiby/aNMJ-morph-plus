param(
    [Parameter(Mandatory=$true)]
    [string]$FijiRoot,
    [Parameter(Mandatory=$true)]
    [string]$PluginJar
)

$ErrorActionPreference = 'Stop'
$runtimeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workDir = Join-Path $runtimeDir '_work\java-plugin-smoke'
$source = (Resolve-Path -LiteralPath (Join-Path $runtimeDir 'JavaPluginSmoke.java')).Path
$FijiRoot = (Resolve-Path -LiteralPath $FijiRoot).Path
$PluginJar = (Resolve-Path -LiteralPath $PluginJar).Path
$pluginsDir = Join-Path $FijiRoot 'plugins'
$installedPlugin = Join-Path $pluginsDir ("anmj-morph-plus-runtime-smoke-" + [Guid]::NewGuid().ToString('N') + '.jar')

$java = Get-ChildItem (Join-Path $FijiRoot 'java\win64') -Recurse -Filter 'java.exe' |
    Select-Object -First 1
$javac = Get-ChildItem (Join-Path $FijiRoot 'java\win64') -Recurse -Filter 'javac.exe' |
    Select-Object -First 1
if (-not $java -or -not $javac) {
    throw 'Could not find the Java runtime bundled with the pinned Fiji installation.'
}

New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$classFile = Join-Path $workDir 'JavaPluginSmoke.class'
if (Test-Path -LiteralPath $classFile) {
    Remove-Item -LiteralPath $classFile -Force
}

$separator = [IO.Path]::PathSeparator
Copy-Item -LiteralPath $PluginJar -Destination $installedPlugin
try {
    $classpath = @(
        (Join-Path $FijiRoot 'jars\*'),
        (Join-Path $FijiRoot 'plugins\*'),
        $workDir
    ) -join $separator

    & $javac.FullName '-proc:none' '-cp' $classpath '-d' $workDir $source
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not compile Java plugin smoke against the pinned Fiji runtime.'
    }

    $output = & $java.FullName '-Djava.awt.headless=true' '-cp' $classpath 'JavaPluginSmoke' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Java plugin smoke failed:`n$($output -join "`n")"
    }
    if (($output -join "`n") -notlike '*DONE java plugin smoke*') {
        throw "Java plugin smoke did not report completion:`n$($output -join "`n")"
    }
} finally {
    if (Test-Path -LiteralPath $installedPlugin) {
        Remove-Item -LiteralPath $installedPlugin -Force
    }
}

Write-Output 'aNMJ-morph+ Java plugin fresh-Fiji smoke passed.'
