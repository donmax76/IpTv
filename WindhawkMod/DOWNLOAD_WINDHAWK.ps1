# Download Windhawk Portable
# This script downloads Windhawk portable version

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$windhawkDir = Join-Path (Split-Path -Parent $scriptDir) "Windhawk"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Windhawk Portable Downloader" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Create directory
if (-not (Test-Path $windhawkDir)) {
    New-Item -ItemType Directory -Path $windhawkDir -Force | Out-Null
    Write-Host "[OK] Created directory: $windhawkDir" -ForegroundColor Green
}

Write-Host "Target directory: $windhawkDir" -ForegroundColor Yellow
Write-Host ""

# Check if already exists
if (Test-Path (Join-Path $windhawkDir "windhawk.exe")) {
    Write-Host "[INFO] Windhawk already exists in target directory" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Would you like to download again? (Y/N)" -ForegroundColor Yellow
    $response = Read-Host
    if ($response -ne "Y" -and $response -ne "y") {
        Write-Host "Skipping download." -ForegroundColor Green
        exit 0
    }
}

Write-Host "Please download Windhawk manually:" -ForegroundColor Yellow
Write-Host ""
Write-Host "1. Go to: https://windhawk.net/download" -ForegroundColor White
Write-Host "2. Download the portable version" -ForegroundColor White
Write-Host "3. Extract to: $windhawkDir" -ForegroundColor White
Write-Host ""
Write-Host "Or use direct download link if available:" -ForegroundColor Yellow
Write-Host ""

# Try to get download link
try {
    $downloadPage = Invoke-WebRequest -Uri "https://windhawk.net/download" -UseBasicParsing -ErrorAction SilentlyContinue
    if ($downloadPage) {
        # Look for download links
        $links = $downloadPage.Links | Where-Object { $_.href -like "*windhawk*.zip" -or $_.href -like "*download*" }
        if ($links) {
            Write-Host "Found potential download links:" -ForegroundColor Green
            foreach ($link in $links | Select-Object -First 3) {
                Write-Host "  - $($link.href)" -ForegroundColor Cyan
            }
        }
    }
} catch {
    Write-Host "[INFO] Could not auto-detect download link" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "After downloading, extract Windhawk to:" -ForegroundColor Yellow
Write-Host "  $windhawkDir" -ForegroundColor Cyan
Write-Host ""
Write-Host "Press any key when done..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# Verify
if (Test-Path (Join-Path $windhawkDir "windhawk.exe")) {
    Write-Host ""
    Write-Host "[OK] Windhawk found! Ready to use." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[WARNING] Windhawk.exe not found in target directory" -ForegroundColor Yellow
    Write-Host "Please make sure you extracted it correctly." -ForegroundColor Yellow
}

