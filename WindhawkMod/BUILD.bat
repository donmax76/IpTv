@echo off
echo Building WindhawkMod...
echo.

REM Check if Visual Studio is available
where msbuild >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: MSBuild not found in PATH
    echo Please run this from Visual Studio Developer Command Prompt
    echo or add MSBuild to PATH
    pause
    exit /b 1
)

REM Build the project
msbuild WindhawkMod.sln /p:Configuration=Release /p:Platform=x64 /t:Build

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Build successful!
    echo Output: bin\x64\Release\WindhawkMod.dll
) else (
    echo.
    echo Build failed!
    pause
    exit /b 1
)

pause

