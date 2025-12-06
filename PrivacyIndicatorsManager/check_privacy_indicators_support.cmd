@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM check_privacy_indicators_support.cmd
REM Gizlilik gostergelerinin sonderilmesi desteyinin yoxlanilmasi

echo.
echo ========================================
echo Gizlilik gostergelerinin sonderilmesi desteyinin yoxlanilmasi
echo ========================================
echo.

REM Windows versiyasinin alinmasi
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"
for /f "tokens=3" %%a in ('reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion" /v EditionID 2^>nul ^| findstr EditionID') do set "EDITION=%%a"

REM Administrator huquqlarinin yoxlanilmasi
net session >nul 2>&1
if %errorlevel% == 0 (
    set "IS_ADMIN=True"
) else (
    set "IS_ADMIN=False"
)

echo Sistem melumatlari:
echo   OS: !OS_CAPTION!
echo   Qurulum: !OS_BUILD!
echo   Nesr: !EDITION!
echo.

set "MIN_BUILD=18362"
set "SUPPORTS_INDICATORS=False"
set "SUPPORTS_POLICY=False"

REM Qurulumun yoxlanilmasi
if !OS_BUILD! geq !MIN_BUILD! set "SUPPORTS_INDICATORS=True"

REM Nesrin yoxlanilmasi
echo !EDITION! | findstr /i "Pro Enterprise Education Server" >nul
if %errorlevel% == 0 set "SUPPORTS_POLICY=True"

echo Gizlilik gostergelerinin desteyi:
if "!SUPPORTS_INDICATORS!"=="True" (
    echo   [OK] Bu Windows versiyasinda desteklenir
) else (
    echo   [X] Windows 10 versiya 1903 ^(qurulum 18362^) ve ya daha yenileri teleb olunur
)
echo.

echo Qrup siyaseti desteyi:
if "!SUPPORTS_POLICY!"=="True" (
    echo   [OK] Nesr qrup siyasetini destekleyir
) else (
    echo   [X] Nesr qrup siyasetini desteklemir ^(Home versiyalari^)
)
echo.

echo Administrator huquqlari:
if "!IS_ADMIN!"=="True" (
    echo   [OK] Administrator adindan ise salinib
) else (
    echo   [X] Administrator adindan ise salinmayib ^(HKLM-e yazmaq ucun teleb olunur^)
)
echo.

REM Reestr acarinin yoxlanilmasi
set "REG_PATH=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"

reg query "!REG_PATH!" >nul 2>&1
if %errorlevel% == 0 (
    echo Reestrin movcud veziyyeti:
    reg query "!REG_PATH!" /v !REG_NAME! >nul 2>&1
    if %errorlevel% == 0 (
        for /f "tokens=3" %%a in ('reg query "!REG_PATH!" /v !REG_NAME! 2^>nul ^| findstr !REG_NAME!') do set "CURRENT_VALUE=%%a"
        echo   Acar movcuddur: DisablePrivacyIndicators = !CURRENT_VALUE!
    ) else (
        echo   Acar movcuddur, lakin DisablePrivacyIndicators quraşdırılmayıb
    )
) else (
    echo Reestrin movcud veziyyeti:
    echo   Acar movcud deyil ^(skript terefinden yaradilacaq^)
)
echo.

echo ========================================
echo YEKUN QIYMETLENDIRME
echo ========================================
echo.

set "WILL_WORK=False"
if "!SUPPORTS_INDICATORS!"=="True" (
    if "!IS_ADMIN!"=="True" (
        if "!SUPPORTS_POLICY!"=="True" (
            set "WILL_WORK=True"
        ) else (
            echo !EDITION! | findstr /i "Home" >nul
            if errorlevel 1 (
                REM Not Home edition
            ) else (
                set "WILL_WORK=True"
            )
        )
    )
)

if "!WILL_WORK!"=="True" (
    echo [OK] Skript bu sistemde ISLEMELIDIR
) else (
    echo [X] Skript bu sistemde ISLEMEYE BILER
    echo.
    echo Sebebler:
    if "!SUPPORTS_INDICATORS!"=="False" (
        echo   - Windows versiyasi cox kohne ^(Windows 10 1903+ ve ya Windows 11 teleb olunur^)
    )
    if "!IS_ADMIN!"=="False" (
        echo   - Administrator huquqlari teleb olunur
    )
)

echo.
echo ========================================
echo ELAVE MELUMAT
echo ========================================
echo.
echo Desteklenen Windows versiyalari:
echo   - Windows 10 versiya 1903 ^(qurulum 18362^) ve daha yenileri
echo   - Windows 11 ^(butun versiyalar^)
echo.
echo Windows nesrleri:
echo   - Windows 10/11 Pro, Enterprise, Education - tam destek
echo   - Windows 10/11 Home - isleye biler, lakin garantiya verilmir
echo.
echo Qeyd:
echo   Reestr acari HKLM:\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators
echo   qrup siyaseti siyasetidir. Windows Home-da tesir gostermeye biler.
echo   Microsoft bu acar haqqinda resmi senedlesme temin etmir.
echo.

pause
endlocal

