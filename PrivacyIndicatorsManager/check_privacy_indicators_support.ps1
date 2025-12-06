# check_privacy_indicators_support.ps1
# Gizlilik gostergelerinin sonderilmesi desteyinin yoxlanilmasi

$osInfo = Get-CimInstance Win32_OperatingSystem
$osBuild = $osInfo.BuildNumber
$edition = (Get-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion" -Name "EditionID" -ErrorAction SilentlyContinue).EditionID
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Write-Host "=== Gizlilik gostergelerinin sonderilmesi desteyinin yoxlanilmasi ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Sistem melumatlari:" -ForegroundColor Yellow
Write-Host "  OS: $($osInfo.Caption)"
Write-Host "  Qurulum: $osBuild"
Write-Host "  Nesr: $edition"
Write-Host ""

$minBuild = 18362
$supportsIndicators = $osBuild -ge $minBuild
$supportsPolicy = $edition -match "Pro|Enterprise|Education|Server"

Write-Host "Gizlilik gostergelerinin desteyi:" -ForegroundColor Yellow
if ($supportsIndicators) {
    Write-Host "  [OK] Bu Windows versiyasinda desteklenir" -ForegroundColor Green
} else {
    Write-Host "  [X] Windows 10 versiya 1903 (qurulum 18362) ve ya daha yenileri teleb olunur" -ForegroundColor Red
}
Write-Host ""

Write-Host "Qrup siyaseti desteyi:" -ForegroundColor Yellow
if ($supportsPolicy) {
    Write-Host "  [OK] Nesr qrup siyasetini destekleyir" -ForegroundColor Green
} else {
    Write-Host "  [X] Nesr qrup siyasetini desteklemir (Home versiyalari)" -ForegroundColor Yellow
}
Write-Host ""

Write-Host "Administrator huquqlari:" -ForegroundColor Yellow
if ($isAdmin) {
    Write-Host "  [OK] Administrator adindan ise salinib" -ForegroundColor Green
} else {
    Write-Host "  [X] Administrator adindan ise salinmayib (HKLM-e yazmaq ucun teleb olunur)" -ForegroundColor Red
}
Write-Host ""

$regPath = "HKLM:\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
$regName = "DisablePrivacyIndicators"

if (Test-Path $regPath) {
    Write-Host "Reestrin movcud veziyyeti:" -ForegroundColor Yellow
    $currentValue = (Get-ItemProperty -Path $regPath -Name $regName -ErrorAction SilentlyContinue).$regName
    if ($null -ne $currentValue) {
        Write-Host "  Acar movcuddur: DisablePrivacyIndicators = $currentValue"
    } else {
        Write-Host "  Acar movcuddur, lakin DisablePrivacyIndicators quraşdırılmayıb"
    }
} else {
    Write-Host "Reestrin movcud veziyyeti:" -ForegroundColor Yellow
    Write-Host "  Acar movcud deyil (skript terefinden yaradilacaq)"
}
Write-Host ""

Write-Host "=== YEKUN QIYMETLENDIRME ===" -ForegroundColor Cyan
Write-Host ""

$willWork = $supportsIndicators -and ($supportsPolicy -or $edition -match "Home") -and $isAdmin

if ($willWork) {
    Write-Host "[OK] Skript bu sistemde ISLEMELIDIR" -ForegroundColor Green
} else {
    Write-Host "[X] Skript bu sistemde ISLEMEYE BILER" -ForegroundColor Red
    Write-Host ""
    Write-Host "Sebebler:" -ForegroundColor Yellow
    if (-not $supportsIndicators) {
        Write-Host "  - Windows versiyasi cox kohne (Windows 10 1903+ ve ya Windows 11 teleb olunur)"
    }
    if (-not $supportsPolicy -and -not ($edition -match "Home")) {
        Write-Host "  - Windows nesri bu reestr acarini desteklemeye biler"
    }
    if (-not $isAdmin) {
        Write-Host "  - Administrator huquqlari teleb olunur"
    }
}

Write-Host ""
Write-Host "=== ELAVE MELUMAT ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Desteklenen Windows versiyalari:" -ForegroundColor Yellow
Write-Host "  - Windows 10 versiya 1903 (qurulum 18362) ve daha yenileri"
Write-Host "  - Windows 11 (butun versiyalar)"
Write-Host ""
Write-Host "Windows nesrleri:" -ForegroundColor Yellow
Write-Host "  - Windows 10/11 Pro, Enterprise, Education - tam destek"
Write-Host "  - Windows 10/11 Home - isleye biler, lakin garantiya verilmir"
Write-Host ""
Write-Host "Qeyd:" -ForegroundColor Yellow
Write-Host "  Reestr acari HKLM:\SOFTWARE\Policies\Microsoft\Windows\PrivacyIndicators"
Write-Host "  qrup siyaseti siyasetidir. Windows Home-da tesir gostermeye biler."
Write-Host "  Microsoft bu acar haqqinda resmi senedlesme temin etmir."
Write-Host ""
