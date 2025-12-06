@echo off
cd /d D:\Android_Projects\TestApp
echo Очистка проекта...
cd SilentMicRecorder
if exist bin rmdir /s /q bin
if exist obj rmdir /s /q obj
dotnet clean SilentMicService.csproj
cd ..
echo.
echo Сборка проекта...
dotnet build SilentMicRecorder/SilentMicService.csproj -c Release --no-incremental
echo.
echo Публикация проекта...
dotnet publish SilentMicRecorder/SilentMicService.csproj -c Release -o dist\SilentMicRecorder --no-build false
echo.
echo Проверка результата...
if exist dist\SilentMicRecorder\SilentMicService.exe (
    echo Файл собран успешно!
    dir dist\SilentMicRecorder\SilentMicService.exe
    echo.
    echo Дата и время файла выше должны быть текущими!
) else (
    echo ОШИБКА: Файл не найден!
)
pause

