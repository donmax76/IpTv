#Requires -RunAsAdministrator

# Script to enable microphone access after disabling privacy indicators

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "ENABLING MICROPHONE ACCESS" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Method 1: Enable through CapabilityAccessManager
$regPath1 = "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone"
$regName1 = "Value"
$regValue1 = "Allow"

Write-Host "Enabling microphone access..." -ForegroundColor Yellow
Write-Host ""

try {
    if (!(Test-Path $regPath1)) {
        New-Item -Path $regPath1 -Force | Out-Null
    }
    
    Set-ItemProperty -Path $regPath1 -Name $regName1 -Value $regValue1 -Type String -Force -ErrorAction Stop
    Write-Host "[OK] Microphone access enabled (CapabilityAccessManager)" -ForegroundColor Green
}
catch {
    Write-Host "[ERROR] Failed to set access: $_" -ForegroundColor Red
}

# Method 2: Enable through Group Policies (if available)
$regPath2 = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy"
$regName2 = "LetAppsAccessMicrophone"

try {
    if (Test-Path $regPath2) {
        Set-ItemProperty -Path $regPath2 -Name $regName2 -Value 2 -Type DWord -Force -ErrorAction SilentlyContinue
        Write-Host "[OK] Group policies configured" -ForegroundColor Green
    }
}
catch {
    Write-Host "[WARNING] Group policies not available (this is normal)" -ForegroundColor Yellow
}

# Method 3: Enable for classic applications
$regPath3 = "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged"
$regName3 = "Value"

try {
    if (!(Test-Path $regPath3)) {
        New-Item -Path $regPath3 -Force | Out-Null
    }
    
    Set-ItemProperty -Path $regPath3 -Name $regName3 -Value "Allow" -Type String -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] Access for classic applications enabled" -ForegroundColor Green
}
catch {
    Write-Host "[WARNING] Failed to configure access for classic applications" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "[SUCCESS] CHANGES APPLIED!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Now:" -ForegroundColor Yellow
Write-Host "1. Restart AudioRecorder application" -ForegroundColor White
Write-Host "2. Or restart your computer" -ForegroundColor White
Write-Host ""
Write-Host "If problem persists:" -ForegroundColor Yellow
Write-Host "1. Open Windows Settings" -ForegroundColor White
Write-Host "2. Privacy & Security -> Microphone" -ForegroundColor White
Write-Host "3. Enable 'Allow apps to access your microphone'" -ForegroundColor White
Write-Host "4. Enable 'Allow desktop apps to access your microphone'" -ForegroundColor White
Write-Host ""

pause
