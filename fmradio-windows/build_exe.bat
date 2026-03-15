@echo off
echo ==========================================
echo   Building FM Radio .exe (Windows 10)
echo ==========================================
echo.

REM Check Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found!
    echo Download Python 3.8+ from https://python.org
    echo Make sure to check "Add to PATH" during install
    pause
    exit /b 1
)

REM Install dependencies
echo Installing dependencies...
pip install pyrtlsdr numpy scipy sounddevice pyinstaller --quiet

echo.
echo Building executable...
echo.

pyinstaller --noconfirm --onefile --windowed ^
    --name "FmRadio" ^
    --icon "NONE" ^
    --add-data "fm_stations.json;." ^
    --hidden-import "rtlsdr" ^
    --hidden-import "numpy" ^
    --hidden-import "scipy.signal" ^
    --hidden-import "sounddevice" ^
    --hidden-import "tkinter" ^
    fm_radio.py

if exist "dist\FmRadio.exe" (
    echo.
    echo ==========================================
    echo   BUILD SUCCESS!
    echo   Output: dist\FmRadio.exe
    echo ==========================================
    echo.

    REM Copy to Desktop if requested
    if "%1"=="--install" (
        echo Copying to Desktop...
        mkdir "%USERPROFILE%\Desktop\FmRadio" 2>nul
        copy "dist\FmRadio.exe" "%USERPROFILE%\Desktop\FmRadio\FmRadio.exe"
        echo Done! FmRadio.exe is on your Desktop in FmRadio folder.
    )
) else (
    echo.
    echo BUILD FAILED! Check errors above.
)

pause
