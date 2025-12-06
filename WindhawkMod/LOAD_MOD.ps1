# Script to help load WindhawkMod in Windhawk
# This script provides instructions and checks prerequisites

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "WindhawkMod Loader Helper" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$dllPath = Join-Path $scriptDir "bin\x64\Release\WindhawkMod.dll"
$logPath = Join-Path $env:TEMP "WindhawkMod.log"

# Check DLL
Write-Host "Checking DLL..." -ForegroundColor Yellow
if (-not (Test-Path $dllPath)) {
    Write-Host "ERROR: DLL not found at $dllPath" -ForegroundColor Red
    Write-Host "Please build the project first using BUILD.bat" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] DLL found: $dllPath" -ForegroundColor Green
Write-Host ""

# Check Windhawk
Write-Host "Checking Windhawk..." -ForegroundColor Yellow
$windhawkPaths = @(
    "$scriptDir\..\Windhawk\windhawk.exe",
    "${env:ProgramFiles}\Windhawk\windhawk.exe",
    "${env:ProgramFiles(x86)}\Windhawk\windhawk.exe",
    "${env:LOCALAPPDATA}\Programs\Windhawk\windhawk.exe"
)

$windhawkFound = $false
$windhawkPath = $null

foreach ($path in $windhawkPaths) {
    if (Test-Path $path) {
        $windhawkFound = $true
        $windhawkPath = $path
        break
    }
}

if ($windhawkFound) {
    Write-Host "[OK] Windhawk found: $windhawkPath" -ForegroundColor Green
    Write-Host ""
    Write-Host "Would you like to start Windhawk? (Y/N)" -ForegroundColor Yellow
    $response = Read-Host
    if ($response -eq "Y" -or $response -eq "y") {
        Start-Process $windhawkPath
        Write-Host "Windhawk started!" -ForegroundColor Green
    }
} else {
    Write-Host "[WARNING] Windhawk not found in standard locations" -ForegroundColor Yellow
    Write-Host "Please install Windhawk from: https://windhawk.net/" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Or extract portable version to: $scriptDir\..\Windhawk\" -ForegroundColor Yellow
}
Write-Host ""

# Instructions
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Instructions to load mod:" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. Open Windhawk" -ForegroundColor White
Write-Host "2. Go to 'My Mods' tab" -ForegroundColor White
Write-Host "3. Click 'Create a new mod'" -ForegroundColor White
Write-Host "4. Enter mod name: 'Hide Microphone Icon'" -ForegroundColor White
Write-Host "5. Set DLL path to:" -ForegroundColor White
Write-Host "   $dllPath" -ForegroundColor Cyan
Write-Host "6. Enable the mod" -ForegroundColor White
Write-Host "7. Restart Explorer if needed" -ForegroundColor White
Write-Host ""

# Test instructions
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Testing:" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "After loading the mod:" -ForegroundColor White
Write-Host "1. Start audio recording (HiddenAudioService.exe)" -ForegroundColor White
Write-Host "2. Check system tray - microphone icon should be HIDDEN" -ForegroundColor White
Write-Host "3. Check log file: $logPath" -ForegroundColor White
Write-Host ""

# Check log if exists
if (Test-Path $logPath) {
    Write-Host "Current log file (last 10 lines):" -ForegroundColor Yellow
    Write-Host "----------------------------------------" -ForegroundColor Gray
    Get-Content $logPath -Tail 10
    Write-Host "----------------------------------------" -ForegroundColor Gray
    Write-Host ""
}

Write-Host "Press any key to exit..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

