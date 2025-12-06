@echo off
setlocal enabledelayedexpansion

echo ========================================
echo Сборка MicBypassHook.dll
echo ========================================
echo.

REM Проверка наличия Visual Studio
where msbuild.exe >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] MSBuild не найден в PATH
    echo.
    echo Установите Visual Studio или добавьте MSBuild в PATH
    echo Пример: "C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe"
    echo.
    pause
    exit /b 1
)

echo [ШАГ 1] Очистка предыдущих сборок...
if exist "bin" rmdir /s /q "bin"
if exist "obj" rmdir /s /q "obj"
echo [OK] Очистка завершена
echo.

echo [ШАГ 2] Сборка Release версии (x64)...
msbuild MicBypassHook.sln /p:Configuration=Release /p:Platform=x64 /m
if %errorlevel% neq 0 (
    echo.
    echo [ОШИБКА] Сборка не удалась
    pause
    exit /b 1
)

echo.
echo ========================================
echo [УСПЕХ] Сборка завершена!
echo ========================================
echo.
echo DLL находится в: bin\x64\Release\MicBypassHook.dll
echo.
echo Полный путь: %CD%\bin\x64\Release\MicBypassHook.dll
echo.

REM Проверка наличия DLL
if exist "bin\x64\Release\MicBypassHook.dll" (
    echo [OK] MicBypassHook.dll успешно создан
    dir "bin\x64\Release\MicBypassHook.dll"
) else (
    echo [ОШИБКА] DLL не найден после сборки
    echo Проверьте путь: %CD%\bin\x64\Release\
)

echo.
pause

