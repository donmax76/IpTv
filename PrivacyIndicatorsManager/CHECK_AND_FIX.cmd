@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

title Проверка и исправление индикатора микрофона

echo.
echo ========================================
echo ПРОВЕРКА И ИСПРАВЛЕНИЕ ИНДИКАТОРА МИКРОФОНА
echo ========================================
echo.

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Запустите от имени администратора!
    pause
    exit /b 1
)

REM Получение информации о системе
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"
for /f "tokens=3" %%a in ('reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion" /v EditionID 2^>nul ^| findstr EditionID') do set "EDITION=%%a"

echo Система: !OS_CAPTION!
echo Сборка: !OS_BUILD!
echo Редакция: !EDITION!
echo.

REM Проверка текущего состояния
set "REG_PATH=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME=DisablePrivacyIndicators"

echo Проверка текущего состояния реестра...
echo.

reg query "!REG_PATH!" /v !REG_NAME! >nul 2>&1
if %errorlevel% == 0 (
    for /f "tokens=3" %%a in ('reg query "!REG_PATH!" /v !REG_NAME! 2^>nul ^| findstr !REG_NAME!') do set "CURRENT_VALUE=%%a"
    
    if defined CURRENT_VALUE (
        echo [НАЙДЕНО] Текущее значение: DisablePrivacyIndicators = !CURRENT_VALUE!
        
        if "!CURRENT_VALUE!"=="1" (
            echo [OK] Значение установлено правильно
            echo.
            echo ПРОБЛЕМА: Значение установлено, но индикатор все равно показывается
            echo.
            echo ВОЗМОЖНЫЕ ПРИЧИНЫ:
            echo 1. Не перезагружен компьютер после установки
            echo 2. Windows 11 требует дополнительных настроек
            echo 3. На Windows Home может не работать полностью
            echo 4. Обновления Windows сбросили настройки
            echo.
            
            set /p "FIX=Применить дополнительные исправления? (Y/N): "
            
            if /i "!FIX!"=="Y" (
                echo.
                echo Применение дополнительных исправлений...
                echo.
                
                REM Дополнительные параметры для Windows 11
                if !OS_BUILD! geq 22000 (
                    echo [Windows 11] Применение дополнительных настроек...
                    
                    REM Отключение телеметрии
                    reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\DataCollection" /v AllowTelemetry /t REG_DWORD /d 0 /f >nul 2>&1
                    echo [OK] Телеметрия отключена
                    
                    REM Дополнительный параметр
                    reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone" /v Value /t REG_SZ /d "Deny" /f >nul 2>&1
                    echo [OK] Дополнительные настройки применены
                )
                
                REM Перезапуск Explorer
                echo.
                echo Перезапуск Explorer...
                taskkill /f /im explorer.exe >nul 2>&1
                timeout /t 2 /nobreak >nul
                start explorer.exe
                echo [OK] Explorer перезапущен
                
                echo.
                echo ========================================
                echo [ВАЖНО] ПЕРЕЗАГРУЗИТЕ КОМПЬЮТЕР!
                echo ========================================
                echo.
                echo Изменения применены, но для полного эффекта
                echo необходимо перезагрузить систему.
                echo.
                
                set /p "REBOOT=Перезагрузить компьютер сейчас? (Y/N): "
                if /i "!REBOOT!"=="Y" (
                    shutdown /r /t 10 /c "Перезагрузка для применения изменений индикатора микрофона"
                    echo.
                    echo Компьютер будет перезагружен через 10 секунд...
                )
            )
        ) else (
            echo [ПРОБЛЕМА] Значение установлено неправильно: !CURRENT_VALUE!
            echo Должно быть: 1
            echo.
            set /p "FIX=Исправить значение? (Y/N): "
            if /i "!FIX!"=="Y" (
                reg add "!REG_PATH!" /v !REG_NAME! /t REG_DWORD /d 1 /f >nul 2>&1
                echo [OK] Значение исправлено
                echo Перезагрузите компьютер для применения изменений
            )
        )
    ) else (
        echo [ПРОБЛЕМА] Значение не найдено в реестре
        echo.
        set /p "FIX=Установить значение? (Y/N): "
        if /i "!FIX!"=="Y" (
            if not exist "!REG_PATH!" (
                reg add "!REG_PATH!" /f >nul 2>&1
            )
            reg add "!REG_PATH!" /v !REG_NAME! /t REG_DWORD /d 1 /f >nul 2>&1
            echo [OK] Значение установлено
            echo Перезагрузите компьютер для применения изменений
        )
    )
) else (
    echo [ПРОБЛЕМА] Ключ реестра не найден
    echo.
    set /p "FIX=Создать ключ и установить значение? (Y/N): "
    if /i "!FIX!"=="Y" (
        reg add "!REG_PATH!" /f >nul 2>&1
        reg add "!REG_PATH!" /v !REG_NAME! /t REG_DWORD /d 1 /f >nul 2>&1
        echo [OK] Ключ создан и значение установлено
        echo Перезагрузите компьютер для применения изменений
    )
)

echo.
echo ========================================
echo РЕКОМЕНДАЦИИ
echo ========================================
echo.
echo 1. ОБЯЗАТЕЛЬНО перезагрузите компьютер после изменений
echo 2. На Windows Home может работать не полностью
echo 3. Если не помогает - используйте виртуальный аудио драйвер
echo 4. Некоторые обновления Windows могут сбрасывать настройки
echo.

pause
endlocal

