@echo off
echo ========================================
echo WindhawkMod Standalone Loader
echo ========================================
echo.
echo This loader injects WindhawkMod.dll directly into explorer.exe
echo WITHOUT requiring Windhawk to be installed!
echo.

set "DLL_PATH=%~dp0bin\x64\Release\WindhawkMod.dll"
set "LOADER_PATH=%~dp0bin\x64\Release\StandaloneLoader.exe"

REM Check if DLL exists
if not exist "%DLL_PATH%" (
    echo [ERROR] WindhawkMod.dll not found!
    echo Please build WindhawkMod first: BUILD.bat
    pause
    exit /b 1
)

REM Check if loader exists, if not, build it
if not exist "%LOADER_PATH%" (
    echo [INFO] StandaloneLoader not found, building...
    cd StandaloneLoader
    
    REM Try to find MSBuild
    set "MSBUILD="
    if exist "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\MSBuild\Current\Bin\MSBuild.exe" (
        set "MSBUILD=C:\Program Files\Microsoft Visual Studio\2022\Enterprise\MSBuild\Current\Bin\MSBuild.exe"
    ) else if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe" (
        set "MSBUILD=C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe"
    ) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2019\Enterprise\MSBuild\Current\Bin\MSBuild.exe" (
        set "MSBUILD=C:\Program Files (x86)\Microsoft Visual Studio\2019\Enterprise\MSBuild\Current\Bin\MSBuild.exe"
    ) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\MSBuild\Current\Bin\MSBuild.exe" (
        set "MSBUILD=C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\MSBuild\Current\Bin\MSBuild.exe"
    )
    
    if defined MSBUILD (
        call "%MSBUILD%" StandaloneLoader.vcxproj /p:Configuration=Release /p:Platform=x64 /t:Build /v:minimal
        if exist "bin\x64\Release\StandaloneLoader.exe" (
            copy "bin\x64\Release\StandaloneLoader.exe" "..\bin\x64\Release\StandaloneLoader.exe"
        ) else if exist "StandaloneLoader\bin\x64\Release\StandaloneLoader.exe" (
            copy "StandaloneLoader\bin\x64\Release\StandaloneLoader.exe" "..\bin\x64\Release\StandaloneLoader.exe"
        )
    ) else (
        echo [ERROR] MSBuild not found. Please build manually or install Visual Studio.
        cd ..
        pause
        exit /b 1
    )
    
    cd ..
    
    if not exist "%LOADER_PATH%" (
        echo [ERROR] Failed to build StandaloneLoader
        pause
        exit /b 1
    )
)

echo [OK] DLL found: %DLL_PATH%
echo [OK] Loader found: %LOADER_PATH%
echo.

echo Injecting WindhawkMod.dll into explorer.exe...
echo.

REM Check if running as admin
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] This requires administrator privileges!
    echo Please run as administrator.
    pause
    exit /b 1
)

"%LOADER_PATH%" "%DLL_PATH%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] DLL injected successfully!
    echo.
    echo The microphone icon should now be hidden in the system tray.
    echo Check logs: %%TEMP%%\WindhawkMod.log
) else (
    echo.
    echo [ERROR] Failed to inject DLL
    echo.
    echo Troubleshooting:
    echo 1. Make sure you're running as administrator
    echo 2. Check if explorer.exe is running
    echo 3. Check logs: %%TEMP%%\WindhawkMod.log
)

echo.
pause

