@echo off
chcp 65001 >nul
title Очистка списка "Последняя активность микрофона" (кроме моей программы)

:: Проверка админа
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Запустите от имени администратора!
    pause
    exit /b 1
)

:: Укажите имя вашего exe (без .exe или полное имя процесса)
set "MY_APP=SilentMicService.exe"
:: или по полному пути
:: set "MY_APP=D:\Android_Projects\ConsoleVoiceRecorder\bin\Debug\net8.0\ConsoleVoiceRecorder.exe"

echo Очистка списка, кроме: %MY_APP%
echo.

:: Останавливаем службу приватности
net stop PimSvc >nul 2>&1

:: Основной путь к ConsentStore
set "BASE_KEY=HKCU\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged"

:: Перебираем все подпапки и удаляем LastUsedTimeStart/Stop, кроме вашей программы
for /f "delims=" %%K in ('reg query "%BASE_KEY%" 2^>nul') do (
    for /f "delims=" %%A in ('reg query "%%K" /v Value 2^>nul ^| findstr /i "%MY_APP%"') do (
        echo Сохраняем запись для: %%K
    ) || (
        echo Удаляем запись: %%K
        reg delete "%%K" /v LastUsedTimeStart /f >nul 2>&1
        reg delete "%%K" /v LastUsedTimeStop /f >nul 2>&1
    )
)

:: Перезапускаем службу
net start PimSvc >nul 2>&1

echo.
echo Готово! Все записи удалены, кроме вашей программы.
echo Через 10–20 секунд список обновится — ваша программа останется, остальные исчезнут.
pause