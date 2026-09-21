@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo ============================================================
echo  MK15 Port Inspector 1.0.0 - Windows build
echo ============================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_windows.ps1"
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (
  echo Готово. APK лежит в папке out.
) else (
  echo Сборка завершилась с ошибкой. Откройте build_windows.log.
)
echo.
pause
exit /b %RC%
