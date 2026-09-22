@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  MK15 Port Inspector - collect diagnostics from real MK15
echo ============================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\collect_mk15.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo Device diagnostics were collected and an upload to GitHub was attempted.
) else (
  echo Device diagnostics failed or were incomplete. Local logs were preserved under runs\.
)
echo.
echo If upload failed, run PUBLISH_LAST_RUN.bat later.
echo.
pause
exit /b %RC%
