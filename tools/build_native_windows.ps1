param(
    [Parameter(Mandatory = $true)]
    [string]$SdkRoot,
    [string]$NdkVersion = '28.2.13676358'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$NdkRoot = Join-Path $SdkRoot ('ndk\' + $NdkVersion)
$PrebuiltRoot = Join-Path $NdkRoot 'toolchains\llvm\prebuilt'
$Source = Join-Path $Root 'app\src\main\jni\mk15serial.c'
$OutDir = Join-Path $Root '.native-jniLibs\arm64-v8a'
$Output = Join-Path $OutDir 'libmk15serial.so'

if (-not (Test-Path $NdkRoot)) {
    throw ('NDK directory not found: ' + $NdkRoot)
}
if (-not (Test-Path $Source)) {
    throw ('Native source not found: ' + $Source)
}

$HostDir = Get-ChildItem -Path $PrebuiltRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'windows-*' } |
    Select-Object -First 1
if (-not $HostDir) {
    throw ('Windows LLVM prebuilt directory not found under: ' + $PrebuiltRoot)
}

$Clang = Join-Path $HostDir.FullName 'bin\clang.exe'
$ReadElf = Join-Path $HostDir.FullName 'bin\llvm-readelf.exe'
$Sysroot = Join-Path $HostDir.FullName 'sysroot'

if (-not (Test-Path $Clang)) {
    throw ('clang.exe not found: ' + $Clang)
}
if (-not (Test-Path $Sysroot)) {
    throw ('NDK sysroot not found: ' + $Sysroot)
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if (Test-Path $Output) {
    Remove-Item -Force $Output
}

Write-Host '=== Native UART build ==='
Write-Host ('NDK: ' + $NdkRoot)
Write-Host ('Clang: ' + $Clang)
Write-Host ('Source: ' + $Source)
Write-Host ('Output: ' + $Output)

$Args = @(
    '--target=aarch64-linux-android23',
    ('--sysroot=' + $Sysroot),
    '-fPIC',
    '-shared',
    '-O2',
    '-Wall',
    '-Wextra',
    '-Werror=return-type',
    '-Wl,-soname,libmk15serial.so',
    $Source,
    '-o',
    $Output
)

& $Clang @Args
if ($LASTEXITCODE -ne 0) {
    throw ('clang returned exit code ' + $LASTEXITCODE)
}
if (-not (Test-Path $Output)) {
    throw ('Native build reported success but output was not found: ' + $Output)
}

$Length = (Get-Item $Output).Length
if ($Length -le 0) {
    throw 'Native library is empty.'
}

if (Test-Path $ReadElf) {
    $Header = (& $ReadElf '-h' $Output 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw ('llvm-readelf returned exit code ' + $LASTEXITCODE)
    }
    if ($Header -notmatch 'AArch64') {
        throw 'Native library is not AArch64.'
    }
    Write-Host 'Native ELF verified: AArch64.'
}

$Hash = (Get-FileHash -Algorithm SHA256 $Output).Hash
Write-Host ('Native library ready: ' + $Output)
Write-Host ('Native SHA256: ' + $Hash)
