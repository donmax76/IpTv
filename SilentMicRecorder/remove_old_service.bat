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
echo    Удаление старого AudCore
echo ========================================
echo.

set "OLD_SERVICE_NAME=AudCore"

echo [1/2] Остановка службы %OLD_SERVICE_NAME%...
sc stop %OLD_SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    echo Служба остановлена.
    timeout /t 3 /nobreak >nul
) else (
    echo Служба не была запущена или уже остановлена.
)

echo [2/2] Удаление службы %OLD_SERVICE_NAME%...
sc query %OLD_SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    sc delete %OLD_SERVICE_NAME%
    if %errorlevel% == 0 (
        echo Служба %OLD_SERVICE_NAME% успешно удалена!
        timeout /t 2 /nobreak >nul
    ) else (
        echo [ОШИБКА] Не удалось удалить службу.
        echo Возможно, служба помечена для удаления. Подождите несколько секунд и попробуйте снова.
    )
) else (
    echo Служба %OLD_SERVICE_NAME% не найдена в системе.
)

echo.
echo ========================================
echo Готово!
echo ========================================
echo.
echo Для проверки выполните:
echo   sc query %OLD_SERVICE_NAME%
echo.
pause

