$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$LogPath = Join-Path $Root 'build_windows.log'
$OutDir = Join-Path $Root 'out'
$ApkSource = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$ApkTarget = Join-Path $OutDir 'MK15PortInspector-1.0.0-debug.apk'
$ToolsDir = Join-Path $Root '.tools'
$GradleVersion = '8.7'
$GradleZip = Join-Path $ToolsDir "gradle-$GradleVersion-bin.zip"
$GradleHome = Join-Path $ToolsDir "gradle-$GradleVersion"
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'

function Fail([string]$Message) {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $Message) -ForegroundColor Red
    throw $Message
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }
    $AndroidStudioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $AndroidStudioJbr 'bin\java.exe')) {
        return $AndroidStudioJbr
    }
    return $null
}

function Find-AndroidSdk {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:LOCALAPPDATA) {
        $DefaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path $DefaultSdk) { return $DefaultSdk }
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null

try {
    Start-Transcript -Path $LogPath -Force | Out-Null
    Write-Host '=== MK15 Port Inspector: сборка Windows ==='
    Write-Host ('Проект: ' + $Root)

    $JavaHome = Find-JavaHome
    if (-not $JavaHome) {
        Fail 'Не найден JDK. Установите Android Studio и задайте JAVA_HOME на C:\Program Files\Android\Android Studio\jbr либо на JDK 17.'
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    Write-Host ('JAVA_HOME=' + $env:JAVA_HOME)
    & (Join-Path $JavaHome 'bin\java.exe') -version

    $Sdk = Find-AndroidSdk
    if (-not $Sdk) {
        Fail 'Не найден Android SDK. Установите Android Studio / SDK и задайте ANDROID_SDK_ROOT.'
    }
    $env:ANDROID_SDK_ROOT = $Sdk
    $env:ANDROID_HOME = $Sdk
    Write-Host ('ANDROID_SDK_ROOT=' + $Sdk)

    $AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
    $Aapt2 = Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'
    if (-not (Test-Path $AndroidJar) -or -not (Test-Path $Aapt2)) {
        Write-Host ''
        Write-Host 'Не установлены Android SDK Platform 34 и/или Build-Tools 34.0.0.' -ForegroundColor Yellow
        Write-Host 'В Android Studio откройте SDK Manager и установите Android 14 (API 34) + Build-Tools 34.0.0, либо выполните:'
        Write-Host '  sdkmanager.bat "platforms;android-34" "build-tools;34.0.0" "platform-tools"'
        Fail 'Не хватает компонентов Android SDK для сборки.'
    }

    # Быстрый независимый тест кодека/CRC протокола до Android-сборки.
    $HostBuild = Join-Path $Root '.host-test-build'
    if (Test-Path $HostBuild) { Remove-Item -Recurse -Force $HostBuild }
    New-Item -ItemType Directory -Force -Path $HostBuild | Out-Null
    $Javac = Join-Path $JavaHome 'bin\javac.exe'
    $Java = Join-Path $JavaHome 'bin\java.exe'
    & $Javac -encoding UTF-8 -d $HostBuild `
        (Join-Path $Root 'app\src\main\java\com\mk15\portinspector\SiyiProtocol.java') `
        (Join-Path $Root 'host-tests\ProtocolSelfTest.java')
    if ($LASTEXITCODE -ne 0) { Fail 'Не прошёл javac для теста протокола.' }
    & $Java -cp $HostBuild ProtocolSelfTest
    if ($LASTEXITCODE -ne 0) { Fail 'Не прошёл ProtocolSelfTest.' }

    $GradleCmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($GradleCmd) {
        $Gradle = $GradleCmd.Source
        Write-Host ('Используется Gradle из PATH: ' + $Gradle)
    } else {
        if (-not (Test-Path $GradleBat)) {
            Write-Host "Gradle $GradleVersion не найден. Загружаю официальную сборку..."
            if (-not (Test-Path $GradleZip)) {
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
                Invoke-WebRequest -Uri $Url -OutFile $GradleZip -UseBasicParsing
            }
            if (Test-Path $GradleHome) { Remove-Item -Recurse -Force $GradleHome }
            Expand-Archive -Path $GradleZip -DestinationPath $ToolsDir -Force
        }
        if (-not (Test-Path $GradleBat)) { Fail 'Gradle распакован, но gradle.bat не найден.' }
        $Gradle = $GradleBat
        Write-Host ('Используется локальный Gradle: ' + $Gradle)
    }

    Push-Location $Root
    try {
        & $Gradle ':app:assembleDebug' '--no-daemon' '--stacktrace'
        if ($LASTEXITCODE -ne 0) { Fail ('Gradle завершился с кодом ' + $LASTEXITCODE) }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path $ApkSource)) { Fail ('Gradle сообщил успех, но APK не найден: ' + $ApkSource) }
    Copy-Item -Force $ApkSource $ApkTarget
    $Hash = (Get-FileHash -Algorithm SHA256 $ApkTarget).Hash

    Write-Host ''
    Write-Host 'СБОРКА УСПЕШНА.' -ForegroundColor Green
    Write-Host ('APK: ' + $ApkTarget)
    Write-Host ('SHA256: ' + $Hash)
    Write-Host ('Журнал: ' + $LogPath)
}
catch {
    Write-Host ''
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ('Полный журнал: ' + $LogPath)
    exit 1
}
finally {
    try { Stop-Transcript | Out-Null } catch {}
}
exit 0
