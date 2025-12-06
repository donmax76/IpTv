@echo off
echo ========================================
echo WindhawkMod Standalone Test
echo ========================================
echo.

set "DLL_PATH=%~dp0bin\x64\Release\WindhawkMod.dll"
set "LOADER_PATH=%~dp0bin\x64\Release\StandaloneLoader.exe"
set "LOG_PATH=%TEMP%\WindhawkMod.log"

echo Checking files...
echo.

REM Check DLL
if not exist "%DLL_PATH%" (
    echo [ERROR] WindhawkMod.dll not found!
    echo Expected: %DLL_PATH%
    echo Please build the project first: BUILD.bat
    goto :error
)
echo [OK] WindhawkMod.dll found

REM Check Loader
if not exist "%LOADER_PATH%" (
    echo [WARNING] StandaloneLoader.exe not found
    echo It will be built automatically when you run USE_STANDALONE.bat
) else (
    echo [OK] StandaloneLoader.exe found
)

echo.
echo ========================================
echo Test Plan
echo ========================================
echo.
echo 1. Check if running as administrator
echo 2. Check if explorer.exe is running
echo 3. Test DLL injection
echo 4. Check logs
echo.

REM Check admin
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Not running as administrator!
    echo DLL injection requires administrator privileges.
    echo.
    echo Please run this script as administrator:
    echo Right-click -^> "Run as administrator"
    echo.
) else (
    echo [OK] Running as administrator
)

echo.

REM Check explorer
tasklist /FI "IMAGENAME eq explorer.exe" 2>NUL | find /I /N "explorer.exe">NUL
if "%ERRORLEVEL%"=="0" (
    echo [OK] explorer.exe is running
) else (
    echo [ERROR] explorer.exe is not running!
    goto :error
)

echo.
echo ========================================
echo Ready to test
echo ========================================
echo.
echo To inject the DLL, run:
echo   USE_STANDALONE.bat
echo.
echo Or manually:
echo   %LOADER_PATH% %DLL_PATH%
echo.
echo After injection:
echo 1. Start audio recording
echo 2. Check system tray - microphone icon should be HIDDEN
echo 3. Check logs: %LOG_PATH%
echo.

if exist "%LOG_PATH%" (
    echo Current log file (last 10 lines):
    echo ----------------------------------------
    powershell -Command "Get-Content '%LOG_PATH%' -Tail 10 -ErrorAction SilentlyContinue"
    echo ----------------------------------------
) else (
    echo [INFO] Log file will be created after DLL injection
)

echo.
goto :end

:error
echo.
echo Test preparation failed!
pause
exit /b 1

:end
pause

