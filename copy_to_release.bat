@echo off
cd /d D:\Android_Projects\TestApp
if not exist "SilentMicRecorder\bin\Release\net8.0-windows\win-x64" mkdir "SilentMicRecorder\bin\Release\net8.0-windows\win-x64"
copy /Y "dist\SilentMicRecorder\SilentMicService.exe" "SilentMicRecorder\bin\Release\net8.0-windows\win-x64\"
copy /Y "dist\SilentMicRecorder\SilentMicService.pdb" "SilentMicRecorder\bin\Release\net8.0-windows\win-x64\"
copy /Y "dist\SilentMicRecorder\appsettings.json" "SilentMicRecorder\bin\Release\net8.0-windows\win-x64\"
echo Файлы скопированы
dir "SilentMicRecorder\bin\Release\net8.0-windows\win-x64"

