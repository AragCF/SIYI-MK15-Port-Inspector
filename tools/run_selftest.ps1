param(
    [string]$RunDir = ''
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $RunId = 'selftest_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
    $RunDir = Join-Path (Join-Path $Root 'runs') $RunId
}

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$ConsoleLog = Join-Path $RunDir 'console.log'
Set-Content -Path (Join-Path $RunDir 'run_type.txt') -Value 'selftest' -Encoding UTF8

$TranscriptStarted = $false
$Rc = 1

try {
    try {
        Start-Transcript -Path $ConsoleLog -Force | Out-Null
        $TranscriptStarted = $true
    }
    catch {
        Add-Content -Path $ConsoleLog -Value ('Start-Transcript failed: ' + $_.Exception.Message) -Encoding UTF8
    }

    $JavaHome = $env:JAVA_HOME
    if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
        $DefaultJdk = 'C:\Program Files\Android\Android Studio\jbr'
        if (Test-Path (Join-Path $DefaultJdk 'bin\javac.exe')) {
            $JavaHome = $DefaultJdk
        }
    }

    if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
        throw 'JDK was not found. Install Android Studio or set JAVA_HOME.'
    }

    $HostBuild = Join-Path $Root '.host-test-build'
    if (Test-Path $HostBuild) {
        Remove-Item -Recurse -Force $HostBuild
    }
    New-Item -ItemType Directory -Force -Path $HostBuild | Out-Null

    $Javac = Join-Path $JavaHome 'bin\javac.exe'
    $Java = Join-Path $JavaHome 'bin\java.exe'
    $ProtocolSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\SiyiProtocol.java'
    $DiffSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\ProbeDiffEngine.java'
    $ProtocolTest = Join-Path $Root 'host-tests\ProtocolSelfTest.java'
    $DiffTest = Join-Path $Root 'host-tests\ProbeDiffSelfTest.java'

    & $Javac -encoding UTF-8 -d $HostBuild $ProtocolSource $DiffSource $ProtocolTest $DiffTest
    if ($LASTEXITCODE -ne 0) {
        throw ('javac returned exit code ' + $LASTEXITCODE)
    }

    & $Java -cp $HostBuild ProtocolSelfTest
    if ($LASTEXITCODE -ne 0) {
        throw ('ProtocolSelfTest returned exit code ' + $LASTEXITCODE)
    }

    & $Java -cp $HostBuild ProbeDiffSelfTest
    if ($LASTEXITCODE -ne 0) {
        throw ('ProbeDiffSelfTest returned exit code ' + $LASTEXITCODE)
    }

    $Rc = 0
}
catch {
    $Rc = 1
    Write-Host ('SELF-TEST FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host $_.ScriptStackTrace
}
finally {
    Set-Content -Path (Join-Path $RunDir 'result_code.txt') -Value ([string]$Rc) -Encoding ASCII

    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch {}
    }

    try {
        & (Join-Path $PSScriptRoot 'publish_run.ps1') -RunDir $RunDir -ResultCode $Rc -RunType 'selftest'
        if ($LASTEXITCODE -ne 0) {
            Write-Host ('Diagnostic upload returned exit code ' + $LASTEXITCODE + '. The local run folder is preserved.') -ForegroundColor Yellow
        }
    }
    catch {
        Add-Content -Path (Join-Path $RunDir 'publish.log') -Value ('Publisher exception: ' + $_.Exception.Message) -Encoding UTF8
    }
}

exit $Rc
