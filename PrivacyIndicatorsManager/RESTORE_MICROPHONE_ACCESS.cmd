@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ========================================
REM ВОССТАНОВЛЕНИЕ ДОСТУПА К МИКРОФОНУ
REM Используйте этот скрипт, если доступ к микрофону был заблокирован
REM ========================================

title Восстановление доступа к микрофону

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Запустите скрипт от имени администратора!
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo ВОССТАНОВЛЕНИЕ ДОСТУПА К МИКРОФОНУ
echo ========================================
echo.

REM 1. Включаем доступ к микрофону глобально
echo [ШАГ 1] Включение глобального доступа к микрофону...
set "REG_PATH1=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
reg query "!REG_PATH1!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH1!" /f >nul 2>&1
)
reg add "!REG_PATH1!" /v Value /t REG_SZ /d "Allow" /f >nul 2>&1
reg delete "!REG_PATH1!" /v LastUsedTimeStop /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Глобальный доступ включен
)

REM 2. Включаем доступ для классических приложений
echo.
echo [ШАГ 2] Включение доступа для классических приложений...
set "REG_PATH2=HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged"
reg query "!REG_PATH2!" >nul 2>&1
if %errorlevel% neq 0 (
    reg add "!REG_PATH2!" /f >nul 2>&1
)
reg add "!REG_PATH2!" /v Value /t REG_SZ /d "Allow" /f >nul 2>&1
reg delete "!REG_PATH2!" /v LastUsedTimeStop /f >nul 2>&1
if %errorlevel% == 0 (
    echo [OK] Доступ для классических приложений включен
)

REM 3. Включаем доступ для всех конкретных приложений
echo.
echo [ШАГ 3] Включение доступа для всех приложений...
for /f "delims=" %%i in ('reg query "!REG_PATH2!" 2^>nul ^| findstr /i "HKEY"') do (
    reg add "%%i" /v Value /t REG_SZ /d "Allow" /f >nul 2>&1
    reg delete "%%i" /v LastUsedTimeStop /f >nul 2>&1
)
echo [OK] Доступ для всех приложений включен

REM 4. Удаляем блокирующие политики
echo.
echo [ШАГ 4] Удаление блокирующих политик...
set "REG_PATH3=HKLM\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy"
reg query "!REG_PATH3!" /v LetAppsAccessMicrophone >nul 2>&1
if %errorlevel% == 0 (
    reg delete "!REG_PATH3!" /v LetAppsAccessMicrophone /f >nul 2>&1
    reg delete "!REG_PATH3!" /v LetAppsAccessMicrophone_UserInControlOfTheseApps /f >nul 2>&1
    reg delete "!REG_PATH3!" /v LetAppsAccessMicrophone_ForceAllowTheseApps /f >nul 2>&1
    reg delete "!REG_PATH3!" /v LetAppsAccessMicrophone_ForceDenyTheseApps /f >nul 2>&1
    echo [OK] Блокирующие политики удалены
) else (
    echo [OK] Блокирующие политики не найдены
)

REM 5. Восстанавливаем службы конфиденциальности
echo.
echo [ШАГ 5] Восстановление служб конфиденциальности...
sc query "UserDataSvc" >nul 2>&1
if %errorlevel% == 0 (
    sc config "UserDataSvc" start= auto >nul 2>&1
    net start "UserDataSvc" >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Служба UserDataSvc восстановлена
    )
)

sc query "PimSvc" >nul 2>&1
if %errorlevel% == 0 (
    sc config "PimSvc" start= auto >nul 2>&1
    net start "PimSvc" >nul 2>&1
    if %errorlevel% == 0 (
        echo [OK] Служба PimSvc восстановлена
    )
)

REM 6. Перезапуск аудио служб
echo.
echo [ШАГ 6] Перезапуск аудио служб...
powershell -NoProfile -Command "Try { Restart-Service -Name 'AudioEndpointBuilder' -Force -ErrorAction Stop; Write-Output '[OK] Служба AudioEndpointBuilder перезапущена' } Catch { Write-Output '[ПРЕДУПРЕЖДЕНИЕ] Не удалось перезапустить AudioEndpointBuilder (' + $_.Exception.Message + ')' }"
powershell -NoProfile -Command "Try { Restart-Service -Name 'Audiosrv' -Force -ErrorAction Stop; Write-Output '[OK] Служба Audiosrv перезапущена' } Catch { Write-Output '[ПРЕДУПРЕЖДЕНИЕ] Не удалось перезапустить Audiosrv (' + $_.Exception.Message + ')' }"
echo Если службы не перезапускаются, перезагрузите компьютер вручную.

echo.
echo ========================================
echo [УСПЕХ] ДОСТУП К МИКРОФОНУ ВОССТАНОВЛЕН!
echo ========================================
echo.
echo ВАЖНО:
echo 1. Перезагрузите компьютер для полного применения изменений
echo 2. Проверьте настройки: Параметры -^> Конфиденциальность -^> Микрофон
echo 3. Убедитесь, что доступ к микрофону включен для всех приложений
echo.
pause
endlocal

