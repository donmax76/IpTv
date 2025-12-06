# check_windows_version.ps1
# Gizlilik gostergeleri reestr acari ucun Windows versiyasinin desteklenmesini yoxlayir

$osInfo = Get-CimInstance Win32_OperatingSystem
$osBuild = $osInfo.BuildNumber
$edition = (Get-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion" -Name "EditionID" -ErrorAction SilentlyContinue).EditionID
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Write-Host "Windows Versiyasi: $($osInfo.Caption) Qurulum $osBuild" -ForegroundColor Cyan
Write-Host "Nesr: $edition" -ForegroundColor Cyan
Write-Host "Administrator: $isAdmin" -ForegroundColor Cyan
Write-Host ""

$minBuild = 18362
$supportsIndicators = $osBuild -ge $minBuild
$supportsPolicy = $edition -match "Pro|Enterprise|Education|Server"

Write-Host "Destek Analizi:" -ForegroundColor Yellow
Write-Host "  Gizlilik gostergeleri (Qurulum >= 18362): $supportsIndicators"
Write-Host "  Qrup siyaseti desteyi: $supportsPolicy"
Write-Host "  Administrator huquqlari: $isAdmin"
Write-Host ""

if ($supportsIndicators -and $isAdmin) {
    if ($supportsPolicy) {
        Write-Host "Netice: Skript bu sistemde ISLEMELIDIR" -ForegroundColor Green
    } else {
        Write-Host "Netice: Skript bu sistemde ISLEYE BILER (Home nesri)" -ForegroundColor Yellow
    }
} else {
    Write-Host "Netice: Skript bu sistemde ISLEMEYECEK" -ForegroundColor Red
}

Write-Host ""
Write-Host "Desteklenen Windows versiyalari:" -ForegroundColor Yellow
Write-Host "  - Windows 10 versiya 1903 (Qurulum 18362) ve daha yenileri"
Write-Host "  - Windows 11 (butun versiyalar)"
Write-Host ""
Write-Host "Desteklenen Nesrler:" -ForegroundColor Yellow
Write-Host "  - Windows 10/11 Pro, Enterprise, Education: Tam destek"
Write-Host "  - Windows 10/11 Home: Isleye biler, lakin garantiya verilmir"
