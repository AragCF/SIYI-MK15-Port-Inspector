$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$RunsRoot = Join-Path $Root 'runs'

if (-not (Test-Path $RunsRoot)) {
    Write-Host 'No runs directory exists yet.' -ForegroundColor Yellow
    exit 1
}

$Latest = Get-ChildItem -Path $RunsRoot -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Latest) {
    Write-Host 'No local run directories were found.' -ForegroundColor Yellow
    exit 1
}

$ResultCode = 1
$ResultFile = Join-Path $Latest.FullName 'result_code.txt'
if (Test-Path $ResultFile) {
    $RawResult = (Get-Content $ResultFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $ParsedResult = 0
    if ([int]::TryParse([string]$RawResult, [ref]$ParsedResult)) {
        $ResultCode = $ParsedResult
    }
}

$RunType = 'unknown'
$RunTypeFile = Join-Path $Latest.FullName 'run_type.txt'
if (Test-Path $RunTypeFile) {
    $RawType = (Get-Content $RunTypeFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($RawType) {
        $RunType = ([string]$RawType).Trim()
    }
}

Write-Host ('Retrying publish for: ' + $Latest.FullName)
& (Join-Path $PSScriptRoot 'publish_run.ps1') -RunDir $Latest.FullName -ResultCode $ResultCode -RunType $RunType
exit $LASTEXITCODE
