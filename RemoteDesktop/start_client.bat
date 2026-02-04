@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo ╔══════════════════════════════════════════════════════════╗
echo ║              Remote Desktop Client v2.0                   ║
echo ╚══════════════════════════════════════════════════════════╝
echo.

REM Проверяем Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Python не найден!
    echo Установите Python 3.8+ с https://python.org
    pause
    exit /b 1
)

REM Проверяем файл клиента
if not exist "remote_client.py" (
    echo [ОШИБКА] Файл remote_client.py не найден!
    pause
    exit /b 1
)

REM Проверяем виртуальное окружение
if exist "venv\Scripts\python.exe" (
    echo [INFO] Использую виртуальное окружение
    call venv\Scripts\activate.bat
    python remote_client.py %*
) else (
    echo [INFO] Виртуальное окружение не найдено
    echo [INFO] Запускаю установку зависимостей...
    echo.
    call setup_venv.bat
    
    if exist "venv\Scripts\python.exe" (
        call venv\Scripts\activate.bat
        python remote_client.py %*
    ) else (
        echo [ОШИБКА] Не удалось создать окружение!
        pause
        exit /b 1
    )
)

if errorlevel 1 (
    echo.
    echo [ОШИБКА] Клиент завершился с ошибкой!
    pause
)
