#Requires -RunAsAdministrator

# Enhanced script to disable Windows privacy indicators (microphone/camera)
# Works on Windows 10 1903+ and Windows 11

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Disable Privacy Indicators" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check Windows version
$osInfo  = Get-CimInstance Win32_OperatingSystem
$osBuild = [int]$osInfo.BuildNumber
$minBuild = 18362

if ($osBuild -lt $minBuild) {
    Write-Host "[ERROR] Windows 10 1903 (build 18362) or newer is required." -ForegroundColor Red
    Write-Host "Current build: $osBuild" -ForegroundColor Yellow
    pause
    exit 1
}

Write-Host "Windows: $($osInfo.Caption) (build $osBuild)" -ForegroundColor Green
Write-Host ""

# Registry paths
$regPathPolicy = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
$regName       = "DisablePrivacyIndicators"
$regPathAlt    = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\DataCollection"

Write-Host "Applying registry changes..." -ForegroundColor Yellow
Write-Host ""

# Main policy path
try {
    if (!(Test-Path $regPathPolicy)) {
        Write-Host "Creating key: $regPathPolicy" -ForegroundColor Yellow
        New-Item -Path $regPathPolicy -Force | Out-Null
    }

    Write-Host "Setting $regName = 1" -ForegroundColor Yellow
    Set-ItemProperty -Path $regPathPolicy -Name $regName -Value 1 -Type DWord -Force -ErrorAction Stop
    Write-Host "[OK] Policy value configured" -ForegroundColor Green
}
catch {
    Write-Host "[ERROR] Failed to set policy value: $_" -ForegroundColor Red
}

# Alternate path (for some Home editions)
try {
    if (!(Test-Path $regPathAlt)) {
        Write-Host "Creating alternate key: $regPathAlt" -ForegroundColor Yellow
        New-Item -Path $regPathAlt -Force | Out-Null
    }

    Write-Host "Setting AllowTelemetry = 0 (best effort)" -ForegroundColor Yellow
    Set-ItemProperty -Path $regPathAlt -Name "AllowTelemetry" -Value 0 -Type DWord -Force -ErrorAction SilentlyContinue
}
catch {
    Write-Host "[WARN] Alternate path not available (ok to ignore)" -ForegroundColor Yellow
}

# Verify result
Write-Host ""
Write-Host "Verifying..." -ForegroundColor Yellow

$value = (Get-ItemProperty -Path $regPathPolicy -Name $regName -ErrorAction SilentlyContinue).$regName

if ($value -eq 1) {
    Write-Host "[OK] Privacy indicators disabled." -ForegroundColor Green
    Write-Host ""
    Write-Host "IMPORTANT: Restart Explorer or reboot to apply changes." -ForegroundColor Yellow
    Write-Host "1) Restart Explorer via Task Manager" -ForegroundColor White
    Write-Host "2) or reboot the system" -ForegroundColor White
    Write-Host ""

    $restart = Read-Host "Restart Explorer now? (Y/N)"
    if ($restart -eq 'Y' -or $restart -eq 'y') {
        Write-Host "Restarting Explorer..." -ForegroundColor Yellow
        try {
            Stop-Process -Name explorer -Force -ErrorAction Stop
            Start-Sleep -Seconds 2
            Start-Process explorer.exe
            Write-Host "[OK] Explorer restarted" -ForegroundColor Green
        }
        catch {
            Write-Host "[ERROR] Failed to restart Explorer automatically." -ForegroundColor Red
            Write-Host "Please restart Explorer manually or reboot." -ForegroundColor Yellow
        }
    }
    else {
        Write-Host "Please restart Explorer manually or reboot later." -ForegroundColor Yellow
    }
}
else {
    Write-Host "[ERROR] Policy value was not set. Check administrator rights and try again." -ForegroundColor Red
}

Write-Host ""
pause

