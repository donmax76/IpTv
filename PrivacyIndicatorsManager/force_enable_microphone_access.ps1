#Requires -RunAsAdministrator

Write-Host " ========================================\ -ForegroundColor Cyan
Write-Host \FORCED MICROPHONE ACCESS RESET\ -ForegroundColor Cyan
Write-Host \========================================\ -ForegroundColor Cyan
Write-Host \\ 

function Set-RegistryValueSafe {
 param (
 [string],
 [string],
 [object],
 [Microsoft.Win32.RegistryValueKind] = [Microsoft.Win32.RegistryValueKind]::String
 )
 try {
 if (-not (Test-Path )) {
 New-Item -Path -Force | Out-Null
 }
 Set-ItemProperty -Path -Name -Value -Type -Force -ErrorAction Stop
 Write-Host \[OK]  ->  = \ -ForegroundColor Green
 }
 catch {
 Write-Host \[ERROR]  ->   \ -ForegroundColor Red
 }
}

# 1. Global access
 = 'HKCU:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone'
Set-RegistryValueSafe 'Value' 'Allow'
Remove-ItemProperty -Path -Name 'LastUsedTimeStop' -ErrorAction SilentlyContinue

# 2. Desktop apps (NonPackaged)
 = Join-Path 'NonPackaged'
Set-RegistryValueSafe 'Value' 'Allow'
Get-ChildItem -ErrorAction SilentlyContinue | ForEach-Object {
 Set-RegistryValueSafe .PsPath 'Value' 'Allow'
 Remove-ItemProperty -Path .PsPath -Name 'LastUsedTimeStop' -ErrorAction SilentlyContinue
}

# 3. Group policy fallback
 = 'HKLM:\\SOFTWARE\\Policies\\Microsoft\\Windows\\AppPrivacy'
Set-RegistryValueSafe 'LetAppsAccessMicrophone' 2 ([Microsoft.Win32.RegistryValueKind]::DWord)
Set-RegistryValueSafe 'LetAppsAccessMicrophone_ForceAllowTheseApps' ''
Set-RegistryValueSafe 'LetAppsAccessMicrophone_ForceDenyTheseApps' ''

# 4. DeviceAccess consent reset
 = 'HKCU:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\DeviceAccess\\Global'
Get-ChildItem -ErrorAction SilentlyContinue | ForEach-Object {
 Set-RegistryValueSafe .PsPath 'Value' 'Allow'
}

# 5. Ensure each AudioRecorder.exe entry is allowed
 = @(
 (Join-Path (Get-Location) 'bin\\Debug\\net8.0-windows\\AudioRecorder.exe'),
 (Join-Path (Get-Location) 'bin\\Release\\net8.0-windows\\AudioRecorder.exe')
) | Where-Object { Test-Path }

foreach ( in ) {
 = .Replace('\\', '|')
 = Join-Path 
 Set-RegistryValueSafe 'Value' 'Allow'
}

Write-Host ''
Write-Host 'Restarting audio services...' -ForegroundColor Yellow
'Audiosrv','AudioEndpointBuilder' | ForEach-Object {
 try {
 Stop-Service -Name -Force -ErrorAction SilentlyContinue
 Start-Service -Name -ErrorAction SilentlyContinue
 Write-Host \[OK] Restarted \ -ForegroundColor Green
 }
 catch {
 Write-Host \[WARNING] Could not restart   \ -ForegroundColor Yellow
 }
}

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '[DONE] MICROPHONE ACCESS RESET' -ForegroundColor Green
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''
Write-Host 'Next steps:' -ForegroundColor Yellow
Write-Host '1. Restart AudioRecorder' -ForegroundColor White
Write-Host '2. If still not working, reboot Windows' -ForegroundColor White
Write-Host '3. Verify Settings -> Privacy & security -> Microphone' -ForegroundColor White
Write-Host ''

pause
