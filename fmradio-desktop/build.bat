@echo off
REM Build FM Radio Desktop for Windows
REM Requires: kotlinc (Kotlin compiler), JDK 11+

set SRC_DIR=%~dp0src
set OUT_DIR=%~dp0build
set JAR_NAME=fmradio-desktop.jar

echo === Building FM Radio Desktop ===
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%\classes"

echo === Compiling Kotlin sources ===
kotlinc -jvm-target 11 -nowarn ^
    src\com\fmradio\dsp\FmDemodulator.kt ^
    src\com\fmradio\dsp\AudioEqualizer.kt ^
    src\com\fmradio\dsp\RdsDecoder.kt ^
    src\com\fmradio\rtlsdr\RtlTcpClient.kt ^
    src\com\fmradio\ui\DesktopAudioPlayer.kt ^
    src\com\fmradio\ui\MainWindow.kt ^
    -d "%OUT_DIR%\classes"

echo === Creating JAR ===
cd "%OUT_DIR%\classes"
mkdir META-INF
echo Manifest-Version: 1.0> META-INF\MANIFEST.MF
echo Main-Class: com.fmradio.ui.MainWindowKt>> META-INF\MANIFEST.MF

jar cfm "%OUT_DIR%\%JAR_NAME%" META-INF\MANIFEST.MF .

echo === BUILD COMPLETE ===
echo JAR: %OUT_DIR%\%JAR_NAME%
echo.
echo To run: java -jar %OUT_DIR%\%JAR_NAME%
echo.
echo Prerequisites:
echo   1. Install RTL-SDR drivers (Zadig + librtlsdr)
echo   2. Run: rtl_tcp -a 127.0.0.1
echo   3. Then launch this app and click Connect
pause
