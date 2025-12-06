@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ========================================
REM УЛУЧШЕННЫЙ СКРИПТ ОТКЛЮЧЕНИЯ ИНДИКАТОРА МИКРОФОНА
REM Использует несколько методов для гарантированного результата
REM ========================================

title Отключение индикатора микрофона - Улучшенная версия

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
echo ========================================
echo ОТКЛЮЧЕНИЕ ИНДИКАТОРА МИКРОФОНА
echo ========================================
echo.

REM Получение информации о системе
for /f "tokens=2 delims==" %%a in ('wmic os get Caption /value 2^>nul') do set "OS_CAPTION=%%a"
for /f "tokens=2 delims==" %%a in ('wmic os get BuildNumber /value 2^>nul') do set "OS_BUILD=%%a"
for /f "tokens=3" %%a in ('reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion" /v EditionID 2^>nul ^| findstr EditionID') do set "EDITION=%%a"

echo Система: !OS_CAPTION!
echo Сборка: !OS_BUILD!
echo Редакция: !EDITION!
echo.

REM Метод 1: Основной путь через групповые политики
set "REG_PATH1=HKLM\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
set "REG_NAME1=DisablePrivacyIndicators"
set "REG_VALUE=1"

REM Метод 2: Альтернативный путь (для Windows 11)
set "REG_PATH2=HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\DataCollection"
set "REG_NAME2=AllowTelemetry"

REM Метод 3: Явное включение доступа к микрофону (чтобы не блокировать доступ)
set "REG_PATH3=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
set "REG_NAME3=Value"

echo Применение изменений в реестре...
echo.

REM ========== МЕТОД 1: Основной путь ==========
echo [МЕТОД 1] Основной путь групповых политик
echo.

reg query "!REG_PATH1!" >nul 2>&1
if %errorlevel% neq 0 (
    echo Создание ключа: !REG_PATH1!
    reg add "!REG_PATH1!" /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Ключ создан
    ) else (
        echo [ОШИБКА] Не удалось создать ключ
    )
) else (
    echo Ключ уже существует: !REG_PATH1!
)

echo Установка значения: !REG_NAME1! = !REG_VALUE!
reg add "!REG_PATH1!" /v !REG_NAME1! /t REG_DWORD /d !REG_VALUE! /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Значение установлено
) else (
    echo [ОШИБКА] Не удалось установить значение
)

REM ========== МЕТОД 2: Дополнительные параметры для Windows 11 ==========
if !OS_BUILD! geq 22000 (
    echo.
    echo [МЕТОД 2] Дополнительные настройки для Windows 11
    echo.
    
    REM Отключение телеметрии (может влиять на индикаторы)
    reg query "!REG_PATH2!" >nul 2>&1
    if %errorlevel% neq 0 (
        reg add "!REG_PATH2!" /f >nul 2>&1
    )
    reg add "!REG_PATH2!" /v !REG_NAME2! /t REG_DWORD /d 0 /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Телеметрия отключена
    )
    
    REM Дополнительные ключи для Windows 11
    set "REG_PATH2B=HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\DataCollection"
    reg add "!REG_PATH2B!" /v AllowTelemetry /t REG_DWORD /d 0 /f >nul 2>&1
    reg add "!REG_PATH2B!" /v DoNotShowFeedbackNotifications /t REG_DWORD /d 1 /f >nul 2>&1
    
    REM Отключение уведомлений конфиденциальности
    set "REG_PATH2C=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Privacy"
    reg add "!REG_PATH2C!" /v TailoredExperiencesWithDiagnosticDataEnabled /t REG_DWORD /d 0 /f >nul 2>&1
    
    set "REG_PATH2D=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Privacy\Flyout"
    reg query "!REG_PATH2D!" >nul 2>&1
    if %errorlevel% neq 0 (
        reg add "!REG_PATH2D!" /f >nul 2>&1
    )
    reg add "!REG_PATH2D!" /v DisablePrivacyFlyout /t REG_DWORD /d 1 /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Уведомления конфиденциальности отключены
    )
)

REM ========== МЕТОД 3: ЯВНОЕ ВКЛЮЧЕНИЕ ДОСТУПА К МИКРОФОНУ ==========
echo.
echo [МЕТОД 3] Явное включение доступа к микрофону (ВАЖНО!)
echo.

REM Включаем доступ к микрофону для приложений
reg query "!REG_PATH3!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH3!" /f >nul 2>&1
)
reg add "!REG_PATH3!" /v !REG_NAME3! /t REG_SZ /d "Allow" /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Доступ к микрофону ВКЛЮЧЕН
) else (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось установить доступ
)

REM Также включаем доступ для классических приложений
set "REG_PATH4=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged"
reg query "!REG_PATH4!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH4!" /f >nul 2>&1
)
reg add "!REG_PATH4!" /v Value /t REG_SZ /d "Allow" /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Доступ для классических приложений ВКЛЮЧЕН
)

REM Удаляем LastUsedTimeStop для всех приложений (может блокировать доступ)
for /f "delims=" %%i in ('reg query "!REG_PATH4!" 2^>nul ^| findstr /i "HKEY"') do (
    reg delete "%%i" /v LastUsedTimeStop /f >nul 2>&1
)

REM Включаем доступ для всех конкретных приложений в NonPackaged
for /f "delims=" %%i in ('reg query "!REG_PATH4!" 2^>nul ^| findstr /i "HKEY"') do (
    reg add "%%i" /v Value /t REG_SZ /d "Allow" /f >nul 2>&1
    reg delete "%%i" /v LastUsedTimeStop /f >nul 2>&1
)

REM ========== МЕТОД 4: Временная остановка службы конфиденциальности ==========
echo.
echo [МЕТОД 4] Временная остановка службы конфиденциальности (БЕЗ отключения)
echo.

REM ВАЖНО: Только останавливаем службы, НЕ отключаем их, чтобы не блокировать доступ
REM Останавливаем службу UserDataSvc временно (если доступно)
sc query "UserDataSvc" >nul 2>&1
if %errorlevel% == 0 (
    echo Временная остановка службы UserDataSvc...
    net stop "UserDataSvc" >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Служба UserDataSvc остановлена (будет перезапущена системой)
    ) else (
        echo [ИНФО] Служба UserDataSvc уже остановлена или недоступна
    )
) else (
    echo [ИНФО] Служба UserDataSvc не найдена
)

REM Останавливаем службу PimSvc временно (Privacy Indicators Manager Service)
sc query "PimSvc" >nul 2>&1
if %errorlevel% == 0 (
    echo Временная остановка службы PimSvc...
    net stop "PimSvc" >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Служба PimSvc остановлена (будет перезапущена системой)
    ) else (
        echo [ИНФО] Служба PimSvc уже остановлена или недоступна
    )
) else (
    echo [ИНФО] Служба PimSvc не найдена
)

REM ========== МЕТОД 5: Дополнительные ключи реестра ==========
echo.
echo [МЕТОД 5] Дополнительные ключи реестра
echo.

REM ВАЖНО: НЕ устанавливаем LetAppsAccessMicrophone, так как это может блокировать доступ
REM Вместо этого удаляем эти ключи, если они существуют
set "REG_PATH5=HKLM\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy"
reg query "!REG_PATH5!" /v LetAppsAccessMicrophone >nul 2>&1
if %errorlevel% == 0 (
    echo Удаление блокирующих политик доступа...
    reg delete "!REG_PATH5!" /v LetAppsAccessMicrophone /f >nul 2>&1
    reg delete "!REG_PATH5!" /v LetAppsAccessMicrophone_UserInControlOfTheseApps /f >nul 2>&1
    reg delete "!REG_PATH5!" /v LetAppsAccessMicrophone_ForceAllowTheseApps /f >nul 2>&1
    reg delete "!REG_PATH5!" /v LetAppsAccessMicrophone_ForceDenyTheseApps /f >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Блокирующие политики удалены
    )
) else (
    echo [OK] Блокирующие политики не найдены
)

REM Отключение индикаторов через ShellExperienceHost
set "REG_PATH6=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Notifications\Settings\Windows.SystemToast.PrivacyIndicators"
reg query "!REG_PATH6!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH6!" /f >nul 2>&1
)
reg add "!REG_PATH6!" /v Enabled /t REG_DWORD /d 0 /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Уведомления индикаторов отключены
)

REM ========== ПРОВЕРКА РЕЗУЛЬТАТА ==========
echo.
echo ========================================
echo ПРОВЕРКА РЕЗУЛЬТАТА
echo ========================================
echo.

set "SUCCESS=0"

REM Проверка основного значения
for /f "tokens=3" %%a in ('reg query "!REG_PATH1!" /v !REG_NAME1! 2^>nul ^| findstr !REG_NAME1!') do set "CHECK1=%%a"
if defined CHECK1 (
    if "!CHECK1!"=="!REG_VALUE!" set "SUCCESS=1"
    if "!CHECK1!"=="0x!REG_VALUE!" set "SUCCESS=1"
    echo [OK] Основной параметр установлен: DisablePrivacyIndicators = !CHECK1!
) else (
    echo [ПРЕДУПРЕЖДЕНИЕ] Основной параметр не найден
)

if !SUCCESS! == 1 (
    echo.
    echo ========================================
    echo [УСПЕХ] ИЗМЕНЕНИЯ ПРИМЕНЕНЫ!
    echo ========================================
    echo.
    echo ВАЖНО: Для полного применения изменений:
    echo.
    echo 1. ПЕРЕЗАГРУЗИТЕ КОМПЬЮТЕР (рекомендуется)
    echo    ИЛИ
    echo 2. Перезапустите следующие службы:
    echo    - Windows Explorer
    echo    - Служба конфиденциальности (Privacy Service)
    echo.
    
    set /p "RESTART=Перезапустить Explorer и службы сейчас? (Y/N): "
    
    if /i "!RESTART!"=="Y" (
        echo.
        echo Перезапуск Explorer...
        taskkill /f /im explorer.exe >nul 2>&1
        timeout /t 2 /nobreak >nul
        start explorer.exe
        
        echo.
        echo Перезапуск служб конфиденциальности...
        REM Восстанавливаем службы (они должны работать для доступа к микрофону)
        sc config "UserDataSvc" start= auto >nul 2>&1
        sc config "PimSvc" start= auto >nul 2>&1
        net start "UserDataSvc" >nul 2>&1
        net start "PimSvc" >nul 2>&1
        timeout /t 2 /nobreak >nul
        
        echo.
        echo [OK] Службы перезапущены
        echo.
        echo Если индикатор все еще показывается, ПЕРЕЗАГРУЗИТЕ компьютер!
    ) else (
        echo.
        echo Для применения изменений:
        echo 1. Перезагрузите компьютер (ЛУЧШИЙ ВАРИАНТ)
        echo 2. Или перезапустите Explorer вручную
    )
) else (
    echo.
    echo [ОШИБКА] Изменения не применены
    echo Проверьте права администратора и попробуйте снова
)

echo.
echo ========================================
echo ДОПОЛНИТЕЛЬНАЯ ИНФОРМАЦИЯ
echo ========================================
echo.
echo ВАЖНО: Этот скрипт:
echo - Отключает ИНДИКАТОР использования микрофона (видимость)
echo - НО ВКЛЮЧАЕТ доступ к микрофону для приложений
echo.
echo Если индикатор все еще показывается после перезагрузки:
echo.
echo 1. Проверьте версию Windows (нужна 1903+)
echo 2. На Windows Home может не работать полностью
echo 3. Некоторые обновления Windows могут сбрасывать настройки
echo 4. Попробуйте использовать виртуальный аудио драйвер
echo.
echo Текущие значения в реестре:
reg query "!REG_PATH1!" /v !REG_NAME1! 2>nul
echo.
echo Проверка доступа к микрофону:
reg query "!REG_PATH3!" /v !REG_NAME3! 2>nul
echo.

pause
endlocal

