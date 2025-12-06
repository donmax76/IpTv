@echo off
setlocal enabledelayedexpansion

echo ========================================
echo Инжекция MicBypassHook.dll в процесс
echo ========================================
echo.

echo ВАЖНО: Сначала запустите целевое приложение (AudioRecorder.exe), затем инжектируйте DLL.
echo.

if "%~1"=="" (
    echo Использование: INJECT_DLL.bat ^<имя_процесса.exe^|путь^> [путь_к_dll]
    echo.
    echo Примеры:
    echo   INJECT_DLL.bat AudioRecorder.exe
    echo   INJECT_DLL.bat AudioRecorder.exe "D:\Android_Projects\TestApp\MicBypassHook\bin\x64\Release\MicBypassHook.dll"
    echo.
    pause
    exit /b 1
)

set "PROCESS_ARG=%~1"
set "DLL_PATH=%~2"

if "%DLL_PATH%"=="" (
    set "DLL_PATH=%~dp0bin\x64\Release\MicBypassHook.dll"
)

if not exist "%DLL_PATH%" (
    echo [ОШИБКА] DLL не найдена: "%DLL_PATH%"
    echo Сначала соберите проект командой BUILD.bat
    pause
    exit /b 1
)

echo [ИНФО] Процесс / путь: %PROCESS_ARG%
echo [ИНФО] DLL: %DLL_PATH%
echo.

echo Запуск PowerShell-скрипта для инжекции...
PowerShell -NoProfile -ExecutionPolicy Bypass -File "%~dp0InjectDll.ps1" "%PROCESS_ARG%" "%DLL_PATH%"
set "ps_exit=%errorlevel%"

echo.
if "%ps_exit%"=="0" (
    echo [OK] Инжекция завершена успешно
) else (
    echo [ОШИБКА] Инжекция завершилась с кодом %ps_exit%
)

echo.
pause
exit /b %ps_exit%

