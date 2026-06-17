"""
TVViewer - Windows Desktop IPTV Player
Matches Android app design and functionality.
Requires: PyQt5, python-vlc, requests
Install VLC media player for playback.
"""

import sys
import os
import re
import json
import time
import threading
import tempfile
import subprocess
import urllib.request
from datetime import datetime

# Build-time version code (mirrors GITHUB_RUN_NUMBER, written into version.py
# by CI). Used by the GitHub-based updater so each new build has a
# strictly-greater code than the previous one.
try:
    from version import WIN_VERSION_CODE  # type: ignore
except Exception:
    WIN_VERSION_CODE = 0
WIN_VERSION_NAME = "5.4"

# PyInstaller support: add bundled data path
if getattr(sys, 'frozen', False):
    _base_path = sys._MEIPASS
    sys.path.insert(0, _base_path)
else:
    _base_path = os.path.dirname(os.path.abspath(__file__))
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QLineEdit, QListWidget, QListWidgetItem,
    QStackedWidget, QFrame, QProgressBar, QSplitter, QFileDialog,
    QDialog, QDialogButtonBox, QFormLayout, QMessageBox, QScrollArea,
    QComboBox, QSlider, QToolBar, QAction, QSizePolicy, QAbstractItemView,
    QGraphicsOpacityEffect
)
from PyQt5.QtCore import (
    Qt, QTimer, pyqtSignal, QThread, QSize, QUrl, QObject,
    QPropertyAnimation, QEasingCurve, QRect,
)
from PyQt5.QtGui import QFont, QColor, QPalette, QIcon, QPixmap, QKeySequence, QPainter, QBrush, QPen
from PyQt5.QtNetwork import QNetworkAccessManager, QNetworkRequest, QNetworkReply
import hashlib

# Round 220a: автономная сборка под Windows.
# PyInstaller --onedir сборка кладёт libvlc.dll / libvlccore.dll / папку
# plugins/ рядом с TVViewer.exe (через --add-binary в workflow). Чтобы
# python-vlc нашёл их, до его импорта добавляем директорию EXE в DLL
# search path и выставляем VLC_PLUGIN_PATH. Без этого юзер видел
# FileNotFoundError: Could not find module 'libvlc.dll'.
if getattr(sys, 'frozen', False):
    _bundle_dir = os.path.dirname(sys.executable)
    _plugins_dir = os.path.join(_bundle_dir, 'plugins')
    if os.path.isdir(_plugins_dir):
        os.environ['VLC_PLUGIN_PATH'] = _plugins_dir
    if hasattr(os, 'add_dll_directory'):
        try:
            os.add_dll_directory(_bundle_dir)
        except (OSError, FileNotFoundError):
            pass
    # PATH fallback for older Python and dependencies of libvlc.dll
    os.environ['PATH'] = _bundle_dir + os.pathsep + os.environ.get('PATH', '')

try:
    import vlc
    HAS_VLC = True
except (ImportError, OSError, FileNotFoundError):
    HAS_VLC = False

from m3u_parser import fetch_playlist, load_playlist_file, Channel, PlaylistResult
from epg_parser import fetch_epg, get_now_next, get_current_progress, EpgData, normalize_id, fuzzy_key, trace
import channel_meta_lookup

# --- Crash auto-publish to ntfy.sh (token-less) ---
# Same topic as the Android client so the developer reads one stream:
NTFY_TOPIC = "tvviewer-donmax76-50090885b4d9a5e0"

def _publish_to_ntfy(title: str, body: str):
    """Best-effort POST to ntfy.sh. Runs in a daemon thread; never throws."""
    def _send():
        try:
            req = urllib.request.Request(
                f"https://ntfy.sh/{NTFY_TOPIC}",
                data=body.encode('utf-8', 'replace'),
                method='POST',
                headers={
                    'Content-Type': 'text/plain; charset=utf-8',
                    'User-Agent': 'TVViewer-Windows',
                    'Title': title.encode('ascii', 'replace').decode('ascii'),
                    'Tags': 'warning,windows,tvviewer',
                },
            )
            urllib.request.urlopen(req, timeout=8).close()
        except Exception:
            pass
    threading.Thread(target=_send, daemon=True).start()


# --- Crash log path ---
def _log_file_path() -> str:
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        d = os.path.join(base, "TVViewer", "logs")
    else:
        d = os.path.join(os.path.expanduser("~"), ".tvviewer", "logs")
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return os.path.join(d, "tvviewer.log")

def _read_log_tail(path: str, max_chars: int = 4000) -> str:
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - max_chars))
            return f.read()[-max_chars:]
    except Exception:
        return ""

# --- Quality detection from channel name ---
_QUALITY_PATTERNS = [
    ("4K",   re.compile(r'(?i)(?:^|[\s\[\(\.\-_])(4k|uhd|2160p?)(?:$|[\s\]\)\.\-_])')),
    ("FHD",  re.compile(r'(?i)(?:^|[\s\[\(\.\-_])(fhd|fullhd|full[\s\-]?hd|1080p?|1080i)(?:$|[\s\]\)\.\-_])')),
    ("HD",   re.compile(r'(?i)(?:^|[\s\[\(\.\-_])(hd|720p?|h264|ahd)(?:$|[\s\]\)\.\-_])')),
    ("SD",   re.compile(r'(?i)(?:^|[\s\[\(\.\-_])(sd|480p?|360p?|240p?|low)(?:$|[\s\]\)\.\-_])')),
]

# Round 221c (Windows): letter-tile fallback для каналов без логотипа.
# Цветная плашка с инициалами, цвет — детерминированный hash имени,
# один и тот же канал в разных плейлистах получает одинаковую плашку.
_LETTER_TILE_PALETTE = (
    "#7C6CF7", "#00CEC9", "#FF7675", "#00B894",
    "#FDC094", "#74B9FF", "#FD79A8", "#E17055",
    "#A29BFE", "#55EFC4", "#6C5CE7", "#EC9A9A",
)
_LETTER_TILE_CACHE = {}  # (name, size) -> QIcon
_LETTER_TILE_CACHE_CAP = 2000  # Round 233: чтобы не расти бесконечно


def _letter_tile_initials(name: str) -> str:
    parts = [p for p in re.split(r"[ \-_./|]+", name or "") if p]
    if not parts:
        return "?"
    if len(parts) == 1:
        return parts[0][0].upper()
    return (parts[0][0] + parts[1][0]).upper()


def _letter_tile_color(name: str) -> str:
    h = 0
    for c in name or "":
        h = h * 31 + ord(c)
    return _LETTER_TILE_PALETTE[abs(h) % len(_LETTER_TILE_PALETTE)]


def make_letter_tile_icon(name: str, size: int = 48) -> QIcon:
    cache_key = (name or "", size)
    if cache_key in _LETTER_TILE_CACHE:
        return _LETTER_TILE_CACHE[cache_key]
    pm = QPixmap(size, size)
    pm.fill(QColor(0, 0, 0, 0))
    painter = QPainter(pm)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setBrush(QBrush(QColor(_letter_tile_color(name))))
    painter.setPen(Qt.NoPen)
    radius = size * 0.18
    painter.drawRoundedRect(0, 0, size, size, radius, radius)
    painter.setPen(QPen(QColor("white")))
    font = QFont("Segoe UI", int(size * 0.36), QFont.Bold)
    painter.setFont(font)
    painter.drawText(pm.rect(), Qt.AlignCenter, _letter_tile_initials(name))
    painter.end()
    icon = QIcon(pm)
    # Round 233: FIFO эвикция чтобы кэш не съел всю память на больших
    # плейлистах (10k+ каналов).
    if len(_LETTER_TILE_CACHE) >= _LETTER_TILE_CACHE_CAP:
        try:
            _LETTER_TILE_CACHE.pop(next(iter(_LETTER_TILE_CACHE)), None)
        except StopIteration:
            pass
    _LETTER_TILE_CACHE[cache_key] = icon
    return icon


def detect_quality(name: str) -> str:
    """Return '4K' / 'FHD' / 'HD' / 'SD' / '' for a channel name."""
    if not name:
        return ""
    for label, pat in _QUALITY_PATTERNS:
        if pat.search(name):
            return label
    return ""

QUALITY_COLORS = {
    "4K":  "#ff5252",
    "FHD": "#2979ff",
    "HD":  "#00c853",
    "SD":  "#9e9e9e",
}

# Built-in EPG sources used as a fallback so programme info works out-of-the-box.
DEFAULT_EPG_URLS = [
    "http://epg.it999.ru/edem.xml.gz",
    "https://iptvx.one/epg/epg.xml.gz",
]


# ============================================================
# Round 232 (Windows): i18n. Простая словарная схема — таблица
# ключ → перевод по локали. ru/en/uk/az. Дефолт ru. Меняется
# в SettingsPage; некоторые экраны требуют перезапуска (надписи
# фиксируются в момент сборки UI).
# ============================================================
TRANSLATIONS = {
    'ru': {
        'app_name': "M3U IPTV",
        'channels': "Каналы",
        'playlists': "Плейлисты",
        'favorites': "Избранное",
        'recent': "Недавние",
        'tv_guide': "ТВ-гид",
        'settings': "Настройки",
        'search': "Поиск",
        'play': "Играть",
        'pause': "Пауза",
        'prev': "< Пред",
        'next': "След >",
        'back': "< Назад",
        'volume': "Громкость",
        'aspect': "Соотношение",
        'speed': "Скорость",
        'audio_track': "Аудио",
        'sleep_timer': "Таймер сна",
        'pip': "PiP",
        'fullscreen': "Полный экран",
        'language': "Язык",
        'language_changed': "Язык изменён. Перезапустите приложение.",
        'add_playlist': "Добавить плейлист",
        'remove': "Удалить",
        'built_in_playlists': "Встроенные плейлисты",
        'my_playlists': "Мои плейлисты",
        'choose': "— Выберите —",
        'by_language': "По языку",
        'by_category': "По категории",
        'by_country': "По стране",
        'by_region': "По региону",
        'no_logos': "Нет логотипов",
        'channel_count': "{n} каналов",
        'panel_channels': "Каналы",
        'panel_quick': "Быстрые настройки",
        'press_l_for_channels': "L — список каналов, R — настройки",
    },
    'en': {
        'app_name': "M3U IPTV",
        'channels': "Channels",
        'playlists': "Playlists",
        'favorites': "Favorites",
        'recent': "Recent",
        'tv_guide': "TV Guide",
        'settings': "Settings",
        'search': "Search",
        'play': "Play",
        'pause': "Pause",
        'prev': "< Prev",
        'next': "Next >",
        'back': "< Back",
        'volume': "Volume",
        'aspect': "Aspect",
        'speed': "Speed",
        'audio_track': "Audio",
        'sleep_timer': "Sleep timer",
        'pip': "PiP",
        'fullscreen': "Fullscreen",
        'language': "Language",
        'language_changed': "Language changed. Restart the app.",
        'add_playlist': "Add playlist",
        'remove': "Remove",
        'built_in_playlists': "Built-in playlists",
        'my_playlists': "My playlists",
        'choose': "— Choose —",
        'by_language': "By language",
        'by_category': "By category",
        'by_country': "By country",
        'by_region': "By region",
        'no_logos': "No logos",
        'channel_count': "{n} channels",
        'panel_channels': "Channels",
        'panel_quick': "Quick settings",
        'press_l_for_channels': "L — channel list, R — settings",
    },
    'uk': {
        'app_name': "M3U IPTV",
        'channels': "Канали",
        'playlists': "Плейлисти",
        'favorites': "Обране",
        'recent': "Нещодавні",
        'tv_guide': "Телепрограма",
        'settings': "Налаштування",
        'search': "Пошук",
        'play': "Відтворити",
        'pause': "Пауза",
        'prev': "< Попер",
        'next': "Далі >",
        'back': "< Назад",
        'volume': "Гучність",
        'aspect': "Співвідношення",
        'speed': "Швидкість",
        'audio_track': "Аудіо",
        'sleep_timer': "Таймер сну",
        'pip': "PiP",
        'fullscreen': "На весь екран",
        'language': "Мова",
        'language_changed': "Мову змінено. Перезапустіть застосунок.",
        'add_playlist': "Додати плейлист",
        'remove': "Видалити",
        'built_in_playlists': "Вбудовані плейлисти",
        'my_playlists': "Мої плейлисти",
        'choose': "— Виберіть —",
        'by_language': "За мовою",
        'by_category': "За категорією",
        'by_country': "За країною",
        'by_region': "За регіоном",
        'no_logos': "Немає логотипів",
        'channel_count': "{n} каналів",
        'panel_channels': "Канали",
        'panel_quick': "Швидкі налаштування",
        'press_l_for_channels': "L — список каналів, R — налаштування",
    },
    'az': {
        'app_name': "M3U IPTV",
        'channels': "Kanallar",
        'playlists': "Pleylistlər",
        'favorites': "Seçilmişlər",
        'recent': "Son baxılanlar",
        'tv_guide': "TV proqramı",
        'settings': "Tənzimləmələr",
        'search': "Axtar",
        'play': "Oxut",
        'pause': "Dayandır",
        'prev': "< Əvvəlki",
        'next': "Sonrakı >",
        'back': "< Geri",
        'volume': "Səs",
        'aspect': "Nisbət",
        'speed': "Sürət",
        'audio_track': "Audio",
        'sleep_timer': "Yuxu taymeri",
        'pip': "PiP",
        'fullscreen': "Tam ekran",
        'language': "Dil",
        'language_changed': "Dil dəyişdi. Tətbiqi yenidən başladın.",
        'add_playlist': "Pleylist əlavə et",
        'remove': "Sil",
        'built_in_playlists': "Daxili pleylistlər",
        'my_playlists': "Mənim pleylistlərim",
        'choose': "— Seçin —",
        'by_language': "Dilə görə",
        'by_category': "Kateqoriyaya görə",
        'by_country': "Ölkəyə görə",
        'by_region': "Regiona görə",
        'no_logos': "Loqo yoxdur",
        'channel_count': "{n} kanal",
        'panel_channels': "Kanallar",
        'panel_quick': "Tez tənzimləmələr",
        'press_l_for_channels': "L — kanal siyahısı, R — tənzimləmələr",
    },
}

_CURRENT_LANG = 'ru'


def set_ui_language(lang: str):
    global _CURRENT_LANG
    _CURRENT_LANG = lang if lang in TRANSLATIONS else 'ru'


def t(key: str, **kwargs) -> str:
    """Lookup a translation. Falls back to ru, then to the key itself."""
    table = TRANSLATIONS.get(_CURRENT_LANG) or TRANSLATIONS['ru']
    s = table.get(key) or TRANSLATIONS['ru'].get(key) or key
    if kwargs:
        try:
            return s.format(**kwargs)
        except Exception:
            return s
    return s

# --- Colors matching Android dark theme ---
COLORS = {
    'background': '#0F0F1A',
    'surface': '#1A1A2E',
    'card': '#222240',
    'card_hover': '#2C2C50',
    'primary': '#7C6CF7',
    'primary_dark': '#5A4DC5',
    'secondary': '#4ECDC4',
    'text_primary': '#FFFFFF',
    'text_secondary': '#B0B0C0',
    'text_hint': '#707088',
    'favorite_active': '#FF6B6B',
    'favorite_inactive': '#555570',
    'error': '#FF6B6B',
}

STYLESHEET = f"""
QMainWindow, QWidget {{
    background-color: {COLORS['background']};
    color: {COLORS['text_primary']};
    font-family: 'Segoe UI', Arial, sans-serif;
}}
QLabel {{
    color: {COLORS['text_primary']};
}}
QLineEdit {{
    background-color: {COLORS['surface']};
    color: {COLORS['text_primary']};
    border: 1px solid {COLORS['card']};
    border-radius: 8px;
    padding: 8px 12px;
    font-size: 14px;
}}
QLineEdit:focus {{
    border: 2px solid {COLORS['primary']};
}}
QPushButton {{
    background-color: {COLORS['card']};
    color: {COLORS['text_primary']};
    border: none;
    border-radius: 8px;
    padding: 8px 16px;
    font-size: 13px;
    min-height: 32px;
}}
QPushButton:hover {{
    background-color: {COLORS['primary_dark']};
}}
QPushButton:pressed {{
    background-color: {COLORS['primary']};
}}
QPushButton#primaryBtn {{
    background-color: {COLORS['primary']};
    font-weight: bold;
}}
QPushButton#primaryBtn:hover {{
    background-color: {COLORS['primary_dark']};
}}
QPushButton#navBtn {{
    background-color: transparent;
    color: {COLORS['text_hint']};
    border-radius: 4px;
    padding: 10px 16px;
    font-size: 13px;
    font-weight: bold;
}}
QPushButton#navBtn:hover {{
    background-color: {COLORS['surface']};
    color: {COLORS['text_primary']};
}}
QPushButton#navBtnActive {{
    background-color: {COLORS['primary']};
    color: white;
    border-radius: 4px;
    padding: 10px 16px;
    font-size: 13px;
    font-weight: bold;
}}
QPushButton#categoryBtn {{
    background-color: {COLORS['surface']};
    border-radius: 16px;
    padding: 6px 16px;
    font-size: 13px;
}}
QPushButton#categoryBtnActive {{
    background-color: {COLORS['primary']};
    border-radius: 16px;
    padding: 6px 16px;
    font-size: 13px;
    font-weight: bold;
}}
QPushButton#favBtn {{
    background-color: transparent;
    font-size: 18px;
    min-width: 36px;
    max-width: 36px;
    padding: 4px;
}}
QListWidget {{
    background-color: {COLORS['background']};
    border: none;
    outline: none;
}}
QListWidget::item {{
    background-color: {COLORS['card']};
    border-radius: 12px;
    margin: 3px 8px;
    padding: 10px 12px;
    color: {COLORS['text_primary']};
}}
QListWidget::item:selected {{
    background-color: {COLORS['primary_dark']};
    border: 2px solid {COLORS['primary']};
}}
QListWidget::item:hover {{
    background-color: #2A2A50;
}}
QProgressBar {{
    background-color: {COLORS['surface']};
    border: none;
    border-radius: 2px;
    max-height: 4px;
}}
QProgressBar::chunk {{
    background-color: {COLORS['secondary']};
    border-radius: 2px;
}}
QScrollArea {{
    border: none;
    background-color: {COLORS['background']};
}}
QSplitter::handle {{
    background-color: {COLORS['card']};
    width: 2px;
}}
QComboBox {{
    background-color: {COLORS['surface']};
    color: {COLORS['text_primary']};
    border: 1px solid {COLORS['card']};
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 13px;
}}
QSlider::groove:horizontal {{
    background: {COLORS['surface']};
    height: 6px;
    border-radius: 3px;
}}
QSlider::handle:horizontal {{
    background: {COLORS['primary']};
    width: 16px;
    height: 16px;
    margin: -5px 0;
    border-radius: 8px;
}}
QSlider::sub-page:horizontal {{
    background: {COLORS['primary']};
    border-radius: 3px;
}}
"""

CONFIG_FILE = "tvviewer_config.json"


class Config:
    """Settings persistence - mirrors Android AppPreferences."""
    def __init__(self):
        self.playlists = []  # [{name, url}]
        self.favorites = set()
        self.last_playlist_url = ""
        self.last_playlist_name = ""
        self.last_epg_url = ""
        self.last_channel_url = ""
        self.last_category = "All"
        self.volume = 80
        self.network_caching_ms = 3000     # VLC :network-caching
        self.autoplay_last = False         # open last channel on startup
        self.remember_fullscreen = False   # restore fullscreen on player open
        self.sleep_timer_minutes = 0       # 0 = off
        self.recent_urls = []              # most recent first, capped to RECENT_LIMIT
        self.channel_sort = "default"      # default | name | number | quality
        self.epg_urls = []                 # additional EPG URLs (merged with playlist's url-tvg)
        self.user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        self.always_on_top = False
        self.hardware_decode = True
        self.audio_output = ""             # VLC --aout: "" auto / directsound / mmdevice / waveout
        self.per_channel_state = {}        # url -> {volume, aspect_idx, speed_idx, position_ms, audio_track}
        # Round 232 (Windows): UI language. ru/en/uk/az. На первом
        # запуске возьмём системную локаль, потом юзер может сменить в
        # настройках.
        import locale as _locale
        sys_lang = ""
        try:
            sys_lang = (_locale.getdefaultlocale()[0] or "")[:2].lower()
        except Exception:
            pass
        self.ui_language = sys_lang if sys_lang in ("ru", "en", "uk", "az") else "ru"
        self.load()

    RECENT_LIMIT = 30
    PER_CHANNEL_LIMIT = 200  # cap stored per-channel state entries

    def push_recent(self, url: str):
        if not url:
            return
        try:
            self.recent_urls = [u for u in self.recent_urls if u != url]
        except Exception:
            self.recent_urls = []
        self.recent_urls.insert(0, url)
        if len(self.recent_urls) > self.RECENT_LIMIT:
            self.recent_urls = self.recent_urls[:self.RECENT_LIMIT]

    def get_channel_state(self, url: str) -> dict:
        if not url:
            return {}
        return dict(self.per_channel_state.get(url, {}))

    def save_channel_state(self, url: str, state: dict):
        if not url or not isinstance(state, dict):
            return
        # Filter to known keys to keep storage tight
        cleaned = {k: state[k] for k in
                   ('volume', 'aspect_idx', 'speed_idx', 'position_ms', 'audio_track')
                   if k in state}
        if not cleaned:
            return
        # LRU-ish trim: if cap reached and url is new, drop oldest insertion
        if url not in self.per_channel_state and len(self.per_channel_state) >= self.PER_CHANNEL_LIMIT:
            try:
                first_key = next(iter(self.per_channel_state))
                self.per_channel_state.pop(first_key, None)
            except StopIteration:
                pass
        self.per_channel_state[url] = cleaned

    def load(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                self.playlists = data.get('playlists', [])
                self.favorites = set(data.get('favorites', []))
                self.last_playlist_url = data.get('last_playlist_url', '')
                self.last_playlist_name = data.get('last_playlist_name', '')
                self.last_epg_url = data.get('last_epg_url', '')
                self.last_channel_url = data.get('last_channel_url', '')
                self.last_category = data.get('last_category', 'All')
                self.volume = int(data.get('volume', 80))
                self.network_caching_ms = int(data.get('network_caching_ms', 3000))
                self.autoplay_last = bool(data.get('autoplay_last', False))
                self.remember_fullscreen = bool(data.get('remember_fullscreen', False))
                self.sleep_timer_minutes = int(data.get('sleep_timer_minutes', 0))
                self.recent_urls = list(data.get('recent_urls', []))[:self.RECENT_LIMIT]
                self.channel_sort = data.get('channel_sort', 'default')
                self.epg_urls = list(data.get('epg_urls', []))
                self.user_agent = data.get('user_agent', self.user_agent)
                self.always_on_top = bool(data.get('always_on_top', False))
                self.hardware_decode = bool(data.get('hardware_decode', True))
                self.audio_output = data.get('audio_output', '')
                pcs = data.get('per_channel_state', {})
                if isinstance(pcs, dict):
                    self.per_channel_state = pcs
                # Round 232: загружаем сохранённый язык если он есть.
                stored_lang = data.get('ui_language', '')
                if stored_lang in ("ru", "en", "uk", "az"):
                    self.ui_language = stored_lang
            except Exception:
                pass

    def save(self):
        data = {
            'playlists': self.playlists,
            'favorites': list(self.favorites),
            'last_playlist_url': self.last_playlist_url,
            'last_playlist_name': self.last_playlist_name,
            'last_epg_url': self.last_epg_url,
            'last_channel_url': self.last_channel_url,
            'last_category': self.last_category,
            'volume': self.volume,
            'network_caching_ms': self.network_caching_ms,
            'autoplay_last': self.autoplay_last,
            'remember_fullscreen': self.remember_fullscreen,
            'sleep_timer_minutes': self.sleep_timer_minutes,
            'recent_urls': self.recent_urls,
            'channel_sort': self.channel_sort,
            'epg_urls': self.epg_urls,
            'user_agent': self.user_agent,
            'always_on_top': self.always_on_top,
            'hardware_decode': self.hardware_decode,
            'audio_output': self.audio_output,
            'per_channel_state': self.per_channel_state,
            'ui_language': getattr(self, 'ui_language', 'ru'),
        }
        try:
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass


class LoadPlaylistThread(QThread):
    """Background thread for loading playlists."""
    finished = pyqtSignal(object)
    error = pyqtSignal(str)

    def __init__(self, url):
        super().__init__()
        self.url = url

    def run(self):
        try:
            if os.path.isfile(self.url):
                result = load_playlist_file(self.url)
            else:
                result = fetch_playlist(self.url)
            self.finished.emit(result)
        except Exception as e:
            self.error.emit(str(e))


class UpdateCheckThread(QThread):
    """Queries GitHub Releases for the latest Windows build.

    Mirrors UpdateChecker.kt on Android: parses `win-v5.4-build<run>` tags
    and treats the build number as a versionCode.
    """
    finished = pyqtSignal(object)  # dict with keys: code, name, tag, url, notes — or None

    REPO = "donmax76/iptv"

    def run(self):
        try:
            req = urllib.request.Request(
                f"https://api.github.com/repos/{self.REPO}/releases?per_page=20",
                headers={
                    'Accept': 'application/vnd.github.v3+json',
                    'User-Agent': 'TVViewer-Windows',
                },
            )
            with urllib.request.urlopen(req, timeout=15) as r:
                data = json.loads(r.read())
            wins = [rel for rel in data
                    if isinstance(rel, dict) and rel.get('tag_name', '').startswith('win-')]
            best = None
            for rel in wins:
                m = re.search(r'build(\d+)', rel.get('tag_name', ''))
                if not m:
                    continue
                code = int(m.group(1))
                if best is None or code > best[0]:
                    best = (code, rel)
            if best is None:
                self.finished.emit(None)
                return
            code, rel = best
            asset = next((a for a in rel.get('assets', [])
                          if a.get('name', '').lower().endswith('.exe')), None)
            self.finished.emit({
                'code': code,
                'name': rel.get('name', ''),
                'tag': rel.get('tag_name', ''),
                'url': asset['browser_download_url'] if asset else rel.get('html_url', ''),
                'notes': rel.get('body', ''),
            })
        except Exception:
            self.finished.emit(None)


class DownloadUpdateThread(QThread):
    """Downloads a new EXE to a temp file and reports progress."""
    progress = pyqtSignal(int)  # 0..100
    finished = pyqtSignal(object)  # path or None
    error = pyqtSignal(str)

    def __init__(self, url: str, parent=None):
        super().__init__(parent)
        self.url = url

    def run(self):
        tmp_dir = tempfile.gettempdir()
        out_path = os.path.join(tmp_dir, 'TVViewer.update.exe')
        try:
            req = urllib.request.Request(self.url, headers={'User-Agent': 'TVViewer-Windows'})
            with urllib.request.urlopen(req, timeout=30) as resp:
                total = int(resp.headers.get('Content-Length') or 0)
                read = 0
                with open(out_path, 'wb') as f:
                    while True:
                        chunk = resp.read(64 * 1024)
                        if not chunk:
                            break
                        f.write(chunk)
                        read += len(chunk)
                        if total > 0:
                            self.progress.emit(int(read * 100 / total))
            self.finished.emit(out_path)
        except Exception as e:
            self.error.emit(str(e))
            try: os.remove(out_path)
            except Exception: pass


def _swap_self_and_restart(new_exe_path: str):
    """Swap the running .exe with `new_exe_path` and restart.

    Only works for a frozen PyInstaller build. Returns True if a swap
    script was launched (caller should quit immediately afterwards).
    """
    if not getattr(sys, 'frozen', False):
        return False
    current = sys.executable
    if not current.lower().endswith('.exe'):
        return False
    try:
        bat = (
            "@echo off\r\n"
            "ping -n 3 127.0.0.1 >nul\r\n"
            f'move /Y "{new_exe_path}" "{current}" >nul 2>&1\r\n'
            f'start "" "{current}"\r\n'
            'del "%~f0"\r\n'
        )
        bat_path = os.path.join(tempfile.gettempdir(), 'tvviewer_update.bat')
        with open(bat_path, 'w', encoding='ascii') as f:
            f.write(bat)
        flags = 0
        if hasattr(subprocess, 'DETACHED_PROCESS'):
            flags |= subprocess.DETACHED_PROCESS
        if hasattr(subprocess, 'CREATE_NO_WINDOW'):
            flags |= subprocess.CREATE_NO_WINDOW
        subprocess.Popen(['cmd', '/c', bat_path], creationflags=flags)
        return True
    except Exception:
        return False


class LoadEpgThread(QThread):
    """Background thread for loading EPG data from one or more URLs.

    Mirrors Android EpgRepository.fetchAll: emits live progress updates
    (sent over progress signal), traces every step into tvviewer_trace.txt
    so user can debug a stuck refresh, and filters by playlist channels
    so a 50-MB XMLTV doesn't keep 5000 unused channels in memory.
    """
    finished = pyqtSignal(object)
    progress = pyqtSignal(str)

    def __init__(self, urls, channel_filter=None):
        super().__init__()
        if isinstance(urls, str):
            urls = [urls]
        self.urls = [u for u in (urls or []) if u]
        self.channel_filter = set(channel_filter) if channel_filter else None

    def run(self):
        trace("EPG", f"fetchAll start: {len(self.urls)} sources, "
                     f"filter={len(self.channel_filter) if self.channel_filter else 0} keys")
        merged = {}
        cb = lambda s: self.progress.emit(s)
        for url in self.urls:
            try:
                data = fetch_epg(url, progress=cb, channel_filter=self.channel_filter)
                if isinstance(data, dict):
                    merged.update(data)
            except Exception as e:
                trace("EPG", f"source failed: {url} → {type(e).__name__}: {e}")
                continue
        trace("EPG", f"fetchAll done: merged={len(merged)} channels")
        self.finished.emit(merged)


class LogoCache(QObject):
    """Async logo loader with disk cache, shared across pages.

    Limits concurrent network requests to avoid hammering the network thread
    and starving the UI when a 5000-channel playlist asks for icons all at once.
    """
    logo_ready = pyqtSignal()  # coalesced: fires at most every ~400ms after a batch of loads

    MAX_CONCURRENT = 6
    MAX_ICONS_IN_MEM = 2000  # very rough cap to prevent unbounded growth

    def __init__(self, cache_dir: str, parent=None):
        super().__init__(parent)
        self.cache_dir = cache_dir
        try:
            os.makedirs(cache_dir, exist_ok=True)
        except Exception:
            pass
        self.icons: dict = {}      # url -> QIcon
        self.missing: set = set()  # urls that failed to load
        self._inflight: set = set()
        self._queue: list = []     # URLs waiting for a slot
        self.nam = QNetworkAccessManager(self)
        self.nam.finished.connect(self._on_finished)
        self._emit_timer = QTimer(self)
        self._emit_timer.setSingleShot(True)
        self._emit_timer.setInterval(400)
        self._emit_timer.timeout.connect(self.logo_ready.emit)

    def _path(self, url: str) -> str:
        return os.path.join(
            self.cache_dir,
            hashlib.sha1(url.encode('utf-8', 'ignore')).hexdigest()[:16] + '.png')

    def get(self, url: str):
        """Return QIcon immediately (possibly None). Enqueues async download on miss."""
        if not url or url in self.missing:
            return None
        cached = self.icons.get(url)
        if cached is not None:
            return cached
        disk = self._path(url)
        if os.path.exists(disk):
            pm = QPixmap(disk)
            if not pm.isNull():
                icon = QIcon(pm)
                if len(self.icons) < self.MAX_ICONS_IN_MEM:
                    self.icons[url] = icon
                return icon
        if url not in self._inflight and url not in self._queue:
            self._queue.append(url)
            self._pump()
        return None

    def _pump(self):
        while self._queue and len(self._inflight) < self.MAX_CONCURRENT:
            url = self._queue.pop(0)
            if url in self._inflight or url in self.icons or url in self.missing:
                continue
            self._inflight.add(url)
            try:
                req = QNetworkRequest(QUrl(url))
                req.setRawHeader(b"User-Agent", b"TVViewer/5.3")
                reply = self.nam.get(req)
                reply.setProperty('url', url)
            except Exception:
                self._inflight.discard(url)
                self.missing.add(url)

    def _on_finished(self, reply: QNetworkReply):
        url = reply.property('url')
        self._inflight.discard(url)
        try:
            if reply.error() != QNetworkReply.NoError:
                self.missing.add(url)
                return
            data = bytes(reply.readAll())
            if not data:
                self.missing.add(url)
                return
            pm = QPixmap()
            if not pm.loadFromData(data):
                self.missing.add(url)
                return
            if pm.width() > 128 or pm.height() > 128:
                pm = pm.scaled(128, 128, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            try:
                with open(self._path(url), 'wb') as f:
                    f.write(data)
            except Exception:
                pass
            if len(self.icons) < self.MAX_ICONS_IN_MEM:
                self.icons[url] = QIcon(pm)
            if not self._emit_timer.isActive():
                self._emit_timer.start()
        finally:
            reply.deleteLater()
            self._pump()


# ============================================================
# Playlists Page
# ============================================================
class PlaylistsPage(QWidget):
    playlist_selected = pyqtSignal(str, str)  # name, url

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.setAcceptDrops(True)
        self.init_ui()

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            for url in event.mimeData().urls():
                if url.isLocalFile() and url.toLocalFile().lower().endswith(('.m3u', '.m3u8')):
                    event.acceptProposedAction()
                    return
        event.ignore()

    def dropEvent(self, event):
        added = 0
        for url in event.mimeData().urls():
            if not url.isLocalFile():
                continue
            path = url.toLocalFile()
            if not path.lower().endswith(('.m3u', '.m3u8')):
                continue
            name = os.path.splitext(os.path.basename(path))[0]
            self.config.playlists.append({'name': name, 'url': path})
            added += 1
        if added:
            self.config.save()
            self.refresh_list()
            event.acceptProposedAction()

    # Round 220: parity with Android — built-in iptv-org bundles exposed
    # via four comboboxes (by language / category / country / region),
    # custom playlists shown as a list below.
    BUILTIN_CATEGORIES = [
        ('By language', [
            ('🌐 Русскоязычные', 'https://iptv-org.github.io/iptv/languages/rus.m3u'),
            ('🌐 Українські',    'https://iptv-org.github.io/iptv/languages/ukr.m3u'),
            ('🌐 Azərbaycanca',  'https://iptv-org.github.io/iptv/languages/aze.m3u'),
            ('🌐 Türkçe',        'https://iptv-org.github.io/iptv/languages/tur.m3u'),
            ('🌐 English',       'https://iptv-org.github.io/iptv/languages/eng.m3u'),
            ('🌐 Deutsch',       'https://iptv-org.github.io/iptv/languages/deu.m3u'),
            ('🌐 Español',       'https://iptv-org.github.io/iptv/languages/spa.m3u'),
        ]),
        ('By category', [
            ('⚽ Sports',     'https://iptv-org.github.io/iptv/categories/sports.m3u'),
            ('📰 News',       'https://iptv-org.github.io/iptv/categories/news.m3u'),
            ('🎵 Music',      'https://iptv-org.github.io/iptv/categories/music.m3u'),
            ('🎬 Movies',     'https://iptv-org.github.io/iptv/categories/movies.m3u'),
            ('📺 Entertain.', 'https://iptv-org.github.io/iptv/categories/entertainment.m3u'),
            ('🧒 Kids',       'https://iptv-org.github.io/iptv/categories/kids.m3u'),
            ('📚 Documentary','https://iptv-org.github.io/iptv/categories/documentary.m3u'),
            ('🍳 Cooking',    'https://iptv-org.github.io/iptv/categories/cooking.m3u'),
        ]),
        ('By country', [
            ('🇷🇺 Россия',     'https://iptv-org.github.io/iptv/countries/ru.m3u'),
            ('🇺🇦 Украина',    'https://iptv-org.github.io/iptv/countries/ua.m3u'),
            ('🇧🇾 Беларусь',   'https://iptv-org.github.io/iptv/countries/by.m3u'),
            ('🇰🇿 Казахстан',  'https://iptv-org.github.io/iptv/countries/kz.m3u'),
            ('🇦🇿 Азербайджан', 'https://iptv-org.github.io/iptv/countries/az.m3u'),
            ('🇬🇪 Грузия',     'https://iptv-org.github.io/iptv/countries/ge.m3u'),
            ('🇲🇩 Молдова',    'https://iptv-org.github.io/iptv/countries/md.m3u'),
            ('🇦🇲 Армения',    'https://iptv-org.github.io/iptv/countries/am.m3u'),
            ('🇺🇿 Узбекистан', 'https://iptv-org.github.io/iptv/countries/uz.m3u'),
            ('🇰🇬 Кыргызстан', 'https://iptv-org.github.io/iptv/countries/kg.m3u'),
            ('🇹🇯 Таджикистан','https://iptv-org.github.io/iptv/countries/tj.m3u'),
            ('🇵🇱 Польша',     'https://iptv-org.github.io/iptv/countries/pl.m3u'),
            ('🇩🇪 Германия',   'https://iptv-org.github.io/iptv/countries/de.m3u'),
            ('🇬🇧 UK',         'https://iptv-org.github.io/iptv/countries/uk.m3u'),
            ('🇺🇸 США',        'https://iptv-org.github.io/iptv/countries/us.m3u'),
            ('🇨🇦 Канада',     'https://iptv-org.github.io/iptv/countries/ca.m3u'),
            ('🇹🇷 Турция',     'https://iptv-org.github.io/iptv/countries/tr.m3u'),
            ('🇮🇷 Иран',       'https://iptv-org.github.io/iptv/countries/ir.m3u'),
            ('🇮🇱 Израиль',    'https://iptv-org.github.io/iptv/countries/il.m3u'),
        ]),
        ('By region', [
            ('🌍 СНГ',          'https://iptv-org.github.io/iptv/regions/cis.m3u'),
            ('🌍 Europe',       'https://iptv-org.github.io/iptv/regions/eur.m3u'),
            ('🌍 Asia',         'https://iptv-org.github.io/iptv/regions/asia.m3u'),
            ('🌍 North America','https://iptv-org.github.io/iptv/regions/noram.m3u'),
        ]),
    ]

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        title = QLabel("M3U IPTV")
        title.setFont(QFont('Segoe UI', 24, QFont.Bold))
        layout.addWidget(title)

        subtitle = QLabel("Select a playlist  ·  drop .m3u/.m3u8 files here to import")
        subtitle.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 14px;")
        layout.addWidget(subtitle)
        layout.addSpacing(12)

        # Built-in playlists — four comboboxes (matches Android Round 220).
        builtin_label = QLabel("Built-in playlists")
        builtin_label.setStyleSheet(f"color: {COLORS['text_primary']}; font-size: 14px; font-weight: bold;")
        layout.addWidget(builtin_label)

        self._builtin_combos = []
        grid = QHBoxLayout()
        col_left = QVBoxLayout()
        col_right = QVBoxLayout()
        for i, (cat_label, items) in enumerate(self.BUILTIN_CATEGORIES):
            lbl = QLabel(cat_label)
            lbl.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 12px;")
            combo = QComboBox()
            combo.addItem("— Choose —", None)
            for name, url in items:
                combo.addItem(name, url)
            combo.currentIndexChanged.connect(
                lambda idx, c=combo: self.on_builtin_chosen(c))
            self._builtin_combos.append(combo)
            (col_left if i % 2 == 0 else col_right).addWidget(lbl)
            (col_left if i % 2 == 0 else col_right).addWidget(combo)
        grid.addLayout(col_left)
        grid.addSpacing(8)
        grid.addLayout(col_right)
        layout.addLayout(grid)
        layout.addSpacing(12)

        custom_label = QLabel("My playlists")
        custom_label.setStyleSheet(f"color: {COLORS['text_primary']}; font-size: 14px; font-weight: bold;")
        layout.addWidget(custom_label)

        self.playlist_list = QListWidget()
        self.playlist_list.setSpacing(4)
        self.playlist_list.itemDoubleClicked.connect(self.on_playlist_click)
        layout.addWidget(self.playlist_list)

        btn_row = QHBoxLayout()
        btn_add_url = QPushButton("+ Add URL")
        btn_add_url.setObjectName("primaryBtn")
        btn_add_url.clicked.connect(self.add_playlist_url)
        btn_row.addWidget(btn_add_url)

        btn_add_file = QPushButton("+ Open File")
        btn_add_file.clicked.connect(self.add_playlist_file)
        btn_row.addWidget(btn_add_file)

        btn_row.addStretch()

        btn_remove = QPushButton("Remove")
        btn_remove.setStyleSheet(f"color: {COLORS['error']};")
        btn_remove.clicked.connect(self.remove_playlist)
        btn_row.addWidget(btn_remove)

        layout.addLayout(btn_row)
        self.refresh_list()

    def on_builtin_chosen(self, combo):
        idx = combo.currentIndex()
        if idx <= 0:
            return
        url = combo.itemData(idx)
        name = combo.itemText(idx)
        if url:
            self.playlist_selected.emit(name, url)
        # Reset back to placeholder so reselecting the same item works.
        combo.blockSignals(True)
        combo.setCurrentIndex(0)
        combo.blockSignals(False)

    def refresh_list(self):
        self.playlist_list.clear()
        for pl in self.config.playlists:
            item = QListWidgetItem(f"{pl['name']}\n{pl['url']}")
            item.setData(Qt.UserRole, pl)
            self.playlist_list.addItem(item)

    def on_playlist_click(self, item):
        pl = item.data(Qt.UserRole)
        if pl:
            self.playlist_selected.emit(pl['name'], pl['url'])

    def add_playlist_url(self):
        dlg = QDialog(self)
        dlg.setWindowTitle("Add Playlist")
        dlg.setStyleSheet(STYLESHEET)
        dlg.setMinimumWidth(450)
        form = QFormLayout(dlg)
        name_edit = QLineEdit()
        name_edit.setPlaceholderText("Playlist name")
        url_edit = QLineEdit()
        url_edit.setPlaceholderText("http://... or https://...")
        form.addRow("Name:", name_edit)
        form.addRow("URL:", url_edit)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.accepted.connect(dlg.accept)
        btns.rejected.connect(dlg.reject)
        form.addWidget(btns)
        if dlg.exec_() == QDialog.Accepted:
            name = name_edit.text().strip()
            url = url_edit.text().strip()
            if name and url:
                self.config.playlists.append({'name': name, 'url': url})
                self.config.save()
                self.refresh_list()

    def add_playlist_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Open M3U Playlist", "",
            "Playlist files (*.m3u *.m3u8);;All files (*)")
        if path:
            name = os.path.splitext(os.path.basename(path))[0]
            self.config.playlists.append({'name': name, 'url': path})
            self.config.save()
            self.refresh_list()

    def remove_playlist(self):
        row = self.playlist_list.currentRow()
        if row >= 0:
            self.config.playlists.pop(row)
            self.config.save()
            self.refresh_list()


# ============================================================
# Channels Page
# ============================================================
class ChannelsPage(QWidget):
    channel_play = pyqtSignal(int)  # channel index

    def __init__(self, config: Config, logo_cache: LogoCache = None):
        super().__init__()
        self.config = config
        self.logo_cache = logo_cache
        self.channels = []
        self.filtered = []
        self.categories = []
        self.selected_category = "All"
        self.epg_data = {}
        self.ch_to_index = {}  # id(ch) -> index in self.channels (O(1) lookup)
        self.quality_filter = "All"  # All / 4K / FHD / HD / SD
        self.quality_buttons = {}
        self.init_ui()

        # Debounce search input so typing doesn't rebuild the whole list per keystroke
        self._search_timer = QTimer(self)
        self._search_timer.setSingleShot(True)
        self._search_timer.setInterval(200)
        self._search_timer.timeout.connect(self.filter_channels)

        if self.logo_cache is not None:
            self.logo_cache.logo_ready.connect(self._refresh_logos)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        header = QHBoxLayout()
        self.title_label = QLabel(t('channels'))
        self.title_label.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(self.title_label)
        header.addStretch()
        self.count_label = QLabel("")
        self.count_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.count_label)
        layout.addLayout(header)

        srow = QHBoxLayout()
        srow.setSpacing(6)
        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search channels...")
        self.search_edit.textChanged.connect(self._on_search_text)
        srow.addWidget(self.search_edit, 1)

        self.sort_combo = QComboBox()
        self.sort_combo.addItem("Sort: Default", "default")
        self.sort_combo.addItem("Sort: Name", "name")
        self.sort_combo.addItem("Sort: Number", "number")
        self.sort_combo.addItem("Sort: Quality (4K → SD)", "quality")
        cur_sort = getattr(self.config, 'channel_sort', 'default')
        for i in range(self.sort_combo.count()):
            if self.sort_combo.itemData(i) == cur_sort:
                self.sort_combo.setCurrentIndex(i)
                break
        self.sort_combo.currentIndexChanged.connect(self._on_sort_changed)
        srow.addWidget(self.sort_combo)
        layout.addLayout(srow)
        layout.addSpacing(8)

        # Category bar
        self.cat_scroll = QScrollArea()
        self.cat_scroll.setWidgetResizable(True)
        self.cat_scroll.setMaximumHeight(48)
        self.cat_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.cat_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.cat_widget = QWidget()
        self.cat_layout = QHBoxLayout(self.cat_widget)
        self.cat_layout.setContentsMargins(0, 0, 0, 0)
        self.cat_layout.setSpacing(8)
        self.cat_scroll.setWidget(self.cat_widget)
        layout.addWidget(self.cat_scroll)
        layout.addSpacing(4)

        # Quality filter chips (All / 4K / FHD / HD / SD)
        qrow = QHBoxLayout()
        qrow.setContentsMargins(0, 0, 0, 0)
        qrow.setSpacing(6)
        for label in ("All", "4K", "FHD", "HD", "SD"):
            btn = QPushButton(label)
            btn.setCheckable(True)
            btn.setMinimumHeight(28)
            btn.clicked.connect(lambda _checked=False, q=label: self.select_quality(q))
            self.quality_buttons[label] = btn
            qrow.addWidget(btn)
        qrow.addStretch()
        layout.addLayout(qrow)
        self._update_quality_chip_styles()
        layout.addSpacing(6)

        # Channel list (larger row height for remote/touch friendliness)
        self.channel_list = QListWidget()
        self.channel_list.setSpacing(2)
        self.channel_list.setIconSize(QSize(48, 48))
        self.channel_list.setUniformItemSizes(True)
        self.channel_list.setStyleSheet(
            "QListWidget::item { padding: 8px 6px; }"
            "QListWidget::item:selected { background-color: " + COLORS['primary'] + "; color: white; }"
            "QListWidget::item:focus { outline: 2px solid " + COLORS['primary'] + "; }")
        self.channel_list.itemDoubleClicked.connect(self.on_channel_click)
        self.channel_list.setSelectionMode(QAbstractItemView.SingleSelection)
        layout.addWidget(self.channel_list)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 12px;")
        layout.addWidget(self.status_label)

    def set_channels(self, channels, name="", epg_data=None):
        self.channels = channels
        self.ch_to_index = {id(ch): i for i, ch in enumerate(channels)}
        if epg_data:
            self.epg_data = epg_data
        self.title_label.setText(name or "Channels")
        cats = sorted(set(ch.group for ch in channels if ch.group))
        self.categories = ["All", "★ Recent"] + cats
        last_cat = getattr(self.config, 'last_category', '') or "All"
        self.selected_category = last_cat if last_cat in self.categories else "All"
        self.rebuild_categories()
        self.filter_channels()

    def _on_search_text(self, _text: str):
        self._search_timer.start()

    def _on_sort_changed(self, _idx: int):
        self.config.channel_sort = self.sort_combo.currentData()
        self.config.save()
        self.filter_channels()

    def set_epg(self, epg_data):
        self.epg_data = epg_data
        self.filter_channels()

    def rebuild_categories(self):
        while self.cat_layout.count():
            w = self.cat_layout.takeAt(0).widget()
            if w:
                w.deleteLater()
        for cat in self.categories:
            btn = QPushButton(cat)
            if cat == self.selected_category:
                btn.setObjectName("categoryBtnActive")
            else:
                btn.setObjectName("categoryBtn")
            btn.setStyleSheet(STYLESHEET)
            btn.clicked.connect(lambda checked, c=cat: self.select_category(c))
            self.cat_layout.addWidget(btn)
        self.cat_layout.addStretch()

    def select_category(self, cat):
        self.selected_category = cat
        self.config.last_category = cat
        self.config.save()
        self.rebuild_categories()
        self.filter_channels()

    def select_quality(self, label: str):
        self.quality_filter = label
        self._update_quality_chip_styles()
        self.filter_channels()

    def _update_quality_chip_styles(self):
        for label, btn in self.quality_buttons.items():
            active = (label == self.quality_filter)
            btn.setChecked(active)
            color = QUALITY_COLORS.get(label, COLORS['primary'])
            if active:
                btn.setStyleSheet(
                    "QPushButton { background-color: " + color +
                    "; color: white; border-radius: 14px; padding: 4px 14px; font-weight: bold; }")
            else:
                btn.setStyleSheet(
                    "QPushButton { background-color: " + COLORS['card'] +
                    "; color: " + COLORS['text_secondary'] +
                    "; border-radius: 14px; padding: 4px 14px; }"
                    "QPushButton:hover { background-color: " + COLORS['card_hover'] + "; }")

    def filter_channels(self):
        query = self.search_edit.text().strip().lower()
        cat = self.selected_category
        favs = self.config.favorites
        epg = self.epg_data
        qf = self.quality_filter
        recent_set = set(getattr(self.config, 'recent_urls', []) or [])
        recent_order = {u: i for i, u in enumerate(getattr(self.config, 'recent_urls', []) or [])}
        is_recent = (cat == "★ Recent")
        # Build filtered list once
        filtered = []
        for ch in self.channels:
            if is_recent:
                if ch.url not in recent_set:
                    continue
            elif cat != "All" and ch.group != cat:
                continue
            if query and query not in ch.name.lower():
                continue
            if qf != "All" and detect_quality(ch.name) != qf:
                continue
            filtered.append(ch)
        if is_recent:
            filtered.sort(key=lambda c: recent_order.get(c.url, 9999))
        else:
            sort_mode = getattr(self.config, 'channel_sort', 'default')
            if sort_mode == "name":
                filtered.sort(key=lambda c: (c.name or "").lower())
            elif sort_mode == "number":
                # Stable: keep original M3U order (already in self.channels order)
                pass
            elif sort_mode == "quality":
                rank = {"4K": 0, "FHD": 1, "HD": 2, "SD": 3, "": 4}
                filtered.sort(key=lambda c: (rank.get(detect_quality(c.name), 4),
                                             (c.name or "").lower()))
        self.filtered = filtered

        # Show EPG titles only for small lists to keep the UI responsive
        show_epg = len(filtered) <= 500
        ch_to_index = self.ch_to_index
        logo_cache = self.logo_cache
        lst = self.channel_list
        lst.setUpdatesEnabled(False)
        try:
            lst.clear()
            for i, ch in enumerate(filtered):
                epg_text = ""
                if show_epg:
                    now_prog, _ = get_now_next(epg, ch.tvg_id, ch.name)
                    if now_prog:
                        try:
                            t = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                            epg_text = f"  {t} {now_prog.title}"
                        except (OSError, ValueError):
                            pass
                fav = " ♥" if ch.url in favs else ""
                group = f" [{ch.group}]" if ch.group else ""
                q = detect_quality(ch.name)
                qbadge = f"  ◆{q}" if q else ""
                item = QListWidgetItem(f"{i+1}. {ch.name}{qbadge}{fav}{group}{epg_text}")
                item.setData(Qt.UserRole, ch_to_index.get(id(ch), -1))
                if q:
                    item.setForeground(QColor(QUALITY_COLORS[q]))
                # Round 221c (Windows): сначала кэш реального лого,
                # если нет — letter-tile с инициалами и цветом из имени.
                icon = None
                if logo_cache is not None and ch.logo_url:
                    icon = logo_cache.get(ch.logo_url)
                item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
                lst.addItem(item)
        finally:
            lst.setUpdatesEnabled(True)

        self.count_label.setText(f"{len(filtered)} channels")

    def _refresh_logos(self):
        """Called when new logos have been downloaded; update icons in place."""
        if self.logo_cache is None:
            return
        lst = self.channel_list
        for row in range(lst.count()):
            item = lst.item(row)
            idx = item.data(Qt.UserRole)
            if idx is None or idx < 0 or idx >= len(self.channels):
                continue
            ch = self.channels[idx]
            if not ch.logo_url:
                continue
            # Round 221c (Windows): не пропускаем item.icon()!=null —
            # сейчас все имеют letter-tile, реальное лого должно его
            # заменить.
            icon = self.logo_cache.get(ch.logo_url)
            if icon is not None:
                item.setIcon(icon)

    def on_channel_click(self, item):
        idx = item.data(Qt.UserRole)
        if idx is not None:
            self.channel_play.emit(idx)

    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Return or event.key() == Qt.Key_Enter:
            item = self.channel_list.currentItem()
            if item:
                self.on_channel_click(item)
                return
        super().keyPressEvent(event)


# ============================================================
# Favorites Page
# ============================================================
class FavoritesPage(QWidget):
    channel_play = pyqtSignal(int)

    def __init__(self, config: Config, logo_cache: LogoCache = None):
        super().__init__()
        self.config = config
        self.logo_cache = logo_cache
        self.channels = []
        self.fav_channels = []
        self.epg_data = {}
        self.init_ui()
        if self.logo_cache is not None:
            self.logo_cache.logo_ready.connect(self._refresh_logos)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        title = QLabel("Favorites")
        title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        layout.addWidget(title)
        self.count_label = QLabel("")
        self.count_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        layout.addWidget(self.count_label)
        layout.addSpacing(8)
        self.fav_list = QListWidget()
        self.fav_list.setSpacing(2)
        self.fav_list.setIconSize(QSize(48, 48))
        self.fav_list.setUniformItemSizes(True)
        self.fav_list.setStyleSheet(
            "QListWidget::item { padding: 8px 6px; }"
            "QListWidget::item:selected { background-color: " + COLORS['primary'] + "; color: white; }")
        self.fav_list.itemDoubleClicked.connect(self.on_click)
        layout.addWidget(self.fav_list)

    def refresh(self, channels, epg_data):
        self.channels = channels
        self.epg_data = epg_data
        favs = self.config.favorites
        self.fav_list.setUpdatesEnabled(False)
        try:
            self.fav_list.clear()
            self.fav_channels = []
            for idx, ch in enumerate(channels):
                if ch.url not in favs:
                    continue
                self.fav_channels.append(ch)
                now_prog, _ = get_now_next(epg_data, ch.tvg_id, ch.name)
                epg = f"  {now_prog.title}" if now_prog else ""
                item = QListWidgetItem(f"♥ {ch.name}{epg}")
                item.setData(Qt.UserRole, idx)
                icon = None
                if self.logo_cache is not None and ch.logo_url:
                    icon = self.logo_cache.get(ch.logo_url)
                item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
                self.fav_list.addItem(item)
        finally:
            self.fav_list.setUpdatesEnabled(True)
        self.count_label.setText(f"{len(self.fav_channels)} favorites")

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        lst = self.fav_list
        for row in range(lst.count()):
            item = lst.item(row)
            idx = item.data(Qt.UserRole)
            if idx is None or idx < 0 or idx >= len(self.channels):
                continue
            ch = self.channels[idx]
            if not ch.logo_url or not item.icon().isNull():
                continue
            icon = self.logo_cache.get(ch.logo_url)
            if icon is not None:
                item.setIcon(icon)

    def on_click(self, item):
        idx = item.data(Qt.UserRole)
        if idx is not None:
            self.channel_play.emit(idx)


# ============================================================
# Player Page
# ============================================================
class PlayerPage(QWidget):
    back_requested = pyqtSignal()

    ASPECT_RATIOS = ["", "16:9", "4:3", "1:1", "16:10", "2.35:1"]
    SPEED_VALUES = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]

    def __init__(self, config: Config, logo_cache: LogoCache = None):
        super().__init__()
        self.config = config
        self.logo_cache = logo_cache
        self.channels = []
        self.current_index = 0
        self.epg_data = {}
        self.vlc_instance = None
        self.player = None
        self.current_media = None
        self._aspect_idx = 0
        self._speed_idx = 2  # 1.0x
        self._number_input = ""
        self._sleep_deadline = 0  # monotonic ms; 0 = off
        self.init_ui()
        self.init_vlc()

        # Sleep timer tick (once per minute)
        self._sleep_timer = QTimer(self)
        self._sleep_timer.setInterval(10 * 1000)
        self._sleep_timer.timeout.connect(self._tick_sleep)

        # Number-input commit timer (D-pad-style channel selection)
        self._number_timer = QTimer(self)
        self._number_timer.setSingleShot(True)
        self._number_timer.setInterval(1500)
        self._number_timer.timeout.connect(self._apply_number_input)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Top bar
        top_bar = QHBoxLayout()
        top_bar.setContentsMargins(12, 8, 12, 8)
        self.btn_back = QPushButton("< Back")
        self.btn_back.clicked.connect(self.back_requested.emit)
        top_bar.addWidget(self.btn_back)
        self.channel_name_label = QLabel("Channel")
        self.channel_name_label.setFont(QFont('Segoe UI', 16, QFont.Bold))
        top_bar.addWidget(self.channel_name_label)
        top_bar.addStretch()
        self.channel_number_label = QLabel("")
        self.channel_number_label.setStyleSheet(f"color: {COLORS['text_secondary']};")
        top_bar.addWidget(self.channel_number_label)
        self.btn_fav = QPushButton("♡")
        self.btn_fav.setObjectName("favBtn")
        self.btn_fav.clicked.connect(self.toggle_favorite)
        top_bar.addWidget(self.btn_fav)
        layout.addLayout(top_bar)

        # EPG info bar
        self.epg_bar = QLabel("")
        self.epg_bar.setStyleSheet(
            f"background-color: {COLORS['surface']}; color: {COLORS['secondary']};"
            f" padding: 6px 12px; font-size: 13px;")
        layout.addWidget(self.epg_bar)

        self.epg_progress = QProgressBar()
        self.epg_progress.setMaximum(100)
        self.epg_progress.setTextVisible(False)
        self.epg_progress.setMaximumHeight(4)
        layout.addWidget(self.epg_progress)

        # Video frame with OSD banner overlay (parented to video_frame)
        self.video_frame = QFrame()
        self.video_frame.setStyleSheet("background-color: black;")
        self.video_frame.setMinimumHeight(400)
        self.video_frame.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        layout.addWidget(self.video_frame)
        self._build_osd_banner()
        # Round 232 (Windows): аналоги Android-овых overlay-панелей.
        # Левая — список каналов с поиском; правая — быстрые настройки
        # (Aspect / Speed / Audio / Sleep / Fullscreen / PiP / Favorite).
        # Скрыты по умолчанию; toggle хоткеями L / R и кнопками в top-bar.
        self._build_channels_overlay()
        self._build_quick_overlay()
        # Кнопки в top-bar для тех у кого нет физической клавиатуры.
        try:
            self._inject_overlay_toggle_buttons()
        except Exception:
            pass

        # Auto-hide banner timer
        self._banner_timer = QTimer(self)
        self._banner_timer.setSingleShot(True)
        self._banner_timer.setInterval(4500)
        self._banner_timer.timeout.connect(self._hide_banner)

        # Bottom controls
        ctrl = QHBoxLayout()
        ctrl.setContentsMargins(12, 8, 12, 8)

        self.btn_prev = QPushButton("< Prev")
        self.btn_prev.clicked.connect(lambda: self.switch_channel(-1))
        ctrl.addWidget(self.btn_prev)

        self.btn_play = QPushButton("Pause")
        self.btn_play.setObjectName("primaryBtn")
        self.btn_play.clicked.connect(self.toggle_play)
        ctrl.addWidget(self.btn_play)

        self.btn_next = QPushButton("Next >")
        self.btn_next.clicked.connect(lambda: self.switch_channel(1))
        ctrl.addWidget(self.btn_next)

        ctrl.addSpacing(20)
        ctrl.addWidget(QLabel("Vol:"))
        self.vol_slider = QSlider(Qt.Horizontal)
        self.vol_slider.setRange(0, 100)
        self.vol_slider.setValue(self.config.volume)
        self.vol_slider.setMaximumWidth(150)
        self.vol_slider.valueChanged.connect(self.set_volume)
        ctrl.addWidget(self.vol_slider)

        # Extra player controls
        self.btn_aspect = QPushButton("Aspect: auto")
        self.btn_aspect.clicked.connect(self.cycle_aspect_ratio)
        ctrl.addWidget(self.btn_aspect)

        self.btn_speed = QPushButton("1.0x")
        self.btn_speed.clicked.connect(self.cycle_speed)
        ctrl.addWidget(self.btn_speed)

        self.btn_audio = QPushButton("Audio")
        self.btn_audio.clicked.connect(self.cycle_audio_track)
        ctrl.addWidget(self.btn_audio)

        self.btn_sleep = QPushButton("Sleep")
        self.btn_sleep.clicked.connect(self.configure_sleep_timer)
        ctrl.addWidget(self.btn_sleep)

        self.btn_pip = QPushButton("PiP")
        self.btn_pip.setToolTip("Mini player mode (P)")
        self.btn_pip.clicked.connect(self._on_pip_clicked)
        ctrl.addWidget(self.btn_pip)

        self.btn_fullscreen = QPushButton("⛶")
        self.btn_fullscreen.setToolTip("Fullscreen (F11)")
        self.btn_fullscreen.clicked.connect(self.toggle_fullscreen)
        ctrl.addWidget(self.btn_fullscreen)

        ctrl.addStretch()
        self.number_label = QLabel("")
        self.number_label.setStyleSheet(
            f"color: {COLORS['secondary']}; font-weight: bold; font-size: 18px;")
        ctrl.addWidget(self.number_label)

        self.sleep_label = QLabel("")
        self.sleep_label.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 12px;")
        ctrl.addWidget(self.sleep_label)

        self.clock_label = QLabel("")
        self.clock_label.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 13px;")
        ctrl.addWidget(self.clock_label)

        layout.addLayout(ctrl)

        # EPG update timer
        self.epg_timer = QTimer()
        self.epg_timer.timeout.connect(self.update_epg_display)
        self.epg_timer.start(30000)

        self.clock_timer = QTimer()
        self.clock_timer.timeout.connect(self.update_clock)
        self.clock_timer.start(30000)
        self.update_clock()

    def _build_osd_banner(self):
        """Floating channel info banner (parented to video_frame, shown briefly on switch)."""
        self.osd_banner = QWidget(self.video_frame)
        self.osd_banner.setStyleSheet(
            "background-color: rgba(18, 18, 32, 220);"
            " border-radius: 10px;")
        self.osd_banner.hide()
        row = QHBoxLayout(self.osd_banner)
        row.setContentsMargins(12, 10, 16, 10)
        row.setSpacing(12)

        self.osd_logo = QLabel()
        self.osd_logo.setFixedSize(56, 56)
        self.osd_logo.setAlignment(Qt.AlignCenter)
        self.osd_logo.setStyleSheet(
            f"background-color: {COLORS['card']}; border-radius: 6px;")
        row.addWidget(self.osd_logo)

        col = QVBoxLayout()
        col.setSpacing(2)
        self.osd_number_name = QLabel("")
        self.osd_number_name.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(self.osd_number_name)

        self.osd_now = QLabel("")
        self.osd_now.setStyleSheet(f"color: {COLORS['secondary']}; font-size: 13px;")
        self.osd_now.setWordWrap(False)
        col.addWidget(self.osd_now)

        self.osd_next = QLabel("")
        self.osd_next.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 12px;")
        self.osd_next.setWordWrap(False)
        col.addWidget(self.osd_next)

        self.osd_progress = QProgressBar()
        self.osd_progress.setMaximum(100)
        self.osd_progress.setTextVisible(False)
        self.osd_progress.setMaximumHeight(4)
        col.addWidget(self.osd_progress)

        row.addLayout(col, 1)
        # Position is set in resizeEvent / _position_osd

    def _position_osd(self):
        if not hasattr(self, 'osd_banner'):
            return
        parent = self.video_frame
        pw = parent.width()
        ph = parent.height()
        if pw <= 0 or ph <= 0:
            return
        bw = min(540, max(340, pw - 40))
        bh = 92
        self.osd_banner.setGeometry(20, 20, bw, bh)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._position_osd()
        self._position_overlays()

    # ---- Round 232: side-panel overlays ----

    def _build_channels_overlay(self):
        """Слева, ширина 360px. Содержит поиск + QListWidget со всеми каналами."""
        self.channels_overlay = QWidget(self.video_frame)
        self.channels_overlay.setStyleSheet(
            "background-color: rgba(15, 15, 26, 220);"
            " border-right: 1px solid rgba(124, 108, 247, 180);")
        self.channels_overlay.hide()
        col = QVBoxLayout(self.channels_overlay)
        col.setContentsMargins(10, 10, 10, 10)
        col.setSpacing(8)
        title = QLabel(t('panel_channels'))
        title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(title)
        self._overlay_search = QLineEdit()
        self._overlay_search.setPlaceholderText(t('search') + "…")
        self._overlay_search.textChanged.connect(self._refresh_channels_overlay)
        col.addWidget(self._overlay_search)
        self._overlay_list = QListWidget()
        self._overlay_list.setIconSize(QSize(28, 28))
        self._overlay_list.itemClicked.connect(self._overlay_channel_clicked)
        col.addWidget(self._overlay_list, 1)

    def _build_quick_overlay(self):
        """Справа, ширина 240px. Кнопки быстрых настроек."""
        self.quick_overlay = QWidget(self.video_frame)
        self.quick_overlay.setStyleSheet(
            "background-color: rgba(15, 15, 26, 220);"
            " border-left: 1px solid rgba(124, 108, 247, 180);")
        self.quick_overlay.hide()
        col = QVBoxLayout(self.quick_overlay)
        col.setContentsMargins(10, 10, 10, 10)
        col.setSpacing(8)
        title = QLabel(t('panel_quick'))
        title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(title)

        def _btn(label, callback):
            b = QPushButton(label)
            b.clicked.connect(callback)
            return b

        col.addWidget(_btn(t('aspect'), self.cycle_aspect_ratio))
        col.addWidget(_btn(t('speed'), self.cycle_speed))
        col.addWidget(_btn(t('audio_track'), self.cycle_audio_track))
        col.addWidget(_btn(t('sleep_timer'), self.configure_sleep_timer))
        col.addWidget(_btn(t('fullscreen'), self.toggle_fullscreen))
        col.addWidget(_btn(t('pip'), self._on_pip_clicked))
        col.addWidget(_btn("♥ " + t('favorites'), self.toggle_favorite))
        col.addStretch()

    def _inject_overlay_toggle_buttons(self):
        """В top_bar добавляем кнопки для toggle левой/правой панели."""
        if not hasattr(self, 'btn_back'):
            return
        top_layout = self.btn_back.parentWidget().layout() if self.btn_back.parent() else None
        # btn_back живёт в HBoxLayout наверху; QLayout не возвращает legко
        # parentWidget()->layout(), поэтому добавим кнопки прямо рядом
        # с back через свой layout (создадим bar если ещё нет).
        # Простой путь: создаём отдельный bar для toggle поверх video_frame.
        self._overlay_toggle_bar = QWidget(self.video_frame)
        self._overlay_toggle_bar.setStyleSheet("background: transparent;")
        bar = QHBoxLayout(self._overlay_toggle_bar)
        bar.setContentsMargins(8, 8, 8, 8)
        bar.setSpacing(8)
        self.btn_panel_channels = QPushButton("☰ " + t('channels'))
        self.btn_panel_channels.setStyleSheet(
            "background-color: rgba(15, 15, 26, 200); color: white;"
            " padding: 8px 14px; border-radius: 6px; border: 1px solid #7C6CF7;")
        self.btn_panel_channels.clicked.connect(self.toggle_channels_overlay)
        bar.addWidget(self.btn_panel_channels)
        bar.addStretch()
        self.btn_panel_quick = QPushButton("⚙ " + t('settings'))
        self.btn_panel_quick.setStyleSheet(
            "background-color: rgba(15, 15, 26, 200); color: white;"
            " padding: 8px 14px; border-radius: 6px; border: 1px solid #7C6CF7;")
        self.btn_panel_quick.clicked.connect(self.toggle_quick_overlay)
        bar.addWidget(self.btn_panel_quick)
        self._overlay_toggle_bar.adjustSize()

    def _position_overlays(self):
        if not hasattr(self, 'channels_overlay'):
            return
        pw = self.video_frame.width()
        ph = self.video_frame.height()
        if pw <= 0 or ph <= 0:
            return
        ch_w = min(360, int(pw * 0.40))
        qk_w = min(280, int(pw * 0.32))
        self.channels_overlay.setGeometry(0, 0, ch_w, ph)
        self.quick_overlay.setGeometry(pw - qk_w, 0, qk_w, ph)
        if hasattr(self, '_overlay_toggle_bar'):
            # Позиционируем bar над OSD-баннером, ширина = video_frame
            self._overlay_toggle_bar.setGeometry(0, ph - 56, pw, 56)
            self._overlay_toggle_bar.raise_()

    def toggle_channels_overlay(self):
        if not hasattr(self, 'channels_overlay'):
            return
        if self.channels_overlay.isVisible():
            self._slide_out(self.channels_overlay, direction='left')
        else:
            self.quick_overlay.hide()
            self._refresh_channels_overlay()
            self._slide_in(self.channels_overlay, direction='left')
            self.channels_overlay.raise_()
            self._overlay_search.setFocus()

    def toggle_quick_overlay(self):
        if not hasattr(self, 'quick_overlay'):
            return
        if self.quick_overlay.isVisible():
            self._slide_out(self.quick_overlay, direction='right')
        else:
            self.channels_overlay.hide()
            self._slide_in(self.quick_overlay, direction='right')
            self.quick_overlay.raise_()

    # Round 233: 200мс slide-in/out для overlay-панелей. Аналог
    # Android Round 211 slide_in_left / slide_in_right.
    def _stop_anim(self, widget):
        """Останавливаем предыдущую анимацию если ещё крутится —
        иначе быстрые тогглы L/L/L могут оставить виджет в висячем
        состоянии (animation finished дёргает hide() уже после
        нового show)."""
        prev = getattr(widget, '_slide_anim', None)
        if prev is not None:
            try:
                prev.stop()
            except Exception:
                pass
            widget._slide_anim = None

    def _slide_in(self, widget, direction):
        self._stop_anim(widget)
        pw = self.video_frame.width()
        ph = self.video_frame.height()
        if pw <= 0 or ph <= 0:
            widget.show()
            return
        w = widget.width() or (360 if direction == 'left' else 280)
        # Стартовая позиция за пределом, целевая — впритык к краю.
        if direction == 'left':
            start = QRect(-w, 0, w, ph)
            end = QRect(0, 0, w, ph)
        else:
            start = QRect(pw, 0, w, ph)
            end = QRect(pw - w, 0, w, ph)
        widget.setGeometry(start)
        widget.show()
        anim = QPropertyAnimation(widget, b"geometry", self)
        anim.setDuration(200)
        anim.setEasingCurve(QEasingCurve.OutCubic)
        anim.setStartValue(start)
        anim.setEndValue(end)
        anim.start(QPropertyAnimation.DeleteWhenStopped)
        # Держим ref чтобы Python GC не съел до старта.
        widget._slide_anim = anim

    def _slide_out(self, widget, direction):
        self._stop_anim(widget)
        pw = self.video_frame.width()
        ph = self.video_frame.height()
        cur = widget.geometry()
        w = widget.width() or (360 if direction == 'left' else 280)
        if direction == 'left':
            end = QRect(-w, 0, w, ph)
        else:
            end = QRect(pw, 0, w, ph)
        anim = QPropertyAnimation(widget, b"geometry", self)
        anim.setDuration(160)
        anim.setEasingCurve(QEasingCurve.InCubic)
        anim.setStartValue(cur)
        anim.setEndValue(end)
        anim.finished.connect(widget.hide)
        anim.start(QPropertyAnimation.DeleteWhenStopped)
        widget._slide_anim = anim

    def _refresh_channels_overlay(self):
        if not hasattr(self, '_overlay_list'):
            return
        q = (self._overlay_search.text() or "").strip().lower()
        # Round 233: setUpdatesEnabled(False) — иначе QListWidget
        # перерисовывается на каждый addItem, на 10k каналах
        # подвешивает UI на 1-2 сек. Plus аппаратный лимит на 500
        # элементов когда нет поиска — больше юзеру всё равно не
        # обозреть, а скролл становится медленным.
        self._overlay_list.setUpdatesEnabled(False)
        try:
            self._overlay_list.clear()
            shown = 0
            cap = 500 if not q else 10000  # без поиска — лимит; с поиском показываем всё подходящее
            for idx, ch in enumerate(self.channels or []):
                if q and q not in (ch.name or "").lower():
                    continue
                if shown >= cap:
                    break
                # EPG: now-программа добавляется к названию, чтобы юзер
                # видел что идёт. Берём только now (next в overlay не
                # помещается без лишних 2 строк).
                epg_suffix = ""
                if self.epg_data:
                    try:
                        now_prog, _ = get_now_next(
                            self.epg_data, ch.tvg_id, ch.name)
                        if now_prog and now_prog.title:
                            epg_suffix = f"\n   {now_prog.title}"
                    except Exception:
                        pass
                item = QListWidgetItem(f"{idx+1}. {ch.name}{epg_suffix}")
                item.setData(Qt.UserRole, idx)
                icon = None
                if self.logo_cache is not None and ch.logo_url:
                    icon = self.logo_cache.get(ch.logo_url)
                item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
                self._overlay_list.addItem(item)
                shown += 1
        finally:
            self._overlay_list.setUpdatesEnabled(True)

    def _overlay_channel_clicked(self, item):
        idx = item.data(Qt.UserRole)
        if isinstance(idx, int) and 0 <= idx < len(self.channels):
            try:
                # Используем штатный play_channel — он же сбрасывает
                # стейт и обновляет UI. Передаём текущие channels/epg
                # чтобы не пересоздавать их.
                self.play_channel(idx, self.channels, self.epg_data)
            except Exception:
                pass
            self.channels_overlay.hide()

    def retranslate_ui(self):
        """Round 233: переводит все доступные подписи на лету."""
        try:
            if hasattr(self, 'btn_back'):
                self.btn_back.setText(t('back'))
            if hasattr(self, 'btn_prev'):
                self.btn_prev.setText(t('prev'))
            if hasattr(self, 'btn_next'):
                self.btn_next.setText(t('next'))
            if hasattr(self, 'btn_audio'):
                self.btn_audio.setText(t('audio_track'))
            if hasattr(self, 'btn_sleep'):
                self.btn_sleep.setText(t('sleep_timer'))
            if hasattr(self, 'btn_pip'):
                self.btn_pip.setText(t('pip'))
            if hasattr(self, 'btn_play'):
                # play/pause label зависит от состояния — оставляем как есть.
                pass
            if hasattr(self, 'btn_panel_channels'):
                self.btn_panel_channels.setText("☰ " + t('channels'))
            if hasattr(self, 'btn_panel_quick'):
                self.btn_panel_quick.setText("⚙ " + t('settings'))
        except Exception:
            pass

    def _show_channel_banner(self):
        if not self.channels or self.current_index >= len(self.channels):
            return
        ch = self.channels[self.current_index]
        self.osd_number_name.setText(f"{self.current_index + 1}  {ch.name}")
        # Logo
        pix = None
        if self.logo_cache is not None and ch.logo_url:
            icon = self.logo_cache.get(ch.logo_url)
            if icon is not None:
                pix = icon.pixmap(56, 56)
        if pix is not None and not pix.isNull():
            self.osd_logo.setPixmap(pix)
        else:
            self.osd_logo.clear()
            self.osd_logo.setText(ch.name[:2].upper() if ch.name else "")
            self.osd_logo.setStyleSheet(
                f"background-color: {COLORS['card']};"
                f" border-radius: 6px; color: {COLORS['text_secondary']};"
                f" font-weight: bold; font-size: 18px;")
        # EPG
        now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id, ch.name)
        if now_prog:
            try:
                t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
                self.osd_now.setText(f"{t1}–{t2}  {now_prog.title}")
            except (OSError, ValueError):
                self.osd_now.setText(now_prog.title or "")
            self.osd_progress.setValue(int(get_current_progress(now_prog) * 100))
            self.osd_progress.show()
        else:
            self.osd_now.setText("")
            self.osd_progress.setValue(0)
            self.osd_progress.hide()
        if next_prog:
            try:
                nt = datetime.fromtimestamp(next_prog.start).strftime('%H:%M')
                self.osd_next.setText(f"Next: {nt}  {next_prog.title}")
            except (OSError, ValueError):
                self.osd_next.setText(f"Next: {next_prog.title or ''}")
        else:
            self.osd_next.setText("")

        self._position_osd()
        self.osd_banner.raise_()
        self.osd_banner.show()
        self._banner_timer.start()

    def _hide_banner(self):
        if hasattr(self, 'osd_banner'):
            self.osd_banner.hide()

    def _on_pip_clicked(self):
        mw = self.window()
        if hasattr(mw, 'toggle_pip_mode'):
            mw.toggle_pip_mode()

    def init_vlc(self):
        if not HAS_VLC:
            return
        try:
            args = ['--no-xlib']
            # Hardware decode toggle
            if not getattr(self.config, 'hardware_decode', True):
                args += ['--avcodec-hw=none']
            # Audio output backend on Windows ("" = auto)
            ao = getattr(self.config, 'audio_output', '')
            if ao:
                args += [f'--aout={ao}']
            # Custom HTTP user-agent for streams
            ua = getattr(self.config, 'user_agent', '')
            if ua:
                args += [f'--http-user-agent={ua}']
            self.vlc_instance = vlc.Instance(*args)
            self.player = self.vlc_instance.media_player_new()
        except Exception:
            self.vlc_instance = None
            self.player = None

    def play_channel(self, index, channels, epg_data):
        # Save state for previously-playing channel before switching
        self._save_current_channel_state()

        self.channels = channels
        self.current_index = index
        self.epg_data = epg_data
        ch = channels[index]
        self.channel_name_label.setText(ch.name)
        self.channel_number_label.setText(f"{index + 1} / {len(channels)}")
        self.update_fav_btn()
        self.update_epg_display()

        # Restore per-channel preferences before play_url applies them
        st = self.config.get_channel_state(ch.url)
        if st:
            try:
                if 'aspect_idx' in st:
                    self._aspect_idx = int(st['aspect_idx']) % len(self.ASPECT_RATIOS)
                if 'speed_idx' in st:
                    self._speed_idx = int(st['speed_idx']) % len(self.SPEED_VALUES)
                if 'volume' in st and 0 <= int(st['volume']) <= 100:
                    self.vol_slider.setValue(int(st['volume']))
            except Exception:
                pass

        self.play_url(ch.url)

        # If we have a saved position (VOD only — live streams report -1 duration),
        # try to seek there once VLC reports a positive length.
        if st and 'position_ms' in st:
            try:
                pos = int(st['position_ms'])
                if pos > 0:
                    QTimer.singleShot(1500, lambda p=pos: self._maybe_seek(p))
            except Exception:
                pass

        if st and 'audio_track' in st:
            try:
                trk = int(st['audio_track'])
                QTimer.singleShot(1500, lambda t=trk: self._maybe_set_audio_track(t))
            except Exception:
                pass

        self.config.push_recent(ch.url)
        self.config.save()
        self._show_channel_banner()

    def _maybe_seek(self, pos_ms: int):
        if not self.player:
            return
        try:
            length = self.player.get_length()
        except Exception:
            length = -1
        if length > 0 and pos_ms < length - 30000:  # don't restore if near the end
            try: self.player.set_time(pos_ms)
            except Exception: pass

    def _maybe_set_audio_track(self, track_id: int):
        if not self.player:
            return
        try: self.player.audio_set_track(track_id)
        except Exception: pass

    def _save_current_channel_state(self):
        if not self.channels or self.current_index >= len(self.channels):
            return
        ch = self.channels[self.current_index]
        if not ch or not ch.url:
            return
        state = {
            'aspect_idx': self._aspect_idx,
            'speed_idx': self._speed_idx,
            'volume': self.vol_slider.value() if hasattr(self, 'vol_slider') else self.config.volume,
        }
        if self.player:
            try:
                t = self.player.get_time()
                length = self.player.get_length()
                # Only persist position for VOD (length > 0); skip live streams.
                if t and t > 30000 and length > 0 and t < length - 30000:
                    state['position_ms'] = int(t)
            except Exception:
                pass
            try:
                track = self.player.audio_get_track()
                if track is not None and track >= 0:
                    state['audio_track'] = int(track)
            except Exception:
                pass
        self.config.save_channel_state(ch.url, state)
        self.config.save()

    def play_url(self, url):
        if not self.player:
            self.epg_bar.setText("VLC not installed. Install VLC and python-vlc.")
            return
        # Release previous media to avoid resource accumulation across channel switches
        prev = self.current_media
        media = self.vlc_instance.media_new(url)
        media.add_option(f':network-caching={int(self.config.network_caching_ms)}')
        self.player.set_media(media)
        self.current_media = media
        if prev is not None:
            try:
                prev.release()
            except Exception:
                pass
        if sys.platform == "win32":
            self.player.set_hwnd(int(self.video_frame.winId()))
        elif sys.platform == "linux":
            self.player.set_xwindow(int(self.video_frame.winId()))
        elif sys.platform == "darwin":
            self.player.set_nsobject(int(self.video_frame.winId()))
        self.player.audio_set_volume(self.config.volume)
        # Restore aspect ratio & speed per current selection
        self._apply_aspect_ratio()
        try:
            self.player.set_rate(self.SPEED_VALUES[self._speed_idx])
        except Exception:
            pass
        self.player.play()
        self.btn_play.setText("Pause")
        # Remember last channel for autoplay-last
        self.config.last_channel_url = url
        self.config.save()

    def toggle_play(self):
        if not self.player:
            return
        if self.player.is_playing():
            self.player.pause()
            self.btn_play.setText("Play")
        else:
            self.player.play()
            self.btn_play.setText("Pause")

    def switch_channel(self, direction):
        if not self.channels:
            return
        # Debounce zapping: rapid +1/-1 just updates the pending index and
        # fires play_channel once after the user stops, instead of starting
        # and stopping VLC for every keypress.
        if not hasattr(self, '_pending_index'):
            self._pending_index = self.current_index
            self._zap_timer = QTimer(self)
            self._zap_timer.setSingleShot(True)
            self._zap_timer.setInterval(350)
            self._zap_timer.timeout.connect(self._commit_zap)
        self._pending_index = (self._pending_index + direction) % len(self.channels)
        self.current_index = self._pending_index
        # Visual feedback while zapping
        if self.channels:
            ch = self.channels[self._pending_index]
            self.channel_name_label.setText(ch.name)
            self.channel_number_label.setText(f"{self._pending_index + 1} / {len(self.channels)}")
        self._zap_timer.start()

    def _commit_zap(self):
        idx = getattr(self, '_pending_index', self.current_index)
        if idx is None or idx < 0 or idx >= len(self.channels):
            return
        self.current_index = idx
        self.play_channel(idx, self.channels, self.epg_data)

    def set_volume(self, val):
        self.config.volume = val
        if self.player:
            self.player.audio_set_volume(val)

    def toggle_favorite(self):
        if not self.channels or self.current_index >= len(self.channels):
            return
        url = self.channels[self.current_index].url
        if url in self.config.favorites:
            self.config.favorites.discard(url)
        else:
            self.config.favorites.add(url)
        self.config.save()
        self.update_fav_btn()

    def update_fav_btn(self):
        if self.channels and self.current_index < len(self.channels):
            is_fav = self.channels[self.current_index].url in self.config.favorites
            self.btn_fav.setText("♥" if is_fav else "♡")
            color = COLORS['favorite_active'] if is_fav else COLORS['text_hint']
            self.btn_fav.setStyleSheet(
                f"background: transparent; color: {color}; font-size: 20px;"
                f" min-width: 36px; max-width: 36px;")

    def update_epg_display(self):
        if not self.channels or self.current_index >= len(self.channels):
            return
        ch = self.channels[self.current_index]
        now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id, ch.name)
        if now_prog:
            t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
            t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
            text = f"Now: {t1}-{t2}  {now_prog.title}"
            if next_prog:
                nt = datetime.fromtimestamp(next_prog.start).strftime('%H:%M')
                text += f"    |    Next: {nt} {next_prog.title}"
            self.epg_bar.setText(text)
            progress = get_current_progress(now_prog)
            self.epg_progress.setValue(int(progress * 100))
        else:
            self.epg_bar.setText("")
            self.epg_progress.setValue(0)

    def update_clock(self):
        self.clock_label.setText(datetime.now().strftime('%H:%M'))

    # --- Aspect ratio ---

    def cycle_aspect_ratio(self):
        self._aspect_idx = (self._aspect_idx + 1) % len(self.ASPECT_RATIOS)
        self._apply_aspect_ratio()

    def _apply_aspect_ratio(self):
        if not self.player:
            return
        ratio = self.ASPECT_RATIOS[self._aspect_idx]
        label = ratio if ratio else "auto"
        try:
            self.player.video_set_aspect_ratio(ratio.encode() if ratio else None)
        except Exception:
            pass
        self.btn_aspect.setText(f"Aspect: {label}")

    # --- Playback speed ---

    def cycle_speed(self):
        self._speed_idx = (self._speed_idx + 1) % len(self.SPEED_VALUES)
        speed = self.SPEED_VALUES[self._speed_idx]
        if self.player:
            try:
                self.player.set_rate(speed)
            except Exception:
                pass
        self.btn_speed.setText(f"{speed:g}x")

    # --- Audio track ---

    def cycle_audio_track(self):
        if not self.player:
            return
        try:
            tracks = self.player.audio_get_track_description() or []
            # Filter out the -1 "deactivate" pseudo-track
            usable = [t for t in tracks if t and t[0] >= 0]
            if len(usable) < 2:
                self.epg_bar.setText("Single audio track")
                return
            cur = self.player.audio_get_track()
            ids = [t[0] for t in usable]
            try:
                pos = ids.index(cur)
            except ValueError:
                pos = -1
            nxt = usable[(pos + 1) % len(usable)]
            self.player.audio_set_track(nxt[0])
            name = nxt[1].decode(errors='ignore') if isinstance(nxt[1], (bytes, bytearray)) else str(nxt[1])
            self.btn_audio.setText(f"Audio: {name[:12]}")
        except Exception:
            pass

    # --- Fullscreen ---

    def toggle_fullscreen(self):
        w = self.window()
        if w is None:
            return
        if w.isFullScreen():
            w.showNormal()
        else:
            w.showFullScreen()

    # --- Sleep timer ---

    def configure_sleep_timer(self):
        from PyQt5.QtWidgets import QInputDialog
        mins, ok = QInputDialog.getInt(
            self, "Sleep timer",
            "Minutes until pause (0 = off):",
            max(0, self.config.sleep_timer_minutes), 0, 240, 5)
        if not ok:
            return
        self._start_sleep_timer(mins)

    def _start_sleep_timer(self, minutes: int):
        self.config.sleep_timer_minutes = int(minutes)
        self.config.save()
        if minutes <= 0:
            self._sleep_deadline = 0
            self._sleep_timer.stop()
            self.sleep_label.setText("")
            return
        self._sleep_deadline = int(time.monotonic() * 1000) + minutes * 60 * 1000
        self._sleep_timer.start()
        self._update_sleep_label()

    def _update_sleep_label(self):
        if self._sleep_deadline <= 0:
            self.sleep_label.setText("")
            return
        remaining_ms = self._sleep_deadline - int(time.monotonic() * 1000)
        if remaining_ms <= 0:
            self.sleep_label.setText("")
            return
        mins = remaining_ms // 60000 + 1
        self.sleep_label.setText(f"💤 {mins} min")

    def _tick_sleep(self):
        if self._sleep_deadline <= 0:
            return
        remaining_ms = self._sleep_deadline - int(time.monotonic() * 1000)
        if remaining_ms <= 0:
            self._sleep_deadline = 0
            self._sleep_timer.stop()
            self.sleep_label.setText("")
            if self.player:
                try: self.player.pause()
                except Exception: pass
            self.btn_play.setText("Play")
            return
        self._update_sleep_label()

    # --- Number input (digit keys select channel number) ---

    def _handle_digit(self, digit: int):
        self._number_input += str(digit)
        self.number_label.setText(self._number_input)
        self._number_timer.start()

    def _apply_number_input(self):
        txt = self._number_input
        self._number_input = ""
        self.number_label.setText("")
        if not txt or not self.channels:
            return
        try:
            num = int(txt)
        except ValueError:
            return
        if 1 <= num <= len(self.channels):
            self.current_index = num - 1
            self.play_channel(self.current_index, self.channels, self.epg_data)

    def stop(self):
        self._save_current_channel_state()
        # VLC's stop() is synchronous and can block for up to a few seconds on
        # dead/slow streams. Run it on a background thread so the UI never freezes.
        p = self.player
        if not p:
            return
        try:
            threading.Thread(
                target=lambda: (
                    p.stop() if p else None
                ),
                daemon=True,
            ).start()
        except Exception:
            try: p.stop()
            except Exception: pass

    def release_vlc(self):
        if self.current_media is not None:
            try: self.current_media.release()
            except Exception: pass
            self.current_media = None
        if self.player is not None:
            try: self.player.release()
            except Exception: pass
            self.player = None
        if self.vlc_instance is not None:
            try: self.vlc_instance.release()
            except Exception: pass
            self.vlc_instance = None

    def keyPressEvent(self, event):
        key = event.key()
        # Digit keys: compose channel number (non-keypad and keypad)
        if Qt.Key_0 <= key <= Qt.Key_9:
            self._handle_digit(key - Qt.Key_0)
            return
        if key == Qt.Key_Space:
            self.toggle_play()
        elif key in (Qt.Key_Up, Qt.Key_PageUp, Qt.Key_MediaPrevious):
            self.switch_channel(-1)
        elif key in (Qt.Key_Down, Qt.Key_PageDown, Qt.Key_MediaNext):
            self.switch_channel(1)
        elif key == Qt.Key_Left:
            # Step back 10s for VOD; on live, treated as prev channel
            if self.player:
                try:
                    pos = self.player.get_time()
                    if pos > 0:
                        self.player.set_time(max(0, pos - 10000))
                        return
                except Exception:
                    pass
            self.switch_channel(-1)
        elif key == Qt.Key_Right:
            if self.player:
                try:
                    pos = self.player.get_time()
                    if pos > 0:
                        self.player.set_time(pos + 10000)
                        return
                except Exception:
                    pass
            self.switch_channel(1)
        elif key == Qt.Key_Return or key == Qt.Key_Enter:
            # OK/Enter on remote: show channel banner
            self._show_channel_banner()
        elif key == Qt.Key_I:
            # Info button on remotes
            self._show_channel_banner()
        elif key == Qt.Key_Escape:
            if self.window() is not None and self.window().isFullScreen():
                self.window().showNormal()
            else:
                self.back_requested.emit()
        elif key == Qt.Key_F:
            self.toggle_favorite()
        elif key == Qt.Key_Plus or key == Qt.Key_VolumeUp:
            self.vol_slider.setValue(min(100, self.vol_slider.value() + 5))
        elif key == Qt.Key_Minus or key == Qt.Key_VolumeDown:
            self.vol_slider.setValue(max(0, self.vol_slider.value() - 5))
        elif key == Qt.Key_F11:
            self.toggle_fullscreen()
        elif key == Qt.Key_P:
            # Toggle Picture-in-Picture (frameless mini player)
            mw = self.window()
            if hasattr(mw, 'toggle_pip_mode'):
                mw.toggle_pip_mode()
        elif key == Qt.Key_A:
            self.cycle_aspect_ratio()
        elif key == Qt.Key_T:
            self.cycle_audio_track()
        elif key == Qt.Key_BracketRight:
            self.cycle_speed()
        elif key == Qt.Key_BracketLeft:
            # Cycle backward
            self._speed_idx = (self._speed_idx - 1) % len(self.SPEED_VALUES)
            speed = self.SPEED_VALUES[self._speed_idx]
            if self.player:
                try: self.player.set_rate(speed)
                except Exception: pass
            self.btn_speed.setText(f"{speed:g}x")
        else:
            super().keyPressEvent(event)


# ============================================================
# Recent Page (recently watched channels)
# ============================================================
class RecentPage(QWidget):
    channel_play = pyqtSignal(int)

    def __init__(self, config: Config, logo_cache: LogoCache = None):
        super().__init__()
        self.config = config
        self.logo_cache = logo_cache
        self.channels = []
        self.epg_data = {}
        self.init_ui()
        if self.logo_cache is not None:
            self.logo_cache.logo_ready.connect(self._refresh_logos)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        header = QHBoxLayout()
        title = QLabel("Recent")
        title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(title)
        header.addStretch()
        self.count_label = QLabel("")
        self.count_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.count_label)
        btn_clear = QPushButton("Clear")
        btn_clear.clicked.connect(self._clear)
        header.addWidget(btn_clear)
        layout.addLayout(header)
        layout.addSpacing(8)

        self.recent_list = QListWidget()
        self.recent_list.setSpacing(2)
        self.recent_list.setIconSize(QSize(48, 48))
        self.recent_list.setStyleSheet(
            "QListWidget::item { padding: 8px 6px; }"
            "QListWidget::item:selected { background-color: " + COLORS['primary'] + "; color: white; }")
        self.recent_list.itemDoubleClicked.connect(self._on_click)
        layout.addWidget(self.recent_list)

    def refresh(self, channels, epg_data):
        self.channels = channels
        self.epg_data = epg_data
        url_to_idx = {ch.url: i for i, ch in enumerate(channels)}
        self.recent_list.setUpdatesEnabled(False)
        try:
            self.recent_list.clear()
            count = 0
            for url in self.config.recent_urls:
                idx = url_to_idx.get(url)
                if idx is None:
                    continue
                ch = channels[idx]
                now_prog, _ = get_now_next(epg_data, ch.tvg_id, ch.name)
                epg_text = f"  {now_prog.title}" if now_prog else ""
                q = detect_quality(ch.name)
                qbadge = f"  ◆{q}" if q else ""
                item = QListWidgetItem(f"{ch.name}{qbadge}{epg_text}")
                item.setData(Qt.UserRole, idx)
                if q:
                    item.setForeground(QColor(QUALITY_COLORS[q]))
                icon = None
                if self.logo_cache is not None and ch.logo_url:
                    icon = self.logo_cache.get(ch.logo_url)
                item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
                self.recent_list.addItem(item)
                count += 1
        finally:
            self.recent_list.setUpdatesEnabled(True)
        self.count_label.setText(f"{count} channels")

    def _on_click(self, item):
        idx = item.data(Qt.UserRole)
        if idx is not None and idx >= 0:
            self.channel_play.emit(idx)

    def _clear(self):
        self.config.recent_urls = []
        self.config.save()
        self.refresh(self.channels, self.epg_data)

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        lst = self.recent_list
        for row in range(lst.count()):
            item = lst.item(row)
            idx = item.data(Qt.UserRole)
            if idx is None or idx < 0 or idx >= len(self.channels):
                continue
            ch = self.channels[idx]
            if not ch.logo_url or not item.icon().isNull():
                continue
            icon = self.logo_cache.get(ch.logo_url)
            if icon is not None:
                item.setIcon(icon)


# ============================================================
# TV Guide Page (EPG for all channels at current time)
# ============================================================
class TvGuidePage(QWidget):
    channel_play = pyqtSignal(int)

    def __init__(self, config: Config, logo_cache: LogoCache = None):
        super().__init__()
        self.config = config
        self.logo_cache = logo_cache
        self.channels = []
        self.epg_data = {}
        self.init_ui()

        # Refresh timer: programmes roll over with time
        self._tick = QTimer(self)
        self._tick.setInterval(60 * 1000)
        self._tick.timeout.connect(self.refresh_list)

        if self.logo_cache is not None:
            self.logo_cache.logo_ready.connect(self._refresh_logos)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        header = QHBoxLayout()
        title = QLabel("TV Guide")
        title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(title)
        header.addStretch()
        self.status = QLabel("")
        self.status.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.status)
        layout.addLayout(header)

        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search channels...")
        self.search_edit.textChanged.connect(self._debounced_refresh)
        layout.addWidget(self.search_edit)
        layout.addSpacing(6)

        self.guide_list = QListWidget()
        self.guide_list.setSpacing(2)
        self.guide_list.setIconSize(QSize(48, 48))
        self.guide_list.setStyleSheet(
            "QListWidget::item { padding: 8px 6px; }"
            "QListWidget::item:selected { background-color: " + COLORS['primary'] + "; color: white; }")
        self.guide_list.itemDoubleClicked.connect(self._on_click)
        layout.addWidget(self.guide_list)

        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(200)
        self._debounce.timeout.connect(self.refresh_list)

    def _debounced_refresh(self, _text):
        self._debounce.start()

    def set_data(self, channels, epg_data):
        self.channels = channels
        self.epg_data = epg_data
        self.refresh_list()
        if not self._tick.isActive():
            self._tick.start()

    def refresh_list(self):
        query = self.search_edit.text().strip().lower()
        lst = self.guide_list
        lst.setUpdatesEnabled(False)
        try:
            lst.clear()
            count = 0
            for idx, ch in enumerate(self.channels):
                if query and query not in ch.name.lower():
                    continue
                now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id, ch.name)
                if now_prog:
                    try:
                        t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                        t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
                        now_text = f"  ▶ {t1}–{t2}  {now_prog.title}"
                    except (OSError, ValueError):
                        now_text = f"  ▶ {now_prog.title}"
                else:
                    now_text = "  ▶ —"
                next_text = ""
                if next_prog:
                    try:
                        nt = datetime.fromtimestamp(next_prog.start).strftime('%H:%M')
                        next_text = f"\n  ⏭ {nt}  {next_prog.title}"
                    except (OSError, ValueError):
                        next_text = f"\n  ⏭ {next_prog.title}"
                item = QListWidgetItem(f"{ch.name}{now_text}{next_text}")
                item.setData(Qt.UserRole, idx)
                icon = None
                if self.logo_cache is not None and ch.logo_url:
                    icon = self.logo_cache.get(ch.logo_url)
                item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
                lst.addItem(item)
                count += 1
        finally:
            lst.setUpdatesEnabled(True)
        self.status.setText(f"{count} channels · updated {datetime.now().strftime('%H:%M')}")

    def _on_click(self, item):
        idx = item.data(Qt.UserRole)
        if idx is not None and idx >= 0:
            self.channel_play.emit(idx)

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        lst = self.guide_list
        for row in range(lst.count()):
            item = lst.item(row)
            idx = item.data(Qt.UserRole)
            if idx is None or idx < 0 or idx >= len(self.channels):
                continue
            ch = self.channels[idx]
            if not ch.logo_url or not item.icon().isNull():
                continue
            icon = self.logo_cache.get(ch.logo_url)
            if icon is not None:
                item.setIcon(icon)


# ============================================================
# Settings Page
# ============================================================
class SettingsPage(QWidget):
    settings_changed = pyqtSignal()

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.init_ui()

    def init_ui(self):
        from PyQt5.QtWidgets import QCheckBox, QSpinBox
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        container = QWidget()
        layout = QVBoxLayout(container)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(10)

        title = QLabel(t('settings'))
        title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        layout.addWidget(title)
        layout.addSpacing(8)

        # VLC status
        vlc_status = "VLC: Installed" if HAS_VLC else "VLC: Not found - install VLC and python-vlc"
        vlc_label = QLabel(vlc_status)
        vlc_label.setStyleSheet(
            f"color: {'#4ECDC4' if HAS_VLC else COLORS['error']}; font-size: 14px;")
        layout.addWidget(vlc_label)
        layout.addSpacing(12)

        # Round 232 (Windows): language selector — самое заметное чего
        # не было в Windows-версии раньше.
        layout.addWidget(self._section(t('language')))
        lang_row = QHBoxLayout()
        lang_row.addWidget(QLabel(t('language') + ":"))
        self.lang_combo = QComboBox()
        for code, label in (
            ('ru', 'Русский'),
            ('en', 'English'),
            ('uk', 'Українська'),
            ('az', 'Azərbaycanca'),
        ):
            self.lang_combo.addItem(label, code)
        cur_lang = getattr(self.config, 'ui_language', 'ru')
        for i in range(self.lang_combo.count()):
            if self.lang_combo.itemData(i) == cur_lang:
                self.lang_combo.setCurrentIndex(i)
                break
        self.lang_combo.currentIndexChanged.connect(self._save_language)
        lang_row.addWidget(self.lang_combo, 1)
        layout.addLayout(lang_row)
        layout.addSpacing(12)

        # --- Playback section ---
        layout.addWidget(self._section("Playback"))

        # Buffer / network caching
        buf_row = QHBoxLayout()
        buf_row.addWidget(QLabel("Buffer (network cache):"))
        self.buf_combo = QComboBox()
        self.buf_combo.addItem("Low (1500 ms)", 1500)
        self.buf_combo.addItem("Normal (3000 ms)", 3000)
        self.buf_combo.addItem("High (6000 ms)", 6000)
        self.buf_combo.addItem("Very high (10000 ms)", 10000)
        self._set_combo_by_value(self.buf_combo, self.config.network_caching_ms, default_idx=1)
        self.buf_combo.currentIndexChanged.connect(self._save_buffer)
        buf_row.addWidget(self.buf_combo, 1)
        layout.addLayout(buf_row)

        # Default volume
        vol_row = QHBoxLayout()
        vol_row.addWidget(QLabel("Default volume:"))
        self.vol_spin = QSpinBox()
        self.vol_spin.setRange(0, 100)
        self.vol_spin.setSuffix("%")
        self.vol_spin.setValue(self.config.volume)
        self.vol_spin.valueChanged.connect(self._save_volume)
        vol_row.addWidget(self.vol_spin)
        vol_row.addStretch()
        layout.addLayout(vol_row)

        # Sleep timer default
        sleep_row = QHBoxLayout()
        sleep_row.addWidget(QLabel("Sleep timer (default):"))
        self.sleep_spin = QSpinBox()
        self.sleep_spin.setRange(0, 240)
        self.sleep_spin.setSuffix(" min (0 = off)")
        self.sleep_spin.setValue(self.config.sleep_timer_minutes)
        self.sleep_spin.valueChanged.connect(self._save_sleep)
        sleep_row.addWidget(self.sleep_spin)
        sleep_row.addStretch()
        layout.addLayout(sleep_row)

        # --- Behaviour section ---
        layout.addSpacing(8)
        layout.addWidget(self._section("Behaviour"))

        self.cb_autoplay = QCheckBox("Autoplay last channel on startup")
        self.cb_autoplay.setChecked(self.config.autoplay_last)
        self.cb_autoplay.toggled.connect(self._save_autoplay)
        layout.addWidget(self.cb_autoplay)

        self.cb_fullscreen = QCheckBox("Open player in fullscreen")
        self.cb_fullscreen.setChecked(self.config.remember_fullscreen)
        self.cb_fullscreen.toggled.connect(self._save_fullscreen)
        layout.addWidget(self.cb_fullscreen)

        self.cb_top = QCheckBox("Always on top (mini-player mode)")
        self.cb_top.setChecked(self.config.always_on_top)
        self.cb_top.toggled.connect(self._save_always_on_top)
        layout.addWidget(self.cb_top)

        # --- Advanced playback section ---
        layout.addSpacing(8)
        layout.addWidget(self._section("Advanced (VLC)"))

        self.cb_hwdec = QCheckBox("Hardware decoding (recommended)")
        self.cb_hwdec.setChecked(self.config.hardware_decode)
        self.cb_hwdec.toggled.connect(self._save_hwdec)
        layout.addWidget(self.cb_hwdec)

        ao_row = QHBoxLayout()
        ao_row.addWidget(QLabel("Audio output:"))
        self.aout_combo = QComboBox()
        self.aout_combo.addItem("Auto", "")
        self.aout_combo.addItem("DirectSound", "directsound")
        self.aout_combo.addItem("MMDevice (WASAPI)", "mmdevice")
        self.aout_combo.addItem("WaveOut", "waveout")
        self._set_combo_by_value(self.aout_combo, self.config.audio_output, 0)
        self.aout_combo.currentIndexChanged.connect(self._save_aout)
        ao_row.addWidget(self.aout_combo, 1)
        layout.addLayout(ao_row)

        ua_row = QHBoxLayout()
        ua_row.addWidget(QLabel("HTTP User-Agent:"))
        self.ua_edit = QLineEdit(self.config.user_agent)
        self.ua_edit.editingFinished.connect(self._save_ua)
        ua_row.addWidget(self.ua_edit, 1)
        layout.addLayout(ua_row)
        ua_hint = QLabel("Note: changes take effect after restart.")
        ua_hint.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 11px;")
        layout.addWidget(ua_hint)

        # --- EPG sources ---
        layout.addSpacing(8)
        layout.addWidget(self._section("EPG sources (multi-EPG)"))

        self.epg_list = QListWidget()
        self.epg_list.setMaximumHeight(120)
        self._refresh_epg_list()
        layout.addWidget(self.epg_list)

        epg_row = QHBoxLayout()
        self.epg_input = QLineEdit()
        self.epg_input.setPlaceholderText("https://example.com/epg.xml.gz")
        epg_row.addWidget(self.epg_input, 1)
        btn_epg_add = QPushButton("Add")
        btn_epg_add.clicked.connect(self._add_epg_url)
        epg_row.addWidget(btn_epg_add)
        btn_epg_del = QPushButton("Remove")
        btn_epg_del.clicked.connect(self._remove_epg_url)
        epg_row.addWidget(btn_epg_del)
        layout.addLayout(epg_row)
        epg_hint = QLabel("Programmes from all sources are merged. The playlist's url-tvg is always used.")
        epg_hint.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 11px;")
        layout.addWidget(epg_hint)

        # --- Data section ---
        layout.addSpacing(8)
        layout.addWidget(self._section("Data"))

        data_row = QHBoxLayout()
        btn_clear_fav = QPushButton("Clear favorites")
        btn_clear_fav.clicked.connect(self._clear_favorites)
        data_row.addWidget(btn_clear_fav)
        btn_clear_recent = QPushButton("Clear recent")
        btn_clear_recent.clicked.connect(self._clear_recent)
        data_row.addWidget(btn_clear_recent)
        btn_clear_pcs = QPushButton("Clear per-channel state")
        btn_clear_pcs.clicked.connect(self._clear_per_channel_state)
        data_row.addWidget(btn_clear_pcs)
        btn_reset = QPushButton("Reset settings")
        btn_reset.clicked.connect(self._reset_settings)
        data_row.addWidget(btn_reset)
        data_row.addStretch()
        layout.addLayout(data_row)

        # --- Updates ---
        layout.addSpacing(8)
        layout.addWidget(self._section("Updates"))
        upd_row = QHBoxLayout()
        self.btn_check_updates = QPushButton("Check for updates")
        self.btn_check_updates.clicked.connect(self._check_updates)
        upd_row.addWidget(self.btn_check_updates)
        self.update_status = QLabel("")
        self.update_status.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 12px;")
        upd_row.addWidget(self.update_status, 1)
        layout.addLayout(upd_row)

        # --- Help / report issue ---
        layout.addSpacing(8)
        layout.addWidget(self._section("Help"))
        help_row = QHBoxLayout()
        btn_report = QPushButton("Report a problem on GitHub")
        btn_report.clicked.connect(self._report_issue)
        help_row.addWidget(btn_report)
        btn_log = QPushButton("Open log folder")
        btn_log.clicked.connect(self._open_log_dir)
        help_row.addWidget(btn_log)
        help_row.addStretch()
        layout.addLayout(help_row)

        # --- Info section ---
        layout.addSpacing(12)
        ver_label = QLabel(f"TVViewer v{WIN_VERSION_NAME} build {WIN_VERSION_CODE} (Windows Desktop)")
        ver_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        layout.addWidget(ver_label)

        info = QLabel(
            "Keyboard shortcuts (Player):\n"
            "  Space — play/pause        F11 — fullscreen\n"
            "  Up/Down — switch channel  [ / ] — speed - / +\n"
            "  + / - — volume            A — aspect ratio\n"
            "  F — toggle favorite       T — audio track\n"
            "  0–9 — channel number      Esc — back\n"
        )
        info.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 12px;")
        info.setWordWrap(True)
        layout.addWidget(info)

        layout.addStretch()
        scroll.setWidget(container)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(scroll)

    def _section(self, text: str) -> QLabel:
        lbl = QLabel(text)
        lbl.setStyleSheet(
            f"color: {COLORS['secondary']}; font-size: 13px; font-weight: bold;"
            f" padding: 4px 0;")
        return lbl

    @staticmethod
    def _set_combo_by_value(combo: QComboBox, value, default_idx: int = 0):
        for i in range(combo.count()):
            if combo.itemData(i) == value:
                combo.setCurrentIndex(i)
                return
        combo.setCurrentIndex(default_idx)

    def _save_buffer(self, _idx):
        self.config.network_caching_ms = int(self.buf_combo.currentData())
        self.config.save()
        self.settings_changed.emit()

    def _save_language(self, _idx):
        # Round 233: язык меняется мгновенно через _retranslate_all —
        # без диалога «перезапустите приложение». Виджеты у которых нет
        # retranslate_ui всё ещё застряют со старыми подписями до
        # перезапуска, но navigation + ключевые экраны обновляются.
        code = self.lang_combo.currentData()
        if not code or code == getattr(self.config, 'ui_language', 'ru'):
            return
        self.config.ui_language = code
        self.config.save()
        set_ui_language(code)
        self.settings_changed.emit()  # MainWindow дёрнет _retranslate_all

    def retranslate_ui(self):
        # SettingsPage.title и section-метки фиксируются в сборке.
        # При смене языка достаточно обновить главный заголовок и
        # известные QLabel'ы.
        try:
            for child in self.findChildren(QLabel):
                # Заголовок «Settings»
                if child.text() in ("Settings", "Настройки", "Налаштування", "Tənzimləmələr"):
                    child.setText(t('settings'))
        except Exception:
            pass

    def _save_volume(self, v):
        self.config.volume = int(v)
        self.config.save()
        self.settings_changed.emit()

    def _save_sleep(self, v):
        self.config.sleep_timer_minutes = int(v)
        self.config.save()

    def _save_autoplay(self, checked):
        self.config.autoplay_last = bool(checked)
        self.config.save()

    def _save_fullscreen(self, checked):
        self.config.remember_fullscreen = bool(checked)
        self.config.save()

    def _save_always_on_top(self, checked):
        self.config.always_on_top = bool(checked)
        self.config.save()
        self.settings_changed.emit()

    def _save_hwdec(self, checked):
        self.config.hardware_decode = bool(checked)
        self.config.save()

    def _save_aout(self, _idx):
        self.config.audio_output = self.aout_combo.currentData() or ""
        self.config.save()

    def _save_ua(self):
        self.config.user_agent = self.ua_edit.text().strip()
        self.config.save()

    def _refresh_epg_list(self):
        self.epg_list.clear()
        for u in getattr(self.config, 'epg_urls', []) or []:
            self.epg_list.addItem(u)

    def _add_epg_url(self):
        u = self.epg_input.text().strip()
        if not u:
            return
        if u in self.config.epg_urls:
            return
        self.config.epg_urls.append(u)
        self.config.save()
        self.epg_input.clear()
        self._refresh_epg_list()
        self.settings_changed.emit()

    def _remove_epg_url(self):
        row = self.epg_list.currentRow()
        if row < 0:
            return
        try:
            self.config.epg_urls.pop(row)
            self.config.save()
            self._refresh_epg_list()
            self.settings_changed.emit()
        except IndexError:
            pass

    def _clear_recent(self):
        self.config.recent_urls = []
        self.config.save()
        self.settings_changed.emit()

    def _clear_per_channel_state(self):
        self.config.per_channel_state = {}
        self.config.save()

    def _report_issue(self):
        try:
            from urllib.parse import quote
            import platform
            log_path = _log_file_path()
            tail = _read_log_tail(log_path, 4000)
            body = (
                "**App version**: TVViewer Windows v5.4\n"
                f"**OS**: {platform.platform()}\n"
                f"**Python**: {platform.python_version()}\n"
                f"**VLC**: {'installed' if HAS_VLC else 'not installed'}\n\n"
                "**Steps to reproduce**:\n"
                "1. \n2. \n3. \n\n"
                "**Expected**:\n\n"
                "**Actual**:\n\n"
                "**Recent log**:\n```\n" + (tail or "(empty)") + "\n```\n"
            )
            # Auto-publish to ntfy first (zero user effort)
            _publish_to_ntfy("[Windows] manual report", body)
            url = ("https://github.com/donmax76/iptv/issues/new"
                   f"?title={quote('[Windows] ')}&body={quote(body)}")
            QApplication.clipboard().setText(url)
            import webbrowser
            webbrowser.open(url)
            QMessageBox.information(self, "Report sent",
                "Лог отправлен на ntfy и URL для GitHub Issue скопирован в буфер обмена.")
        except Exception as e:
            QMessageBox.warning(self, "Report issue", f"Could not open GitHub: {e}")

    def _open_log_dir(self):
        try:
            path = os.path.dirname(os.path.abspath(_log_file_path()))
            if sys.platform == "win32":
                os.startfile(path)
            elif sys.platform == "darwin":
                os.system(f'open "{path}"')
            else:
                os.system(f'xdg-open "{path}"')
        except Exception as e:
            QMessageBox.warning(self, "Open log folder", str(e))

    # ----- Updates -----
    def _check_updates(self):
        self.btn_check_updates.setEnabled(False)
        self.update_status.setText("Checking…")
        self._upd_thread = UpdateCheckThread(self)
        self._upd_thread.finished.connect(self._on_update_check)
        self._upd_thread.start()

    def _on_update_check(self, info):
        self.btn_check_updates.setEnabled(True)
        if not isinstance(info, dict):
            self.update_status.setText("Could not reach GitHub.")
            QMessageBox.information(
                self, "Updates",
                "Could not check for updates (no internet?).")
            return
        cur = WIN_VERSION_CODE
        latest = int(info.get('code', 0))
        if latest <= cur:
            self.update_status.setText(
                f"You have the latest build ({cur}). GitHub: {latest}.")
            QMessageBox.information(
                self, "Updates",
                f"You're on the latest version.\n\n"
                f"Installed: build {cur}\n"
                f"GitHub:    build {latest}")
            return
        # Newer build available — offer to download & install
        msg = (f"New build {latest} available (you have {cur}).\n\n"
               f"{(info.get('notes') or '')[:500]}\n\n"
               f"Download and install now?")
        reply = QMessageBox.question(
            self, "Update available", msg,
            QMessageBox.Yes | QMessageBox.No, QMessageBox.Yes)
        if reply != QMessageBox.Yes:
            self.update_status.setText(f"Build {latest} available.")
            return
        url = info.get('url') or ''
        if not url.lower().endswith('.exe'):
            QMessageBox.warning(self, "Updates",
                "Release does not have an EXE asset; opening browser.")
            try:
                import webbrowser; webbrowser.open(url)
            except Exception: pass
            return
        self._do_download(url, latest)

    def _do_download(self, url: str, build: int):
        self._dl_progress = QProgressBar()
        self._dl_progress.setMaximum(100)
        self._dl_dialog = QDialog(self)
        self._dl_dialog.setWindowTitle("Downloading update…")
        self._dl_dialog.setModal(True)
        self._dl_dialog.setMinimumWidth(400)
        v = QVBoxLayout(self._dl_dialog)
        v.addWidget(QLabel(f"Downloading build {build}…"))
        v.addWidget(self._dl_progress)
        self._dl_thread = DownloadUpdateThread(url, self)
        self._dl_thread.progress.connect(self._dl_progress.setValue)
        self._dl_thread.finished.connect(self._on_download_done)
        self._dl_thread.error.connect(self._on_download_error)
        self._dl_thread.start()
        self._dl_dialog.show()

    def _on_download_done(self, path):
        if hasattr(self, '_dl_dialog'):
            self._dl_dialog.accept()
        if not path:
            return
        if _swap_self_and_restart(path):
            QApplication.quit()
        else:
            QMessageBox.information(
                self, "Update downloaded",
                f"New EXE saved to:\n{path}\n\n"
                "Close TVViewer, replace the existing EXE with this file, "
                "then start it again.")

    def _on_download_error(self, err: str):
        if hasattr(self, '_dl_dialog'):
            self._dl_dialog.reject()
        QMessageBox.warning(self, "Download failed", err)

    def _clear_favorites(self):
        reply = QMessageBox.question(
            self, "Clear favorites",
            "Remove all favorites?",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply == QMessageBox.Yes:
            self.config.favorites.clear()
            self.config.save()
            self.settings_changed.emit()

    def _reset_settings(self):
        reply = QMessageBox.question(
            self, "Reset settings",
            "Reset all settings to defaults? Playlists and favorites are kept.",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply != QMessageBox.Yes:
            return
        self.config.volume = 80
        self.config.network_caching_ms = 3000
        self.config.autoplay_last = False
        self.config.remember_fullscreen = False
        self.config.sleep_timer_minutes = 0
        self.config.always_on_top = False
        self.config.hardware_decode = True
        self.config.audio_output = ""
        self.config.channel_sort = "default"
        self.config.save()
        # Refresh UI
        self.vol_spin.setValue(self.config.volume)
        self._set_combo_by_value(self.buf_combo, self.config.network_caching_ms, 1)
        self.sleep_spin.setValue(0)
        self.cb_autoplay.setChecked(False)
        self.cb_fullscreen.setChecked(False)
        self.cb_top.setChecked(False)
        self.cb_hwdec.setChecked(True)
        self._set_combo_by_value(self.aout_combo, "", 0)
        self.settings_changed.emit()


# ============================================================
# Main Window
# ============================================================
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.config = Config()
        self.channels = []
        self.epg_data = {}
        self.loader_thread = None
        self.epg_thread = None
        # Корень для всех кэшей: лого, EPG, iptv-org channels.json,
        # tvviewer_trace.txt. Хранится рядом с config.
        self.cache_dir = os.path.dirname(os.path.abspath(CONFIG_FILE))
        # Shared logo cache (async network + on-disk cache)
        cache_root = os.path.join(self.cache_dir, "tvviewer_logos")
        self.logo_cache = LogoCache(cache_root, self)
        # Pre-warm iptv-org channels DB чтобы лого/tvg-id для каналов
        # без tvg-logo стали доступны через несколько секунд после
        # старта (Android делает то же в TVViewerApp.onCreate).
        try:
            channel_meta_lookup.ensure_loaded(self.cache_dir)
        except Exception:
            pass
        self.setWindowTitle("M3U IPTV - TVViewer")
        self.setMinimumSize(900, 600)
        self.resize(1100, 700)
        self.init_ui()
        self.auto_load_last()
        # Silent auto-check for new build at startup (only for frozen EXE)
        QTimer.singleShot(3000, self._auto_check_updates)

    def _auto_check_updates(self):
        if not getattr(sys, 'frozen', False):
            return
        try:
            self._startup_upd = UpdateCheckThread(self)
            self._startup_upd.finished.connect(self._on_startup_update_check)
            self._startup_upd.start()
        except Exception:
            pass

    def _on_startup_update_check(self, info):
        if not isinstance(info, dict):
            return
        latest = int(info.get('code', 0))
        if latest <= WIN_VERSION_CODE:
            return
        msg = (f"New build {latest} available (you have {WIN_VERSION_CODE}).\n\n"
               f"{(info.get('notes') or '')[:400]}\n\n"
               f"Download and install now?")
        reply = QMessageBox.question(
            self, "Update available", msg,
            QMessageBox.Yes | QMessageBox.No, QMessageBox.Yes)
        if reply != QMessageBox.Yes:
            return
        url = info.get('url') or ''
        if not url.lower().endswith('.exe'):
            return
        self.settings_page._do_download(url, latest)

    def init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        # Content area
        self.stack = QStackedWidget()

        self.playlists_page = PlaylistsPage(self.config)
        self.playlists_page.playlist_selected.connect(self.load_playlist)
        self.stack.addWidget(self.playlists_page)

        self.channels_page = ChannelsPage(self.config, self.logo_cache)
        self.channels_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.channels_page)

        self.favorites_page = FavoritesPage(self.config, self.logo_cache)
        self.favorites_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.favorites_page)

        self.player_page = PlayerPage(self.config, self.logo_cache)
        self.player_page.back_requested.connect(self.show_channels)
        self.stack.addWidget(self.player_page)

        self.settings_page = SettingsPage(self.config)
        self.settings_page.settings_changed.connect(self._on_settings_changed)
        self.stack.addWidget(self.settings_page)

        self.tv_guide_page = TvGuidePage(self.config, self.logo_cache)
        self.tv_guide_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.tv_guide_page)

        self.recent_page = RecentPage(self.config, self.logo_cache)
        self.recent_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.recent_page)

        main_layout.addWidget(self.stack, 1)

        # Bottom navigation bar
        nav_bar = QWidget()
        nav_bar.setStyleSheet(f"background-color: {COLORS['surface']};")
        nav_bar.setFixedHeight(52)
        nav_layout = QHBoxLayout(nav_bar)
        nav_layout.setContentsMargins(0, 0, 0, 0)
        nav_layout.setSpacing(0)

        # Round 233: nav-кнопки храним вместе с translation-ключом,
        # чтобы retranslate_ui() мог обновить подписи без пересборки.
        self.nav_buttons = []
        nav_items = [
            ('playlists', 0),
            ('channels', 1),
            ('tv_guide', 5),
            ('favorites', 2),
            ('recent', 6),
            ('settings', 4),
        ]
        for tkey, page_idx in nav_items:
            btn = QPushButton(t(tkey))
            btn.setObjectName("navBtn")
            btn.setProperty('_t_key', tkey)
            btn.clicked.connect(lambda checked, idx=page_idx: self.switch_page(idx))
            nav_layout.addWidget(btn)
            self.nav_buttons.append((btn, page_idx))

        main_layout.addWidget(nav_bar)
        self.update_nav_highlight(0)

    def _update_nav_labels(self):
        for btn, _idx in getattr(self, 'nav_buttons', []):
            key = btn.property('_t_key')
            if key:
                btn.setText(t(key))

    def switch_page(self, idx):
        if idx == 2:
            self.favorites_page.refresh(self.channels, self.epg_data)
        elif idx == 5:
            self.tv_guide_page.set_data(self.channels, self.epg_data)
        elif idx == 6:
            self.recent_page.refresh(self.channels, self.epg_data)
        self.player_page.stop()
        self.stack.setCurrentIndex(idx)
        self.update_nav_highlight(idx)
        focus_target = None
        if idx == 1 and self.channels_page.channel_list.count():
            focus_target = self.channels_page.channel_list
        elif idx == 2 and self.favorites_page.fav_list.count():
            focus_target = self.favorites_page.fav_list
        elif idx == 5 and self.tv_guide_page.guide_list.count():
            focus_target = self.tv_guide_page.guide_list
        elif idx == 6 and self.recent_page.recent_list.count():
            focus_target = self.recent_page.recent_list
        if focus_target is not None:
            if focus_target.currentRow() < 0:
                focus_target.setCurrentRow(0)
            focus_target.setFocus()

    def keyPressEvent(self, event):
        key = event.key()
        # Round 232: L / R открывают side-panels плеера, ТОЛЬКО если
        # сейчас открыт PlayerPage. На других страницах эти клавиши
        # отдаются Qt по умолчанию (например, для поиска по букве в
        # списке).
        try:
            current = self.stack.currentWidget()
            if isinstance(current, PlayerPage):
                if key == Qt.Key_L:
                    current.toggle_channels_overlay(); return
                if key == Qt.Key_R:
                    current.toggle_quick_overlay(); return
                if key == Qt.Key_Escape:
                    # ESC закрывает любой видимый overlay
                    if hasattr(current, 'channels_overlay') and current.channels_overlay.isVisible():
                        current.channels_overlay.hide(); return
                    if hasattr(current, 'quick_overlay') and current.quick_overlay.isVisible():
                        current.quick_overlay.hide(); return
        except Exception:
            pass
        # Global section shortcuts (work from anywhere)
        if key == Qt.Key_F1:
            self.switch_page(0); return
        if key == Qt.Key_F2:
            self.switch_page(1); return
        if key == Qt.Key_F3:
            self.switch_page(5); return
        if key == Qt.Key_F4:
            self.switch_page(2); return
        if key == Qt.Key_F6:
            self.switch_page(6); return
        if key == Qt.Key_F5:
            if self.config.last_playlist_url:
                self.load_playlist(
                    self.config.last_playlist_name or "Playlist",
                    self.config.last_playlist_url)
            return
        super().keyPressEvent(event)

    def update_nav_highlight(self, active_idx):
        for btn, idx in self.nav_buttons:
            if idx == active_idx:
                btn.setObjectName("navBtnActive")
            else:
                btn.setObjectName("navBtn")
            btn.setStyleSheet(STYLESHEET)

    def load_playlist(self, name, url):
        self.channels_page.status_label.setText("Loading...")
        self.switch_page(1)
        self.config.last_playlist_url = url
        self.config.last_playlist_name = name
        self.config.save()

        self.loader_thread = LoadPlaylistThread(url)
        self.loader_thread.finished.connect(lambda r: self.on_playlist_loaded(r, name))
        self.loader_thread.error.connect(self.on_playlist_error)
        self.loader_thread.start()

    def on_playlist_loaded(self, result: PlaylistResult, name: str):
        self.channels = result.channels
        # Fallback логотипов через iptv-org channels.json (как Android
        # ChannelMetaLookup). Если плейлист не несёт tvg-logo, пробуем
        # найти по имени канала. Если БД ещё не загружена — повторим
        # после её загрузки через коллбэк.
        try:
            channel_meta_lookup.fill_missing_logos(self.channels)
        except Exception:
            pass
        self.channels_page.set_channels(self.channels, name, self.epg_data)
        self.channels_page.status_label.setText(f"{len(self.channels)} channels loaded")
        # Кикаем загрузку iptv-org БД (no-op если уже загружена), и
        # после готовности заново применяем fill_missing_logos +
        # обновляем UI.
        def on_meta_ready():
            enriched = 0
            try:
                enriched = channel_meta_lookup.fill_missing_logos(self.channels)
            except Exception:
                pass
            if enriched:
                trace("META", f"enriched {enriched} channels with iptv-org logos/tvg-ids")
                # Перерисовываем оба списка
                if hasattr(self, 'channels_page'):
                    self.channels_page.set_channels(self.channels, name, self.epg_data)
        channel_meta_lookup.ensure_loaded(self.cache_dir,
                                          on_loaded=lambda: QTimer.singleShot(0, on_meta_ready))

        # Build the EPG source list: playlist's url-tvg + last_epg_url + extra
        # epg_urls + built-in defaults so EPG works out-of-the-box even when
        # the playlist has no x-tvg-url and the user hasn't added a source.
        epg_sources = []
        if result.epg_url:
            self.config.last_epg_url = result.epg_url
            self.config.save()
            epg_sources.append(result.epg_url)
        elif self.config.last_epg_url:
            epg_sources.append(self.config.last_epg_url)
        for u in getattr(self.config, 'epg_urls', []) or []:
            if u and u not in epg_sources:
                epg_sources.append(u)
        for u in DEFAULT_EPG_URLS:
            if u not in epg_sources:
                epg_sources.append(u)
        if epg_sources:
            self.load_epg(epg_sources)

        # Autoplay last channel (best-effort: match by URL)
        if self.config.autoplay_last and self.config.last_channel_url:
            for i, ch in enumerate(self.channels):
                if ch.url == self.config.last_channel_url:
                    self.play_channel(i)
                    break

    def on_playlist_error(self, error: str):
        self.channels_page.status_label.setText(f"Error: {error}")

    def load_epg(self, urls):
        # Accept single URL (str) or list of URLs (multi-EPG).
        # Build filter from current playlist so big XMLTV files don't keep
        # 5000+ unused channels in memory (mirrors Android Round 73).
        playlist_keys = set()
        for ch in self.channels:
            if getattr(ch, 'tvg_id', None):
                k = normalize_id(ch.tvg_id)
                if k:
                    playlist_keys.add(k)
            n = normalize_id(ch.name)
            if n:
                playlist_keys.add(n)
        self.epg_thread = LoadEpgThread(urls, channel_filter=playlist_keys or None)
        self.epg_thread.finished.connect(self.on_epg_loaded)
        # Live progress goes to ChannelsPage status label so user sees
        # "downloaded 8500 KB, парсю…" instead of a frozen UI.
        if hasattr(self, 'channels_page') and hasattr(self.channels_page, 'status_label'):
            self.epg_thread.progress.connect(self.channels_page.status_label.setText)
        self.epg_thread.start()

    def on_epg_loaded(self, data):
        if data:
            self.epg_data = data
            self.channels_page.set_epg(data)
            if self.stack.currentIndex() == 5:
                self.tv_guide_page.set_data(self.channels, self.epg_data)

    def play_channel(self, index):
        if index < 0 or index >= len(self.channels):
            return
        self.stack.setCurrentIndex(3)
        self.update_nav_highlight(-1)
        self.player_page.play_channel(index, self.channels, self.epg_data)
        # Apply remembered fullscreen preference
        if self.config.remember_fullscreen and not self.isFullScreen():
            self.showFullScreen()
        # Start sleep timer if configured
        if self.config.sleep_timer_minutes > 0:
            self.player_page._start_sleep_timer(self.config.sleep_timer_minutes)

    def show_channels(self):
        self.player_page.stop()
        if self.isFullScreen():
            self.showNormal()
        self.switch_page(1)

    def toggle_pip_mode(self):
        """Frameless 480×270 always-on-top mini player in the screen corner."""
        if not getattr(self, '_pip_active', False):
            self._pip_prev_geom = self.geometry()
            self._pip_prev_flags = self.windowFlags()
            self._pip_prev_fullscreen = self.isFullScreen()
            if self._pip_prev_fullscreen:
                self.showNormal()
            self.setWindowFlag(Qt.FramelessWindowHint, True)
            self.setWindowFlag(Qt.WindowStaysOnTopHint, True)
            screen = QApplication.primaryScreen().availableGeometry()
            self.resize(480, 270)
            self.move(screen.right() - 480 - 20, screen.top() + 20)
            # Hide bottom nav while in mini-mode
            for btn, _idx in getattr(self, 'nav_buttons', []):
                btn.parent().setVisible(False)
                break
            self._pip_active = True
            self.show()
        else:
            self.setWindowFlag(Qt.FramelessWindowHint, False)
            self.setWindowFlag(Qt.WindowStaysOnTopHint, bool(self.config.always_on_top))
            if hasattr(self, '_pip_prev_geom'):
                self.setGeometry(self._pip_prev_geom)
            for btn, _idx in getattr(self, 'nav_buttons', []):
                btn.parent().setVisible(True)
                break
            self._pip_active = False
            if getattr(self, '_pip_prev_fullscreen', False):
                self.showFullScreen()
            else:
                self.show()

    def auto_load_last(self):
        url = self.config.last_playlist_url
        name = self.config.last_playlist_name
        if url:
            self.load_playlist(name or "Playlist", url)

    def _on_settings_changed(self):
        # Push new default volume to the running player
        self.player_page.vol_slider.setValue(self.config.volume)
        # Refresh channel list (favorite state / category visibility may have changed)
        self.channels_page.filter_channels()
        if self.stack.currentIndex() == 2:
            self.favorites_page.refresh(self.channels, self.epg_data)
        if self.stack.currentIndex() == 5:
            self.tv_guide_page.set_data(self.channels, self.epg_data)
        if self.stack.currentIndex() == 6:
            self.recent_page.refresh(self.channels, self.epg_data)
        # Apply mini-player (always-on-top) toggle live
        try:
            cur = bool(self.windowFlags() & Qt.WindowStaysOnTopHint)
            if cur != bool(self.config.always_on_top):
                self.setWindowFlag(Qt.WindowStaysOnTopHint, bool(self.config.always_on_top))
                self.show()
        except Exception:
            pass
        # Round 233: retranslate UI без перезапуска. Каждая страница
        # обновляет свои подписи в retranslate_ui(); основное навигация
        # обновляется отдельно.
        try:
            self._retranslate_all()
        except Exception:
            pass

    def _retranslate_all(self):
        # MainWindow chrome
        try:
            self.setWindowTitle(t('app_name'))
        except Exception:
            pass
        # Nav buttons (label store keys → t())
        try:
            self._update_nav_labels()
        except Exception:
            pass
        # Every page that has retranslate_ui() gets a call.
        for page in (
            getattr(self, 'home_page', None),
            getattr(self, 'playlists_page', None),
            getattr(self, 'channels_page', None),
            getattr(self, 'favorites_page', None),
            getattr(self, 'player_page', None),
            getattr(self, 'settings_page', None),
            getattr(self, 'tv_guide_page', None),
            getattr(self, 'recent_page', None),
        ):
            fn = getattr(page, 'retranslate_ui', None)
            if callable(fn):
                try:
                    fn()
                except Exception:
                    pass

    def closeEvent(self, event):
        self.player_page.stop()
        self.player_page.release_vlc()
        self.config.save()
        event.accept()


def _install_crash_handler(app):
    """Log unhandled exceptions to disk and offer a 'Report on GitHub' dialog."""
    import traceback
    import logging
    import platform as _platform
    log_path = _log_file_path()
    try:
        logging.basicConfig(
            filename=log_path,
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(message)s",
        )
    except Exception:
        pass

    def _excepthook(exc_type, exc_value, exc_tb):
        try:
            tb_text = "".join(traceback.format_exception(exc_type, exc_value, exc_tb))
            try:
                logging.error("Unhandled exception:\n%s", tb_text)
            except Exception:
                pass
            # Offer to file an issue
            try:
                from urllib.parse import quote
                short = (exc_value.args[0] if getattr(exc_value, 'args', None) else str(exc_value))[:80]
                body = (
                    "Automatic crash report.\n\n"
                    f"**App**: TVViewer Windows v{WIN_VERSION_NAME} build {WIN_VERSION_CODE}\n"
                    f"**OS**: {_platform.platform()}\n"
                    f"**Python**: {_platform.python_version()}\n\n"
                    "**Traceback**:\n```\n" + tb_text[-4000:] + "\n```\n"
                )
                # Auto-publish to ntfy.sh — same topic as Android, so the
                # developer can read crashes from any client without
                # additional setup.
                _publish_to_ntfy(f"[Windows crash] {short}", body)
                url = ("https://github.com/donmax76/iptv/issues/new"
                       f"?title={quote('[Windows crash] ' + short)}&body={quote(body)}")
                msg = QMessageBox()
                msg.setIcon(QMessageBox.Critical)
                msg.setWindowTitle("TVViewer crashed")
                msg.setText("An unexpected error occurred.")
                msg.setInformativeText(str(exc_value)[:300])
                msg.setDetailedText(tb_text[-3000:])
                btn_report = msg.addButton("Report on GitHub", QMessageBox.AcceptRole)
                msg.addButton(QMessageBox.Close)
                msg.exec_()
                if msg.clickedButton() is btn_report:
                    import webbrowser
                    webbrowser.open(url)
            except Exception:
                pass
        finally:
            sys.__excepthook__(exc_type, exc_value, exc_tb)

    sys.excepthook = _excepthook


def main():
    app = QApplication(sys.argv)
    app.setStyleSheet(STYLESHEET)
    app.setFont(QFont('Segoe UI', 12))
    _install_crash_handler(app)
    # Round 232: применяем язык до сборки UI. MainWindow при создании
    # тоже инициализирует Config, но мы это делаем СНАЧАЛА чтобы при
    # рендере виджетов уже была правильная локаль.
    _bootstrap_cfg = Config()
    set_ui_language(getattr(_bootstrap_cfg, 'ui_language', 'ru'))
    window = MainWindow()
    # Apply persisted always-on-top preference
    if window.config.always_on_top:
        window.setWindowFlag(Qt.WindowStaysOnTopHint, True)
    window.show()
    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
