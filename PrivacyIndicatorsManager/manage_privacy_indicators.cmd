@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM manage_privacy_indicators.cmd
REM Gizlilik gostergelerinin idare edilmesi
REM Bu skript check_privacy_indicators_support, enable_privacy_indicators ve disable_privacy_indicators skriptlerini istifade edir

REM Skriptin yerlesdiyi qovluq
set "SCRIPT_DIR=%~dp0"

REM Administrator huquqlarinin yoxlanilmasi
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [XETALI] Bu skripti administrator adindan ise salmalisiniz.
    echo Sag duymeni basib "Administrator kimi ise sal" secin.
    pause
    exit /b 1
)

:MAIN_MENU
cls
echo.
echo ========================================
echo Gizlilik gostergelerinin idare edilmesi
echo ========================================
echo.

REM Movcud veziyyetin yoxlanilmasi
set "REG_PATH=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"
set "CURRENT_STATUS=Namum"

reg query "!REG_PATH!" /v !REG_NAME! >nul 2>&1
if %errorlevel% == 0 (
    for /f "tokens=3" %%a in ('reg query "!REG_PATH!" /v !REG_NAME! 2^>nul ^| findstr !REG_NAME!') do set "CURRENT_VALUE=%%a"
    
    if defined CURRENT_VALUE (
        if "!CURRENT_VALUE!"=="0x1" set "CURRENT_VALUE=1"
        if "!CURRENT_VALUE!"=="0x0" set "CURRENT_VALUE=0"
        
        if "!CURRENT_VALUE!"=="1" (
            set "CURRENT_STATUS=Sonderilib"
        ) else if "!CURRENT_VALUE!"=="0" (
            set "CURRENT_STATUS=Aktivlesdirilib"
        )
    )
) else (
    set "CURRENT_STATUS=Quraşdırılmayıb"
)

echo Movcud veziyyet: !CURRENT_STATUS!
if defined CURRENT_VALUE (
    echo Reestr deyeri: DisablePrivacyIndicators = !CURRENT_VALUE!
)
echo.

REM Sistem melumatlarinin gosterilmesi
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"

echo Sistem melumatlari:
echo   OS: !OS_CAPTION!
echo   Qurulum: !OS_BUILD!
echo.

echo ========================================
echo MENYU
echo ========================================
echo.
echo   1. Veziyyeti yoxla ^(check_privacy_indicators_support^)
echo   2. Gostergeleri sonder ^(disable_privacy_indicators^)
echo   3. Gostergeleri aktivlesdir ^(enable_privacy_indicators^)
echo   4. Cixis
echo.
set /p "CHOICE=Seciminizi edin ^(1-4^): "

if "!CHOICE!"=="1" (
    echo.
    echo ========================================
    echo Veziyyetin yoxlanilmasi
    echo ========================================
    echo.
    call "!SCRIPT_DIR!check_privacy_indicators_support.cmd"
    echo.
    pause
    goto MAIN_MENU
) else if "!CHOICE!"=="2" (
    echo.
    echo ========================================
    echo Gostergelerin sonderilmesi
    echo ========================================
    echo.
    call "!SCRIPT_DIR!disable_privacy_indicators.cmd"
    echo.
    pause
    goto MAIN_MENU
) else if "!CHOICE!"=="3" (
    echo.
    echo ========================================
    echo Gostergelerin aktivlesdirilmesi
    echo ========================================
    echo.
    call "!SCRIPT_DIR!enable_privacy_indicators.cmd"
    echo.
    pause
    goto MAIN_MENU
) else if "!CHOICE!"=="4" (
    echo.
    echo Cixilir...
    exit /b 0
) else (
    echo.
    echo [XETALI] Yalnis secim! 1-4 arasi reqem daxil edin.
    timeout /t 2 /nobreak >nul
    goto MAIN_MENU
)

endlocal

