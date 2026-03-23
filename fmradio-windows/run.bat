@echo off
echo ==========================================
echo   FM Radio RTL-SDR - Windows 10
echo ==========================================
echo.

REM Check Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found! Install Python 3.8+ from python.org
    pause
    exit /b 1
)

REM Install dependencies
echo Installing dependencies...
pip install -r requirements.txt --quiet

echo.
echo Starting FM Radio...
echo.
python fm_radio.py

pause
