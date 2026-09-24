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

if (-not (Test-Path $NdkRoot)) {
    throw ('NDK directory not found: ' + $NdkRoot)
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

if (-not (Test-Path $Clang)) { throw ('clang.exe not found: ' + $Clang) }
if (-not (Test-Path $Sysroot)) { throw ('NDK sysroot not found: ' + $Sysroot) }

function Build-NativeTarget(
    [string]$Name,
    [string]$Source,
    [string]$OutDir,
    [string]$LibraryName
) {
    if (-not (Test-Path $Source)) { throw ($Name + ' source not found: ' + $Source) }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $Output = Join-Path $OutDir $LibraryName
    if (Test-Path $Output) { Remove-Item -Force $Output }

    Write-Host ''
    Write-Host ('=== Native build: ' + $Name + ' ===')
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
        ('-Wl,-soname,' + $LibraryName),
        $Source,
        '-o',
        $Output
    )

    & $Clang @Args
    if ($LASTEXITCODE -ne 0) { throw ($Name + ' clang returned exit code ' + $LASTEXITCODE) }
    if (-not (Test-Path $Output)) { throw ($Name + ' output was not found: ' + $Output) }
    if ((Get-Item $Output).Length -le 0) { throw ($Name + ' native library is empty.') }

    if (Test-Path $ReadElf) {
        $Header = (& $ReadElf '-h' $Output 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) { throw ($Name + ' llvm-readelf returned exit code ' + $LASTEXITCODE) }
        if ($Header -notmatch 'AArch64') { throw ($Name + ' native library is not AArch64.') }
        Write-Host ($Name + ' ELF verified: AArch64.')
    }

    $Hash = (Get-FileHash -Algorithm SHA256 $Output).Hash
    Write-Host ($Name + ' native library ready: ' + $Output)
    Write-Host ($Name + ' native SHA256: ' + $Hash)
}

Build-NativeTarget -Name 'Port Inspector' -Source (Join-Path $Root 'app\src\main\jni\mk15serial.c') -OutDir (Join-Path $Root '.native-jniLibs\arm64-v8a') -LibraryName 'libmk15serial.so'
Build-NativeTarget -Name 'MK15 C-D SDK' -Source (Join-Path $Root 'mk15-sdk\src\main\jni\mk15sdkserial.c') -OutDir (Join-Path $Root '.native-sdk-jniLibs\arm64-v8a') -LibraryName 'libmk15sdkserial.so'
