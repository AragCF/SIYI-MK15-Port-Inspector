@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  MK15 Port Inspector - retry upload of the latest local run
echo ============================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\publish_last_run.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo Upload attempt completed.
) else (
  echo Upload failed. Check the latest runs\...\publish.log file.
)
echo.
pause
exit /b %RC%
