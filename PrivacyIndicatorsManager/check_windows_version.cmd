@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM check_windows_version.cmd
REM Gizlilik gostergeleri reestr acari ucun Windows versiyasinin desteklenmesini yoxlayir

echo.
echo ========================================
echo Windows Versiyasinin Yoxlanilmasi
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

echo Windows Versiyasi: !OS_CAPTION! Qurulum !OS_BUILD!
echo Nesr: !EDITION!
echo Administrator: !IS_ADMIN!
echo.

set "MIN_BUILD=18362"
set "SUPPORTS_INDICATORS=False"
set "SUPPORTS_POLICY=False"

REM Qurulumun yoxlanilmasi
if !OS_BUILD! geq !MIN_BUILD! set "SUPPORTS_INDICATORS=True"

REM Nesrin yoxlanilmasi
echo !EDITION! | findstr /i "Pro Enterprise Education Server" >nul
if %errorlevel% == 0 set "SUPPORTS_POLICY=True"

echo Destek Analizi:
echo   Gizlilik gostergeleri (Qurulum ^>= 18362): !SUPPORTS_INDICATORS!
echo   Qrup siyaseti desteyi: !SUPPORTS_POLICY!
echo   Administrator huquqlari: !IS_ADMIN!
echo.

if "!SUPPORTS_INDICATORS!"=="True" (
    if "!IS_ADMIN!"=="True" (
        if "!SUPPORTS_POLICY!"=="True" (
            echo Netice: Skript bu sistemde ISLEMELIDIR
        ) else (
            echo Netice: Skript bu sistemde ISLEYE BILER ^(Home nesri^)
        )
    ) else (
        echo Netice: Skript bu sistemde ISLEMEYECEK
    )
) else (
    echo Netice: Skript bu sistemde ISLEMEYECEK
)

echo.
echo Desteklenen Windows versiyalari:
echo   - Windows 10 versiya 1903 ^(Qurulum 18362^) ve daha yenileri
echo   - Windows 11 ^(butun versiyalar^)
echo.
echo Desteklenen Nesrler:
echo   - Windows 10/11 Pro, Enterprise, Education: Tam destek
echo   - Windows 10/11 Home: Isleye biler, lakin garantiya verilmir
echo.

pause
endlocal

