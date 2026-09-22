$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$RunId = 'device_' + (Get-Date -Format 'yyyyMMdd_HHmmss')
$RunDir = Join-Path (Join-Path $Root 'runs') $RunId
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
Set-Content -Path (Join-Path $RunDir 'run_type.txt') -Value 'device' -Encoding UTF8

$TranscriptStarted = $false
$Rc = 1

function Find-Adb {
    $Candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $Candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:ANDROID_HOME) { $Candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA) { $Candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    $Candidates += 'C:\54\Dist\Progr\Android_Tools\platform-tools\adb.exe'
    foreach ($Candidate in $Candidates) {
        if ($Candidate -and (Test-Path $Candidate)) { return $Candidate }
    }
    $Cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($Cmd) { return $Cmd.Source }
    return $null
}

function Run-Adb([string]$Name, [string[]]$Args) {
    $Path = Join-Path $RunDir $Name
    try {
        $Output = & $script:Adb @Args 2>&1
        $Output | Out-File -FilePath $Path -Encoding UTF8
        return $Output
    }
    catch {
        ('ERROR: ' + $_.Exception.Message) | Out-File -FilePath $Path -Encoding UTF8
        return @()
    }
}

try {
    try {
        Start-Transcript -Path (Join-Path $RunDir 'console.log') -Force | Out-Null
        $TranscriptStarted = $true
    } catch {}

    Write-Host ('Run directory: ' + $RunDir)
    $script:Adb = Find-Adb
    if (-not $script:Adb) {
        throw 'adb.exe was not found. Install Android platform-tools or set ANDROID_SDK_ROOT.'
    }

    Write-Host ('ADB: ' + $script:Adb)
    Run-Adb 'adb_devices.txt' @('devices','-l') | Out-Null

    $DeviceLines = & $script:Adb devices 2>&1
    $Serials = @()
    foreach ($Line in $DeviceLines) {
        if ($Line -match '^([^\s]+)\s+device$') { $Serials += $Matches[1] }
    }
    if ($Serials.Count -eq 0) {
        throw 'No authorized ADB device is connected. Enable USB debugging on MK15 and authorize this PC.'
    }

    $Serial = $Serials[0]
    Set-Content -Path (Join-Path $RunDir 'device_serial.txt') -Value $Serial -Encoding ASCII
    Write-Host ('Using device: ' + $Serial)

    Run-Adb 'getprop.txt' @('-s',$Serial,'shell','getprop') | Out-Null
    Run-Adb 'packages.txt' @('-s',$Serial,'shell','pm list packages') | Out-Null
    Run-Adb 'ps.txt' @('-s',$Serial,'shell','ps -A') | Out-Null
    Run-Adb 'dumpsys_usb.txt' @('-s',$Serial,'shell','dumpsys usb') | Out-Null
    Run-Adb 'dumpsys_input.txt' @('-s',$Serial,'shell','dumpsys input') | Out-Null
    Run-Adb 'proc_input_devices.txt' @('-s',$Serial,'shell','cat /proc/bus/input/devices') | Out-Null
    Run-Adb 'proc_tty_drivers.txt' @('-s',$Serial,'shell','cat /proc/tty/drivers') | Out-Null
    Run-Adb 'dev_ports.txt' @('-s',$Serial,'shell','ls -l /dev/ttyHS0 /dev/ttyUSB* /dev/ttyACM* /dev/input/* 2>&1') | Out-Null
    Run-Adb 'net_interfaces.txt' @('-s',$Serial,'shell','ip addr 2>&1') | Out-Null
    Run-Adb 'net_sockets.txt' @('-s',$Serial,'shell','(ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null)') | Out-Null
    Run-Adb 'app_package.txt' @('-s',$Serial,'shell','dumpsys package com.mk15.portinspector') | Out-Null
    Run-Adb 'app_private_files.txt' @('-s',$Serial,'shell','run-as com.mk15.portinspector sh -c "find files -maxdepth 2 -type f -ls 2>&1"') | Out-Null

    $AppFiles = Join-Path $RunDir 'app_files'
    New-Item -ItemType Directory -Force -Path $AppFiles | Out-Null
    & $script:Adb -s $Serial pull '/sdcard/Android/data/com.mk15.portinspector/files' $AppFiles 2>&1 |
        Out-File -FilePath (Join-Path $RunDir 'adb_pull_app_files.txt') -Encoding UTF8

    & $script:Adb -s $Serial logcat -c 2>$null

    Write-Host ''
    Write-Host 'For the next 15 seconds move SA through all three positions repeatedly.' -ForegroundColor Cyan
    Write-Host 'Also move SB and SC once if convenient. This helps identify the changing event source.'
    Start-Sleep -Seconds 2

    $GetEventOut = Join-Path $RunDir 'getevent_live.txt'
    $GetEventErr = Join-Path $RunDir 'getevent_live.err.txt'
    $LogcatOut = Join-Path $RunDir 'logcat_live.txt'
    $LogcatErr = Join-Path $RunDir 'logcat_live.err.txt'

    $GetEvent = Start-Process -FilePath $script:Adb -ArgumentList @('-s',$Serial,'shell','getevent','-lt') -NoNewWindow -PassThru -RedirectStandardOutput $GetEventOut -RedirectStandardError $GetEventErr
    $Logcat = Start-Process -FilePath $script:Adb -ArgumentList @('-s',$Serial,'logcat','-v','threadtime') -NoNewWindow -PassThru -RedirectStandardOutput $LogcatOut -RedirectStandardError $LogcatErr

    Start-Sleep -Seconds 15
    foreach ($P in @($GetEvent,$Logcat)) {
        if ($P -and -not $P.HasExited) {
            try { Stop-Process -Id $P.Id -Force } catch {}
        }
    }

    Run-Adb 'getevent_capabilities.txt' @('-s',$Serial,'shell','getevent -pl') | Out-Null
    Run-Adb 'dumpsys_input_after.txt' @('-s',$Serial,'shell','dumpsys input') | Out-Null
    $Rc = 0
}
catch {
    $Rc = 1
    Write-Host ('DEVICE COLLECTION FAILED: ' + $_.Exception.Message) -ForegroundColor Red
}
finally {
    Set-Content -Path (Join-Path $RunDir 'result_code.txt') -Value ([string]$Rc) -Encoding ASCII
    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch {}
    }
    try {
        & (Join-Path $PSScriptRoot 'publish_run.ps1') -RunDir $RunDir -ResultCode $Rc -RunType 'device'
        if ($LASTEXITCODE -ne 0) {
            Write-Host ('Diagnostic upload returned exit code ' + $LASTEXITCODE + '. Local files are preserved.') -ForegroundColor Yellow
        }
    } catch {
        Write-Host ('Publisher failed: ' + $_.Exception.Message) -ForegroundColor Yellow
    }
}
exit $Rc
