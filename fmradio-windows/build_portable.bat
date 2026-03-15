@echo off
setlocal enabledelayedexpansion
title FM Radio - Build Portable Distribution
color 0A
echo.
echo  ============================================
echo   FM Radio RTL-SDR v1.0
echo   Building Portable Distribution
echo  ============================================
echo.

REM === Configuration ===
set "PYTHON_VERSION=3.10.11"
set "PYTHON_EMBED_URL=https://www.python.org/ftp/python/%PYTHON_VERSION%/python-%PYTHON_VERSION%-embed-amd64.zip"
set "GET_PIP_URL=https://bootstrap.pypa.io/get-pip.py"
set "OUTPUT_DIR=%~dp0dist\fmradio-portable"
set "PYTHON_DIR=%OUTPUT_DIR%\python"

REM === Clean previous build ===
if exist "%OUTPUT_DIR%" (
    echo  [*] Cleaning previous build...
    rmdir /s /q "%OUTPUT_DIR%"
)
mkdir "%OUTPUT_DIR%"
mkdir "%PYTHON_DIR%"

REM === Download Python embeddable ===
echo  [1/6] Downloading Python %PYTHON_VERSION% embeddable...
set "PYTHON_ZIP=%TEMP%\python-embed.zip"
curl -L -o "%PYTHON_ZIP%" "%PYTHON_EMBED_URL%"
if %errorlevel% neq 0 (
    echo  [ERROR] Failed to download Python. Check internet connection.
    pause
    exit /b 1
)

REM === Extract Python ===
echo  [2/6] Extracting Python...
powershell -Command "Expand-Archive -Path '%PYTHON_ZIP%' -DestinationPath '%PYTHON_DIR%' -Force"
if %errorlevel% neq 0 (
    echo  [ERROR] Failed to extract Python.
    pause
    exit /b 1
)

REM === Enable pip in embeddable Python ===
REM The embeddable distribution has import restrictions by default.
REM We need to uncomment "import site" in python310._pth
echo  [3/6] Configuring Python for pip...
set "PTH_FILE="
for %%f in ("%PYTHON_DIR%\python*._pth") do set "PTH_FILE=%%f"
if defined PTH_FILE (
    REM Rewrite the ._pth file to enable site-packages
    (
        echo python310.zip
        echo .
        echo Lib
        echo Lib\site-packages
        echo import site
    ) > "!PTH_FILE!"
)

REM Create Lib and site-packages directories
mkdir "%PYTHON_DIR%\Lib" 2>nul
mkdir "%PYTHON_DIR%\Lib\site-packages" 2>nul

REM === Install pip ===
echo  [4/6] Installing pip...
curl -L -o "%PYTHON_DIR%\get-pip.py" "%GET_PIP_URL%"
"%PYTHON_DIR%\python.exe" "%PYTHON_DIR%\get-pip.py" --no-warn-script-location --quiet
if %errorlevel% neq 0 (
    echo  [ERROR] Failed to install pip.
    pause
    exit /b 1
)
del "%PYTHON_DIR%\get-pip.py" 2>nul

REM === Install dependencies ===
echo  [5/6] Installing FM Radio dependencies...
echo         (numpy, scipy, sounddevice, pyrtlsdr - this may take a few minutes)
"%PYTHON_DIR%\python.exe" -m pip install ^
    numpy scipy sounddevice pyrtlsdr ^
    --target "%PYTHON_DIR%\Lib\site-packages" ^
    --no-warn-script-location --quiet
if %errorlevel% neq 0 (
    echo  [ERROR] Failed to install dependencies.
    pause
    exit /b 1
)

REM === Verify critical scipy submodules ===
echo  [*] Verifying scipy submodules...
"%PYTHON_DIR%\python.exe" -c "from scipy.signal import firwin, lfilter, decimate; from scipy.spatial import cKDTree; print('  scipy.signal OK'); print('  scipy.spatial OK')"
if %errorlevel% neq 0 (
    echo  [WARN] scipy verification failed, attempting full scipy reinstall...
    "%PYTHON_DIR%\python.exe" -m pip install scipy --force-reinstall ^
        --target "%PYTHON_DIR%\Lib\site-packages" ^
        --no-warn-script-location --quiet
)

REM === Copy application files ===
echo  [6/6] Copying application files...
copy /Y "%~dp0fm_radio.py" "%OUTPUT_DIR%\fm_radio.py"
if exist "%~dp0fm_stations.json" (
    copy /Y "%~dp0fm_stations.json" "%OUTPUT_DIR%\fm_stations.json"
)

REM === Create launcher batch file ===
(
echo @echo off
echo title FM Radio RTL-SDR - Portable
echo color 0A
echo echo.
echo echo  ============================================
echo echo   FM Radio RTL-SDR v1.0
echo echo   Portable Edition ^(Python included^)
echo echo  ============================================
echo echo  NOTE: Connect RTL-SDR device before starting!
echo echo  If not detected, install WinUSB driver via Zadig:
echo echo    https://zadig.akeo.ie
echo "%%~dp0python\python.exe" "%%~dp0fm_radio.py"
echo if %%errorlevel%% neq 0 ^(
echo     echo.
echo     echo  [ERROR] Application exited with error. See above.
echo     pause
echo ^)
) > "%OUTPUT_DIR%\FmRadio.bat"

REM === Done ===
echo.
echo  ============================================
echo   BUILD SUCCESS!
echo   Output: %OUTPUT_DIR%
echo.
echo   Contents:
echo     FmRadio.bat       - Double-click to run
echo     fm_radio.py       - Application source
echo     python\           - Embedded Python + deps
echo  ============================================
echo.
echo  To distribute: zip the fmradio-portable folder.
echo.
pause
