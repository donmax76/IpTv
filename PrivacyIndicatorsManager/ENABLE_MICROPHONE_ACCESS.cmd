@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

title Включение доступа к микрофону для приложений

echo ========================================
echo ВКЛЮЧЕНИЕ ДОСТУПА К МИКРОФОНУ
echo ========================================
echo.
echo Этот скрипт включит доступ к микрофону для приложений
echo после отключения индикаторов приватности
echo.
echo ВАЖНО: Запустите от имени администратора!
echo.
pause

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Запустите скрипт от имени администратора!
    pause
    exit /b 1
)

echo.
echo [OK] Права администратора подтверждены
echo.

REM Включаем доступ к микрофону для приложений
set "REG_PATH=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
set "REG_NAME=Value"
set "REG_VALUE=Allow"

echo Применение изменений...
echo.

REM Создание ключа, если не существует
reg query "!REG_PATH!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH!" /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Ключ создан
    )
)

REM Установка значения Allow
reg add "!REG_PATH!" /v !REG_NAME! /t REG_SZ /d !REG_VALUE! /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Доступ к микрофону включен
) else (
    echo [ОШИБКА] Не удалось установить значение
    pause
    exit /b 1
)

REM Также включаем доступ через групповые политики (если доступно)
set "REG_PATH2=HKLM\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy"
set "REG_NAME2=LetAppsAccessMicrophone"

reg query "!REG_PATH2!" >nul 2>&1
if %errorlevel% == 0 (
    reg add "!REG_PATH2!" /v !REG_NAME2! /t REG_DWORD /d 2 /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Групповые политики настроены
    )
)

echo.
echo ========================================
echo [УСПЕХ] ДОСТУП К МИКРОФОНУ ВКЛЮЧЕН!
echo ========================================
echo.
echo Теперь:
echo 1. Перезапустите приложение AudioRecorder
echo 2. Или перезагрузите компьютер
echo.
echo Если проблема сохраняется:
echo 1. Откройте Параметры Windows
echo 2. Конфиденциальность и защита -^> Микрофон
echo 3. Включите "Разрешить приложениям доступ к микрофону"
echo 4. Включите "Разрешить классическим приложениям доступ к микрофону"
echo.

pause
endlocal

