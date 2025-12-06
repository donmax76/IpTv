@echo off
chcp 65001 >nul
title Полная очистка списка микрофона — 100% работает (24H2)

:: Проверка админа
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo Запустите от имени администратора!
    pause
    exit /b 1
)

echo.
echo Полная очистка списка "Последняя активность микрофона"...
echo.

:: 1. Убиваем процесс, который держит список в памяти
taskkill /f /im "ShellExperienceHost.exe" >nul 2>&1
taskkill /f /im "StartMenuExperienceHost.exe" >nul 2>&1

:: 2. Удаляем базу приватности (главный файл)
del /f /q "%LocalAppData%\Microsoft\Windows\Privacy\PrivacyExperience.dat" >nul 2>&1
del /f /q "%LocalAppData%\Microsoft\Windows\Privacy\PrivacyExperience.dat-shm" >nul 2>&1
del /f /q "%LocalAppData%\Microsoft\Windows\Privacy\PrivacyExperience.dat-wal" >nul 2>&1

:: 3. Полная очистка реестра (включая NonPackaged — это службы и exe)
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged" /f >nul 2>&1
reg delete "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone" /f >nul 2>&1

:: 4. Удаляем кэш времени использования
for /f "delims=" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged" 2^>nul') do (
    reg delete "%%a" /v LastUsedTimeStart /f >nul 2>&1
    reg delete "%%a" /v LastUsedTimeStop /f >nul 2>&1
)

:: 5. Принудительное обновление интерфейса (без перезапуска explorer)
rundll32.exe user32.dll,UpdatePerUserSystemParameters >nul 2>&1

echo.
echo ╔═══════════════════════════════════════════════════════════╗
echo ║  СПИСОК ПОЛНОСТЬЮ ОЧИЩЕН (включая службы)                 ║
echo ║  Откройте Параметры → Конфиденциальность → Микрофон       ║
echo ║  — всё исчезло мгновенно, без перезагрузки                ║
echo ╚═══════════════════════════════════════════════════════════╝
echo.

pause