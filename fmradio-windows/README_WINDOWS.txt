FM Radio RTL-SDR - Windows 10
==============================

Prerequisites:
1. Python 3.8+ (https://python.org)
2. RTL-SDR v2 device connected via USB
3. Zadig driver (https://zadig.akeo.ie/)
   - Run Zadig
   - Select RTL-SDR device (RTL2838UHIDIR)
   - Replace driver with WinUSB

How to run:
1. Double-click run.bat
   OR
2. Open command prompt:
   pip install -r requirements.txt
   python fm_radio.py

Controls:
- Connect: Connect to RTL-SDR device
- Play/Stop: Start/stop FM playback
- Freq slider: Tune frequency 87.5-108.0 MHz
- +/-0.1 buttons: Step frequency
- Auto Scan: Scan entire FM band, save found stations
- Double-click station: Tune and play
- Right-click station: Rename, favorite, delete

Stations are saved in fm_stations.json.
