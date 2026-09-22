# SIYI MK15 Port Inspector

Diagnostic Android application for SIYI MK15. The first practical goal is to determine which interface exposes the upper-left three-position SA switch, find its actual communication channel, and display the live value.

Current version: 1.2.0.

## Repository workflow

This repository is now the primary source. There is no need to exchange source ZIP archives between iterations.

Local Windows directory:

    C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector

First clone:

    cd /d "C:\54\Projects\!0724 - Coating Robot"
    git clone https://github.com/AragCF/SIYI-MK15-Port-Inspector.git
    cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"

Update before the next test:

    cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
    git pull

## Windows build

Run:

    BUILD_WINDOWS.bat

The launcher and all PowerShell scripts used from Windows are intentionally ASCII-only. This avoids the Windows PowerShell 5.1 UTF-8-without-BOM parsing problem that was seen in the first local run.

The script:

1. creates a unique runs/build_YYYYMMDD_HHMMSS directory before the actual build starts;
2. starts a transcript before loading the main build script;
3. runs the SIYI protocol self-test;
4. builds the debug APK;
5. copies the APK and SHA256 into the run folder when available;
6. stores the result code and a short Git/OS snapshot;
7. commits only that run directory and attempts to push it to origin/main even when the build failed.

The build output is also copied to:

    out\MK15PortInspector-1.2.0-debug.apk

If automatic upload fails because of network, Git authentication, a remote update, or unrelated local changes, the run directory and local diagnostic commit are preserved. Retry with:

    PUBLISH_LAST_RUN.bat

The publishing script never runs a broad git add. It adds only the current runs/<id> directory, so unrelated local source edits are not silently committed.

## Protocol self-test

RUN_PROTOCOL_SELFTEST.bat uses the same diagnostic run/publish mechanism.

## GitHub Actions

Every source/script change on main is validated on a Windows runner:

- Windows PowerShell 5.1 parses all tools/*.ps1 scripts;
- the SIYI protocol self-test is executed;
- the Android project is built with Gradle;
- the debug APK is uploaded as a GitHub Actions artifact.

Commits that contain only runs/** diagnostics do not start a new CI build.

Detailed project notes: 00_README_RU.md, 01_FINDINGS.md, 02_TASK_SCOPE.md, 03_TESTS.md and 04_PROGRESS.md.

Safety: first RC-channel tests must be performed on the bench, not in flight and without an active propulsion system.


## Real MK15 diagnostics

After installing and running the APK, connect the MK15 to the Windows PC with ADB enabled and run:

    COLLECT_MK15.bat

For 15 seconds the collector records Linux input events and Android logcat while you move SA through all three positions. It also captures USB/input/TTY/network state and pulls the app persistent runtime log. The resulting runs/device_... directory is automatically committed and pushed to GitHub when possible.


## Transport Explorer 1.1.0

The application can now select or probe multiple paths: AUTO, USB COM/CP210x, UDP, paired Bluetooth SPP, raw /dev/ttyHS0 when permissions allow it, and passive Android Input.

The Switch Finder workflow is:
1. capture a baseline;
2. move SA to another physical position;
3. press compare;
4. repeat several times.

The app compares RC channel values, Android input state, transport byte streams/counters, and selected Linux system sources. Candidates that change consistently with SA are ranked above noisy background counters.


## Standalone report handoff 1.2.0

The buddy operating the MK15 does not need ADB to hand diagnostics back.

The app now creates a complete ZIP and offers three destinations directly on screen:

- **ZIP → Download**: saves to `Download/MK15PortInspector/` so Android File Explorer can find it; with ADB it can also be pulled from that public path.
- **ZIP → флешка/файл…**: opens Android's system file picker. If a USB flash drive is mounted and exposed by DocumentsUI, select it there.
- **ZIP → thesystem**: POSTs the report to `https://thesystem.pro/?action=siyi_receive`.

The working ZIP is also kept in the app external files directory under `Android/data/com.mk15.portinspector/files/reports/`.

### thesystem upload contract

`multipart/form-data`

File field: `report` with MIME `application/zip`.

Text fields: `report_id`, `app_version`, `package`, `device`, `android`, `transport`, `sa_channel`, `finder_rounds`.

The UI shows the HTTP result and a short server response. Any HTTP 2xx is treated as success.
