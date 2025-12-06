@echo off
chcp 65001 >nul

REM Переходим в директорию скрипта
cd /d "%~dp0"

echo ========================================
echo Remote Desktop Server
echo ========================================
echo.
echo Текущая директория: %CD%
echo Проверка и установка зависимостей...
echo.

REM Проверяем наличие Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: Python не найден!
    echo Установите Python 3.8 или выше
    pause
    exit /b 1
)

REM Проверяем наличие файла
if not exist "remote_server.py" (
    echo ОШИБКА: Файл remote_server.py не найден!
    echo Убедитесь, что вы запускаете скрипт из правильной директории.
    echo Текущая директория: %CD%
    pause
    exit /b 1
)

REM Проверяем наличие виртуального окружения
if exist "venv\Scripts\python.exe" (
    echo Использую виртуальное окружение...
    call venv\Scripts\activate.bat
    python remote_server.py
) else (
    echo.
    echo ВНИМАНИЕ: Виртуальное окружение не найдено!
    echo.
    echo Создаю виртуальное окружение с зависимостями...
    echo Это займет несколько минут при первом запуске.
    echo.
    call setup_venv.bat
    if exist "venv\Scripts\python.exe" (
        call venv\Scripts\activate.bat
        python remote_server.py
    ) else (
        echo.
        echo ОШИБКА: Не удалось создать виртуальное окружение!
        echo Попробуйте запустить setup_venv.bat вручную
        pause
    )
)

if errorlevel 1 (
    echo.
    echo ОШИБКА при запуске сервера!
    echo.
    pause
)

pause

