param(
    [string]$RunDir = ''
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $RunId = 'build_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
    $RunDir = Join-Path (Join-Path $Root 'runs') $RunId
}
else {
    $RunId = Split-Path -Leaf $RunDir
}

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$ConsoleLog = Join-Path $RunDir 'console.log'
$ResultCodeFile = Join-Path $RunDir 'result_code.txt'
$RunTypeFile = Join-Path $RunDir 'run_type.txt'

Set-Content -Path $RunTypeFile -Value 'build' -Encoding UTF8

$TranscriptStarted = $false
$BuildRc = 1

try {
    try {
        Start-Transcript -Path $ConsoleLog -Force | Out-Null
        $TranscriptStarted = $true
    }
    catch {
        Add-Content -Path $ConsoleLog -Value ('Start-Transcript failed: ' + $_.Exception.Message) -Encoding UTF8
    }

    Write-Host ('Run directory: ' + $RunDir)
    Write-Host 'Console output, build artifacts and a short environment snapshot are preserved even on failure.'
    Write-Host ''

    try {
        & (Join-Path $PSScriptRoot 'build_windows.ps1') -RunDir $RunDir
        $BuildRc = 0
    }
    catch {
        $BuildRc = 1
        Write-Host ''
        Write-Host ('BUILD FAILED: ' + $_.Exception.Message) -ForegroundColor Red
        Write-Host $_.ScriptStackTrace
    }
}
finally {
    Set-Content -Path $ResultCodeFile -Value ([string]$BuildRc) -Encoding ASCII

    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch {}
    }

    try {
        & (Join-Path $PSScriptRoot 'publish_run.ps1') -RunDir $RunDir -ResultCode $BuildRc -RunType 'build'
        $PublishRc = $LASTEXITCODE
        if ($PublishRc -ne 0) {
            Write-Host ('Diagnostic upload returned exit code ' + $PublishRc + '. The local run folder is preserved.') -ForegroundColor Yellow
        }
    }
    catch {
        Add-Content -Path (Join-Path $RunDir 'publish.log') -Value ('Publisher exception: ' + $_.Exception.Message) -Encoding UTF8
        Write-Host ('Diagnostic upload failed: ' + $_.Exception.Message) -ForegroundColor Yellow
    }
}

exit $BuildRc
