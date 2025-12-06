@echo off
echo Переустановка службы SilentMicService...
echo.

cd /d D:\Android_Projects\TestApp\SilentMicRecorder

set SERVICE_EXE=D:\Android_Projects\TestApp\SilentMicRecorder\bin\Release\net8.0-windows\win-x64\SilentMicService.exe

if not exist "%SERVICE_EXE%" (
    echo ОШИБКА: Файл не найден: %SERVICE_EXE%
    echo.
    echo Ищем файл SilentMicService.exe...
    dir /s /b SilentMicService.exe
    pause
    exit /b 1
)

echo Файл найден: %SERVICE_EXE%
echo.

echo Остановка службы (если запущена)...
sc stop SilentMicService
timeout /t 2 /nobreak >nul

echo Удаление старой службы...
sc delete SilentMicService
timeout /t 2 /nobreak >nul

echo Создание службы с правильным путем...
sc create SilentMicService binPath= "%SERVICE_EXE%" start= demand
if errorlevel 1 (
    echo ОШИБКА при создании службы!
    pause
    exit /b 1
)

echo.
echo Служба успешно переустановлена!
echo Путь: %SERVICE_EXE%
echo.
echo Для запуска службы выполните: sc start SilentMicService
echo.
pause

