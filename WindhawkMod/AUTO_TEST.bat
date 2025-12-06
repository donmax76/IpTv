@echo off
echo ========================================
echo WindhawkMod Auto Test
echo ========================================
echo.

REM Check if DLL exists
if not exist "bin\x64\Release\WindhawkMod.dll" (
    echo ERROR: DLL not found!
    echo Please build the project first: BUILD.bat
    pause
    exit /b 1
)

echo [OK] DLL found
echo.

REM Check if HiddenAudioService exists
cd ..
if exist "HiddenAudioService\bin\Debug\net8.0-windows\HiddenAudioService.exe" (
    set "SERVICE_PATH=HiddenAudioService\bin\Debug\net8.0-windows\HiddenAudioService.exe"
    echo [OK] HiddenAudioService found
) elseif exist "HiddenAudioService\bin\Release\net8.0-windows\HiddenAudioService.exe" (
    set "SERVICE_PATH=HiddenAudioService\bin\Release\net8.0-windows\HiddenAudioService.exe"
    echo [OK] HiddenAudioService found
) else (
    echo [INFO] HiddenAudioService not found in bin, will try to build
    set "SERVICE_PATH="
)

echo.
echo ========================================
echo Test Plan:
echo ========================================
echo.
echo 1. Make sure WindhawkMod is loaded in Windhawk
echo 2. Start audio recording service
echo 3. Check system tray for microphone icon (should be HIDDEN)
echo 4. Check logs in %%TEMP%%\WindhawkMod.log
echo.
echo ========================================
echo.

if defined SERVICE_PATH (
    echo Starting HiddenAudioService...
    echo.
    echo NOTE: The service will start recording. 
    echo Check the system tray - microphone icon should be HIDDEN!
    echo.
    echo Press Ctrl+C to stop recording
    echo.
    pause
    start "" "%SERVICE_PATH%"
) else (
    echo Building and starting HiddenAudioService...
    dotnet run --project HiddenAudioService\HiddenAudioService.csproj
)

echo.
echo Test completed!
echo Check %%TEMP%%\WindhawkMod.log for mod activity
pause

