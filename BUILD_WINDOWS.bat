@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  MK15 Port Inspector - Windows build with auto diagnostics
echo ============================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_build.ps1"
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo Build completed successfully.
) else (
  echo Build failed. The run log was saved under runs\ and an upload to GitHub was attempted.
)
echo.
echo If automatic GitHub upload failed, run PUBLISH_LAST_RUN.bat after restoring network/authentication.
echo.
pause
exit /b %RC%
