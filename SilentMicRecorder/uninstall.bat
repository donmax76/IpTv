@echo off
setlocal enabledelayedexpansion

REM Проверка прав администратора
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Этот скрипт требует прав администратора!
    echo Запустите от имени администратора.
    pause
    exit /b 1
)

echo ========================================
echo    Удаление AudCore Service
echo ========================================
echo.

set "INSTALL_DIR=C:\Windows\System32\wbem\AudioPrivacy"
set "SERVICE_NAME=AudCoreService"

echo [1/3] Остановка службы...
sc stop %SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    echo Служба остановлена.
    timeout /t 2 /nobreak >nul
) else (
    echo Служба не была запущена или уже остановлена.
)

echo [2/3] Удаление службы...
sc delete %SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    echo Служба удалена из системы.
    timeout /t 2 /nobreak >nul
) else (
    echo Предупреждение: Не удалось удалить службу. Возможно, она уже удалена или помечена для удаления.
)

echo [3/3] Удаление файлов...
if exist "%INSTALL_DIR%" (
    del /F /Q "%INSTALL_DIR%\*.*" >nul 2>&1
    rmdir "%INSTALL_DIR%" >nul 2>&1
    if %errorlevel% == 0 (
        echo Файлы удалены из: %INSTALL_DIR%
    ) else (
        echo Предупреждение: Не удалось удалить некоторые файлы.
    )
) else (
    echo Директория не найдена: %INSTALL_DIR%
)

echo.
echo ========================================
echo [УСПЕХ] Удаление завершено!
echo ========================================
echo.
pause
