@echo off
echo ========================================
echo    Сборка и подготовка AudCore
echo ========================================
echo.

cd /d "%~dp0"

echo [1/2] Очистка предыдущей сборки...
dotnet clean >nul 2>&1
if exist "bin" rmdir /s /q "bin" >nul 2>&1
if exist "obj" rmdir /s /q "obj" >nul 2>&1

echo [2/2] Публикация проекта (single file, trimmed)...
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:PublishTrimmed=true -p:TrimMode=link -p:PublishReadyToRun=false -p:DebugType=none -p:DebugSymbols=false --force

if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Сборка завершилась с ошибками!
    pause
    exit /b 1
)

echo.
echo ========================================
echo [УСПЕХ] Сборка завершена!
echo ========================================
echo.
echo Файлы находятся в: bin\Release\net8.0-windows\win-x64\publish\
echo.
echo Для установки запустите setup.bat от имени администратора.
echo.
pause

