@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM Улучшенный скрипт для отключения индикаторов приватности микрофона
REM Работает на Windows 10 1903+ и Windows 11

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Запустите скрипт от имени администратора!
    echo Правой кнопкой мыши -^> "Запуск от имени администратора"
    pause
    exit /b 1
)

echo ========================================
echo Отключение индикаторов приватности
echo ========================================
echo.

REM Проверка версии Windows
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"

set "MIN_BUILD=18362"

if !OS_BUILD! lss !MIN_BUILD! (
    echo [ОШИБКА] Требуется Windows 10 версия 1903 (сборка 18362) или новее
    echo Ваша сборка: !OS_BUILD!
    pause
    exit /b 1
)

echo Windows версия: !OS_CAPTION!
echo Сборка: !OS_BUILD!
echo.

REM Основной путь реестра
set "REG_PATH1=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"
set "REG_VALUE=1"

echo Применение изменений в реестре...
echo.

REM Создание ключа, если не существует
reg query "!REG_PATH1!" >nul 2>&1
if %errorlevel% neq 0 (
    echo Создание ключа: !REG_PATH1!
    reg add "!REG_PATH1!" /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Ключ создан
    ) else (
        echo [ОШИБКА] Не удалось создать ключ
        pause
        exit /b 1
    )
)

REM Установка значения
echo Установка значения: !REG_NAME! = !REG_VALUE!
reg add "!REG_PATH1!" /v !REG_NAME! /t REG_DWORD /d !REG_VALUE! /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Значение установлено
) else (
    echo [ОШИБКА] Не удалось установить значение
    pause
    exit /b 1
)

REM Проверка результата
echo.
echo Проверка результата...
set "VERIFY_VALUE="
for /f "tokens=3" %%a in ('reg query "!REG_PATH1!" /v !REG_NAME! 2^>nul ^| findstr !REG_NAME!') do set "VERIFY_VALUE=%%a"

if defined VERIFY_VALUE (
    if "!VERIFY_VALUE!"=="!REG_VALUE!" set "VALUE_OK=1"
    if "!VERIFY_VALUE!"=="0x!REG_VALUE!" set "VALUE_OK=1"
)

if defined VALUE_OK (
    echo.
    echo [OK] Индикаторы приватности успешно отключены!
    echo.
    echo ВАЖНО: Для применения изменений необходимо:
    echo   1. Перезапустить Windows Explorer
    echo   2. ИЛИ перезагрузить компьютер
    echo.
    
    set /p "RESTART=Перезапустить Explorer сейчас? (Y/N): "
    
    if /i "!RESTART!"=="Y" (
        echo.
        echo Перезапуск Explorer...
        taskkill /f /im explorer.exe >nul 2>&1
        timeout /t 2 /nobreak >nul
        start explorer.exe
        if %errorlevel% == 0 (
            echo [OK] Explorer перезапущен
            echo.
            echo Изменения применены! Индикатор микрофона должен исчезнуть.
        ) else (
            echo [ОШИБКА] Не удалось перезапустить Explorer автоматически
            echo Перезапустите Explorer вручную или перезагрузите компьютер
        )
    ) else (
        echo.
        echo Перезапустите Explorer вручную:
        echo   Ctrl+Shift+Esc -^> Диспетчер задач -^> Explorer -^> Перезапустить
        echo Или перезагрузите компьютер
    )
) else (
    echo.
    echo [ОШИБКА] Значение не установлено правильно
    echo Проверьте права администратора и попробуйте снова
)

echo.
pause
endlocal

