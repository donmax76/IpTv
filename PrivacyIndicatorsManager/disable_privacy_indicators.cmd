@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM disable_privacy_indicators.cmd
REM Windows-da gizlilik gostergelerinin sonderilmesi
REM
REM Desteklenen versiyalar:
REM   - Windows 10 versiya 1903 (qurulum 18362) ve daha yenileri
REM   - Windows 11 (butun versiyalar)
REM
REM Telebler:
REM   - Administrator adindan ise salinmasi
REM   - Deyisikliklerin tetbiqi ucun sistemin yeniden yuklenmesi ve ya Explorer-in yeniden baslatilmasi

REM Administrator huquqlarinin yoxlanilmasi
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [XETALI] Bu skripti administrator adindan ise salmalisiniz.
    echo Sag duymeni basib "Administrator kimi ise sal" secin.
    pause
    exit /b 1
)

set "REG_PATH=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"
set "REG_VALUE=1"

REM Windows versiyasinin yoxlanilmasi
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"

set "MIN_BUILD=18362"

if !OS_BUILD! lss !MIN_BUILD! (
    echo [XETALI] Gizlilik gostergeleri yalniz Windows 10 versiya 1903 ^(qurulum 18362^) ve daha yenilerinde desteklenir.
    echo Sizin qurulumunuz: !OS_BUILD!
    pause
    exit /b 1
)

echo.
echo ========================================
echo Gizlilik gostergelerinin sonderilmesi
echo ========================================
echo.
echo Windows versiyasi: !OS_CAPTION!
echo Qurulum: !OS_BUILD!
echo.

REM Reestr acarinin yaradilmasi, eger yoxdursa
reg query "!REG_PATH!" >nul 2>&1
if %errorlevel% neq 0 (
    echo Reestr acarinin yaradilmasi: !REG_PATH!
    reg add "!REG_PATH!" /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Acar yaradildi
    ) else (
        echo [XETALI] Reestr acari yaradila bilmedi.
        pause
        exit /b 1
    )
) else (
    echo Reestr acari artiq movcuddur: !REG_PATH!
)

REM Deyerin quraşdırılması
echo.
echo Deyerin quraşdırılması: !REG_NAME! = !REG_VALUE!
reg add "!REG_PATH!" /v !REG_NAME! /t REG_DWORD /d !REG_VALUE! /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Deyer quraşdırıldı
) else (
    echo [XETALI] Deyer quraşdırıla bilmedi.
    pause
    exit /b 1
)

REM Quraşdırılmış deyerin yoxlanilmasi
set "VERIFY_VALUE="
for /f "tokens=3" %%a in ('reg query "!REG_PATH!" /v !REG_NAME! 2^>nul ^| findstr !REG_NAME!') do set "VERIFY_VALUE=%%a"

REM Reestr deyeri hex formatinda ola biler (0x1), ona gore hem decimal hem hex yoxlayiriq
if defined VERIFY_VALUE (
    if "!VERIFY_VALUE!"=="!REG_VALUE!" (
        set "VALUE_MATCH=True"
    ) else if "!VERIFY_VALUE!"=="0x!REG_VALUE!" (
        set "VALUE_MATCH=True"
    ) else (
        set "VALUE_MATCH=False"
    )
) else (
    set "VALUE_MATCH=False"
)

if "!VALUE_MATCH!"=="True" (
    echo.
    echo [OK] Gosterge ugurla sonderildi!
    echo.
    echo Deyisikliklerin tetbiqi ucun:
    echo   1. Windows Explorer-i yeniden basladin ^(Explorer^)
    echo   2. Ve ya kompyuteri yeniden yukleyin
    echo.
    
    set /p "RESTART_CHOICE=Explorer-i indi yeniden baslatmaq? ^(Y/N^): "
    
    if /i "!RESTART_CHOICE!"=="Y" (
        echo.
        echo Explorer-in yeniden baslatilmasi...
        taskkill /f /im explorer.exe >nul 2>&1
        timeout /t 2 /nobreak >nul
        start explorer.exe
        if %errorlevel% == 0 (
            echo [OK] Explorer yeniden basladildi
        ) else (
            echo [XEBERDARLIQ] Explorer avtomatik olaraq yeniden basladila bilmedi. Onu elle yeniden basladin ve ya kompyuteri yeniden yukleyin.
        )
    ) else (
        echo.
        echo Deyisikliklerin tetbiqi ucun asagidakilardan birini edin:
        echo   - Explorer-i elle yeniden basladin (Ctrl+Shift+Esc -^> Tapshiriq meneceri -^> Explorer -^> Yeniden baslat)
        echo   - Ve ya kompyuteri yeniden yukleyin
    )
) else (
    echo.
    echo [XEBERDARLIQ] Deyer duzgun quraşdırılmadı. Erişim huquqlarinizi yoxlayin.
)

echo.
pause
endlocal

