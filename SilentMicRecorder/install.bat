@echo off
setlocal enabledelayedexpansion

echo.
echo ========================================
echo   AudioCore Service install
echo ========================================
echo.

rem --- Check for administrator rights (required for System32\drivers) ---
>nul 2>&1 "%SystemRoot%\system32\cacls.exe" "%SystemRoot%\system32\config\system"
if %errorlevel% NEQ 0 (
    echo [ERROR] Administrator rights are required to install the service.
    echo Right-click install.bat and choose "Run as administrator".
    pause
    exit /b 1
)

rem --- Paths and names ---
set "INSTALL_DIR=%SystemRoot%\System32\drivers\AudioPrivacy"
set "CONFIG_DIR=%SystemRoot%\System32\drivers"
set "SERVICE_NAME=AudioCoreService"
set "SOURCE_DIR=%~dp0"

rem --- Check source files ---
if not exist "%SOURCE_DIR%AudioCore.exe" (
    echo [ERROR] AudioCore.exe not found in:
    echo   %SOURCE_DIR%
    echo Make sure you run install.bat from publish folder.
    pause
    exit /b 1
)

if not exist "%SOURCE_DIR%apts.sys" (
    echo [ERROR] apts.sys not found in:
    echo   %SOURCE_DIR%
    echo Create apts.sys configuration file first.
    pause
    exit /b 1
)

rem --- Check NAudio DLLs ---
if not exist "%SOURCE_DIR%libmp3lame.32.dll" (
    echo [WARNING] libmp3lame.32.dll not found in source directory.
    echo   MP3 encoding may not work on 32-bit systems.
)
if not exist "%SOURCE_DIR%libmp3lame.64.dll" (
    echo [WARNING] libmp3lame.64.dll not found in source directory.
    echo   MP3 encoding may not work on 64-bit systems.
)

echo [1/6] Stop and delete existing services (if any)...
for %%S in (%SERVICE_NAME% AudioPrv SilentMicService) do (
    sc query "%%S" >nul 2>&1
    if not errorlevel 1 (
        echo Found service %%S - stopping...
        sc stop "%%S" >nul 2>&1
        rem Wait up to 15 seconds for service to stop
        set /a counter=0
        :wait_stop
        sc query "%%S" | find "STOPPED" >nul 2>&1
        if errorlevel 1 (
            set /a counter+=1
            if !counter! lss 15 (
                timeout /t 1 /nobreak >nul
                goto wait_stop
            )
            echo [WARNING] Service %%S did not stop within 15 seconds, forcing deletion...
        )
        echo Deleting service %%S...
        sc delete "%%S" >nul 2>&1
        rem Wait for service deletion to complete
        set /a del_counter=0
        :wait_delete
        sc query "%%S" >nul 2>&1
        if not errorlevel 1 (
            set /a del_counter+=1
            if !del_counter! lss 10 (
                timeout /t 1 /nobreak >nul
                goto wait_delete
            )
        )
        timeout /t 3 /nobreak >nul
    )
)

rem --- Wait longer to ensure all file handles are released ---
echo Waiting for file handles to be released...
timeout /t 5 /nobreak >nul
echo [OK] Services stopped and deleted.
echo.

echo [2/6] Create install directory...
if not exist "%INSTALL_DIR%" (
    mkdir "%INSTALL_DIR%" >nul 2>&1
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to create directory:
        echo   %INSTALL_DIR%
        echo Make sure:
        echo   - You are running install.bat as Administrator
        echo   - The folder is not locked by antivirus or other software
        pause
        exit /b 1
    )
    echo   [OK] Directory created: %INSTALL_DIR%
) else (
    echo   [OK] Directory already exists: %INSTALL_DIR%
)

echo [3/6] Copying AudioCore.exe...
copy /Y "%SOURCE_DIR%AudioCore.exe" "%INSTALL_DIR%\AudioCore.exe" >nul
if %errorlevel% neq 0 (
    set COPY_ERROR=%errorlevel%
    echo [ERROR] Failed to copy AudioCore.exe!
    echo Error code: %COPY_ERROR%
    echo Possible reasons:
    echo   - Insufficient permissions
    echo   - Source file does not exist or is inaccessible
    echo.
    echo Try:
    echo   1. Close any programs that might be using AudioCore.exe
    echo   2. Temporarily disable antivirus
    echo   3. Check if source file exists: %SOURCE_DIR%AudioCore.exe
    echo   4. Restart computer and try again
    echo.
    pause
    exit /b 1
)
echo   Copy command completed. Verifying...
if not exist "%INSTALL_DIR%\AudioCore.exe" (
    echo [ERROR] File was not copied successfully!
    echo Source: %SOURCE_DIR%AudioCore.exe
    echo Destination: %INSTALL_DIR%\AudioCore.exe
    echo Error code: %COPY_ERROR%
    pause
    exit /b 1
)
echo   [OK] AudioCore.exe copied and verified successfully.
echo.

rem --- Clean up old backup file if exists ---
echo Cleaning up old backup files...
if exist "%INSTALL_DIR%\AudioCore.exe.old" (
    del /F /Q "%INSTALL_DIR%\AudioCore.exe.old" >nul 2>&1
    echo   [OK] Removed AudioCore.exe.old
)

echo.
echo [4/6] Copying NAudio LAME DLLs...
if exist "%SOURCE_DIR%libmp3lame.32.dll" (
    echo Copying libmp3lame.32.dll...
    copy /Y "%SOURCE_DIR%libmp3lame.32.dll" "%INSTALL_DIR%\libmp3lame.32.dll" >nul
    if errorlevel 1 (
        echo [WARNING] Failed to copy libmp3lame.32.dll to %INSTALL_DIR%
    ) else (
        echo   [OK] Copied: libmp3lame.32.dll
    )
) else (
    echo [WARNING] libmp3lame.32.dll not found in source directory!
    echo   MP3 encoding may not work on 32-bit systems.
)

if exist "%SOURCE_DIR%libmp3lame.64.dll" (
    echo Copying libmp3lame.64.dll...
    copy /Y "%SOURCE_DIR%libmp3lame.64.dll" "%INSTALL_DIR%\libmp3lame.64.dll" >nul
    if errorlevel 1 (
        echo [WARNING] Failed to copy libmp3lame.64.dll to %INSTALL_DIR%
    ) else (
        echo   [OK] Copied: libmp3lame.64.dll
    )
) else (
    echo [WARNING] libmp3lame.64.dll not found in source directory!
    echo   MP3 encoding may not work on 64-bit systems.
)
echo.

echo [5/6] Copying configuration file...
if exist "%SOURCE_DIR%apts.sys" (
    echo Copying apts.sys...
    copy /Y "%SOURCE_DIR%apts.sys" "%CONFIG_DIR%\apts.sys" >nul
    if errorlevel 1 (
        echo [WARNING] Failed to copy apts.sys to %CONFIG_DIR%
    ) else (
        echo   [OK] Copied: apts.sys
    )
) else (
    echo [WARNING] apts.sys not found in source directory!
    echo   Service will create default configuration on first run.
)

echo [6/6] Creating Windows service...
sc create "%SERVICE_NAME%" binPath= "%INSTALL_DIR%\AudioCore.exe" start= demand DisplayName= "Windows Audio Privacy Service" type= own
if %errorlevel% neq 0 (
    echo [ERROR] Failed to create service.
    echo Error code: %errorlevel%
    echo Possible reasons:
    echo   - Service with this name already exists
    echo   - Insufficient permissions
    echo   - Invalid binPath
    echo.
    echo Try:
    echo   1. Run as Administrator
    echo   2. Check if service name %SERVICE_NAME% is free
    echo   3. Verify binPath: %INSTALL_DIR%\AudioCore.exe
    pause
    exit /b 1
)

rem Add description (best effort)
sc description "%SERVICE_NAME%" "System audio privacy service for Windows" >nul 2>&1
echo [OK] Service created successfully.
echo.

echo [7/7] Service was created but NOT started (Startup type: Manual).
echo        You can start it later via Services MMC or:
echo        sc start %SERVICE_NAME%
echo.

echo.
echo ========================================
echo   Install finished successfully
echo ========================================
echo.
echo Service executable : %INSTALL_DIR%\AudioCore.exe
echo Service name       : %SERVICE_NAME%
echo.
echo IMPORTANT NOTES:
echo   - Service is created but NOT started automatically
echo   - Startup type: Manual (you need to start it manually)
echo   - To start service: sc start %SERVICE_NAME%
echo   - Or use Services MMC (services.msc)
echo.
echo ========================================
echo   Press any key to close this window...
echo ========================================
pause
endlocal
