<#
.SYNOPSIS
    Removes the Virtual Audio Driver package and device instance.

.DESCRIPTION
    This script automates cleanup of the test virtual audio driver by:
      - Ensuring the script runs elevated.
      - Removing the `ROOT\VirtualAudioDriver` device using devcon.exe.
      - Locating the published INF (`oemXX.inf`) that originated from `VirtualAudioDriver.inf`.
      - Deleting the driver package via pnputil (with uninstall + force).

.NOTES
    Run from an elevated PowerShell session.
    If `devcon.exe` resides alongside this script, that copy will be used.
#>

[CmdletBinding()]
param(
    [string]$HardwareId = 'ROOT\VirtualAudioDriver',
    [string]$OriginalInfName = 'VirtualAudioDriver.inf'
)

function Assert-Administrator {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentIdentity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Административные права обязательны. Перезапустите PowerShell от имени администратора.'
    }
}

function Resolve-Devcon {
    param(
        [string]$ScriptDir
    )

    $candidates = @(
        Join-Path $ScriptDir 'devcon.exe',
        'C:\Program Files (x86)\Windows Kits\10\Tools\10.0.26100.0\x64\devcon.exe',
        'C:\Program Files (x86)\Windows Kits\10\Tools\x64\devcon.exe',
        'C:\Program Files (x86)\Windows Kits\10\Tools\10.0.22621.0\x64\devcon.exe'
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $searchRoot = 'C:\Program Files (x86)\Windows Kits\10\Tools'
    if (Test-Path -LiteralPath $searchRoot) {
        $found = Get-ChildItem -LiteralPath $searchRoot -Filter 'devcon.exe' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($found) {
            return $found.FullName
        }
    }

    throw 'Не найден devcon.exe. Скопируйте его рядом со скриптом или укажите путь в коде.'
}

function Remove-Device {
    param(
        [string]$DevconPath,
        [string]$HardwareId
    )

    Write-Host "Удаляю устройство $HardwareId через devcon..." -ForegroundColor Cyan
    $process = Start-Process -FilePath $DevconPath -ArgumentList @('remove', $HardwareId) -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ne 0) {
        Write-Warning "devcon завершился с кодом $($process.ExitCode). Возможно устройство уже отсутствует."
    } else {
        Write-Host 'Устройство удалено или уже отсутствовало.' -ForegroundColor Green
    }
}

function Find-DriverInf {
    param(
        [string]$OriginalInfName
    )

    Write-Host 'Ищу опубликованный INF через pnputil /enum-drivers...' -ForegroundColor Cyan
    $output = pnputil /enum-drivers
    if ($LASTEXITCODE -ne 0) {
        throw 'pnputil /enum-drivers завершился с ошибкой.'
    }

    $blocks = -split ($output -join [Environment]::NewLine), '(\r?\n){2,}'
    foreach ($block in $blocks) {
        if ($block -match [regex]::Escape($OriginalInfName)) {
            if ($block -match 'Published Name\s*:\s*(\S+)') {
                return $Matches[1]
            }
        }
    }

    return $null
}

function Remove-DriverPackage {
    param(
        [string]$PublishedInf
    )

    Write-Host "Удаляю пакет драйвера $PublishedInf через pnputil..." -ForegroundColor Cyan
    $process = Start-Process -FilePath 'pnputil.exe' -ArgumentList @('/delete-driver', $PublishedInf, '/uninstall', '/force') -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ne 0) {
        throw "pnputil завершился с кодом $($process.ExitCode). Проверьте вывод и устраните проблему вручную."
    }

    Write-Host 'Пакет драйвера удалён.' -ForegroundColor Green
}

try {
    Assert-Administrator
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
    $devconPath = Resolve-Devcon -ScriptDir $scriptDir
    Write-Host "Используется devcon: $devconPath" -ForegroundColor DarkGray

    Remove-Device -DevconPath $devconPath -HardwareId $HardwareId

    $publishedInf = Find-DriverInf -OriginalInfName $OriginalInfName
    if (-not $publishedInf) {
        Write-Warning "Не найден опубликованный INF для $OriginalInfName. Возможно пакет уже удалён."
        return
    }

    Remove-DriverPackage -PublishedInf $publishedInf
    Write-Host 'Готово. Перезагрузите систему, если Windows предложит это сделать.' -ForegroundColor Green
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}

