@echo off
chcp 65001 >nul
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
echo    Установка AudioCore Service
echo ========================================
echo.

REM Определяем пути
set "INSTALL_DIR=C:\Windows\System32\drivers\AudioPrivacy"
set "SERVICE_NAME=AudioCoreService"
set "SOURCE_DIR=%~dp0bin\Release\net8.0-windows\win-x64\publish"

REM Проверка наличия исходных файлов
if not exist "%SOURCE_DIR%\AudioCore.exe" (
    echo [ОШИБКА] Файл AudioCore.exe не найден!
    echo Искали по пути: %SOURCE_DIR%\AudioCore.exe
    echo.
    echo Сначала выполните сборку проекта:
    echo   dotnet publish -c Release
    echo.
    pause
    exit /b 1
)

echo [1/6] Остановка службы (если запущена)...
sc stop %SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    echo Ожидание остановки службы...
    timeout /t 3 /nobreak >nul
)

echo [2/6] Удаление старой службы (если существует)...
sc query %SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    sc delete %SERVICE_NAME% >nul 2>&1
    if %errorlevel% == 0 (
        echo Старая служба удалена.
        timeout /t 2 /nobreak >nul
    ) else (
        echo Предупреждение: Не удалось удалить службу. Возможно, она помечена для удаления.
        timeout /t 3 /nobreak >nul
    )
)

echo [3/6] Создание директории установки...
if not exist "%INSTALL_DIR%" (
    mkdir "%INSTALL_DIR%" >nul 2>&1
    if %errorlevel% neq 0 (
        echo [ОШИБКА] Не удалось создать директорию: %INSTALL_DIR%
        pause
        exit /b 1
    )
    echo Директория создана: %INSTALL_DIR%
) else (
    echo Директория уже существует: %INSTALL_DIR%
)

echo [4/6] Копирование файлов...
copy /Y "%SOURCE_DIR%\AudioCore.exe" "%INSTALL_DIR%\AudioCore.exe" >nul
if %errorlevel% neq 0 (
    echo [ОШИБКА] Не удалось скопировать AudioCore.exe
    pause
    exit /b 1
)
echo AudioCore.exe скопирован.

if exist "%SOURCE_DIR%\appsettings.json" (
    copy /Y "%SOURCE_DIR%\appsettings.json" "%INSTALL_DIR%\appsettings.json" >nul
    if %errorlevel% == 0 (
        echo appsettings.json скопирован.
    )
)

echo [5/6] Создание службы Windows...
sc create %SERVICE_NAME% binPath= "%INSTALL_DIR%\AudioCore.exe" start= auto DisplayName= "Audio Privacy Core Service" type= own
if %errorlevel% neq 0 (
    echo [ОШИБКА] Не удалось создать службу!
    pause
    exit /b 1
)
echo Служба создана успешно.

REM Добавляем описание службы
sc description %SERVICE_NAME% "Системная служба приватности аудио для Windows" >nul 2>&1

echo [6/6] Запуск службы...
sc start %SERVICE_NAME%
if %errorlevel% neq 0 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось запустить службу автоматически.
    echo Проверьте логи службы и настройки конфигурации.
) else (
    echo Служба успешно запущена!
)

echo.
echo ========================================
echo [УСПЕХ] Установка завершена!
echo ========================================
echo.
echo Служба установлена в: %INSTALL_DIR%
echo Имя службы: %SERVICE_NAME%
echo.
echo Для управления службой используйте:
echo   sc start %SERVICE_NAME%
echo   sc stop %SERVICE_NAME%
echo   sc query %SERVICE_NAME%
echo.
pause

