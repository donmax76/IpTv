# disable_privacy_indicators.ps1
# Windows-da gizlilik gostergelerinin sonderilmesi
#
# Desteklenen versiyalar:
#   - Windows 10 versiya 1903 (qurulum 18362) ve daha yenileri
#   - Windows 11 (butun versiyalar)
#
# Telebler:
#   - Administrator adindan ise salinmasi
#   - Deyisikliklerin tetbiqi ucun sistemin yeniden yuklenmesi ve ya Explorer-in yeniden baslatilmasi

#Requires -RunAsAdministrator

$regPath = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
$regName = "DisablePrivacyIndicators"
$regValue = 1

# Windows versiyasinin yoxlanilmasi
$osInfo = Get-CimInstance Win32_OperatingSystem
$osBuild = $osInfo.BuildNumber
$minBuild = 18362

if ($osBuild -lt $minBuild) {
    Write-Error "Gizlilik gostergeleri yalniz Windows 10 versiya 1903 (qurulum 18362) ve daha yenilerinde desteklenir. Sizin qurulumunuz: $osBuild"
    exit 1
}

Write-Host "Gizlilik gostergelerinin sonderilmesi..." -ForegroundColor Cyan
Write-Host "Windows versiyasi: $($osInfo.Caption) (qurulum $osBuild)" -ForegroundColor Gray
Write-Host ""

# Reestr acarinin yaradilmasi, eger yoxdursa
if (!(Test-Path $regPath)) {
    Write-Host "Reestr acarinin yaradilmasi: $regPath" -ForegroundColor Yellow
    try {
        New-Item -Path $regPath -Force | Out-Null
        Write-Host "[OK] Acar yaradildi" -ForegroundColor Green
    }
    catch {
        Write-Error "Reestr acari yaradila bilmedi: $_"
        exit 1
    }
}
else {
    Write-Host "Reestr acari artiq movcuddur: $regPath" -ForegroundColor Gray
}

# Deyerin quraşdırılması
Write-Host "Deyerin quraşdırılması: $regName = $regValue" -ForegroundColor Yellow
try {
    Set-ItemProperty -Path $regPath -Name $regName -Value $regValue -Type DWord -ErrorAction Stop
    Write-Host "[OK] Deyer quraşdırıldı" -ForegroundColor Green
}
catch {
    Write-Error "Deyer quraşdırıla bilmedi: $_"
    exit 1
}

# Quraşdırılmış deyerin yoxlanilmasi
$verifyValue = (Get-ItemProperty -Path $regPath -Name $regName -ErrorAction SilentlyContinue).$regName
if ($verifyValue -eq $regValue) {
    Write-Host ""
    Write-Host "[OK] Gosterge ugurla sonderildi!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Deyisikliklerin tetbiqi ucun:" -ForegroundColor Yellow
    Write-Host "  1. Windows Explorer-i yeniden basladin (Explorer)" -ForegroundColor White
    Write-Host "  2. Ve ya kompyuteri yeniden yukleyin" -ForegroundColor White
    Write-Host ""
    
    $restartChoice = Read-Host "Explorer-i indi yeniden baslatmaq? (Y/N)"
    if ($restartChoice -eq 'Y' -or $restartChoice -eq 'y') {
        Write-Host "Explorer-in yeniden baslatilmasi..." -ForegroundColor Yellow
        try {
            Get-Process explorer | Stop-Process -Force
            Start-Sleep -Seconds 2
            Start-Process explorer.exe
            Write-Host "[OK] Explorer yeniden basladildi" -ForegroundColor Green
        }
        catch {
            Write-Warning "Explorer avtomatik olaraq yeniden basladila bilmedi. Onu elle yeniden basladin ve ya kompyuteri yeniden yukleyin."
        }
    }
    else {
        Write-Host ""
        Write-Host "Deyisikliklerin tetbiqi ucun asagidakilardan birini edin:" -ForegroundColor Yellow
        Write-Host "  - Explorer-i elle yeniden basladin (Ctrl+Shift+Esc -> Tapshiriq meneceri -> Explorer -> Yeniden baslat)" -ForegroundColor White
        Write-Host "  - Ve ya kompyuteri yeniden yukleyin" -ForegroundColor White
    }
}
else {
    Write-Warning "Deyer duzgun quraşdırılmadı. Erişim huquqlarinizi yoxlayin."
}
