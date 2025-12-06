@echo off
chcp 65001 >nul
echo ========================================
echo Создание виртуального окружения
echo ========================================
echo.

REM Переходим в директорию скрипта
cd /d "%~dp0"

REM Проверяем наличие Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: Python не найден!
    echo Установите Python 3.8 или выше
    pause
    exit /b 1
)

echo Создаю виртуальное окружение в папке venv...
echo.

REM Удаляем старое окружение если есть
if exist "venv" (
    echo Удаляю старое виртуальное окружение...
    rmdir /s /q venv
)

REM Создаем новое виртуальное окружение
python -m venv venv

if errorlevel 1 (
    echo ОШИБКА: Не удалось создать виртуальное окружение!
    echo Убедитесь, что установлен модуль venv
    pause
    exit /b 1
)

echo.
echo Активирую виртуальное окружение...
call venv\Scripts\activate.bat

echo.
echo Обновляю pip...
python -m pip install --upgrade pip --quiet

echo.
echo Устанавливаю зависимости...
python -m pip install -r requirements.txt

if errorlevel 1 (
    echo.
    echo ОШИБКА при установке зависимостей!
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo Виртуальное окружение успешно создано!
echo ========================================
echo.
echo Все зависимости установлены в папку venv
echo Теперь можно запускать сервер и клиент
echo.
pause

