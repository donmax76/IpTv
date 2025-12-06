@echo off
chcp 65001 >nul
title Отключение индикатора микрофона

echo ========================================
echo ОТКЛЮЧЕНИЕ ИНДИКАТОРА МИКРОФОНА
echo ========================================
echo.
echo Этот скрипт отключит индикатор активности микрофона
echo в Windows 10/11
echo.
echo ВАЖНО: Запустите от имени администратора!
echo.
pause

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Запустите скрипт от имени администратора!
    echo.
    echo Инструкция:
    echo 1. Правой кнопкой мыши на этом файле
    echo 2. Выберите "Запуск от имени администратора"
    echo.
    pause
    exit /b 1
)

echo.
echo [OK] Права администратора подтверждены
echo.

REM Основной путь реестра
set "REG_PATH=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"
set "REG_VALUE=1"

echo Применение изменений...
echo.

REM Создание ключа
reg query "%REG_PATH%" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "%REG_PATH%" /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Ключ реестра создан
    )
)

REM Установка значения
reg add "%REG_PATH%" /v %REG_NAME% /t REG_DWORD /d %REG_VALUE% /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Значение установлено
) else (
    echo [ОШИБКА] Не удалось установить значение
    pause
    exit /b 1
)

REM Проверка
for /f "tokens=3" %%a in ('reg query "%REG_PATH%" /v %REG_NAME% 2^>nul ^| findstr %REG_NAME%') do set "CHECK=%%a"

if defined CHECK (
    echo.
    echo ========================================
    echo [УСПЕХ] Индикатор микрофона отключен!
    echo ========================================
    echo.
    echo Для применения изменений:
    echo.
    echo ВАРИАНТ 1 (рекомендуется):
    echo   1. Нажмите Ctrl+Shift+Esc
    echo   2. Найдите "Проводник" (Explorer)
    echo   3. Правой кнопкой -^> Перезапустить
    echo.
    echo ВАРИАНТ 2:
    echo   Перезагрузите компьютер
    echo.
    
    set /p "RESTART=Перезапустить Explorer автоматически? (Y/N): "
    
    if /i "%RESTART%"=="Y" (
        echo.
        echo Перезапуск Explorer...
        taskkill /f /im explorer.exe >nul 2>&1
        timeout /t 2 /nobreak >nul
        start explorer.exe
        echo [OK] Готово! Индикатор должен исчезнуть.
    )
) else (
    echo.
    echo [ОШИБКА] Изменения не применены
    echo Попробуйте запустить скрипт снова от имени администратора
)

echo.
pause

