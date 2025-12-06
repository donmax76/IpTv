@echo off
echo ========================================
echo    Сборка Аудио Рекордера
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

REM Очистка предыдущей сборки
echo Очистка предыдущей сборки...
dotnet clean

REM Сборка проекта
echo.
echo Сборка проекта в режиме Release...
dotnet build -c Release

if errorlevel 1 (
    echo.
    echo [ОШИБКА] Сборка завершилась с ошибками
    pause
    exit /b 1
)

echo.
echo ========================================
echo [УСПЕХ] Сборка завершена успешно!
echo ========================================
echo.
echo Исполняемый файл находится в:
echo bin\Release\net6.0-windows\AudioRecorder.exe
echo.

pause

