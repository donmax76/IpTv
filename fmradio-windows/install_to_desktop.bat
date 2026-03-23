@echo off
echo ==========================================
echo   FM Radio - Install to Desktop
echo ==========================================
echo.
echo Target: C:\Users\narmi\Desktop\FmRadio
echo.

REM Check Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found!
    echo Download Python 3.8+ from https://python.org
    pause
    exit /b 1
)

REM Install dependencies
echo [1/3] Installing dependencies...
pip install pyrtlsdr numpy scipy sounddevice pyinstaller --quiet
if %errorlevel% neq 0 (
    echo ERROR: Failed to install dependencies
    pause
    exit /b 1
)

REM Build exe
echo [2/3] Building FmRadio.exe ...
pyinstaller --noconfirm --onefile --windowed ^
    --name "FmRadio" ^
    --hidden-import "rtlsdr" ^
    --hidden-import "numpy" ^
    --hidden-import "scipy.signal" ^
    --hidden-import "scipy.spatial" ^
    --hidden-import "scipy.fft" ^
    --hidden-import "scipy.linalg" ^
    --hidden-import "scipy.special" ^
    --hidden-import "sounddevice" ^
    --hidden-import "tkinter" ^
    --hidden-import "_sounddevice_data" ^
    --collect-data "sounddevice" ^
    fm_radio.py

if not exist "dist\FmRadio.exe" (
    echo BUILD FAILED!
    pause
    exit /b 1
)

REM Copy to Desktop
echo [3/3] Installing to C:\Users\narmi\Desktop\FmRadio ...
mkdir "C:\Users\narmi\Desktop\FmRadio" 2>nul
copy /Y "dist\FmRadio.exe" "C:\Users\narmi\Desktop\FmRadio\FmRadio.exe"

echo.
echo ==========================================
echo   DONE!
echo   FmRadio.exe installed to:
echo   C:\Users\narmi\Desktop\FmRadio\FmRadio.exe
echo ==========================================
echo.
echo NOTE: Make sure RTL-SDR driver is installed via Zadig
echo       (WinUSB driver for RTL2838UHIDIR device)
echo.
pause
