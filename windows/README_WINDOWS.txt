TVViewer - Windows Desktop IPTV Player
=======================================

Installation:
1. Install Python 3.8+ from python.org
2. Install VLC Media Player from videolan.org (64-bit recommended)
3. Run: pip install -r requirements.txt
4. Run: python tvviewer.py

Requirements:
- Python 3.8+
- VLC Media Player (must be installed system-wide)
- PyQt5
- python-vlc
- requests

Keyboard shortcuts (Player):
- Space      - Play/Pause
- Up/Down    - Switch channels
- +/-        - Volume up/down
- F          - Toggle favorite
- Esc        - Back to channels
- 0-9        - Direct channel number input

Features (matching Android app):
- M3U/M3U8 playlist loading (URL or file)
- Category filtering
- Channel search
- Favorites
- EPG (TV Guide) with progress
- VLC-based playback (HLS, MPEG-TS, RTSP, etc.)
- Dark theme matching Android design
- Keyboard/remote control support
