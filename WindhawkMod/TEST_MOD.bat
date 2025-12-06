@echo off
echo ========================================
echo Testing WindhawkMod
echo ========================================
echo.

set "DLL_PATH=%~dp0bin\x64\Release\WindhawkMod.dll"
set "LOG_PATH=%TEMP%\WindhawkMod.log"

echo Checking DLL...
if not exist "%DLL_PATH%" (
    echo ERROR: DLL not found at %DLL_PATH%
    echo Please build the project first!
    pause
    exit /b 1
)

echo [OK] DLL found: %DLL_PATH%
echo.

echo Checking log file...
if exist "%LOG_PATH%" (
    echo [OK] Log file exists: %LOG_PATH%
    echo.
    echo Last 20 lines of log:
    echo ----------------------------------------
    powershell -Command "Get-Content '%LOG_PATH%' -Tail 20"
    echo ----------------------------------------
) else (
    echo [INFO] Log file not created yet (will be created when mod loads)
)
echo.

echo ========================================
echo Testing Instructions:
echo ========================================
echo.
echo 1. Load WindhawkMod.dll in Windhawk:
echo    - Open Windhawk
echo    - Create new mod
echo    - Point to: %DLL_PATH%
echo    - Enable the mod
echo.
echo 2. Test audio recording:
echo    - Run: cd ..\.. && dotnet run --project HiddenAudioService\HiddenAudioService.csproj
echo    - Or use: AudioRecorder.exe
echo.
echo 3. Check system tray:
echo    - Microphone icon should be HIDDEN
echo    - Audio recording should work normally
echo.
echo 4. Monitor logs:
echo    - Log file: %LOG_PATH%
echo    - Use DebugView for real-time logs
echo.
pause

