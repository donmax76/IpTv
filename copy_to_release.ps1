$ErrorActionPreference = "Stop"
$source = "D:\Android_Projects\TestApp\dist\SilentMicRecorder"
$dest = "D:\Android_Projects\TestApp\SilentMicRecorder\bin\Release\net8.0-windows\win-x64"

Write-Host "Создание папки назначения..."
New-Item -ItemType Directory -Force -Path $dest | Out-Null

Write-Host "Копирование файлов..."
$files = @("SilentMicService.exe", "SilentMicService.pdb", "appsettings.json")
foreach ($file in $files) {
    $srcPath = Join-Path $source $file
    $dstPath = Join-Path $dest $file
    if (Test-Path $srcPath) {
        Copy-Item $srcPath -Destination $dstPath -Force
        Write-Host "  Скопирован: $file"
    } else {
        Write-Host "  НЕ НАЙДЕН: $file"
    }
}

Write-Host "`nПроверка результата:"
Get-ChildItem $dest | ForEach-Object {
    Write-Host "  $($_.Name) - $($_.Length) байт"
}

