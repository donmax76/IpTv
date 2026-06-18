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
    QGraphicsOpacityEffect, QStyledItemDelegate, QStyle,
    QGraphicsDropShadowEffect,
)
from PyQt5.QtCore import (
    Qt, QTimer, pyqtSignal, QThread, QSize, QUrl, QObject,
    QPropertyAnimation, QEasingCurve, QRect, QRectF,
)
from PyQt5.QtGui import (
    QFont, QColor, QPalette, QIcon, QPixmap, QKeySequence,
    QPainter, QBrush, QPen, QFontMetrics,
)
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
from epg_parser import (
    fetch_epg, get_now_next, get_current_progress, get_upcoming_programmes,
    EpgData, normalize_id, fuzzy_key, trace,
)
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


# Round 258: централизованное логирование. Юзер: «добавь логирования всех
# ошибок». Инициализируем logging СРАЗУ при импорте, чтобы все ошибки
# (даже до запуска MainWindow / splash) попадали в файл.
import logging as _logging
_LOGGER = _logging.getLogger('tvviewer')
if not _LOGGER.handlers:
    try:
        _h = _logging.FileHandler(_log_file_path(), encoding='utf-8')
        _h.setFormatter(_logging.Formatter(
            '%(asctime)s %(levelname)s [%(name)s] %(message)s'))
        _LOGGER.addHandler(_h)
        _LOGGER.setLevel(_logging.INFO)
        _LOGGER.propagate = False
    except Exception:
        pass


def _safe_call(fn, *args, **kwargs):
    """Round 281: try/except-wrapped вызов, лог в случае ошибки. Удобно
    подсовывать в daemon-Thread targets без писания лишних try'ев."""
    try:
        return fn(*args, **kwargs)
    except Exception as e:
        try:
            log_error(getattr(fn, '__name__', 'safe_call'), e)
        except Exception:
            pass


def log_error(tag: str, err, extra: str = ""):
    """Запись ошибки + traceback в tvviewer.log. Используется в except-
    блоках вместо silent pass."""
    try:
        import traceback as _tb
        if isinstance(err, BaseException):
            msg = f"[{tag}] {type(err).__name__}: {err}"
            if extra:
                msg = f"{msg} | {extra}"
            _LOGGER.error("%s\n%s", msg, _tb.format_exc())
        else:
            _LOGGER.error("[%s] %s%s", tag, err,
                          f" | {extra}" if extra else "")
    except Exception:
        pass


def log_info(tag: str, msg: str):
    try:
        _LOGGER.info("[%s] %s", tag, msg)
    except Exception:
        pass


def log_warn(tag: str, msg: str):
    try:
        _LOGGER.warning("[%s] %s", tag, msg)
    except Exception:
        pass


def _install_threading_hook():
    """Round 258: ловим необработанные исключения из QThread/threading.
    Без этого крах в фоновой нитке (LoadEpgThread, _PhotoFetcher,
    DownloadUpdateThread) проходил в /dev/null."""
    try:
        import threading
        if hasattr(threading, 'excepthook'):
            def _hook(args):
                try:
                    log_error('thread', args.exc_value,
                              extra=f"thread={getattr(args.thread, 'name', '?')}")
                except Exception:
                    pass
            threading.excepthook = _hook
    except Exception:
        pass


_install_threading_hook()


def _install_main_thread_watchdog():
    """Round 272: ловит зависания main-thread и пишет stack trace в лог.

    Юзер: «в логе ничего нет того что происходит с формой почему оно
    тормозит и зависает, нужно чтобы были ошибки». Зависание само по
    себе НЕ exception — Python не знает, что main thread заблокирован
    в `socket.recv` / `subprocess` / тяжёлом for-цикле. Watchdog:

      1) main thread тикает heartbeat в shared variable раз в 1 сек
         через QTimer (когда Qt event loop работает — heartbeat идёт).
      2) Background-thread каждые 2 сек проверяет heartbeat. Если он
         не обновлялся >3 сек — main thread где-то завис.
      3) Вытаскивает stack trace main-thread'а через sys._current_frames()
         и пишет в лог как WARNING. Юзер видит, ГДЕ именно стояли.
    """
    try:
        import threading as _th
        import time as _t
        import traceback as _tb
        import sys as _sys
        # main-thread heartbeat (взять id один раз при инсталляции).
        try:
            _main_tid = _th.main_thread().ident
        except Exception:
            _main_tid = None
        if _main_tid is None:
            return
        _last_tick = [_t.monotonic()]
        # Стартанём QTimer только когда QApplication уже создан — это
        # делает watchdog_start() из main(). Тут просто экспонируем.
        _last_tick_ref = _last_tick

        def _watcher():
            warned_at = 0.0
            while True:
                _t.sleep(2.0)
                now = _t.monotonic()
                elapsed = now - _last_tick_ref[0]
                # Триггер: >3 сек без тика И не чаще раза в 5 сек.
                if elapsed > 3.0 and now - warned_at > 5.0:
                    try:
                        frames = _sys._current_frames()
                        frame = frames.get(_main_tid)
                        if frame is not None:
                            tb = "".join(_tb.format_stack(frame))
                        else:
                            tb = "(no frame for main thread)"
                        log_warn('watchdog',
                                 f"main thread blocked {elapsed:.1f}s\n{tb}")
                        warned_at = now
                    except Exception as e:
                        log_error('watchdog.dump', e)

        _th.Thread(target=_watcher, daemon=True, name='wd').start()
        globals()['_WATCHDOG_LAST_TICK'] = _last_tick
        log_info('watchdog', f"installed, main_tid={_main_tid}")
    except Exception as e:
        log_error('_install_main_thread_watchdog', e)


def _start_watchdog_heartbeat(app):
    """Round 272: запускает QTimer тика watchdog после QApplication."""
    try:
        import time as _t
        last = globals().get('_WATCHDOG_LAST_TICK')
        if last is None:
            return
        from PyQt5.QtCore import QTimer as _QT
        _tmr = _QT()
        _tmr.setInterval(1000)
        _tmr.timeout.connect(lambda: last.__setitem__(0, _t.monotonic()))
        _tmr.start()
        globals()['_WATCHDOG_TIMER'] = _tmr  # держим ref от GC
    except Exception as e:
        log_error('_start_watchdog_heartbeat', e)


_install_main_thread_watchdog()


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
# Round 242 (Windows): ChannelSorter — порт Android ChannelSorter.kt
# (Android Round 194). Поддерживает default / number / name / group /
# quality. Используется и в ChannelsPage и в Favorites/Recent.
# ============================================================
def sort_channels(channels, sort_mode):
    """Сортирует список Channel'ов по заданному режиму. Возвращает
    новый отсортированный список (исходный не мутирует)."""
    if not channels:
        return channels
    if sort_mode == "name":
        return sorted(channels, key=lambda c: (c.name or "").lower())
    if sort_mode == "group":
        # Без группы — в самый низ по алфавиту.
        return sorted(channels, key=lambda c: (
            (c.group or "zzzzzz").lower(),
            (c.name or "").lower(),
        ))
    if sort_mode == "quality":
        rank = {"4K": 0, "FHD": 1, "HD": 2, "SD": 3, "": 4}
        return sorted(channels, key=lambda c: (
            rank.get(detect_quality(c.name or ""), 4),
            (c.name or "").lower(),
        ))
    if sort_mode == "number":
        # Используем tvg_id если число, иначе позицию в плейлисте.
        def _num(c):
            try:
                return int(c.tvg_id) if c.tvg_id and c.tvg_id.isdigit() else 10**9
            except Exception:
                return 10**9
        return sorted(channels, key=lambda c: (_num(c), (c.name or "").lower()))
    return list(channels)  # "default"


# ============================================================
# Round 242 (Windows): XtreamApi — порт Android XtreamApi.kt. Авторизация
# через player_api.php + сборка M3U-URL для get.php.
# ============================================================
class XtreamApi:
    @staticmethod
    def authenticate(server, username, password):
        """Возвращает dict с user_info при успехе, None при ошибке."""
        try:
            base = (server or "").rstrip('/')
            url = f"{base}/player_api.php?username={username}&password={password}"
            req = urllib.request.Request(
                url, headers={'User-Agent': 'TVViewer/Windows'})
            with urllib.request.urlopen(req, timeout=15) as resp:
                if getattr(resp, 'status', 200) != 200:
                    return None
                body = resp.read().decode('utf-8', errors='replace')
            data = json.loads(body)
            ui = data.get('user_info') or {}
            if str(ui.get('auth', '0')) != '1':
                return None
            return {'user_info': ui, 'server_info': data.get('server_info')}
        except Exception:
            return None

    @staticmethod
    def build_m3u_url(server, username, password):
        base = (server or "").rstrip('/')
        return f"{base}/get.php?username={username}&password={password}&type=m3u_plus&output=ts"


# ============================================================
# Round 232 (Windows): i18n. Простая словарная схема — таблица
# ключ → перевод по локали. ru/en/uk/az. Дефолт ru. Меняется
# в SettingsPage; некоторые экраны требуют перезапуска (надписи
# фиксируются в момент сборки UI).
# ============================================================
TRANSLATIONS = {
    'ru': {
        'app_name': "M3U IPTV",
        'home': "Главная",
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
        # Round 265: расширенный набор для полного покрытия UI.
        'live': "Прямой эфир",
        'add_url': "+ Добавить URL",
        'open_file': "+ Открыть файл",
        'xtream': "+ Xtream",
        'from_clipboard': "📋 Из буфера",
        'clear': "Очистить",
        'add': "Добавить",
        'cancel': "Отмена",
        'ok': "OK",
        'save': "Сохранить",
        'reset_settings': "Сбросить настройки",
        'clear_favorites': "Очистить избранное",
        'clear_recent': "Очистить недавние",
        'clear_per_channel_state': "Очистить состояние каналов",
        'check_for_updates': "Проверить обновления",
        'open_releases': "Открыть страницу релизов на GitHub",
        'open_log_folder': "Открыть папку логов",
        'report_issue': "Сообщить о проблеме на GitHub",
        'updates': "Обновления",
        'data': "Данные",
        'help': "Помощь",
        'about': "О программе",
        'now': "Сейчас",
        'next_program': "Далее",
        'no_program': "Нет программы",
        'select_playlist': "Выберите плейлист · перетащите .m3u/.m3u8 сюда",
        'add_playlist_title': "Добавить плейлист",
        'xtream_codes': "Xtream Codes",
        'channel': "Канал",
        'buffer_label': "Буфер (сетевой кэш):",
        'default_volume': "Громкость по умолчанию:",
        'color_theme': "Цветовая тема:",
        'audio_output': "Аудио-выход:",
        'channel_sort': "Сортировка каналов:",
        'autoplay_last': "Авто-воспроизведение последнего",
        'remember_fullscreen': "Запоминать fullscreen",
        'always_on_top': "Поверх всех окон",
        'hardware_decode': "Аппаратное декодирование",
        'user_agent': "User-Agent для потоков:",
        'epg_sources': "Источники EPG:",
        'mini_player': "Мини-плеер",
        'installed': "Установлено",
        'channels_count_short': "{n} кан.",
        'favorites_label': "Избранное",
        'recent_label': "Недавние",
        'tv_guide_label': "ТВ-гид",
        'channels_label': "Каналы",
        'playlists_label': "Плейлисты",
        'search_channels': "Поиск каналов...",
        'mute': "Без звука",
        'channels_in_list': "{n} каналов",
        'paste_url': "Вставьте URL плейлиста",
        'name_optional': "Имя (необязательно)",
        'updates_check_in_progress': "Проверяю…",
        'on_latest_version': "У вас последняя версия",
        'new_build_available': "Доступен новый build {build}",
        'download_install': "Скачать и установить?",
        'update_no_internet': "Не удалось связаться с GitHub (нет интернета?)",
        'restart_required': "Требуется перезапуск приложения",
    },
    'en': {
        'app_name': "M3U IPTV",
        'home': "Home",
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
        'live': "Live",
        'add_url': "+ Add URL",
        'open_file': "+ Open file",
        'xtream': "+ Xtream",
        'from_clipboard': "📋 From clipboard",
        'clear': "Clear",
        'add': "Add",
        'cancel': "Cancel",
        'ok': "OK",
        'save': "Save",
        'reset_settings': "Reset settings",
        'clear_favorites': "Clear favorites",
        'clear_recent': "Clear recent",
        'clear_per_channel_state': "Clear per-channel state",
        'check_for_updates': "Check for updates",
        'open_releases': "Open GitHub releases page",
        'open_log_folder': "Open log folder",
        'report_issue': "Report a problem on GitHub",
        'updates': "Updates",
        'data': "Data",
        'help': "Help",
        'about': "About",
        'now': "Now",
        'next_program': "Next",
        'no_program': "No programme",
        'select_playlist': "Select a playlist · drop .m3u/.m3u8 files here to import",
        'add_playlist_title': "Add Playlist",
        'xtream_codes': "Xtream Codes",
        'channel': "Channel",
        'buffer_label': "Buffer (network cache):",
        'default_volume': "Default volume:",
        'color_theme': "Color theme:",
        'audio_output': "Audio output:",
        'channel_sort': "Channel sort:",
        'autoplay_last': "Autoplay last channel",
        'remember_fullscreen': "Remember fullscreen",
        'always_on_top': "Always on top",
        'hardware_decode': "Hardware decoding",
        'user_agent': "HTTP User-Agent:",
        'epg_sources': "EPG sources:",
        'mini_player': "Mini player",
        'installed': "Installed",
        'channels_count_short': "{n} ch.",
        'favorites_label': "Favorites",
        'recent_label': "Recent",
        'tv_guide_label': "TV Guide",
        'channels_label': "Channels",
        'playlists_label': "Playlists",
        'search_channels': "Search channels...",
        'mute': "Mute",
        'channels_in_list': "{n} channels",
        'paste_url': "Paste playlist URL",
        'name_optional': "Name (optional)",
        'updates_check_in_progress': "Checking…",
        'on_latest_version': "You're on the latest version",
        'new_build_available': "Build {build} available",
        'download_install': "Download and install?",
        'update_no_internet': "Could not reach GitHub (no internet?)",
        'restart_required': "Application restart required",
    },
    'uk': {
        'app_name': "M3U IPTV",
        'home': "Головна",
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
        'home': "Əsas",
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
        'live': "Canlı yayım",
        'add_url': "+ URL əlavə et",
        'open_file': "+ Fayl aç",
        'xtream': "+ Xtream",
        'from_clipboard': "📋 Mübadilə buferindən",
        'clear': "Təmizlə",
        'add': "Əlavə et",
        'cancel': "Ləğv et",
        'ok': "OK",
        'save': "Saxla",
        'reset_settings': "Tənzimləmələri sıfırla",
        'clear_favorites': "Seçilmişləri sil",
        'clear_recent': "Son baxılanları sil",
        'clear_per_channel_state': "Kanal vəziyyətlərini sil",
        'check_for_updates': "Yeniləmələri yoxla",
        'open_releases': "GitHub releases səhifəsini aç",
        'open_log_folder': "Loq qovluğunu aç",
        'report_issue': "GitHub-da problem bildir",
        'updates': "Yeniləmələr",
        'data': "Məlumatlar",
        'help': "Kömək",
        'about': "Haqqında",
        'now': "İndi",
        'next_program': "Növbəti",
        'no_program': "Proqram yoxdur",
        'select_playlist': "Pleylist seçin · .m3u/.m3u8 faylını buraya atın",
        'add_playlist_title': "Pleylist əlavə et",
        'xtream_codes': "Xtream Kodları",
        'channel': "Kanal",
        'buffer_label': "Bufer (şəbəkə keşi):",
        'default_volume': "Varsayılan səs:",
        'color_theme': "Rəng teması:",
        'audio_output': "Audio çıxışı:",
        'channel_sort': "Kanal sıralaması:",
        'autoplay_last': "Sonuncunu avto-başlat",
        'remember_fullscreen': "Tam ekranı yadda saxla",
        'always_on_top': "Həmişə üstdə",
        'hardware_decode': "Aparat dekodlama",
        'user_agent': "HTTP User-Agent:",
        'epg_sources': "EPG mənbələri:",
        'mini_player': "Mini pleyer",
        'installed': "Quraşdırılıb",
        'channels_count_short': "{n} kan.",
        'favorites_label': "Seçilmişlər",
        'recent_label': "Son",
        'tv_guide_label': "TV proqramı",
        'channels_label': "Kanallar",
        'playlists_label': "Pleylistlər",
        'search_channels': "Kanal axtar...",
        'mute': "Səssiz",
        'channels_in_list': "{n} kanal",
        'paste_url': "Pleylist URL-ni yapışdır",
        'name_optional': "Ad (məcburi deyil)",
        'updates_check_in_progress': "Yoxlanılır…",
        'on_latest_version': "Sizdə ən son versiyadır",
        'new_build_available': "Build {build} mövcuddur",
        'download_install': "Yükləyib quraşdırmaq?",
        'update_no_internet': "GitHub-a çatmaq mümkün olmadı",
        'restart_required': "Tətbiqi yenidən başlatmaq lazımdır",
    },
}

_CURRENT_LANG = 'en'  # Round 278: дефолт English (юзер)

# Round 242: расширенный список языков — порт Android LocaleHelper.
# Реальные переводы есть для ru/en/uk/az; для остальных fallback на ru.
SUPPORTED_LANGUAGES = [
    ('system', 'System'),
    ('ru', 'Русский'),
    ('en', 'English'),
    ('uk', 'Українська'),
    ('az', 'Azərbaycanca'),
    ('de', 'Deutsch'),
    ('fr', 'Français'),
    ('es', 'Español'),
    ('pl', 'Polski'),
    ('tr', 'Türkçe'),
    ('it', 'Italiano'),
    ('zh', '中文'),
    ('ar', 'العربية'),
    ('pt', 'Português'),
]


def _resolve_system_lang():
    """Round 278: дефолт English (а не ru)."""
    try:
        import locale as _locale
        code = (_locale.getdefaultlocale()[0] or "")[:2].lower()
        return code if code in TRANSLATIONS else 'en'
    except Exception:
        return 'en'


def set_ui_language(lang: str):
    """Round 242/278: 'system' резолвится в системную локаль; для языков
    без перевода — fallback на en (был ru)."""
    global _CURRENT_LANG
    if lang == 'system':
        _CURRENT_LANG = _resolve_system_lang()
    elif lang in TRANSLATIONS:
        _CURRENT_LANG = lang
    else:
        _CURRENT_LANG = 'en'


def t(key: str, **kwargs) -> str:
    """Lookup a translation. Falls back to en (Round 278: было ru), затем
    к самому ключу."""
    table = TRANSLATIONS.get(_CURRENT_LANG) or TRANSLATIONS.get('en') or TRANSLATIONS.get('ru')
    s = table.get(key) if table else None
    if not s:
        s = (TRANSLATIONS.get('en') or TRANSLATIONS.get('ru') or {}).get(key) or key
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

# Round 247: цветовые темы — порт Android Round 211 themes.xml.
# Меняют только primary / primary_dark / secondary; фон / карточки /
# текст остаются для консистентности тёмной темы.
THEME_PALETTES = {
    # Round 280: новый дефолт — бирюзово-голубая палитра как у референса
    # (zedom-стиль). Юзер прислал скриншоты, попросил применить.
    'default': ('#00C8E6', '#0099B3', '#26D4F5'),  # бирюзово-голубой
    'purple':  ('#7C6CF7', '#5A4DC5', '#4ECDC4'),  # старый фирменный
    'blue':    ('#2196F3', '#1976D2', '#03DAC5'),
    'green':   ('#4CAF50', '#388E3C', '#00BCD4'),
    'orange':  ('#FF9800', '#F57C00', '#FFB74D'),
    'red':     ('#F44336', '#D32F2F', '#FF7043'),
}


def apply_theme(theme_code):
    """Round 247: меняет COLORS['primary'/'primary_dark'/'secondary']
    и пересобирает глобальную STYLESHEET. После вызова приложение
    должно перепривязать app.setStyleSheet(STYLESHEET)."""
    global COLORS, STYLESHEET
    palette = THEME_PALETTES.get(theme_code) or THEME_PALETTES['default']
    COLORS['primary'], COLORS['primary_dark'], COLORS['secondary'] = palette
    # Перегенерируем STYLESHEET — это f-string, нужно собрать заново.
    STYLESHEET = _build_stylesheet()

def _build_stylesheet():
    """Round 247: STYLESHEET как функция, чтобы apply_theme мог
    перестроить её с новыми COLORS."""
    return f"""
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
    /* Round 278: видимый и контрастный курсор ввода. */
    selection-background-color: {COLORS['primary']};
    selection-color: white;
}}
QLineEdit:focus {{
    border: 2px solid {COLORS['primary']};
    background-color: #15152A;
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


STYLESHEET = _build_stylesheet()

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
        # Round 284: дефолт 6000мс — соответствует Android ExoPlayer
        # normal-режиму (DefaultLoadControl 6000/18000/200/1500).
        self.network_caching_ms = 6000     # VLC :network-caching
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
        # Round 246: позиция персистентных часов в плеере
        # (top_right / top_left / bottom_right / bottom_left / off).
        self.clock_position = "top_right"
        # Round 247: цветовая тема — default(purple) / blue / green /
        # orange / red. Порт Android Round 211 цветных тем.
        self.theme_color = "default"
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
        # Round 278: дефолт — English (юзер: «язык сделай по умолчанию
        # английский»). Если системная локаль = ru/uk/az — берём её.
        self.ui_language = sys_lang if sys_lang in ("ru", "en", "uk", "az") else "en"
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
                self.network_caching_ms = int(data.get('network_caching_ms', 6000))
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
                cp = data.get('clock_position', 'top_right')
                if cp in ('top_right', 'top_left', 'bottom_right',
                          'bottom_left', 'off'):
                    self.clock_position = cp
                tc = data.get('theme_color', 'default')
                if tc in THEME_PALETTES:
                    self.theme_color = tc
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
            'clock_position': self.clock_position,
            'theme_color': self.theme_color,
            'per_channel_state': self.per_channel_state,
            'ui_language': getattr(self, 'ui_language', 'ru'),
        }
        try:
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            log_error('Config.save', e)

    def save_async(self):
        """Round 260: фоновое сохранение конфига. Юзер: «программа
        сильно тормозит». При переключении каналов вызывалось save()
        ДВАЖДЫ синхронно (в _save_current_channel_state и в play_url),
        каждый раз — полный JSON dump 50+ KB на диск. На HDD/медленном
        SSD это давало ощутимый микро-фриз. Дамп идёт в daemon-нитке,
        UI не ждёт."""
        try:
            import threading as _th
            _th.Thread(target=self.save, daemon=True).start()
        except Exception as e:
            log_error('Config.save_async', e)
            try: self.save()
            except Exception: pass


class LoadPlaylistThread(QThread):
    """Background thread for loading playlists."""
    finished = pyqtSignal(object)
    error = pyqtSignal(str)

    def __init__(self, url):
        super().__init__()
        self.url = url

    def run(self):
        try:
            log_info('playlist', f"loading {self.url}")
            if os.path.isfile(self.url):
                result = load_playlist_file(self.url)
            else:
                result = fetch_playlist(self.url)
            chs = getattr(result, 'channels', []) or []
            groups = {c.group for c in chs if c.group}
            # Round 282: показываем сколько групп найдено + 3 примера,
            # чтобы быстро понять, парсятся ли категории. Юзер:
            # «категории из плейлиста ... не показываются».
            sample = sorted(groups)[:3]
            log_info('playlist',
                     f"ok channels={len(chs)} groups={len(groups)} "
                     f"sample={sample}")
            self.finished.emit(result)
        except Exception as e:
            log_error('LoadPlaylistThread', e, extra=f"url={self.url}")
            self.error.emit(str(e))


class UpdateCheckThread(QThread):
    """Queries GitHub Releases for the latest Windows build.

    Mirrors UpdateChecker.kt on Android: parses `win-v5.4-build<run>` tags
    and treats the build number as a versionCode.
    """
    finished = pyqtSignal(object)  # dict with keys: code, name, tag, url, notes — or None

    REPO = "donmax76/IpTv"  # Round 266: canonical case

    FAST_VERSION_JSON = (
        "https://raw.githubusercontent.com/donmax76/IpTv/main/"
        "windows-version.json")

    def _fetch(self, url, headers):
        """Round 264: тройной транспорт — requests → urllib → urllib без
        проверки SSL. PyInstaller-сборка без cacert.pem падала на TLS,
        unverified — последний шанс достучаться до github."""
        # 1) requests (несёт certifi)
        try:
            import requests as _rq
            resp = _rq.get(url, headers=headers, timeout=15)
            resp.raise_for_status()
            log_info('update', f"fetched via requests: {url}")
            return resp.content
        except Exception as e:
            log_warn('update', f"requests failed for {url}: {type(e).__name__}: {e}")
        # 2) urllib
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read()
            log_info('update', f"fetched via urllib: {url}")
            return raw
        except Exception as e:
            log_warn('update', f"urllib failed for {url}: {type(e).__name__}: {e}")
        # 3) urllib + unverified SSL
        try:
            import ssl as _ssl
            ctx = _ssl._create_unverified_context()
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15, context=ctx) as r:
                raw = r.read()
            log_warn('update', f"fetched via urllib (UNVERIFIED): {url}")
            return raw
        except Exception as e:
            log_error('update.all_failed', e, extra=f"url={url}")
            return None

    def _try_fast_path(self):
        """Round 264: Android-style fast path — читаем windows-version.json
        с raw.githubusercontent.com (CDN, ~100мс) вместо пагинации API."""
        try:
            log_info('update', f"fast path: {self.FAST_VERSION_JSON}")
            raw = self._fetch(self.FAST_VERSION_JSON,
                              {'User-Agent': 'TVViewer-Windows',
                               'Cache-Control': 'no-cache'})
            if not raw:
                return None
            obj = json.loads(raw)
            code = int(obj.get('versionCode', 0))
            if code <= 0:
                return None
            # Round 269: ПРЕДПОЧИТАЕМ zipUrl — onefile EXE падает у юзеров
            # с «Failed to load Python DLL python311.dll». ZIP надёжный
            # как Android APK.
            url = obj.get('zipUrl') or obj.get('exeUrl') or ''
            has_exe = bool(obj.get('zipUrl') or obj.get('exeUrl'))
            log_info('update',
                     f"fast path ok: build={code} tag={obj.get('tag','')} "
                     f"exe={has_exe}")
            return {
                'code': code,
                'name': obj.get('versionName', ''),
                'tag': obj.get('tag', f"win-v5.4-build{code}"),
                'url': url,
                'has_exe': has_exe,
                'notes': obj.get('releaseNotes', ''),
            }
        except Exception as e:
            log_warn('update', f"fast path parse failed: {type(e).__name__}: {e}")
            return None

    def run(self):
        # Round 260/263/264: fast path → API fallback. Юзер: «в андроид
        # версии обновление работает» — там тот же путь через
        # raw.githubusercontent.com/.../version.json.
        # 0) fast path
        fast = self._try_fast_path()
        if fast is not None:
            self.finished.emit(fast)
            return
        try:
            log_info('update', f"slow path: querying releases for {self.REPO}")
            # Round 266: пагинируем — у репо много Android v5.4-buildN
            # релизов в день, и наши win-v5.4-buildN иногда падают за
            # пределы первой страницы. Юзер видел «win-* releases: 0».
            # Сканируем до 5 страниц по 100 = 500 релизов (как Android
            # UpdateChecker.MAX_PAGES в build.yml). Останавливаемся
            # раньше если нашли — каждое следующее с меньшим build #.
            headers = {
                'Accept': 'application/vnd.github.v3+json',
                'User-Agent': 'TVViewer-Windows',
            }
            best = None
            for page in range(1, 6):
                api_url = (f"https://api.github.com/repos/{self.REPO}"
                           f"/releases?per_page=100&page={page}")
                raw = self._fetch(api_url, headers)
                if raw is None:
                    break
                data = json.loads(raw)
                if not isinstance(data, list) or not data:
                    break
                wins = [rel for rel in data
                        if isinstance(rel, dict)
                        and rel.get('tag_name', '').startswith('win-')]
                log_info('update',
                         f"page {page}: {len(data)} releases, {len(wins)} win-*")
                for rel in wins:
                    m = re.search(r'build(\d+)', rel.get('tag_name', ''))
                    if not m:
                        continue
                    code = int(m.group(1))
                    if best is None or code > best[0]:
                        best = (code, rel)
                # Если уже нашли — следующие страницы будут только старше.
                if best is not None and page >= 1:
                    break
            if best is None:
                log_warn('update', "no win-* release with buildN tag found "
                                   "after 5 pages")
                self.finished.emit(None)
                return
            code, rel = best
            assets = rel.get('assets', []) or []
            asset_names = [a.get('name', '') for a in assets]
            log_info('update', f"latest build={code} tag={rel.get('tag_name')} "
                               f"assets={asset_names}")
            # Round 269: ПРЕДПОЧИТАЕМ ZIP над EXE — onefile EXE падает
            # на юзерах с «Failed to load Python DLL python311.dll».
            zip_asset = next((a for a in assets
                              if a.get('name', '').lower().endswith('.zip')), None)
            exe_asset = next((a for a in assets
                              if a.get('name', '').lower().endswith('.exe')), None)
            url = ''
            if zip_asset is not None:
                url = zip_asset.get('browser_download_url') or ''
            elif exe_asset is not None:
                url = exe_asset.get('browser_download_url') or ''
            else:
                url = rel.get('html_url', '')
            log_info('update', f"chosen url={url}")
            self.finished.emit({
                'code': code,
                'name': rel.get('name', ''),
                'tag': rel.get('tag_name', ''),
                'url': url,
                'has_exe': zip_asset is not None or exe_asset is not None,
                'notes': rel.get('body', ''),
            })
        except Exception as e:
            log_error('UpdateCheckThread', e)
            self.finished.emit(None)


class DownloadUpdateThread(QThread):
    """Round 269: качаем ZIP (TVViewer-Windows-Portable.zip) и
    распаковываем поверх установленной папки. Раньше был --onefile EXE,
    но он падал на пользовательском Windows с «Failed to load Python DLL
    python311.dll» (известный баг PyInstaller --onefile). ZIP — это то,
    как обновляется Android (APK), и работает надёжно."""
    progress = pyqtSignal(int)  # 0..100
    finished = pyqtSignal(object)  # path or None
    error = pyqtSignal(str)

    def __init__(self, url: str, parent=None):
        super().__init__(parent)
        self.url = url

    def run(self):
        tmp_dir = tempfile.gettempdir()
        is_zip = self.url.lower().endswith('.zip')
        out_name = 'TVViewer.update.zip' if is_zip else 'TVViewer.update.exe'
        out_path = os.path.join(tmp_dir, out_name)
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
            log_info('update', f"downloaded {read} bytes → {out_path}")
            self.finished.emit(out_path)
        except Exception as e:
            log_error('DownloadUpdateThread', e, extra=f"url={self.url}")
            self.error.emit(str(e))
            try: os.remove(out_path)
            except Exception: pass


def _extract_zip_and_restart(zip_path: str):
    """Round 269/272: распаковать ZIP поверх install-папки и
    перезапуститься. Юзер: «идёт обновление оно после закрывается и
    открывается также старая версия» — значит распаковка падала из-за
    блокировки TVViewer.exe (файл-handle ещё держится).

    Round 272 укрепляет скрипт:
      • Wait-Process по PID родителя ДО распаковки (не таймаут).
      • Expand-Archive -Force — устойчивее .NET ExtractToDirectory на
        залоченных файлах, плюс при ошибке пробует .NET fallback.
      • Лог в %TEMP%\\tvviewer_update.log с уровнями каждой операции.
      • Hard-exit процесса (os._exit(0)) перед запуском VBS — снимает
        файл-блокировку с TVViewer.exe мгновенно.
    """
    if not getattr(sys, 'frozen', False):
        return False
    current_exe = sys.executable
    install_dir = os.path.dirname(current_exe)
    if not current_exe.lower().endswith('.exe'):
        return False
    try:
        tmp = tempfile.gettempdir()
        log_path = os.path.join(tmp, 'tvviewer_update.log')
        own_pid = os.getpid()
        # PowerShell: ждём смерти родителя по PID, потом Expand-Archive
        # с -Force; при ошибке — fallback на .NET ZipFile.
        ps_cmd = (
            "$ErrorActionPreference='Continue'; "
            f"$pid_parent = {own_pid}; "
            "try { Wait-Process -Id $pid_parent -Timeout 30 } catch {}; "
            "Start-Sleep -Milliseconds 500; "
            f"$zip = '{zip_path}'; "
            f"$dst = '{install_dir}'; "
            f"$log = '{log_path}'; "
            "Add-Content $log \"[ps] starting extract zip=$zip dst=$dst\"; "
            "$ok = $false; "
            "try { "
            "  Expand-Archive -Path $zip -DestinationPath $dst -Force; "
            "  $ok = $true; "
            "  Add-Content $log '[ps] Expand-Archive OK' "
            "} catch { "
            "  Add-Content $log \"[ps] Expand-Archive failed: $_\" "
            "}; "
            "if (-not $ok) { "
            "  try { "
            "    Add-Type -AssemblyName System.IO.Compression.FileSystem; "
            "    [System.IO.Compression.ZipFile]::ExtractToDirectory("
            "      $zip, $dst, $true); "
            "    $ok = $true; "
            "    Add-Content $log '[ps] .NET extract OK' "
            "  } catch { "
            "    Add-Content $log \"[ps] .NET extract failed: $_\" "
            "  } "
            "}; "
            "Remove-Item $zip -ErrorAction SilentlyContinue; "
            "Add-Content $log \"[ps] done ok=$ok\""
        )
        bat = (
            "@echo off\r\n"
            f'echo [%date% %time%] update start, parent pid={own_pid} '
            f'> "{log_path}"\r\n'
            f'powershell.exe -NoProfile -ExecutionPolicy Bypass '
            f'-WindowStyle Hidden -Command "{ps_cmd}" '
            f'>> "{log_path}" 2>&1\r\n'
            f'echo [%date% %time%] bat exit: %errorlevel% >> "{log_path}"\r\n'
        )
        bat_path = os.path.join(tmp, 'tvviewer_update.bat')
        with open(bat_path, 'w', encoding='ascii') as f:
            f.write(bat)
        # VBS ждёт BAT, потом стартует НОВЫЙ TVViewer.exe из install_dir
        # (с явным указанием working directory — иначе start запускал бы
        # из %TEMP% и относительные пути в коде ломались бы).
        vbs = (
            'Set sh = CreateObject("WScript.Shell")\r\n'
            f'sh.CurrentDirectory = "{install_dir}"\r\n'
            f'sh.Run "cmd /c ""{bat_path}""", 0, True\r\n'
            f'sh.Run """{current_exe}""", 1, False\r\n'
            'Set fso = CreateObject("Scripting.FileSystemObject")\r\n'
            'On Error Resume Next\r\n'
            f'fso.DeleteFile "{bat_path}"\r\n'
            'fso.DeleteFile WScript.ScriptFullName\r\n'
        )
        vbs_path = os.path.join(tmp, 'tvviewer_update.vbs')
        with open(vbs_path, 'w', encoding='ascii') as f:
            f.write(vbs)
        flags = 0
        if hasattr(subprocess, 'DETACHED_PROCESS'):
            flags |= subprocess.DETACHED_PROCESS
        if hasattr(subprocess, 'CREATE_NO_WINDOW'):
            flags |= subprocess.CREATE_NO_WINDOW
        subprocess.Popen(['wscript.exe', vbs_path], creationflags=flags,
                         close_fds=True)
        log_info('update',
                 f"ZIP extract script launched, install_dir={install_dir}, "
                 f"log at {log_path}, parent_pid={own_pid}")
        return True
    except Exception as e:
        log_error('_extract_zip_and_restart', e)
        return False


def _swap_self_and_restart(new_exe_path: str):
    """Swap the running .exe with `new_exe_path` and restart.

    Only works for a frozen PyInstaller build. Returns True if a swap
    script was launched (caller should quit immediately afterwards).

    Round 269: юзер: «зачем после обновления выходит окно терминала
    где происходит пинг а потом закрывается и не открывается сама
    программа». BAT с `ping` показывал cmd-окно несмотря на
    CREATE_NO_WINDOW, и `start "" "{path}"` иногда не запускал EXE
    обратно. Переписали через VBScript-обёртку:
      • VBScript запускается через wscript.exe — никогда не показывает
        окно.
      • Внутри VBS вызываем cmd .bat с WindowStyle=0 (Hidden).
      • Вместо `ping` используем `timeout /t 2 /nobreak`.
      • `start` заменён на прямой запуск через CreateObject.Run,
        чтобы избежать ошибок с пробелами в путях.
    """
    if not getattr(sys, 'frozen', False):
        return False
    current = sys.executable
    if not current.lower().endswith('.exe'):
        return False
    try:
        tmp = tempfile.gettempdir()
        log_path = os.path.join(tmp, 'tvviewer_update.log')
        # BAT — основная работа: подождать, заменить, лог.
        bat = (
            "@echo off\r\n"
            f'echo [%date% %time%] update start > "{log_path}"\r\n'
            "timeout /t 2 /nobreak >nul 2>&1\r\n"
            f'move /Y "{new_exe_path}" "{current}" >> "{log_path}" 2>&1\r\n'
            f'echo move exit: %errorlevel% >> "{log_path}"\r\n'
        )
        bat_path = os.path.join(tmp, 'tvviewer_update.bat')
        with open(bat_path, 'w', encoding='ascii') as f:
            f.write(bat)
        # VBS — обёртка для невидимого запуска BAT, потом — новый EXE.
        # WScript.Shell.Run window style 0 = Hidden, ждём окончания BAT,
        # затем запускаем заменённый EXE и удаляем себя.
        vbs = (
            'Set sh = CreateObject("WScript.Shell")\r\n'
            f'sh.Run "cmd /c ""{bat_path}""", 0, True\r\n'
            f'sh.Run """{current}""", 1, False\r\n'
            'Set fso = CreateObject("Scripting.FileSystemObject")\r\n'
            'On Error Resume Next\r\n'
            f'fso.DeleteFile "{bat_path}"\r\n'
            'fso.DeleteFile WScript.ScriptFullName\r\n'
        )
        vbs_path = os.path.join(tmp, 'tvviewer_update.vbs')
        with open(vbs_path, 'w', encoding='ascii') as f:
            f.write(vbs)
        # wscript.exe сам по себе не показывает окно для VBS-скриптов.
        flags = 0
        if hasattr(subprocess, 'DETACHED_PROCESS'):
            flags |= subprocess.DETACHED_PROCESS
        if hasattr(subprocess, 'CREATE_NO_WINDOW'):
            flags |= subprocess.CREATE_NO_WINDOW
        subprocess.Popen(['wscript.exe', vbs_path], creationflags=flags,
                         close_fds=True)
        log_info('update', f"swap script launched, log at {log_path}")
        return True
    except Exception as e:
        log_error('_swap_self_and_restart', e)
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
                log_error('epg_source', e, extra=f"url={url}")
                continue
        trace("EPG", f"fetchAll done: merged={len(merged)} channels")
        log_info('epg', f"merged channels={len(merged)} from {len(self.urls)} sources")
        self.finished.emit(merged)


class _LogoFetcher(QThread):
    """Round 265: загрузчик одной логотипной картинки.

    Юзер: «в андроид версии эта проблема решена с лого». Android Coil
    + OkHttp бандлят CA-сертификаты, поэтому HTTPS у них работает
    из коробки. У нас в PyInstaller-сборке QNetworkAccessManager без
    cacert.pem — HTTPS молча падал и папка tvviewer_logos оставалась
    пустая. Делаем тройной транспорт:
      1) requests (несёт certifi внутри пакета)
      2) urllib с системным SSL
      3) urllib без проверки SSL (последний шанс)
    """
    done = pyqtSignal(str, bytes, str)  # url, data, error

    def __init__(self, url, parent=None):
        super().__init__(parent)
        self._url = url

    def run(self):
        err = ""
        data = b""
        headers = {'User-Agent': 'TVViewer/Windows'}
        # Round 269: ОДНА попытка urllib с 2с timeout. Юзер: «зависании
        # происходят во всех окнах везде». На плейлисте 3639 каналов с
        # битыми/недоступными лого 3 транспорта × 4с = 12с на одну ссылку
        # × 3000+ = постоянное GIL-молотилово фоновых потоков. requests
        # тяжёлая (Session, certifi, urllib3 — каждый раз пересоздаём).
        # Возвращаемся к минимуму: один urllib-вызов, 2 сек, дальше next.
        try:
            req = urllib.request.Request(self._url, headers=headers)
            with urllib.request.urlopen(req, timeout=2) as r:
                data = r.read()
        except Exception as e:
            err = f"{type(e).__name__}"
        try:
            self.done.emit(self._url, data, err)
        except Exception as e:
            log_error('_LogoFetcher.emit', e)


class LogoCache(QObject):
    """Async logo loader with disk cache, shared across pages.

    Round 265: QNAM выкинут — у него в PyInstaller-сборке без cacert.pem
    HTTPS отваливался молча (QNetworkReply.error()==NoError, body пустой,
    папка tvviewer_logos оставалась пустая). Юзер: «лого ни у одного
    канала нету и папка tvviewer_logos пустая». Перенесли на urllib в
    QThread — точно так же как _PhotoFetcher в Round 253.
    """
    logo_ready = pyqtSignal()

    # Round 269: 6 → 2. Юзер видит фризы везде. На плейлисте 3639
    # каналов 6 одновременных HTTP-потоков + GIL = постоянная фоновая
    # нагрузка. Двух воркеров достаточно — лого подгружаются плавно
    # без блокировки UI.
    MAX_CONCURRENT = 2
    MAX_ICONS_IN_MEM = 2000

    def __init__(self, cache_dir: str, parent=None):
        super().__init__(parent)
        self.cache_dir = cache_dir
        try:
            os.makedirs(cache_dir, exist_ok=True)
        except Exception as e:
            log_error('LogoCache.makedirs', e, extra=cache_dir)
        self.icons: dict = {}
        self.missing: set = set()
        self._inflight: set = set()
        self._queue: list = []
        self._workers: list = []  # QThread refs чтобы GC не убил
        # Round 269: circuit breaker. Юзер: «зависании происходят во
        # всех окнах везде». На плейлисте с битыми лого 6 воркеров
        # молотили без остановки. После 50 подряд неудач — пауза 60с.
        self._consecutive_fail = 0
        self._paused_until = 0.0
        self._emit_timer = QTimer(self)
        self._emit_timer.setSingleShot(True)
        self._emit_timer.setInterval(400)
        self._emit_timer.timeout.connect(self.logo_ready.emit)
        log_info('logo', f"cache dir = {cache_dir}")

    def _path(self, url: str) -> str:
        return os.path.join(
            self.cache_dir,
            hashlib.sha1(url.encode('utf-8', 'ignore')).hexdigest()[:16] + '.png')

    def get(self, url: str):
        if not url or url in self.missing:
            return None
        # Round 268: ранний отсев невалидных URL — иначе фетчер делает
        # 3 транспортных попытки × 4 сек = 12 сек впустую на каждой
        # битой ссылке, и 6 воркеров постоянно сидят на дохлых URL,
        # съедая CPU/диск-IO.
        u = url.strip()
        if not (u.startswith('http://') or u.startswith('https://')):
            self.missing.add(url)
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
        # Round 269: circuit breaker — пока пауза, не спавним новых
        # воркеров. Юзер не должен страдать из-за плейлиста с дохлыми
        # лого-ссылками.
        import time as _t
        if _t.monotonic() < self._paused_until:
            return
        while self._queue and len(self._inflight) < self.MAX_CONCURRENT:
            url = self._queue.pop(0)
            if url in self._inflight or url in self.icons or url in self.missing:
                continue
            self._inflight.add(url)
            try:
                w = _LogoFetcher(url, self)
                w.done.connect(self._on_done)
                # Сохраняем ref чтобы Python не собрал; чистим в finished.
                self._workers.append(w)
                w.finished.connect(lambda w=w: self._workers.remove(w)
                                   if w in self._workers else None)
                w.start()
            except Exception as e:
                log_error('LogoCache._pump', e, extra=url)
                self._inflight.discard(url)
                self.missing.add(url)

    def _on_done(self, url, data, err):
        import time as _t
        self._inflight.discard(url)
        try:
            if err:
                self.missing.add(url)
                self._consecutive_fail += 1
                if self._consecutive_fail >= 50 and self._paused_until == 0:
                    # Round 269: circuit breaker — пауза 60 сек после
                    # 50 подряд неудач. Не молотим бесконечно.
                    self._paused_until = _t.monotonic() + 60.0
                    log_warn('logo',
                             "50 consecutive failures, pausing 60s")
                return
            if not data:
                self.missing.add(url)
                self._consecutive_fail += 1
                return
            pm = QPixmap()
            if not pm.loadFromData(data):
                self.missing.add(url)
                self._consecutive_fail += 1
                return
            # успех — сбрасываем счётчик и снимаем паузу.
            self._consecutive_fail = 0
            self._paused_until = 0.0
            if pm.width() > 128 or pm.height() > 128:
                pm = pm.scaled(128, 128, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            try:
                with open(self._path(url), 'wb') as f:
                    f.write(data)
            except Exception as e:
                log_error('logo.write_disk', e, extra=url)
            if len(self.icons) < self.MAX_ICONS_IN_MEM:
                self.icons[url] = QIcon(pm)
            if not self._emit_timer.isActive():
                self._emit_timer.start()
        finally:
            self._pump()


# ============================================================
# Round 253: загрузчик фоновой картинки HomePage в отдельном QThread.
# QNetworkAccessManager в PyInstaller-сборке часто не имеет OpenSSL,
# и picsum.photos (HTTPS-only) молча возвращал NoError + пустой
# body. urllib работает через Python ssl, бандлится PyInstaller'ом
# из коробки.
# ============================================================
class _PhotoFetcher(QThread):
    image_ready = pyqtSignal(bytes)

    def __init__(self, url, parent=None):
        super().__init__(parent)
        self._url = url

    def run(self):
        data = b""
        try:
            req = urllib.request.Request(
                self._url,
                headers={'User-Agent': 'TVViewer/Windows'})
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = resp.read()
        except Exception as e:
            log_error('PhotoFetcher', e, extra=f"url={self._url}")
            data = b""
        try:
            self.image_ready.emit(data)
        except Exception as e:
            log_error('PhotoFetcher.emit', e)


# ============================================================
# Round 241 (Windows): Home Page — порт Android HomeFragment.
# Большие кнопки «Прямой эфир» и «Плейлисты» поверх циклящегося
# фотофона с picsum.photos.
# ============================================================
class HomePage(QWidget):
    live_requested = pyqtSignal()        # хочу плеер с последним плейлистом
    playlists_requested = pyqtSignal()    # хочу вкладку плейлистов

    PHOTO_URL_BASE = "https://picsum.photos/1280/720?random="
    SLIDE_INTERVAL_MS = 30_000
    FADE_DURATION_MS = 1_400

    def __init__(self, config):
        super().__init__()
        self.config = config
        self._photo_seed = int(time.time())
        self._bg_pix_a = None
        self._bg_pix_b = None
        self._showing_a = True
        self._fader = None  # текущая fade-анимация
        # Фоновые QLabel'ы — кросс-фейд между двумя картинками.
        self.bg_a = QLabel(self)
        self.bg_b = QLabel(self)
        for w in (self.bg_a, self.bg_b):
            w.setScaledContents(True)
            # Round 249: прозрачный фон у QLabel'ов чтобы пока фото не
            # загрузилось был виден gradient из paintEvent (а не сплошной
            # тёмный квадрат).
            w.setStyleSheet("background: transparent;")
        self.bg_b.hide()
        # Тёмный overlay поверх фото для читаемости текста.
        self.dim = QLabel(self)
        self.dim.setStyleSheet("background-color: rgba(15, 15, 26, 130);")
        self._build_ui()
        # Загрузчик картинок в отдельном потоке.
        self._net = QNetworkAccessManager(self)
        self._net.finished.connect(self._on_photo_loaded)
        self._fetch_photo(self._photo_seed)
        # Таймер cycle.
        self._cycle_timer = QTimer(self)
        self._cycle_timer.setInterval(self.SLIDE_INTERVAL_MS)
        self._cycle_timer.timeout.connect(self._cycle)
        self._cycle_timer.start()

    def paintEvent(self, event):
        # Round 249: gradient-фон как fallback пока picsum.photos не
        # загрузилось (или офлайн). Фирменная палитра.
        try:
            from PyQt5.QtGui import QLinearGradient
            painter = QPainter(self)
            grad = QLinearGradient(0, 0, self.width(), self.height())
            grad.setColorAt(0.0, QColor("#0F0F1A"))
            grad.setColorAt(0.5, QColor("#1E1E3A"))
            grad.setColorAt(1.0, QColor("#0F0F1A"))
            painter.fillRect(self.rect(), QBrush(grad))
            painter.end()
        except Exception:
            pass
        super().paintEvent(event)

    def _build_ui(self):
        # Контентный слой — поверх фонов. ВАЖНО: прозрачный фон, иначе
        # глобальный QSS QWidget{background-color} закрасит фото.
        self.content = QWidget(self)
        self.content.setAttribute(Qt.WA_TranslucentBackground, True)
        self.content.setStyleSheet("background: transparent;")
        col = QVBoxLayout(self.content)
        col.setContentsMargins(60, 60, 60, 60)
        col.setSpacing(20)
        col.addStretch()

        # Round 271: добавили лого над заголовком на HomePage.
        try:
            _ico = _app_icon_path()
            if _ico:
                src = QPixmap(_ico)
                if not src.isNull():
                    pm = src.scaled(96, 96, Qt.KeepAspectRatio,
                                    Qt.SmoothTransformation)
                    logo_lbl = QLabel()
                    logo_lbl.setPixmap(pm)
                    logo_lbl.setStyleSheet("background: transparent;")
                    col.addWidget(logo_lbl)
        except Exception:
            pass

        title = QLabel(t('app_name'))
        title.setStyleSheet(
            "color: white; font-size: 48px; font-weight: bold;"
            " background: transparent;")
        col.addWidget(title)

        self.subtitle = QLabel("TVViewer")
        self.subtitle.setStyleSheet(
            "color: #00CEC9; font-size: 18px; background: transparent;")
        col.addWidget(self.subtitle)

        col.addSpacing(40)

        # Большая фиолетовая кнопка «Прямой эфир».
        self.btn_live = QPushButton("▶  " + (t('play') if t('play') != 'play' else "Прямой эфир"))
        self.btn_live.setMinimumHeight(70)
        self.btn_live.setMinimumWidth(360)
        self.btn_live.setStyleSheet(
            "QPushButton { background-color: #7C6CF7; color: white;"
            " border-radius: 14px; font-size: 22px; font-weight: bold;"
            " padding: 12px 24px; }"
            "QPushButton:hover { background-color: #5A4DC5; }"
            "QPushButton:pressed { background-color: #4A3DB5; }")
        self.btn_live.clicked.connect(self.live_requested.emit)
        col.addWidget(self.btn_live, alignment=Qt.AlignLeft)

        # Вторая кнопка — «Плейлисты».
        self.btn_playlists = QPushButton("📋  " + t('playlists'))
        self.btn_playlists.setMinimumHeight(60)
        self.btn_playlists.setMinimumWidth(360)
        self.btn_playlists.setStyleSheet(
            "QPushButton { background-color: rgba(30, 30, 58, 220);"
            " color: white; border: 2px solid #7C6CF7; border-radius: 12px;"
            " font-size: 18px; padding: 10px 20px; }"
            "QPushButton:hover { background-color: rgba(60, 60, 92, 220); }")
        self.btn_playlists.clicked.connect(self.playlists_requested.emit)
        col.addWidget(self.btn_playlists, alignment=Qt.AlignLeft)

        col.addSpacing(20)

        # Текущий плейлист — справочная подпись.
        self.default_label = QLabel("")
        self.default_label.setStyleSheet(
            "color: rgba(255,255,255,180); font-size: 13px;"
            " background: transparent;")
        col.addWidget(self.default_label)

        col.addStretch()
        self._refresh_default_label()

    def _refresh_default_label(self):
        name = getattr(self.config, 'last_playlist_name', '') or ''
        if name:
            self.default_label.setText(f"📂  {name}")
        else:
            self.default_label.setText("")

    def showEvent(self, event):
        super().showEvent(event)
        self._refresh_default_label()
        # Round 253: пере-запрашиваем фон если предыдущая попытка
        # не сработала (фотки пустые).
        try:
            if (self.bg_a.pixmap() is None or self.bg_a.pixmap().isNull()) \
               and (self.bg_b.pixmap() is None or self.bg_b.pixmap().isNull()):
                self._photo_seed += 1
                self._fetch_photo(self._photo_seed)
        except Exception:
            pass
        if hasattr(self, '_cycle_timer'):
            self._cycle_timer.start()

    def hideEvent(self, event):
        super().hideEvent(event)
        if hasattr(self, '_cycle_timer'):
            self._cycle_timer.stop()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.bg_a.setGeometry(self.rect())
        self.bg_b.setGeometry(self.rect())
        self.dim.setGeometry(self.rect())
        if hasattr(self, 'content'):
            self.content.setGeometry(self.rect())
        for w in (self.bg_a, self.bg_b, self.dim):
            w.lower()
        self.dim.raise_()
        if hasattr(self, 'content'):
            self.content.raise_()

    def _fetch_photo(self, seed):
        """Round 253: качаем картинку в отдельном QThread через urllib —
        PyInstaller-бандл часто без OpenSSL для QNetworkAccessManager,
        и picsum.photos HTTPS-запросы заваливались молча. urllib
        работает напрямую через ssl-модуль Python."""
        try:
            url = f"{self.PHOTO_URL_BASE}{seed}"
            t = _PhotoFetcher(url, self)
            t.image_ready.connect(self._on_photo_bytes)
            t.start()
            # Держим ref чтобы GC не съел до finished.
            if not hasattr(self, '_active_fetchers'):
                self._active_fetchers = []
            self._active_fetchers.append(t)
            t.finished.connect(
                lambda t=t: self._active_fetchers.remove(t)
                if t in self._active_fetchers else None)
        except Exception:
            pass

    def _on_photo_bytes(self, data):
        try:
            if not data:
                return
            pix = QPixmap()
            if not pix.loadFromData(data):
                return
            target = self.bg_b if self._showing_a else self.bg_a
            target.setPixmap(pix)
            target.show()
            self._fade_swap(target)
        except Exception:
            pass

    def _on_photo_loaded(self, reply):
        # Legacy QNAM-обработчик — оставлен для совместимости если
        # кто-то всё ещё дёргает старым путём.
        try:
            if reply.error() != QNetworkReply.NoError:
                reply.deleteLater()
                return
            data = bytes(reply.readAll())
            reply.deleteLater()
            self._on_photo_bytes(data)
        except Exception:
            pass

    def _fade_swap(self, new_target):
        """Кросс-фейд между bg_a и bg_b."""
        try:
            effect = QGraphicsOpacityEffect(new_target)
            effect.setOpacity(0.0)
            new_target.setGraphicsEffect(effect)
            anim = QPropertyAnimation(effect, b"opacity", self)
            anim.setDuration(self.FADE_DURATION_MS)
            anim.setStartValue(0.0)
            anim.setEndValue(1.0)
            anim.setEasingCurve(QEasingCurve.InOutQuad)

            def _on_finish():
                old = self.bg_a if new_target is self.bg_b else self.bg_b
                old.hide()
                self._showing_a = (new_target is self.bg_a)
                try:
                    new_target.setGraphicsEffect(None)
                except Exception:
                    pass

            anim.finished.connect(_on_finish)
            anim.start(QPropertyAnimation.DeleteWhenStopped)
            self._fader = anim
        except Exception:
            # Fallback без анимации: показать сразу.
            new_target.show()
            (self.bg_a if new_target is self.bg_b else self.bg_b).hide()
            self._showing_a = (new_target is self.bg_a)

    def _cycle(self):
        self._photo_seed += 1
        self._fetch_photo(self._photo_seed)


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
            self.config.save_async()
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

        self._title = QLabel(t('app_name'))
        self._title.setFont(QFont('Segoe UI', 24, QFont.Bold))
        layout.addWidget(self._title)

        self._subtitle = QLabel(t('select_playlist'))
        self._subtitle.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 14px;")
        layout.addWidget(self._subtitle)
        layout.addSpacing(12)

        # Built-in playlists — four comboboxes (matches Android Round 220).
        self._builtin_label = QLabel(t('built_in_playlists'))
        self._builtin_label.setStyleSheet(f"color: {COLORS['text_primary']}; font-size: 14px; font-weight: bold;")
        layout.addWidget(self._builtin_label)

        self._builtin_combos = []
        self._builtin_cat_labels = []
        # Round 265: ключи переводов для категорий вместо хардкода.
        cat_t_keys = ['by_language', 'by_category', 'by_country', 'by_region']
        grid = QHBoxLayout()
        col_left = QVBoxLayout()
        col_right = QVBoxLayout()
        for i, (_cat_label_en, items) in enumerate(self.BUILTIN_CATEGORIES):
            cat_key = cat_t_keys[i] if i < len(cat_t_keys) else 'choose'
            lbl = QLabel(t(cat_key))
            lbl.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 12px;")
            lbl.setProperty('_t_key', cat_key)
            self._builtin_cat_labels.append(lbl)
            combo = QComboBox()
            combo.addItem(t('choose'), None)
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

        self._custom_label = QLabel(t('my_playlists'))
        self._custom_label.setStyleSheet(f"color: {COLORS['text_primary']}; font-size: 14px; font-weight: bold;")
        layout.addWidget(self._custom_label)

        self.playlist_list = QListWidget()
        self.playlist_list.setSpacing(4)
        self.playlist_list.itemDoubleClicked.connect(self.on_playlist_click)
        layout.addWidget(self.playlist_list)

        btn_row = QHBoxLayout()
        self._btn_add_url = QPushButton(t('add_url'))
        self._btn_add_url.setObjectName("primaryBtn")
        self._btn_add_url.clicked.connect(self.add_playlist_url)
        btn_row.addWidget(self._btn_add_url)

        self._btn_add_file = QPushButton(t('open_file'))
        self._btn_add_file.clicked.connect(self.add_playlist_file)
        btn_row.addWidget(self._btn_add_file)

        # Round 242: Xtream-codes login.
        self._btn_add_xtream = QPushButton(t('xtream'))
        self._btn_add_xtream.clicked.connect(self.add_playlist_xtream)
        btn_row.addWidget(self._btn_add_xtream)

        # Round 247: вставка URL из буфера обмена — порт Android
        # «paste_url_from_clipboard» из PlaylistsFragment.
        self._btn_paste = QPushButton(t('from_clipboard'))
        self._btn_paste.clicked.connect(self.add_playlist_from_clipboard)
        btn_row.addWidget(self._btn_paste)

        btn_row.addStretch()

        self._btn_remove = QPushButton(t('remove'))
        self._btn_remove.setStyleSheet(f"color: {COLORS['error']};")
        self._btn_remove.clicked.connect(self.remove_playlist)
        btn_row.addWidget(self._btn_remove)

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

    def retranslate_ui(self):
        """Round 265: применяем переводы ко всем сохранённым QLabel/QPushButton."""
        try:
            if hasattr(self, '_title'):
                self._title.setText(t('app_name'))
            if hasattr(self, '_subtitle'):
                self._subtitle.setText(t('select_playlist'))
            if hasattr(self, '_builtin_label'):
                self._builtin_label.setText(t('built_in_playlists'))
            if hasattr(self, '_custom_label'):
                self._custom_label.setText(t('my_playlists'))
            for lbl in getattr(self, '_builtin_cat_labels', []):
                key = lbl.property('_t_key')
                if key:
                    lbl.setText(t(key))
            # Combo «— Choose —» — индекс 0.
            for combo in getattr(self, '_builtin_combos', []):
                if combo.count() > 0:
                    combo.setItemText(0, t('choose'))
            if hasattr(self, '_btn_add_url'):
                self._btn_add_url.setText(t('add_url'))
            if hasattr(self, '_btn_add_file'):
                self._btn_add_file.setText(t('open_file'))
            if hasattr(self, '_btn_add_xtream'):
                self._btn_add_xtream.setText(t('xtream'))
            if hasattr(self, '_btn_paste'):
                self._btn_paste.setText(t('from_clipboard'))
            if hasattr(self, '_btn_remove'):
                self._btn_remove.setText(t('remove'))
        except Exception as e:
            log_error('PlaylistsPage.retranslate_ui', e)

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
                self.config.save_async()
                self.refresh_list()

    def add_playlist_xtream(self):
        """Round 242: добавить плейлист по Xtream-codes креденшелам.
        Юзер вводит сервер/login/пароль — мы аутентифицируемся,
        и если auth OK — собираем M3U-URL и добавляем."""
        dlg = QDialog(self)
        dlg.setWindowTitle("Xtream Codes")
        dlg.setStyleSheet(STYLESHEET)
        dlg.setMinimumWidth(450)
        form = QFormLayout(dlg)
        name_edit = QLineEdit()
        name_edit.setPlaceholderText("My Xtream")
        server_edit = QLineEdit()
        server_edit.setPlaceholderText("http://example.com:8080")
        user_edit = QLineEdit()
        user_edit.setPlaceholderText("login")
        pass_edit = QLineEdit()
        pass_edit.setEchoMode(QLineEdit.Password)
        form.addRow("Name:", name_edit)
        form.addRow("Server:", server_edit)
        form.addRow("Username:", user_edit)
        form.addRow("Password:", pass_edit)
        status = QLabel("")
        status.setStyleSheet(f"color: {COLORS['text_secondary']};")
        form.addRow(status)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        form.addRow(btns)

        def _try_login():
            srv = server_edit.text().strip()
            usr = user_edit.text().strip()
            pwd = pass_edit.text()
            name = name_edit.text().strip() or "Xtream"
            if not (srv and usr and pwd):
                status.setText("Заполните сервер, логин и пароль.")
                return
            status.setText("Проверяю…")
            QApplication.processEvents()
            info = XtreamApi.authenticate(srv, usr, pwd)
            if info is None:
                status.setText("Не удалось войти. Проверьте данные.")
                return
            url = XtreamApi.build_m3u_url(srv, usr, pwd)
            self.config.playlists.append({'name': name, 'url': url})
            self.config.save_async()
            self.refresh_list()
            dlg.accept()

        btns.accepted.connect(_try_login)
        btns.rejected.connect(dlg.reject)
        dlg.exec_()

    def add_playlist_from_clipboard(self):
        """Round 247: достаём URL из буфера обмена. Поддерживает
        прямой ввод https://...m3u/m3u8 и текст с URL внутри."""
        try:
            txt = (QApplication.clipboard().text() or "").strip()
        except Exception:
            txt = ""
        if not txt:
            QMessageBox.information(self, "Из буфера",
                "Буфер обмена пуст.")
            return
        # Сначала ищем явный m3u/m3u8 URL.
        m = re.search(r'(?i)https?://\S+\.m3u8?\S*', txt)
        url = m.group(0) if m else (
            txt if (txt.lower().startswith('http://') or
                    txt.lower().startswith('https://')) else None)
        if not url:
            QMessageBox.information(self, "Из буфера",
                "В буфере нет ссылки на плейлист.")
            return
        # Извлекаем имя из URL.
        try:
            m2 = re.search(r'/([^/?#]+\.m3u8?)', url)
            name = (m2.group(1).rstrip('.m3u8').rstrip('.m3u') if m2
                    else f"Playlist {len(self.config.playlists) + 1}")
        except Exception:
            name = f"Playlist {len(self.config.playlists) + 1}"
        # Подтверждение и добавление.
        confirm = QMessageBox.question(
            self, "Добавить плейлист?",
            f"{name}\n{url}",
            QMessageBox.Yes | QMessageBox.No)
        if confirm == QMessageBox.Yes:
            self.config.playlists.append({'name': name, 'url': url})
            self.config.save_async()
            self.refresh_list()

    def add_playlist_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Open M3U Playlist", "",
            "Playlist files (*.m3u *.m3u8);;All files (*)")
        if path:
            name = os.path.splitext(os.path.basename(path))[0]
            self.config.playlists.append({'name': name, 'url': path})
            self.config.save_async()
            self.refresh_list()

    def remove_playlist(self):
        row = self.playlist_list.currentRow()
        if row >= 0:
            self.config.playlists.pop(row)
            self.config.save_async()
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
        self.search_edit.setPlaceholderText(t('search_channels'))
        self.search_edit.setClearButtonEnabled(True)  # Round 278
        self.search_edit.textChanged.connect(self._on_search_text)
        srow.addWidget(self.search_edit, 1)

        self.sort_combo = QComboBox()
        self.sort_combo.addItem("Sort: Default", "default")
        self.sort_combo.addItem("Sort: Name", "name")
        self.sort_combo.addItem("Sort: Number", "number")
        self.sort_combo.addItem("Sort: Group", "group")
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
        # Round 240: вернул стандартный плоский список для ChannelsPage —
        # делегат на больших плейлистах рисовался плохо, юзер видел
        # «нет списков каналов». Делегат остаётся только в плеере
        # (overlay), где помещается всего ~500 элементов.
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
        self.config.save_async()
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
        self.config.save_async()
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
            # Round 242: единая сортировка через sort_channels —
            # поддерживает default/name/group/number/quality.
            filtered = sort_channels(filtered, sort_mode)
        self.filtered = filtered

        # Round 277: ВСЯ filter_channels стала incremental. Раньше она
        # синхронно вставляла 3639 QListWidgetItems за один проход
        # (~10 сек на медленных машинах) — watchdog ловил это как
        # «main thread blocked 11.2s». Теперь:
        #   • первые 200 рисуются СРАЗУ (юзер видит верх списка);
        #   • остальные подсыпаются пачками по 100 через QTimer 30мс;
        #   • летеер-тайлы дорисовываются позже (Round 273).
        show_epg = len(filtered) <= 500
        self._filter_state = {
            'filtered': filtered,
            'show_epg': show_epg,
            'epg': epg,
            'favs': favs,
        }
        lst = self.channel_list
        lst.setUpdatesEnabled(False)
        try:
            lst.clear()
            first_batch = min(200, len(filtered))
            for i in range(first_batch):
                self._append_channel_item(i, filtered[i], show_epg, epg, favs,
                                          tile=True)
        finally:
            lst.setUpdatesEnabled(True)
        self.count_label.setText(f"{len(filtered)} channels")
        if len(filtered) > 200:
            self._chunk_idx = 200
            if not hasattr(self, '_chunk_timer'):
                self._chunk_timer = QTimer(self)
                self._chunk_timer.setInterval(30)
                self._chunk_timer.timeout.connect(self._fill_next_chunk)
            self._chunk_timer.start()

    def _append_channel_item(self, i, ch, show_epg, epg, favs, tile=False):
        try:
            ch_to_index = self.ch_to_index
            logo_cache = self.logo_cache
            lst = self.channel_list
            epg_text = ""
            if show_epg and epg:
                try:
                    now_prog, _ = get_now_next(epg, ch.tvg_id, ch.name)
                    if now_prog:
                        try:
                            tstart = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                            epg_text = f"  {tstart} {now_prog.title}"
                        except (OSError, ValueError):
                            pass
                except Exception:
                    pass
            fav = " ♥" if ch.url in favs else ""
            group_txt = f" [{ch.group}]" if ch.group else ""
            q = detect_quality(ch.name)
            qbadge = f"  ◆{q}" if q else ""
            item = QListWidgetItem(f"{i+1}. {ch.name}{qbadge}{fav}{group_txt}{epg_text}")
            item.setData(Qt.UserRole, ch_to_index.get(id(ch), -1))
            if q:
                item.setForeground(QColor(QUALITY_COLORS[q]))
            icon = None
            if logo_cache is not None and ch.logo_url:
                try:
                    icon = logo_cache.get(ch.logo_url)
                except Exception:
                    icon = None
            if icon is None and tile:
                icon = make_letter_tile_icon(ch.name)
            if icon is not None:
                item.setIcon(icon)
            lst.addItem(item)
        except Exception as e:
            log_error('_append_channel_item', e)

    def _fill_next_chunk(self):
        """Round 277: подсыпаем 100 каналов за тик, чтобы не блокировать
        UI. Без этого filter_channels на 3639 каналах вешал главную
        нитку на ~11 сек, что watchdog видел чётко в логе."""
        try:
            if not self.isVisible():
                return
            st = getattr(self, '_filter_state', None)
            if not st:
                self._chunk_timer.stop()
                return
            filtered = st['filtered']
            end = min(self._chunk_idx + 100, len(filtered))
            lst = self.channel_list
            lst.setUpdatesEnabled(False)
            try:
                for i in range(self._chunk_idx, end):
                    self._append_channel_item(i, filtered[i],
                                              st['show_epg'], st['epg'],
                                              st['favs'], tile=False)
            finally:
                lst.setUpdatesEnabled(True)
            self._chunk_idx = end
            if end >= len(filtered):
                self._chunk_timer.stop()
                # запускаем letter-tile дорисовку для остатка
                self._lazy_tile_idx = 200
                if not hasattr(self, '_lazy_tile_timer'):
                    self._lazy_tile_timer = QTimer(self)
                    self._lazy_tile_timer.setInterval(50)
                    self._lazy_tile_timer.timeout.connect(self._lazy_fill_tiles)
                self._lazy_tile_timer.start()
        except Exception as e:
            log_error('_fill_next_chunk', e)

    def _lazy_fill_tiles(self):
        """Round 273: фоновая раскладка letter-tile'ов пачками по 50.
        Без этого UI замораживался на ~10 сек на плейлисте 3639+
        каналов когда filter_channels рисовал все плашки синхронно."""
        try:
            lst = self.channel_list
            if not self.isVisible():
                # вкладка не активна — продолжим позже
                return
            end = min(self._lazy_tile_idx + 50, lst.count())
            for i in range(self._lazy_tile_idx, end):
                item = lst.item(i)
                if item is None or not item.icon().isNull():
                    continue
                idx = item.data(Qt.UserRole)
                if isinstance(idx, int) and 0 <= idx < len(self.channels):
                    ch = self.channels[idx]
                    item.setIcon(make_letter_tile_icon(ch.name))
            self._lazy_tile_idx = end
            if end >= lst.count():
                self._lazy_tile_timer.stop()
        except Exception as e:
            log_error('_lazy_fill_tiles', e)

    def _refresh_logos(self):
        """Called when new logos have been downloaded; update icons in place."""
        if self.logo_cache is None:
            return
        # Round 262: пропускаем если страница скрыта — иначе на каждый
        # logo_ready (раз ~400мс пока подтягиваются 3639 лого) мы
        # обходили весь QListWidget и тормозили FullHD-воспроизведение.
        if not self.isVisible():
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
        self._title = QLabel(t('favorites'))
        self._title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        layout.addWidget(self._title)
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
        self.count_label.setText(f"{len(self.fav_channels)} · {t('favorites')}")

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        if not self.isVisible():
            return  # Round 262: не тормозим плеер пока юзер не на странице
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

    def retranslate_ui(self):
        try:
            if hasattr(self, '_title'):
                self._title.setText(t('favorites'))
            if hasattr(self, 'count_label') and self.channels:
                self.count_label.setText(
                    f"{len(getattr(self, 'fav_channels', []))} · {t('favorites')}")
        except Exception as e:
            log_error('FavoritesPage.retranslate_ui', e)




# ============================================================
# Round 236 (Windows): custom delegate для overlay-списка каналов.
# Аналог Android Round 212 item_overlay_channel.xml — рендерит
# лого + имя + категорию + 3-slot EPG-сетку + полоску прогресса
# по текущей передаче. Имитирует Android Material card-style row.
# ============================================================
class ClockLabel(QLabel):
    """Round 260: часы поверх видео без QGraphicsDropShadowEffect.

    Юзер видел в логах:
      ERROR [qt] UpdateLayeredWindowIndirect failed for ...
                 dirty=(121x85 2445, -10)
    Drop-shadow с blurRadius=20 расширял визуальные границы виджета за
    пределы overlay_host (top-level WA_TranslucentBackground), и Windows
    GDI отказывался обновлять layered-window с отрицательной y. Каждая
    такая попытка — лишний GDI-stall. Рисуем чёрную обводку + белую
    заливку прямо в paintEvent — никаких эффектов, никаких слоёв."""

    def paintEvent(self, _event):
        text = self.text()
        if not text:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing, True)
        p.setFont(self.font())
        # 8-направленный stroke: чёрная обводка вокруг белого текста
        # имитирует drop-shadow с blur=2px, но рисуется в native GDI
        # без layered-window-обновлений.
        p.setPen(QPen(QColor(0, 0, 0, 230)))
        rect = self.rect()
        for dx in (-2, -1, 0, 1, 2):
            for dy in (-2, -1, 0, 1, 2):
                if dx == 0 and dy == 0:
                    continue
                p.drawText(rect.adjusted(dx, dy, dx, dy),
                           Qt.AlignLeft | Qt.AlignVCenter, text)
        p.setPen(QPen(QColor("white")))
        p.drawText(rect, Qt.AlignLeft | Qt.AlignVCenter, text)


class ChannelRowDelegate(QStyledItemDelegate):
    ROW_HEIGHT = 80
    LOGO_SIZE = 48
    PAD = 10
    EPG_COLS = 3
    EPG_COL_W = 130

    def __init__(self, get_logo, get_epg_data, parent=None):
        super().__init__(parent)
        self._get_logo = get_logo      # callable(channel) -> QIcon|None
        self._get_epg = get_epg_data   # callable(channel) -> (now, upcoming_list)
        self._cyan = QColor("#09B8E5")
        self._white = QColor("white")
        self._secondary = QColor("#B0B0CC")
        self._primary = QColor("#7C6CF7")
        self._card = QColor(36, 36, 60, 180)
        # Round 254: яркое выделение выбранной строки. Юзер: «фокуса и
        # выделения выбранной строки нигде не видно». Альфа 220 + жирная
        # белая полоса слева как Material highlight.
        self._card_sel = QColor(124, 108, 247, 220)
        self._chip_hd = QColor("#00CEC9")
        self._chip_4k = QColor("#FF7675")
        self._chip_sd = QColor("#74B9FF")

    def sizeHint(self, option, index):
        # Round 240: option.rect.width() может быть 0 при первичном
        # layout-pass; возвращаем безопасную ширину чтобы строки не
        # коллапсировали и юзер видел список.
        w = option.rect.width() if option.rect.width() > 0 else 600
        return QSize(w, self.ROW_HEIGHT)

    def paint(self, painter, option, index):
        painter.save()
        painter.setRenderHint(QPainter.Antialiasing, True)
        rect = option.rect
        # Карточка с фоном — выделение или обычный card-цвет.
        is_sel = bool(option.state & QStyle.State_Selected)
        is_hover = bool(option.state & QStyle.State_MouseOver)
        bg = self._card_sel if is_sel else self._card
        if is_hover and not is_sel:
            bg = QColor(60, 60, 92, 200)
        painter.setBrush(QBrush(bg))
        painter.setPen(Qt.NoPen)
        painter.drawRoundedRect(rect.adjusted(4, 3, -4, -3), 8, 8)
        if is_sel:
            # Round 254: чёткий контур + белая полоса-индикатор слева.
            painter.setPen(QPen(self._white, 3))
            painter.setBrush(Qt.NoBrush)
            painter.drawRoundedRect(rect.adjusted(4, 3, -4, -3), 8, 8)
            # Белая полоса-индикатор у левого края — как Material list item.
            painter.setPen(Qt.NoPen)
            painter.setBrush(QBrush(self._white))
            painter.drawRect(rect.left() + 4, rect.top() + 6,
                             5, rect.height() - 12)

        # Данные канала из user-role.
        data = index.data(Qt.UserRole + 1) or {}
        name = data.get('name', '')
        group = data.get('group', '')
        number = data.get('number', '')
        quality = data.get('quality', '')
        channel = data.get('_channel')
        # Round 239: EPG/лого читаем из пред-вычисленных полей в data,
        # а НЕ вызываем lookup в paint(). Иначе на каждом repaint /
        # scroll шла фуззи-индексация EPG для каждой видимой строки —
        # UI зависал.
        now_prog = data.get('_now')
        upcoming = data.get('_upcoming') or []
        icon = data.get('_icon')

        x = rect.left() + self.PAD
        cy = rect.center().y()

        # Номер канала (маленький бейджик).
        if number:
            num_w = 38
            num_h = 22
            num_rect = QRectF(x, cy - num_h / 2, num_w, num_h)
            painter.setBrush(QBrush(QColor(124, 108, 247, 130)))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(num_rect, 6, 6)
            painter.setPen(QPen(self._white))
            painter.setFont(QFont('Segoe UI', 10, QFont.Bold))
            painter.drawText(num_rect, Qt.AlignCenter, str(number))
            x += num_w + 8

        # Лого — уже подготовлено в data (если не было, используем
        # letter-tile fallback).
        if icon is None and name:
            icon = make_letter_tile_icon(name, self.LOGO_SIZE)
        if icon is not None:
            try:
                pix = icon.pixmap(self.LOGO_SIZE, self.LOGO_SIZE)
                painter.drawPixmap(int(x), int(cy - self.LOGO_SIZE / 2), pix)
            except Exception:
                pass
        x += self.LOGO_SIZE + 10

        # Колонка имени — 200px (минимум) или адаптивно.
        name_col_w = max(180, rect.right() - x - (self.EPG_COL_W * self.EPG_COLS) - self.PAD)
        name_col_w = min(name_col_w, 260)
        # Имя канала.
        painter.setPen(QPen(self._white))
        painter.setFont(QFont('Segoe UI', 11, QFont.Bold))
        fm = QFontMetrics(painter.font())
        name_text = fm.elidedText(name, Qt.ElideRight, name_col_w)
        painter.drawText(int(x), int(cy - 16), int(name_col_w), 20,
                         Qt.AlignLeft | Qt.AlignVCenter, name_text)

        # Группа + quality chip под именем.
        sub_y = int(cy + 4)
        painter.setFont(QFont('Segoe UI', 9))
        sub_x = x
        if quality:
            chip = self._chip_4k if quality == '4K' else (
                self._chip_hd if quality in ('HD', 'FHD') else self._chip_sd)
            chip_text = quality
            cw = QFontMetrics(painter.font()).horizontalAdvance(chip_text) + 12
            ch = 16
            chip_rect = QRectF(sub_x, sub_y - 2, cw, ch)
            painter.setBrush(QBrush(chip))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(chip_rect, 8, 8)
            painter.setPen(QPen(QColor(15, 15, 26)))
            painter.drawText(chip_rect, Qt.AlignCenter, chip_text)
            sub_x += cw + 6
        if group:
            painter.setPen(QPen(self._secondary))
            grp_text = QFontMetrics(painter.font()).elidedText(
                group, Qt.ElideRight, int(x + name_col_w - sub_x))
            painter.drawText(int(sub_x), sub_y - 2, int(x + name_col_w - sub_x), 16,
                             Qt.AlignLeft | Qt.AlignVCenter, grp_text)

        x += name_col_w + 8

        # EPG-слоты — до 3 будущих программ.
        slot_x = x
        for i in range(self.EPG_COLS):
            if i >= len(upcoming):
                break
            prog = upcoming[i]
            try:
                tstart = datetime.fromtimestamp(prog.start).strftime('%H:%M')
            except Exception:
                tstart = ''
            slot_rect = QRect(int(slot_x), rect.top() + 8,
                              self.EPG_COL_W - 6, rect.height() - 22)
            # Время — cyan, мелкое.
            painter.setPen(QPen(self._cyan))
            painter.setFont(QFont('Segoe UI', 9, QFont.Bold))
            painter.drawText(slot_rect.x(), slot_rect.y(), slot_rect.width(), 14,
                             Qt.AlignLeft | Qt.AlignVCenter, tstart)
            # Название.
            painter.setPen(QPen(self._white))
            painter.setFont(QFont('Segoe UI', 9))
            fm2 = QFontMetrics(painter.font())
            title = fm2.elidedText(prog.title or '', Qt.ElideRight,
                                    slot_rect.width())
            painter.drawText(slot_rect.x(), slot_rect.y() + 14,
                             slot_rect.width(), 30,
                             Qt.AlignLeft | Qt.AlignTop | Qt.TextWordWrap, title)
            slot_x += self.EPG_COL_W

        # Полоска прогресса по текущей программе — на дне строки.
        if now_prog:
            try:
                pct = get_current_progress(now_prog)
            except Exception:
                pct = 0
            bar_y = rect.bottom() - 6
            bar_x = rect.left() + 60
            bar_w = rect.width() - 70
            painter.setBrush(QBrush(QColor(255, 255, 255, 30)))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(QRectF(bar_x, bar_y, bar_w, 2), 1, 1)
            painter.setBrush(QBrush(self._primary))
            painter.drawRoundedRect(QRectF(bar_x, bar_y, bar_w * max(0.0, min(1.0, pct)), 2), 1, 1)
        painter.restore()


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
        # Round 273: НЕ зовём init_vlc() здесь — `libvlc_new()` сканирует
        # plugins/ и инициализирует кодеки 5-20 сек, и всё это
        # блокирует main thread на старте. Watchdog поймал 14 сек
        # фриза. VLC нужен только когда юзер реально что-то играет —
        # инициализируем в первый play_url() или в background QThread
        # сразу после показа MainWindow (см. MainWindow.__init__).

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

        # Round 256: верхняя панель (Back / Channel name / Favorite) и
        # EPG-полоска ОБЁРНУТЫ в один widget _top_chrome — юзер
        # «верхняя панель вообще не нужна» — по умолчанию скрываем.
        # Виджеты остаются доступны для кода, который их трогает.
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

        # EPG info bar (тоже в _top_chrome).
        self.epg_bar = QLabel("")
        self.epg_bar.setStyleSheet(
            f"background-color: {COLORS['surface']}; color: {COLORS['secondary']};"
            f" padding: 6px 12px; font-size: 13px;")

        self.epg_progress = QProgressBar()
        self.epg_progress.setMaximum(100)
        self.epg_progress.setTextVisible(False)
        self.epg_progress.setMaximumHeight(4)

        # Контейнер для всей «верхней панели» — прячем целиком.
        self._top_chrome = QWidget()
        chrome_col = QVBoxLayout(self._top_chrome)
        chrome_col.setContentsMargins(0, 0, 0, 0)
        chrome_col.setSpacing(0)
        chrome_col.addLayout(top_bar)
        chrome_col.addWidget(self.epg_bar)
        chrome_col.addWidget(self.epg_progress)
        self._top_chrome.hide()  # юзер: «верхняя панель вообще не нужна»
        layout.addWidget(self._top_chrome)

        # Video frame with OSD banner overlay (parented to video_frame)
        self.video_frame = QFrame()
        self.video_frame.setStyleSheet("background-color: black;")
        self.video_frame.setMinimumHeight(400)
        self.video_frame.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        layout.addWidget(self.video_frame)
        # Round 248: VLC рисует видео прямо на нативном HWND video_frame
        # и закрывает любые Qt-виджеты внутри него. Поэтому все оверлеи,
        # часы и баннер живут в ОТДЕЛЬНОМ top-level прозрачном окне
        # overlay_host, которое плавает поверх видео и трекает его
        # геометрию. Так VLC физически не может их перекрыть.
        # Round 267: убран WindowStaysOnTopHint — был причиной
        # появления overlay над другими приложениями.
        self.overlay_host = QWidget(
            None,
            Qt.FramelessWindowHint | Qt.Tool
            | Qt.NoDropShadowWindowHint)
        self.overlay_host.setAttribute(Qt.WA_TranslucentBackground, True)
        # Round 267: УБРАЛИ WA_ShowWithoutActivating — без активации
        # окна QLineEdit поиска в channels_overlay не получал ввод
        # клавиатуры. Юзер: «в списке каналов нет возможности ввести
        # имя для поиска». Окно активируется при show — это ок.
        self.overlay_host.hide()
        # Таймер синхронизации позиции overlay_host с video_frame —
        # ловит перемещение/ресайз/фуллскрин главного окна.
        self._overlay_sync_timer = QTimer(self)
        # Round 250: 200мс был агрессивный (CPU 5-10% и подвисания).
        # 800мс хватает чтобы отслеживать перемещение окна; реальная
        # реакция на ресайз/show/hide идёт через явные вызовы.
        # Round 267: 800мс был слишком медленный — оверлеи отставали
        # при перетаскивании окна. 150мс достаточно гладко и при этом
        # дешёвый _sync_overlay_host (rmcache early return).
        self._overlay_sync_timer.setInterval(150)
        self._overlay_sync_timer.timeout.connect(self._sync_overlay_host)
        self._last_overlay_geom = None  # кэш геометрии — пропускаем no-op
        self._build_osd_banner()
        # Round 255: даём splash перерисоваться между тяжёлыми overlay.
        # PlayerPage — самый дорогой шаг init_ui(); без yield'ов между
        # подсборками юзер видит замёрзший splash.
        QApplication.processEvents()
        # Round 232 (Windows): аналоги Android-овых overlay-панелей.
        # Левая — список каналов с поиском; правая — быстрые настройки
        # (Aspect / Speed / Audio / Sleep / Fullscreen / PiP / Favorite).
        # Скрыты по умолчанию; toggle хоткеями L / R и кнопками в top-bar.
        self._build_channels_overlay()
        QApplication.processEvents()
        self._build_quick_overlay()
        QApplication.processEvents()
        # Round 244: цепочка как в Android — LEFT → каналы → категории
        # → центральное меню.
        self._build_categories_overlay()
        QApplication.processEvents()
        self._build_center_menu()
        QApplication.processEvents()
        # Кнопки в top-bar для тех у кого нет физической клавиатуры.
        try:
            self._inject_overlay_toggle_buttons()
        except Exception:
            pass

        # Auto-hide banner timer
        # Round 245: банер информации — как в Android-плеере:
        # появляется на переключении канала и исчезает через ~4.5 сек.
        # ТАКЖЕ показывается на mouseMove и keypress в плеере чтобы
        # юзер мог взглянуть на инфо в любой момент.
        self._banner_timer = QTimer(self)
        self._banner_timer.setSingleShot(True)
        self._banner_timer.setInterval(4500)
        self._banner_timer.timeout.connect(self._hide_banner)
        try:
            self.video_frame.setMouseTracking(True)
            self.setMouseTracking(True)
        except Exception:
            pass

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

        # Round 246: персистентные часы поверх видео — как Android
        # persistentClock (Round 221b). Большие белые цифры + чёрная
        # тень для читаемости на любом фоне, без подложки. Позиция —
        # верх-право, обновляется по тому же clock_timer.
        # Round 260: ClockLabel сам рисует чёрный stroke + белый fill в
        # paintEvent. Раньше использовали QGraphicsDropShadowEffect c
        # blurRadius=20 на QLabel поверх translucent overlay_host — это
        # вызывало UpdateLayeredWindowIndirect failed в Qt-логе на каждое
        # обновление часов и подвешивало приложение.
        self.persistent_clock = ClockLabel(self.overlay_host)
        f = QFont('Segoe UI', 24, QFont.Bold)
        self.persistent_clock.setFont(f)
        self.persistent_clock.setStyleSheet("background: transparent;")
        self.persistent_clock.setText(datetime.now().strftime('%H:%M'))
        self.persistent_clock.adjustSize()

        # Round 245: нижняя панель кнопок СКРЫТА — как в Android-плеере,
        # где нет видимых нижних кнопок. Все управление перенесено в
        # right-overlay (RIGHT) и через хоткеи + osd_banner показывает
        # инфо при переключении. Сами кнопки оставляем в layout (для
        # кода который их трогает: btn_play.setText "Pause" и т.п.),
        # но прячем сам контейнер.
        self._bottom_ctrl_layout = ctrl
        self._bottom_ctrl_widget = QWidget()
        self._bottom_ctrl_widget.setLayout(ctrl)
        self._bottom_ctrl_widget.setVisible(False)
        layout.addWidget(self._bottom_ctrl_widget)

        # EPG update timer
        self.epg_timer = QTimer()
        self.epg_timer.timeout.connect(self.update_epg_display)
        self.epg_timer.start(30000)

        self.clock_timer = QTimer()
        self.clock_timer.timeout.connect(self.update_clock)
        self.clock_timer.start(30000)
        self.update_clock()

    def _build_mini_osd(self):
        """Round 280: канальная info-карта в стиле референса (zedom).
        Логотип слева + название и БОЛЬШОЙ номер канала + EPG-прогресс
        с временами и категория. Показывается на 4 сек при
        переключении / изменении громкости."""
        # Контейнер
        self._mini_osd = QWidget(self.overlay_host)
        self._mini_osd.setStyleSheet(
            "background-color: rgba(0, 0, 0, 200);"
            " border-radius: 14px;"
            " border: 2px solid rgba(0, 200, 230, 220);")
        self._mini_osd.hide()
        outer = QHBoxLayout(self._mini_osd)
        outer.setContentsMargins(16, 14, 22, 14)
        outer.setSpacing(16)
        # Левая колонка — логотип/плашка категории
        self._osd_logo_lbl = QLabel()
        self._osd_logo_lbl.setFixedSize(72, 72)
        self._osd_logo_lbl.setAlignment(Qt.AlignCenter)
        self._osd_logo_lbl.setStyleSheet(
            "background-color: rgba(40, 40, 56, 220);"
            " border-radius: 8px; color: #FFCB57;"
            " font-weight: bold; font-size: 12px;")
        outer.addWidget(self._osd_logo_lbl)
        # Правая колонка — текст
        right = QVBoxLayout()
        right.setSpacing(2)
        # Верхняя строка — плейлист/группа маленьким шрифтом
        top_row = QHBoxLayout()
        top_row.setSpacing(12)
        self._osd_plist = QLabel("")
        self._osd_plist.setStyleSheet(
            "color: #B0C0CC; font-size: 12px; background: transparent;")
        top_row.addWidget(self._osd_plist)
        self._osd_group = QLabel("")
        self._osd_group.setStyleSheet(
            "color: #00C8E6; font-size: 12px; background: transparent;")
        top_row.addWidget(self._osd_group)
        top_row.addStretch()
        right.addLayout(top_row)
        # Главная строка — название канала + БОЛЬШОЙ номер
        main_row = QHBoxLayout()
        main_row.setSpacing(14)
        self._osd_name = QLabel("")
        self._osd_name.setStyleSheet(
            "color: white; font-size: 22px; font-weight: bold;"
            " background: transparent;")
        main_row.addWidget(self._osd_name, 1)
        self._osd_number = QLabel("")
        self._osd_number.setStyleSheet(
            "color: white; font-size: 40px; font-weight: bold;"
            " background: transparent;")
        self._osd_number.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        main_row.addWidget(self._osd_number)
        right.addLayout(main_row)
        # EPG-прогресс — время начала, текущая программа, время конца + бар
        epg_row = QHBoxLayout()
        epg_row.setSpacing(6)
        self._osd_t1 = QLabel("")
        self._osd_t1.setStyleSheet(
            "color: white; font-size: 11px; background: transparent;")
        self._osd_prog_title = QLabel("")
        self._osd_prog_title.setStyleSheet(
            "color: white; font-size: 11px; background: transparent;")
        self._osd_t2 = QLabel("")
        self._osd_t2.setStyleSheet(
            "color: white; font-size: 11px; background: transparent;")
        epg_row.addWidget(self._osd_t1)
        epg_row.addWidget(self._osd_prog_title, 1)
        epg_row.addWidget(self._osd_t2)
        right.addLayout(epg_row)
        self._osd_progress = QProgressBar()
        self._osd_progress.setMaximum(100)
        self._osd_progress.setTextVisible(False)
        self._osd_progress.setMaximumHeight(3)
        self._osd_progress.setStyleSheet(
            "QProgressBar { background-color: rgba(255,255,255,40);"
            " border: none; border-radius: 1px; }"
            "QProgressBar::chunk { background-color: #00C8E6;"
            " border-radius: 1px; }")
        right.addWidget(self._osd_progress)
        outer.addLayout(right, 1)
        self._mini_osd_timer = QTimer(self)
        self._mini_osd_timer.setSingleShot(True)
        self._mini_osd_timer.setInterval(4000)
        self._mini_osd_timer.timeout.connect(self._mini_osd.hide)
        # Простой fallback для громкости — отдельный текстовый bubble.
        self._mini_osd_vol = QLabel(self.overlay_host)
        self._mini_osd_vol.setStyleSheet(
            "background-color: rgba(0, 0, 0, 200); color: white;"
            " font-size: 16px; font-weight: bold;"
            " border-radius: 10px; padding: 10px 16px;"
            " border: 1px solid rgba(0, 200, 230, 200);")
        self._mini_osd_vol.setAlignment(Qt.AlignCenter)
        self._mini_osd_vol.hide()
        self._mini_osd_vol_timer = QTimer(self)
        self._mini_osd_vol_timer.setSingleShot(True)
        self._mini_osd_vol_timer.setInterval(1500)
        self._mini_osd_vol_timer.timeout.connect(self._mini_osd_vol.hide)

    def show_channel_osd(self, channel, index, total):
        """Round 280: канальная OSD-карта в стиле zedom — логотип,
        большой номер, EPG-прогресс."""
        try:
            if not hasattr(self, '_mini_osd'):
                self._build_mini_osd()
            # Логотип канала или плашка-категория
            pix = None
            if self.logo_cache is not None and channel.logo_url:
                icon = self.logo_cache.get(channel.logo_url)
                if icon is not None:
                    pix = icon.pixmap(72, 72)
            if pix and not pix.isNull():
                self._osd_logo_lbl.setPixmap(pix)
                self._osd_logo_lbl.setText("")
            else:
                self._osd_logo_lbl.clear()
                self._osd_logo_lbl.setText(
                    (channel.group or channel.name[:2].upper())[:12])
            # Подзаголовок: имя плейлиста + группа
            try:
                pl_name = (self.window().config.last_playlist_name or "playlist")[:20]
            except Exception:
                pl_name = "playlist"
            self._osd_plist.setText(pl_name)
            self._osd_group.setText(("▶ " + channel.group) if channel.group else "")
            # Главное: имя + номер
            self._osd_name.setText(channel.name)
            self._osd_number.setText(str(index + 1))
            # EPG
            now_prog, next_prog = get_now_next(
                self.epg_data, channel.tvg_id, channel.name)
            if now_prog:
                try:
                    t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                    t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
                    self._osd_t1.setText(t1)
                    self._osd_t2.setText(t2)
                    self._osd_prog_title.setText(now_prog.title or "")
                    self._osd_progress.setValue(int(get_current_progress(now_prog) * 100))
                except (OSError, ValueError):
                    self._osd_t1.setText("")
                    self._osd_t2.setText("")
                    self._osd_prog_title.setText(now_prog.title or "")
                    self._osd_progress.setValue(0)
            else:
                self._osd_t1.setText("")
                self._osd_t2.setText("")
                self._osd_prog_title.setText("—")
                self._osd_progress.setValue(0)
            # Позиционирование — нижний центр над видео
            self._mini_osd.adjustSize()
            pw = self.overlay_host.width()
            ph = self.overlay_host.height()
            if pw > 0 and ph > 0:
                w = min(640, max(420, self._mini_osd.sizeHint().width()))
                h = self._mini_osd.sizeHint().height()
                self._mini_osd.setGeometry((pw - w) // 2, ph - h - 60, w, h)
                self._mini_osd.show()
                self._mini_osd.raise_()
                self._mini_osd_timer.start()
        except Exception as e:
            log_error('show_channel_osd', e)

    def show_mini_osd(self, text):
        """Round 280: облегчённая OSD только для громкости. Канал
        переключается через show_channel_osd с полноценной карточкой."""
        try:
            if not hasattr(self, '_mini_osd_vol'):
                self._build_mini_osd()
            self._mini_osd_vol.setText(text)
            self._mini_osd_vol.adjustSize()
            pw = self.overlay_host.width()
            ph = self.overlay_host.height()
            if pw > 0 and ph > 0:
                w = self._mini_osd_vol.width()
                h = self._mini_osd_vol.height()
                self._mini_osd_vol.setGeometry((pw - w) // 2, 40, w, h)
                self._mini_osd_vol.show()
                self._mini_osd_vol.raise_()
                self._mini_osd_vol_timer.start()
        except Exception as e:
            log_error('show_mini_osd', e)

    def _build_osd_banner(self):
        """Floating channel info banner (parented to video_frame, shown briefly on switch)."""
        self.osd_banner = QWidget(self.overlay_host)
        self.osd_banner.setStyleSheet(
            "background-color: rgba(18, 18, 32, 220);"
            " border-radius: 10px;")
        self.osd_banner.hide()
        # Round 279: создаём мини-OSD сразу
        self._build_mini_osd()
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
        # Round 252: верхняя панель как в Android (bg_player_gradient_top):
        # имя канала + now-playing слева, LIVE-бейдж и часы справа.
        # Авто-скрывается вместе с баннером.
        self._build_top_panel()

    def _build_top_panel(self):
        self.top_panel = QWidget(self.overlay_host)
        # Градиент сверху вниз: непрозрачный чёрный → прозрачный.
        self.top_panel.setStyleSheet(
            "background-color: rgba(0,0,0,0);")
        self.top_panel.hide()
        # Полупрозрачная подложка через дочерний QLabel с градиентом
        # (QSS не делает linear-gradient на QWidget надёжно, поэтому
        # рисуем сплошную затемнённую полосу).
        bg = QLabel(self.top_panel)
        bg.setStyleSheet("background-color: rgba(10,10,20,180);")
        self._top_panel_bg = bg
        row = QHBoxLayout(self.top_panel)
        row.setContentsMargins(18, 12, 18, 12)
        row.setSpacing(12)
        # Иконка списка каналов (☰) — клик открывает список.
        self.top_channels_btn = QPushButton("☰")
        self.top_channels_btn.setFixedSize(38, 38)
        self.top_channels_btn.setStyleSheet(
            "QPushButton { background: transparent; color: white;"
            " font-size: 20px; border: none; }"
            "QPushButton:hover { color: #7C6CF7; }")
        self.top_channels_btn.clicked.connect(self.left_press)
        row.addWidget(self.top_channels_btn)
        # Имя канала + now-playing.
        name_col = QVBoxLayout()
        name_col.setSpacing(2)
        self.top_channel_name = QLabel("")
        self.top_channel_name.setStyleSheet(
            "color: white; font-size: 17px; font-weight: bold;"
            " background: transparent;")
        name_col.addWidget(self.top_channel_name)
        self.top_now = QLabel("")
        self.top_now.setStyleSheet(
            "color: rgba(255,255,255,180); font-size: 12px;"
            " background: transparent;")
        name_col.addWidget(self.top_now)
        row.addLayout(name_col, 1)
        # LIVE-бейдж.
        live = QLabel("● LIVE")
        live.setStyleSheet(
            "color: white; font-size: 11px; font-weight: bold;"
            " background-color: #E53935; border-radius: 4px;"
            " padding: 3px 8px;")
        row.addWidget(live)
        # Часы.
        self.top_clock = QLabel("")
        self.top_clock.setStyleSheet(
            "color: white; font-size: 15px; font-weight: bold;"
            " background: transparent;")
        row.addWidget(self.top_clock)

    def _position_top_panel(self):
        if not hasattr(self, 'top_panel'):
            return
        pw = self.overlay_host.width()
        if pw <= 0:
            return
        h = 62
        self.top_panel.setGeometry(0, 0, pw, h)
        if hasattr(self, '_top_panel_bg'):
            self._top_panel_bg.setGeometry(0, 0, pw, h)
            self._top_panel_bg.lower()

    def _position_osd(self):
        if not hasattr(self, 'osd_banner'):
            return
        parent = self.overlay_host
        pw = parent.width()
        ph = parent.height()
        if pw <= 0 or ph <= 0:
            return
        bw = min(560, max(340, pw - 40))
        bh = 96
        # Round 252: баннер инфо теперь ВНИЗУ (как Android bottomBar),
        # верхняя панель занимает верх.
        self.osd_banner.setGeometry(20, ph - bh - 20, bw, bh)
        self._position_top_panel()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        # Round 248: overlay_host — top-level окно, ресайз PlayerPage
        # не меняет его автоматически; пересинхронизируем.
        self._sync_overlay_host()

    # ---- Round 232: side-panel overlays ----

    def _build_channels_overlay(self):
        """Слева, ширина 360px. Содержит поиск + QListWidget со всеми каналами."""
        self.channels_overlay = QWidget(self.overlay_host)
        # Round 239: убран QGraphicsDropShadowEffect — на больших
        # списках с делегатом он вызывал re-paint шторм, юзер сказал
        # «программа зависает на любую кнопку». Плоская обводка без
        # эффектов работает значительно стабильнее.
        self.channels_overlay.setStyleSheet(
            "background-color: rgba(15, 15, 26, 170);"
            " border-top-right-radius: 14px;"
            " border-bottom-right-radius: 14px;"
            " border: 2px solid rgba(124, 108, 247, 220);"
            " border-left: none;")
        self.channels_overlay.hide()
        col = QVBoxLayout(self.channels_overlay)
        col.setContentsMargins(10, 10, 10, 10)
        col.setSpacing(8)
        title = QLabel(t('panel_channels'))
        title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(title)
        self._overlay_search = QLineEdit()
        self._overlay_search.setPlaceholderText(t('search') + "…")
        # Round 278: кнопка «×» внутри QLineEdit — стирает всю строку
        # одним кликом. Юзер: «нельзя разом удалить всю строку».
        # Также Ctrl+A и Ctrl+Backspace работают нативно — eventFilter
        # пропускает все клавиши пока QLineEdit имеет фокус.
        self._overlay_search.setClearButtonEnabled(True)
        self._overlay_search.textChanged.connect(self._refresh_channels_overlay)
        # Round 278: при изменении ЗЕРКАЛИМ в ChannelsPage.search_edit —
        # иначе унаследованный фильтр оставался на вкладке Каналы и
        # юзер мог думать, что overlay-стирание не работает.
        self._overlay_search.textChanged.connect(self._mirror_search_to_channels_page)
        col.addWidget(self._overlay_search)
        self._overlay_list = QListWidget()
        self._overlay_list.setIconSize(QSize(28, 28))
        self._overlay_list.itemClicked.connect(self._overlay_channel_clicked)
        self._overlay_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self._overlay_list.customContextMenuRequested.connect(
            self._show_overlay_channel_details)
        # Round 236: рендеринг строк через ChannelRowDelegate — лого +
        # имя + EPG-сетка + прогресс, копия Android Round 212 layout.
        self._overlay_list.setMouseTracking(True)
        self._overlay_list.setStyleSheet(
            "QListWidget { background: transparent; border: none; outline: none; }"
            "QListWidget::item { padding: 0; }"
            "QListWidget::item:selected { background: transparent; }")
        self._channel_delegate = ChannelRowDelegate(
            get_logo=self._delegate_get_logo,
            get_epg_data=self._delegate_get_epg,
            parent=self._overlay_list,
        )
        self._overlay_list.setItemDelegate(self._channel_delegate)
        col.addWidget(self._overlay_list, 1)

    def _build_categories_overlay(self):
        """Round 244: узкая панель категорий — слева, ~200px. Возникает
        на 2-е нажатие LEFT (как Android Round 199)."""
        self.categories_overlay = QWidget(self.overlay_host)
        self.categories_overlay.setStyleSheet(
            "background-color: rgba(15, 15, 26, 170);"
            " border-top-right-radius: 14px;"
            " border-bottom-right-radius: 14px;"
            " border: 2px solid rgba(124, 108, 247, 220);"
            " border-left: none;")
        self.categories_overlay.hide()
        col = QVBoxLayout(self.categories_overlay)
        col.setContentsMargins(10, 10, 10, 10)
        col.setSpacing(8)
        title = QLabel(t('panel_quick') if False else "Категории")
        title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(title)
        self._cat_list = QListWidget()
        # Round 254: чёткое выделение фокуса. Юзер: «фокуса и выделения
        # выбранной строки нигде не видно». Толстая обводка + светлая
        # заливка + белая полоса слева.
        self._cat_list.setStyleSheet(
            "QListWidget { background: transparent; color: white;"
            " border: none; font-size: 14px; outline: none; }"
            "QListWidget::item { padding: 10px 8px; border-radius: 6px;"
            " margin-bottom: 4px; }"
            "QListWidget::item:hover { background-color: rgba(124, 108, 247, 100); }"
            "QListWidget::item:selected { background-color: #7C6CF7;"
            " color: white; border-left: 4px solid white;"
            " font-weight: bold; }")
        self._cat_list.itemClicked.connect(self._on_category_chosen)
        col.addWidget(self._cat_list, 1)

    def _build_center_menu(self):
        """Round 244: центральное popup-меню. Возникает на 3-е нажатие
        LEFT (как Android Round 211). Кнопки: Настройки / Избранное /
        Недавние / Поиск."""
        self.center_menu_overlay = QWidget(self.overlay_host)
        # Полупрозрачный dim позади.
        self.center_menu_overlay.setStyleSheet(
            "background-color: rgba(0, 0, 0, 130);")
        self.center_menu_overlay.hide()
        # Внутренняя панель — карточка с кнопками.
        outer = QVBoxLayout(self.center_menu_overlay)
        outer.setAlignment(Qt.AlignCenter)
        card = QWidget(self.center_menu_overlay)
        card.setStyleSheet(
            "background-color: rgba(26, 26, 50, 250);"
            " border: 2px solid #7C6CF7; border-radius: 16px;")
        card.setFixedWidth(360)
        inner = QVBoxLayout(card)
        inner.setContentsMargins(20, 20, 20, 20)
        inner.setSpacing(10)
        title = QLabel(t('settings'))
        title.setStyleSheet("color: white; font-size: 18px;"
                            " font-weight: bold; padding-bottom: 4px;")
        inner.addWidget(title)

        def _row(label, callback):
            b = QPushButton(label)
            b.setMinimumHeight(48)
            # Round 256: focused-стиль через property вместо :focus —
            # VLC native HWND ворует Qt-focus, поэтому полагаться на
            # hasFocus()/QSS :focus нельзя. Подсветку рисуем сами.
            b.setStyleSheet(
                "QPushButton { background-color: rgba(60, 60, 92, 200);"
                " color: white; border: 1px solid #7C6CF7;"
                " border-radius: 8px; font-size: 14px;"
                " padding: 8px 12px; text-align: left; }"
                "QPushButton:hover { background-color: #7C6CF7; }"
                "QPushButton[focused=\"true\"] { background-color: #7C6CF7;"
                " border: 2px solid white; font-weight: bold; }")
            b.setProperty('focused', False)
            b.clicked.connect(callback)
            return b

        # Кнопки тригерят переключение MainWindow (через сигнал, плюс
        # закрытие центрального меню).
        # Round 254: EPG-инфо текущего канала вверху центр-меню
        # (бывшая нижняя инфо-панель переехала сюда по запросу юзера).
        self._center_menu_info = QLabel("")
        self._center_menu_info.setWordWrap(True)
        self._center_menu_info.setStyleSheet(
            "color: #00CEC9; font-size: 13px; line-height: 1.5;"
            " background-color: rgba(0,0,0,80); border-radius: 8px;"
            " padding: 10px 12px; margin-bottom: 6px;")
        inner.addWidget(self._center_menu_info)

        # Round 254/256: храним кнопки списком — нужен для D-pad навигации
        # (Up/Down в _handle_key) с явным трекером индекса (Qt-focus
        # ворует VLC native HWND, hasFocus() ненадёжен).
        self._center_menu_buttons = []
        self._center_menu_focused_idx = 0
        b1 = _row("⚙  " + t('settings'),
                  lambda: self._center_menu_action('settings'))
        b2 = _row("★  " + t('favorites'),
                  lambda: self._center_menu_action('favorites'))
        b3 = _row("⏱  " + t('recent'),
                  lambda: self._center_menu_action('recent'))
        b4 = _row("🔍  Поиск",
                  lambda: self._center_menu_action('search'))
        for bb in (b1, b2, b3, b4):
            bb.setFocusPolicy(Qt.StrongFocus)
            inner.addWidget(bb)
            self._center_menu_buttons.append(bb)
        outer.addWidget(card)
        # Сохраняем reference на card чтобы могли вернуть фокус.
        self._center_menu_card = card

    def _apply_button_focus(self, buttons, idx):
        """Round 256: вручную выставляем 'focused' property на нужной
        кнопке и сбрасываем на остальных, потом форсим перерендер QSS.
        Используется и для центр-меню, и для quick-overlay."""
        if not buttons:
            return
        idx = idx % len(buttons)
        for i, b in enumerate(buttons):
            b.setProperty('focused', i == idx)
            # Перевычислить стиль (Qt не переоценивает QSS-property
            # автоматически — нужно unpolish/polish).
            try:
                b.style().unpolish(b)
                b.style().polish(b)
                b.update()
            except Exception:
                pass

    def step_center_menu_focus(self, delta):
        """Round 256: D-pad навигация по центр-меню. delta=+1/-1."""
        if not hasattr(self, '_center_menu_buttons') or not self._center_menu_buttons:
            return
        self._center_menu_focused_idx = (
            self._center_menu_focused_idx + delta) % len(self._center_menu_buttons)
        self._apply_button_focus(self._center_menu_buttons,
                                 self._center_menu_focused_idx)

    def trigger_center_menu_focused(self):
        if not hasattr(self, '_center_menu_buttons') or not self._center_menu_buttons:
            return
        idx = self._center_menu_focused_idx % len(self._center_menu_buttons)
        self._center_menu_buttons[idx].click()

    def step_quick_overlay_focus(self, delta):
        """Round 256: D-pad навигация по quick-overlay."""
        if not hasattr(self, '_quick_overlay_buttons') or not self._quick_overlay_buttons:
            return
        self._quick_overlay_focused_idx = (
            self._quick_overlay_focused_idx + delta) % len(self._quick_overlay_buttons)
        self._apply_button_focus(self._quick_overlay_buttons,
                                 self._quick_overlay_focused_idx)

    def trigger_quick_overlay_focused(self):
        if not hasattr(self, '_quick_overlay_buttons') or not self._quick_overlay_buttons:
            return
        idx = self._quick_overlay_focused_idx % len(self._quick_overlay_buttons)
        self._quick_overlay_buttons[idx].click()

    def _center_menu_action(self, action):
        """Round 244: handler центрального меню. Закрывает все overlays
        + переключается на нужную вкладку."""
        self.hide_all_overlays()
        try:
            mw = self.window()
            if action == 'settings':
                mw.switch_page(4)
            elif action == 'favorites':
                mw.switch_page(2)
            elif action == 'recent':
                mw.switch_page(6)
            elif action == 'search':
                mw.switch_page(1)
                # Сразу даём фокус на поисковую строку каналов.
                try:
                    mw.channels_page.search_edit.setFocus()
                except Exception:
                    pass
        except Exception:
            pass

    def hide_all_overlays(self):
        for w in ('channels_overlay', 'categories_overlay',
                  'center_menu_overlay', 'quick_overlay'):
            o = getattr(self, w, None)
            if o is not None and o.isVisible():
                o.hide()
        # Round 251: сброс LEFT-стадии, чтобы следующее LEFT начинало
        # с открытия каналов, а не продолжало с середины.
        self._left_stage = 0
        self._left_dir = 1

    def _refresh_categories_overlay(self):
        if not hasattr(self, '_cat_list'):
            return
        cats = ["All"]
        seen = set(["All"])
        for ch in (self.channels or []):
            g = (ch.group or "").strip()
            if g and g not in seen:
                seen.add(g)
                cats.append(g)
        self._cat_list.clear()
        for c in cats:
            item = QListWidgetItem(c)
            self._cat_list.addItem(item)

    def _on_category_chosen(self, item):
        """Round 244: при выборе категории — закрываем overlay
        категорий, открываем список каналов с фильтром."""
        cat = item.text()
        # Простая фильтрация: храним в config.last_category и сигналим
        # MainWindow обновить ChannelsPage (если он есть).
        try:
            self.config.last_category = cat
            self.config.save_async()
            mw = self.window()
            cp = getattr(mw, 'channels_page', None)
            if cp is not None:
                cp.selected_category = cat
                try:
                    cp.filter_channels()
                except Exception:
                    pass
        except Exception:
            pass
        # Скрываем overlay категорий и показываем каналы.
        if self.categories_overlay.isVisible():
            self.categories_overlay.hide()
        self._refresh_channels_overlay()
        self._slide_in(self.channels_overlay, direction='left')
        self.channels_overlay.raise_()

    def _build_quick_overlay(self):
        """Справа, ширина 240px. Кнопки быстрых настроек."""
        self.quick_overlay = QWidget(self.overlay_host)
        self.quick_overlay.setStyleSheet(
            "background-color: rgba(15, 15, 26, 170);"
            " border-top-left-radius: 14px;"
            " border-bottom-left-radius: 14px;"
            " border: 2px solid rgba(124, 108, 247, 220);"
            " border-right: none;")
        self.quick_overlay.hide()
        col = QVBoxLayout(self.quick_overlay)
        col.setContentsMargins(10, 10, 10, 10)
        col.setSpacing(8)
        title = QLabel(t('panel_quick'))
        title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(title)

        # Round 256: список кнопок quick-overlay для D-pad навигации
        # (юзер: «не возможно перемещать строки в меню которая
        # открывается при нажатии вправо»). Стили — заметный focus.
        self._quick_overlay_buttons = []
        self._quick_overlay_focused_idx = 0

        def _btn(label, callback):
            b = QPushButton(label)
            b.setMinimumHeight(40)
            b.setStyleSheet(
                "QPushButton { background-color: rgba(60, 60, 92, 200);"
                " color: white; border: 1px solid #7C6CF7;"
                " border-radius: 8px; font-size: 13px;"
                " padding: 6px 10px; text-align: left; }"
                "QPushButton:hover { background-color: #7C6CF7; }"
                "QPushButton[focused=\"true\"] { background-color: #7C6CF7;"
                " border: 2px solid white; font-weight: bold; }")
            b.setProperty('focused', False)
            b.clicked.connect(callback)
            col.addWidget(b)
            self._quick_overlay_buttons.append(b)
            return b

        _btn(t('aspect'), self.cycle_aspect_ratio)
        _btn(t('speed'), self.cycle_speed)
        _btn(t('audio_track'), self.cycle_audio_track)
        _btn(t('sleep_timer'), self.configure_sleep_timer)
        _btn(t('fullscreen'), self.toggle_fullscreen)
        _btn(t('pip'), self._on_pip_clicked)
        _btn("♥ " + t('favorites'), self.toggle_favorite)
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
        self._overlay_toggle_bar = QWidget(self.overlay_host)
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

    def showEvent(self, event):
        # Round 248: при показе PlayerPage поднимаем overlay_host над
        # видео и запускаем синхронизацию его геометрии.
        # Round 261: ДОПОЛНИТЕЛЬНО ставим MainWindow владельцем
        # overlay_host (setParent + те же флаги). На Windows owned-окно
        # минимизируется/уходит назад вместе со своим owner-ом. Без
        # этого overlay_host (Qt.Tool|WindowStaysOnTopHint) светился
        # поверх ДРУГИХ приложений при alt-tab. Юзер: «опять эти
        # элементы выходят за пределы своей программы». Делается один
        # раз при первом show — последующие setParent дешевле, но всё
        # равно пересоздают native window, так что флагуем.
        super().showEvent(event)
        try:
            if not getattr(self, '_overlay_owner_set', False):
                mw = self.window()
                if mw is not None and mw is not self:
                    # Round 267: УБРАЛИ WindowStaysOnTopHint — именно
                    # этот флаг делал overlay_host «topmost» на уровне
                    # ОС и выводил его поверх ДРУГИХ приложений. Юзер:
                    # «опять все эти элементы появляются поверх других
                    # программ». Сам Qt.Tool + parent=MainWindow создаёт
                    # на Windows owner-relationship — owned-окно
                    # автоматически уходит назад вместе с owner-ом, а
                    # выше owner-а его поднимаем сами через raise_().
                    self.overlay_host.setParent(
                        mw,
                        Qt.FramelessWindowHint | Qt.Tool
                        | Qt.NoDropShadowWindowHint)
                    self.overlay_host.setAttribute(
                        Qt.WA_TranslucentBackground, True)
                    # Round 267: НЕ ставим WA_ShowWithoutActivating —
                    # тогда QLineEdit получает ввод клавиатуры.
                    self._overlay_owner_set = True
                    log_info('overlay', f"owner set to {type(mw).__name__}")
            self._sync_overlay_host()
            self.overlay_host.show()
            self.overlay_host.raise_()
            self._overlay_sync_timer.start()
        except Exception as e:
            log_error('PlayerPage.showEvent', e)

    def hideEvent(self, event):
        # Round 248: уходя из плеера прячем overlay_host (иначе он
        # останется висеть поверх других вкладок) и стопаем таймер.
        super().hideEvent(event)
        try:
            self._overlay_sync_timer.stop()
            self.overlay_host.hide()
        except Exception:
            pass

    def _sync_overlay_host(self):
        """Round 248/250: позиционируем top-level overlay_host точно над
        video_frame. Раскладываем дочерние оверлеи ТОЛЬКО когда
        геометрия реально изменилась — иначе таймер 5 раз в секунду
        вызывал каскад setGeometry/repaint, что и давало зависания.
        Round 268: адаптивный интервал — 150мс пока что-то открыто,
        1000мс когда оверлей пуст (только часы). Снижает фоновую
        нагрузку при просмотре без панелей."""
        try:
            any_overlay_visible = any(
                getattr(self, n, None) is not None
                and getattr(self, n).isVisible()
                for n in ('channels_overlay', 'categories_overlay',
                          'center_menu_overlay', 'quick_overlay'))
            new_interval = 150 if any_overlay_visible else 1000
            if self._overlay_sync_timer.interval() != new_interval:
                self._overlay_sync_timer.setInterval(new_interval)
            if not self.video_frame.isVisible():
                self.overlay_host.hide()
                return
            tl = self.video_frame.mapToGlobal(self.video_frame.rect().topLeft())
            w = self.video_frame.width()
            h = self.video_frame.height()
            if w <= 0 or h <= 0:
                return
            geom = (tl.x(), tl.y(), w, h)
            if geom == self._last_overlay_geom:
                # геометрия не менялась — только удостоверимся что окно
                # видимо/поднято, и выходим.
                if not self.overlay_host.isVisible():
                    self.overlay_host.show()
                return
            self._last_overlay_geom = geom
            self.overlay_host.setGeometry(*geom)
            if not self.overlay_host.isVisible():
                self.overlay_host.show()
            self.overlay_host.raise_()
            self._position_osd()
            self._position_overlays()
            self._position_persistent_clock()
            # Round 248: когда открыт интерактивный оверлей — окно
            # ловит мышь; когда видны только часы/баннер — пропускаем
            # клики на видео (иначе нельзя кликнуть по плееру).
            interactive = any(
                getattr(self, name, None) is not None
                and getattr(self, name).isVisible()
                for name in ('channels_overlay', 'categories_overlay',
                             'center_menu_overlay', 'quick_overlay'))
            self.overlay_host.setAttribute(
                Qt.WA_TransparentForMouseEvents, not interactive)
        except Exception as e:
            log_error('_sync_overlay_host', e)

    def _position_overlays(self):
        if not hasattr(self, 'channels_overlay'):
            return
        pw = self.overlay_host.width()
        ph = self.overlay_host.height()
        if pw <= 0 or ph <= 0:
            return
        # Round 236: расширили left-overlay до 640px чтобы EPG-сетка
        # помещалась в строке (3 × 130px = 390px + лого + имя).
        ch_w = min(680, int(pw * 0.62))
        qk_w = min(280, int(pw * 0.32))
        cat_w = min(220, int(pw * 0.22))
        self.channels_overlay.setGeometry(0, 0, ch_w, ph)
        self.quick_overlay.setGeometry(pw - qk_w, 0, qk_w, ph)
        # Round 244: новые панели — категории слева (узкая), центральное
        # меню на весь экран с dim-фоном.
        if hasattr(self, 'categories_overlay'):
            self.categories_overlay.setGeometry(0, 0, cat_w, ph)
        if hasattr(self, 'center_menu_overlay'):
            self.center_menu_overlay.setGeometry(0, 0, pw, ph)
        if hasattr(self, '_overlay_toggle_bar'):
            # Позиционируем bar над OSD-баннером, ширина = video_frame
            self._overlay_toggle_bar.setGeometry(0, ph - 56, pw, 56)
            self._overlay_toggle_bar.raise_()

    def left_press(self):
        """Round 251: LEFT — ping-pong по стадиям. Открывает поэтапно
        и так же поэтапно закрывает (юзер: «когда он открыт влево он
        так же должен поэтапно закрываться»).

          стадии: 0=закрыто 1=каналы 2=категории 3=центр-меню
          LEFT идёт 0→1→2→3, на максимуме разворачивается 3→2→1→0.
        """
        if not hasattr(self, 'channels_overlay'):
            return
        self._sync_overlay_host()
        stage = getattr(self, '_left_stage', 0)
        direction = getattr(self, '_left_dir', 1)
        if stage >= 3:
            direction = -1
        elif stage <= 0:
            direction = 1
        stage += direction
        stage = max(0, min(3, stage))
        self._left_stage = stage
        self._left_dir = direction
        self._apply_left_stage(stage)

    def _apply_left_stage(self, stage):
        """Round 251: показывает оверлеи соответствующие стадии 0-3."""
        # Сначала прячем всё.
        for name in ('channels_overlay', 'categories_overlay',
                     'center_menu_overlay'):
            o = getattr(self, name, None)
            if o is not None and o.isVisible():
                o.hide()
        self.quick_overlay.hide()
        if stage == 1:
            # Round 278: при ПЕРВОМ открытии унаследуем фильтр из
            # ChannelsPage, дальше пользователь сам управляет.
            try:
                if not self._overlay_search.text().strip():
                    mw = self.window()
                    cp = getattr(mw, 'channels_page', None)
                    if cp is not None:
                        cp_q = (cp.search_edit.text() or "").strip()
                        if cp_q:
                            self._overlay_search.blockSignals(True)
                            self._overlay_search.setText(cp_q)
                            self._overlay_search.blockSignals(False)
            except Exception:
                pass
            self._refresh_channels_overlay()
            self._slide_in(self.channels_overlay, direction='left')
            self.channels_overlay.raise_()
            # Round 267: фокус сразу на СПИСОК (а не поиск), чтобы Up/Down
            # стрелки сразу ходили по каналам. Юзер хочет видеть курсор
            # на проигрываемом канале — это уже сделано в
            # _refresh_channels_overlay через setCurrentRow + scrollToItem.
            # Чтобы перейти к поиску — Tab или клик мышью.
            self._overlay_list.setFocus()
        elif stage == 2:
            self._refresh_categories_overlay()
            self._slide_in(self.categories_overlay, direction='left')
            self.categories_overlay.raise_()
            self._cat_list.setFocus()
            if self._cat_list.count() > 0:
                self._cat_list.setCurrentRow(0)
        elif stage == 3:
            self._update_center_menu_epg()
            self.center_menu_overlay.show()
            self.center_menu_overlay.raise_()
            # Round 256: подсветка первой кнопки через property-механизм
            # (Qt-focus ненадёжен из-за нативного VLC окна).
            try:
                self._center_menu_focused_idx = 0
                self._apply_button_focus(self._center_menu_buttons, 0)
            except Exception:
                pass
        # stage 0 = всё закрыто (уже скрыли выше).
        self._sync_overlay_host()

    def toggle_channels_overlay(self):
        """Старый API — оставлен для совместимости. Тогглит только
        список каналов (без cycle через категории / меню)."""
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
            # Round 256: подсветка первой кнопки quick-overlay чтобы
            # D-pad сразу мог по ней ходить.
            try:
                self._quick_overlay_focused_idx = 0
                self._apply_button_focus(self._quick_overlay_buttons, 0)
            except Exception:
                pass

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
        pw = self.overlay_host.width()
        ph = self.overlay_host.height()
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
        pw = self.overlay_host.width()
        ph = self.overlay_host.height()
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

    def _delegate_get_logo(self, channel):
        """Используется ChannelRowDelegate."""
        if channel is None:
            return None
        if self.logo_cache is not None and getattr(channel, 'logo_url', None):
            return self.logo_cache.get(channel.logo_url)
        return None

    def _delegate_get_epg(self, channel):
        """Возвращает (now_prog, upcoming_list[3]) для делегата."""
        if not self.epg_data or channel is None:
            return None, []
        try:
            now_prog, _ = get_now_next(self.epg_data, channel.tvg_id, channel.name)
            upcoming = get_upcoming_programmes(
                self.epg_data, channel.tvg_id, channel.name, 3)
            return now_prog, upcoming
        except Exception:
            return None, []

    def _refresh_channels_overlay(self):
        if not hasattr(self, '_overlay_list'):
            return
        # Round 278: фильтр из ChannelsPage наследуется ТОЛЬКО при
        # первом открытии overlay (см. _apply_left_stage), здесь —
        # просто берём то, что в нашей строке поиска. Раньше после
        # стирания фильтр самовозвращался из ChannelsPage. Юзер:
        # «при удалении в поле поиска в списке каналов он удаляется
        # и в конце возвращает написанное».
        q = (self._overlay_search.text() or "").strip().lower()
        self._overlay_list.setUpdatesEnabled(False)
        # Round 267: запоминаем, на какой строке overlay-списка лежит
        # currently playing канал — для setCurrentRow в конце.
        current_overlay_row = -1
        try:
            self._overlay_list.clear()
            shown = 0
            cap = 500 if not q else 10000
            for idx, ch in enumerate(self.channels or []):
                if q and q not in (ch.name or "").lower():
                    continue
                if shown >= cap:
                    break
                # Round 239: EPG и лого вычисляем ОДИН РАЗ при
                # populate, кладём в data — paint() читает готовое.
                now_p, upc = (None, [])
                if self.epg_data:
                    try:
                        now_p, _ = get_now_next(self.epg_data, ch.tvg_id, ch.name)
                        upc = get_upcoming_programmes(
                            self.epg_data, ch.tvg_id, ch.name, 3)
                    except Exception:
                        pass
                icon = None
                if self.logo_cache is not None and ch.logo_url:
                    try:
                        icon = self.logo_cache.get(ch.logo_url)
                    except Exception:
                        icon = None
                item = QListWidgetItem(f"{idx+1}. {ch.name}")
                item.setData(Qt.UserRole, idx)
                item.setData(Qt.UserRole + 1, {
                    'name': ch.name or '',
                    'group': ch.group or '',
                    'number': str(idx + 1),
                    'quality': detect_quality(ch.name or ''),
                    '_channel': ch,
                    '_now': now_p,
                    '_upcoming': upc,
                    '_icon': icon,
                })
                item.setSizeHint(QSize(0, ChannelRowDelegate.ROW_HEIGHT))
                self._overlay_list.addItem(item)
                if idx == self.current_index:
                    current_overlay_row = shown
                shown += 1
        finally:
            self._overlay_list.setUpdatesEnabled(True)
        # Round 267: фокус на текущем канале (юзер: «не фокусируется в
        # списке на тот канал который сейчас показывает»).
        if current_overlay_row >= 0:
            try:
                self._overlay_list.setCurrentRow(current_overlay_row)
                from PyQt5.QtWidgets import QAbstractItemView
                self._overlay_list.scrollToItem(
                    self._overlay_list.currentItem(),
                    QAbstractItemView.PositionAtCenter)
            except Exception:
                pass

    def _mirror_search_to_channels_page(self, text):
        """Round 278: что юзер пишет (или стирает) в overlay-поиске —
        попадает в ChannelsPage.search_edit. Иначе фильтр на вкладке
        Каналы оставался жить своей жизнью и юзер видел «один и тот же
        текст возвращается»."""
        try:
            mw = self.window()
            cp = getattr(mw, 'channels_page', None)
            if cp is None:
                return
            if cp.search_edit.text() != text:
                cp.search_edit.blockSignals(True)
                cp.search_edit.setText(text)
                cp.search_edit.blockSignals(False)
                # Дёргаем фильтр явно — мы заблокировали textChanged.
                try:
                    cp.filter_channels()
                except Exception:
                    pass
        except Exception as e:
            log_error('mirror_search', e)

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

    def _show_overlay_channel_details(self, pos):
        """Round 234: модальный popup с now/next + описанием — порт
        Android Round 221k channelDetailsPanel. Открывается правым
        кликом по строке в overlay-списке."""
        item = self._overlay_list.itemAt(pos)
        if not item:
            return
        idx = item.data(Qt.UserRole)
        if not isinstance(idx, int) or not (0 <= idx < len(self.channels)):
            return
        ch = self.channels[idx]
        now_prog, next_prog = (None, None)
        try:
            now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id, ch.name)
        except Exception:
            pass
        # Собираем тело сообщения.
        lines = [ch.name]
        if ch.group:
            lines.append(f"[{ch.group}]")
        lines.append("")
        if now_prog:
            try:
                t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
                lines.append(f"⏵ {t1}–{t2}  {now_prog.title}")
            except Exception:
                lines.append(f"⏵ {now_prog.title}")
            if now_prog.description:
                lines.append(now_prog.description[:600])
        else:
            lines.append("— нет данных о текущей программе —")
        if next_prog:
            lines.append("")
            try:
                tnext = datetime.fromtimestamp(next_prog.start).strftime('%d.%m %H:%M')
                lines.append(f"⏭ {tnext}  {next_prog.title}")
            except Exception:
                lines.append(f"⏭ {next_prog.title}")
            if next_prog.description:
                lines.append(next_prog.description[:600])
        QMessageBox.information(self, ch.name, "\n".join(lines))

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

        # Round 252: заполняем верхнюю панель.
        try:
            self.top_channel_name.setText(f"{self.current_index + 1}  {ch.name}")
            self.top_now.setText(self.osd_now.text())
            self.top_clock.setText(datetime.now().strftime('%H:%M'))
        except Exception:
            pass

        self._position_osd()
        # Round 254: НЕ показываем верхнюю и нижнюю панели — юзер:
        # «верхняя панель вообще не нужна, нижняя — в меню по LEFT».
        # Оставляем только обновление центр-меню и часов.
        try:
            self._update_center_menu_epg()
        except Exception:
            pass
        # Round 254: banner_timer не нужен — нет автоскрываемых панелей.

    def _update_center_menu_epg(self):
        """Round 254: помещаем EPG-инфо текущего канала в центр-меню
        (нижняя инфо-панель убрана, юзер: «нижняя должна быть в меню
        которая открывается в конце при нажатии в лево»)."""
        if not hasattr(self, '_center_menu_info'):
            return
        try:
            if not self.channels or self.current_index >= len(self.channels):
                self._center_menu_info.setText("")
                return
            ch = self.channels[self.current_index]
            now_prog, next_prog = get_now_next(
                self.epg_data, ch.tvg_id, ch.name)
            lines = [f"📺  {ch.name}"]
            if now_prog:
                t1 = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                t2 = datetime.fromtimestamp(now_prog.end).strftime('%H:%M')
                lines.append(f"▶  {t1}–{t2}   {now_prog.title}")
            if next_prog:
                tn = datetime.fromtimestamp(next_prog.start).strftime('%H:%M')
                lines.append(f"⏭  {tn}   {next_prog.title}")
            self._center_menu_info.setText("\n".join(lines))
        except Exception:
            pass

    def mouseMoveEvent(self, event):
        """Round 245: любое движение мыши в плеере оживляет
        info-banner — как Android при нажатии любой клавиши."""
        try:
            if self.channels and not self.osd_banner.isVisible():
                self._show_channel_banner()
            else:
                self._banner_timer.start()  # продлеваем таймер
        except Exception:
            pass
        super().mouseMoveEvent(event)

    def _hide_banner(self):
        if hasattr(self, 'osd_banner'):
            self.osd_banner.hide()
        # Round 252: верхняя панель скрывается вместе с баннером.
        if hasattr(self, 'top_panel'):
            self.top_panel.hide()

    def _on_pip_clicked(self):
        mw = self.window()
        if hasattr(mw, 'toggle_pip_mode'):
            mw.toggle_pip_mode()

    def init_vlc(self):
        if not HAS_VLC:
            return
        try:
            # Round 284: тюним VLC под live-IPTV как другие плееры
            # (Kodi, OttPlayer, Ace Stream) — больший буфер, отключение
            # лишних оверхедов, аппаратный декодер по умолчанию.
            # Юзер: «все каналы зависают а в других программах всё
            # работает чётко без запинаний».
            args = [
                '--no-xlib',
                '--no-video-title-show',       # без мигания title при переключении
                '--no-stats',                  # отключаем сбор stats
                '--no-osd',                    # OSD-текст рисуем сами
                '--no-snapshot-preview',
                '--no-sub-autodetect-file',    # не ищем сабы для live
                '--clock-jitter=0',            # лучше audio/video sync
                '--clock-synchro=0',           # без жёсткой синхронизации
                # Round 284: 6 секунд буфера — как Android ExoPlayer
                # «normal» режим (DefaultLoadControl 6000/18000/200/1500).
                # Юзер: «в андроид версии всё работает хорошо». VLC
                # дефолт 1000мс совсем мало для live-IPTV.
                '--live-caching=6000',
                '--network-caching=6000',
                '--file-caching=6000',
                # --sout-mux-caching не нужен (только для streaming-out).
                # Звук: высокий приоритет и без resamplera-по-умолчанию,
                # чтобы не было фоновых щелчков.
                '--audio-resampler=soxr',
                '--audio-time-stretch',
            ]
            # Hardware decode: по умолчанию `any` (VLC сам выберет d3d11va
            # / dxva2). Юзер может отключить через настройки.
            if getattr(self.config, 'hardware_decode', True):
                args += ['--avcodec-hw=any']
            else:
                args += ['--avcodec-hw=none']
            # Windows: предпочитаем direct3d11 video output — современный
            # драйвер, гораздо лучше чем устаревший legacy GDI.
            if sys.platform == "win32":
                args += ['--vout=direct3d11']
            # Audio output backend (по умолчанию auto).
            ao = getattr(self.config, 'audio_output', '')
            if ao:
                args += [f'--aout={ao}']
            # Custom HTTP user-agent для стримов.
            ua = getattr(self.config, 'user_agent', '')
            if ua:
                args += [f'--http-user-agent={ua}']
            self.vlc_instance = vlc.Instance(*args)
            self.player = self.vlc_instance.media_player_new()
            log_info('vlc', f"instance ok, args={args}")
        except Exception as e:
            log_error('init_vlc', e, extra=f"args={args}")
            self.vlc_instance = None
            self.player = None

    def play_channel(self, index, channels, epg_data):
        # Save state for previously-playing channel before switching
        self._save_current_channel_state()

        self.channels = channels
        self.current_index = index
        self.epg_data = epg_data
        ch = channels[index]
        log_info('play', f"#{index+1}/{len(channels)} {ch.name!r} url={ch.url[:80]}")
        self.channel_name_label.setText(ch.name)
        self.channel_number_label.setText(f"{index + 1} / {len(channels)}")
        self.update_fav_btn()
        self.update_epg_display()
        # Round 280: канальная OSD-карта в стиле референса.
        self.show_channel_osd(ch, index, len(channels))

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
            except Exception as e:
                log_error('restore_channel_state', e, extra=f"url={ch.url[:80]}")

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
        self.config.save_async()
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
        # Round 281: VLC audio_set_track блокирует 21+ сек на сложных
        # стримах — watchdog поймал. Перенос в фон, как play_url и
        # cycle_audio_track в Round 279.
        if not self.player:
            return
        try:
            import threading as _th
            player = self.player
            _th.Thread(target=lambda: _safe_call(player.audio_set_track, track_id),
                       daemon=True, name='vlc-set-aud').start()
        except Exception as e:
            log_error('_maybe_set_audio_track', e)

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
        self.config.save_async()  # Round 260: фон, не блокируем переключение

    def _ensure_vlc_then_play(self):
        """Round 283: фоновая инициализация VLC + откладываемый play_url.
        Юзер видит «Подключаю канал…» 20-30 сек вместо фриза UI."""
        try:
            log_info('vlc', "lazy init from play_url (background)")
            self.init_vlc()
            url = getattr(self, '_pending_play_url', None)
            if url and self.player:
                # Возвращаемся в GUI-нитку для второй фазы (set_media и т.п.).
                QTimer.singleShot(0, lambda: self.play_url(url))
        except Exception as e:
            log_error('_ensure_vlc_then_play', e)
        finally:
            self._ensure_running = False

    def play_url(self, url):
        # Round 283: ВСЁ — в фон, включая первую инициализацию VLC.
        # Юзер кликнул канал ДО окончания warm-up'а → раньше play_url
        # делал init_vlc СИНХРОННО на main thread, watchdog ловил
        # 22 секунды фриза в libvlc_new. Теперь ставим в очередь
        # _pending_play_url и стартуем _ensure_vlc_then_play в нитке.
        if not HAS_VLC:
            log_warn('play_url', "no VLC player available")
            self.epg_bar.setText("VLC not installed.")
            return
        self._pending_play_url = url
        try:
            self.show_mini_osd("⏳  Подключаю канал…")
        except Exception:
            pass
        if not self.player:
            # VLC ещё не готов — запускаем init+play в одной нитке.
            # Защита от двойного init: если ensure-нитка уже бежит,
            # просто обновляем _pending_play_url (последний клик
            # выиграет), новой нитки не плодим.
            if getattr(self, '_ensure_running', False):
                log_info('vlc', "ensure already running, pending updated")
                return
            try:
                import threading as _th
                self._ensure_running = True
                _th.Thread(target=self._ensure_vlc_then_play,
                           daemon=True, name='vlc-ensure').start()
            except Exception as e:
                self._ensure_running = False
                log_error('play_url.ensure', e)
            return
        # Round 279: ВСЁ переключение канала уходит в фоновый поток.
        # Watchdog поймал блок 16.7 сек в `libvlc_media_player_set_media`
        # (внутри stop() ждёт умирающий network thread). Юзеру это
        # выглядит как полный фриз UI на 10-16 сек.
        try:
            import threading as _th
            # Берём HWND ДО входа в нитку — winId() должен вызываться
            # только из GUI-потока.
            hwnd = int(self.video_frame.winId()) if sys.platform == "win32" else None
            xwin = int(self.video_frame.winId()) if sys.platform == "linux" else None
            nsobj = int(self.video_frame.winId()) if sys.platform == "darwin" else None
            volume = int(self.config.volume)
            aspect = self.ASPECT_RATIOS[self._aspect_idx]
            speed = float(self.SPEED_VALUES[self._speed_idx])
            net_cache = int(self.config.network_caching_ms)
            prev_media = self.current_media
            vlc_inst = self.vlc_instance
            player = self.player

            def _swap():
                try:
                    media = vlc_inst.media_new(url)
                    media.add_option(f':network-caching={net_cache}')
                    # set_media внутри STOP'ает текущий поток — это и
                    # есть тот самый 16 сек блок.
                    player.set_media(media)
                    self.current_media = media
                    if prev_media is not None:
                        try:
                            prev_media.release()
                        except Exception as e:
                            log_error('media_release', e)
                    if hwnd is not None:
                        player.set_hwnd(hwnd)
                    elif xwin is not None:
                        player.set_xwindow(xwin)
                    elif nsobj is not None:
                        player.set_nsobject(nsobj)
                    player.audio_set_volume(volume)
                    try:
                        player.video_set_aspect_ratio(aspect.encode() if aspect else None)
                    except Exception:
                        pass
                    try:
                        player.set_rate(speed)
                    except Exception as e:
                        log_error('set_rate', e)
                    player.play()
                    log_info('play', f"set_media done for {url[:80]}")
                except Exception as e:
                    log_error('play_url.bg', e, extra=f"url={url[:80]}")

            _th.Thread(target=_swap, daemon=True, name='vlc-swap').start()
            # UI обновляется СРАЗУ — юзер видит, что клик принят.
            self.btn_play.setText("Pause")
            self.config.last_channel_url = url
            self.config.save_async()
        except Exception as e:
            log_error('play_url', e, extra=f"url={url[:80]}")

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
            # Round 280: канальная OSD-карта при пролистывании ↑/↓.
            self.show_channel_osd(ch, self._pending_index, len(self.channels))
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
        # Round 279: мини-OSD с уровнем громкости.
        bars = max(0, min(10, int(val / 10)))
        bar_str = "█" * bars + "░" * (10 - bars)
        self.show_mini_osd(f"🔊  {val}%   {bar_str}")

    def toggle_favorite(self):
        if not self.channels or self.current_index >= len(self.channels):
            return
        url = self.channels[self.current_index].url
        if url in self.config.favorites:
            self.config.favorites.discard(url)
        else:
            self.config.favorites.add(url)
        self.config.save_async()
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
        now_str = datetime.now().strftime('%H:%M')
        self.clock_label.setText(now_str)
        # Round 246: тот же текст в персистентные часы поверх видео.
        try:
            if hasattr(self, 'persistent_clock'):
                self.persistent_clock.setText(now_str)
                self.persistent_clock.adjustSize()
                self._position_persistent_clock()
        except Exception:
            pass

    def _position_persistent_clock(self):
        """Round 246: позиция часов читается из config.clock_position
        — top_right / top_left / bottom_right / bottom_left / off."""
        try:
            if not hasattr(self, 'persistent_clock'):
                return
            pos = getattr(self.config, 'clock_position', 'top_right')
            if pos == 'off':
                self.persistent_clock.hide()
                return
            self.persistent_clock.show()
            pw = self.overlay_host.width()
            ph = self.overlay_host.height()
            cw = self.persistent_clock.width()
            ch = self.persistent_clock.height()
            pad = 14
            if pos == 'top_left':
                x, y = pad, 10
            elif pos == 'bottom_right':
                x, y = pw - cw - pad, ph - ch - 10
            elif pos == 'bottom_left':
                x, y = pad, ph - ch - 10
            else:  # top_right
                x, y = pw - cw - pad, 10
            self.persistent_clock.setGeometry(x, y, cw, ch)
            self.persistent_clock.raise_()
        except Exception:
            pass

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
        # Round 279: VLC `audio_get_track_description` / `audio_set_track`
        # могут блокировать на проблемных стримах. Уносим в фон.
        if not self.player:
            return
        try:
            import threading as _th
            player = self.player
            def _bg():
                try:
                    tracks = player.audio_get_track_description() or []
                    usable = [t for t in tracks if t and t[0] >= 0]
                    if len(usable) < 2:
                        return
                    cur = player.audio_get_track()
                    ids = [t[0] for t in usable]
                    try:
                        pos = ids.index(cur)
                    except ValueError:
                        pos = -1
                    nxt = usable[(pos + 1) % len(usable)]
                    player.audio_set_track(nxt[0])
                    log_info('vlc',
                             f"audio track → {nxt[0]} ({len(usable)} total)")
                except Exception as e:
                    log_error('cycle_audio_track.bg', e)
            _th.Thread(target=_bg, daemon=True, name='vlc-audio').start()
        except Exception as e:
            log_error('cycle_audio_track', e)

    # --- Fullscreen ---

    def toggle_fullscreen(self):
        w = self.window()
        if w is None:
            return
        if w.isFullScreen():
            w.showNormal()
            if hasattr(w, '_apply_fullscreen_chrome'):
                w._apply_fullscreen_chrome(False)
        else:
            w.showFullScreen()
            if hasattr(w, '_apply_fullscreen_chrome'):
                w._apply_fullscreen_chrome(True)

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
        self.config.save_async()
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
        self._title = QLabel(t('recent'))
        self._title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(self._title)
        header.addStretch()
        self.count_label = QLabel("")
        self.count_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.count_label)
        self._btn_clear = QPushButton(t('clear'))
        self._btn_clear.clicked.connect(self._clear)
        header.addWidget(self._btn_clear)
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
        self.config.save_async()
        self.refresh(self.channels, self.epg_data)

    def retranslate_ui(self):
        try:
            if hasattr(self, '_title'):
                self._title.setText(t('recent'))
            if hasattr(self, '_btn_clear'):
                self._btn_clear.setText(t('clear'))
        except Exception as e:
            log_error('RecentPage.retranslate_ui', e)

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        if not self.isVisible():
            return  # Round 262: пропускаем когда страница не видна
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
    epg_refresh_requested = pyqtSignal()  # Round 257: ручное обновление EPG

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

        # Round 257/259: авто-обновление EPG. Юзер: «при просмотре
        # фул хд каналов и возможно идёт обновление тв программы»
        # — поэтому увеличили интервал до 4 часов (Android default)
        # и добавили проверку «активен ли плеер» в _on_epg_refresh
        # MainWindow.
        self._auto_epg = QTimer(self)
        self._auto_epg.setInterval(4 * 60 * 60 * 1000)
        self._auto_epg.timeout.connect(self.epg_refresh_requested.emit)

        if self.logo_cache is not None:
            self.logo_cache.logo_ready.connect(self._refresh_logos)

    def _on_refresh_clicked(self):
        self.status.setText("Обновляю EPG…")
        self.epg_refresh_requested.emit()

    def showEvent(self, event):
        # Round 262: _tick перестраивает весь guide_list (3000+ каналов)
        # с get_now_next по каждому — это дорого. Гоняем его ТОЛЬКО
        # когда страница на экране. Иначе FullHD на PlayerPage лагает.
        super().showEvent(event)
        try:
            if self.channels and not self._tick.isActive():
                self._tick.start()
            self.refresh_list()  # сразу освежим entries при заходе
        except Exception as e:
            log_error('TvGuidePage.showEvent', e)

    def hideEvent(self, event):
        super().hideEvent(event)
        try:
            self._tick.stop()
        except Exception as e:
            log_error('TvGuidePage.hideEvent', e)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        header = QHBoxLayout()
        self._title = QLabel(t('tv_guide'))
        self._title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(self._title)
        header.addStretch()
        # Round 257: ручная кнопка обновления EPG (юзер: «нет ручного или
        # авто обновления тв гида»). Эмитим refresh_requested — MainWindow
        # триггерит LoadEpgThread по тем же source-ам, что были при
        # загрузке плейлиста.
        self.btn_refresh = QPushButton("↻ " + t('updates'))
        self.btn_refresh.setStyleSheet(
            "QPushButton { background-color: #7C6CF7; color: white;"
            " padding: 6px 14px; border-radius: 6px; font-size: 13px; }"
            "QPushButton:hover { background-color: #9485FA; }")
        self.btn_refresh.clicked.connect(self._on_refresh_clicked)
        header.addWidget(self.btn_refresh)
        self.status = QLabel("")
        self.status.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.status)
        layout.addLayout(header)

        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText(t('search_channels'))
        self.search_edit.setClearButtonEnabled(True)  # Round 278
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
        # Round 262: _tick стартуется только когда страница реально
        # видна — иначе он зря перебирал 3639 каналов раз в минуту,
        # пока юзер на плеере. _auto_epg тикает каждые 4ч (throttle в
        # MainWindow._on_epg_refresh), без проблем.
        if self.isVisible() and self.channels and not self._tick.isActive():
            self._tick.start()
        if not self._auto_epg.isActive():
            self._auto_epg.start()

    def refresh_list(self):
        # Round 262: ОЧЕНЬ дорогая операция — обход 3000+ каналов с
        # get_now_next + make_letter_tile_icon. Раньше вызывалась
        # _tick'ом каждые 60 сек ДАЖЕ когда юзер на PlayerPage и смотрит
        # FullHD. Это и было основной причиной зависаний. Теперь — только
        # когда страница видна.
        if not self.isVisible():
            return
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

    def retranslate_ui(self):
        try:
            if hasattr(self, '_title'):
                self._title.setText(t('tv_guide'))
            if hasattr(self, 'btn_refresh'):
                self.btn_refresh.setText("↻ " + t('updates'))
            if hasattr(self, 'search_edit'):
                self.search_edit.setPlaceholderText(t('search_channels'))
        except Exception as e:
            log_error('TvGuidePage.retranslate_ui', e)

    def _refresh_logos(self):
        if self.logo_cache is None:
            return
        if not self.isVisible():
            return  # Round 262: пропускаем когда TvGuidePage скрыт
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
        # Round 242: расширенный список языков как Android LocaleHelper.
        # 'system' = автодетект из локали ОС. Реально перевод есть для
        # ru/en/uk/az; остальные показываются по дефолту (ru).
        for code, label in SUPPORTED_LANGUAGES:
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

        # Round 247: цветовая тема — как Android (5 вариантов).
        theme_row = QHBoxLayout()
        theme_row.addWidget(QLabel("Цветовая тема:"))
        self.theme_combo = QComboBox()
        for code, label in (
            ('default', '🟣 Фиолетовый (по умолчанию)'),
            ('blue',    '🔵 Синий'),
            ('green',   '🟢 Зелёный'),
            ('orange',  '🟠 Оранжевый'),
            ('red',     '🔴 Красный'),
        ):
            self.theme_combo.addItem(label, code)
        cur_theme = getattr(self.config, 'theme_color', 'default')
        for i in range(self.theme_combo.count()):
            if self.theme_combo.itemData(i) == cur_theme:
                self.theme_combo.setCurrentIndex(i)
                break
        self.theme_combo.currentIndexChanged.connect(self._save_theme)
        theme_row.addWidget(self.theme_combo, 1)
        layout.addLayout(theme_row)

        # Round 246: позиция персистентных часов в плеере.
        clock_row = QHBoxLayout()
        clock_row.addWidget(QLabel("Часы в плеере:"))
        self.clock_combo = QComboBox()
        for code, label in (
            ('top_right',    'Верх-право'),
            ('top_left',     'Верх-лево'),
            ('bottom_right', 'Низ-право'),
            ('bottom_left',  'Низ-лево'),
            ('off',          'Скрыть'),
        ):
            self.clock_combo.addItem(label, code)
        cur_pos = getattr(self.config, 'clock_position', 'top_right')
        for i in range(self.clock_combo.count()):
            if self.clock_combo.itemData(i) == cur_pos:
                self.clock_combo.setCurrentIndex(i)
                break
        self.clock_combo.currentIndexChanged.connect(self._save_clock_position)
        clock_row.addWidget(self.clock_combo, 1)
        layout.addLayout(clock_row)

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
        # Round 263: показываем текущую версию ПРЯМО в Updates-секции
        # (юзер: «сам тоже не пишет какая у него сейчас версия»). До этого
        # версия была только в маленькой подписи внизу страницы.
        cur_ver_label = QLabel(
            f"Установлено: TVViewer v{WIN_VERSION_NAME} build {WIN_VERSION_CODE}")
        cur_ver_label.setStyleSheet(
            "color: white; font-size: 13px; font-weight: bold;"
            " padding: 4px 0;")
        layout.addWidget(cur_ver_label)
        upd_row = QHBoxLayout()
        self.btn_check_updates = QPushButton("Check for updates")
        self.btn_check_updates.clicked.connect(self._check_updates)
        upd_row.addWidget(self.btn_check_updates)
        # Round 263: дефолтный статус — показываем что мы знаем версию
        # ДО клика на «Check». Иначе юзер думает «ничего не пишет».
        self.update_status = QLabel(
            f"Текущий build {WIN_VERSION_CODE}. Нажмите «Check for updates».")
        self.update_status.setStyleSheet(
            f"color: {COLORS['text_secondary']}; font-size: 12px;")
        self.update_status.setWordWrap(True)
        upd_row.addWidget(self.update_status, 1)
        layout.addLayout(upd_row)
        # Round 263: ручной fallback — открыть страницу релизов в
        # браузере. Если auto-check молчит / network падает / SSL —
        # юзер всегда может зайти руками и скачать TVViewer-update.exe.
        btn_releases = QPushButton("Открыть страницу релизов на GitHub")
        btn_releases.clicked.connect(self._open_releases_page)
        layout.addWidget(btn_releases)

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
        self.config.save_async()
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
        self.config.save_async()
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

    def _save_theme(self, _idx):
        """Round 247: меняем цветовую тему — apply_theme +
        re-применяем stylesheet к QApplication. Без перезапуска."""
        code = self.theme_combo.currentData()
        if not code or code == getattr(self.config, 'theme_color', 'default'):
            return
        self.config.theme_color = code
        self.config.save_async()
        try:
            apply_theme(code)
            QApplication.instance().setStyleSheet(STYLESHEET)
        except Exception:
            pass
        self.settings_changed.emit()

    def _save_clock_position(self, _idx):
        code = self.clock_combo.currentData()
        if not code:
            return
        self.config.clock_position = code
        self.config.save_async()
        self.settings_changed.emit()

    def _save_volume(self, v):
        self.config.volume = int(v)
        self.config.save_async()
        self.settings_changed.emit()

    def _save_sleep(self, v):
        self.config.sleep_timer_minutes = int(v)
        self.config.save_async()

    def _save_autoplay(self, checked):
        self.config.autoplay_last = bool(checked)
        self.config.save_async()

    def _save_fullscreen(self, checked):
        self.config.remember_fullscreen = bool(checked)
        self.config.save_async()

    def _save_always_on_top(self, checked):
        self.config.always_on_top = bool(checked)
        self.config.save_async()
        self.settings_changed.emit()

    def _save_hwdec(self, checked):
        self.config.hardware_decode = bool(checked)
        self.config.save_async()

    def _save_aout(self, _idx):
        self.config.audio_output = self.aout_combo.currentData() or ""
        self.config.save_async()

    def _save_ua(self):
        self.config.user_agent = self.ua_edit.text().strip()
        self.config.save_async()

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
        self.config.save_async()
        self.epg_input.clear()
        self._refresh_epg_list()
        self.settings_changed.emit()

    def _remove_epg_url(self):
        row = self.epg_list.currentRow()
        if row < 0:
            return
        try:
            self.config.epg_urls.pop(row)
            self.config.save_async()
            self._refresh_epg_list()
            self.settings_changed.emit()
        except IndexError:
            pass

    def _clear_recent(self):
        self.config.recent_urls = []
        self.config.save_async()
        self.settings_changed.emit()

    def _clear_per_channel_state(self):
        self.config.per_channel_state = {}
        self.config.save_async()

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
    def _open_releases_page(self):
        """Round 263: ручной fallback на случай когда auto-check не
        достучался до GitHub API (SSL в PyInstaller / firewall / прокси)."""
        try:
            import webbrowser
            webbrowser.open(
                'https://github.com/donmax76/iptv/releases?q=win-v5.4&expanded=true')
        except Exception as e:
            log_error('open_releases_page', e)

    def _check_updates(self):
        log_info('update', f"manual check clicked, current build={WIN_VERSION_CODE}")
        self.btn_check_updates.setEnabled(False)
        self.update_status.setText("Checking…")
        self._upd_thread = UpdateCheckThread(self)
        self._upd_thread.finished.connect(self._on_update_check)
        self._upd_thread.start()

    def _on_update_check(self, info):
        self.btn_check_updates.setEnabled(True)
        cur = WIN_VERSION_CODE
        if not isinstance(info, dict):
            self.update_status.setText(
                f"Текущий build {cur}. GitHub недоступен. См. tvviewer.log.")
            log_warn('update', "manual check: info is None (network or parse error)")
            # Round 263: даже когда сеть не отдала — показываем юзеру
            # его текущую версию и подсказываем как смотреть лог.
            QMessageBox.information(
                self, "Updates",
                f"Текущая установленная версия:\n"
                f"TVViewer v{WIN_VERSION_NAME} build {cur}\n\n"
                "Не удалось связаться с GitHub (нет сети / firewall /\n"
                "блокировка SSL в этой сборке PyInstaller).\n\n"
                "Подробности — в файле tvviewer.log (нажмите\n"
                "«Open log folder» в этой же вкладке Настроек).")
            return
        latest = int(info.get('code', 0))
        log_info('update', f"check result: latest={latest} current={cur} url={info.get('url','')}")
        if latest <= cur:
            self.update_status.setText(
                f"У вас последняя версия — build {cur}. На GitHub: {latest}.")
            QMessageBox.information(
                self, "Updates",
                f"You're on the latest version.\n\n"
                f"Installed: TVViewer v{WIN_VERSION_NAME} build {cur}\n"
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
        # Round 260: если release ещё не успел получить TVViewer-update.exe
        # (старая сборка до Round 257), URL может указывать на ZIP или
        # на страницу релиза. Открываем в браузере с понятным сообщением.
        if not info.get('has_exe'):
            log_warn('update', f"release has no .exe asset, opening url={url}")
            QMessageBox.information(
                self, "Updates",
                f"Build {latest} доступен, но в этом релизе ещё нет\n"
                "TVViewer-update.exe (это есть только в свежих сборках,\n"
                "Round 257+).\n\n"
                "Открою страницу релиза — скачайте полный ZIP вручную\n"
                "и распакуйте поверх установленной папки.")
            try:
                import webbrowser; webbrowser.open(url)
            except Exception as e:
                log_error('update.webbrowser', e)
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
        # Round 269: ZIP → распаковать в install dir; EXE → swap (legacy).
        # Юзер видел «Failed to load Python DLL» от --onefile EXE-update —
        # теперь предпочитаем ZIP (надёжно, как Android APK).
        ok = False
        if path.lower().endswith('.zip'):
            ok = _extract_zip_and_restart(path)
        else:
            ok = _swap_self_and_restart(path)
        if ok:
            # Round 272: ХАРД-выход. QApplication.quit() асинхронный,
            # background QThread'ы держат файл-блокировку на TVViewer.exe,
            # и Expand-Archive не может перезаписать. Юзер видел «после
            # закрывается и открывается также старая версия». os._exit
            # снимает блокировку моментально; VBS-watcher уже стартует
            # новый EXE после Wait-Process.
            QApplication.quit()
            os._exit(0)
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
            self.config.save_async()
            self.settings_changed.emit()

    def _reset_settings(self):
        reply = QMessageBox.question(
            self, "Reset settings",
            "Reset all settings to defaults? Playlists and favorites are kept.",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply != QMessageBox.Yes:
            return
        self.config.volume = 80
        self.config.network_caching_ms = 6000  # Round 284
        self.config.autoplay_last = False
        self.config.remember_fullscreen = False
        self.config.sleep_timer_minutes = 0
        self.config.always_on_top = False
        self.config.hardware_decode = True
        self.config.audio_output = ""
        self.config.channel_sort = "default"
        self.config.save_async()
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
    def __init__(self, progress_cb=None):
        super().__init__()
        # Round 255: progress_cb(percent, text) — колбек для splash.
        # Вызывается между шагами init_ui чтобы юзер видел движение
        # и анимацию прогресс-бара пока строятся страницы.
        self._progress_cb = progress_cb or (lambda *a, **kw: None)
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
        # Round 248: application-level event filter — ловим хоткеи даже
        # когда фокус забрало нативное VLC-видео-окно. Без этого в
        # плеере не работали стрелки / Space / цифры.
        try:
            QApplication.instance().installEventFilter(self)
        except Exception as e:
            log_error('installEventFilter', e)
        # Round 259: следим за активностью приложения. overlay_host
        # (top-level Tool с WindowStaysOnTopHint) рисует часы/кнопки
        # «Каналы»/«Настройки» поверх ВСЕХ окон, включая другие
        # приложения, когда юзер alt-tab'ает из TVViewer. Прячем
        # overlay_host вместе с потерей фокуса; возвращаем когда
        # окно снова активно и юзер на PlayerPage.
        try:
            QApplication.instance().applicationStateChanged.connect(
                self._on_app_state_changed)
        except Exception as e:
            log_error('applicationStateChanged.connect', e)
        # Silent auto-check for new build at startup (only for frozen EXE)
        QTimer.singleShot(3000, self._auto_check_updates)
        # Round 273: прогреваем VLC в background-нитке через 800мс после
        # старта. Если юзер кликнет канал ДО окончания прогрева — play_url
        # сам сделает синхронный init_vlc (всё равно лучше чем 14 сек
        # фриз на старте). vlc.Instance / media_player_new — нативные
        # C-вызовы без Python-объектов, GIL не нужен и thread-safe.
        QTimer.singleShot(800, self._warm_vlc_async)

    def _warm_vlc_async(self):
        try:
            import threading as _th
            def _worker():
                try:
                    log_info('vlc', "warming up in background")
                    self.player_page.init_vlc()
                    log_info('vlc', "warm-up done")
                except Exception as e:
                    log_error('vlc_warmup', e)
            _th.Thread(target=_worker, daemon=True, name='vlc-warm').start()
        except Exception as e:
            log_error('_warm_vlc_async', e)

    def _on_app_state_changed(self, state):
        """Round 259: alt-tab → прячем overlay_host чтобы наши часы и
        кнопки overlay-toggle не светились поверх других приложений.
        Возврат — показываем (если на PlayerPage)."""
        try:
            from PyQt5.QtCore import Qt as _Qt
            page = self.stack.currentWidget()
            if not isinstance(page, PlayerPage):
                return
            host = getattr(page, 'overlay_host', None)
            if host is None:
                return
            if state == _Qt.ApplicationActive:
                page._sync_overlay_host()
                host.show()
                host.raise_()
            else:
                host.hide()
        except Exception as e:
            log_error('_on_app_state_changed', e)

    def changeEvent(self, event):
        """Round 261: belt-and-braces — ловим WindowState/Activation
        events MainWindow и синхронизируем overlay_host. applicationState-
        Changed на Windows иногда не срабатывает при minimize-to-tray
        или при кликe на другой app без полного alt-tab."""
        try:
            from PyQt5.QtCore import QEvent as _QE
            if event.type() in (_QE.WindowStateChange,
                                _QE.ActivationChange,
                                _QE.WindowDeactivate,
                                _QE.WindowActivate):
                page = self.stack.currentWidget()
                if isinstance(page, PlayerPage):
                    host = getattr(page, 'overlay_host', None)
                    if host is not None:
                        active = self.isActiveWindow() and not self.isMinimized()
                        if active:
                            page._sync_overlay_host()
                            host.show()
                            host.raise_()
                        else:
                            host.hide()
        except Exception as e:
            log_error('changeEvent', e)
        super().changeEvent(event)

    def moveEvent(self, event):
        """Round 276: при drag-е окна оверлей должен лететь ВМЕСТЕ
        с окном. Раньше дебаунс 30мс давал отставание. Теперь:
        моментально дёргаем lightweight sync (только setGeometry на
        overlay_host), а полную перерасстановку детей делает обычный
        _overlay_sync_timer тиком 150мс."""
        super().moveEvent(event)
        self._fast_overlay_track()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._fast_overlay_track()

    def _fast_overlay_track(self):
        try:
            page = self.stack.currentWidget()
            if not isinstance(page, PlayerPage):
                return
            host = getattr(page, 'overlay_host', None)
            vf = getattr(page, 'video_frame', None)
            if host is None or vf is None or not vf.isVisible():
                return
            tl = vf.mapToGlobal(vf.rect().topLeft())
            host.setGeometry(tl.x(), tl.y(), vf.width(), vf.height())
        except Exception:
            pass

    def eventFilter(self, obj, event):
        """Round 248: глобальный перехват клавиш. Когда играет VLC, его
        HWND забирает фокус и keyPressEvent в MainWindow не вызывается.
        Здесь ловим KeyPress на уровне приложения. Если активна
        текстовая строка ввода (поиск / диалог) — НЕ перехватываем,
        чтобы юзер мог печатать."""
        try:
            if event.type() == event.KeyPress:
                fw = QApplication.focusWidget()
                # Не мешаем вводу текста.
                if isinstance(fw, QLineEdit):
                    return False
                key = event.key()
                # Round 277: type-to-search для overlay channels —
                # event.text() даёт реальный введённый символ независимо
                # от раскладки (русский, азербайджанский, ...). Round 276
                # ограничивался Qt.Key_A..Z = только латиница.
                cur = self.stack.currentWidget()
                if (isinstance(cur, PlayerPage)
                        and hasattr(cur, 'channels_overlay')
                        and cur.channels_overlay.isVisible()
                        and hasattr(cur, '_overlay_search')):
                    txt = event.text()
                    # printable & не служебная клавиша → в строку поиска
                    if (key == Qt.Key_Backspace
                            or (txt and txt.isprintable() and txt != '\r'
                                and txt != '\t' and txt != '\x1b')):
                        se = cur._overlay_search
                        se.setFocus()
                        if key == Qt.Key_Backspace:
                            se.setText(se.text()[:-1])
                        else:
                            se.setText(se.text() + txt)
                        return True
                if self._handle_key(key):
                    return True
        except Exception as e:
            log_error('eventFilter', e)
        return super().eventFilter(obj, event)

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
        # Round 275: НЕ показываем модальный диалог на старте — он
        # блокировал main thread и watchdog логировал его как
        # «main thread blocked 12s». Теперь просто пишем в лог +
        # подсветка в Settings; юзер сам нажмёт «Check for updates»,
        # если захочет обновиться.
        if not isinstance(info, dict):
            return
        latest = int(info.get('code', 0))
        if latest <= WIN_VERSION_CODE:
            return
        log_info('update',
                 f"new build {latest} available (current {WIN_VERSION_CODE}). "
                 f"User can install via Settings → Check for updates.")
        try:
            if hasattr(self, 'settings_page') and hasattr(self.settings_page, 'update_status'):
                self.settings_page.update_status.setText(
                    f"Доступен build {latest}. "
                    f"Установлен build {WIN_VERSION_CODE}. "
                    f"Нажмите «Check for updates».")
        except Exception as e:
            log_error('startup_update_notify', e)

    def init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        # Content area
        self.stack = QStackedWidget()

        # Round 255: между каждой страницей вызываем progress_cb +
        # processEvents — Qt прокачивает таймеры/перерисовки splash,
        # юзер видит, что программа не висит.
        self._progress_cb(40, "Плейлисты…")
        self.playlists_page = PlaylistsPage(self.config)
        self.playlists_page.playlist_selected.connect(self.load_playlist)
        self.stack.addWidget(self.playlists_page)

        self._progress_cb(48, "Каналы…")
        self.channels_page = ChannelsPage(self.config, self.logo_cache)
        self.channels_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.channels_page)

        self._progress_cb(55, "Избранное…")
        self.favorites_page = FavoritesPage(self.config, self.logo_cache)
        self.favorites_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.favorites_page)

        self._progress_cb(62, "Плеер…")
        self.player_page = PlayerPage(self.config, self.logo_cache)
        self.player_page.back_requested.connect(self.show_channels)
        self.stack.addWidget(self.player_page)

        self._progress_cb(70, "Настройки…")
        self.settings_page = SettingsPage(self.config)
        self.settings_page.settings_changed.connect(self._on_settings_changed)
        self.stack.addWidget(self.settings_page)

        self._progress_cb(76, "Программа передач…")
        self.tv_guide_page = TvGuidePage(self.config, self.logo_cache)
        self.tv_guide_page.channel_play.connect(self.play_channel)
        self.tv_guide_page.epg_refresh_requested.connect(self._on_epg_refresh)
        self.stack.addWidget(self.tv_guide_page)

        self._progress_cb(82, "Недавние…")
        self.recent_page = RecentPage(self.config, self.logo_cache)
        self.recent_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.recent_page)

        self._progress_cb(88, "Главный экран…")
        # Round 241: HomePage — добавляем в конец чтобы не сдвинуть
        # индексы существующих страниц. Index = 7.
        self.home_page = HomePage(self.config)
        self.home_page.live_requested.connect(self._on_home_live)
        self.home_page.playlists_requested.connect(lambda: self.switch_page(0))
        self.stack.addWidget(self.home_page)
        self._home_index = self.stack.count() - 1

        main_layout.addWidget(self.stack, 1)

        # Bottom navigation bar
        nav_bar = QWidget()
        self.nav_bar = nav_bar  # Round 251: ref для fullscreen-скрытия
        nav_bar.setStyleSheet(f"background-color: {COLORS['surface']};")
        nav_bar.setFixedHeight(52)
        nav_layout = QHBoxLayout(nav_bar)
        nav_layout.setContentsMargins(0, 0, 0, 0)
        nav_layout.setSpacing(0)

        # Round 233/235/241: nav-кнопки с translation-ключом + Material
        # Unicode-иконкой. Home (index 7) добавлен первым.
        self.nav_buttons = []
        nav_items = [
            ('home',      getattr(self, '_home_index', 7), '🏠'),
            ('playlists', 0, '📋'),
            ('channels',  1, '📺'),
            ('tv_guide',  5, '📅'),
            ('favorites', 2, '★'),
            ('recent',    6, '⏱'),
            ('settings',  4, '⚙'),
        ]
        for tkey, page_idx, icon_ch in nav_items:
            btn = QPushButton(f"{icon_ch}  {t(tkey)}")
            btn.setObjectName("navBtn")
            btn.setProperty('_t_key', tkey)
            btn.setProperty('_icon_ch', icon_ch)
            btn.clicked.connect(lambda checked, idx=page_idx: self.switch_page(idx))
            nav_layout.addWidget(btn)
            self.nav_buttons.append((btn, page_idx))

        main_layout.addWidget(nav_bar)

        # Round 237: тонкая status-полоса под навигацией с подсказками
        # клавиш. «Управление программой должно быть простым» (юзер).
        self.shortcut_bar = QLabel(
            "  F1-F6 разделы · F11 Fullscreen  ·  В плеере: ← Channels · "
            "→ Settings · ↑↓ Канал · Space Pause · F Favorite · M Mute · "
            "+/- Громкость · 0-9 № канала · Esc Закрыть")
        self.shortcut_bar.setStyleSheet(
            f"background-color: {COLORS['background']};"
            f" color: {COLORS['text_hint']};"
            " padding: 4px 12px; font-size: 11px; border-top: 1px solid"
            f" {COLORS['surface']};")
        self.shortcut_bar.setFixedHeight(24)
        main_layout.addWidget(self.shortcut_bar)
        # Round 241: стартуем на HomePage (как Android nav_home).
        try:
            self.switch_page(self._home_index)
        except Exception:
            self.update_nav_highlight(0)

    def _update_nav_labels(self):
        for btn, _idx in getattr(self, 'nav_buttons', []):
            key = btn.property('_t_key')
            icon = btn.property('_icon_ch') or ""
            if key:
                btn.setText(f"{icon}  {t(key)}" if icon else t(key))

    def switch_page(self, idx):
        # Round 257: при переходе НА любую страницу кроме плеера —
        # автоматически выходим из fullscreen и показываем nav_bar +
        # shortcut_bar (юзер: «при фул скрине при переходе в настройки
        # вернуться не получается — нужно выйти из полноэкранного
        # режима»). И наоборот: на плеере прячем chrome всегда —
        # всегда «фул-скрин-выгляд» без F11.
        try:
            log_info('nav', f"switch_page → {idx}")
            going_to_player = (idx == 3)
            if not going_to_player and self.isFullScreen():
                self.showNormal()
                self._apply_fullscreen_chrome(False)
            if hasattr(self, 'nav_bar'):
                self.nav_bar.setVisible(not going_to_player)
            if hasattr(self, 'shortcut_bar'):
                self.shortcut_bar.setVisible(not going_to_player)
        except Exception as e:
            log_error('switch_page', e, extra=f"idx={idx}")
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
        if self._handle_key(event.key()):
            return
        super().keyPressEvent(event)

    def _handle_key(self, key):
        """Round 248: единый обработчик хоткеев. Вызывается и из
        keyPressEvent, и из application-level event filter (чтобы
        клавиши работали даже когда фокус забрало нативное VLC-окно).
        Возвращает True если клавиша обработана."""
        try:
            current = self.stack.currentWidget()
            if isinstance(current, PlayerPage):
                # Esc / Backspace — закрыть оверлей или вернуться назад.
                if key in (Qt.Key_Escape, Qt.Key_Backspace):
                    # Round 251: Esc закрывает оверлеи ПОЛНОСТЬЮ (в
                    # отличие от LEFT, который шагает поэтапно). Если
                    # ничего не открыто — выходим из fullscreen, иначе
                    # назад на каналы.
                    any_overlay = any(
                        getattr(current, n, None) is not None
                        and getattr(current, n).isVisible()
                        for n in ('center_menu_overlay', 'categories_overlay',
                                  'channels_overlay', 'quick_overlay'))
                    if any_overlay:
                        current.hide_all_overlays()
                        current._sync_overlay_host()
                        return True
                    if self.isFullScreen():
                        self.showNormal()
                        self._apply_fullscreen_chrome(False)
                        return True
                    current.back_requested.emit(); return True
                if hasattr(current, 'channels_overlay') and current.channels_overlay.isVisible():
                    if key == Qt.Key_Right:
                        # Round 254: RIGHT теперь ЗАКРЫВАЕТ список каналов
                        # (юзер: «при нажатии вправо он должен закрываться
                        # чего нет»). Используем left_press который делает
                        # обратный шаг state-machine, либо полностью прячем.
                        current.hide_all_overlays()
                        current._sync_overlay_host()
                        return True
                    if key == Qt.Key_Left:
                        current.left_press(); return True
                    if key in (Qt.Key_Return, Qt.Key_Enter):
                        item = current._overlay_list.currentItem()
                        if item:
                            current._overlay_channel_clicked(item)
                        return True
                    if key in (Qt.Key_Up, Qt.Key_Down):
                        # Навигация по overlay-списку.
                        lst = current._overlay_list
                        row = lst.currentRow()
                        if key == Qt.Key_Up and row > 0:
                            lst.setCurrentRow(row - 1)
                        elif key == Qt.Key_Down and row < lst.count() - 1:
                            lst.setCurrentRow(row + 1)
                        return True
                    # Round 276: «type to search» — буква/цифра отдаёт
                    # фокус строке поиска и пишет символ туда. Юзер:
                    # «опять нет возможности в списке каналов делать
                    # поиск нельзя вписать что либо». Tool-окно
                    # overlay_host неуверенно отдаёт фокус QLineEdit
                    # через мышиный клик, поэтому ловим клавишу здесь
                    # и явно перебрасываем.
                    is_text = (
                        (Qt.Key_A <= key <= Qt.Key_Z) or
                        (Qt.Key_0 <= key <= Qt.Key_9) or
                        key == Qt.Key_Space or
                        key == Qt.Key_Backspace
                    )
                    if is_text and hasattr(current, '_overlay_search'):
                        se = current._overlay_search
                        se.setFocus()
                        if key == Qt.Key_Backspace:
                            txt = se.text()
                            se.setText(txt[:-1])
                        else:
                            ch_str = ''
                            if Qt.Key_A <= key <= Qt.Key_Z:
                                ch_str = chr(ord('a') + (key - Qt.Key_A))
                            elif Qt.Key_0 <= key <= Qt.Key_9:
                                ch_str = chr(ord('0') + (key - Qt.Key_0))
                            elif key == Qt.Key_Space:
                                ch_str = ' '
                            if ch_str:
                                se.setText(se.text() + ch_str)
                        return True
                elif (hasattr(current, 'categories_overlay')
                      and current.categories_overlay.isVisible()):
                    # В overlay категорий: Up/Down навигируют, Enter
                    # выбирает, Left → центральное меню, Esc/Right закрыть.
                    clist = current._cat_list
                    if key in (Qt.Key_Up, Qt.Key_Down):
                        row = clist.currentRow()
                        if key == Qt.Key_Up and row > 0:
                            clist.setCurrentRow(row - 1)
                        elif key == Qt.Key_Down and row < clist.count() - 1:
                            clist.setCurrentRow(row + 1)
                        return True
                    if key in (Qt.Key_Return, Qt.Key_Enter):
                        item = clist.currentItem()
                        if item:
                            current._on_category_chosen(item)
                        return True
                    if key == Qt.Key_Left:
                        current.left_press(); return True
                    if key == Qt.Key_Right:
                        current.categories_overlay.hide(); return True
                elif (hasattr(current, 'center_menu_overlay')
                      and current.center_menu_overlay.isVisible()):
                    if key == Qt.Key_Left:
                        current.left_press(); return True
                    if key == Qt.Key_Right:
                        # Round 254: RIGHT закрывает центр-меню (симметрично
                        # списку каналов).
                        current.hide_all_overlays()
                        current._sync_overlay_host()
                        return True
                    if key == Qt.Key_Up:
                        current.step_center_menu_focus(-1); return True
                    if key == Qt.Key_Down:
                        current.step_center_menu_focus(+1); return True
                    if key in (Qt.Key_Return, Qt.Key_Enter):
                        current.trigger_center_menu_focused(); return True
                elif hasattr(current, 'quick_overlay') and current.quick_overlay.isVisible():
                    # Round 256: quick-overlay — D-pad навигация по
                    # кнопкам (юзер: «не возможно перемещать строки в
                    # меню которая открывается при нажатии вправо»).
                    if key == Qt.Key_Right:
                        current.toggle_quick_overlay(); return True
                    if key == Qt.Key_Left:
                        current.toggle_quick_overlay(); return True
                    if key == Qt.Key_Up:
                        current.step_quick_overlay_focus(-1); return True
                    if key == Qt.Key_Down:
                        current.step_quick_overlay_focus(+1); return True
                    if key in (Qt.Key_Return, Qt.Key_Enter):
                        current.trigger_quick_overlay_focused(); return True
                else:
                    # Видео без оверлеев: стрелки = каналы/панели.
                    if key == Qt.Key_Left:
                        current.left_press(); return True
                    if key == Qt.Key_Right:
                        current.toggle_quick_overlay(); return True
                    if key == Qt.Key_Up:
                        current.switch_channel(-1); return True
                    if key == Qt.Key_Down:
                        current.switch_channel(1); return True
                    if key == Qt.Key_Space:
                        current.toggle_play(); return True
                    if key == Qt.Key_F:
                        current.toggle_favorite(); return True
                    if key == Qt.Key_M:
                        cur_v = current.vol_slider.value()
                        if cur_v > 0:
                            current._saved_volume_before_mute = cur_v
                            current.vol_slider.setValue(0)
                        else:
                            current.vol_slider.setValue(
                                getattr(current, '_saved_volume_before_mute', 50))
                        return True
                    if key in (Qt.Key_Plus, Qt.Key_Equal, Qt.Key_VolumeUp):
                        current.vol_slider.setValue(min(100, current.vol_slider.value() + 5))
                        return True
                    if key in (Qt.Key_Minus, Qt.Key_Underscore, Qt.Key_VolumeDown):
                        current.vol_slider.setValue(max(0, current.vol_slider.value() - 5))
                        return True
                # Цифровые клавиши 0-9 — ввод номера канала.
                if Qt.Key_0 <= key <= Qt.Key_9:
                    try:
                        digit = key - Qt.Key_0
                        cur = getattr(current, '_number_input', '')
                        current._number_input = (cur + str(digit))[-4:]
                        current.number_label.setText(current._number_input)
                        current._number_timer.start()
                    except Exception:
                        pass
                    return True
                if key == Qt.Key_L:
                    current.left_press(); return True
                if key == Qt.Key_R:
                    current.toggle_quick_overlay(); return True
        except Exception as e:
            log_error('_handle_key', e, extra=f"key={key}")
        # Global section shortcuts (работают везде).
        if key == Qt.Key_F1:
            self.switch_page(0); return True
        if key == Qt.Key_F2:
            self.switch_page(1); return True
        if key == Qt.Key_F3:
            self.switch_page(5); return True
        if key == Qt.Key_F4:
            self.switch_page(2); return True
        if key == Qt.Key_F6:
            self.switch_page(6); return True
        # Round 250: F11 — fullscreen всего окна, работает в любой
        # вкладке (раньше был только в плеере, а нижние кнопки скрыты —
        # запустить fullscreen стало вообще не из чего).
        if key == Qt.Key_F11:
            if self.isFullScreen():
                self.showNormal()
                self._apply_fullscreen_chrome(False)
            else:
                self.showFullScreen()
                self._apply_fullscreen_chrome(True)
            return True
        if key == Qt.Key_F5:
            if self.config.last_playlist_url:
                self.load_playlist(
                    self.config.last_playlist_name or "Playlist",
                    self.config.last_playlist_url)
            return True
        return False

    def _apply_fullscreen_chrome(self, fullscreen):
        """Round 251: в полном экране прячем нижнюю навигацию и
        полоску хоткеев, чтобы видео занимало ВЕСЬ экран (юзер:
        «фулл скрин не полный»). При выходе — показываем обратно."""
        try:
            if hasattr(self, 'nav_bar'):
                self.nav_bar.setVisible(not fullscreen)
            if hasattr(self, 'shortcut_bar'):
                self.shortcut_bar.setVisible(not fullscreen)
            cur = self.stack.currentWidget()
            if isinstance(cur, PlayerPage):
                QTimer.singleShot(60, cur._sync_overlay_host)
        except Exception as e:
            log_error('_apply_fullscreen_chrome', e, extra=f"fs={fullscreen}")

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
        self.config.save_async()

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
            self.config.save_async()
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
            # Round 278: НЕ грузим EPG сразу. LoadEpgThread парсит ~50 МБ
            # XMLTV под GIL, главная нитка остаётся почти без CPU 20+
            # сек, и watchdog логирует это как «main thread blocked 11s».
            # Откладываем на 5 сек после загрузки плейлиста — пользователь
            # уже видит каналы, может что-то выбрать, и тогда уже идёт
            # тяжёлая EPG-загрузка.
            QTimer.singleShot(5000, lambda srcs=list(epg_sources): self.load_epg(srcs))

        # Autoplay last channel (best-effort: match by URL)
        if self.config.autoplay_last and self.config.last_channel_url:
            for i, ch in enumerate(self.channels):
                if ch.url == self.config.last_channel_url:
                    self.play_channel(i)
                    break

    def on_playlist_error(self, error: str):
        log_error('playlist_load', error)
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

    def _on_epg_refresh(self):
        """Round 257/259: ручная или авто-перезагрузка EPG. Юзер:
        «программа постоянно зависает при просмотре фул хд каналов и
        возможно идёт обновление тв программы». Поэтому:
        - если плеер активно играет — пропускаем АВТО-перезагрузку
          (ручная кнопка ↻ всегда работает);
        - дросселируем: не чаще раза в час;
        - LoadEpgThread парсит большой XMLTV под GIL и крадёт CPU у
          QApplication, на FullHD это даёт лаги."""
        import time as _t
        now = _t.time()
        last = getattr(self, '_last_epg_load_ts', 0)
        auto = self.sender() is not getattr(self.tv_guide_page, 'btn_refresh', None)
        if auto:
            # авто-триггер: пропускаем если плеер активно проигрывает
            try:
                cur = self.stack.currentWidget()
                if isinstance(cur, PlayerPage) and getattr(cur, 'player', None) \
                        and cur.player.is_playing():
                    log_info('epg-refresh',
                             'skipped: player is playing, will retry next tick')
                    return
            except Exception as e:
                log_error('epg-refresh.check_player', e)
            # авто-троттл: не чаще раза в час
            if now - last < 60 * 60:
                log_info('epg-refresh',
                         f'skipped: throttled, last={int(now - last)}s ago')
                return
        sources = []
        if getattr(self.config, 'last_epg_url', ''):
            sources.append(self.config.last_epg_url)
        for u in getattr(self.config, 'epg_urls', []) or []:
            if u and u not in sources:
                sources.append(u)
        if not sources:
            self.tv_guide_page.status.setText(
                "Нет источников EPG. Добавьте URL в Настройках.")
            return
        self.tv_guide_page.status.setText(
            f"Скачиваю EPG ({len(sources)} src)…")
        self._last_epg_load_ts = now
        log_info('epg-refresh', f"loading {len(sources)} sources auto={auto}")
        self.load_epg(sources)

    def play_channel(self, index):
        if index < 0 or index >= len(self.channels):
            return
        self.stack.setCurrentIndex(3)
        self.update_nav_highlight(-1)
        # Round 257: на плеере всегда прячем MainWindow chrome — юзер
        # хочет «фул-скрин по умолчанию» без необходимости F11.
        try:
            if hasattr(self, 'nav_bar'):
                self.nav_bar.setVisible(False)
            if hasattr(self, 'shortcut_bar'):
                self.shortcut_bar.setVisible(False)
        except Exception:
            pass
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

    def _on_home_live(self):
        """Round 241: «Прямой эфир» — открыть плеер с последним
        каналом. Порт Android HomeFragment.onLiveClicked()."""
        # Если есть последний канал — играем.
        last_ch = self.config.last_channel_url
        last_pl = self.config.last_playlist_url
        if last_ch and self.channels:
            for i, ch in enumerate(self.channels):
                if ch.url == last_ch:
                    self.play_channel(i)
                    return
        # Иначе если есть плейлист — грузим его, потом юзер выберет канал.
        if last_pl:
            self.load_playlist(self.config.last_playlist_name or "Playlist", last_pl)
            return
        # Иначе подсказка: открыть плейлисты.
        QMessageBox.information(
            self, t('app_name'),
            "Сначала добавьте плейлист на вкладке «Плейлисты»."
            if _CURRENT_LANG == 'ru'
            else "Add a playlist first on the Playlists tab.")
        self.switch_page(0)

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
        # Round 246: применяем смену позиции часов сразу.
        try:
            self.player_page._position_persistent_clock()
        except Exception:
            pass
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
        # Round 279: synchronous save at close — нужно гарантированно
        # дописать config до выхода. release_vlc() уходит в фон чтобы
        # не подвешивать выход.
        try:
            import threading as _th
            _th.Thread(target=self.player_page.release_vlc,
                       daemon=True, name='vlc-release').start()
        except Exception as e:
            log_error('closeEvent.release', e)
        self.player_page.stop()
        self.config.save()
        event.accept()


def _install_crash_handler(app):
    """Log unhandled exceptions to disk and offer a 'Report on GitHub' dialog."""
    import traceback
    import platform as _platform
    # Round 258: используем уже-инициализированный _LOGGER (см. начало
    # файла), basicConfig здесь больше не нужен.

    # Round 275: rate-limit — каскад исключений (например NameError в
    # таймере раз в 50мс) генерил каскад модальных QMessageBox и
    # синхронных ntfy.sh POST'ов. Watchdog показал _excepthook
    # блокирующим main thread по 12 сек. Теперь:
    #   - модальный диалог показываем ОДИН РАЗ за сессию;
    #   - ntfy.sh публикуем в фоновом потоке;
    #   - дубликаты логируем без UI.
    state = {'shown': False, 'last_sig': None, 'count': 0}

    def _publish_async(title, body):
        try:
            import threading as _th
            _th.Thread(target=lambda: _publish_to_ntfy(title, body),
                       daemon=True, name='ntfy').start()
        except Exception:
            pass

    def _excepthook(exc_type, exc_value, exc_tb):
        try:
            tb_text = "".join(traceback.format_exception(exc_type, exc_value, exc_tb))
            try:
                _LOGGER.error("Unhandled exception:\n%s", tb_text)
            except Exception:
                pass
            # Сигнатура для дедупа — тип + последний фрейм.
            try:
                last_line = tb_text.strip().split('\n')[-1][:120]
                sig = f"{exc_type.__name__}:{last_line}"
            except Exception:
                sig = str(exc_type)
            state['count'] += 1
            # Тот же тип ошибки повторяется — не дёргаем UI.
            if sig == state['last_sig'] or state['shown']:
                return
            state['last_sig'] = sig
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
                # Round 275: ntfy.sh в фоне — больше не вешает main thread.
                _publish_async(f"[Windows crash] {short}", body)
                url = ("https://github.com/donmax76/IpTv/issues/new"
                       f"?title={quote('[Windows crash] ' + short)}&body={quote(body)}")
                # Один раз за сессию — диалог. Дальше всё в лог.
                state['shown'] = True
                msg = QMessageBox()
                msg.setIcon(QMessageBox.Critical)
                msg.setWindowTitle("TVViewer crashed")
                msg.setText("An unexpected error occurred.")
                msg.setInformativeText(str(exc_value)[:300])
                msg.setDetailedText(tb_text[-3000:])
                btn_report = msg.addButton("Report on GitHub", QMessageBox.AcceptRole)
                btn_copy = msg.addButton("Copy stacktrace", QMessageBox.ActionRole)
                msg.addButton(QMessageBox.Close)
                msg.exec_()
                clicked = msg.clickedButton()
                if clicked is btn_report:
                    import webbrowser
                    webbrowser.open(url)
                elif clicked is btn_copy:
                    try:
                        QApplication.clipboard().setText(tb_text)
                    except Exception:
                        pass
            except Exception:
                pass
        finally:
            sys.__excepthook__(exc_type, exc_value, exc_tb)

    sys.excepthook = _excepthook


# ============================================================
# Round 235 (Windows): Splash window — gradient background + logo +
# app name + version. Показывается пока MainWindow строится (что на
# 10k каналов с EPG занимает 2-4 секунды). Аналог Android Round 222c.
# ============================================================
class SplashWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowFlags(Qt.SplashScreen | Qt.FramelessWindowHint)
        self.setAttribute(Qt.WA_TranslucentBackground, False)
        self.setFixedSize(560, 360)
        self._build_ui()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(40, 40, 40, 40)
        layout.setAlignment(Qt.AlignCenter)
        layout.setSpacing(12)

        # Round 271: используем настоящее лого из assets/tvviewer.png
        # (тот же файл, что у Android — ic_launcher_512.png). Юзер:
        # «добавь лого для этой программы и иконку».
        logo = QLabel()
        logo.setFixedSize(140, 140)
        logo.setAlignment(Qt.AlignCenter)
        ico_path = _app_icon_path()
        pm = QPixmap()
        if ico_path:
            src = QPixmap(ico_path)
            if not src.isNull():
                pm = src.scaled(140, 140, Qt.KeepAspectRatio,
                                Qt.SmoothTransformation)
        if pm.isNull():
            # Fallback на старый градиент + emoji если файл не нашёлся.
            pm = QPixmap(140, 140)
            pm.fill(QColor(0, 0, 0, 0))
            painter = QPainter(pm)
            painter.setRenderHint(QPainter.Antialiasing)
            from PyQt5.QtGui import QLinearGradient
            grad = QLinearGradient(0, 0, 140, 140)
            grad.setColorAt(0.0, QColor("#7C6CF7"))
            grad.setColorAt(1.0, QColor("#00CEC9"))
            painter.setBrush(QBrush(grad))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(0, 0, 140, 140, 26, 26)
            painter.setPen(QPen(QColor("white")))
            f = QFont('Segoe UI Symbol', 64, QFont.Bold)
            painter.setFont(f)
            painter.drawText(pm.rect(), Qt.AlignCenter, "📺")
            painter.end()
        logo.setPixmap(pm)
        layout.addWidget(logo, alignment=Qt.AlignCenter)

        # Имя приложения.
        name = QLabel("M3U IPTV")
        name.setAlignment(Qt.AlignCenter)
        name.setStyleSheet("color: white; font-size: 32px; font-weight: bold;")
        layout.addWidget(name)

        # Подпись.
        sub = QLabel("TVViewer")
        sub.setAlignment(Qt.AlignCenter)
        sub.setStyleSheet("color: #00CEC9; font-size: 14px;")
        layout.addWidget(sub)

        layout.addSpacing(20)

        # Round 243: прогресс-бар — был indeterminate (setRange(0,0)),
        # но QSS-override на ::chunk блокирует Qt-нативную animation
        # marquee, и юзер видел статичную полоску. Делаем determinate
        # с QPropertyAnimation 0→100% за 1.5 сек и зацикливаем (loopCount=-1)
        # — визуально как пульсирующий load-indicator.
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(5)
        self.progress_bar.setTextVisible(False)
        self.progress_bar.setFixedHeight(6)
        self.progress_bar.setStyleSheet(
            "QProgressBar { background-color: rgba(255,255,255,30);"
            " border: none; border-radius: 3px; }"
            "QProgressBar::chunk { background-color: #7C6CF7;"
            " border-radius: 3px; }")
        layout.addWidget(self.progress_bar)

        self.status_label = QLabel("Загрузка…")
        self.status_label.setAlignment(Qt.AlignCenter)
        self.status_label.setStyleSheet(
            "color: rgba(255,255,255,180); font-size: 13px;"
            " background: transparent;")
        layout.addWidget(self.status_label)

    def set_progress(self, value, text=None):
        """Round 253: бар плавно догоняет target через QTimer 30мс,
        чтобы юзер видел движение даже когда главный поток ушёл
        строить MainWindow. processEvents() в каждом step main()
        даёт таймеру отработать."""
        try:
            self._target_progress = max(0, min(100, int(value)))
            if text is not None:
                self.status_label.setText(text)
            if not hasattr(self, '_tick_timer'):
                self._tick_timer = QTimer(self)
                self._tick_timer.setInterval(30)
                self._tick_timer.timeout.connect(self._tick_progress)
                self._tick_timer.start()
            QApplication.processEvents()
        except Exception:
            pass

    def _tick_progress(self):
        try:
            cur = self.progress_bar.value()
            tgt = getattr(self, '_target_progress', cur)
            if cur < tgt:
                self.progress_bar.setValue(min(cur + 2, tgt))
            elif cur > tgt:
                self.progress_bar.setValue(max(cur - 2, tgt))
        except Exception:
            pass

    def close(self):
        try:
            if hasattr(self, '_tick_timer'):
                self._tick_timer.stop()
        except Exception:
            pass
        super().close()

    def paintEvent(self, event):
        # Фон с диагональным градиентом — фирменная палитра.
        from PyQt5.QtGui import QLinearGradient
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        grad = QLinearGradient(0, 0, self.width(), self.height())
        grad.setColorAt(0.0, QColor("#0F0F1A"))
        grad.setColorAt(0.5, QColor("#1E1E3A"))
        grad.setColorAt(1.0, QColor("#0F0F1A"))
        painter.fillRect(self.rect(), QBrush(grad))
        # Тонкая фиолетовая обводка по периметру.
        painter.setPen(QPen(QColor("#7C6CF7"), 2))
        painter.setBrush(Qt.NoBrush)
        painter.drawRoundedRect(self.rect().adjusted(1, 1, -1, -1), 12, 12)
        super().paintEvent(event)


def _app_icon_path() -> str:
    """Round 271: путь к assets/tvviewer.png (.ico для Windows). Юзер:
    «добавь лого для этой программы и иконку»."""
    candidates = []
    if getattr(sys, 'frozen', False):
        bundle = os.path.dirname(sys.executable)
        candidates += [
            os.path.join(bundle, 'tvviewer.png'),
            os.path.join(bundle, 'assets', 'tvviewer.png'),
            os.path.join(bundle, '_internal', 'tvviewer.png'),
            os.path.join(bundle, '_internal', 'assets', 'tvviewer.png'),
        ]
    here = os.path.dirname(os.path.abspath(__file__))
    candidates += [
        os.path.join(here, 'assets', 'tvviewer.png'),
        os.path.join(here, 'tvviewer.png'),
    ]
    for p in candidates:
        if os.path.exists(p):
            return p
    return ''


def main():
    app = QApplication(sys.argv)
    app.setFont(QFont('Segoe UI', 12))
    # Round 272: запускаем watchdog ПЕРВЫМ делом — пусть он
    # ловит зависания на всём остальном инициализационном пути.
    _start_watchdog_heartbeat(app)
    # Round 271: иконка приложения — отображается в таскбаре, alt-tab,
    # окнах и в загловке. На сборке PyInstaller --icon встроит .ico
    # в сам EXE; здесь дополнительно ставим runtime-иконку через PNG.
    try:
        _ico = _app_icon_path()
        if _ico:
            app.setWindowIcon(QIcon(_ico))
    except Exception:
        pass
    _install_crash_handler(app)
    # Round 258: Qt сам пишет warning/critical в stderr — перехватываем и
    # пишем в tvviewer.log. Иначе на --windowed сборке без консоли все
    # «QObject::startTimer: …» и пр. терялись.
    try:
        from PyQt5.QtCore import qInstallMessageHandler, QtMsgType
        def _qt_msg_handler(mode, ctx, message):
            try:
                if mode == QtMsgType.QtDebugMsg:
                    log_info('qt', message)
                elif mode == QtMsgType.QtWarningMsg:
                    log_warn('qt', message)
                else:  # Critical / Fatal / Info
                    log_error('qt', message)
            except Exception:
                pass
        qInstallMessageHandler(_qt_msg_handler)
        log_info('app', f"startup v{WIN_VERSION_NAME} build {WIN_VERSION_CODE}")
    except Exception as _e:
        log_error('qt-handler-install', _e)
    # Round 232: применяем язык до сборки UI. MainWindow при создании
    # тоже инициализирует Config, но мы это делаем СНАЧАЛА чтобы при
    # рендере виджетов уже была правильная локаль.
    _bootstrap_cfg = Config()
    set_ui_language(getattr(_bootstrap_cfg, 'ui_language', 'ru'))
    # Round 247: применяем выбранную цветовую тему ДО setStyleSheet.
    apply_theme(getattr(_bootstrap_cfg, 'theme_color', 'default'))
    app.setStyleSheet(STYLESHEET)
    # Round 235: показываем splash пока MainWindow строится. На больших
    # плейлистах сборка занимает 2-4 сек, без splash юзер видит чёрный
    # экран и думает что зависло.
    splash = SplashWindow()
    splash.show()
    splash.set_progress(10, "Запуск…")
    # Round 253: даём splash время прорисоваться и тикнуть таймер
    # пару раз ДО блокирующей сборки MainWindow. Иначе юзер видит
    # «прогресс не двигается».
    for _ in range(8):
        app.processEvents()
        time.sleep(0.02)
    splash.set_progress(35, "Подготовка интерфейса…")
    for _ in range(4):
        app.processEvents()
        time.sleep(0.02)

    # Round 255: progress_cb — между шагами init_ui обновляет splash и
    # прокачивает Qt event loop. Юзер: «опять окно где пишется
    # подготовка интерфейса висит в основном потоке». Передача в main
    # потоке остаётся — Qt-виджеты MUST создаваться на GUI-потоке, но
    # мы дробим работу на куски и между ними даём splash дышать.
    def _progress(pct, txt):
        try:
            splash.set_progress(pct, txt)
            # Несколько прокачек чтобы анимация прогресс-бара и repaint
            # splash успели отработать ДО следующего тяжёлого шага.
            for _ in range(3):
                app.processEvents()
        except Exception:
            pass

    window = MainWindow(progress_cb=_progress)
    splash.set_progress(95, "Почти готово…")
    for _ in range(4):
        app.processEvents()
        time.sleep(0.02)
    # Apply persisted always-on-top preference
    if window.config.always_on_top:
        window.setWindowFlag(Qt.WindowStaysOnTopHint, True)
    splash.set_progress(100, "Готово")
    for _ in range(8):
        app.processEvents()
        time.sleep(0.02)
    window.show()
    # Round 235: гасим splash после того как MainWindow отрисована,
    # с небольшой задержкой чтобы splash был виден хотя бы 600мс
    # (без задержки на быстрой машине мелькает за мс).
    try:
        QTimer.singleShot(600, splash.close)
    except Exception:
        try:
            splash.close()
        except Exception:
            pass
    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
