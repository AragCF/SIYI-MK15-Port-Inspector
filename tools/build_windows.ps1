param(
    [string]$RunDir = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $Root 'out'
$ApkSource = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$ApkTarget = Join-Path $OutDir 'MK15PortInspector-1.0.1-debug.apk'
$ToolsDir = Join-Path $Root '.tools'
$GradleVersion = '8.7'
$GradleZip = Join-Path $ToolsDir ("gradle-" + $GradleVersion + "-bin.zip")
$GradleHome = Join-Path $ToolsDir ("gradle-" + $GradleVersion)
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'

function Fail([string]$Message) {
    Write-Host ''
    Write-Host ('ERROR: ' + $Message) -ForegroundColor Red
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
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    if ($env:LOCALAPPDATA) {
        $DefaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path $DefaultSdk) {
            return $DefaultSdk
        }
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null

Write-Host '=== MK15 Port Inspector: Windows build ==='
Write-Host ('Project: ' + $Root)

$JavaHome = Find-JavaHome
if (-not $JavaHome) {
    Fail 'JDK was not found. Install Android Studio or set JAVA_HOME to a JDK 17 installation.'
}

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
Write-Host ('JAVA_HOME=' + $env:JAVA_HOME)
& (Join-Path $JavaHome 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) {
    Fail ('java -version returned exit code ' + $LASTEXITCODE)
}

$Sdk = Find-AndroidSdk
if (-not $Sdk) {
    Fail 'Android SDK was not found. Install Android Studio/SDK or set ANDROID_SDK_ROOT.'
}

$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk
Write-Host ('ANDROID_SDK_ROOT=' + $Sdk)

$AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$Aapt2 = Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'
if (-not (Test-Path $AndroidJar) -or -not (Test-Path $Aapt2)) {
    Write-Host ''
    Write-Host 'Android SDK Platform 34 and/or Build-Tools 34.0.0 are missing.' -ForegroundColor Yellow
    Write-Host 'Install them in Android Studio SDK Manager or run:'
    Write-Host '  sdkmanager.bat "platforms;android-34" "build-tools;34.0.0" "platform-tools"'
    Fail 'Required Android SDK components are missing.'
}

$HostBuild = Join-Path $Root '.host-test-build'
if (Test-Path $HostBuild) {
    Remove-Item -Recurse -Force $HostBuild
}
New-Item -ItemType Directory -Force -Path $HostBuild | Out-Null

$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Java = Join-Path $JavaHome 'bin\java.exe'
$ProtocolSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\SiyiProtocol.java'
$ProtocolTest = Join-Path $Root 'host-tests\ProtocolSelfTest.java'

Write-Host ''
Write-Host 'Running SIYI protocol self-test...'
& $Javac -encoding UTF-8 -d $HostBuild $ProtocolSource $ProtocolTest
if ($LASTEXITCODE -ne 0) {
    Fail ('javac protocol test compile returned exit code ' + $LASTEXITCODE)
}

& $Java -cp $HostBuild ProtocolSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('ProtocolSelfTest returned exit code ' + $LASTEXITCODE)
}

$GradleCmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
if ($GradleCmd) {
    $Gradle = $GradleCmd.Source
    Write-Host ('Using Gradle from PATH: ' + $Gradle)
}
else {
    if (-not (Test-Path $GradleBat)) {
        Write-Host ("Gradle " + $GradleVersion + " was not found. Downloading official distribution...")
        if (-not (Test-Path $GradleZip)) {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
            Invoke-WebRequest -Uri $Url -OutFile $GradleZip -UseBasicParsing
        }
        if (Test-Path $GradleHome) {
            Remove-Item -Recurse -Force $GradleHome
        }
        Expand-Archive -Path $GradleZip -DestinationPath $ToolsDir -Force
    }

    if (-not (Test-Path $GradleBat)) {
        Fail 'Gradle archive was extracted but gradle.bat was not found.'
    }

    $Gradle = $GradleBat
    Write-Host ('Using local Gradle: ' + $Gradle)
}

Push-Location $Root
try {
    Write-Host ''
    Write-Host 'Running Android debug build...'
    & $Gradle ':app:assembleDebug' '--no-daemon' '--stacktrace'
    if ($LASTEXITCODE -ne 0) {
        Fail ('Gradle returned exit code ' + $LASTEXITCODE)
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $ApkSource)) {
    Fail ('Gradle reported success but APK was not found: ' + $ApkSource)
}

Copy-Item -Force $ApkSource $ApkTarget
$Hash = (Get-FileHash -Algorithm SHA256 $ApkTarget).Hash

Write-Host ''
Write-Host 'BUILD SUCCESSFUL.' -ForegroundColor Green
Write-Host ('APK: ' + $ApkTarget)
Write-Host ('SHA256: ' + $Hash)
