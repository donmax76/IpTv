@echo off
echo ========================================
echo    Запуск Аудио Рекордера
echo ========================================
echo.

REM Проверка наличия .NET
dotnet --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] .NET не установлен!
    echo.
    echo Пожалуйста, установите .NET 6.0 или выше с:
    echo https://dotnet.microsoft.com/download
    echo.
    pause
    exit /b 1
)

echo [OK] .NET обнаружен
echo.

REM Восстановление зависимостей
echo Восстановление зависимостей...
dotnet restore
if errorlevel 1 (
    echo [ОШИБКА] Не удалось восстановить зависимости
    pause
    exit /b 1
)

echo.
echo [OK] Зависимости восстановлены
echo.

REM Запуск приложения
echo Запуск приложения...
echo.
dotnet run

pause

