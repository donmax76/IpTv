@echo off
echo ========================================
echo WindhawkMod Integration with Main Project
echo ========================================
echo.

set "PROJECT_ROOT=%~dp0.."
set "MOD_DLL=%~dp0bin\x64\Release\WindhawkMod.dll"
set "AUDIO_SERVICE=%PROJECT_ROOT%\HiddenAudioService\bin\Debug\net8.0-windows\HiddenAudioService.exe"

echo Checking components...
echo.

REM Check DLL
if not exist "%MOD_DLL%" (
    echo [ERROR] WindhawkMod.dll not found!
    echo Please build the project first: BUILD.bat
    goto :error
)
echo [OK] WindhawkMod.dll found

REM Check if service needs building
if not exist "%AUDIO_SERVICE%" (
    echo [INFO] HiddenAudioService not found, checking Release...
    set "AUDIO_SERVICE=%PROJECT_ROOT%\HiddenAudioService\bin\Release\net8.0-windows\HiddenAudioService.exe"
    if not exist "%AUDIO_SERVICE%" (
        echo [INFO] Building HiddenAudioService...
        cd "%PROJECT_ROOT%"
        dotnet build HiddenAudioService\HiddenAudioService.csproj -c Debug
        set "AUDIO_SERVICE=%PROJECT_ROOT%\HiddenAudioService\bin\Debug\net8.0-windows\HiddenAudioService.exe"
    )
)

if exist "%AUDIO_SERVICE%" (
    echo [OK] HiddenAudioService found
) else (
    echo [WARNING] HiddenAudioService not found, will need to build
)

echo.
echo ========================================
echo Integration Summary
echo ========================================
echo.
echo WindhawkMod DLL: %MOD_DLL%
echo.
echo To use this mod:
echo 1. Load %MOD_DLL% in Windhawk
echo 2. Enable the mod
echo 3. Start audio recording with HiddenAudioService
echo 4. Microphone icon will be hidden in system tray
echo.
echo ========================================
echo Quick Start Commands
echo ========================================
echo.
echo Load mod:     powershell -ExecutionPolicy Bypass -File LOAD_MOD.ps1
echo Test mod:     AUTO_TEST.bat
echo Build mod:    BUILD.bat
echo.
goto :end

:error
echo.
echo Integration failed! Please fix errors above.
pause
exit /b 1

:end
echo.
pause

