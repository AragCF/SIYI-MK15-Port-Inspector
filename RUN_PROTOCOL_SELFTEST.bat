@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  MK15 Port Inspector - protocol self-test with auto diagnostics
echo ============================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_selftest.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo Protocol self-test completed successfully.
) else (
  echo Protocol self-test failed. The run log was saved under runs\ and an upload to GitHub was attempted.
)
echo.
echo If automatic GitHub upload failed, run PUBLISH_LAST_RUN.bat after restoring network/authentication.
echo.
pause
exit /b %RC%
