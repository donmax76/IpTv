@echo off
REM Auto-start script for WindhawkMod
REM This can be added to Windows startup

echo Starting WindhawkMod...

set "SCRIPT_DIR=%~dp0"
set "DLL_PATH=%SCRIPT_DIR%bin\x64\Release\WindhawkMod.dll"
set "LOADER_PATH=%SCRIPT_DIR%bin\x64\Release\StandaloneLoader.exe"

REM Check if already injected (simple check - can be improved)
tasklist /FI "IMAGENAME eq explorer.exe" /FO CSV | findstr /C:"explorer.exe" >nul
if %ERRORLEVEL% NEQ 0 (
    echo explorer.exe not found, waiting...
    timeout /t 5 /nobreak >nul
)

REM Check if DLL exists
if not exist "%DLL_PATH%" (
    echo Error: WindhawkMod.dll not found!
    exit /b 1
)

REM Check if loader exists
if not exist "%LOADER_PATH%" (
    echo Error: StandaloneLoader.exe not found!
    exit /b 1
)

REM Inject DLL (silently)
"%LOADER_PATH%" "%DLL_PATH%" >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo WindhawkMod loaded successfully
) else (
    echo Failed to load WindhawkMod (may need admin rights)
)

