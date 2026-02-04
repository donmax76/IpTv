@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo ╔══════════════════════════════════════════════════════════╗
echo ║       Remote Desktop v2.0 - Установка зависимостей       ║
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

echo [INFO] Python найден
python --version
echo.

REM Удаляем старое окружение если есть
if exist "venv" (
    echo [INFO] Удаление старого виртуального окружения...
    rmdir /s /q venv
)

REM Создаем виртуальное окружение
echo [INFO] Создание виртуального окружения...
python -m venv venv

if not exist "venv\Scripts\python.exe" (
    echo [ОШИБКА] Не удалось создать виртуальное окружение!
    pause
    exit /b 1
)

REM Активируем и устанавливаем зависимости
echo [INFO] Установка зависимостей...
echo.

call venv\Scripts\activate.bat

REM Обновляем pip
python -m pip install --upgrade pip

REM Устанавливаем зависимости
pip install -r requirements.txt

if errorlevel 1 (
    echo.
    echo [ОШИБКА] Не удалось установить зависимости!
    pause
    exit /b 1
)

echo.
echo ╔══════════════════════════════════════════════════════════╗
echo ║                  Установка завершена!                     ║
echo ╠══════════════════════════════════════════════════════════╣
echo ║  Запуск сервера:  start_server.bat                       ║
echo ║  Запуск клиента:  start_client.bat                       ║
echo ╚══════════════════════════════════════════════════════════╝
echo.

pause
