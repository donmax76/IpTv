@echo off
echo ========================================
echo Windhawk Portable Installation Script
echo ========================================
echo.

set "WINDHAWK_DIR=%~dp0..\Windhawk"
set "WINDHAWK_URL=https://windhawk.net/download"

echo Checking for Windhawk directory...
if not exist "%WINDHAWK_DIR%" (
    echo Creating Windhawk directory...
    mkdir "%WINDHAWK_DIR%"
)

echo.
echo Please download Windhawk manually from:
echo %WINDHAWK_URL%
echo.
echo Extract it to: %WINDHAWK_DIR%
echo.
echo After extraction, run this script again to verify installation.
echo.

if exist "%WINDHAWK_DIR%\windhawk.exe" (
    echo [OK] Windhawk found at: %WINDHAWK_DIR%\windhawk.exe
    echo Installation verified!
) else (
    echo [INFO] Windhawk not found. Please download and extract it.
    echo.
    echo You can also:
    echo 1. Download from: %WINDHAWK_URL%
    echo 2. Extract to: %WINDHAWK_DIR%
    echo 3. Or install Windhawk system-wide
)

echo.
pause

