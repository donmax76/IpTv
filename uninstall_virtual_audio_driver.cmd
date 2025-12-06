@echo off
setlocal ENABLEDELAYEDEXPANSION
chcp 65001 >nul

set "HARDWARE_ID=ROOT\VirtualAudioDriver"
set "TARGET_INF=VirtualAudioDriver.inf"
set "SCRIPT_DIR=%~dp0"

rem --- Check for administrative privileges ---
net session >nul 2>&1
if errorlevel 1 (
    echo Требуются права администратора. Запустите этот скрипт от имени администратора.
    exit /b 1
)

rem --- Locate devcon.exe ---
set "DEVCON_PATH="
if exist "%SCRIPT_DIR%devcon.exe" (
    set "DEVCON_PATH=%SCRIPT_DIR%devcon.exe"
) else (
    for %%I in (
        "C:\Program Files (x86)\Windows Kits\10\Tools\10.0.26100.0\x64\devcon.exe"
        "C:\Program Files (x86)\Windows Kits\10\Tools\x64\devcon.exe"
        "C:\Program Files (x86)\Windows Kits\10\Tools\10.0.22621.0\x64\devcon.exe"
    ) do (
        if exist %%~I (
            set "DEVCON_PATH=%%~I"
            goto devcon_found
        )
    )
)

:devcon_found
if not defined DEVCON_PATH (
    echo Не найден devcon.exe. Скопируйте его рядом со скриптом или поправьте пути в файле.
    exit /b 1
)

echo Используется devcon: "%DEVCON_PATH%"
echo Удаление устройства %HARDWARE_ID% ...
"%DEVCON_PATH%" remove %HARDWARE_ID%
if errorlevel 1 (
    echo devcon завершился с ошибкой или устройство уже отсутствует. Продолжаю.
) else (
    echo Устройство удалено или отсутствовало.
)

echo.
echo Поиск опубликованного INF для %TARGET_INF% ...
set "PUBLISHED_INF="

for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command ^
    "pnputil /enum-drivers | Out-String | ForEach-Object { $_ }"`) do (
    rem Dummy loop to ensure PowerShell executes; actual parsing done below
)

for /f "usebackq tokens=*" %%P in (`powershell -NoProfile ^
    -Command ^
    "pnputil /enum-drivers | & {
        param($target)
        $published = $null
        foreach ($line in $input) {
            if ($line -match '^(Опубликованное имя|Published Name)\s*:\s*(.+)$') {
                $published = $Matches[2].Trim()
            }
            elseif ($line -match '^(Исходное имя|Original Name)\s*:\s*(.+)$') {
                if ($Matches[2].Trim().ToLower() -eq $target.ToLower()) {
                    if ($published) { $published; break }
                }
            }
        }
    }" ^
    "%TARGET_INF%"`) do (
    set "PUBLISHED_INF=%%P"
)

if not defined PUBLISHED_INF (
    echo Не найден опубликованный INF для %TARGET_INF%. Возможно пакет уже удалён.
    exit /b 0
)

echo Найден пакет: !PUBLISHED_INF!
echo Удаление пакета драйвера...
pnputil /delete-driver "!PUBLISHED_INF!" /uninstall /force
if errorlevel 1 (
    echo Ошибка при удалении драйвера. Проверьте вывод pnputil.
    exit /b 1
)

echo Готово. При необходимости перезагрузите систему.
exit /b 0

