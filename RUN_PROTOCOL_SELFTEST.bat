@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo JDK не найден. Установите Android Studio или задайте JAVA_HOME.
  pause
  exit /b 1
)
if exist ".host-test-build" rmdir /s /q ".host-test-build"
mkdir ".host-test-build"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -d ".host-test-build" "app\src\main\java\com\mk15\portinspector\SiyiProtocol.java" "host-tests\ProtocolSelfTest.java"
if errorlevel 1 goto :fail
"%JAVA_HOME%\bin\java.exe" -cp ".host-test-build" ProtocolSelfTest
if errorlevel 1 goto :fail
echo.
echo ПРОТОКОЛЬНЫЕ ТЕСТЫ ПРОЙДЕНЫ.
pause
exit /b 0
:fail
echo.
echo ПРОТОКОЛЬНЫЙ ТЕСТ НЕ ПРОЙДЕН.
pause
exit /b 1
