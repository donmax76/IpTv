@echo off
title FM Radio RTL-SDR - Portable Launcher
color 0A
echo.
echo  ============================================
echo   FM Radio RTL-SDR v1.0 - Windows 10
echo  ============================================
echo.

REM Check Python
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] Python not found!
    echo.
    echo  Please install Python 3.8+:
    echo  https://python.org/downloads
    echo.
    echo  IMPORTANT: Check "Add Python to PATH" during install!
    echo.
    pause
    exit /b 1
)

echo  [1/4] Checking Python version...
python --version

echo  [2/4] Installing required packages...
pip install pyrtlsdr numpy scipy sounddevice --quiet 2>nul
if %errorlevel% neq 0 (
    echo  [WARN] Some packages may have failed. Trying with --user flag...
    pip install pyrtlsdr numpy scipy sounddevice --user --quiet 2>nul
)

echo  [3/4] Checking RTL-SDR driver...
echo.
echo  NOTE: If RTL-SDR is not detected, install Zadig driver:
echo  1. Download from https://zadig.akeo.ie
echo  2. Connect RTL-SDR device
echo  3. In Zadig, select "Bulk-In, Interface (Interface 0)"
echo  4. Replace driver with "WinUSB"
echo.

echo  [4/4] Starting FM Radio...
echo.

python "%~dp0fm_radio.py"

if %errorlevel% neq 0 (
    echo.
    echo  [ERROR] Application crashed. Check errors above.
    echo.
    pause
)
