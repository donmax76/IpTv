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
    QGraphicsDropShadowEffect, QSpinBox, QCheckBox,
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
    save_to_cache as save_epg_cache,
    load_from_cache as load_epg_cache,
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
        # Round 381: по просьбе юзера «в логе много ненужного — оставь
        # только критическое». Уровень WARNING: все log_info(...) (диагно-
        # стический шум по playlist/update/epg/logo/overlay/vlc/play)
        # больше НЕ пишутся в файл; в лог попадают только предупреждения
        # (log_warn) и ошибки с трейсбеком (log_error). Сами вызовы
        # log_info оставлены в коде — они просто отфильтровываются
        # уровнем, так что вернуть подробный лог = сменить одну строку.
        _LOGGER.setLevel(_logging.WARNING)
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

    Round 299: ИДЕМПОТЕНТНО. Юзер словил баг — channel_meta_lookup
    делал `import tvviewer` и module-level вызов запускал watchdog
    ВТОРОЙ раз, дубликат watcher'а репортил «blocked 30/60/90/120/180s»
    бесконечно (его _last_tick никогда не тикался, бил false-alarm
    каждые 6 сек). Теперь — флаг и ранний выход.
      1) main thread тикает heartbeat в shared variable раз в 1 сек
         через QTimer (когда Qt event loop работает — heartbeat идёт).
      2) Background-thread каждые 2 сек проверяет heartbeat. Если он
         не обновлялся >3 сек — main thread где-то завис.
      3) Вытаскивает stack trace main-thread'а через sys._current_frames()
         и пишет в лог как WARNING. Юзер видит, ГДЕ именно стояли.
    """
    if globals().get('_WATCHDOG_INSTALLED'):
        return
    globals()['_WATCHDOG_INSTALLED'] = True
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
                # Round 292: подавляем варнинг пока выставлен флаг
                # _WATCHDOG_SUPPRESS — это период когда мы заведомо
                # знаем что C-код держит GIL (libvlc_new, EPG-парс) и
                # warning тут ложный.
                if globals().get('_WATCHDOG_SUPPRESS', 0) > 0:
                    continue
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


def _watchdog_suppress(on: bool):
    """Round 292: помечает заведомо тяжёлые C-операции (libvlc_new,
    XMLTV parse) чтобы watchdog не репортил «main thread blocked»
    когда реального фриза нет — просто GIL держит C-код."""
    try:
        cur = globals().get('_WATCHDOG_SUPPRESS', 0)
        globals()['_WATCHDOG_SUPPRESS'] = max(0, cur + (1 if on else -1))
    except Exception:
        pass


_install_main_thread_watchdog()


# Round 299: инъектируем suppress-callback в channel_meta_lookup чтобы
# тот мог глушить watchdog во время iptv-org JSON-парсинга БЕЗ
# `import tvviewer` (который запускал бы повторный module-init).
try:
    channel_meta_lookup._WATCHDOG_SUPPRESS_CB = _watchdog_suppress
except Exception:
    pass


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
        # Round 364: родительский контроль + правка плейлистов.
        'parental_control': "Родительский контроль",
        'parental_set_pin': "Установить PIN-код",
        'parental_change_pin': "Сменить PIN-код",
        'parental_remove_pin': "Отключить (убрать PIN)",
        'parental_locked_categories': "Заблокированные категории",
        'parental_enter_pin': "Введите PIN-код",
        'parental_new_pin': "Новый PIN-код (4–8 цифр)",
        'parental_wrong_pin': "Неверный PIN-код",
        'parental_pin_set': "PIN-код установлен",
        'parental_pin_removed': "Родительский контроль отключён",
        'parental_channel_locked': "Канал заблокирован 🔒",
        'parental_channel_unlocked': "Канал разблокирован",
        'parental_lock_channel': "🔒 Заблокировать канал",
        'parental_unlock_channel': "🔓 Разблокировать канал",
        'parental_status_on': "PIN установлен",
        'playlist_edit': "Редактировать",
        'playlist_copy_url': "Копировать URL",
        'playlist_url_copied': "URL скопирован в буфер обмена",
        'playlist_name_hint': "Название",
        'playlist_url_hint': "URL плейлиста",
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
        'open_releases': "Открыть страницу релизов",
        'open_log_folder': "Открыть папку логов",
        'report_issue': "Сообщить о проблеме",
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
        'update_no_internet': "Не удалось связаться с сервером (нет интернета?)",
        'restart_required': "Требуется перезапуск приложения",
        'no_playlist_yet': "Сначала добавьте плейлист на вкладке «Плейлисты».",
    
        # Round 350: live-retranslate keys.
        'section_playback': 'Воспроизведение',
        'section_behaviour': 'Поведение',
        'section_advanced': 'Дополнительно (VLC)',
        'section_epg_sources': 'Источники EPG (мульти-EPG)',
        'section_data': 'Данные',
        'section_navigation': 'Навигация',
        'no_playlists_yet': 'Нет сохранённых плейлистов',
        'section_updates': 'Обновления',
        'section_help': 'Помощь',
        'section_appearance': 'Внешний вид',
        'section_about': 'О программе',
        'section_language': 'Язык',
        'section_diagnostics': 'Диагностика',
        'vlc_installed': 'VLC: Установлен',
        'vlc_not_found': 'VLC: Не найден — установите VLC и python-vlc',
        'buffer_low': 'Низкий (1500 мс)',
        'buffer_normal': 'Нормальный (3000 мс)',
        'buffer_default': 'По умолчанию (5000 мс)',
        'buffer_high': 'Высокий (9000 мс)',
        'buffer_very_high': 'Очень высокий (10000 мс)',
        'clock_top_right': 'Верх-право',
        'clock_top_left': 'Верх-лево',
        'clock_bottom_right': 'Низ-право',
        'clock_bottom_left': 'Низ-лево',
        'clock_off': 'Скрыть',
        'clock_in_player_label': 'Часы в плеере:',
        'theme_default': '🟣 Фиолетовый (по умолчанию)',
        'theme_blue': '🔵 Синий',
        'theme_green': '🟢 Зелёный',
        'theme_orange': '🟠 Оранжевый',
        'theme_red': '🔴 Красный',
        'sleep_minutes_off': ' мин (0 = выкл)',
        'sleep_timer_default': 'Таймер сна (по умолч.):',
        'hardware_decode_recommended': 'Аппаратное декодирование (рекомендуется)',
        'open_player_fullscreen': 'Открывать плеер в полноэкранном режиме',
        'autoplay_last_help': 'Авто-воспроизведение последнего канала при запуске',
        'list_preview': 'Мини-превью при листании списка',
        'show_adult': 'Показывать 18+ / XXX',
        'always_on_top_mini': 'Поверх всех окон (режим мини-плеера)',
        'audio_output_auto': 'Авто',
        'audio_output_directsound': 'DirectSound',
        'audio_output_mmdevice': 'MMDevice (WASAPI)',
        'audio_output_waveout': 'WaveOut',
        'ua_note_restart': 'Примечание: изменения вступят в силу после перезапуска.',
        'epg_url_placeholder': 'https://example.com/epg.xml.gz',
        'epg_merged_note': 'Программы из всех источников объединяются. url-tvg плейлиста используется всегда.',
        'reset_settings_button': 'Сбросить настройки',
        'reset_confirm_title': 'Сбросить настройки',
        'reset_confirm_body': 'Сбросить все настройки к значениям по умолчанию? Плейлисты и избранное сохранятся.',
        'clear_favorites_confirm': 'Удалить все избранные?',
        'clear_recent_confirm': 'Очистить список недавних?',
        'clear_per_channel_state_confirm': 'Очистить состояние всех каналов?',
        'checking_updates': 'Проверяю…',
        'current_build_status_template': 'Текущий build {build}. Нажмите «Проверить обновления».',
        'installed_template': 'Установлено: {name} build {build}',
        'aspect_auto': 'Соотношение: авто',
        'vol_label': 'Громк:',
        'btn_back': '< Назад',
        'btn_channel': 'Канал',
        'dialog_add_playlist': 'Добавить плейлист',
        'form_name': 'Имя:',
        'form_url': 'URL:',
        'form_server': 'Сервер:',
        'form_username': 'Логин:',
        'form_password': 'Пароль:',
        'placeholder_playlist_name': 'Имя плейлиста',
        'placeholder_url': 'http://... или https://...',
        'placeholder_xtream_name': 'Мой Xtream',
        'panel_categories': 'Категории',
        'btn_show_channels': '☰ Показать список каналов',
        'menu_search': 'Поиск',
    },
    'en': {
        'app_name': "M3U IPTV",
        'parental_control': "Parental control",
        'parental_set_pin': "Set PIN",
        'parental_change_pin': "Change PIN",
        'parental_remove_pin': "Disable (remove PIN)",
        'parental_locked_categories': "Locked categories",
        'parental_enter_pin': "Enter PIN",
        'parental_new_pin': "New PIN (4–8 digits)",
        'parental_wrong_pin': "Wrong PIN",
        'parental_pin_set': "PIN set",
        'parental_pin_removed': "Parental control disabled",
        'parental_channel_locked': "Channel locked 🔒",
        'parental_channel_unlocked': "Channel unlocked",
        'parental_lock_channel': "🔒 Lock channel",
        'parental_unlock_channel': "🔓 Unlock channel",
        'parental_status_on': "PIN set",
        'playlist_edit': "Edit",
        'playlist_copy_url': "Copy URL",
        'playlist_url_copied': "URL copied to clipboard",
        'playlist_name_hint': "Name",
        'playlist_url_hint': "Playlist URL",
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
        'open_releases': "Open releases page",
        'open_log_folder': "Open log folder",
        'report_issue': "Report a problem",
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
        'update_no_internet': "Could not reach the server (no internet?)",
        'restart_required': "Application restart required",
        'no_playlist_yet': "Add a playlist first on the Playlists tab.",
    
        # Round 350: live-retranslate keys.
        'section_playback': 'Playback',
        'section_behaviour': 'Behaviour',
        'section_advanced': 'Advanced (VLC)',
        'section_epg_sources': 'EPG sources (multi-EPG)',
        'section_data': 'Data',
        'section_navigation': 'Navigation',
        'no_playlists_yet': 'No saved playlists yet',
        'section_updates': 'Updates',
        'section_help': 'Help',
        'section_appearance': 'Appearance',
        'section_about': 'About',
        'section_language': 'Language',
        'section_diagnostics': 'Diagnostics',
        'vlc_installed': 'VLC: Installed',
        'vlc_not_found': 'VLC: Not found - install VLC and python-vlc',
        'buffer_low': 'Low (1500 ms)',
        'buffer_normal': 'Normal (3000 ms)',
        'buffer_default': 'Default (5000 ms)',
        'buffer_high': 'High (9000 ms)',
        'buffer_very_high': 'Very high (10000 ms)',
        'clock_top_right': 'Top-right',
        'clock_top_left': 'Top-left',
        'clock_bottom_right': 'Bottom-right',
        'clock_bottom_left': 'Bottom-left',
        'clock_off': 'Hide',
        'clock_in_player_label': 'Clock in player:',
        'theme_default': '🟣 Purple (default)',
        'theme_blue': '🔵 Blue',
        'theme_green': '🟢 Green',
        'theme_orange': '🟠 Orange',
        'theme_red': '🔴 Red',
        'sleep_minutes_off': ' min (0 = off)',
        'sleep_timer_default': 'Sleep timer (default):',
        'hardware_decode_recommended': 'Hardware decoding (recommended)',
        'open_player_fullscreen': 'Open player in fullscreen',
        'autoplay_last_help': 'Autoplay last channel on startup',
        'list_preview': 'Mini preview while browsing the list',
        'show_adult': 'Show 18+ / XXX',
        'always_on_top_mini': 'Always on top (mini-player mode)',
        'audio_output_auto': 'Auto',
        'audio_output_directsound': 'DirectSound',
        'audio_output_mmdevice': 'MMDevice (WASAPI)',
        'audio_output_waveout': 'WaveOut',
        'ua_note_restart': 'Note: changes take effect after restart.',
        'epg_url_placeholder': 'https://example.com/epg.xml.gz',
        'epg_merged_note': 'Programmes from all sources are merged. The playlist\'s url-tvg is always used.',
        'reset_settings_button': 'Reset settings',
        'reset_confirm_title': 'Reset settings',
        'reset_confirm_body': 'Reset all settings to defaults? Playlists and favorites are kept.',
        'clear_favorites_confirm': 'Remove all favorites?',
        'clear_recent_confirm': 'Clear recent list?',
        'clear_per_channel_state_confirm': 'Clear per-channel state?',
        'checking_updates': 'Checking…',
        'current_build_status_template': 'Current build {build}. Click "Check for updates".',
        'installed_template': 'Installed: {name} build {build}',
        'aspect_auto': 'Aspect: auto',
        'vol_label': 'Vol:',
        'btn_back': '< Back',
        'btn_channel': 'Channel',
        'dialog_add_playlist': 'Add Playlist',
        'form_name': 'Name:',
        'form_url': 'URL:',
        'form_server': 'Server:',
        'form_username': 'Username:',
        'form_password': 'Password:',
        'placeholder_playlist_name': 'Playlist name',
        'placeholder_url': 'http://... or https://...',
        'placeholder_xtream_name': 'My Xtream',
        'panel_categories': 'Categories',
        'btn_show_channels': '☰ Show channel list',
        'menu_search': 'Search',
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
        # Round 328: бекфіл усіх ключів, які раніше були тільки в ru/en/az.
        # Юзер: «перепроверь весь перевод».
        'live': "Прямий ефір",
        'add_url': "+ Додати URL",
        'open_file': "+ Відкрити файл",
        'xtream': "Xtream Codes",
        'from_clipboard': "Вставити з буфера",
        'xtream_codes': "Xtream Codes",
        'add_playlist_title': "Додати плейлист",
        'name_optional': "Назва (необов'язково)",
        'mini_player': "Міні-плеєр (поверх інших вікон)",
        'remember_fullscreen': "Запам'ятати повноекранний режим",
        'always_on_top': "Поверх усіх вікон",
        'mute': "Без звуку",
        'channels_in_list': "{n} каналів",
        'paste_url': "Вставте URL плейлиста",
        'channel_sort': "Сортування каналів:",
        'default_volume': "Гучність за замовчуванням:",
        'autoplay_last': "Авто-відтворення останнього каналу",
        'check_for_updates': "Перевірити оновлення",
        'open_releases': "Відкрити сторінку релізів",
        'open_log_folder': "Відкрити теку логів",
        'report_issue': "Повідомити про проблему",
        'updates': "Оновлення",
        'data': "Дані",
        'help': "Довідка",
        'about': "Про програму",
        'updates_check_in_progress': "Перевіряю…",
        'on_latest_version': "У вас остання версія",
        'new_build_available': "Доступний новий build {build}",
        'download_install': "Завантажити та встановити?",
        'update_no_internet': "Не вдалося зв'язатися з сервером (немає інтернету?)",
        'restart_required': "Потрібен перезапуск програми",
        'installed': "Встановлено",
        'reset_settings': "Скинути налаштування",
        'clear_favorites': "Очистити обране",
        'clear_recent': "Очистити нещодавні",
        'clear_per_channel_state': "Очистити стан каналів",
        'now': "Зараз",
        'next_program': "Далі",
        'no_program': "Немає програми",
        'channel': "Канал",
        'select_playlist': "Виберіть плейлист",
        'epg_sources': "Джерела EPG:",
        'user_agent': "HTTP User-Agent:",
        'audio_output': "Аудіо вихід:",
        'color_theme': "Колірна тема:",
        'hardware_decode': "Апаратне декодування",
        'buffer_label': "Буфер (мережевий кеш):",
        'add': "Додати",
        'clear': "Очистити",
        'ok': "OK",
        'cancel': "Скасувати",
        'save': "Зберегти",
        'channels_count_short': "{n} каналів",
        'channels_label': "Канали",
        'playlists_label': "Плейлисти",
        'favorites_label': "Обране",
        'recent_label': "Нещодавні",
        'tv_guide_label': "Телепрограма",
        'search_channels': "Пошук каналів...",
        'no_playlist_yet': "Спочатку додайте плейлист на вкладці «Плейлисти».",
    
        # Round 350: live-retranslate keys.
        'section_playback': 'Відтворення',
        'section_behaviour': 'Поведінка',
        'section_advanced': 'Розширені (VLC)',
        'section_epg_sources': 'Джерела EPG (мульти-EPG)',
        'section_data': 'Дані',
        'section_navigation': 'Навігація',
        'no_playlists_yet': 'Немає збережених плейлистів',
        'section_updates': 'Оновлення',
        'section_help': 'Довідка',
        'section_appearance': 'Зовнішній вигляд',
        'section_about': 'Про програму',
        'section_language': 'Мова',
        'section_diagnostics': 'Діагностика',
        'vlc_installed': 'VLC: Встановлено',
        'vlc_not_found': 'VLC: Не знайдено — встановіть VLC і python-vlc',
        'buffer_low': 'Низький (1500 мс)',
        'buffer_normal': 'Нормальний (3000 мс)',
        'buffer_default': 'За замовчуванням (5000 мс)',
        'buffer_high': 'Високий (9000 мс)',
        'buffer_very_high': 'Дуже високий (10000 мс)',
        'clock_top_right': 'Верх-право',
        'clock_top_left': 'Верх-ліво',
        'clock_bottom_right': 'Низ-право',
        'clock_bottom_left': 'Низ-ліво',
        'clock_off': 'Сховати',
        'clock_in_player_label': 'Годинник у плеєрі:',
        'theme_default': '🟣 Фіолетовий (за замовч.)',
        'theme_blue': '🔵 Синій',
        'theme_green': '🟢 Зелений',
        'theme_orange': '🟠 Помаранчевий',
        'theme_red': '🔴 Червоний',
        'sleep_minutes_off': ' хв (0 = вимк)',
        'sleep_timer_default': 'Таймер сну (за замовч.):',
        'hardware_decode_recommended': 'Апаратне декодування (рекомендовано)',
        'open_player_fullscreen': 'Відкривати плеєр у повноекранному режимі',
        'autoplay_last_help': 'Авто-відтворення останнього каналу при запуску',
        'always_on_top_mini': 'Поверх усіх вікон (режим міні-плеєра)',
        'audio_output_auto': 'Авто',
        'audio_output_directsound': 'DirectSound',
        'audio_output_mmdevice': 'MMDevice (WASAPI)',
        'audio_output_waveout': 'WaveOut',
        'ua_note_restart': 'Примітка: зміни наберуть чинності після перезапуску.',
        'epg_url_placeholder': 'https://example.com/epg.xml.gz',
        'epg_merged_note': 'Програми з усіх джерел об\'єднуються. url-tvg плейліста використовується завжди.',
        'reset_settings_button': 'Скинути налаштування',
        'reset_confirm_title': 'Скинути налаштування',
        'reset_confirm_body': 'Скинути всі налаштування до значень за замовчуванням? Плейлісти та обране зберігаються.',
        'clear_favorites_confirm': 'Видалити всі обрані?',
        'clear_recent_confirm': 'Очистити список нещодавніх?',
        'clear_per_channel_state_confirm': 'Очистити стан усіх каналів?',
        'checking_updates': 'Перевіряю…',
        'current_build_status_template': 'Поточний build {build}. Натисніть «Перевірити оновлення».',
        'installed_template': 'Встановлено: {name} build {build}',
        'aspect_auto': 'Співвідношення: авто',
        'vol_label': 'Гучн:',
        'btn_back': '< Назад',
        'btn_channel': 'Канал',
        'dialog_add_playlist': 'Додати плейліст',
        'form_name': 'Назва:',
        'form_url': 'URL:',
        'form_server': 'Сервер:',
        'form_username': 'Логін:',
        'form_password': 'Пароль:',
        'placeholder_playlist_name': 'Назва плейліста',
        'placeholder_url': 'http://... або https://...',
        'placeholder_xtream_name': 'Мій Xtream',
        'panel_categories': 'Категорії',
        'btn_show_channels': '☰ Показати список каналів',
        'menu_search': 'Пошук',
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
        'open_releases': "Buraxılışlar səhifəsini aç",
        'open_log_folder': "Loq qovluğunu aç",
        'report_issue': "Problem bildir",
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
        'update_no_internet': "Serverə bağlanmaq mümkün olmadı",
        'restart_required': "Tətbiqi yenidən başlatmaq lazımdır",
        'no_playlist_yet': "Əvvəlcə «Pleylistlər» bölməsində pleylist əlavə edin.",
    
        # Round 350: live-retranslate keys.
        'section_playback': 'Oxutma',
        'section_behaviour': 'Davranış',
        'section_advanced': 'Əlavə (VLC)',
        'section_epg_sources': 'EPG mənbələri (multi-EPG)',
        'section_data': 'Məlumatlar',
        'section_navigation': 'Naviqasiya',
        'no_playlists_yet': 'Saxlanmış pleylist yoxdur',
        'section_updates': 'Yeniləmələr',
        'section_help': 'Kömək',
        'section_appearance': 'Görünüş',
        'section_about': 'Haqqında',
        'section_language': 'Dil',
        'section_diagnostics': 'Diaqnostika',
        'vlc_installed': 'VLC: Quraşdırılıb',
        'vlc_not_found': 'VLC: Tapılmadı - VLC və python-vlc quraşdırın',
        'buffer_low': 'Aşağı (1500 ms)',
        'buffer_normal': 'Normal (3000 ms)',
        'buffer_default': 'Defolt (5000 ms)',
        'buffer_high': 'Yüksək (9000 ms)',
        'buffer_very_high': 'Çox yüksək (10000 ms)',
        'clock_top_right': 'Yuxarı-sağ',
        'clock_top_left': 'Yuxarı-sol',
        'clock_bottom_right': 'Aşağı-sağ',
        'clock_bottom_left': 'Aşağı-sol',
        'clock_off': 'Gizlət',
        'clock_in_player_label': 'Pleyerdə saat:',
        'theme_default': '🟣 Bənövşəyi (varsayılan)',
        'theme_blue': '🔵 Mavi',
        'theme_green': '🟢 Yaşıl',
        'theme_orange': '🟠 Narıncı',
        'theme_red': '🔴 Qırmızı',
        'sleep_minutes_off': ' dəq (0 = söndür)',
        'sleep_timer_default': 'Yuxu taymeri (varsayılan):',
        'hardware_decode_recommended': 'Aparat dekodlama (tövsiyə olunur)',
        'open_player_fullscreen': 'Pleyeri tam ekranda aç',
        'autoplay_last_help': 'Başlanğıcda son kanalı avto-oxut',
        'always_on_top_mini': 'Həmişə üstdə (mini-pleyer rejimi)',
        'audio_output_auto': 'Avto',
        'audio_output_directsound': 'DirectSound',
        'audio_output_mmdevice': 'MMDevice (WASAPI)',
        'audio_output_waveout': 'WaveOut',
        'ua_note_restart': 'Qeyd: dəyişikliklər yenidən başladıqdan sonra qüvvəyə minir.',
        'epg_url_placeholder': 'https://example.com/epg.xml.gz',
        'epg_merged_note': 'Bütün mənbələrdən proqramlar birləşdirilir. Pleylistin url-tvg-si həmişə istifadə olunur.',
        'reset_settings_button': 'Tənzimləmələri sıfırla',
        'reset_confirm_title': 'Tənzimləmələri sıfırla',
        'reset_confirm_body': 'Bütün tənzimləmələri varsayılana sıfırlamaq? Pleylistlər və seçilmişlər saxlanılır.',
        'clear_favorites_confirm': 'Bütün seçilmişləri silmək?',
        'clear_recent_confirm': 'Son baxılanlar siyahısını təmizləmək?',
        'clear_per_channel_state_confirm': 'Kanal vəziyyətlərini təmizləmək?',
        'checking_updates': 'Yoxlanılır…',
        'current_build_status_template': 'Cari build {build}. «Yeniləmələri yoxla» düyməsini basın.',
        'installed_template': 'Quraşdırılıb: {name} build {build}',
        'aspect_auto': 'Nisbət: avto',
        'vol_label': 'Səs:',
        'btn_back': '< Geri',
        'btn_channel': 'Kanal',
        'dialog_add_playlist': 'Pleylist əlavə et',
        'form_name': 'Ad:',
        'form_url': 'URL:',
        'form_server': 'Server:',
        'form_username': 'İstifadəçi:',
        'form_password': 'Şifrə:',
        'placeholder_playlist_name': 'Pleylist adı',
        'placeholder_url': 'http://... və ya https://...',
        'placeholder_xtream_name': 'Mənim Xtream-im',
        'panel_categories': 'Kateqoriyalar',
        'btn_show_channels': '☰ Kanal siyahısını göstər',
        'menu_search': 'Axtar',
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


def _retranslate_widgets(root):
    """Round 350: live-retranslate любой ветки виджетов.

    Поддерживаемые QObject properties:
      '_t_key'            -> setText / placeholder для QLabel / QPushButton /
                             QCheckBox / QAction. Если задан '_t_suffix' —
                             добавляется в конец.
      '_t_kwargs'         -> dict подстановок для t(key, **kwargs).
      '_t_prefix'         -> произвольный префикс к переведённой строке.
      '_t_suffix_key'     -> для QSpinBox.setSuffix
      '_t_placeholder_key'-> для QLineEdit.setPlaceholderText
      '_t_item_keys'      -> список ключей, по одному на каждый item
                             QComboBox; индекс соответствует индексу item.
    """
    # Импортируем тут чтобы избежать циклической загрузки.
    from PyQt5.QtWidgets import (
        QLabel, QPushButton, QCheckBox, QSpinBox, QLineEdit, QComboBox,
        QAction,
    )

    def _apply_text(widget, key):
        try:
            kw = widget.property('_t_kwargs') or {}
            if not isinstance(kw, dict):
                kw = {}
            text = t(key, **kw)
            prefix = widget.property('_t_prefix') or ''
            suffix = widget.property('_t_suffix') or ''
            widget.setText(f"{prefix}{text}{suffix}")
        except Exception:
            pass

    # QLabel / QPushButton / QCheckBox
    for cls in (QLabel, QPushButton, QCheckBox):
        for w in root.findChildren(cls):
            key = w.property('_t_key')
            if key:
                _apply_text(w, key)

    # QSpinBox suffix
    for w in root.findChildren(QSpinBox):
        sk = w.property('_t_suffix_key')
        if sk:
            try:
                w.setSuffix(t(sk))
            except Exception:
                pass

    # QLineEdit placeholder
    for w in root.findChildren(QLineEdit):
        pk = w.property('_t_placeholder_key')
        if pk:
            try:
                w.setPlaceholderText(t(pk))
            except Exception:
                pass

    # QComboBox items
    for w in root.findChildren(QComboBox):
        keys = w.property('_t_item_keys')
        if keys:
            try:
                for i, k in enumerate(keys):
                    if i < w.count() and k:
                        w.setItemText(i, t(k))
            except Exception:
                pass

    # QAction (для toolbars/menus)
    for a in root.findChildren(QAction):
        key = a.property('_t_key')
        if key:
            try:
                a.setText(t(key))
            except Exception:
                pass


def _reapply_theme_roles(root):
    """Round 337: перегенерирует inline-стиль виджетов, помеченных
    свойством '_theme_role', из ТЕКУЩЕГО (после apply_theme()) словаря
    COLORS. Round 335's unpolish/polish sweep обновляет только QSS-
    каскад (QApplication.setStyleSheet) — виджеты со своим собственным
    setStyleSheet(f"...{COLORS[...]}...") держат значение, вычисленное
    в момент СОЗДАНИЯ, потому что это обычный Python f-string, а не
    Qt QSS-правило, которое Qt мог бы пересчитать заново."""
    from PyQt5.QtWidgets import QLabel
    for w in root.findChildren(QLabel):
        role = w.property('_theme_role')
        if role == 'section_header':
            try:
                w.setStyleSheet(
                    f"color: {COLORS['secondary']}; font-size: 13px;"
                    f" font-weight: bold; padding: 4px 0;")
            except Exception:
                pass


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
# Round 335: теперь меняем И background/surface/card — юзер: «цветовая
# схема не везде работает основной фон программы остаётся без
# изменения». Кортеж: (primary, primary_dark, secondary, background,
# surface, card). Background — самый тёмный (основной фон), surface —
# nav-bar / диалоги, card — карточки списка. Все три выбраны
# подкрашенными в сторону primary для целостного вида.
THEME_PALETTES = {
    # Round 280: новый дефолт — бирюзово-голубая палитра как у референса.
    'default': ('#00C8E6', '#0099B3', '#26D4F5',
                '#06141B', '#0F2530', '#16313D'),
    'purple':  ('#7C6CF7', '#5A4DC5', '#4ECDC4',
                '#10101F', '#1E1E2E', '#28283C'),
    'blue':    ('#2196F3', '#1976D2', '#03DAC5',
                '#0A1421', '#13243A', '#1B304E'),
    'green':   ('#4CAF50', '#388E3C', '#00BCD4',
                '#0A1A0E', '#13301A', '#1B4225'),
    'orange':  ('#FF9800', '#F57C00', '#FFB74D',
                '#1F140A', '#332010', '#46301A'),
    'red':     ('#F44336', '#D32F2F', '#FF7043',
                '#1F0E0E', '#331818', '#462020'),
}


def apply_theme(theme_code):
    """Round 247: меняет COLORS['primary'/'primary_dark'/'secondary']
    и пересобирает глобальную STYLESHEET. После вызова приложение
    должно перепривязать app.setStyleSheet(STYLESHEET).
    Round 335: дополнительно меняет background / surface / card."""
    global COLORS, STYLESHEET
    palette = THEME_PALETTES.get(theme_code) or THEME_PALETTES['default']
    if len(palette) >= 6:
        (COLORS['primary'], COLORS['primary_dark'], COLORS['secondary'],
         COLORS['background'], COLORS['surface'], COLORS['card']) = palette[:6]
        # card_hover чуть светлее card.
        try:
            from PyQt5.QtGui import QColor as _QC
            c = _QC(COLORS['card']).lighter(125).name()
            COLORS['card_hover'] = c
        except Exception:
            COLORS['card_hover'] = COLORS['card']
    else:
        COLORS['primary'], COLORS['primary_dark'], COLORS['secondary'] = palette[:3]
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
        # Round 351: сериализует конкурентные save() — фоновый воркер
        # (_save_worker, Round 346) и синхронный save() из closeEvent
        # могли писать CONFIG_FILE одновременно, переплетая записи.
        self._save_io_lock = threading.Lock()
        self.playlists = []  # [{name, url}]
        self.favorites = set()
        self.last_playlist_url = ""
        self.last_playlist_name = ""
        self.last_epg_url = ""
        self.last_channel_url = ""
        self.last_category = "All"
        self.volume = 80
        # Round 292: 9000мс — выше Android normal (6000), ближе к high.
        # Юзер жалуется на запинку видео — больший буфер устойчивее.
        # Round 328: 9000 → 5000. Юзер: «при автооткрытии последнего
        # канала появляется звук а еще через несколько сек уже и само
        # изображение». 5 сек хватает для стабильности и в 2 раза
        # быстрее старт. Юзер может вернуть 9000 в Настройках.
        self.network_caching_ms = 5000
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
        # Round 278/328: дефолт ЖЁСТКО English (юзер повторил: «язык по
        # умолчанию английский должен быть»). Системная локаль больше
        # не подсасывается на первом запуске — пользователь может
        # сменить язык вручную в Settings.
        self.ui_language = "en"
        # Round 364 (Windows): родительский контроль — паритет с Android.
        self.parental_pin_hash = ""        # SHA-256 hex; "" = выключен
        self.locked_categories = set()     # имена заблокированных категорий
        self.locked_channel_urls = set()   # url'ы заблокированных каналов
        # Round 382: показывать взрослые категории (18+/XXX). По умолчанию
        # скрыто; включение за PIN. Мини-превью при листании списка.
        self.show_adult = False
        self.list_preview = False
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

    def update_playlist(self, index: int, name: str, url: str):
        """Round 364 (Windows): правка своего плейлиста — паритет с
        Android. Меняем имя и/или URL по индексу."""
        if 0 <= index < len(self.playlists):
            self.playlists[index] = {'name': name, 'url': url}
            self.save_async()

    # ---- Round 364: родительский контроль ----
    def parental_enabled(self) -> bool:
        return bool(getattr(self, 'parental_pin_hash', ''))

    @staticmethod
    def _pin_hash(pin: str) -> str:
        import hashlib as _hl
        return _hl.sha256(pin.encode('utf-8', 'ignore')).hexdigest()

    def check_pin(self, pin: str) -> bool:
        return bool(self.parental_pin_hash) and \
            self._pin_hash(pin) == self.parental_pin_hash

    def set_pin(self, pin: str):
        self.parental_pin_hash = self._pin_hash(pin)
        self.save_async()

    def clear_pin(self):
        self.parental_pin_hash = ''
        self.save_async()

    def channel_configured_locked(self, ch) -> bool:
        """Настроена ли блокировка канала (точечно или через категорию).
        НЕ учитывает сессионную разблокировку — для значка замка."""
        if not self.parental_enabled():
            return False
        try:
            if ch.url in self.locked_channel_urls:
                return True
            if not self.locked_categories:
                return False
            grp = ch.group or ''
            for part in grp.replace('|', ';').replace(',', ';').split(';'):
                p = part.strip()
                if p and p in self.locked_categories:
                    return True
        except Exception:
            pass
        return False

    def toggle_channel_lock(self, url: str) -> bool:
        if url in self.locked_channel_urls:
            self.locked_channel_urls.discard(url)
            locked = False
        else:
            self.locked_channel_urls.add(url)
            locked = True
        self.save_async()
        return locked

    def get_channel_state(self, url: str) -> dict:
        if not url:
            return {}
        return dict(self.per_channel_state.get(url, {}))

    def update_channel_state(self, url: str, **kv):
        """Round 360: точечное СЛИЯНИЕ ключей в per-channel state.
        save_channel_state ЗАМЕНЯЕТ весь dict целиком — из-за этого
        выбор аудиодорожки терялся: фоновое сохранение при уходе с
        канала не смогло прочитать текущую дорожку из VLC (стрим уже
        останавливался) → построило state БЕЗ ключа audio_track →
        замена стёрла ранее сохранённое значение. Здесь же обновляются
        только переданные ключи, остальное сохраняется."""
        if not url:
            return
        st = self.get_channel_state(url)
        st.update(kv)
        self.save_channel_state(url, st)
        self.save_async()

    def save_channel_state(self, url: str, state: dict):
        if not url or not isinstance(state, dict):
            return
        # Filter to known keys to keep storage tight
        # Round 360: + audio_track_name — ID дорожки у HLS-потоков может
        # меняться между сессиями, восстановление умеет fallback по имени.
        cleaned = {k: state[k] for k in
                   ('volume', 'aspect_idx', 'speed_idx', 'position_ms',
                    'audio_track', 'audio_track_name')
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
                self.network_caching_ms = int(data.get('network_caching_ms', 9000))
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
                # Round 359: юзер — «при смене языка он не сохраняет при
                # следующем открытии». Комбо в Настройках предлагает
                # 12+ кодов (включая 'system' и языки без перевода), а
                # этот gate принимал только ru/en/uk/az — любой другой
                # выбор сохранялся на диск, но при старте молча
                # отбрасывался и язык откатывался. Принимаем все коды
                # из SUPPORTED_LANGUAGES: set_ui_language сам корректно
                # резолвит 'system' и делает fallback для языков без
                # перевода.
                stored_lang = data.get('ui_language', '')
                try:
                    valid = {c for c, _l in SUPPORTED_LANGUAGES}
                except Exception:
                    valid = {"ru", "en", "uk", "az", "system"}
                if stored_lang in valid:
                    self.ui_language = stored_lang
                # Round 364: родительский контроль.
                self.parental_pin_hash = data.get('parental_pin_hash', '') or ''
                self.locked_categories = set(data.get('locked_categories', []) or [])
                self.locked_channel_urls = set(data.get('locked_channel_urls', []) or [])
                # Round 382.
                self.show_adult = bool(data.get('show_adult', False))
                self.list_preview = bool(data.get('list_preview', False))
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
            'ui_language': getattr(self, 'ui_language', 'en'),
            'parental_pin_hash': getattr(self, 'parental_pin_hash', '') or '',
            'locked_categories': list(getattr(self, 'locked_categories', set())),
            'locked_channel_urls': list(getattr(self, 'locked_channel_urls', set())),
            'show_adult': bool(getattr(self, 'show_adult', False)),
            'list_preview': bool(getattr(self, 'list_preview', False)),
        }
        # Round 351: атомарная запись (tmp + os.replace) под lock'ом.
        # Раньше писали прямо в CONFIG_FILE: конкурентный save() из
        # двух ниток (фоновый _save_worker + синхронный closeEvent)
        # мог переплести записи, а крэш посреди записи оставлял
        # обрезанный/битый JSON — при следующем запуске конфиг
        # (плейлисты, избранное, настройки) молча терялся целиком.
        try:
            with self._save_io_lock:
                tmp = CONFIG_FILE + '.tmp'
                with open(tmp, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                os.replace(tmp, CONFIG_FILE)
        except Exception as e:
            log_error('Config.save', e)

    def save_async(self):
        """Round 260: фоновое сохранение конфига. Юзер: «программа
        сильно тормозит». При переключении каналов вызывалось save()
        ДВАЖДЫ синхронно (в _save_current_channel_state и в play_url),
        каждый раз — полный JSON dump 50+ KB на диск. На HDD/медленном
        SSD это давало ощутимый микро-фриз. Дамп идёт в daemon-нитке,
        UI не ждёт.

        Round 346: раньше каждый вызов спавнил НОВУЮ OS-нитку через
        threading.Thread(...).start(). Юзер поймал watchdog-стек:
          play_url → save_async → threading.py:start → wait → wait
        11.2с main thread стоял внутри Thread.start()'а — ЭТО САМО
        СОЗДАНИЕ нитки зависло, потому что к моменту клика на канал
        уже крутилось много других фоновых ниток (VLC warm-up, EPG-
        парсинг, logo-fetch, enrichment) и ОС не могла сразу
        распланировать ещё одну (start() ждёт когда новая нитка
        реально начнёт исполняться и выставит внутренний Event).
        play_url и _save_current_channel_state вызывают save_async()
        на КАЖДОЕ переключение канала — при быстром зэппинге это
        плодило нитки одну за одной поверх уже перегруженной системы.
        Теперь один ДОЛГОЖИВУЩИЙ воркер стартует один раз за всю
        сессию; повторные save_async() просто будят его через Event —
        никаких новых OS-ниток на каждый клик."""
        try:
            import threading as _th
            self._save_pending = True
            if not hasattr(self, '_save_event'):
                self._save_event = _th.Event()
            self._save_event.set()
            if not getattr(self, '_save_worker_started', False):
                self._save_worker_started = True
                _th.Thread(target=self._save_worker, daemon=True,
                          name='cfg-save').start()
        except Exception as e:
            log_error('Config.save_async', e)
            try: self.save()
            except Exception: pass

    def _save_worker(self):
        """Round 346: единственная долгоживущая нитка, обслуживающая
        ВСЕ вызовы save_async() за сессию — см. комментарий там."""
        while True:
            self._save_event.wait()
            self._save_event.clear()
            if getattr(self, '_save_pending', False):
                self._save_pending = False
                try:
                    self.save()
                except Exception as e:
                    log_error('Config._save_worker', e)


# ============================================================
# Round 364 (Windows): родительский контроль — паритет с Android.
# ============================================================
# Сессионная разблокировка: после верного PIN блокировки сняты до
# конца сеанса (перезапуск снова включает защиту) — иначе зэппинг
# через заблокированную зону спрашивал бы PIN на каждый канал.
_PARENTAL_SESSION_UNLOCKED = False

# Round 382: ключевые слова взрослых категорий (18+/XXX). Матчим по имени
# ГРУППЫ (категории), а не по названию канала — поэтому подстрока
# безопасна: категория «Adult»/«XXX»/«18+» = взрослый контент, а канал
# «Adult Swim» лежит в другой группе и под фильтр не попадает.
_ADULT_KEYWORDS = (
    "18+", "xxx", "porn", "adult", "erotic",
    "эротик", "для взрослых", "взрослое", "взрослы",
)


def is_adult_group(group) -> bool:
    """true, если имя группы/категории относится к взрослому контенту."""
    if not group:
        return False
    g = str(group).lower()
    return any(k in g for k in _ADULT_KEYWORDS)


def channel_is_adult(ch) -> bool:
    """true, если канал принадлежит взрослой категории."""
    grp = getattr(ch, 'group', None)
    if not grp:
        return False
    canonical = str(grp).split(';')[0].split(',')[0].split('|')[0].strip()
    return is_adult_group(canonical) or is_adult_group(grp)


def parental_is_locked(config, ch) -> bool:
    """Нужен ли PIN перед просмотром канала."""
    if _PARENTAL_SESSION_UNLOCKED:
        return False
    return config.channel_configured_locked(ch)


def ask_pin(parent, config, on_success, unlock_session=True, on_cancel=None):
    """Диалог ввода PIN. При верном — on_success(). Неверный — просим
    ещё раз. Отмена — on_cancel()."""
    from PyQt5.QtWidgets import QInputDialog, QLineEdit
    while True:
        pin, ok = QInputDialog.getText(
            parent, t('parental_control'), t('parental_enter_pin'),
            QLineEdit.Password)
        if not ok:
            if on_cancel:
                on_cancel()
            return
        if config.check_pin(pin):
            if unlock_session:
                global _PARENTAL_SESSION_UNLOCKED
                _PARENTAL_SESSION_UNLOCKED = True
            on_success()
            return
        # неверный — цикл повторится
        from PyQt5.QtWidgets import QMessageBox
        QMessageBox.warning(parent, t('parental_control'),
                            t('parental_wrong_pin'))


def ask_new_pin(parent, config, on_done=None):
    """Диалог установки нового PIN (4–8 цифр)."""
    from PyQt5.QtWidgets import QInputDialog, QLineEdit, QMessageBox
    pin, ok = QInputDialog.getText(
        parent, t('parental_set_pin'), t('parental_new_pin'),
        QLineEdit.Password)
    if not ok:
        return
    if pin.isdigit() and 4 <= len(pin) <= 8:
        global _PARENTAL_SESSION_UNLOCKED
        config.set_pin(pin)
        _PARENTAL_SESSION_UNLOCKED = False
        QMessageBox.information(parent, t('parental_control'),
                               t('parental_pin_set'))
        if on_done:
            on_done()
    else:
        QMessageBox.warning(parent, t('parental_set_pin'),
                            t('parental_new_pin'))


class LoadPlaylistThread(QThread):
    """Background thread for loading playlists."""
    finished = pyqtSignal(object)
    error = pyqtSignal(str)

    def __init__(self, url):
        super().__init__()
        self.url = url

    def run(self):
        # Round 298: 3 попытки с экспонентой 1с/2с/4с — некоторые CDN
        # (ucoz, flussonic) рандомно обрывают TCP при первом запросе.
        # Юзер: `RemoteDisconnected: Remote end closed connection`.
        import time as _t
        last_err = None
        for attempt in range(3):
            try:
                log_info('playlist',
                         f"loading {self.url} (try {attempt+1}/3)")
                if os.path.isfile(self.url):
                    result = load_playlist_file(self.url)
                else:
                    result = fetch_playlist(self.url)
                chs = getattr(result, 'channels', []) or []
                groups = {c.group for c in chs if c.group}
                sample = sorted(groups)[:3]
                log_info('playlist',
                         f"ok channels={len(chs)} groups={len(groups)} "
                         f"sample={sample}")
                self.finished.emit(result)
                return
            except Exception as e:
                last_err = e
                log_warn('playlist',
                         f"try {attempt+1} failed: {type(e).__name__}: {e}")
                if attempt < 2:
                    _t.sleep((attempt + 1) * 1.5)
        log_error('LoadPlaylistThread', last_err, extra=f"url={self.url}")
        self.error.emit(str(last_err))


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
        с raw.githubusercontent.com (CDN, ~100мс) вместо пагинации API.
        Round 315: добавлен cache-busting `?t=<ts>` — у raw-githubusercontent
        TTL ~5 минут на CDN, и Cache-Control в запросе CDN игнорирует.
        Юзер: «есть build 99 уже 2 минуты, а обновление видит только 98»."""
        try:
            import time as _t
            url = f"{self.FAST_VERSION_JSON}?t={int(_t.time())}"
            log_info('update', f"fast path: {url}")
            raw = self._fetch(url,
                              {'User-Agent': 'TVViewer-Windows',
                               'Cache-Control': 'no-cache',
                               'Pragma': 'no-cache'})
            if not raw:
                return None
            obj = json.loads(raw)
            code = int(obj.get('versionCode', 0))
            if code <= 0:
                return None
            # Round 301: вернулись к ZIP-only. Round 296 пытался дать
            # юзеру одним EXE, но onefile упал у него с
            # «_PYI_APPLICATION_HOME_DIR is not defined!». exeUrl
            # остаётся как fallback на случай старых релизов в JSON.
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
            # Round 301: ПРЕДПОЧИТАЕМ ZIP. Onefile EXE падал у юзера
            # с «_PYI_APPLICATION_HOME_DIR is not defined!». exe_asset
            # сохраняем как fallback для совместимости со старыми релизами.
            zip_asset = next((a for a in assets
                              if a.get('name', '').lower().endswith('.zip')), None)
            exe_asset = next((a for a in assets
                              if 'update' in a.get('name', '').lower()
                              and a.get('name', '').lower().endswith('.exe')), None)
            if exe_asset is None:
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
        # Round 295: тройной транспорт как у UpdateCheckThread. Юзер
        # увидел «SSL: CERTIFICATE_VERIFY_FAILED unable to get local
        # issuer certificate» при скачивании обновления — PyInstaller-
        # сборка не несёт cacert.pem. ZIP идёт с github.com (публичный),
        # проверка сертификата не критична.
        def _open(url):
            headers = {'User-Agent': 'TVViewer-Windows'}
            # 1) requests (несёт certifi)
            try:
                import requests as _rq
                r = _rq.get(url, headers=headers, timeout=60, stream=True)
                r.raise_for_status()
                log_info('update', "download via requests")
                return ('requests', r)
            except Exception as e1:
                log_warn('update', f"download requests failed: {type(e1).__name__}")
            # 2) urllib системный SSL
            try:
                req = urllib.request.Request(url, headers=headers)
                resp = urllib.request.urlopen(req, timeout=60)
                log_info('update', "download via urllib")
                return ('urllib', resp)
            except Exception as e2:
                log_warn('update', f"download urllib failed: {type(e2).__name__}")
            # 3) urllib без проверки SSL
            import ssl as _ssl
            ctx = _ssl._create_unverified_context()
            req = urllib.request.Request(url, headers=headers)
            resp = urllib.request.urlopen(req, timeout=60, context=ctx)
            log_warn('update', "download via urllib (UNVERIFIED SSL)")
            return ('urllib', resp)

        try:
            kind, src = _open(self.url)
            read = 0
            if kind == 'requests':
                total = int(src.headers.get('Content-Length') or 0)
                with open(out_path, 'wb') as f:
                    for chunk in src.iter_content(64 * 1024):
                        if not chunk:
                            continue
                        f.write(chunk)
                        read += len(chunk)
                        if total > 0:
                            self.progress.emit(int(read * 100 / total))
                src.close()
            else:
                total = int(src.headers.get('Content-Length') or 0)
                with open(out_path, 'wb') as f:
                    while True:
                        chunk = src.read(64 * 1024)
                        if not chunk:
                            break
                        f.write(chunk)
                        read += len(chunk)
                        if total > 0:
                            self.progress.emit(int(read * 100 / total))
                src.close()
            log_info('update', f"downloaded {read} bytes → {out_path}")
            self.finished.emit(out_path)
        except Exception as e:
            log_error('DownloadUpdateThread', e, extra=f"url={self.url}")
            self.error.emit(str(e))
            try: os.remove(out_path)
            except Exception: pass


def _ps_squote(s: str) -> str:
    """Round 337: экранирование для PowerShell single-quoted строк —
    внутри `'...'` апостроф удваивается (`''`). Юзер с путём вроде
    `C:\\Users\\D'Angelo\\...` (кастомная install-папка с апострофом
    в имени) иначе ломал сгенерированный PS-скрипт синтаксической
    ошибкой — обновление тихо падало, приложение выходило и не
    перезапускалось (видно только в tvviewer_update.log)."""
    return (s or '').replace("'", "''")


def _vbs_dquote(s: str) -> str:
    """Round 337: экранирование для VBScript double-quoted строк —
    внутри `"..."` кавычка удваивается (`""`)."""
    return (s or '').replace('"', '""')


_user32_argtypes_ready = False


def _setup_user32_argtypes():
    """Round 337: явные argtypes/restype для user32-вызовов, которые
    _FocusForcingLineEdit дёргает через ctypes.windll.user32.<fn>(...)
    с ДЕФОЛТНЫМИ c_int сигнатурами. HWND — указательного размера
    (8 байт на 64-bit Windows); без явного wintypes.HWND ctypes может
    молча усечь/неверно замаршалить большие значения хендлов, из-за
    чего SetForegroundWindow/AttachThreadInput/SetFocus тихо
    промахиваются мимо нужного окна вместо ошибки — воспроизводится
    как «на этой машине курсор/фокус просто не работает» без единой
    строки в логе. Настраивается один раз (idempotent через module-
    level флаг)."""
    global _user32_argtypes_ready
    if _user32_argtypes_ready or sys.platform != 'win32':
        return
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        user32.SetForegroundWindow.argtypes = [wintypes.HWND]
        user32.SetForegroundWindow.restype = wintypes.BOOL
        user32.BringWindowToTop.argtypes = [wintypes.HWND]
        user32.BringWindowToTop.restype = wintypes.BOOL
        user32.GetForegroundWindow.argtypes = []
        user32.GetForegroundWindow.restype = wintypes.HWND
        user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, wintypes.LPDWORD]
        user32.GetWindowThreadProcessId.restype = wintypes.DWORD
        user32.AttachThreadInput.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
        user32.AttachThreadInput.restype = wintypes.BOOL
        user32.SetFocus.argtypes = [wintypes.HWND]
        user32.SetFocus.restype = wintypes.HWND
        # keybd_event(BYTE bVk, BYTE bScan, DWORD dwFlags, ULONG_PTR
        # dwExtraInfo) — ULONG_PTR указательного размера, не указатель.
        user32.keybd_event.argtypes = [wintypes.BYTE, wintypes.BYTE,
                                        wintypes.DWORD, ctypes.c_size_t]
        user32.keybd_event.restype = None
        kernel32.GetCurrentThreadId.argtypes = []
        kernel32.GetCurrentThreadId.restype = wintypes.DWORD
        # SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y,
        # int cx, int cy, UINT uFlags) — используется в
        # MainWindow._fast_overlay_track для мгновенного трекинга
        # оверлея во время drag'а окна.
        user32.SetWindowPos.argtypes = [
            wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
            ctypes.c_int, ctypes.c_int, wintypes.UINT]
        user32.SetWindowPos.restype = wintypes.BOOL
        _user32_argtypes_ready = True
    except Exception as e:
        log_error('_setup_user32_argtypes', e)


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
            f"$zip = '{_ps_squote(zip_path)}'; "
            f"$dst = '{_ps_squote(install_dir)}'; "
            f"$log = '{_ps_squote(log_path)}'; "
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
            f'sh.CurrentDirectory = "{_vbs_dquote(install_dir)}"\r\n'
            f'sh.Run "cmd /c ""{_vbs_dquote(bat_path)}""", 0, True\r\n'
            f'sh.Run """{_vbs_dquote(current_exe)}""", 1, False\r\n'
            'Set fso = CreateObject("Scripting.FileSystemObject")\r\n'
            'On Error Resume Next\r\n'
            f'fso.DeleteFile "{_vbs_dquote(bat_path)}"\r\n'
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
        install_dir = os.path.dirname(current)
        # BAT — основная работа: подождать пока процесс умрёт, заменить
        # exe, лог. Round 296: Wait через timeout достаточно (старый
        # процесс os._exit'нулся сразу).
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
        # Round 296: sh.CurrentDirectory = install_dir ОБЯЗАТЕЛЬНО —
        # новый onefile-update.exe собран с --runtime-tmpdir _tvupd,
        # распаковка Python-runtime идёт в CWD\_tvupd. Без задания CWD
        # это был бы system32 (нет прав) → «Failed to load Python DLL».
        vbs = (
            'Set sh = CreateObject("WScript.Shell")\r\n'
            f'sh.CurrentDirectory = "{_vbs_dquote(install_dir)}"\r\n'
            f'sh.Run "cmd /c ""{_vbs_dquote(bat_path)}""", 0, True\r\n'
            f'sh.Run """{_vbs_dquote(current)}""", 1, False\r\n'
            'Set fso = CreateObject("Scripting.FileSystemObject")\r\n'
            'On Error Resume Next\r\n'
            f'fso.DeleteFile "{_vbs_dquote(bat_path)}"\r\n'
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


class LearnedLogos:
    """Round 288: порт Android LearnedLogos. Постоянная on-disk таблица
    {normalize(channel_name) → logo_url}, наполняется из каждого
    распарсенного плейлиста с tvg-logo и переиспользуется когда тот же
    канал встречается в плейлисте БЕЗ logo. Кап 10k entries чтобы JSON
    не разъехался."""
    MAX_ENTRIES = 10000
    BAD_PREFIXES = ('https://www.google.com/s2/favicons',
                    'http://www.google.com/s2/favicons',
                    'data:',)

    def __init__(self, cache_dir: str):
        self.path = os.path.join(cache_dir, 'learned_logos.json')
        self.map: dict = {}
        try:
            if os.path.exists(self.path):
                with open(self.path, 'r', encoding='utf-8') as f:
                    self.map = json.load(f) or {}
        except Exception as e:
            log_error('LearnedLogos.load', e)
            self.map = {}
        log_info('logo', f"learned: {len(self.map)} entries")

    @staticmethod
    def _norm(name: str) -> str:
        if not name:
            return ""
        try:
            from epg_parser import normalize_id
            return normalize_id(name)
        except Exception:
            return name.lower()

    def harvest(self, channels):
        """Записываем (name → logo_url) из каждого канала с tvg-logo.
        Google-favicon URL'ы блокируем — они помечены как мусор Android
        Round 100."""
        added = 0
        # Round 351: 4000 каналов × regex _norm без yield'а — бежит в
        # _enrich_bg на каждую загрузку плейлиста; периодически уступаем
        # GIL (та же причина что во всех парсерах).
        _n = 0
        for ch in channels or []:
            _n += 1
            if _n % 200 == 0:
                time.sleep(0.001)
            url = (ch.logo_url or '').strip()
            if not url or any(url.startswith(p) for p in self.BAD_PREFIXES):
                continue
            k = self._norm(ch.name)
            if not k or k in self.map:
                continue
            self.map[k] = url
            added += 1
            if len(self.map) >= self.MAX_ENTRIES:
                break
        if added:
            log_info('logo', f"learned +{added} (total {len(self.map)})")
            # Round 351: атомарная запись tmp+replace — раньше прямой
            # json.dump в self.path; крэш/выключение посреди записи
            # оставляли битый learned_logos.json, и вся выученная
            # таблица молча терялась при следующем старте.
            try:
                tmp = self.path + '.tmp'
                with open(tmp, 'w', encoding='utf-8') as f:
                    json.dump(self.map, f, ensure_ascii=False, indent=0)
                os.replace(tmp, self.path)
            except Exception as e:
                log_error('LearnedLogos.save', e)
        return added

    def lookup(self, name: str):
        k = self._norm(name)
        if not k:
            return None
        return self.map.get(k)

    def fill_missing(self, channels):
        """Заполняем logo_url у каналов, у которых его нет, из выученной
        таблицы. Применяется после parse_m3u но до set_channels."""
        if not self.map:
            return 0
        applied = 0
        for ch in channels or []:
            if ch.logo_url:
                continue
            url = self.lookup(ch.name)
            if url:
                ch.logo_url = url
                applied += 1
        if applied:
            log_info('logo', f"learned applied {applied} logos")
        return applied


class LogoCache(QObject):
    """Async logo loader with disk cache, shared across pages.

    Round 265: QNAM выкинут — у него в PyInstaller-сборке без cacert.pem
    HTTPS отваливался молча (QNetworkReply.error()==NoError, body пустой,
    папка tvviewer_logos оставалась пустая). Юзер: «лого ни у одного
    канала нету и папка tvviewer_logos пустая». Перенесли на urllib в
    QThread — точно так же как _PhotoFetcher в Round 253.
    """
    logo_ready = pyqtSignal()
    # Round 351: результат фоновой дисковой проверки → main thread.
    # data = bytes файла с диска, либо None если файла нет (тогда идём
    # в сеть). Queued connection — слот исполнится на main.
    _disk_result = pyqtSignal(str, object)

    # Round 269: 6 → 2. Юзер видит фризы везде. На плейлисте 3639
    # каналов 6 одновременных HTTP-потоков + GIL = постоянная фоновая
    # нагрузка. Двух воркеров достаточно — лого подгружаются плавно
    # без блокировки UI.
    # Round 285: 2 → 1. Юзер: «программа тормозит». На слабых машинах
    # параллельные urllib-фетчи + VLC буферизация + main thread =
    # перегрузка контекст-свитчами. Один воркер тянет лого
    # последовательно, на overall-времени почти не сказывается.
    MAX_CONCURRENT = 1
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
        # Round 351: очередь фоновых дисковых проверок (см. get()).
        self._disk_pending: set = set()
        self._disk_queue: list = []
        self._disk_event = threading.Event()
        self._disk_worker_started = False
        self._disk_result.connect(self._on_disk_loaded)
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
        """Round 351: get() больше НЕ трогает диск. Раньше каждый вызов
        делал os.path.exists() + QPixmap(файл) синхронно — а get()
        зовут ВСЕ построчные заполнители списков на main thread
        (каналы, TV-гид, избранное, недавние, оверлей плеера) плюс
        их _refresh_logos-колбэки каждые 400мс, пока качаются лого.
        Под антивирусом один stat стоит десятки мс — сотни строк ×
        десятки мс = многосекундные фризы (юзер ловил 14-15с стеки
        именно через get → os.path.exists).

        Теперь: mem-hit отдаём сразу; иначе URL уходит в очередь
        одной долгоживущей дисковой bg-нитки (_disk_worker): она
        читает БАЙТЫ файла с диска (или убеждается что файла нет) и
        шлёт результат сигналом обратно на main, где происходит только
        in-memory декодирование (QPixmap.loadFromData) — Qt требует
        создавать QPixmap на GUI-нитке, но декодирование из памяти
        стоит микросекунды, антивирус его не трогает. Списки при этом
        получают иконку через тот же logo_ready, что и при сетевой
        закачке — им ничего менять не нужно."""
        if not url or url in self.missing:
            return None
        # Round 268: ранний отсев невалидных URL — иначе фетчер делает
        # 3 транспортных попытки × 4 сек = 12 сек впустую на каждой
        # битой ссылке.
        u = url.strip()
        if not (u.startswith('http://') or u.startswith('https://')):
            self.missing.add(url)
            return None
        cached = self.icons.get(url)
        if cached is not None:
            return cached
        if (url not in self._disk_pending and url not in self._inflight
                and url not in self._queue):
            self._disk_pending.add(url)
            self._disk_queue.append(url)
            self._disk_event.set()
            if not self._disk_worker_started:
                self._disk_worker_started = True
                try:
                    threading.Thread(target=self._disk_worker, daemon=True,
                                     name='logo-disk').start()
                except Exception as e:
                    self._disk_worker_started = False
                    log_error('LogoCache.disk_worker.spawn', e)
        return None

    def _disk_worker(self):
        """Round 351: единственная долгоживущая нитка для ВСЕХ дисковых
        операций LogoCache — чтение кэшированных PNG и запись новых
        (см. _on_done). Main thread диск не трогает вообще."""
        while True:
            self._disk_event.wait()
            self._disk_event.clear()
            while self._disk_queue:
                try:
                    job = self._disk_queue.pop(0)
                except IndexError:
                    break
                if isinstance(job, tuple) and job[0] == 'write':
                    # Запись скачанного лого (см. _on_done) — раньше
                    # шла синхронно на main thread по одному AV-
                    # сканируемому файлу на каждое скачанное лого.
                    _, url, data = job
                    try:
                        with open(self._path(url), 'wb') as f:
                            f.write(data)
                    except Exception as e:
                        log_error('logo.write_disk', e, extra=url)
                    time.sleep(0.001)
                    continue
                url = job
                data = None
                try:
                    p = self._path(url)
                    if os.path.exists(p):
                        with open(p, 'rb') as f:
                            data = f.read()
                except Exception:
                    data = None
                try:
                    self._disk_result.emit(url, data)
                except Exception as e:
                    log_error('LogoCache._disk_worker.emit', e)
                # Уступаем GIL между файлами — I/O и так отпускает его,
                # но при прогретом OS-кэше чтение может быть чисто
                # CPU-bound.
                time.sleep(0.001)

    def _submit_disk_write(self, url, data):
        """Round 351: асинхронная запись файла через дисковую нитку."""
        self._disk_queue.append(('write', url, data))
        self._disk_event.set()
        if not self._disk_worker_started:
            self._disk_worker_started = True
            try:
                threading.Thread(target=self._disk_worker, daemon=True,
                                 name='logo-disk').start()
            except Exception as e:
                self._disk_worker_started = False
                log_error('LogoCache.disk_worker.spawn', e)

    def _on_disk_loaded(self, url, data):
        """Round 351: main-thread слот — только in-memory декодирование."""
        self._disk_pending.discard(url)
        try:
            if data:
                pm = QPixmap()
                if pm.loadFromData(data) and not pm.isNull():
                    icon = QIcon(pm)
                    if len(self.icons) < self.MAX_ICONS_IN_MEM:
                        self.icons[url] = icon
                    if not self._emit_timer.isActive():
                        self._emit_timer.start()
                    return
            # Файла на диске нет (или битый) — обычная сетевая закачка.
            if url not in self._inflight and url not in self._queue:
                self._queue.append(url)
                self._pump()
        except Exception as e:
            log_error('LogoCache._on_disk_loaded', e, extra=url)

    def _prescan_missing_bg(self, urls):
        """Round 349: юзер поймал watchdog-стек 14.3с ПРЯМО в чанкованном
        pre-queue (Round 347): _step → get → os.path.exists. Чанки по 200
        не спасли, потому что под нагруженным антивирусом ОДИН
        os.path.exists() может стоить десятки миллисекунд — 200 таких
        вызовов подряд всё равно давали многосекундный блок ДО того как
        QTimer успевал отдать управление обратно в event loop.

        Эта функция делает САМУ дисковую часть (os.path.exists) —
        единственную медленную часть — вызываемую из ЛЮБОЙ (фоновой)
        нитки: только читает self.icons/self.missing и стучится в
        файловую систему, ничего не мутирует и не трогает Qt/QThread,
        так что потокобезопасна для параллельного чтения с main thread.
        Возвращает список URL, которым реально нужна закачка — дальше
        их можно поставить в очередь на main thread БЕЗ единого
        обращения к диску (см. enqueue/_queue_logo_urls_chunked)."""
        result = []
        _n = 0
        for url in urls:
            # Round 351: страховочный yield — os.path.exists отпускает
            # GIL на syscall'е, но при прогретом OS-кэше цикл почти
            # чисто CPU-bound на тысячах URL.
            _n += 1
            if _n % 500 == 0:
                time.sleep(0.001)
            if not url or url in self.missing or url in self.icons:
                continue
            u = url.strip()
            if not (u.startswith('http://') or u.startswith('https://')):
                continue
            if os.path.exists(self._path(url)):
                continue
            result.append(url)
        return result

    def enqueue(self, url: str):
        """Round 349: лёгкая версия get() для уже прескан(ен)ных URL —
        никакого os.path.exists()/QPixmap, только in-memory проверки и
        добавление в очередь. Безопасно звать пачками на main thread."""
        if not url or url in self.missing or url in self.icons:
            return
        if url not in self._inflight and url not in self._queue:
            self._queue.append(url)
            self._pump()

    def set_paused(self, paused: bool):
        """Round 285: глобальная пауза кэша. PlayerPage ставит paused=True
        пока юзер смотрит видео — кэш не запускает новые лого-фетчи и
        не конкурирует с VLC за CPU/сеть. Уже стартовавшие воркеры
        дойдут естественно."""
        try:
            self._paused = bool(paused)
            if not paused:
                self._pump()
        except Exception as e:
            log_error('LogoCache.set_paused', e)

    def _pump(self):
        # Round 269: circuit breaker — пока пауза, не спавним новых
        # воркеров. Юзер не должен страдать из-за плейлиста с дохлыми
        # лого-ссылками.
        import time as _t
        if _t.monotonic() < self._paused_until:
            return
        # Round 285: глобальная пауза от PlayerPage — пока юзер смотрит
        # видео, не запускаем новые лого-фетчи.
        if getattr(self, '_paused', False):
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
            # Round 351: запись на диск — через дисковую bg-нитку.
            # Раньше open/write шли прямо здесь (main-thread слот),
            # по одному AV-сканируемому файлу на КАЖДОЕ скачанное
            # лого — во время массовой закачки это давало постоянные
            # подёргивания, в том числе поверх играющего видео.
            self._submit_disk_write(url, data)
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
        except Exception:
            # Round 381: фоновое ДЕКОРАТИВНОЕ фото (picsum.photos). Таймаут
            # или обрыв сети здесь — не ошибка приложения, а просто «фон не
            # подгрузился» (остаётся градиент). НЕ логируем — раньше это
            # засоряло лог ERROR'ами с трейсбеком.
            data = b""
        try:
            self.image_ready.emit(data)
        except Exception:
            pass


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

        self._home_title = QLabel(t('app_name'))
        self._home_title.setProperty('_t_key', 'app_name')
        self._home_title.setStyleSheet(
            "color: white; font-size: 48px; font-weight: bold;"
            " background: transparent;")
        col.addWidget(self._home_title)

        self.subtitle = QLabel("TVViewer")
        self.subtitle.setStyleSheet(
            "color: #00CEC9; font-size: 18px; background: transparent;")
        col.addWidget(self.subtitle)

        col.addSpacing(40)

        # Большая фиолетовая кнопка «Прямой эфир».
        self.btn_live = QPushButton("▶  " + t('live'))
        self.btn_live.setProperty('_t_key', 'live')
        self.btn_live.setProperty('_t_prefix', '▶  ')
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
        self.btn_playlists.setProperty('_t_key', 'playlists')
        self.btn_playlists.setProperty('_t_prefix', '📋  ')
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

    def retranslate_ui(self):
        """Round 350: live-retranslate всех тагированных виджетов."""
        try:
            _retranslate_widgets(self)
        except Exception as e:
            log_error('HomePage.retranslate_ui', e)

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

        # Round 364 (Windows): правка и копирование URL своего плейлиста
        # — паритет с Android.
        self._btn_edit = QPushButton(t('playlist_edit'))
        self._btn_edit.clicked.connect(self.edit_playlist)
        btn_row.addWidget(self._btn_edit)

        self._btn_copy = QPushButton(t('playlist_copy_url'))
        self._btn_copy.clicked.connect(self.copy_playlist_url)
        btn_row.addWidget(self._btn_copy)

        self._btn_remove = QPushButton(t('remove'))
        self._btn_remove.setStyleSheet(f"color: {COLORS['error']};")
        self._btn_remove.clicked.connect(self.remove_playlist)
        btn_row.addWidget(self._btn_remove)

        layout.addLayout(btn_row)
        self.refresh_list()

    def _selected_playlist_index(self):
        """Индекс выбранного СВОЕГО плейлиста в config.playlists (строки
        списка соответствуют config.playlists 1:1)."""
        row = self.playlist_list.currentRow()
        if 0 <= row < len(self.config.playlists):
            return row
        return -1

    def edit_playlist(self):
        idx = self._selected_playlist_index()
        if idx < 0:
            return
        pl = self.config.playlists[idx]
        from PyQt5.QtWidgets import (QDialog, QVBoxLayout, QLabel,
                                     QLineEdit, QDialogButtonBox)
        dlg = QDialog(self)
        dlg.setWindowTitle(t('playlist_edit'))
        dlg.setStyleSheet(STYLESHEET)
        dlg.setMinimumWidth(460)
        v = QVBoxLayout(dlg)
        v.addWidget(QLabel(t('playlist_name_hint')))
        name_edit = QLineEdit(pl.get('name', ''))
        v.addWidget(name_edit)
        v.addWidget(QLabel(t('playlist_url_hint')))
        url_edit = QLineEdit(pl.get('url', ''))
        v.addWidget(url_edit)
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        # Кнопка «Копировать URL» прямо в редакторе.
        btn_copy = bb.addButton(t('playlist_copy_url'),
                                QDialogButtonBox.ActionRole)
        btn_copy.clicked.connect(
            lambda: self._copy_to_clipboard(url_edit.text()))
        v.addWidget(bb)
        bb.accepted.connect(dlg.accept)
        bb.rejected.connect(dlg.reject)
        if dlg.exec_() == QDialog.Accepted:
            new_name = name_edit.text().strip()
            new_url = url_edit.text().strip()
            if new_name and new_url:
                self.config.update_playlist(idx, new_name, new_url)
                self.refresh_list()
                self.playlist_list.setCurrentRow(idx)

    def _copy_to_clipboard(self, text):
        try:
            QApplication.clipboard().setText(text or "")
            from PyQt5.QtWidgets import QMessageBox
            QMessageBox.information(self, t('playlist_copy_url'),
                                   t('playlist_url_copied'))
        except Exception as e:
            log_error('copy_playlist_url', e)

    def copy_playlist_url(self):
        idx = self._selected_playlist_index()
        if idx < 0:
            return
        self._copy_to_clipboard(self.config.playlists[idx].get('url', ''))

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
        """Round 265/350: применяем переводы ко всем сохранённым QLabel/QPushButton."""
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
            if hasattr(self, '_btn_edit'):
                self._btn_edit.setText(t('playlist_edit'))
            if hasattr(self, '_btn_copy'):
                self._btn_copy.setText(t('playlist_copy_url'))
            # Generic sweep for anything tagged with _t_key.
            _retranslate_widgets(self)
        except Exception as e:
            log_error('PlaylistsPage.retranslate_ui', e)

    def on_playlist_click(self, item):
        pl = item.data(Qt.UserRole)
        if pl:
            self.playlist_selected.emit(pl['name'], pl['url'])

    def add_playlist_url(self):
        dlg = QDialog(self)
        dlg.setWindowTitle(t('dialog_add_playlist'))
        dlg.setStyleSheet(STYLESHEET)
        dlg.setMinimumWidth(450)
        form = QFormLayout(dlg)
        name_edit = QLineEdit()
        name_edit.setPlaceholderText(t('placeholder_playlist_name'))
        url_edit = QLineEdit()
        url_edit.setPlaceholderText(t('placeholder_url'))
        form.addRow(t('form_name'), name_edit)
        form.addRow(t('form_url'), url_edit)
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
        dlg.setWindowTitle(t('xtream_codes'))
        dlg.setStyleSheet(STYLESHEET)
        dlg.setMinimumWidth(450)
        form = QFormLayout(dlg)
        name_edit = QLineEdit()
        name_edit.setPlaceholderText(t('placeholder_xtream_name'))
        server_edit = QLineEdit()
        server_edit.setPlaceholderText("http://example.com:8080")
        user_edit = QLineEdit()
        user_edit.setPlaceholderText("login")
        pass_edit = QLineEdit()
        pass_edit.setEchoMode(QLineEdit.Password)
        form.addRow(t('form_name'), name_edit)
        form.addRow(t('form_server'), server_edit)
        form.addRow(t('form_username'), user_edit)
        form.addRow(t('form_password'), pass_edit)
        status = QLabel("")
        status.setStyleSheet(f"color: {COLORS['text_secondary']};")
        form.addRow(status)
        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        form.addRow(btns)

        # Round 351: XtreamApi.authenticate — сетевой urlopen с
        # timeout=15 — выполнялся СИНХРОННО на main thread (processEvents
        # перед ним — костыль, не спасающий от 15с фриза на недоступном
        # сервере). Сетевую часть уносим в bg-нитку, результат
        # возвращаем на main через MainWindow._invoke_on_main (модальный
        # exec_-цикл диалога прокачивает те же события).
        login_state = {'busy': False}

        def _try_login():
            if login_state['busy']:
                return
            srv = server_edit.text().strip()
            usr = user_edit.text().strip()
            pwd = pass_edit.text()
            name = name_edit.text().strip() or "Xtream"
            if not (srv and usr and pwd):
                status.setText("Заполните сервер, логин и пароль.")
                return
            login_state['busy'] = True
            status.setText("Проверяю…")

            def _apply(info):
                login_state['busy'] = False
                if info is None:
                    status.setText("Не удалось войти. Проверьте данные.")
                    return
                url = XtreamApi.build_m3u_url(srv, usr, pwd)
                self.config.playlists.append({'name': name, 'url': url})
                self.config.save_async()
                self.refresh_list()
                dlg.accept()

            mw = self.window()

            def _bg():
                try:
                    info = XtreamApi.authenticate(srv, usr, pwd)
                except Exception:
                    info = None
                # Round 313-паттерн: сигнал MainWindow вместо QTimer
                # из чужой нитки.
                try:
                    mw._invoke_on_main.emit(lambda i=info: _apply(i))
                except Exception as e:
                    log_error('xtream_auth.dispatch', e)

            import threading as _th
            _th.Thread(target=_bg, daemon=True, name='xtream-auth').start()

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
        # Round 302: каретка в этом QLineEdit брала цвет из QPalette.Text
        # (по умолчанию чёрный на тёмной теме Windows) — на тёмном фоне
        # её просто не было видно. Юзер: «в списке каналов в поле поиск
        # при клике на него нет видимого курсора». Та же фиксация, что
        # для _overlay_search в Round 293.
        try:
            from PyQt5.QtGui import QPalette
            pal = self.search_edit.palette()
            pal.setColor(QPalette.Text, QColor("#FFFFFF"))
            pal.setColor(QPalette.WindowText, QColor("#FFFFFF"))
            pal.setColor(QPalette.Base, QColor(COLORS['surface']))
            pal.setColor(QPalette.Highlight, QColor(COLORS['primary']))
            pal.setColor(QPalette.HighlightedText, QColor("#FFFFFF"))
            self.search_edit.setPalette(pal)
        except Exception:
            pass
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
        # Round 382: взрослые категории (18+/XXX) не показываем в панели,
        # пока не включён показ в настройках (за PIN).
        _show_adult = getattr(self.config, 'show_adult', False)
        cats = sorted(set(
            ch.group for ch in channels
            if ch.group and (_show_adult or not is_adult_group(ch.group))))
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
        # Round 351: убран btn.setStyleSheet(STYLESHEET) на каждую
        # кнопку — это re-parse ПОЛНОГО глобального QSS (сотни строк)
        # на каждую из 100-300 категорий, на КАЖДЫЙ клик по категории
        # (select_category зовёт rebuild_categories). Глобальный
        # стиль уже применён через app.setStyleSheet — свежесозданной
        # кнопке с уже выставленным objectName он применится сам при
        # первом polish.
        for cat in self.categories:
            btn = QPushButton(cat)
            if cat == self.selected_category:
                btn.setObjectName("categoryBtnActive")
            else:
                btn.setObjectName("categoryBtn")
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
        # Round 382: скрываем взрослые категории, пока не включён показ.
        show_adult = getattr(self.config, 'show_adult', False)
        # Build filtered list once
        filtered = []
        for ch in self.channels:
            if not show_adult and channel_is_adult(ch):
                continue
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
        # Round 318: всегда показываем EPG, не только при filtered <= 500.
        # Юзер: «не везде показываются тв программы. … при поиске
        # показывается» — раньше cap 500 включал EPG-надпись только
        # когда поиск/категория сократили список. На общем списке 3639
        # каналов EPG был выключен ради скорости populate. С Round 305
        # первичная пачка всего 50 элементов — даже с get_now_next
        # это <1с, остальные 3589 досыпаются чанками по 100 через
        # QTimer 30мс, EPG читается на каждом чанке.
        show_epg = bool(epg)
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
            # Round 305: первичная пачка 200 → 50. Юзер: «при первом
            # запуске программа замирает есть зависание формы два раза».
            # Один из источников — set_epg/set_channels вызывают
            # filter_channels с EPG-данными, и 200 синхронных
            # _append_channel_item с фуззи-лукапом и letter-tile рендером
            # давали ~1.5 сек заморозки. 50 хватает для верха viewport,
            # остальное подсыпается QTimer'ом без вешания UI.
            first_batch = min(50, len(filtered))
            for i in range(first_batch):
                self._append_channel_item(i, filtered[i], show_epg, epg, favs,
                                          tile=True)
        finally:
            lst.setUpdatesEnabled(True)
        self.count_label.setText(f"{len(filtered)} channels")
        if len(filtered) > 50:
            self._chunk_idx = 50
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
            # Round 364: значок замка у заблокированных каналов.
            lock = "🔒 " if self.config.channel_configured_locked(ch) else ""
            item = QListWidgetItem(f"{lock}{i+1}. {ch.name}{qbadge}{fav}{group_txt}{epg_text}")
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
                # Round 305: 200 → 50 синхронной первичной пачки.
                self._lazy_tile_idx = 50
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

    def retranslate_ui(self):
        """Round 350: live-retranslate. Главный title + search placeholder
        + любые тагированные виджеты."""
        try:
            if hasattr(self, 'title_label'):
                self.title_label.setText(t('channels'))
            if hasattr(self, 'search_edit'):
                self.search_edit.setPlaceholderText(t('search_channels'))
            _retranslate_widgets(self)
        except Exception as e:
            log_error('ChannelsPage.retranslate_ui', e)


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
        """Round 351: чанкование — последний полностью синхронный
        list-rebuild в проекте (тот же класс проблемы, что TvGuide до
        Round 345). При сотнях избранных: get_now_next + letter-tile
        рендер на каждый item держали main thread при каждом заходе
        на вкладку. Первые 50 — сразу, остальное чанками по таймеру."""
        self.channels = channels
        self.epg_data = epg_data
        favs = self.config.favorites
        # Дешёвый полный скан (set-membership) — собираем список.
        pairs = [(idx, ch) for idx, ch in enumerate(channels)
                 if ch.url in favs]
        self.fav_channels = [ch for _i, ch in pairs]
        self.fav_list.setUpdatesEnabled(False)
        try:
            self.fav_list.clear()
            first = pairs[:50]
            for idx, ch in first:
                self._append_fav_item(idx, ch)
        finally:
            self.fav_list.setUpdatesEnabled(True)
        self._fav_pending = pairs[50:]
        if self._fav_pending:
            if not hasattr(self, '_fav_chunk_timer'):
                self._fav_chunk_timer = QTimer(self)
                self._fav_chunk_timer.setInterval(30)
                self._fav_chunk_timer.timeout.connect(self._fill_fav_chunk)
            self._fav_chunk_timer.start()
        self.count_label.setText(f"{len(self.fav_channels)} · {t('favorites')}")

    def _append_fav_item(self, idx, ch):
        now_prog, _ = get_now_next(self.epg_data, ch.tvg_id, ch.name)
        epg = f"  {now_prog.title}" if now_prog else ""
        item = QListWidgetItem(f"♥ {ch.name}{epg}")
        item.setData(Qt.UserRole, idx)
        icon = None
        if self.logo_cache is not None and ch.logo_url:
            icon = self.logo_cache.get(ch.logo_url)
        item.setIcon(icon if icon is not None else make_letter_tile_icon(ch.name))
        self.fav_list.addItem(item)

    def _fill_fav_chunk(self):
        """Round 351: досыпаем избранное по 50 за тик."""
        try:
            pending = getattr(self, '_fav_pending', None)
            if not pending:
                self._fav_chunk_timer.stop()
                return
            batch, self._fav_pending = pending[:50], pending[50:]
            self.fav_list.setUpdatesEnabled(False)
            try:
                for idx, ch in batch:
                    self._append_fav_item(idx, ch)
            finally:
                self.fav_list.setUpdatesEnabled(True)
            if not self._fav_pending:
                self._fav_chunk_timer.stop()
        except Exception as e:
            log_error('_fill_fav_chunk', e)
            self._fav_chunk_timer.stop()

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
            _retranslate_widgets(self)
        except Exception as e:
            log_error('FavoritesPage.retranslate_ui', e)




# ============================================================
# Round 236 (Windows): custom delegate для overlay-списка каналов.
# Аналог Android Round 212 item_overlay_channel.xml — рендерит
# лого + имя + категорию + 3-slot EPG-сетку + полоску прогресса
# по текущей передаче. Имитирует Android Material card-style row.
# ============================================================
class NoWheelComboBox(QComboBox):
    """Round 335: QComboBox по дефолту перехватывает wheelEvent для
    листания значений. Юзер: «при прокрутки мышкой окна он изменяет
    содержимое комбо бокса а должен прокручивать вверх или низ само
    окно скролбар». Глушим колесо на закрытом combo — пускаем event
    наверх к родительскому QScrollArea."""
    def wheelEvent(self, ev):
        ev.ignore()


class NoWheelSpinBox(QSpinBox):
    """Round 335: то же для QSpinBox — колесо больше не меняет значение
    при прокрутке окна настроек."""
    def wheelEvent(self, ev):
        ev.ignore()


class _VideoOverlayHost(QWidget):
    """Round 353: юзер — «сделай чтобы можно было управлять громкостью
    мышью колёсиком и нажатием колеса делать мут/анмут». VLC рисует
    видео в СОБСТВЕННЫЙ дочерний HWND внутри video_frame — Qt мышиных
    событий над видео не получает вовсе. Единственное место, где мышь
    над видео видна Qt — это overlay_host: прозрачное top-level окно,
    висящее ровно поверх видео (там живут часы/OSD/оверлеи). Поэтому
    колесо и среднюю кнопку ловим здесь. Дочерние виджеты (списки
    каналов и т.п.) обрабатывают колесо сами — сюда событие доходит
    только если ребёнок его не принял, т.е. реально «над видео»."""
    def __init__(self, player_page, *args):
        super().__init__(*args)
        self._pp = player_page
        # Round 358: явная стрелка — над видео курсором управляем МЫ.
        self.setCursor(Qt.ArrowCursor)

    def paintEvent(self, ev):
        """Round 358: юзер — «опять курсор крутится при переключении
        каналов а при клике исчезает» (Round 355/356 не добили).
        Первопричина: у layered-окна с per-pixel alpha полностью
        прозрачные пиксели «дырявые» для мыши, курсор реально
        наводится на дочернее vout-окно VLC — а его нитка в момент
        переключения канала занята инициализацией декодера/D3D, и
        Windows показывает busy-курсор над неотвечающим окном, что бы
        ни стояло у него в class cursor. Заливаем ВСЁ окно чёрным с
        альфой 1/255: глазу это невидимо (~0.4% затемнения), но каждый
        пиксель становится попадаемым для мыши — курсор над видео
        теперь ВСЕГДА находится над overlay_host (наш отзывчивый
        GUI-поток, обычная стрелка), а не над окном VLC. Заодно колесо
        и средняя кнопка (Round 353) начинают приходить сюда напрямую,
        без пробрасывания через VLC."""
        try:
            p = QPainter(self)
            p.fillRect(self.rect(), QColor(0, 0, 0, 1))
            p.end()
        except Exception as e:
            log_error('overlay_host.paint', e)

    def wheelEvent(self, ev):
        try:
            self._pp._wheel_volume(ev.angleDelta().y())
            ev.accept()
        except Exception as e:
            log_error('overlay_host.wheel', e)

    def mousePressEvent(self, ev):
        try:
            if ev.button() == Qt.MiddleButton:
                self._pp.toggle_mute()
                ev.accept()
                return
        except Exception as e:
            log_error('overlay_host.mid_click', e)
        super().mousePressEvent(ev)


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
    # Round 313: универсальный «сделай это на main-нитке» сигнал.
    # Qt пишет «QObject::startTimer: Timers can only be used with threads
    # started with QThread» когда наши daemon-Thread'ы (vlc-swap,
    # vlc-audio, vlc-audio-menu, meta-fill) дёргают QTimer.singleShot(0,…)
    # для возврата в GUI. Чистый pyqtSignal эмитится thread-safe и
    # вызывает слот в нитке владельца — никаких таймеров не создаётся,
    # warning не печатается.
    _invoke_on_main = pyqtSignal(object)

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
        # Round 337: generation-токен. Каждый play_url() бампает его;
        # bg-нитки (_swap, reconnect-таймер, _maybe_seek/_maybe_set_audio_track)
        # сверяют захваченное значение перед тем как трогать player/
        # current_media — устаревший вызов (юзер уже переключил канал,
        # пока в фоне ждал реконнект/set_media) тихо бросает работу
        # вместо гонки за общим VLC-плеером.
        self._play_generation = 0
        self._reconnect_scheduled_gen = -1
        # Round 341: единый lock на ВСЕ операции, мутирующие
        # self.player/self.vlc_instance. Юзер после Round 337: «стала
        # зависать чаще и стрим с обрывами». Round 337 добавило
        # generation-guards (защита от stale-вызовов) но НЕ добавило
        # взаимного исключения — toggle_play/_seek_or_switch стали
        # background-нитками (правильно для UI-отзывчивости), но
        # теперь МОГЛИ выполняться КОНКУРЕНТНО с активным _swap
        # (который зовёт stop()/set_media()/play() и может занимать
        # секунды) — interleaved нативные libvlc-вызовы из разных
        # ниток это именно то, что порождает «обрывы»/испорченное
        # состояние плеера. Плюс init_vlc() создавало vlc.Instance()/
        # media_player_new() БЕЗ какой-либо защиты, хотя может
        # звонитьcя из ДВУХ разных ниток (vlc-warm и vlc-ensure)
        # почти одновременно — гонка создания дублирующихся
        # инстансов существовала и раньше, теперь тоже закрыта этим
        # же lock'ом.
        self._vlc_op_lock = threading.Lock()
        # Round 313: подключаем диспатчер ДО любого QTimer-кода.
        self._invoke_on_main.connect(self._run_on_main)
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
        self.btn_back = QPushButton(t('back'))
        self.btn_back.setProperty('_t_key', 'back')
        self.btn_back.clicked.connect(self.back_requested.emit)
        top_bar.addWidget(self.btn_back)
        self.channel_name_label = QLabel(t('channel'))
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
        # Round 353: _VideoOverlayHost — колесо мыши над видео =
        # громкость, средняя кнопка = mute (см. класс).
        self.overlay_host = _VideoOverlayHost(
            self,
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
        # Round 293: УБРАНЫ кнопки «☰ Каналы» / «⚙ Настройки» над
        # видео. Юзер: «убери кнопки которые появляются во время
        # просмотра слева и справо». Всё доступно через LEFT/RIGHT.

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

        self.btn_prev = QPushButton(t('prev'))
        self.btn_prev.setProperty('_t_key', 'prev')
        self.btn_prev.clicked.connect(lambda: self.switch_channel(-1))
        ctrl.addWidget(self.btn_prev)

        self.btn_play = QPushButton(t('pause'))
        self.btn_play.setProperty('_t_key', 'pause')
        self.btn_play.setObjectName("primaryBtn")
        self.btn_play.clicked.connect(self.toggle_play)
        ctrl.addWidget(self.btn_play)

        self.btn_next = QPushButton(t('next'))
        self.btn_next.setProperty('_t_key', 'next')
        self.btn_next.clicked.connect(lambda: self.switch_channel(1))
        ctrl.addWidget(self.btn_next)

        ctrl.addSpacing(20)
        self._vol_label = QLabel(t('vol_label'))
        self._vol_label.setProperty('_t_key', 'vol_label')
        ctrl.addWidget(self._vol_label)
        self.vol_slider = QSlider(Qt.Horizontal)
        # Round 314: усиление громкости. Юзер: «добавь усиление громкости».
        # VLC audio_set_volume принимает 0..200 (software gain до +6 dB,
        # выше — клиппинг). Отметка 100 — нормальный максимум, выше —
        # буст. Цветной хендл (см. stylesheet ниже) визуально намекает.
        self.vol_slider.setRange(0, 200)
        self.vol_slider.setValue(self.config.volume)
        self.vol_slider.setMaximumWidth(220)
        self.vol_slider.setTickPosition(QSlider.TicksBelow)
        self.vol_slider.setTickInterval(50)  # отметки 0/50/100/150/200
        self.vol_slider.setStyleSheet(
            "QSlider::groove:horizontal { height: 6px; background: #2A2A40;"
            " border-radius: 3px; }"
            "QSlider::sub-page:horizontal { background:"
            " qlineargradient(x1:0, y1:0, x2:1, y2:0,"
            " stop:0 #7C6CF7, stop:0.5 #7C6CF7,"
            " stop:0.5 #FF6B6B, stop:1 #FF3333);"
            " border-radius: 3px; }"
            "QSlider::handle:horizontal { background: white; width: 14px;"
            " margin: -5px 0; border-radius: 7px; }")
        self.vol_slider.valueChanged.connect(self.set_volume)
        ctrl.addWidget(self.vol_slider)

        # Extra player controls
        self.btn_aspect = QPushButton(t('aspect_auto'))
        self.btn_aspect.setProperty('_t_key', 'aspect_auto')
        self.btn_aspect.clicked.connect(self.cycle_aspect_ratio)
        ctrl.addWidget(self.btn_aspect)

        self.btn_speed = QPushButton("1.0x")
        self.btn_speed.clicked.connect(self.cycle_speed)
        ctrl.addWidget(self.btn_speed)

        self.btn_audio = QPushButton(t('audio_track'))
        self.btn_audio.setProperty('_t_key', 'audio_track')
        # Round 312: открываем меню вместо циклического переключения.
        # Юзер: «нет выбора аудио дорожки» — Round 310 показал OSD но
        # сам цикл по 2-3 дорожкам это не «выбор». Теперь — QMenu со
        # всеми дорожками, текущая отмечена ✓.
        self.btn_audio.clicked.connect(self.show_audio_track_menu)
        ctrl.addWidget(self.btn_audio)

        self.btn_sleep = QPushButton(t('sleep_timer'))
        self.btn_sleep.setProperty('_t_key', 'sleep_timer')
        self.btn_sleep.clicked.connect(self.configure_sleep_timer)
        ctrl.addWidget(self.btn_sleep)

        self.btn_pip = QPushButton(t('pip'))
        self.btn_pip.setProperty('_t_key', 'pip')
        self.btn_pip.setToolTip(t('mini_player'))
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

        # Round 292: Лейбл с разрешением канала. Юзер: «не показывает
        # текущее разрешения канала». Опрашиваем VLC после старта
        # play_url через QTimer (видео-размер доступен только когда
        # декодер уже зацепился, обычно 1-3 сек после set_media).
        # Round 302: фон и шрифт как у часов — юзер: «разрешение под
        # часами его фон и шрифт сделай так же как и часы». ClockLabel
        # рендерит белый текст с чёрной обводкой без layered-window,
        # подходит для поверх-видео-оверлея.
        self.resolution_label = ClockLabel(self.overlay_host)
        # Round 304: шрифт вдвое меньше часов (часы 24pt → разрешение 12pt).
        self.resolution_label.setFont(QFont('Segoe UI', 12, QFont.Bold))
        self.resolution_label.setStyleSheet("background: transparent;")
        self.resolution_label.hide()
        # Round 331: большой OSD цифр ввода номера канала. Юзер: «при
        # вводе от руки номер канала пускай пишет вводимый номер 165 с
        # начало 1 потом 6 потом 5 и если нет 1-2 сек то переходит на
        # этот канал». ClockLabel рендерит белый текст с чёрной обводкой
        # без layered-window — идеально поверх-видео. Прозрачный фон,
        # центр-верх. _number_timer (1500мс) уже коммитит выбор.
        self.number_input_osd = ClockLabel(self.overlay_host)
        self.number_input_osd.setFont(QFont('Segoe UI', 96, QFont.Bold))
        self.number_input_osd.setStyleSheet("background: transparent;")
        self.number_input_osd.hide()
        self._resolution_poll_timer = QTimer(self)
        self._resolution_poll_timer.setInterval(1000)
        self._resolution_poll_timer.timeout.connect(self._poll_resolution)
        self._resolution_poll_count = 0

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
        self._channels_overlay_title = QLabel(t('panel_channels'))
        self._channels_overlay_title.setProperty('_t_key', 'panel_channels')
        self._channels_overlay_title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(self._channels_overlay_title)
        # Round 292: overlay_host — Qt.Tool top-level window. На
        # Windows клик в QLineEdit ВНУТРИ Tool-окна часто не отдаёт
        # ему keyboard focus (focus остаётся на owner-окне MainWindow).
        # Каретка не мигает, ввод не идёт. Подкласс forcing-focus:
        # переопределяем mousePressEvent, явно активируем хост-окно
        # и setFocus(Qt.MouseFocusReason). Дополнительно — простой
        # тестируемый stylesheet с белой кареткой через color.
        class _FocusForcingLineEdit(QLineEdit):
            def mousePressEvent(self, ev):
                # Round 320/321/323/324: пройти все слои Qt+Win32
                # активации. Каретка QLineEdit мигает ТОЛЬКО когда
                # `widget.hasFocus()` И `widget.window().isActiveWindow()`.
                # Round 323 дал overlay_host владельца — это убрало
                # отказ Windows активировать Tool-окно. Но Qt-сторонний
                # isActiveWindow() обновляется ТОЛЬКО когда платформа
                # отдаёт WindowActivate-эвент. На некоторых билдах он
                # не приходит вовремя. Дополнительно дёргаем
                # QApplication.setActiveWindow и шлём синтетический
                # WindowActivate.
                super().mousePressEvent(ev)
                try:
                    w = self.window()
                    if w is None:
                        return
                    if sys.platform == 'win32':
                        try:
                            import ctypes
                            _setup_user32_argtypes()
                            hwnd = int(w.winId())
                            user32 = ctypes.windll.user32
                            # Round 337: ALT press/release раньше не были
                            # в try/finally — если SetForegroundWindow (или
                            # что-то между press/release) бросало
                            # исключение, ALT оставался «зажатым» на
                            # уровне ОС (не было парного key-up),
                            # провоцируя системный menu-activation-mode
                            # пока юзер физически не нажмёт Alt сам.
                            user32.keybd_event(0x12, 0, 0, 0)
                            try:
                                user32.SetForegroundWindow(hwnd)
                            finally:
                                user32.keybd_event(0x12, 0, 0x02, 0)
                            user32.BringWindowToTop(hwnd)
                            # Round 324: AttachThreadInput сцепляет нашу
                            # GUI-нитку с потоком foreground-окна — это
                            # обходит ещё один уровень блокировок.
                            try:
                                fg = user32.GetForegroundWindow()
                                if fg:
                                    fg_tid = user32.GetWindowThreadProcessId(fg, 0)
                                    cur_tid = ctypes.windll.kernel32.GetCurrentThreadId()
                                    if fg_tid and cur_tid and fg_tid != cur_tid:
                                        user32.AttachThreadInput(fg_tid, cur_tid, True)
                                        try:
                                            user32.SetFocus(hwnd)
                                        finally:
                                            user32.AttachThreadInput(fg_tid, cur_tid, False)
                            except Exception:
                                pass
                        except Exception:
                            w.activateWindow()
                            w.raise_()
                    else:
                        w.activateWindow()
                        w.raise_()
                    # Round 324: явно делаем overlay_host активным окном
                    # на Qt-стороне — иначе isActiveWindow() остаётся
                    # False и QLineEdit не запускает caret blink timer.
                    try:
                        QApplication.setActiveWindow(w)
                    except Exception:
                        pass
                except Exception:
                    pass
                self.setFocus(Qt.MouseFocusReason)
                # Round 324: синтетический FocusIn — на случай если
                # platform-plugin не выдал событие.
                try:
                    from PyQt5.QtGui import QFocusEvent
                    from PyQt5.QtCore import QEvent, QCoreApplication
                    QCoreApplication.postEvent(
                        self, QFocusEvent(QEvent.FocusIn, Qt.MouseFocusReason))
                except Exception:
                    pass
                # Повторно через 0мс — иногда WM_SETFOCUS приходит
                # после нашего setFocus и сбрасывает его обратно.
                try:
                    QTimer.singleShot(0,
                        lambda s=self: (s.setFocus(Qt.MouseFocusReason),
                                        s.update()))
                except Exception:
                    pass
        self._overlay_search = _FocusForcingLineEdit()
        self._overlay_search.setPlaceholderText(t('search') + "…")
        self._overlay_search.setClearButtonEnabled(True)
        self._overlay_search.setFocusPolicy(Qt.StrongFocus)
        # Round 293: явный QPalette — Qt берёт цвет каретки из
        # QPalette.Text. На некоторых Windows-темах QSS color не доходит
        # до палитры и каретка получалась цвета фона (невидимая).
        try:
            from PyQt5.QtGui import QPalette
            pal = self._overlay_search.palette()
            pal.setColor(QPalette.Text, QColor("#FFFFFF"))
            pal.setColor(QPalette.WindowText, QColor("#FFFFFF"))
            pal.setColor(QPalette.Base, QColor("#050510"))
            pal.setColor(QPalette.Highlight, QColor("#00C8E6"))
            pal.setColor(QPalette.HighlightedText, QColor("#FFFFFF"))
            self._overlay_search.setPalette(pal)
        except Exception:
            pass
        # Высота 44px + крупный курсор — заметно даже на 4K.
        self._overlay_search.setMinimumHeight(40)
        self._overlay_search.setStyleSheet(
            "QLineEdit { background-color: #1A1A2E; color: white;"
            " border: 2px solid #00C8E6; border-radius: 8px;"
            " padding: 8px 12px; font-size: 16px;"
            " selection-background-color: #00C8E6; selection-color: white; }"
            "QLineEdit:focus { border: 3px solid #26D4F5;"
            " background-color: #050510; }")
        # Round 351: дебаунс 200мс (как у ChannelsPage._search_timer).
        # Раньше КАЖДАЯ буква запускала СРАЗУ ДВА полных прохода по
        # всем каналам (4000+): _refresh_channels_overlay (фильтр +
        # сортировка + item-билды с EPG-lookup'ами) и
        # _mirror_search_to_channels_page → cp.filter_channels()
        # (минуя её собственный дебаунс). Набор 10-буквенного запроса =
        # 20 полных сканов списка на main thread — видимый лаг ввода.
        self._overlay_search_timer = QTimer(self)
        self._overlay_search_timer.setSingleShot(True)
        self._overlay_search_timer.setInterval(200)

        def _overlay_search_fire():
            self._refresh_channels_overlay()
            # Round 278: зеркалим в ChannelsPage.search_edit — иначе
            # унаследованный фильтр оставался на вкладке Каналы.
            self._mirror_search_to_channels_page(
                self._overlay_search.text())
        self._overlay_search_timer.timeout.connect(_overlay_search_fire)
        self._overlay_search.textChanged.connect(
            lambda _t: self._overlay_search_timer.start())
        col.addWidget(self._overlay_search)
        self._overlay_list = QListWidget()
        self._overlay_list.setIconSize(QSize(28, 28))
        self._overlay_list.itemClicked.connect(self._overlay_channel_clicked)
        # Round 382: мини-превью выделенной строки при листании.
        self._overlay_list.currentRowChanged.connect(self._on_overlay_row_changed)
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
        self._categories_overlay_title = QLabel(t('panel_categories'))
        self._categories_overlay_title.setProperty('_t_key', 'panel_categories')
        self._categories_overlay_title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(self._categories_overlay_title)
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
        self._center_menu_title = QLabel(t('settings'))
        self._center_menu_title.setProperty('_t_key', 'settings')
        self._center_menu_title.setStyleSheet("color: white; font-size: 18px;"
                            " font-weight: bold; padding-bottom: 4px;")
        inner.addWidget(self._center_menu_title)

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
        # Round 341: юзер: «зачем в настройках панель навигации я её
        # просил добавить в меню настройки которая выходит при клике
        # по стрелкам» — Home/Playlists/TV-гид переезжают сюда (это и
        # есть «меню которое открывается стрелками», LEFT-стадия 3).
        b0a = _row("🏠  " + t('home'),
                   lambda: self._center_menu_action('home'))
        b0a.setProperty('_t_key', 'home'); b0a.setProperty('_t_prefix', '🏠  ')
        b0b = _row("📋  " + t('playlists'),
                   lambda: self._center_menu_action('playlists'))
        b0b.setProperty('_t_key', 'playlists'); b0b.setProperty('_t_prefix', '📋  ')
        b0c = _row("📅  " + t('tv_guide'),
                   lambda: self._center_menu_action('tv_guide'))
        b0c.setProperty('_t_key', 'tv_guide'); b0c.setProperty('_t_prefix', '📅  ')
        b1 = _row("⚙  " + t('settings'),
                  lambda: self._center_menu_action('settings'))
        b1.setProperty('_t_key', 'settings'); b1.setProperty('_t_prefix', '⚙  ')
        b2 = _row("★  " + t('favorites'),
                  lambda: self._center_menu_action('favorites'))
        b2.setProperty('_t_key', 'favorites'); b2.setProperty('_t_prefix', '★  ')
        b3 = _row("⏱  " + t('recent'),
                  lambda: self._center_menu_action('recent'))
        b3.setProperty('_t_key', 'recent'); b3.setProperty('_t_prefix', '⏱  ')
        b4 = _row("🔍  " + t('menu_search'),
                  lambda: self._center_menu_action('search'))
        b4.setProperty('_t_key', 'menu_search'); b4.setProperty('_t_prefix', '🔍  ')
        # Round 364: блокировка/разблокировка ТЕКУЩЕГО канала. Подпись
        # обновляется при показе меня (см. _update_center_menu_lock).
        b5 = _row(t('parental_lock_channel'),
                  lambda: self._center_menu_action('lock_channel'))
        self._center_menu_lock_btn = b5
        for bb in (b0a, b0b, b0c, b1, b2, b3, b4, b5):
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
        + переключается на нужную вкладку.
        Round 341: добавлены home/playlists/tv_guide. Юзер: «зачем в
        настройках панель навигации я её просил добавить в меню
        настройки которая выходит при клике по стрелкам» — Round 336
        по ошибке положил быстрый переход по вкладкам на страницу
        Settings, хотя юзер имел в виду именно этот overlay (LEFT →
        стадия 3, «меню настроек» которое открывается стрелками)."""
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
            elif action == 'home':
                mw.switch_page(getattr(mw, '_home_index', 7))
            elif action == 'playlists':
                mw.switch_page(0)
            elif action == 'tv_guide':
                mw.switch_page(5)
            elif action == 'lock_channel':
                self._toggle_current_channel_lock()
        except Exception:
            pass

    def _toggle_current_channel_lock(self):
        """Round 364: блокировка/разблокировка текущего канала.
        Блокировка — без PIN (PIN уже задан). Разблокировка — с PIN.
        PIN не задан — предлагаем установить, затем блокируем."""
        if not self.channels or self.current_index >= len(self.channels):
            return
        ch = self.channels[self.current_index]
        cfg = self.config
        from PyQt5.QtWidgets import QMessageBox

        def _do_lock():
            locked = cfg.toggle_channel_lock(ch.url)
            self.show_mini_osd(
                t('parental_channel_locked') if locked
                else t('parental_channel_unlocked'))
            self._refresh_channel_lists()

        if not cfg.parental_enabled():
            ask_new_pin(self, cfg, on_done=_do_lock)
            return
        if ch.url in cfg.locked_channel_urls:
            # Разблокировка — с PIN.
            ask_pin(self, cfg, _do_lock, unlock_session=False)
        else:
            _do_lock()

    def _refresh_channel_lists(self):
        """Round 364: перерисовать списки каналов (значки замка)."""
        try:
            mw = self.window()
            if hasattr(mw, 'channels_page'):
                mw.channels_page.filter_channels()
            if hasattr(self, 'channels_overlay') and \
                    self.channels_overlay.isVisible():
                self._refresh_channels_overlay()
        except Exception as e:
            log_error('_refresh_channel_lists', e)

    def _update_center_menu_lock(self):
        """Round 364: подпись кнопки блокировки — Заблокировать/
        Разблокировать в зависимости от состояния текущего канала."""
        try:
            btn = getattr(self, '_center_menu_lock_btn', None)
            if btn is None:
                return
            locked = False
            if self.channels and self.current_index < len(self.channels):
                url = self.channels[self.current_index].url
                locked = url in self.config.locked_channel_urls
            btn.setText(t('parental_unlock_channel') if locked
                        else t('parental_lock_channel'))
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
        self._quick_overlay_title = QLabel(t('panel_quick'))
        self._quick_overlay_title.setProperty('_t_key', 'panel_quick')
        self._quick_overlay_title.setStyleSheet("color: white; font-size: 16px; font-weight: bold;")
        col.addWidget(self._quick_overlay_title)

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

        b_aspect = _btn(t('aspect'), self.cycle_aspect_ratio)
        b_aspect.setProperty('_t_key', 'aspect')
        b_speed = _btn(t('speed'), self.cycle_speed)
        b_speed.setProperty('_t_key', 'speed')
        # Round 312: меню вместо цикла — см. self.show_audio_track_menu.
        b_audio = _btn(t('audio_track'), self.show_audio_track_menu)
        b_audio.setProperty('_t_key', 'audio_track')
        b_sleep = _btn(t('sleep_timer'), self.configure_sleep_timer)
        b_sleep.setProperty('_t_key', 'sleep_timer')
        b_fs = _btn(t('fullscreen'), self.toggle_fullscreen)
        b_fs.setProperty('_t_key', 'fullscreen')
        b_pip = _btn(t('pip'), self._on_pip_clicked)
        b_pip.setProperty('_t_key', 'pip')
        b_fav = _btn("♥ " + t('favorites'), self.toggle_favorite)
        b_fav.setProperty('_t_key', 'favorites')
        b_fav.setProperty('_t_prefix', '♥ ')
        # Round 297: «Показать список каналов» — переключает в левый
        # overlay списка каналов (как LEFT-пресс). Закрывает quick.
        b_show = _btn(t('btn_show_channels'), self._show_channels_from_quick)
        b_show.setProperty('_t_key', 'btn_show_channels')
        col.addStretch()

    def _show_channels_from_quick(self):
        """Round 297: закрыть quick_overlay → открыть channels_overlay
        (stage=1 LEFT-state-machine)."""
        try:
            if self.quick_overlay.isVisible():
                self.quick_overlay.hide()
            # Форсим состояние и применяем stage 1 (каналы).
            self._left_stage = 0
            self._left_dir = 1
            self._apply_left_stage(1)
        except Exception as e:
            log_error('_show_channels_from_quick', e)

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
        self.btn_panel_channels.setProperty('_t_key', 'channels')
        self.btn_panel_channels.setProperty('_t_prefix', '☰ ')
        self.btn_panel_channels.setStyleSheet(
            "background-color: rgba(15, 15, 26, 200); color: white;"
            " padding: 8px 14px; border-radius: 6px; border: 1px solid #7C6CF7;")
        self.btn_panel_channels.clicked.connect(self.toggle_channels_overlay)
        bar.addWidget(self.btn_panel_channels)
        bar.addStretch()
        self.btn_panel_quick = QPushButton("⚙ " + t('settings'))
        self.btn_panel_quick.setProperty('_t_key', 'settings')
        self.btn_panel_quick.setProperty('_t_prefix', '⚙ ')
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
        # Round 292: УБРАЛ Round 285 паузу LogoCache. Юзер: «нет
        # логотипов каналов». Из-за паузы кэш никогда не качал лого
        # если юзер открывал приложение и сразу шёл смотреть. Один
        # урезанный воркер (MAX_CONCURRENT=1) с CPU не конкурирует.
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
        # Round 285: возобновляем LogoCache, юзер ушёл из плеера.
        super().hideEvent(event)
        try:
            self._overlay_sync_timer.stop()
            self.overlay_host.hide()
            if self.logo_cache is not None:
                self.logo_cache.set_paused(False)
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
            # Round 299: лейбл разрешения тоже фиксим — иначе при
            # переходе в fullscreen старая позиция оказывалась в центре.
            self._position_resolution_label()
            # Round 331: large number-input OSD тоже пере-центрируем
            # при ресайзе / fullscreen.
            try:
                if hasattr(self, 'number_input_osd') and self.number_input_osd.isVisible():
                    self.number_input_osd.adjustSize()
                    nw = self.number_input_osd.width()
                    nh = self.number_input_osd.height()
                    pw2 = self.overlay_host.width()
                    ph2 = self.overlay_host.height()
                    self.number_input_osd.move(
                        max(0, (pw2 - nw) // 2), max(0, int(ph2 * 0.15)))
                    self.number_input_osd.raise_()
            except Exception:
                pass
            # Round 330: переезд _mini_osd (канальная карточка с именем).
            # Юзер: «при открытии он запускает последний канал и на
            # полный экран когда делает он информационную панель с
            # именем и т.д. поднимает на верх сдвигает». Карточка
            # позиционировалась в координатах windowed-размера, потом
            # окно переходило в fullscreen, и старый y оказывался
            # около верхнего края.
            try:
                if hasattr(self, '_mini_osd') and self._mini_osd.isVisible():
                    pw = self.overlay_host.width()
                    ph = self.overlay_host.height()
                    if pw > 0 and ph > 0:
                        mw = min(640, max(420, self._mini_osd.sizeHint().width()))
                        mh = self._mini_osd.sizeHint().height()
                        self._mini_osd.setGeometry((pw - mw) // 2,
                                                   ph - mh - 60, mw, mh)
                        self._mini_osd.raise_()
            except Exception:
                pass
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
        """Round 300: ЦИКЛИЧЕСКАЯ state machine — без ping-pong.
          стадии: 0=закрыто 1=каналы 2=категории 3=центр-меню
        LEFT идёт 0→1→2→3→0→1→…; стадия 2 (категории) ПРОПУСКАЕТСЯ
        если категорий < 2. Юзер: «1.Список каналов 2.Категории если
        есть 3.Меню настройки 4.Очищаем экран ничего уже нет».
        """
        if not hasattr(self, 'channels_overlay'):
            return
        self._sync_overlay_host()
        self._step_left_stage(+1)

    def right_press_state_machine(self):
        """Round 300: RIGHT — обратный ход по той же цепочке если
        что-то открыто слева. Юзер: «Так же в обратном порядке при
        нажатии вправо если открыто что либо слева»."""
        if not hasattr(self, 'channels_overlay'):
            return
        any_visible = any(
            getattr(self, n, None) is not None
            and getattr(self, n).isVisible()
            for n in ('channels_overlay', 'categories_overlay',
                      'center_menu_overlay'))
        if not any_visible:
            return False  # ничего не открыто — RIGHT уходит к другому хендлеру
        self._sync_overlay_host()
        self._step_left_stage(-1)
        return True

    def _step_left_stage(self, direction: int):
        """Round 300: шагаем по 0→1→2→3 циклически, пропуская стадии,
        у которых нечего показывать (категории < 2 элементов)."""
        # Ресинхронизация с реальной видимостью — если оверлеи скрыли
        # извне (Esc, alt-tab, .hide_all_overlays), начинаем с 0.
        any_visible = any(
            getattr(self, n, None) is not None
            and getattr(self, n).isVisible()
            for n in ('channels_overlay', 'categories_overlay',
                      'center_menu_overlay'))
        if not any_visible:
            self._left_stage = 0
        stage = self._left_stage
        # Считаем сколько категорий реально есть — если меньше 2
        # (только «All»), пропускаем стадию 2 в любом направлении.
        try:
            cats_count = 0
            for ch in (self.channels or []):
                if ch.group:
                    cats_count += 1
                    if cats_count > 1:
                        break
            has_categories = cats_count > 0
        except Exception:
            has_categories = False
        # Шаг циклически по mod 4.
        for _ in range(4):
            stage = (stage + direction) % 4
            if stage == 2 and not has_categories:
                continue  # пропускаем категории если их нет
            break
        self._left_stage = stage
        self._apply_left_stage(stage)

    def _apply_left_stage(self, stage):
        """Round 251: показывает оверлеи соответствующие стадии 0-3."""
        # Round 382: мини-превью прячем при любой смене стадии оверлеев.
        self._hide_preview_win()
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
            self._update_center_menu_lock()
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
        # Round 288: ЧИТАЕМ selected_category из ChannelsPage — при
        # выборе категории в overlay категорий мы её туда записываем,
        # но raw overlay channels раньше всё равно показывал ВСЕ
        # каналы, игнорируя категорию. Юзер: «при выборе категории
        # он его не открывает». Теперь применяем тот же фильтр.
        sel_cat = None
        recent_set = set()
        try:
            mw = self.window()
            cp = getattr(mw, 'channels_page', None)
            if cp is not None:
                sel_cat = getattr(cp, 'selected_category', None)
                if sel_cat in (None, '', 'All'):
                    sel_cat = None
            # Round 289: «★ Recent» работает не по group, а по
            # recent_urls — отдельная ветка как в ChannelsPage.
            if sel_cat == '★ Recent' or sel_cat == 'Recent':
                recent_set = set(getattr(self.config, 'recent_urls', []) or [])
        except Exception:
            sel_cat = None
        log_info('overlay', f"refresh: cat={sel_cat!r} q={q!r} "
                            f"channels={len(self.channels or [])}")
        # Round 319: если идёт chunk-fill от прошлого вызова, останавливаем.
        try:
            if hasattr(self, '_overlay_chunk_timer'):
                self._overlay_chunk_timer.stop()
        except Exception:
            pass
        # Round 319: чанковая загрузка как в ChannelsPage.filter_channels.
        # Раньше populate 500 элементов с EPG-лукапом шёл синхронно
        # на main thread за 1-3 сек. Юзер: «при клике на поле он
        # список рефреширует и опять ничего» — клик в поле поиска шёл
        # в очередь, пока главная нитка добивала populate. Теперь
        # первичная пачка 30, остальное чанками по 50 через QTimer
        # 25мс — клик/каретка обрабатываются между чанками.
        cap = 500 if not q and not sel_cat else 10000
        # Round 382: скрываем взрослые категории, пока не включён показ.
        show_adult = getattr(self.config, 'show_adult', False)
        filtered = []
        for idx, ch in enumerate(self.channels or []):
            if not show_adult and channel_is_adult(ch):
                continue
            if recent_set:
                if ch.url not in recent_set:
                    continue
            elif sel_cat and (ch.group or "") != sel_cat:
                continue
            if q and q not in (ch.name or "").lower():
                continue
            filtered.append((idx, ch))
            if len(filtered) >= cap:
                break
        # Сохраняем стейт для дочанков.
        self._overlay_filter_state = {
            'items': filtered,
            'next_idx': 0,
            'current_row': -1,
        }
        self._overlay_list.setUpdatesEnabled(False)
        try:
            self._overlay_list.clear()
            first = min(30, len(filtered))
            for k in range(first):
                self._append_overlay_item(*filtered[k])
            self._overlay_filter_state['next_idx'] = first
        finally:
            self._overlay_list.setUpdatesEnabled(True)
        if len(filtered) > 30:
            if not hasattr(self, '_overlay_chunk_timer'):
                self._overlay_chunk_timer = QTimer(self)
                self._overlay_chunk_timer.setInterval(25)
                self._overlay_chunk_timer.timeout.connect(
                    self._fill_overlay_chunk)
            self._overlay_chunk_timer.start()
        # currentRow для играющего канала ставим уже когда чанки
        # дойдут до него; до тех пор просто оставляем -1.
        current_overlay_row = self._overlay_filter_state.get('current_row',
                                                             -1)
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

    def _append_overlay_item(self, idx, ch):
        """Round 319: добавляет ОДИН канал в _overlay_list. Выделен из
        _refresh_channels_overlay чтобы переиспользовать в чанках."""
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
        try:
            _locked = self.config.channel_configured_locked(ch)
        except Exception:
            _locked = False
        _lock_pfx = "🔒 " if _locked else ""
        item = QListWidgetItem(f"{_lock_pfx}{idx+1}. {ch.name}")
        item.setData(Qt.UserRole, idx)
        item.setData(Qt.UserRole + 1, {
            'name': f"{_lock_pfx}{ch.name or ''}",
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
            st = getattr(self, '_overlay_filter_state', None)
            if st is not None and st.get('current_row', -1) < 0:
                st['current_row'] = self._overlay_list.count() - 1
                try:
                    self._overlay_list.setCurrentRow(st['current_row'])
                    from PyQt5.QtWidgets import QAbstractItemView
                    self._overlay_list.scrollToItem(
                        self._overlay_list.currentItem(),
                        QAbstractItemView.PositionAtCenter)
                except Exception:
                    pass

    def _fill_overlay_chunk(self):
        """Round 319: подсыпаем 50 каналов в _overlay_list за тик.
        Между чанками QTimer 25мс возвращает GIL/event loop main thread'у —
        клики на поле поиска обрабатываются мгновенно, каретка мигает."""
        st = getattr(self, '_overlay_filter_state', None)
        if not st:
            self._overlay_chunk_timer.stop()
            return
        items = st['items']
        start = st['next_idx']
        end = min(start + 50, len(items))
        self._overlay_list.setUpdatesEnabled(False)
        try:
            for k in range(start, end):
                self._append_overlay_item(*items[k])
        finally:
            self._overlay_list.setUpdatesEnabled(True)
        st['next_idx'] = end
        if end >= len(items):
            self._overlay_chunk_timer.stop()

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
            self._hide_preview_win()  # Round 382
            try:
                # Используем штатный play_channel — он же сбрасывает
                # стейт и обновляет UI. Передаём текущие channels/epg
                # чтобы не пересоздавать их.
                self.play_channel(idx, self.channels, self.epg_data)
            except Exception:
                pass
            self.channels_overlay.hide()

    # ============================================================
    # Round 382: мини-превью (PiP) выделенного канала при листании.
    # ============================================================
    def _ensure_preview_widgets(self):
        if getattr(self, '_preview_frame', None) is not None:
            return
        from PyQt5.QtWidgets import QFrame
        # Отдельное top-level безрамочное окно (НЕ ребёнок translucent
        # overlay_host — иначе нативная VLC-поверхность на layered-окне
        # может не отрисоваться). Позиционируем над видео вручную.
        fr = QFrame(None, Qt.FramelessWindowHint | Qt.Tool)
        fr.setAttribute(Qt.WA_NativeWindow, True)     # нативный winId для VLC
        fr.setAttribute(Qt.WA_ShowWithoutActivating, True)  # не воровать фокус
        fr.setStyleSheet(
            "background-color: black;"
            " border: 2px solid rgba(124, 108, 247, 220);")
        fr.hide()
        self._preview_frame = fr
        self._preview_player = None
        self._preview_media = None
        self._preview_pending_idx = -1
        self._preview_timer = QTimer(self)
        self._preview_timer.setSingleShot(True)
        self._preview_timer.setInterval(450)
        self._preview_timer.timeout.connect(self._start_preview_win)

    def _position_preview(self):
        fr = getattr(self, '_preview_frame', None)
        if fr is None:
            return
        from PyQt5.QtCore import QPoint
        vf = getattr(self, 'video_frame', None)
        w, h, m = 320, 180, 24
        try:
            tl = vf.mapToGlobal(QPoint(0, 0))
            x = tl.x() + vf.width() - w - m
            y = tl.y() + vf.height() - h - m
        except Exception:
            return
        fr.setGeometry(int(x), int(y), w, h)

    def _on_overlay_row_changed(self, row):
        if not getattr(self.config, 'list_preview', False):
            return
        if row < 0 or not self.channels_overlay.isVisible():
            return
        item = self._overlay_list.item(row)
        if item is None:
            return
        idx = item.data(Qt.UserRole)
        if not isinstance(idx, int):
            return
        # Уже играющий канал не превьюим.
        if idx == self.current_index:
            self._hide_preview_win()
            return
        self._ensure_preview_widgets()
        self._preview_pending_idx = idx
        self._preview_timer.start()

    def _start_preview_win(self):
        if not getattr(self.config, 'list_preview', False):
            return
        idx = getattr(self, '_preview_pending_idx', -1)
        if not (0 <= idx < len(self.channels)):
            return
        if idx == self.current_index or not self.channels_overlay.isVisible():
            self._hide_preview_win()
            return
        if self.vlc_instance is None:
            return
        ch = self.channels[idx]
        try:
            self._ensure_preview_widgets()
            if self._preview_player is None:
                self._preview_player = self.vlc_instance.media_player_new()
                try:
                    self._preview_player.video_set_mouse_input(False)
                    self._preview_player.video_set_key_input(False)
                except Exception:
                    pass
            self._position_preview()
            self._preview_frame.show()
            self._preview_frame.raise_()
            p = self._preview_player
            try:
                p.stop()
            except Exception:
                pass
            hwnd = None
            if sys.platform == 'win32':
                hwnd = int(self._preview_frame.winId())
                p.set_hwnd(hwnd)
            elif sys.platform.startswith('linux'):
                p.set_xwindow(int(self._preview_frame.winId()))
            elif sys.platform == 'darwin':
                p.set_nsobject(int(self._preview_frame.winId()))
            media = self.vlc_instance.media_new(ch.url)
            try:
                net_cache = int(getattr(self.config, 'network_caching_ms', 3000))
            except Exception:
                net_cache = 3000
            media.add_option(f':network-caching={net_cache}')
            ua_cfg = getattr(self.config, 'user_agent', '') or ''
            if ua_cfg:
                media.add_option(f':http-user-agent={ua_cfg}')
            ref = getattr(self.config, 'http_referer', '') or ''
            if ref:
                media.add_option(f':http-referrer={ref}')
            p.set_media(media)
            self._preview_media = media
            try:
                p.audio_set_mute(True)  # превью без звука
            except Exception:
                pass
            if hwnd is not None:
                p.set_hwnd(hwnd)
            p.play()
        except Exception as e:
            log_error('preview.start', e)
            self._hide_preview_win()

    def _hide_preview_win(self):
        tmr = getattr(self, '_preview_timer', None)
        if tmr is not None:
            tmr.stop()
        p = getattr(self, '_preview_player', None)
        if p is not None:
            try:
                p.stop()
            except Exception:
                pass
        fr = getattr(self, '_preview_frame', None)
        if fr is not None:
            fr.hide()

    def _release_preview_player(self):
        self._hide_preview_win()
        p = getattr(self, '_preview_player', None)
        if p is not None:
            try:
                p.release()
            except Exception:
                pass
        self._preview_player = None

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
        """Round 233/350: переводит все доступные подписи на лету
        через generic helper (виджеты тагированы '_t_key' в init_ui /
        _build_*_overlay)."""
        try:
            # Generic sweep — обходит сам PlayerPage и все overlay-окна.
            _retranslate_widgets(self)
            # overlay_host — top-level окно (родитель None), не дочернее
            # PlayerPage, findChildren его не находит. Обрабатываем отдельно.
            host = getattr(self, 'overlay_host', None)
            if host is not None:
                _retranslate_widgets(host)
            if hasattr(self, 'btn_panel_channels'):
                self.btn_panel_channels.setText("☰ " + t('channels'))
            if hasattr(self, 'btn_panel_quick'):
                self.btn_panel_quick.setText("⚙ " + t('settings'))
            # play/pause label зависит от состояния — оставляем как есть.
        except Exception as e:
            log_error('PlayerPage.retranslate_ui', e)

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

    def _install_stall_watch_timer(self):
        """Round 337: вынесено из init_vlc — QTimer(self) обязан
        создаваться в main thread. Вызывается через _invoke_on_main."""
        try:
            if hasattr(self, '_stall_watch_timer'):
                return
            self._stall_watch_timer = QTimer(self)
            self._stall_watch_timer.setInterval(5000)
            self._stall_watch_timer.timeout.connect(self._check_stall)
            self._stall_watch_timer.start()
        except Exception as e:
            log_error('stall_watch.install', e)

    def _vlc_lock_or_recover(self, timeout=8):
        """Round 350: юзер — «если один канал завис не показал то
        остальные уже тоже не показывают». Причина: player.stop() /
        set_media() / audio_set_track() и т.п. и так уже задокументированы
        в этом файле как способные блокировать 5-20+ сек на дохлых
        стримах (Round 279/281/334) — но ВСЕ они сериализуются через
        ОДИН self._vlc_op_lock (Round 341) БЕЗ таймаута. Если конкретный
        стрим настолько плох что нативный вызов виснет не 20 сек, а
        насмерть (зависший TCP-поток, недоступный сервер), lock
        никогда не освобождается — и КАЖДАЯ последующая попытка
        переключить канал (новый _swap) стоит в очереди на этот же
        lock вечно. Юзер видит: один канал завис → все остальные
        каналы тоже перестают открываться.

        Вместо бесконечного ожидания: пробуем взять lock с таймаутом.
        Если не вышло — считаем VLC-подсистему безнадёжно подвисшей,
        заводим НОВЫЙ lock (старая подвисшая нитка однажды доделает
        свой вызов и освободит СТАРЫЙ lock сама по себе, никому уже не
        мешая) и сиротим self.player/self.vlc_instance — следующий
        play_url() пойдёт по обычному пути «player is None» и создаст
        свежий vlc.Instance() с нуля, как при обычном первом запуске.
        Возвращает (lock, recovered) — lock уже захвачен в обоих
        случаях, вызывающий обязан его release()."""
        if self._vlc_op_lock.acquire(timeout=timeout):
            return self._vlc_op_lock, False
        log_warn('vlc', f"op lock stuck >{timeout}s (wedged native call) — "
                 "resetting VLC subsystem so channel switching keeps working")
        self._vlc_op_lock = threading.Lock()
        self.player = None
        self.vlc_instance = None
        self._vlc_op_lock.acquire()
        return self._vlc_op_lock, True

    def init_vlc(self):
        if not HAS_VLC:
            return
        # Round 341: init_vlc() зовётся из ДВУХ разных background-ниток
        # без координации — _ensure_vlc_then_play (клик по каналу,
        # нитка vlc-ensure) и _warm_vlc_async (нитка vlc-warm, стартует
        # через 800мс после запуска). Если юзер кликает канал пока
        # прогрев ещё идёт (частый случай — vlc.Instance() может занять
        # 5-20 сек), ОБА потока входили сюда без всякой защиты: каждый
        # создавал СВОЙ vlc.Instance()/media_player_new() и оба писали
        # в self.vlc_instance/self.player — какая нитка запишет
        # последней, тот и «выиграл», а чей-то Instance/Player просто
        # осиротевал (утечка + возможно недо-освобождённые callback'и).
        # Юзер: «стала зависать чаще и стрим с обрывами». Double-checked
        # locking: если player уже есть — выходим сразу, не тратя время
        # на построение args и не блокируя lock понапрасну.
        if self.player is not None:
            return
        # Round 351: lock в локальную переменную — _vlc_lock_or_recover
        # (Round 350) может подменить self._vlc_op_lock новым объектом,
        # и release() в finally через повторное чтение атрибута отпустил
        # бы уже ЧУЖОЙ lock (RuntimeError / сломанная сериализация).
        _lock = self._vlc_op_lock
        _lock.acquire()
        try:
            if self.player is not None:
                return
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
                # Round 357: УБРАНЫ '--clock-jitter=0' и
                # '--clock-synchro=0' (стояли с Round 284 «для
                # плавности»). Юзер: «при длительном просмотре звук
                # начинает рассинхронизировать». --clock-synchro=0
                # ВЫКЛЮЧАЕТ механизм подстройки часов VLC — а именно он
                # непрерывно подтягивает аудио/видео к часам потока.
                # Часы источника и звуковой карты всегда чуть-чуть
                # расходятся (десятки ppm), и без коррекции за час-два
                # набегает заметный рассинхрон. --clock-jitter=0 ещё и
                # объявлял входной поток «без джиттера», что на live-
                # IPTV неправда. Дефолты VLC (авто-синхронизация,
                # толерантность к джиттеру) именно для этого и сделаны.
                # Round 284: 6 секунд буфера — как Android ExoPlayer
                # «normal» режим (DefaultLoadControl 6000/18000/200/1500).
                # Юзер: «в андроид версии всё работает хорошо». VLC
                # дефолт 1000мс совсем мало для live-IPTV.
                # Round 292: буфер 9 сек — Android «high» mode
                # (DefaultLoadControl 20000/40000) использует ещё больше,
                # но 9 сек на live достаточно чтобы не запинаться.
                # Round 328: 9000 → 5000. Юзер: «при автооткрытии
                # последнего канала появляется звук а еще через
                # несколько сек уже и само изображение». VLC ждёт
                # пока буфер наполнится до live-caching МС — 5 сек
                # достаточно для стабильной картинки и в 2 раза
                # быстрее старт. На каждое медиа также добавляется
                # `:live-caching` из config (см. _swap), так что
                # юзер может вернуть 9000 в Настройках если запинки.
                '--live-caching=5000',
                '--network-caching=5000',
                '--file-caching=5000',
                '--audio-resampler=soxr',
                '--audio-time-stretch',
                # Round 292: явно отключаем deinterlace — на multistream
                # IPTV он плодит запинки и съедает CPU.
                '--deinterlace=0',
                # Round 292: drop late frames вместо запинок — лучше
                # потерять 1 кадр, чем застрять на нём.
                '--drop-late-frames',
                '--skip-frames',
            ]
            # Hardware decode: на Windows форсим d3d11va (явнее чем any).
            # Юзер: «запинается видео». `any` иногда выбирает software.
            if getattr(self.config, 'hardware_decode', True):
                if sys.platform == "win32":
                    args += ['--avcodec-hw=d3d11va']
                else:
                    args += ['--avcodec-hw=any']
            else:
                args += ['--avcodec-hw=none']
            # Round 288: НЕ форсируем --vout=direct3d11 — на некоторых
            # GPU/драйверах он даёт запинки каждые 1-2 сек. Юзер:
            # «запинается видео». Без --vout VLC сам выберет
            # совместимый (обычно direct3d11 на Win10+ или dxva2 на 7).
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
            # Round 354: юзер — «не работает управление громкостью
            # мышью» после Round 353. Причина: overlay_host — layered-
            # окно с per-pixel alpha, и на Windows его ПОЛНОСТЬЮ
            # прозрачные пиксели «дырявые» для мыши — события падают
            # сквозь них прямо в дочерний HWND VLC-видео, где VLC их
            # съедает сам (поэтому _VideoOverlayHost.wheelEvent просто
            # никогда не вызывался над видео). Отключаем обработку
            # мыши внутри VLC: с mouse_input=False win32-vout VLC
            # прокидывает мышиные сообщения родительскому окну — т.е.
            # нашему video_frame, откуда они обычным Qt-путём
            # поднимаются в PlayerPage.wheelEvent/mousePressEvent
            # (fallback-обработчики из Round 353). Настройка живёт на
            # media_player — переживает смену каналов.
            try:
                self.player.video_set_mouse_input(False)
                # Колесо внутри VLC обрабатывается как «клавиша»
                # (KEY_MOUSEWHEELUP/DOWN) — без отключения key input
                # vout съедал бы WM_MOUSEWHEEL даже при выключенной
                # мыши. Приложение и так обрабатывает ВСЕ клавиши само
                # (глобальный eventFilter, Round 248) — хоткеи VLC
                # не нужны. Стандартная практика встраивания libvlc:
                # отключать оба input'а.
                self.player.video_set_key_input(False)
            except Exception as e:
                log_error('vlc.set_mouse_input', e)
            log_info('vlc', f"instance ok, args={args}")
            # Round 288: подписываемся на EncounteredError + EndReached
            # для авто-реконнекта (Android делает до 8 попыток с
            # экспонентой). На live-IPTV пакеты теряются — без
            # реконнекта канал замораживается насовсем.
            try:
                em = self.player.event_manager()
                from vlc import EventType as _Ev
                em.event_attach(_Ev.MediaPlayerEncounteredError,
                                self._on_vlc_error)
                em.event_attach(_Ev.MediaPlayerEndReached,
                                self._on_vlc_end)
                self._reconnect_attempts = 0
            except Exception as e:
                log_error('vlc.event_attach', e)
            # Round 333: stall-watchdog. EncounteredError + EndReached
            # ловят падение, но при «тихом» зависании (TCP-пакеты
            # перестали приходить, буфер опустел, плеер думает что
            # играет) VLC не эмитит ни одного эвента и канал
            # замораживается насовсем. Каждые 5 сек смотрим
            # get_time() — если значение не меняется 3 тика подряд
            # (15 сек) при is_playing()==True, считаем что стрим
            # подвис и дёргаем reconnect. Юзер: «при длительном
            # просмотре канал зависает и поле не востанавливается».
            # Round 337: init_vlc() выполняется в daemon-нитках
            # (vlc-ensure/vlc-warm) — конструирование и .start() QTimer
            # прямо здесь незаконно для Qt (QObject должен создаваться
            # в нитке-владельце). Заворачиваем установку таймера в
            # _invoke_on_main — тот же диспетчер, что уже используется
            # для возврата UI-колбэков в main (Round 313).
            if not hasattr(self, '_stall_watch_timer'):
                self._stall_last_time = -1
                self._stall_strikes = 0
                self._invoke_on_main.emit(self._install_stall_watch_timer)
        except Exception as e:
            log_error('init_vlc', e, extra=f"args={args}")
            self.vlc_instance = None
            self.player = None
        finally:
            _lock.release()

    def _position_resolution_label(self):
        """Round 299: вынесено отдельно — `_sync_overlay_host` дёргает
        это при каждом ресайзе/переходе в fullscreen, иначе лейбл
        оставался в старой позиции и казалось что «сдвигается в центр»."""
        try:
            if not hasattr(self, 'resolution_label'):
                return
            if not self.resolution_label.isVisible():
                return
            self.resolution_label.adjustSize()
            pw = self.overlay_host.width()
            pad = 14
            clock_h = (self.persistent_clock.height()
                       if hasattr(self, 'persistent_clock') else 30)
            x = max(pad, pw - self.resolution_label.width() - pad)
            y = 10 + clock_h + 4
            self.resolution_label.move(x, y)
            self.resolution_label.raise_()
        except Exception as e:
            log_error('_position_resolution_label', e)

    def _poll_resolution(self):
        """Round 292: каждую секунду спрашиваем VLC размер видео. Как
        только не (0,0) — показываем в углу. После 15 секунд без
        ответа сдаёмся.

        Round 351: video_get_size() — нативный libvlc-вызов, и этот
        таймер тикает ИМЕННО в окно старта стрима, когда мёртвый/
        медленный канал вероятнее всего подвесит нативный вызов.
        Раньше вызов шёл синхронно на main thread без lock'а — сам
        запрос уходит в bg-нитку (дедуп-флаг чтобы тики не
        наслаивались), UI-часть возвращается через _invoke_on_main."""
        try:
            if not self.player:
                return
            if getattr(self, '_res_poll_running', False):
                return
            self._res_poll_running = True
            player = self.player

            def _bg():
                w, h = 0, 0
                try:
                    lock = self._vlc_op_lock
                    if lock.acquire(blocking=False):
                        try:
                            w, h = player.video_get_size(0)
                        except Exception:
                            pass
                        finally:
                            lock.release()
                finally:
                    self._res_poll_running = False
                self._invoke_on_main.emit(
                    lambda w=w, h=h: self._apply_polled_resolution(w, h))

            threading.Thread(target=_bg, daemon=True,
                             name='vlc-res-poll').start()
        except Exception as e:
            self._res_poll_running = False
            log_error('_poll_resolution', e)

    def _apply_polled_resolution(self, w, h):
        """Round 351: UI-часть _poll_resolution — на main thread."""
        try:
            self._resolution_poll_count += 1
            if w > 0 and h > 0:
                # Round 355: кадры пошли → vout-окно VLC создано —
                # чиним его class-cursor (см. _fix_vout_cursor).
                self._fix_vout_cursor()
                self.resolution_label.setText(f"{int(w)} × {int(h)}")
                self.resolution_label.adjustSize()
                pw = self.overlay_host.width()
                pad = 14
                # Под часами справа сверху.
                clock_h = (self.persistent_clock.height()
                           if hasattr(self, 'persistent_clock') else 30)
                self.resolution_label.move(
                    pw - self.resolution_label.width() - pad,
                    10 + clock_h + 4)
                self.resolution_label.show()
                self.resolution_label.raise_()
                self._resolution_poll_timer.stop()
            elif self._resolution_poll_count >= 15:
                self._resolution_poll_timer.stop()
        except Exception as e:
            log_error('_apply_polled_resolution', e)

    def _run_on_main(self, fn):
        """Round 313: слот для _invoke_on_main. Запускается в нитке-владельце
        сигнала (main). Просто вызывает переданный callable."""
        try:
            if callable(fn):
                fn()
        except Exception as e:
            log_error('_run_on_main', e)

    def _start_resolution_polling(self):
        try:
            self._resolution_poll_count = 0
            self.resolution_label.hide()
            self._resolution_poll_timer.start()
            # Round 356: set_media отработал — VLC инициализирован,
            # прячем постоянный статус «Подключаю канал…».
            self._stop_connecting_status()
            # Round 356: чиним курсор vout-окна несколько раз со
            # старта воспроизведения — окно создаётся асинхронно
            # (после начала декодирования), одиночный вызов мог
            # промахнуться мимо ещё не созданного окна. Вызовы
            # идемпотентны и дёшевы.
            for delay in (300, 1200, 3000):
                QTimer.singleShot(delay, self._fix_vout_cursor)
            # Round 361: юзер — «при громкости 150 после перезапуска
            # громкость вроде бы 150, а звук маленький; после ±5 звук
            # восстанавливается». audio_set_volume, вызванный в _swap
            # ДО того как VLC создал аудиовыход (aout строится только
            # когда пошли данные), не «прилипает» для значений > 100:
            # aout инициализируется с усилением 100%. Слайдер при этом
            # показывает 150 (Qt-состояние восстановлено), а реальная
            # громкость 100 — ручное ±5 «чинит», потому что это просто
            # повторный set_volume по ЖИВОМУ aout. Переприменяем
            # громкость слайдера через пару секунд после старта потока
            # — aout уже существует.
            for delay in (2000, 5000):
                QTimer.singleShot(delay, self._reapply_volume)
        except Exception:
            pass

    def _reapply_volume(self):
        """Round 361: повторное применение громкости слайдера к
        живому aout — см. комментарий в _start_resolution_polling."""
        try:
            if not self.player:
                return
            v = int(self.vol_slider.value())
            self._vlc_bg_call('vol-reapply',
                              lambda p, vol=v: p.audio_set_volume(vol))
        except Exception as e:
            log_error('_reapply_volume', e)

    def _show_connecting_status(self):
        """Round 356: юзер — «при первом открытии программы канал не
        сразу открывается очень долго ждать нужно и чёрный экран
        только». Холодный vlc.Instance() на этой машине занимает
        десятки секунд (антивирус пересканирует libvlc.dll + ~300
        плагинов — особенно из папки Downloads), а единственным
        фидбеком был mini-OSD «Подключаю канал…», который прятался
        через 1.5с — дальше юзер смотрел в чистый чёрный экран и
        думал, что программа зависла. Теперь статус живёт до реального
        старта воспроизведения: тикер каждые 500мс перепоказывает OSD
        с анимацией точек и глушит его hide-таймер."""
        try:
            self._connecting_dots = 0
            if not hasattr(self, '_connecting_timer'):
                self._connecting_timer = QTimer(self)
                self._connecting_timer.setInterval(500)
                self._connecting_timer.timeout.connect(
                    self._tick_connecting_status)
            self._connecting_timer.start()
            self._tick_connecting_status()
        except Exception as e:
            log_error('_show_connecting_status', e)

    def _tick_connecting_status(self):
        try:
            self._connecting_dots = (self._connecting_dots + 1) % 4
            dots = '.' * self._connecting_dots
            hint = ""
            # После 8 тиков (4с) добавляем пояснение — юзер понимает,
            # что долгий первый запуск ожидаем, а не «зависло».
            self._connecting_ticks = getattr(self, '_connecting_ticks', 0) + 1
            if self._connecting_ticks > 8:
                hint = "   (первый запуск может занять до минуты)"
            self.show_mini_osd(f"⏳  Подключаю канал{dots}{hint}")
            # Отменяем автоскрытие mini-OSD — статус должен висеть,
            # пока подключаемся.
            try:
                self._mini_osd_vol_timer.stop()
            except Exception:
                pass
        except Exception:
            pass

    def _stop_connecting_status(self):
        try:
            if hasattr(self, '_connecting_timer'):
                self._connecting_timer.stop()
            self._connecting_ticks = 0
            if hasattr(self, '_mini_osd_vol'):
                self._mini_osd_vol.hide()
        except Exception:
            pass

    def _fix_vout_cursor(self):
        """Round 355: юзер — «при просмотре ТВ курсор мыши крутится как
        будто что-то грузится». Побочка Round 354: с
        video_set_mouse_input(False) VLC больше НЕ управляет курсором
        над своим видео-окном (раньше его event-нитка сама ставила
        стрелку/прятала курсор на каждом движении мыши). У класса
        vout-окна VLC нет hCursor, на WM_SETCURSOR никто не отвечает —
        и Windows просто оставляет последний системный курсор, которым
        в момент создания окна был «крутящийся» AppStarting-спиннер.

        Правильный Win32-механизм: прописать классу vout-окна
        стандартную стрелку через SetClassLongPtr(GCLP_HCURSOR) —
        дальше DefWindowProc сам показывает её на каждый WM_SETCURSOR,
        независимо от того, в какой нитке живёт окно. Вызывается из
        _apply_polled_resolution, когда vout-окно гарантированно
        создано (пошли кадры); повторные вызовы безвредны."""
        if sys.platform != 'win32':
            return
        try:
            import ctypes
            from ctypes import wintypes
            user32 = ctypes.windll.user32
            parent = wintypes.HWND(int(self.video_frame.winId()))
            IDC_ARROW = 32512
            arrow = user32.LoadCursorW(None, IDC_ARROW)
            hwnds = []

            @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND,
                                wintypes.LPARAM)
            def _enum(hwnd, _lparam):
                hwnds.append(hwnd)
                return True

            user32.EnumChildWindows(parent, _enum, 0)
            GCLP_HCURSOR = -12
            # 64-битный Python: SetClassLongPtrW; 32-битный фолбэк.
            set_cl = getattr(user32, 'SetClassLongPtrW', None) \
                or user32.SetClassLongW
            fixed = 0
            for hwnd in hwnds:
                try:
                    set_cl(wintypes.HWND(hwnd), GCLP_HCURSOR, arrow)
                    fixed += 1
                except Exception:
                    pass
            # Round 356: юзер — «при переключении канала спиннер
            # появляется, а после клика исчезает». Смена класс-курсора
            # НЕ обновляет уже показанный курсор: Windows пересчитывает
            # форму только на WM_SETCURSOR (движение/клик мыши). Пока
            # vout-нитка занята инициализацией D3D, наведённый курсор
            # показывает busy — и «залипает» до первого клика.
            # SetCursorPos в ту же точку синтезирует WM_MOUSEMOVE →
            # система пересчитывает курсор сама, без участия юзера.
            try:
                pt = wintypes.POINT()
                if user32.GetCursorPos(ctypes.byref(pt)):
                    user32.SetCursorPos(pt.x, pt.y)
            except Exception:
                pass
            if fixed and not getattr(self, '_vout_cursor_logged', False):
                self._vout_cursor_logged = True
                log_info('vlc', f"vout cursor fixed on {fixed} child hwnd(s)")
        except Exception as e:
            log_error('_fix_vout_cursor', e)

    def _on_vlc_error(self, _event):
        # Round 337: libvlc event-колбэки прилетают на СОБСТВЕННОЙ
        # C-нитке libvlc, не на Qt-нитке. _schedule_reconnect читает
        # self.channels/self.current_index и создаёт QTimer —
        # оба небезопасны вне main thread. Заворачиваем через тот же
        # _invoke_on_main диспетчер что и остальной bg→GUI код.
        try:
            self._invoke_on_main.emit(lambda: self._schedule_reconnect("error"))
        except Exception as e:
            log_error('_on_vlc_error', e)

    def _check_stall(self):
        """Round 333: каждые 5с проверяем что get_time() двигается.
        is_playing==True + неизменный get_time за 15с = реконнект.

        Round 341: это main-thread QTimer.timeout слот. Если сейчас
        активен _swap/toggle_play/seek (держит self._vlc_op_lock,
        возможно секунды на stop()), БЛОКИРУЮЩИЙ acquire тут заморозил
        бы UI. Non-blocking acquire: если lock занят — пропускаем тик.

        Round 351: тело целиком уходит в bg-нитку. Non-blocking acquire
        (Round 341) защищал только от ожидания НАШЕГО lock'а, но сами
        is_playing()/get_time() — нативные libvlc-вызовы, и на
        клинически подвисшем стриме даже эти «геттеры» могут
        блокировать (внутри libvlc берут state-lock, который держит
        зависший input-thread). Юзер: «сделай так чтобы нигде не было
        зависаний при любой манипуляции». Дедуп-флаг гарантирует что
        тики не наслаиваются, если проверка сама застряла."""
        if getattr(self, '_stall_check_running', False):
            return
        self._stall_check_running = True

        def _bg():
            try:
                self._check_stall_bg()
            finally:
                self._stall_check_running = False
        try:
            threading.Thread(target=_bg, daemon=True,
                             name='vlc-stall-chk').start()
        except Exception as e:
            self._stall_check_running = False
            log_error('_check_stall.spawn', e)

    def _check_stall_bg(self):
        """Round 351: bg-часть _check_stall — см. комментарий там.
        Лок берём в ЛОКАЛЬНУЮ переменную: _vlc_lock_or_recover
        (Round 350) может подменить self._vlc_op_lock новым объектом,
        и release() через повторное чтение атрибута отпустил бы уже
        ЧУЖОЙ (новый, не взятый нами) lock."""
        lock = self._vlc_op_lock
        if not lock.acquire(blocking=False):
            return
        try:
            p = self.player
            if not p:
                return
            if not p.is_playing():
                self._stall_strikes = 0
                self._stall_last_time = -1
                return
            try:
                cur_t = int(p.get_time() or 0)
            except Exception:
                cur_t = -1
            last = getattr(self, '_stall_last_time', -1)
            if cur_t > 0 and cur_t == last:
                self._stall_strikes = getattr(self, '_stall_strikes', 0) + 1
                if self._stall_strikes >= 3:
                    log_warn('vlc.stall',
                             f"no progress for ~15s at t={cur_t}ms — "
                             f"reconnecting")
                    self._stall_strikes = 0
                    self._stall_last_time = -1
                    # Зовём _schedule_reconnect ПОСЛЕ release() ниже —
                    # и через _invoke_on_main: он создаёт QTimer, это
                    # законно только на main thread.
                    self._pending_stall_reconnect = True
                    return
            else:
                self._stall_strikes = 0
                self._stall_last_time = cur_t
        except Exception as e:
            log_error('_check_stall', e)
        finally:
            lock.release()
        if getattr(self, '_pending_stall_reconnect', False):
            self._pending_stall_reconnect = False
            self._invoke_on_main.emit(
                lambda: self._schedule_reconnect("stalled"))

    def _on_vlc_end(self, _event):
        # Round 337: тот же межпоточный риск что в _on_vlc_error — см.
        # комментарий там.
        try:
            # EndReached на live-стриме = разрыв сети, не нормальный
            # конец файла. Реконнектимся.
            self._invoke_on_main.emit(
                lambda: self._schedule_reconnect("end-reached"))
        except Exception as e:
            log_error('_on_vlc_end', e)

    def _schedule_reconnect(self, reason: str):
        """Round 288: до 8 попыток с экспонентой 1/2/4/8/15с (capped).
        Юзер: «канал замораживается» — это часто разрыв TCP, без
        реконнекта VLC сам не оживает.
        Round 337: всегда вызывается уже в main thread (через
        _invoke_on_main из libvlc-callback'ов, либо напрямую из
        _check_stall который сам main-thread QTimer). Захватываем
        _play_generation — если к моменту срабатывания таймера юзер
        уже переключил канал (generation изменился), реконнект
        тихо отменяется вместо того чтобы силой вернуть старый URL."""
        if not self.channels or self.current_index >= len(self.channels):
            return
        # Round 337: не плодим второй реконнект поверх уже
        # запланированного для той же generation (stall + error могут
        # сработать почти одновременно на одном и том же зависании).
        gen = self._play_generation
        if self._reconnect_scheduled_gen == gen:
            log_info('vlc.reconnect',
                     f"skip: reconnect already scheduled for gen={gen}")
            return
        attempts = getattr(self, '_reconnect_attempts', 0)
        if attempts >= 8:
            log_warn('vlc.reconnect', f"giving up after {attempts} tries")
            return
        delay = min(15000, 1000 * (2 ** attempts))
        self._reconnect_attempts = attempts + 1
        self._reconnect_scheduled_gen = gen
        log_info('vlc.reconnect',
                 f"#{attempts+1}/8 reason={reason} delay={delay}ms gen={gen}")

        def _fire():
            self._reconnect_scheduled_gen = -1
            # Generation изменился — юзер уже переключил канал вручную,
            # реконнект на старый URL больше не актуален.
            if self._play_generation != gen:
                log_info('vlc.reconnect',
                         f"abandoned: gen changed {gen} → "
                         f"{self._play_generation}")
                return
            if not self.channels or self.current_index >= len(self.channels):
                return
            # Перечитываем URL СЕЙЧАС, а не на момент schedule — на
            # случай если что-то в списке каналов успело поменяться.
            url = self.channels[self.current_index].url
            self.play_url(url)

        QTimer.singleShot(delay, _fire)

    def play_channel(self, index, channels, epg_data):
        # Round 364: родительский контроль — заблокированный канал
        # требует PIN перед воспроизведением. После верного PIN
        # блокировки сняты до конца сеанса (parental_is_locked вернёт
        # False), поэтому зэппинг дальше не переспрашивает.
        try:
            if 0 <= index < len(channels):
                _ch = channels[index]
                if parental_is_locked(self.config, _ch):
                    ask_pin(self, self.config,
                            lambda: self.play_channel(index, channels, epg_data))
                    return
        except Exception as e:
            log_error('parental.gate', e)
        # Save state for previously-playing channel before switching
        self._save_current_channel_state()
        # Round 288: сбрасываем reconnect-счётчик при ручном выборе
        # канала (это уже не неудача, а новое намерение).
        self._reconnect_attempts = 0
        # Round 333: stall-strikes тоже сбрасываем — это новый канал.
        self._stall_strikes = 0
        self._stall_last_time = -1

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
                # Round 314: 0..200 — усиление громкости.
                if 'volume' in st and 0 <= int(st['volume']) <= 200:
                    self.vol_slider.setValue(int(st['volume']))
            except Exception as e:
                log_error('restore_channel_state', e, extra=f"url={ch.url[:80]}")

        self.play_url(ch.url)
        # Round 337: захватываем generation ПОСЛЕ play_url() (он его
        # бампает) — отложенные _maybe_seek/_maybe_set_audio_track
        # сверяют его перед применением, чтобы не наложить состояние
        # старого канала если юзер успел зазапить дальше в течение 1.5с.
        my_gen = self._play_generation

        # If we have a saved position (VOD only — live streams report -1 duration),
        # try to seek there once VLC reports a positive length.
        if st and 'position_ms' in st:
            try:
                pos = int(st['position_ms'])
                if pos > 0:
                    QTimer.singleShot(1500, lambda p=pos, g=my_gen:
                                      self._maybe_seek(p, g))
            except Exception:
                pass

        if st and 'audio_track' in st:
            try:
                trk = int(st['audio_track'])
                # Round 360: имя дорожки — для fallback-матча, если ID
                # сменился между сессиями (HLS-потоки).
                tname = str(st.get('audio_track_name', '') or '')
                QTimer.singleShot(1500, lambda t=trk, g=my_gen, n=tname:
                                  self._maybe_set_audio_track(
                                      t, g, track_name=n))
            except Exception:
                pass

        self.config.push_recent(ch.url)
        self.config.save_async()
        self._show_channel_banner()

    def _maybe_seek(self, pos_ms: int, gen: int = None):
        # Round 337: gen — generation захваченная в момент постановки
        # таймера. Если юзер уже сменил канал, не применяем позицию
        # старого канала к новому.
        if gen is not None and gen != self._play_generation:
            return
        if not self.player:
            return
        player = self.player
        # Round 341: get_length()/set_time() выполнялись СИНХРОННО на
        # main thread (вызывается из QTimer.singleShot — та же нитка
        # что и остальной UI). Это уже пре-существующий риск фриза,
        # который не был исправлен в Round 337. Уносим в фон под
        # общим lock'ом, как и остальные VLC-мутации.
        def _bg():
            with self._vlc_op_lock:
                try:
                    length = player.get_length()
                except Exception:
                    length = -1
                # don't restore if near the end
                if length > 0 and pos_ms < length - 30000:
                    try:
                        player.set_time(pos_ms)
                    except Exception:
                        pass
        try:
            import threading as _th
            _th.Thread(target=_bg, daemon=True, name='vlc-maybe-seek').start()
        except Exception as e:
            log_error('_maybe_seek', e)

    def _watch_audio_switch(self, player, t0):
        """Round 362: юзер — «при смене аудио дорожки он зависает видео
        в паузу а потом без звука несколько секунд». In-place
        audio_set_track на TS-потоках мгновенный, но на HLS с
        alternate audio VLC пересобирает буфер (стоп-кадр + тишина на
        network-caching), а иногда поток не оживает вовсе. Раньше мы
        разводили руками «поведение VLC». Теперь: через 4с после смены
        дорожки проверяем, пошло ли время потока; если НЕ пошло —
        быстрый перезапуск канала. Выбранная дорожка восстановится из
        per-channel state (сохранена в момент клика, Round 360) ещё на
        этапе начальной буферизации — итог: предсказуемый рестарт
        вместо неопределённо долгого зависшего кадра."""
        gen = self._play_generation
        url = None
        try:
            if self.channels and 0 <= self.current_index < len(self.channels):
                url = self.channels[self.current_index].url
        except Exception:
            pass
        if not url:
            return

        def _bg():
            try:
                time.sleep(4.0)
                if gen != self._play_generation or self.player is not player:
                    return  # юзер уже переключил канал — не вмешиваемся
                t1 = None
                lock = self._vlc_op_lock
                if lock.acquire(blocking=False):
                    try:
                        try:
                            t1 = player.get_time()
                        except Exception:
                            t1 = None
                    finally:
                        lock.release()
                else:
                    # lock занят — идёт другая операция, не мешаем.
                    return
                if t0 is not None and t1 is not None and t1 <= t0:
                    log_warn('vlc',
                             f"stream stalled after audio switch "
                             f"(t {t0}→{t1}) — quick restart")
                    self._invoke_on_main.emit(
                        lambda u=url: self.play_url(u))
            except Exception as e:
                log_error('_watch_audio_switch', e)
        try:
            threading.Thread(target=_bg, daemon=True,
                             name='vlc-aud-watch').start()
        except Exception as e:
            log_error('_watch_audio_switch.spawn', e)

    def _maybe_set_audio_track(self, track_id: int, gen: int = None,
                               attempts: int = 12, track_name: str = ''):
        # Round 281: VLC audio_set_track блокирует 21+ сек на сложных
        # стримах — watchdog поймал. Перенос в фон, как play_url и
        # cycle_audio_track в Round 279.
        # Round 337: gen-проверка — та же логика что в _maybe_seek.
        # Round 359: юзер — «должен сохранять индивидуально все
        # параметры канала, хоть это аудио». Восстановление
        # аудиодорожки было ОДНИМ выстрелом через 1.5с после старта —
        # live-поток к этому моменту обычно ещё буферизуется (5с
        # network-caching, холодный старт дольше), СПИСКА ДОРОЖЕК ещё
        # не существует, и audio_set_track молча проваливался, никогда
        # не повторяясь. Теперь: проверяем, что дорожка уже есть в
        # audio_get_track_description(); если нет — повторяем каждую
        # секунду до ~12 попыток, с gen-проверкой на каждой (юзер мог
        # уже переключить канал).
        if gen is not None and gen != self._play_generation:
            return
        if not self.player:
            return
        try:
            import threading as _th
            player = self.player
            # Round 341: под общим lock'ом — сериализует с активным
            # _swap/toggle_play/seek.
            def _bg():
                applied = False
                try:
                    with self._vlc_op_lock:
                        if (gen is None
                                or gen == self._play_generation):
                            try:
                                descs = (player.audio_get_track_description()
                                         or [])
                            except Exception:
                                descs = []
                            ids = [t[0] for t in descs if t]
                            target = None
                            if track_id in ids:
                                target = track_id
                            elif track_name and len(descs) > 1:
                                # Round 360: fallback по ИМЕНИ — у HLS
                                # ID дорожек могут меняться между
                                # сессиями, а имена стабильны.
                                for t in descs:
                                    if not t or t[0] < 0:
                                        continue
                                    nm = t[1]
                                    if isinstance(nm, (bytes, bytearray)):
                                        nm = nm.decode('utf-8', 'replace')
                                    if str(nm) == track_name:
                                        target = t[0]
                                        break
                            if target is not None:
                                _safe_call(player.audio_set_track, target)
                                applied = True
                except Exception as e:
                    log_error('_maybe_set_audio_track.bg', e)
                if not applied and attempts > 1:
                    # Дорожки ещё не готовы — пробуем позже, через
                    # main thread (QTimer законен только там).
                    self._invoke_on_main.emit(
                        lambda: QTimer.singleShot(
                            1000,
                            lambda: self._maybe_set_audio_track(
                                track_id, gen, attempts - 1,
                                track_name=track_name)))
                elif applied:
                    log_info('vlc',
                             f"restored audio track {track_id}"
                             f" name='{track_name}'")
            _th.Thread(target=_bg, daemon=True, name='vlc-set-aud').start()
        except Exception as e:
            log_error('_maybe_set_audio_track', e)

    def _save_current_channel_state(self):
        """Round 288: VLC get_time() / get_length() / audio_get_track()
        могут блокировать 3-5 сек на мёртвых стримах. Watchdog поймал
        4.3 сек фриза в get_time через switch_page → stop. Уносим в
        daemon-нитку — позиция/громкость в редких случаях останутся
        чуть устаревшими, но UI всегда отзывчив."""
        if not self.channels or self.current_index >= len(self.channels):
            return
        ch = self.channels[self.current_index]
        if not ch or not ch.url:
            return
        # Снимаем синхронные значения из GUI-нитки.
        base_state = {
            'aspect_idx': self._aspect_idx,
            'speed_idx': self._speed_idx,
            'volume': self.vol_slider.value() if hasattr(self, 'vol_slider') else self.config.volume,
        }
        url = ch.url
        player = self.player

        def _bg():
            # Round 360: НАЧИНАЕМ с уже сохранённого состояния (merge),
            # а не с пустого dict'а. Раньше state строился с нуля: если
            # чтение audio_track из VLC ниже не удавалось (стрим уже
            # останавливается конкурентным _swap нового канала — частый
            # случай при зэппинге), ключ просто выпадал, и
            # save_channel_state ЗАМЕНОЙ затирал ранее сохранённую
            # дорожку. Юзер: «не сохраняется аудио дорожка».
            state = self.config.get_channel_state(url)
            state.update(base_state)
            if player:
                try:
                    t = player.get_time()
                    length = player.get_length()
                    if t and t > 30000 and length > 0 and t < length - 30000:
                        state['position_ms'] = int(t)
                except Exception:
                    pass
                try:
                    track = player.audio_get_track()
                    # Перезаписываем ТОЛЬКО при валидном чтении (>0;
                    # -1 = disabled, 0 у большинства контейнеров —
                    # спец-ES). Невалидное чтение сохраняет прежнее
                    # значение благодаря merge выше.
                    if track is not None and track > 0:
                        state['audio_track'] = int(track)
                except Exception:
                    pass
            try:
                self.config.save_channel_state(url, state)
                self.config.save_async()
            except Exception as e:
                log_error('_save_current_channel_state.bg', e)
        try:
            import threading as _th
            _th.Thread(target=_bg, daemon=True, name='vlc-save-state').start()
        except Exception as e:
            log_error('_save_current_channel_state', e)

    def _ensure_vlc_then_play(self):
        """Round 283: фоновая инициализация VLC + откладываемый play_url.
        Юзер видит «Подключаю канал…» 20-30 сек вместо фриза UI."""
        try:
            log_info('vlc', "lazy init from play_url (background)")
            self.init_vlc()
            url = getattr(self, '_pending_play_url', None)
            if url and self.player:
                # Возвращаемся в GUI-нитку для второй фазы (set_media и т.п.).
                # Round 313: через сигнал, без QTimer-warning из bg-нитки.
                self._invoke_on_main.emit(lambda u=url: self.play_url(u))
            elif self.player is None:
                # Round 356: init_vlc не создал плеер — гасим постоянный
                # статус «Подключаю канал…» и показываем ошибку, иначе
                # анимация крутилась бы вечно.
                def _fail_ui():
                    self._stop_connecting_status()
                    self.show_mini_osd("⚠ Не удалось запустить VLC")
                self._invoke_on_main.emit(_fail_ui)
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
        # Round 337: бампаем generation ПЕРЕД любой работой — захватывается
        # ниже в my_gen и передаётся в _swap-замыкание. Устаревшие
        # bg-нитки (отложенный reconnect, обгоняющий предыдущий _swap)
        # сверяют это значение перед тем как реально применить
        # set_media/play() к общему self.player.
        self._play_generation += 1
        my_gen = self._play_generation
        try:
            self.show_mini_osd("⏳  Подключаю канал…")
        except Exception:
            pass
        if not self.player:
            # VLC ещё не готов — запускаем init+play в одной нитке.
            # Защита от двойного init: если ensure-нитка уже бежит,
            # просто обновляем _pending_play_url (последний клик
            # выиграет), новой нитки не плодим.
            # Round 356: постоянный статус вместо разового OSD — иначе
            # при холодном старте VLC (десятки секунд под антивирусом)
            # юзер после 1.5с смотрел в чёрный экран.
            self._show_connecting_status()
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

            # Round 288: per-channel User-Agent + auto-Referer.
            # Android выводит Referer из origin'а url когда юзер ничего
            # не настроил — много CIS-стримов (tv.izone.az, etc) шлют
            # 403 без Referer. UA берём из config либо стандартный VLC.
            ua_cfg = getattr(self.config, 'user_agent', '') or \
                     'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            referer_cfg = getattr(self.config, 'http_referer', '')
            if not referer_cfg:
                try:
                    from urllib.parse import urlparse as _urlp
                    p = _urlp(url)
                    if p.scheme and p.netloc:
                        referer_cfg = f"{p.scheme}://{p.netloc}/"
                except Exception:
                    referer_cfg = ''

            def _swap():
                try:
                    # Round 337: если пока эта нитка ждала в очереди
                    # шедулера, юзер успел кликнуть другой канал
                    # (my_gen устарел) — бросаем работу, не трогая
                    # общий self.player. Закрывает гонку конкурентных
                    # _swap-вызовов (отложенный reconnect vs ручной зап).
                    if self._play_generation != my_gen:
                        log_info('play_url.bg',
                                 f"stale swap gen={my_gen} "
                                 f"(current={self._play_generation}), skip")
                        return
                    # Round 341: ВЕСЬ swap — под единым lock'ом. Юзер:
                    # «стала зависать чаще и стрим с обрывами» после
                    # Round 337 сделал toggle_play/_seek_or_switch
                    # background-нитками — они могли выполняться
                    # КОНКУРЕНТНО с этим _swap (interleaved stop/
                    # set_media/play/pause на одном native-объекте
                    # портит состояние плеера непредсказуемо). Lock
                    # сериализует все такие операции; блокировка
                    # безопасна — мы уже в bg-нитке, не в main.
                    # Round 350: с таймаутом + авто-восстановлением — см.
                    # _vlc_lock_or_recover. Юзер: «если один канал завис
                    # не показал то остальные уже тоже не показывают» —
                    # раньше .stop()/.set_media() на действительно мёртвом
                    # стриме могли не вернуться НИКОГДА, и lock оставался
                    # захвачен навсегда, блокируя ЛЮБОЕ следующее
                    # переключение канала.
                    lock, recovered = self._vlc_lock_or_recover()
                    try:
                        if recovered:
                            # Старый player подвис насмерть — подсистема
                            # уже сброшена (self.player is None), просто
                            # повторяем play_url через обычный «холодный»
                            # путь, который создаст свежий vlc.Instance().
                            log_info('play_url.bg',
                                     "vlc subsystem was reset (stuck lock), "
                                     "retrying via fresh init")
                            self._invoke_on_main.emit(
                                lambda u=url: self.play_url(u))
                            return
                        # Повторная проверка — генерация могла устареть
                        # пока ждали lock (другой _swap был активен).
                        if self._play_generation != my_gen:
                            log_info('play_url.bg',
                                     f"stale swap gen={my_gen} went stale "
                                     f"waiting for lock, skip")
                            return
                        # Round 334: ПЕРЕД set_media явно прибиваем текущий
                        # поток через stop(). Юзер: «когда подвис показ
                        # канала пытаюсь открыть другой он не закрывая
                        # основной открывает новое окно с другим каналом».
                        # При подвисшем decode-thread'е set_media ждёт его
                        # натурально (может 5-15 сек), а пока ждёт — VLC
                        # на нашу set_hwnd-команду не реагирует и
                        # «новый» декодер ренедерит в собственное окно.
                        # player.stop() гарантирует что предыдущий поток
                        # снят и HWND освобождён, set_media отрабатывает
                        # на чистом плеере, set_hwnd попадает в нужное
                        # место. Stop() уже в bg-нитке, UI не блокирует.
                        try:
                            player.stop()
                        except Exception as _e_stop:
                            log_warn('play_url',
                                     f"pre-swap stop failed: {_e_stop}")
                        # Round 334: HWND проставляем СНАЧАЛА — VLC
                        # запоминает целевое окно до того как новая media
                        # инициализирует декодер. Без этого иногда видео
                        # уходит в дочернее VLC-окно если set_hwnd придёт
                        # позже play().
                        if hwnd is not None:
                            player.set_hwnd(hwnd)
                        elif xwin is not None:
                            player.set_xwindow(xwin)
                        elif nsobj is not None:
                            player.set_nsobject(nsobj)
                        media = vlc_inst.media_new(url)
                        media.add_option(f':network-caching={net_cache}')
                        # Round 328: явно проставляем live-caching из конфига,
                        # чтобы не зависеть от глобального --live-caching из
                        # init_vlc. Меньше буфер = быстрее старт канала.
                        media.add_option(f':live-caching={net_cache}')
                        # Round 288: HTTP headers — UA + Referer на media.
                        if ua_cfg:
                            media.add_option(f':http-user-agent={ua_cfg}')
                        if referer_cfg:
                            media.add_option(f':http-referrer={referer_cfg}')
                        # Round 337: третья проверка generation ПОСЛЕ
                        # медленных синхронных вызовов (stop() может ждать
                        # 5-15 сек) — если за это время подоспел более
                        # новый play_url, не коммитим current_media/play()
                        # поверх того что уже выставила свежая нитка.
                        if self._play_generation != my_gen:
                            log_info('play_url.bg',
                                     f"stale swap gen={my_gen} went stale "
                                     f"mid-flight, abandoning before commit")
                            return
                        # set_media внутри STOP'ает текущий поток — это и
                        # есть тот самый 16 сек блок (теперь Round 334
                        # снимает его явно выше).
                        player.set_media(media)
                        self.current_media = media
                        # Round 291: НЕ вызываем prev_media.release() —
                        # player.set_media() внутри VLC уже сделал
                        # libvlc_media_release на старой. Наш повторный
                        # release был double-free, watchdog поймал:
                        #   OSError: access violation writing 0x...24
                        # Python-обёртка vlc.Media отпустит свою ссылку
                        # через GC когда prev_media выйдет из scope.
                        # Round 334: повторный set_hwnd — на случай если
                        # set_media сбросил привязку.
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
                        log_info('play', f"set_media done for {url[:80]} "
                                         f"ua={ua_cfg[:40]} ref={referer_cfg}")
                    finally:
                        lock.release()
                    # Round 292: запускаем опрос разрешения в GUI-нитке.
                    # Round 313: через сигнал — _swap бежит в plain
                    # threading.Thread, QTimer оттуда ругается.
                    # Round 341: ВНЕ lock'а — это просто dispatch сигнала,
                    # не трогает player напрямую.
                    try:
                        self._invoke_on_main.emit(self._start_resolution_polling)
                    except Exception:
                        pass
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
        # Round 337: is_playing()/pause()/play() могут блокировать на
        # подвисшем/умирающем стриме — та же причина по которой
        # play_url/stop/cycle_audio_track уже давно ушли в фон.
        # Round 341: is_playing() САМА по себе тоже была синхронным
        # вызовом на main thread (не полностью пофикшено в Round 337).
        # Плюс добавлен self._vlc_op_lock — сериализует с активным
        # _swap, иначе pause()/play() отсюда могли interleaved
        # выполниться поверх stop()/set_media() из channel-swap'а на
        # другом канале и портить состояние плеера (юзер: «стала
        # зависать чаще и стрим с обрывами»). Метка кнопки обновляется
        # ПОСЛЕ реального вызова, через _invoke_on_main.
        if not self.player:
            return
        # Round 351: дедуп. Space с клавиатурным автоповтором спавнил
        # НОВУЮ нитку на каждый repeat (~30/с); на подвисшем стриме все
        # они вечно вставали в очередь на _vlc_op_lock (утечка ниток +
        # отложенный «replay» пачки pause/play когда lock освободится).
        # Пока одна операция в полёте — повторные нажатия игнорируем.
        if getattr(self, '_toggle_play_running', False):
            return
        self._toggle_play_running = True
        player = self.player
        def _bg():
            try:
                with self._vlc_op_lock:
                    try:
                        playing = player.is_playing()
                    except Exception:
                        playing = False
                    if playing:
                        player.pause()
                    else:
                        player.play()
                self._invoke_on_main.emit(
                    lambda p=playing: self.btn_play.setText(
                        "Play" if p else "Pause"))
            except Exception as e:
                log_error('toggle_play.bg', e)
            finally:
                self._toggle_play_running = False
        try:
            import threading as _th
            _th.Thread(target=_bg, daemon=True,
                       name='vlc-toggle-play').start()
        except Exception as e:
            self._toggle_play_running = False
            log_error('toggle_play', e)

    def _seek_or_switch(self, direction):
        """Round 337: Left/Right — на VOD перемотка ±10с, на live-канале
        (get_time не растёт, pos<=0) переключение канала. get_time()/
        set_time() уходят в фон — на подвисшем стриме они блокируют
        так же как stop()/set_media() (см. комментарии в play_url)."""
        player = self.player
        if not player:
            self.switch_channel(direction)
            return
        # Round 351: дедуп, как в toggle_play — стрелки с автоповтором
        # спавнили нитку на каждый repeat, все вставали в очередь на
        # lock. Пока перемотка в полёте — повторы игнорируем.
        if getattr(self, '_seek_running', False):
            return
        self._seek_running = True
        def _bg():
            # Round 341: под общим lock'ом — иначе get_time()/set_time()
            # отсюда могли выполниться поверх активного _swap'а
            # (stop()/set_media() на другом канале) и вернуть/применить
            # мусорное значение позиции.
            try:
                with self._vlc_op_lock:
                    try:
                        pos = player.get_time()
                    except Exception:
                        pos = -1
                    if pos and pos > 0:
                        try:
                            new_pos = max(0, pos - 10000) if direction < 0 else pos + 10000
                            player.set_time(new_pos)
                        except Exception as e:
                            log_error('_seek_or_switch.set_time', e)
                        return
                self._invoke_on_main.emit(
                    lambda d=direction: self.switch_channel(d))
            finally:
                self._seek_running = False
        try:
            import threading as _th
            _th.Thread(target=_bg, daemon=True, name='vlc-seek').start()
        except Exception as e:
            self._seek_running = False
            log_error('_seek_or_switch', e)

    def switch_channel(self, direction):
        if not self.channels:
            return
        # Round 325: Up/Down переключают канал ВНУТРИ отфильтрованного
        # списка, если активен поиск или выбрана категория. Юзер: «при
        # поиске канала ... когда хочешь переключить на другой канал
        # с помощью клавиш верх и низ он переключает уже не в найденном
        # списке каналов а в общем». ChannelsPage.filtered отражает
        # активный фильтр (overlay-поиск миррорится туда через
        # _mirror_search_to_channels_page).
        nav_list = self._get_navigation_list()
        if not nav_list:
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
        # Текущий канал в self.channels — находим его позицию в
        # отфильтрованном списке. Если фильтр совпадает с self.channels
        # (нет активного фильтра), это просто индекс == self._pending_index.
        cur_ch = (self.channels[self._pending_index]
                  if 0 <= self._pending_index < len(self.channels) else None)
        try:
            pos = nav_list.index(cur_ch) if cur_ch is not None else 0
        except ValueError:
            # Текущий канал не входит в фильтр (например, юзер сменил
            # фильтр после старта канала) — начинаем с края списка.
            pos = -1 if direction > 0 else len(nav_list)
        new_pos = (pos + direction) % len(nav_list)
        new_ch = nav_list[new_pos]
        # Конвертируем обратно в индекс self.channels — play_channel
        # принимает индекс из self.channels.
        try:
            new_idx = self.channels.index(new_ch)
        except ValueError:
            return
        self._pending_index = new_idx
        self.current_index = new_idx
        # Visual feedback while zapping
        ch = self.channels[new_idx]
        self.channel_name_label.setText(ch.name)
        # n/N в OSD показываем в координатах ФИЛЬТРА когда он активен,
        # иначе общего списка — так юзер видит «3 из 5» при поиске.
        total = len(nav_list)
        shown_n = new_pos + 1
        self.channel_number_label.setText(f"{shown_n} / {total}")
        # Round 280: канальная OSD-карта при пролистывании ↑/↓.
        self.show_channel_osd(ch, new_pos, total)
        self._zap_timer.start()

    def _get_navigation_list(self):
        """Round 325: возвращает текущий отфильтрованный список каналов
        для Up/Down навигации. Если фильтра нет — возвращает self.channels."""
        try:
            mw = self.window()
            cp = getattr(mw, 'channels_page', None)
            if cp is None:
                return self.channels
            filtered = getattr(cp, 'filtered', None)
            if not filtered:
                return self.channels
            # Если фильтр совпадает с полным списком — нет смысла
            # ходить через index() лишний раз.
            if len(filtered) == len(self.channels):
                return self.channels
            return filtered
        except Exception:
            return self.channels

    def _commit_zap(self):
        idx = getattr(self, '_pending_index', self.current_index)
        if idx is None or idx < 0 or idx >= len(self.channels):
            return
        self.current_index = idx
        self.play_channel(idx, self.channels, self.epg_data)

    def set_volume(self, val):
        self.config.volume = val
        # Round 351: audio_set_volume — нативный libvlc-вызов, а слайдер
        # громкости дёргает этот слот на КАЖДЫЙ пиксель перетаскивания —
        # это был самый часто вызываемый незащищённый нативный вызов на
        # main thread во всём файле. На подвисшем стриме одно движение
        # слайдера = фриз UI. Паттерн «последний выигрывает»: пишем
        # желаемое значение в _pending_volume; одна bg-нитка-applier
        # применяет значения циклом, пока они меняются, и умирает.
        # Никакого шторма ниток на каждый пиксель.
        self._pending_volume = int(val)
        if self.player and not getattr(self, '_vol_applier_running', False):
            self._vol_applier_running = True

            def _bg():
                try:
                    while True:
                        v = self._pending_volume
                        p = self.player
                        if p is None:
                            return
                        lock = self._vlc_op_lock
                        with lock:
                            try:
                                p.audio_set_volume(v)
                            except Exception:
                                pass
                        if self._pending_volume == v:
                            return
                finally:
                    self._vol_applier_running = False
            try:
                threading.Thread(target=_bg, daemon=True,
                                 name='vlc-volume').start()
            except Exception as e:
                self._vol_applier_running = False
                log_error('set_volume.spawn', e)
        # Round 279/314: мини-OSD с уровнем громкости. Диапазон 0..200,
        # бар на 20 ячеек (по 10% каждая). При значениях > 100 значок
        # 🔊 меняется на ⚡ — визуальный сигнал «бустим, возможен клиппинг».
        bars = max(0, min(20, int(val / 10)))
        bar_str = "█" * bars + "░" * (20 - bars)
        icon = "⚡" if val > 100 else "🔊"
        self.show_mini_osd(f"{icon}  {val}%   {bar_str}")

    def toggle_mute(self):
        """Round 353: мут/анмут. Вынесено из Key_M-ветки
        MainWindow._handle_key — теперь же вызывается средней кнопкой
        мыши над видео (см. _VideoOverlayHost). Идём через vol_slider
        → set_volume: OSD-бар и bg-applier срабатывают сами."""
        cur_v = self.vol_slider.value()
        if cur_v > 0:
            self._saved_volume_before_mute = cur_v
            self.vol_slider.setValue(0)
        else:
            self.vol_slider.setValue(
                getattr(self, '_saved_volume_before_mute', 50))

    def _wheel_volume(self, delta_y):
        """Round 353: колесо мыши над видео — громкость ±5 за щелчок
        (та же ступень, что у клавиш +/-). delta_y кратен 120 на
        обычной мыши; на тачпадах приходят дробные значения — реагируем
        на знак."""
        if not delta_y:
            return
        step = 5 if delta_y > 0 else -5
        self.vol_slider.setValue(
            max(0, min(200, self.vol_slider.value() + step)))

    def wheelEvent(self, ev):
        """Round 353: фолбэк — до инициализации VLC над video_frame нет
        чужого HWND, и колесо доходит сюда обычным Qt-путём."""
        try:
            self._wheel_volume(ev.angleDelta().y())
            ev.accept()
        except Exception as e:
            log_error('PlayerPage.wheel', e)

    def mousePressEvent(self, ev):
        """Round 353: фолбэк для средней кнопки (см. wheelEvent)."""
        try:
            if ev.button() == Qt.MiddleButton:
                self.toggle_mute()
                ev.accept()
                return
        except Exception as e:
            log_error('PlayerPage.mid_click', e)
        super().mousePressEvent(ev)

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
            pw = self.overlay_host.width()
            ph = self.overlay_host.height()
            # Round 337: overlay_host строится скрытым с нулевой
            # геометрией (см. init_ui), и update_clock() дёргает эту
            # функцию из PlayerPage.__init__ ДО первого реального
            # _sync_overlay_host(). Без guard'а top_right/bottom_right
            # считали x = 0 - cw - pad (отрицательный) и часы рисовались
            # частично за левым краем до следующего ресайза. Остальные
            # position_* методы (resolution_label, osd) уже имеют
            # такую защиту — этому не хватало. Просто не показываем
            # пока нет валидной геометрии; следующий _sync_overlay_host
            # вызовет этот метод повторно с реальными размерами.
            if pw <= 0 or ph <= 0:
                return
            self.persistent_clock.show()
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

    def _vlc_bg_call(self, desc, fn):
        """Round 351: одноразовый нативный VLC-вызов в bg-нитке под
        общим lock'ом. Любой вызов libvlc может блокировать секунды на
        подвисшем стриме — main thread их звать не должен вообще.
        Identity-проверка self.player is player внутри lock'а — чтобы
        не дёргать плеер, который уже release'нут/пересоздан.
        Дедуп по desc: пока вызов этого типа в полёте, повторные
        нажатия (кнопка/клавиша с автоповтором) игнорируются — иначе
        на подвисшем стриме нитки копились бы в очереди на lock."""
        player = self.player
        if not player:
            return
        if not hasattr(self, '_vlc_bg_running'):
            self._vlc_bg_running = set()
        if desc in self._vlc_bg_running:
            return
        self._vlc_bg_running.add(desc)

        def _bg():
            try:
                with self._vlc_op_lock:
                    if self.player is not player:
                        return
                    fn(player)
            except Exception as e:
                log_error(desc, e)
            finally:
                self._vlc_bg_running.discard(desc)
        try:
            threading.Thread(target=_bg, daemon=True,
                             name='vlc-' + desc[:12]).start()
        except Exception as e:
            self._vlc_bg_running.discard(desc)
            log_error(desc + '.spawn', e)

    def _apply_aspect_ratio(self):
        if not self.player:
            return
        ratio = self.ASPECT_RATIOS[self._aspect_idx]
        label = ratio if ratio else "auto"
        # Round 351: video_set_aspect_ratio — нативный вызов; кнопка/
        # клавиша A дёргали его синхронно на main thread без lock'а.
        self._vlc_bg_call(
            'aspect',
            lambda p, r=ratio: p.video_set_aspect_ratio(
                r.encode() if r else None))
        self.btn_aspect.setText(f"Aspect: {label}")

    # --- Playback speed ---

    def cycle_speed(self):
        self._speed_idx = (self._speed_idx + 1) % len(self.SPEED_VALUES)
        speed = self.SPEED_VALUES[self._speed_idx]
        # Round 351: set_rate — нативный вызов, ушёл в bg (см.
        # _vlc_bg_call). Раньше — синхронно на main thread.
        if self.player:
            self._vlc_bg_call('rate', lambda p, s=speed: p.set_rate(s))
        self.btn_speed.setText(f"{speed:g}x")

    # --- Audio track ---

    def show_audio_track_menu(self):
        """Round 312: всплывающее меню со списком всех аудиодорожек.
        Юзер: «нет выбора аудио дорожки» — циклическое переключение
        не давало возможности перейти к конкретной (особенно когда
        дорожек 3-4 на разных языках). VLC-вызов получения списка
        дорожек делаем в фоне, само меню — в Qt-нитке через
        QTimer.singleShot."""
        if not self.player:
            return
        try:
            import threading as _th
            player = self.player
            def _bg():
                try:
                    tracks = player.audio_get_track_description() or []
                    cur = player.audio_get_track()
                    items = []
                    for t in tracks:
                        if not t:
                            continue
                        tid = t[0]
                        name = t[1]
                        if isinstance(name, (bytes, bytearray)):
                            name = name.decode('utf-8', 'replace')
                        items.append((tid, name))
                    log_info('vlc', f"audio menu tracks: {items} current={cur}")
                except Exception as e:
                    log_error('show_audio_track_menu.bg', e)
                    items, cur = [], -1
                # Round 313: сигнал вместо QTimer — без warning'а.
                self._invoke_on_main.emit(
                    lambda i=items, c=cur: self._present_audio_menu(i, c))
            _th.Thread(target=_bg, daemon=True, name='vlc-audio-menu').start()
        except Exception as e:
            log_error('show_audio_track_menu', e)

    def _present_audio_menu(self, items, cur):
        """Round 312: рисует QMenu возле btn_audio. items = [(id, name)]."""
        try:
            from PyQt5.QtWidgets import QMenu
            menu = QMenu(self)
            menu.setStyleSheet(
                "QMenu { background-color: #1A1A2E; color: white;"
                " border: 1px solid #7C6CF7; }"
                "QMenu::item { padding: 8px 24px; }"
                "QMenu::item:selected { background-color: #7C6CF7; }")
            if not items:
                act = menu.addAction("Нет аудиодорожек")
                act.setEnabled(False)
            else:
                import threading as _th
                cur_action = None
                for tid, name in items:
                    label = ("✓ " if tid == cur else "    ") + (
                        name or f"Track {tid}")
                    act = menu.addAction(label)
                    if tid == cur:
                        cur_action = act
                    def _make_handler(track_id=tid, track_name=name):
                        def _h():
                            # Round 337: menu.exec_() ниже блокирует
                            # (модальный цикл) пока юзер решает — за это
                            # время self.player может быть заменён
                            # (channel switch/release). Берём АКТУАЛЬНЫЙ
                            # self.player в момент клика, а не тот что
                            # был захвачен до открытия меню.
                            player = self.player
                            if not player:
                                return
                            # Round 360: сохраняем выбор НАПРЯМУЮ (id +
                            # имя) в момент клика — мы ТОЧНО знаем, что
                            # выбрал юзер. Round 359 читал дорожку
                            # обратно из VLC через
                            # _save_current_channel_state — но VLC
                            # применяет смену ES асинхронно, и чтение
                            # сразу после set возвращало СТАРУЮ дорожку
                            # (или -1), затирая выбор. Имя нужно для
                            # fallback-восстановления: у HLS-потоков ID
                            # дорожек могут меняться между сессиями.
                            try:
                                if (self.channels and 0 <= self.current_index
                                        < len(self.channels)):
                                    _ch_url = self.channels[self.current_index].url
                                    self.config.update_channel_state(
                                        _ch_url,
                                        audio_track=int(track_id),
                                        audio_track_name=str(track_name or ''))
                            except Exception as e:
                                log_error('audio_track.save_choice', e)

                            def _bg():
                                try:
                                    # Round 341: под общим lock'ом.
                                    with self._vlc_op_lock:
                                        player.audio_set_track(track_id)
                                        try:
                                            _t0 = player.get_time()
                                        except Exception:
                                            _t0 = None
                                    log_info('vlc',
                                             f"audio track → {track_id} "
                                             f"'{track_name}'")
                                    # Round 362: если поток не оживёт
                                    # за 4с — быстрый перезапуск канала
                                    # (см. _watch_audio_switch).
                                    self._watch_audio_switch(player, _t0)
                                except Exception as e:
                                    log_error('audio_set_track.bg', e)
                            _th.Thread(target=_bg, daemon=True,
                                       name='vlc-set-aud').start()
                            try:
                                self.show_mini_osd(
                                    f"🔊 {track_name or 'Track ' + str(track_id)}")
                            except Exception:
                                pass
                        return _h
                    act.triggered.connect(_make_handler())
                # Round 359: подсвечиваем ТЕКУЩУЮ дорожку как активный
                # пункт — юзер: «нет фокуса на какой строке». Без
                # setActiveAction QMenu открывается без выделения, и
                # стрелкам «не от чего» шагать.
                if cur_action is not None:
                    menu.setActiveAction(cur_action)
                elif menu.actions():
                    menu.setActiveAction(menu.actions()[0])
            # Позиционируем под кнопкой если она видна, иначе по курсору.
            btn = getattr(self, 'btn_audio', None)
            if btn is not None and btn.isVisible():
                from PyQt5.QtCore import QPoint
                pos = btn.mapToGlobal(QPoint(0, btn.height()))
                menu.exec_(pos)
            else:
                from PyQt5.QtGui import QCursor
                menu.exec_(QCursor.pos())
        except Exception as e:
            log_error('_present_audio_menu', e)

    def cycle_audio_track(self):
        # Round 279: VLC `audio_get_track_description` / `audio_set_track`
        # могут блокировать на проблемных стримах. Уносим в фон.
        # Round 310: добавляем OSD-фидбек. Юзер: «нажимаю на кнопку
        # Аудио и ничего». Большинство live IPTV-стримов несут одну
        # дорожку, и тихий no-op выглядит как сломанная кнопка. Теперь
        # показываем имя текущей дорожки + total, либо «одна дорожка»
        # если переключать нечего. OSD дёргаем через QTimer.singleShot(0)
        # — _bg бежит не в Qt-нитке.
        if not self.player:
            return
        try:
            import threading as _th
            player = self.player
            def _bg():
                try:
                    # Round 341: под общим lock'ом — сериализует с
                    # активным _swap/toggle_play/seek/меню дорожек.
                    with self._vlc_op_lock:
                        tracks = player.audio_get_track_description() or []
                        # Логируем все полученные дорожки для диагностики.
                        raw = [(t[0], (t[1].decode('utf-8', 'replace')
                                       if isinstance(t[1], (bytes, bytearray))
                                       else str(t[1])))
                               for t in tracks if t]
                        log_info('vlc', f"audio tracks: {raw}")
                        usable = [t for t in tracks if t and t[0] >= 0]
                        if len(usable) < 2:
                            msg = (f"🔊 Одна аудио-дорожка"
                                   if len(usable) <= 1
                                   else f"🔊 Нет переключаемых дорожек")
                            # Round 313: сигнал вместо QTimer.
                            self._invoke_on_main.emit(
                                lambda m=msg: self.show_mini_osd(m))
                            return
                        cur = player.audio_get_track()
                        ids = [t[0] for t in usable]
                        try:
                            pos = ids.index(cur)
                        except ValueError:
                            pos = -1
                        nxt = usable[(pos + 1) % len(usable)]
                        player.audio_set_track(nxt[0])
                        try:
                            _t0_switch = player.get_time()
                        except Exception:
                            _t0_switch = None
                        name = nxt[1]
                        if isinstance(name, (bytes, bytearray)):
                            name = name.decode('utf-8', 'replace')
                        log_info('vlc',
                                 f"audio track → {nxt[0]} '{name}' "
                                 f"({len(usable)} total)")
                        msg = f"🔊 {name}  ({pos + 2 if pos + 2 <= len(usable) else 1}/{len(usable)})"
                        # Round 360: сохраняем выбор напрямую — как в
                        # меню дорожек (см. _present_audio_menu).
                        try:
                            if (self.channels and 0 <= self.current_index
                                    < len(self.channels)):
                                _u = self.channels[self.current_index].url
                                _tid, _tname = int(nxt[0]), str(name or '')
                                self._invoke_on_main.emit(
                                    lambda u=_u, t=_tid, n=_tname:
                                    self.config.update_channel_state(
                                        u, audio_track=t,
                                        audio_track_name=n))
                        except Exception as e:
                            log_error('cycle_audio.save_choice', e)
                    # Round 313: сигнал вместо QTimer. Вне lock'а —
                    # просто dispatch OSD-текста.
                    self._invoke_on_main.emit(
                        lambda m=msg: self.show_mini_osd(m))
                    # Round 362: страховка от зависшего после смены
                    # дорожки потока — см. _watch_audio_switch.
                    self._watch_audio_switch(player, _t0_switch)
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
        # Round 327: запоминаем состояние fullscreen ДО открытия диалога.
        # Юзер: «не только при нажатии на настройки а на все кнопки в
        # появившемся меню». Windows иногда демотит fullscreen-окно
        # когда от него уходит фокус на модальный диалог. Сами диалоги
        # мы не убираем (они нужны), но восстанавливаем fullscreen
        # после закрытия.
        w = self.window()
        was_fs = w is not None and w.isFullScreen()
        mins, ok = QInputDialog.getInt(
            self, "Sleep timer",
            "Minutes until pause (0 = off):",
            max(0, self.config.sleep_timer_minutes), 0, 240, 5)
        if was_fs and w is not None and not w.isFullScreen():
            try:
                w.showFullScreen()
                if hasattr(w, '_apply_fullscreen_chrome'):
                    w._apply_fullscreen_chrome(True)
            except Exception:
                pass
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
            # Round 351: pause() — нативный вызов; срабатывание
            # sleep-таймера шло синхронно на main thread, причём в
            # непредсказуемый момент (стрим к этому времени мог давно
            # умереть). В bg под lock'ом через _vlc_bg_call.
            if self.player:
                self._vlc_bg_call('sleep-pause', lambda p: p.pause())
            self.btn_play.setText("Play")
            return
        self._update_sleep_label()

    # --- Number input (digit keys select channel number) ---

    def _handle_digit(self, digit: int):
        # Round 331: ограничиваем 4 цифрами (никто не нумерует > 9999).
        self._number_input = (self._number_input + str(digit))[-4:]
        self.number_label.setText(self._number_input)
        self._show_number_input_osd(self._number_input)
        self._number_timer.start()

    def _show_number_input_osd(self, text: str):
        """Round 331: рисуем большие белые цифры по центру-верху видео
        пока юзер вводит номер канала. Через 1500мс _number_timer
        дёрнет _apply_number_input → канал переключится, OSD спрячется."""
        try:
            if not hasattr(self, 'number_input_osd'):
                return
            self.number_input_osd.setText(text)
            self.number_input_osd.adjustSize()
            pw = self.overlay_host.width()
            ph = self.overlay_host.height()
            w = self.number_input_osd.width()
            h = self.number_input_osd.height()
            # Round 337: раньше show()/raise_() выполнялись БЕЗУСЛОВНО
            # даже когда overlay_host ещё не получил валидную геометрию
            # (move() пропускался, но виджет всё равно показывался в
            # последней/дефолтной позиции) — юзер мог увидеть большую
            # OSD-цифру мелькнувшей в неверном углу сразу после старта
            # или перехода в fullscreen до первого _sync_overlay_host.
            # Теперь show целиком под тем же guard'ом что и move.
            if pw > 0 and ph > 0:
                self.number_input_osd.move(
                    max(0, (pw - w) // 2), max(0, int(ph * 0.15)))
                self.number_input_osd.show()
                self.number_input_osd.raise_()
        except Exception as e:
            log_error('_show_number_input_osd', e)

    def _apply_number_input(self):
        txt = self._number_input
        self._number_input = ""
        self.number_label.setText("")
        # Round 331: прячем большой OSD цифр после применения.
        try:
            if hasattr(self, 'number_input_osd'):
                self.number_input_osd.hide()
        except Exception:
            pass
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
        # Round 337: re-check `self.player is p` inside the bg thread
        # before calling stop() — if release_vlc()/a fresh init_vlc()
        # already swapped self.player out from under us (e.g. app is
        # closing concurrently), this becomes a no-op instead of
        # calling .stop() on an orphaned/possibly-released object.
        # Round 341: та же серия lock'ов что и остальные VLC-мутирующие
        # операции — .stop() отсюда мог интерливиться с активным _swap.
        def _bg():
            with self._vlc_op_lock:
                if self.player is p:
                    try:
                        p.stop()
                    except Exception:
                        pass
        try:
            threading.Thread(target=_bg, daemon=True, name='vlc-stop').start()
        except Exception as e:
            # Round 351: раньше фолбэк выполнял stop() СИНХРОННО на
            # вызывающей (main) нитке — с no-timeout lock'ом и нативным
            # вызовом, который сам же файл документирует как «блокирует
            # 5-20с на дохлых стримах». Причём Thread.start() падает
            # именно при перегрузке нитками (Round 346 поймал 11.2с
            # внутри start()) — то есть фолбэк срабатывал ровно тогда,
            # когда синхронный stop() опаснее всего. Теперь просто
            # логируем и пропускаем: следующий _swap/release_vlc всё
            # равно сделает stop() перед своей работой.
            log_error('stop.spawn', e)

    def release_vlc(self):
        # Round 291: НЕ вызываем self.current_media.release() — Python
        # обёртка vlc.Media сама делает libvlc_media_release в __del__
        # когда ссылка обнуляется. Ручной release + последующее None
        # давало double-free на части систем.
        # Round 337: stop() ПЕРЕД release() внутри ЭТОЙ ЖЕ нитки —
        # раньше MainWindow.closeEvent спавнил release_vlc() и
        # player_page.stop() как ДВЕ отдельные конкурентные нитки,
        # обе трогающие один и тот же self.player без всякой
        # синхронизации (stop() из одной, release() из другой — race
        # на живом libvlc-объекте при закрытии). Теперь closeEvent
        # зовёт только release_vlc() в одной нитке, и стоп + освобождение
        # гарантированно идут строго последовательно.
        # Round 341: + общий lock — та же серия защит что и у остальных
        # VLC-операций.
        with self._vlc_op_lock:
            # Round 382: сначала освобождаем плеер мини-превью (использует
            # тот же vlc_instance).
            try:
                self._release_preview_player()
            except Exception:
                pass
            if self.player is not None:
                try:
                    self.player.stop()
                except Exception:
                    pass
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
            # Step back 10s for VOD; on live, treated as prev channel.
            # Round 337: get_time/set_time уходят в фон — на подвисшем
            # стриме они могут блокировать так же как stop/set_media.
            self._seek_or_switch(-1)
        elif key == Qt.Key_Right:
            self._seek_or_switch(1)
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
            # Round 314: верхняя граница 200 — усиление громкости.
            self.vol_slider.setValue(min(200, self.vol_slider.value() + 5))
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
            # Round 351: set_rate в bg — этот инлайн-дубль cycle_speed
            # остался с прямым нативным вызовом на main thread.
            self._speed_idx = (self._speed_idx - 1) % len(self.SPEED_VALUES)
            speed = self.SPEED_VALUES[self._speed_idx]
            if self.player:
                self._vlc_bg_call('rate', lambda p, s=speed: p.set_rate(s))
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
            _retranslate_widgets(self)
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
        # Round 302: видимый курсор — см. ChannelsPage.search_edit.
        try:
            from PyQt5.QtGui import QPalette
            pal = self.search_edit.palette()
            pal.setColor(QPalette.Text, QColor("#FFFFFF"))
            pal.setColor(QPalette.WindowText, QColor("#FFFFFF"))
            pal.setColor(QPalette.Base, QColor(COLORS['surface']))
            pal.setColor(QPalette.Highlight, QColor(COLORS['primary']))
            pal.setColor(QPalette.HighlightedText, QColor("#FFFFFF"))
            self.search_edit.setPalette(pal)
        except Exception:
            pass
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
        # Round 345: чанкуем как ChannelsPage.filter_channels (Round 305)
        # — юзер: «она опять зависает при переходе на другую вкладку».
        # Этот метод целиком синхронно перебирал ВСЕ каналы на главном
        # потоке при каждом открытии вкладки «ТВ-гид» — единственное
        # место в проекте, которое так и не получило чанкинг. Первые 50
        # строим сразу (юзер видит верх списка мгновенно), остальное —
        # через QTimer 30мс, как везде.
        if not self.isVisible():
            return
        query = self.search_edit.text().strip().lower()
        filtered = [(idx, ch) for idx, ch in enumerate(self.channels)
                   if not query or query in ch.name.lower()]
        self._guide_filter_state = {'items': filtered, 'next_idx': 0}
        lst = self.guide_list
        lst.setUpdatesEnabled(False)
        try:
            lst.clear()
            first = min(50, len(filtered))
            for k in range(first):
                self._append_guide_item(*filtered[k])
            self._guide_filter_state['next_idx'] = first
        finally:
            lst.setUpdatesEnabled(True)
        self.status.setText(
            f"{len(filtered)} channels · updated {datetime.now().strftime('%H:%M')}")
        if len(filtered) > 50:
            if not hasattr(self, '_guide_chunk_timer'):
                self._guide_chunk_timer = QTimer(self)
                self._guide_chunk_timer.setInterval(30)
                self._guide_chunk_timer.timeout.connect(self._fill_guide_chunk)
            self._guide_chunk_timer.start()
        elif hasattr(self, '_guide_chunk_timer'):
            self._guide_chunk_timer.stop()

    def _append_guide_item(self, idx, ch):
        """Round 345: добавляет ОДНУ строку в guide_list. Выделено из
        refresh_list для переиспользования в чанках."""
        lst = self.guide_list
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

    def _fill_guide_chunk(self):
        """Round 345: подсыпаем 50 строк за тик — тот же паттерн что
        ChannelsPage._fill_next_chunk / PlayerPage._fill_overlay_chunk."""
        if not self.isVisible():
            self._guide_chunk_timer.stop()
            return
        st = getattr(self, '_guide_filter_state', None)
        if not st:
            self._guide_chunk_timer.stop()
            return
        items = st['items']
        start = st['next_idx']
        end = min(start + 50, len(items))
        lst = self.guide_list
        lst.setUpdatesEnabled(False)
        try:
            for k in range(start, end):
                self._append_guide_item(*items[k])
        finally:
            lst.setUpdatesEnabled(True)
        st['next_idx'] = end
        if end >= len(items):
            self._guide_chunk_timer.stop()

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
            _retranslate_widgets(self)
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

        # Round 350: каждый user-visible виджет тагается '_t_key'
        # (или _t_suffix_key / _t_placeholder_key / _t_item_keys) чтобы
        # retranslate_ui мог пройти findChildren и применить t(key).
        self._title = QLabel(t('settings'))
        self._title.setProperty('_t_key', 'settings')
        self._title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        layout.addWidget(self._title)
        layout.addSpacing(8)

        # VLC status
        self._vlc_label = QLabel(t('vlc_installed') if HAS_VLC else t('vlc_not_found'))
        self._vlc_label.setProperty(
            '_t_key', 'vlc_installed' if HAS_VLC else 'vlc_not_found')
        self._vlc_label.setStyleSheet(
            f"color: {'#4ECDC4' if HAS_VLC else COLORS['error']}; font-size: 14px;")
        layout.addWidget(self._vlc_label)
        layout.addSpacing(12)

        # Round 232 (Windows): language selector — самое заметное чего
        # не было в Windows-версии раньше.
        layout.addWidget(self._section('section_language'))
        lang_row = QHBoxLayout()
        self._lang_lbl = QLabel(t('language') + ":")
        self._lang_lbl.setProperty('_t_key', 'language')
        self._lang_lbl.setProperty('_t_suffix', ':')
        lang_row.addWidget(self._lang_lbl)
        self.lang_combo = NoWheelComboBox()
        # Round 242: расширенный список языков как Android LocaleHelper.
        # 'system' = автодетект из локали ОС. Реально перевод есть для
        # ru/en/uk/az; остальные показываются по дефолту (ru).
        for code, label in SUPPORTED_LANGUAGES:
            self.lang_combo.addItem(label, code)
        cur_lang = getattr(self.config, 'ui_language', 'en')
        for i in range(self.lang_combo.count()):
            if self.lang_combo.itemData(i) == cur_lang:
                self.lang_combo.setCurrentIndex(i)
                break
        self.lang_combo.currentIndexChanged.connect(self._save_language)
        lang_row.addWidget(self.lang_combo, 1)
        layout.addLayout(lang_row)
        layout.addSpacing(12)

        # --- Playback section ---
        layout.addWidget(self._section('section_playback'))

        # Buffer / network caching
        buf_row = QHBoxLayout()
        self._buf_lbl = QLabel(t('buffer_label'))
        self._buf_lbl.setProperty('_t_key', 'buffer_label')
        buf_row.addWidget(self._buf_lbl)
        self.buf_combo = NoWheelComboBox()
        # Round 337: добавлен 'buffer_default' (5000мс) — реальный
        # Config-дефолт (Round 328) не входил ни в одну из старых 4
        # опций (1500/3000/6000/10000), из-за чего _set_combo_by_value
        # молча падал на default_idx=1 («Normal 3000мс») и юзер видел
        # НЕВЕРНОЕ значение буфера в UI, хотя VLC реально играл на 5000.
        # 'buffer_high' заодно поднят с 6000 на 9000 — это то значение,
        # которое комментарии Round 328 обещают юзеру («можно вернуть
        # 9000 если запинки»), а не молчаливо-другое число.
        _buf_keys = ['buffer_low', 'buffer_normal', 'buffer_default',
                     'buffer_high', 'buffer_very_high']
        for k, v in zip(_buf_keys, (1500, 3000, 5000, 9000, 10000)):
            self.buf_combo.addItem(t(k), v)
        self.buf_combo.setProperty('_t_item_keys', _buf_keys)
        self._set_combo_by_value(self.buf_combo, self.config.network_caching_ms, default_idx=2)
        self.buf_combo.currentIndexChanged.connect(self._save_buffer)
        buf_row.addWidget(self.buf_combo, 1)
        layout.addLayout(buf_row)

        # Default volume
        vol_row = QHBoxLayout()
        self._vol_lbl = QLabel(t('default_volume'))
        self._vol_lbl.setProperty('_t_key', 'default_volume')
        vol_row.addWidget(self._vol_lbl)
        self.vol_spin = NoWheelSpinBox()
        # Round 314: до 200% — усиление громкости (см. PlayerPage).
        self.vol_spin.setRange(0, 200)
        self.vol_spin.setSuffix("%")
        self.vol_spin.setValue(self.config.volume)
        self.vol_spin.valueChanged.connect(self._save_volume)
        vol_row.addWidget(self.vol_spin)
        vol_row.addStretch()
        layout.addLayout(vol_row)

        # Round 247: цветовая тема — как Android (5 вариантов).
        theme_row = QHBoxLayout()
        self._theme_lbl = QLabel(t('color_theme'))
        self._theme_lbl.setProperty('_t_key', 'color_theme')
        theme_row.addWidget(self._theme_lbl)
        self.theme_combo = NoWheelComboBox()
        _theme_keys = ['theme_default', 'theme_blue', 'theme_green', 'theme_orange', 'theme_red']
        _theme_codes = ['default', 'blue', 'green', 'orange', 'red']
        for k, code in zip(_theme_keys, _theme_codes):
            self.theme_combo.addItem(t(k), code)
        self.theme_combo.setProperty('_t_item_keys', _theme_keys)
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
        self._clock_lbl = QLabel(t('clock_in_player_label'))
        self._clock_lbl.setProperty('_t_key', 'clock_in_player_label')
        clock_row.addWidget(self._clock_lbl)
        self.clock_combo = NoWheelComboBox()
        _clock_keys = ['clock_top_right', 'clock_top_left', 'clock_bottom_right',
                       'clock_bottom_left', 'clock_off']
        _clock_codes = ['top_right', 'top_left', 'bottom_right', 'bottom_left', 'off']
        for k, code in zip(_clock_keys, _clock_codes):
            self.clock_combo.addItem(t(k), code)
        self.clock_combo.setProperty('_t_item_keys', _clock_keys)
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
        self._sleep_lbl = QLabel(t('sleep_timer_default'))
        self._sleep_lbl.setProperty('_t_key', 'sleep_timer_default')
        sleep_row.addWidget(self._sleep_lbl)
        self.sleep_spin = NoWheelSpinBox()
        self.sleep_spin.setRange(0, 240)
        self.sleep_spin.setSuffix(t('sleep_minutes_off'))
        self.sleep_spin.setProperty('_t_suffix_key', 'sleep_minutes_off')
        self.sleep_spin.setValue(self.config.sleep_timer_minutes)
        self.sleep_spin.valueChanged.connect(self._save_sleep)
        sleep_row.addWidget(self.sleep_spin)
        sleep_row.addStretch()
        layout.addLayout(sleep_row)

        # --- Behaviour section ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_behaviour'))

        self.cb_autoplay = QCheckBox(t('autoplay_last_help'))
        self.cb_autoplay.setProperty('_t_key', 'autoplay_last_help')
        self.cb_autoplay.setChecked(self.config.autoplay_last)
        self.cb_autoplay.toggled.connect(self._save_autoplay)
        layout.addWidget(self.cb_autoplay)

        # Round 382: мини-превью выделенного канала при листании списка.
        self.cb_list_preview = QCheckBox(t('list_preview'))
        self.cb_list_preview.setProperty('_t_key', 'list_preview')
        self.cb_list_preview.setChecked(getattr(self.config, 'list_preview', False))
        self.cb_list_preview.toggled.connect(self._save_list_preview)
        layout.addWidget(self.cb_list_preview)

        self.cb_fullscreen = QCheckBox(t('open_player_fullscreen'))
        self.cb_fullscreen.setProperty('_t_key', 'open_player_fullscreen')
        self.cb_fullscreen.setChecked(self.config.remember_fullscreen)
        self.cb_fullscreen.toggled.connect(self._save_fullscreen)
        layout.addWidget(self.cb_fullscreen)

        self.cb_top = QCheckBox(t('always_on_top_mini'))
        self.cb_top.setProperty('_t_key', 'always_on_top_mini')
        self.cb_top.setChecked(self.config.always_on_top)
        self.cb_top.toggled.connect(self._save_always_on_top)
        layout.addWidget(self.cb_top)

        # --- Advanced playback section ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_advanced'))

        self.cb_hwdec = QCheckBox(t('hardware_decode_recommended'))
        self.cb_hwdec.setProperty('_t_key', 'hardware_decode_recommended')
        self.cb_hwdec.setChecked(self.config.hardware_decode)
        self.cb_hwdec.toggled.connect(self._save_hwdec)
        layout.addWidget(self.cb_hwdec)

        ao_row = QHBoxLayout()
        self._ao_lbl = QLabel(t('audio_output'))
        self._ao_lbl.setProperty('_t_key', 'audio_output')
        ao_row.addWidget(self._ao_lbl)
        self.aout_combo = NoWheelComboBox()
        _ao_keys = ['audio_output_auto', 'audio_output_directsound',
                    'audio_output_mmdevice', 'audio_output_waveout']
        _ao_codes = ['', 'directsound', 'mmdevice', 'waveout']
        for k, code in zip(_ao_keys, _ao_codes):
            self.aout_combo.addItem(t(k), code)
        self.aout_combo.setProperty('_t_item_keys', _ao_keys)
        self._set_combo_by_value(self.aout_combo, self.config.audio_output, 0)
        self.aout_combo.currentIndexChanged.connect(self._save_aout)
        ao_row.addWidget(self.aout_combo, 1)
        layout.addLayout(ao_row)

        ua_row = QHBoxLayout()
        self._ua_lbl = QLabel(t('user_agent'))
        self._ua_lbl.setProperty('_t_key', 'user_agent')
        ua_row.addWidget(self._ua_lbl)
        self.ua_edit = QLineEdit(self.config.user_agent)
        self.ua_edit.editingFinished.connect(self._save_ua)
        ua_row.addWidget(self.ua_edit, 1)
        layout.addLayout(ua_row)
        self._ua_hint = QLabel(t('ua_note_restart'))
        self._ua_hint.setProperty('_t_key', 'ua_note_restart')
        self._ua_hint.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 11px;")
        layout.addWidget(self._ua_hint)

        # --- EPG sources ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_epg_sources'))

        self.epg_list = QListWidget()
        self.epg_list.setMaximumHeight(120)
        self._refresh_epg_list()
        layout.addWidget(self.epg_list)

        epg_row = QHBoxLayout()
        self.epg_input = QLineEdit()
        self.epg_input.setPlaceholderText(t('epg_url_placeholder'))
        self.epg_input.setProperty('_t_placeholder_key', 'epg_url_placeholder')
        epg_row.addWidget(self.epg_input, 1)
        self._btn_epg_add = QPushButton(t('add'))
        self._btn_epg_add.setProperty('_t_key', 'add')
        self._btn_epg_add.clicked.connect(self._add_epg_url)
        epg_row.addWidget(self._btn_epg_add)
        self._btn_epg_del = QPushButton(t('remove'))
        self._btn_epg_del.setProperty('_t_key', 'remove')
        self._btn_epg_del.clicked.connect(self._remove_epg_url)
        epg_row.addWidget(self._btn_epg_del)
        layout.addLayout(epg_row)
        self._epg_hint = QLabel(t('epg_merged_note'))
        self._epg_hint.setProperty('_t_key', 'epg_merged_note')
        self._epg_hint.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 11px;")
        layout.addWidget(self._epg_hint)

        # Round 341: Quick-nav секция (Round 336) убрана из Settings.
        # Юзер: «зачем в настройках панель навигации я её просил
        # добавить в меню настройки которая выходит при клике по
        # стрелкам» — Round 336 положил её не туда. Реальное место —
        # center_menu_overlay (LEFT-стрелка, стадия 3), см.
        # PlayerPage._build_center_menu_overlay/_center_menu_action.

        # --- Data section ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_data'))

        data_row = QHBoxLayout()
        self._btn_clear_fav = QPushButton(t('clear_favorites'))
        self._btn_clear_fav.setProperty('_t_key', 'clear_favorites')
        self._btn_clear_fav.clicked.connect(self._clear_favorites)
        data_row.addWidget(self._btn_clear_fav)
        self._btn_clear_recent = QPushButton(t('clear_recent'))
        self._btn_clear_recent.setProperty('_t_key', 'clear_recent')
        self._btn_clear_recent.clicked.connect(self._clear_recent)
        data_row.addWidget(self._btn_clear_recent)
        self._btn_clear_pcs = QPushButton(t('clear_per_channel_state'))
        self._btn_clear_pcs.setProperty('_t_key', 'clear_per_channel_state')
        self._btn_clear_pcs.clicked.connect(self._clear_per_channel_state)
        data_row.addWidget(self._btn_clear_pcs)
        self._btn_reset = QPushButton(t('reset_settings_button'))
        self._btn_reset.setProperty('_t_key', 'reset_settings_button')
        self._btn_reset.clicked.connect(self._reset_settings)
        data_row.addWidget(self._btn_reset)
        data_row.addStretch()
        layout.addLayout(data_row)

        # --- Round 364: родительский контроль ---
        layout.addSpacing(8)
        layout.addWidget(self._section('parental_control'))
        par_row = QHBoxLayout()
        self._btn_parental = QPushButton()
        self._btn_parental.clicked.connect(self._parental_clicked)
        par_row.addWidget(self._btn_parental)
        self._btn_parental_cats = QPushButton(t('parental_locked_categories'))
        self._btn_parental_cats.clicked.connect(self._parental_categories)
        par_row.addWidget(self._btn_parental_cats)
        par_row.addStretch()
        layout.addLayout(par_row)
        # Round 382: показ взрослых категорий (18+/XXX) — включение за PIN.
        self.cb_show_adult = QCheckBox(t('show_adult'))
        self.cb_show_adult.setProperty('_t_key', 'show_adult')
        self.cb_show_adult.setChecked(getattr(self.config, 'show_adult', False))
        self.cb_show_adult.toggled.connect(self._toggle_show_adult)
        layout.addWidget(self.cb_show_adult)
        self._refresh_parental_btn()

        # --- Updates ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_updates'))
        # Round 263: показываем текущую версию ПРЯМО в Updates-секции
        # (юзер: «сам тоже не пишет какая у него сейчас версия»). До этого
        # версия была только в маленькой подписи внизу страницы.
        self._cur_ver_label = QLabel(
            t('installed_template',
              name=f"TVViewer v{WIN_VERSION_NAME}", build=WIN_VERSION_CODE))
        self._cur_ver_label.setProperty('_t_key', 'installed_template')
        self._cur_ver_label.setProperty(
            '_t_kwargs', {'name': f"TVViewer v{WIN_VERSION_NAME}",
                          'build': WIN_VERSION_CODE})
        self._cur_ver_label.setStyleSheet(
            "color: white; font-size: 13px; font-weight: bold;"
            " padding: 4px 0;")
        layout.addWidget(self._cur_ver_label)
        upd_row = QHBoxLayout()
        self.btn_check_updates = QPushButton(t('check_for_updates'))
        self.btn_check_updates.setProperty('_t_key', 'check_for_updates')
        self.btn_check_updates.clicked.connect(self._check_updates)
        upd_row.addWidget(self.btn_check_updates)
        # Round 263: дефолтный статус — показываем что мы знаем версию
        # ДО клика на «Check». Иначе юзер думает «ничего не пишет».
        self.update_status = QLabel(
            t('current_build_status_template', build=WIN_VERSION_CODE))
        self.update_status.setProperty('_t_key', 'current_build_status_template')
        self.update_status.setProperty('_t_kwargs', {'build': WIN_VERSION_CODE})
        self.update_status.setStyleSheet(
            f"color: {COLORS['text_secondary']}; font-size: 12px;")
        self.update_status.setWordWrap(True)
        upd_row.addWidget(self.update_status, 1)
        layout.addLayout(upd_row)
        # Round 263: ручной fallback — открыть страницу релизов в
        # браузере. Если auto-check молчит / network падает / SSL —
        # юзер всегда может зайти руками и скачать TVViewer-update.exe.
        self._btn_releases = QPushButton(t('open_releases'))
        self._btn_releases.setProperty('_t_key', 'open_releases')
        self._btn_releases.clicked.connect(self._open_releases_page)
        layout.addWidget(self._btn_releases)

        # --- Help / report issue ---
        layout.addSpacing(8)
        layout.addWidget(self._section('section_help'))
        help_row = QHBoxLayout()
        self._btn_report = QPushButton(t('report_issue'))
        self._btn_report.setProperty('_t_key', 'report_issue')
        self._btn_report.clicked.connect(self._report_issue)
        help_row.addWidget(self._btn_report)
        self._btn_log = QPushButton(t('open_log_folder'))
        self._btn_log.setProperty('_t_key', 'open_log_folder')
        self._btn_log.clicked.connect(self._open_log_dir)
        help_row.addWidget(self._btn_log)
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

    def _section(self, key_or_text: str) -> QLabel:
        # Round 350: принимает translation KEY; запоминает его в
        # property '_t_key' для retranslate_ui. Если передан raw-текст
        # (без зарегистрированного перевода) — отображаем как есть.
        text = t(key_or_text) if key_or_text in (TRANSLATIONS.get('en') or {}) else key_or_text
        lbl = QLabel(text)
        lbl.setProperty('_t_key', key_or_text)
        # Round 337: тег '_theme_role' — _reapply_theme_roles() находит
        # такие лейблы и перегенерирует их inline-стиль из АКТУАЛЬНЫХ
        # COLORS при смене темы. Просто unpolish/polish (Round 335) тут
        # не помогает: это Python f-string, зафиксированный на момент
        # создания виджета, а не Qt QSS-каскад — apply_theme() меняет
        # только словарь COLORS, а не уже вычисленную строку.
        lbl.setProperty('_theme_role', 'section_header')
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
        if not code or code == getattr(self.config, 'ui_language', 'en'):
            return
        self.config.ui_language = code
        self.config.save_async()
        set_ui_language(code)
        self.settings_changed.emit()  # MainWindow дёрнет _retranslate_all

    def retranslate_ui(self):
        # Round 350: live-retranslate всех виджетов с '_t_key' свойством.
        # Покрывает QLabel / QPushButton / QCheckBox / QSpinBox /
        # QLineEdit / QComboBox.
        from PyQt5.QtWidgets import QCheckBox, QSpinBox
        try:
            _retranslate_widgets(self)
        except Exception as e:
            log_error('SettingsPage.retranslate_ui', e)

    def _save_theme(self, _idx):
        """Round 247: меняем цветовую тему — apply_theme +
        re-применяем stylesheet к QApplication. Без перезапуска.
        Round 335: force unpolish/polish на все виджеты — иначе
        QMainWindow/nav_bar/прочие с inline-setStyleSheet остаются
        со старыми цветами (юзер: «основной фон программы остаётся
        без изменения»). Плюс перепривязываем известные inline-
        styled виджеты главного окна."""
        code = self.theme_combo.currentData()
        if not code or code == getattr(self.config, 'theme_color', 'default'):
            return
        self.config.theme_color = code
        self.config.save_async()
        try:
            apply_theme(code)
            app = QApplication.instance()
            app.setStyleSheet(STYLESHEET)
            # Force re-polish — иначе Qt держит вычисленные стили в кеше.
            for w in app.allWidgets():
                try:
                    w.style().unpolish(w)
                    w.style().polish(w)
                    w.update()
                except Exception:
                    pass
            # Перепривязываем известные виджеты с inline-COLORS-стилем.
            try:
                mw = self.window()
                if hasattr(mw, '_refresh_themed_widgets'):
                    mw._refresh_themed_widgets()
            except Exception:
                pass
            # Round 337: section-заголовки самой SettingsPage (тут же,
            # прямо над/под combo темы который юзер только что трогал —
            # самый заметный из «не перекрасившихся» элементов).
            try:
                _reapply_theme_roles(self)
            except Exception:
                pass
        except Exception as e:
            log_error('_save_theme', e)
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

    def _save_list_preview(self, checked):
        # Round 382: мини-превью при листании.
        self.config.list_preview = bool(checked)
        self.config.save_async()

    def _toggle_show_adult(self, checked):
        # Round 382: включение показа 18+/XXX — только за PIN; выключение —
        # без PIN. Пока PIN не подтверждён, держим чекбокс выключенным.
        if not checked:
            self.config.show_adult = False
            self.config.save_async()
            self.settings_changed.emit()
            return

        def _revert():
            self.cb_show_adult.blockSignals(True)
            self.cb_show_adult.setChecked(False)
            self.cb_show_adult.blockSignals(False)

        def _enable():
            self.config.show_adult = True
            self.config.save_async()
            self.cb_show_adult.blockSignals(True)
            self.cb_show_adult.setChecked(True)
            self.cb_show_adult.blockSignals(False)
            self.settings_changed.emit()

        # Держим выключенным до подтверждения.
        _revert()
        if not self.config.parental_enabled():
            # PIN ещё не задан — предлагаем создать, затем включаем.
            ask_new_pin(self, self.config,
                        on_done=lambda: _enable()
                        if self.config.parental_enabled() else None)
        else:
            ask_pin(self, self.config, _enable, unlock_session=False)

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

    # ---- Round 364: родительский контроль ----
    def _refresh_parental_btn(self):
        try:
            if self.config.parental_enabled():
                self._btn_parental.setText(t('parental_status_on'))
            else:
                self._btn_parental.setText(t('parental_set_pin'))
            self._btn_parental_cats.setEnabled(self.config.parental_enabled())
        except Exception:
            pass

    def _parental_clicked(self):
        # PIN не задан — устанавливаем. Задан — просим текущий PIN,
        # затем предлагаем сменить/отключить.
        if not self.config.parental_enabled():
            ask_new_pin(self, self.config, on_done=self._refresh_parental_btn)
            return

        def _authed():
            from PyQt5.QtWidgets import QMessageBox
            box = QMessageBox(self)
            box.setWindowTitle(t('parental_control'))
            box.setText(t('parental_control'))
            b_change = box.addButton(t('parental_change_pin'),
                                     QMessageBox.AcceptRole)
            b_remove = box.addButton(t('parental_remove_pin'),
                                     QMessageBox.DestructiveRole)
            box.addButton(t('cancel'), QMessageBox.RejectRole)
            box.exec_()
            clicked = box.clickedButton()
            if clicked is b_change:
                ask_new_pin(self, self.config, on_done=self._refresh_parental_btn)
            elif clicked is b_remove:
                self.config.clear_pin()
                global _PARENTAL_SESSION_UNLOCKED
                _PARENTAL_SESSION_UNLOCKED = False
                self._refresh_parental_btn()
                QMessageBox.information(self, t('parental_control'),
                                       t('parental_pin_removed'))
        # Управление — только после ввода текущего PIN (не снимая
        # сессионную блокировку просмотра).
        ask_pin(self, self.config, _authed, unlock_session=False)

    def _parental_categories(self):
        if not self.config.parental_enabled():
            return

        def _authed():
            from PyQt5.QtWidgets import (QDialog, QVBoxLayout, QScrollArea,
                                         QWidget, QCheckBox, QDialogButtonBox)
            # Категории: из текущего плейлиста + уже заблокированные.
            cats = set()
            try:
                mw = self.window()
                for ch in getattr(mw, 'channels', []) or []:
                    grp = (ch.group or '').split(';')[0].strip()
                    if grp:
                        cats.add(grp)
            except Exception:
                pass
            cats |= set(self.config.locked_categories)
            cats = sorted(cats)
            dlg = QDialog(self)
            dlg.setWindowTitle(t('parental_locked_categories'))
            dlg.setStyleSheet(STYLESHEET)
            dlg.setMinimumSize(360, 420)
            v = QVBoxLayout(dlg)
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            inner = QWidget()
            iv = QVBoxLayout(inner)
            checks = {}
            for c in cats:
                cb = QCheckBox(c)
                cb.setChecked(c in self.config.locked_categories)
                iv.addWidget(cb)
                checks[c] = cb
            iv.addStretch()
            scroll.setWidget(inner)
            v.addWidget(scroll)
            bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
            v.addWidget(bb)
            bb.accepted.connect(dlg.accept)
            bb.rejected.connect(dlg.reject)
            if dlg.exec_() == QDialog.Accepted:
                self.config.locked_categories = {
                    c for c, cb in checks.items() if cb.isChecked()}
                self.config.save_async()
        ask_pin(self, self.config, _authed, unlock_session=False)

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
                "Лог отправлен и URL для отчёта скопирован в буфер обмена.")
        except Exception as e:
            QMessageBox.warning(self, "Report issue", f"Could not open the page: {e}")

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
                f"Текущий build {cur}. Сервер недоступен. См. tvviewer.log.")
            log_warn('update', "manual check: info is None (network or parse error)")
            # Round 263: даже когда сеть не отдала — показываем юзеру
            # его текущую версию и подсказываем как смотреть лог.
            QMessageBox.information(
                self, "Updates",
                f"Текущая установленная версия:\n"
                f"TVViewer v{WIN_VERSION_NAME} build {cur}\n\n"
                "Не удалось связаться с сервером (нет сети / firewall /\n"
                "блокировка SSL в этой сборке PyInstaller).\n\n"
                "Подробности — в файле tvviewer.log (нажмите\n"
                "«Open log folder» в этой же вкладке Настроек).")
            return
        latest = int(info.get('code', 0))
        log_info('update', f"check result: latest={latest} current={cur} url={info.get('url','')}")
        if latest <= cur:
            self.update_status.setText(
                f"У вас последняя версия — build {cur}. На сервере: {latest}.")
            QMessageBox.information(
                self, "Updates",
                f"You're on the latest version.\n\n"
                f"Installed: TVViewer v{WIN_VERSION_NAME} build {cur}\n"
                f"Server:    build {latest}")
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
            self, t('clear_favorites'),
            t('clear_favorites_confirm'),
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply == QMessageBox.Yes:
            self.config.favorites.clear()
            self.config.save_async()
            self.settings_changed.emit()

    def _reset_settings(self):
        reply = QMessageBox.question(
            self, t('reset_confirm_title'),
            t('reset_confirm_body'),
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if reply != QMessageBox.Yes:
            return
        self.config.volume = 80
        # Round 337: было hardcoded 9000 (Round 292), но Config.__init__
        # (Round 328) уже понизила дефолт до 5000 — Reset Settings
        # расходился с «чистой установкой» и удваивал буфер по
        # сравнению с новым default'ом. Выровнено.
        self.config.network_caching_ms = 5000
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
    # Round 313: дублёр PlayerPage._invoke_on_main для MainWindow-
    # уровневых bg-нитей (meta-fill, ensure_loaded callback). Любой
    # callable, переданный сюда из threading.Thread, исполняется в
    # main-нитке без QTimer-warning'а.
    _invoke_on_main = pyqtSignal(object)

    def __init__(self, progress_cb=None, config=None):
        super().__init__()
        self._invoke_on_main.connect(self._run_on_main)
        # Round 255: progress_cb(percent, text) — колбек для splash.
        # Вызывается между шагами init_ui чтобы юзер видел движение
        # и анимацию прогресс-бара пока строятся страницы.
        self._progress_cb = progress_cb or (lambda *a, **kw: None)
        # Round 351: переиспользуем bootstrap-Config из main() вместо
        # повторного чтения config.json с диска (двойной AV-сканируемый
        # read на старте, на ровном месте).
        self.config = config if config is not None else Config()
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
        # Round 288: learned-logos — постоянная таблица «имя канала →
        # logo URL», копит то, что приходило с tvg-logo в РАНЬШЕ
        # открытых плейлистах. Когда тот же канал в следующем плейлисте
        # идёт без logo — fallback на learned.
        self.learned_logos = LearnedLogos(self.cache_dir)
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
            w, h = vf.width(), vf.height()
            # Round 337: тот же guard что в PlayerPage._sync_overlay_host
            # (которая уже проверяет w<=0/h<=0). При сворачивании окна
            # на Windows moveEvent/resizeEvent иногда стреляют с
            # transient нулевым размером — без guard'а SetWindowPos тут
            # коллапсировал бы overlay_host в 0×0, а поскольку эта
            # функция не трогает page._last_overlay_geom (кэш
            # _sync_overlay_host), последующий _sync_overlay_host мог
            # решить что геометрия «не изменилась» и не восстановить её.
            if w <= 0 or h <= 0:
                return
            # Round 322: setGeometry на Qt.Tool top-level окне с
            # WA_TranslucentBackground идёт через event queue и
            # обновляется с лагом — во время drag юзер видит как
            # оверлей «отстаёт» от перетаскиваемой формы. Дёргаем
            # SetWindowPos напрямую через user32 — мгновенно меняет
            # позицию HWND без Qt-очереди.
            if sys.platform == 'win32':
                try:
                    import ctypes
                    _setup_user32_argtypes()
                    hwnd = int(host.winId())
                    # HWND_TOPMOST=-1, SWP_NOACTIVATE=0x10, SWP_NOZORDER=0x4
                    ctypes.windll.user32.SetWindowPos(
                        hwnd, 0, tl.x(), tl.y(), w, h, 0x10 | 0x4)
                except Exception:
                    host.setGeometry(tl.x(), tl.y(), w, h)
            else:
                host.setGeometry(tl.x(), tl.y(), w, h)
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
                # Round 359: если открыт popup (QMenu выбора аудио-
                # дорожки и т.п.) — НЕ перехватываем. Раньше стрелки
                # Up/Down при открытом меню уходили в _handle_key →
                # switch_channel, а меню их не видело вовсе — юзер:
                # «переключение дорожек аудио не получается при помощи
                # клавиатуры, можно только мышкой».
                if QApplication.activePopupWidget() is not None:
                    return False
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
        """Round 299: возвращён promptpop на старте, юзер: «нет проверки
        на обновление при открытии приложения». Используем
        QMessageBox.open() (НЕ exec_) — non-blocking диалог. Round 299
        пофиксил false-positive watchdog'а, так что блокировку диалога
        теперь watchdog корректно отличит от реального фриза."""
        if not isinstance(info, dict):
            return
        latest = int(info.get('code', 0))
        if latest <= WIN_VERSION_CODE:
            return
        log_info('update',
                 f"new build {latest} available (current {WIN_VERSION_CODE})")
        # Также пишем в Settings status — как раньше.
        try:
            if hasattr(self, 'settings_page') and hasattr(self.settings_page, 'update_status'):
                self.settings_page.update_status.setText(
                    f"Доступен build {latest}. "
                    f"Установлен build {WIN_VERSION_CODE}.")
        except Exception:
            pass
        # Non-modal prompt — открывается без блокировки event loop'а.
        try:
            notes = (info.get('notes') or '').strip()
            preview = notes[:300] + ('…' if len(notes) > 300 else '')
            msg = QMessageBox(self)
            msg.setIcon(QMessageBox.Information)
            msg.setWindowTitle("Доступно обновление")
            msg.setText(f"Новый build {latest} доступен.\n"
                        f"Установлен build {WIN_VERSION_CODE}.")
            if preview:
                msg.setInformativeText(preview)
            msg.setStandardButtons(
                QMessageBox.Yes | QMessageBox.No)
            msg.setDefaultButton(QMessageBox.Yes)
            msg.button(QMessageBox.Yes).setText("Обновить сейчас")
            msg.button(QMessageBox.No).setText("Позже")
            # Сохраняем ссылку на info чтобы _on_startup_update_choice
            # знала откуда брать URL.
            self._pending_update_info = info
            msg.finished.connect(self._on_startup_update_choice)
            self._startup_update_msg = msg  # держим ref
            msg.open()  # non-blocking
        except Exception as e:
            log_error('startup_update_prompt', e)

    def _on_startup_update_choice(self, code):
        """Round 299: пользователь ответил на стартовое обновление-prompt."""
        try:
            if code != QMessageBox.Yes:
                return
            info = getattr(self, '_pending_update_info', None)
            if not info:
                return
            url = info.get('url') or ''
            if not url:
                return
            # Round 351: НЕ переключаемся в Settings. Юзер: «после того
            # как вышло окно обновления и я нажал обновить почему то
            # открывается окно настройки». switch_page(4) был здесь
            # только ради видимости update_status-лейбла, но прогресс
            # скачивания и так показывается модальным QDialog'ом
            # (_do_download._dl_dialog) — это top-level окно, оно видно
            # с любой вкладки. Остаёмся там, где юзер был.
            latest = int(info.get('code', 0))
            try:
                self.settings_page._do_download(url, latest)
            except Exception as e:
                log_error('startup_update_download', e)
        except Exception as e:
            log_error('_on_startup_update_choice', e)

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
        # Round 323: устанавливаем owner-relationship для overlay_host —
        # Qt.Tool top-level окну с parent=None Windows отказывает в
        # активации от клика по дочернему виджету. С owner=MainWindow
        # активация разрешена и WM_SETFOCUS доходит до QLineEdit.
        # Юзер: «опять» — Round 320/321 (SetForegroundWindow + ALT
        # press) сами по себе не решают проблему пока окно не имеет
        # владельца.
        try:
            host = getattr(self.player_page, 'overlay_host', None)
            if host is not None:
                flags = host.windowFlags()
                host.setParent(self, flags)
                host.setAttribute(Qt.WA_TranslucentBackground, True)
                host.hide()
        except Exception as e:
            log_error('overlay_host.setParent', e)

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

        # Round 328: «← Назад» кнопка слева для возврата в предыдущую
        # вкладку. Юзер: «Добавь возможность возврата из других окон
        # в основную там откуда оно было открыто».
        # Round 337: было hardcoded "← Назад" — никогда не переводилось
        # при смене языка (Round 329's live-retranslate machinery
        # никогда до него не доходила). Используем существующий ключ
        # 'back' + '_t_key'/'_t_prefix' тег как у остальных nav-кнопок,
        # чтобы _update_nav_labels/общий retranslate sweep его нашёл.
        self._btn_back = QPushButton(f"←  {t('back')}")
        self._btn_back.setProperty('_t_key', 'back')
        self._btn_back.setProperty('_t_prefix', "←  ")
        self._btn_back.setObjectName("navBtn")
        self._btn_back.setFixedWidth(110)
        self._btn_back.clicked.connect(self.go_back)
        self._btn_back.setEnabled(False)
        nav_layout.addWidget(self._btn_back)

        # Round 233/235/241: nav-кнопки с translation-ключом + Material
        # Unicode-иконкой. Home (index 7) добавлен первым.
        self.nav_buttons = []
        nav_items = [
            ('home',      getattr(self, '_home_index', 7), '🏠'),
            ('playlists', 0, '📋'),
            # Round 382: вкладка «Каналы» убрана из навигации по просьбе
            # юзера (не нужна — список каналов открывается из плеера/Home).
            # Страница channels_page (индекс 1) остаётся: её использует
            # Back из плеера, F2, зэппинг и оверлей списка.
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
        # Round 337: юзер никогда не «был» на странице 0 (дефолт
        # QStackedWidget) до этого момента — это bootstrap, а не
        # реальная навигация. Без _suppress_nav_history switch_page
        # спуривало пушил индекс 0 в ещё-пустую _nav_history (которую
        # оно же и создаёт первым делом), и «← Назад»/Backspace на
        # свежем запуске улетали на вкладку Плейлисты вместо no-op.
        try:
            self._nav_history = []
            self._suppress_nav_history = True
            self.switch_page(self._home_index)
        except Exception:
            self.update_nav_highlight(0)
        finally:
            self._suppress_nav_history = False

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
            # Round 328: трекаем историю навигации для «Назад» (Backspace
            # на не-плеере). Юзер: «Добавь возможность возврата из
            # других окон в основную там откуда оно было открыто».
            # Игнорируем дубли подряд и переходы внутри одной вкладки.
            # _suppress_nav_history выставляется в go_back чтобы возврат
            # не добавлял запись обратно в стек.
            if not hasattr(self, '_nav_history'):
                self._nav_history = []
            cur_idx = self.stack.currentIndex()
            if (not getattr(self, '_suppress_nav_history', False)
                    and cur_idx != idx
                    and (not self._nav_history
                         or self._nav_history[-1] != cur_idx)):
                self._nav_history.append(cur_idx)
                # Cap чтобы не разрастался — 20 шагов хватит.
                if len(self._nav_history) > 20:
                    self._nav_history = self._nav_history[-20:]
            log_info('nav', f"switch_page → {idx}")
            going_to_player = (idx == 3)
            # Round 257/326: раньше при переходе на ЛЮБУЮ страницу
            # кроме плеера форс-выходили из fullscreen. Юзер: «при
            # нажатии на кнопку настройки при полноэкранном
            # отображении программы оно уходит с фулл скрин так быть
            # не должно». Теперь fullscreen сохраняется — nav_bar и
            # shortcut_bar просто становятся видимыми (setVisible
            # ниже), и юзер может ходить по вкладкам не выходя из
            # полноэкранного режима.
            if hasattr(self, 'nav_bar'):
                self.nav_bar.setVisible(not going_to_player)
            if hasattr(self, 'shortcut_bar'):
                self.shortcut_bar.setVisible(not going_to_player)
        except Exception as e:
            log_error('switch_page', e, extra=f"idx={idx}")
        if idx == 2:
            self.favorites_page.refresh(self.channels, self.epg_data)
        elif idx == 5:
            # Round 308/311: ленивый старт EPG раньше срабатывания
            # 60-секундного автотаймера. Если юзер открыл TV-гид
            # сразу — нет смысла ждать минуту, грузим прямо сейчас.
            self._fire_deferred_epg(reason='TvGuide opened')
            self.tv_guide_page.set_data(self.channels, self.epg_data)
        elif idx == 6:
            self.recent_page.refresh(self.channels, self.epg_data)
        # Round 330: НЕ stop()-аем VLC при switch_page. Юзер: «при
        # возврате назад он не воспроизводит канал нужно выбрать его
        # заново из списка и запустить тогда показывает». Раньше любой
        # switch_page дёргал stop() — даже при уходе из плеера в
        # настройки и обратно. Теперь канал продолжает играть в фоне
        # (HWND видеа просто скрыт пока юзер не на плеере), при возврате
        # на player_page видео сразу видимо без переинициализации.
        # Сохраняем per-channel state (volume/aspect/position) при
        # уходе с плеера — раньше это делал _save_current_channel_state
        # внутри stop().
        try:
            cur_w = self.stack.currentWidget()
            if (isinstance(cur_w, PlayerPage)
                    and not isinstance(self.stack.widget(idx), PlayerPage)):
                self.player_page._save_current_channel_state()
        except Exception:
            pass
        self.stack.setCurrentIndex(idx)
        self.update_nav_highlight(idx)
        # Round 328: «← Назад» enabled только когда есть куда возвращаться.
        try:
            if hasattr(self, '_btn_back'):
                self._btn_back.setEnabled(bool(self._nav_history))
        except Exception:
            pass
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

    def _refresh_themed_widgets(self):
        """Round 335: пере-применяем inline-стили, использующие COLORS,
        после смены темы. apply_theme + QApplication.setStyleSheet
        обновляет глобальную таблицу, но виджеты с f-string inline
        styleSheet остаются со старыми цветами потому что f-string
        вычисляется один раз при создании."""
        try:
            if hasattr(self, 'nav_bar'):
                self.nav_bar.setStyleSheet(
                    f"background-color: {COLORS['surface']};")
            if hasattr(self, 'shortcut_bar'):
                self.shortcut_bar.setStyleSheet(
                    f"background-color: {COLORS['background']};"
                    f" color: {COLORS['text_hint']};"
                    " padding: 4px 12px; font-size: 11px;"
                    f" border-top: 1px solid {COLORS['surface']};")
            # Принудительный перерисов главного окна.
            self.update()
        except Exception as e:
            log_error('_refresh_themed_widgets', e)

    def go_back(self):
        """Round 328: возврат на предыдущую страницу из истории.
        Юзер: «Добавь возможность возврата из других окон в основную
        там откуда оно было открыто». Если истории нет — на Home."""
        try:
            history = getattr(self, '_nav_history', None) or []
            if history:
                prev = history.pop()
                self._nav_history = history
                log_info('nav', f"go_back → {prev}")
                self._suppress_nav_history = True
                try:
                    self.switch_page(prev)
                finally:
                    self._suppress_nav_history = False
            elif hasattr(self, '_home_index'):
                self.switch_page(self._home_index)
        except Exception as e:
            log_error('go_back', e)

    def _handle_key(self, key):
        """Round 248: единый обработчик хоткеев. Вызывается и из
        keyPressEvent, и из application-level event filter (чтобы
        клавиши работали даже когда фокус забрало нативное VLC-окно).
        Возвращает True если клавиша обработана."""
        try:
            # Round 303: в PIP-режиме (frameless 480×270 always-on-top)
            # бывает не очевидно как из него выйти — bottom-nav скрыт,
            # верхняя панель плеера микроскопическая, кнопка PiP в right-
            # overlay требует RIGHT-пресса и не всегда видна юзеру.
            # Юзер: «нет возможности выйти из режима PIP». Принимаем
            # P или Esc на уровне MainWindow как универсальный exit-PIP.
            if getattr(self, '_pip_active', False):
                if key in (Qt.Key_P, Qt.Key_Escape):
                    self.toggle_pip_mode()
                    return True
            current = self.stack.currentWidget()
            # Round 328: Backspace на не-плеере = «Назад» (история).
            # На плеере у Backspace своё значение (закрыть overlay /
            # выйти из fullscreen — см. ниже).
            if (key in (Qt.Key_Backspace, Qt.Key_Escape)
                    and not isinstance(current, PlayerPage)):
                # Не мешаем редактированию в QLineEdit.
                fw = QApplication.focusWidget()
                if not isinstance(fw, QLineEdit):
                    self.go_back()
                    return True
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
                        # Round 300: RIGHT идёт ОБРАТНО по той же
                        # цепочке (юзер: «Так же в обратном порядке
                        # при нажатии вправо»). Из stage=1 → stage=0
                        # (закрыто).
                        current.right_press_state_machine()
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
                        # Round 300: stage=2 → stage=1 (каналы).
                        current.right_press_state_machine(); return True
                elif (hasattr(current, 'center_menu_overlay')
                      and current.center_menu_overlay.isVisible()):
                    if key == Qt.Key_Left:
                        current.left_press(); return True
                    if key == Qt.Key_Right:
                        # Round 300: stage=3 → stage=2 (категории) или
                        # → stage=1 если категорий нет.
                        current.right_press_state_machine(); return True
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
                        # Round 353: логика вынесена в toggle_mute —
                        # общая с средней кнопкой мыши над видео.
                        current.toggle_mute()
                        return True
                    if key in (Qt.Key_Plus, Qt.Key_Equal, Qt.Key_VolumeUp):
                        # Round 314: верхняя граница 200 — усиление.
                        current.vol_slider.setValue(min(200, current.vol_slider.value() + 5))
                        return True
                    if key in (Qt.Key_Minus, Qt.Key_Underscore, Qt.Key_VolumeDown):
                        current.vol_slider.setValue(max(0, current.vol_slider.value() - 5))
                        return True
                # Цифровые клавиши 0-9 — ввод номера канала.
                # Round 331: единая точка через _handle_digit — она
                # показывает большой OSD-цифр поверх видео.
                if Qt.Key_0 <= key <= Qt.Key_9:
                    try:
                        current._handle_digit(key - Qt.Key_0)
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
        # Round 351: вместо btn.setStyleSheet(STYLESHEET) — re-parse
        # полного глобального QSS на каждую кнопку при каждом
        # переключении вкладки — просто unpolish/polish: смена
        # objectName переприменяет селекторы уже применённого
        # app-стиля, парсить заново нечего.
        for btn, idx in self.nav_buttons:
            new_name = "navBtnActive" if idx == active_idx else "navBtn"
            if btn.objectName() != new_name:
                btn.setObjectName(new_name)
                st = btn.style()
                st.unpolish(btn)
                st.polish(btn)

    def load_playlist(self, name, url, switch_to_channels=True):
        self.channels_page.status_label.setText("Loading...")
        # Round 306: switch_to_channels=False — auto_load_last на старте
        # грузит плейлист, но Home-страницу не сбрасывает.
        if switch_to_channels:
            self.switch_page(1)
        self.config.last_playlist_url = url
        self.config.last_playlist_name = name
        self.config.save_async()

        self.loader_thread = LoadPlaylistThread(url)
        self.loader_thread.finished.connect(lambda r: self.on_playlist_loaded(r, name))
        self.loader_thread.error.connect(self.on_playlist_error)
        self.loader_thread.start()

    def _queue_logo_urls_chunked(self, urls, tag=''):
        """Round 347: раньше pre-queue логотипов шёл ОДНИМ синхронным
        циклом по self.channels на main thread — logo_cache.get()
        внутри дёргает os.path.exists() на диск на КАЖДЫЙ канал. Юзер
        поймал watchdog-стек 15.6с: _run_on_main → _ui_after_enrich →
        get → os.path.exists. Чанки через QTimer (эта функция) сами по
        себе не спасли — Round 349: юзер поймал НОВЫЙ стек 14.3с прямо
        внутри чанка (_step → get → os.path.exists), потому что под
        нагруженным антивирусом 200 стат-вызовов подряд всё равно
        стоят секунды ДО того как QTimer успевает вернуть управление в
        event loop.

        Round 349: единственная медленная часть (os.path.exists) теперь
        выполняется ЗАРАНЕЕ в фоновой нитке через
        LogoCache._prescan_missing_bg(); сюда приходит уже
        ОТФИЛЬТРОВАННЫЙ список urls, которым реально нужна закачка.
        Этот метод только раскладывает их по очереди (enqueue — без
        обращения к диску), так что даже без чанкинга был бы быстрым;
        чанки оставлены на случай очень больших плейлистов, чтобы не
        держать GIL одним циклом на десятки тысяч элементов."""
        if self.logo_cache is None:
            return
        urls = list(urls)
        state = {'i': 0}
        CHUNK = 500

        def _step():
            cache = self.logo_cache
            i = state['i']
            end = min(i + CHUNK, len(urls))
            for j in range(i, end):
                cache.enqueue(urls[j])
            state['i'] = end
            if end < len(urls):
                QTimer.singleShot(0, _step)
            else:
                log_info('logo', f"{tag}pre-queued {len(urls)} logo URLs")

        _step()

    def on_playlist_loaded(self, result: PlaylistResult, name: str):
        self.channels = result.channels
        # Round 288: тройная стратегия логотипов — порт Android.
        #   1) tvg-logo из плейлиста (уже разобран parse_m3u)
        #   2) learned_logos — что копили из ПРЕДЫДУЩИХ плейлистов
        #   3) iptv-org channels.json — синхронно, если БД уже в памяти
        # Round 332: ВСЯ цепочка enrichment'а уходит в bg-нитку. Юзер:
        # «опять есть зависания. сделай так чтобы ни одного зависания
        # не было». Раньше fill_missing + harvest + json.dump (5-50мс на
        # 3639 каналов) шли синхронно на main. Теперь bg-нитка делает
        # всю обработку и возвращает в main только UI-кусок через
        # _invoke_on_main (pre-queue в QObject LogoCache + set_channels).
        import threading as _th

        def _enrich_bg():
            try:
                if hasattr(self, 'learned_logos'):
                    self.learned_logos.fill_missing(self.channels)
            except Exception as e:
                log_error('learned_logos.fill.bg', e)
            try:
                channel_meta_lookup.fill_missing_logos(self.channels)
            except Exception:
                pass
            try:
                if hasattr(self, 'learned_logos'):
                    self.learned_logos.harvest(self.channels)
            except Exception as e:
                log_error('learned_logos.harvest.bg', e)
            # Round 349: os.path.exists() на КАЖДЫЙ URL — единственная
            # медленная часть pre-queue — считаем ЗДЕСЬ, в bg-нитке, а
            # не на main. См. LogoCache._prescan_missing_bg.
            to_queue = []
            try:
                if self.logo_cache is not None:
                    urls = [ch.logo_url for ch in self.channels if ch.logo_url]
                    to_queue = self.logo_cache._prescan_missing_bg(urls)
            except Exception as e:
                log_error('logo.prescan.bg', e)
            # UI часть в main.
            def _ui_after_enrich():
                try:
                    self._queue_logo_urls_chunked(to_queue)
                except Exception as e:
                    log_error('logo.prequeue', e)
                self.channels_page.set_channels(
                    self.channels, name, self.epg_data)
                self.channels_page.status_label.setText(
                    f"{len(self.channels)} channels loaded")
            self._invoke_on_main.emit(_ui_after_enrich)

        _th.Thread(target=_enrich_bg, daemon=True,
                   name='playlist-enrich').start()
        # Round 332: пре-queue + set_channels теперь живут внутри
        # _ui_after_enrich (см. _enrich_bg выше) — выполняются в main
        # ТОЛЬКО после того как bg-нитка отработала enrichment, причём
        # _invoke_on_main гарантирует доставку без QObject::startTimer
        # варнинга.
        # Кикаем загрузку iptv-org БД (no-op если уже загружена), и
        # после готовности заново применяем fill_missing_logos +
        # обновляем UI + ставим в очередь LogoCache.
        # Round 305: fill_missing_logos уходит в фоновый поток. Юзер:
        # «при первом запуске программа замирает». На 3639 каналов ×
        # 4 регекс-вызова (нормализация + fuzzy) синхронный цикл
        # давал ~3-5 сек заморозки на main thread, когда iptv-org
        # параллельно подгружался. Доступ к _by_name dict — read-only
        # после parse, мутация ch.logo_url GIL-safe; UI-апдейт
        # (pre-queue + set_channels) возвращаем в main через QTimer.
        def on_meta_ready():
            import threading as _th

            def _bg():
                # Round 306: лог размер iptv-org БД + результат
                # enrichment'а в main tvviewer.log (не только trace).
                # Юзер: «лого каналов так же нет». Так увидим, реально
                # ли БД пустая (network fail) или просто fuzzy не матчит.
                db_size = len(getattr(channel_meta_lookup, '_by_name', {}))
                logos_size = len(getattr(channel_meta_lookup,
                                         '_logos_by_id', {}))
                log_info('logo',
                         f"iptv-org ready: db_size={db_size} "
                         f"logos_size={logos_size}")
                try:
                    enriched = channel_meta_lookup.fill_missing_logos(
                        self.channels)
                except Exception as e:
                    log_error('fill_missing_logos', e)
                    enriched = 0
                log_info('logo',
                         f"iptv-org enriched {enriched}/{len(self.channels)} "
                         f"channels")
                # Round 307/308: счётчики/примеры разделены по типу
                # промаха — «вообще не нашли» vs «нашли но iptv-org
                # держит logo=null». Подробности уже логируются в trace
                # из fill_missing_logos; дублируем в tvviewer.log для
                # юзера, чтобы не открывать два файла.
                if enriched == 0 and self.channels:
                    try:
                        from epg_parser import normalize_id, fuzzy_key
                        no_match = []
                        match_no_logo = []
                        # Round 351: worst case (все каналы matched-
                        # without-logo) этот диагностический цикл шёл
                        # по ВСЕМ 4000 каналам × multi-regex lookup
                        # без yield'а — периодически уступаем GIL.
                        _dn = 0
                        for ch in self.channels:
                            _dn += 1
                            if _dn % 200 == 0:
                                time.sleep(0.001)
                            if ch.logo_url:
                                continue
                            m = channel_meta_lookup.lookup(ch.name)
                            if m is None:
                                if len(no_match) < 3:
                                    no_match.append(
                                        f"'{ch.name}'→nk='{normalize_id(ch.name)}',"
                                        f"fk='{fuzzy_key(ch.name)}'")
                            elif m.logo_url is None:
                                if len(match_no_logo) < 3:
                                    match_no_logo.append(
                                        f"'{ch.name}'→id='{m.tvg_id or ''}'")
                            if len(no_match) >= 3 and len(match_no_logo) >= 3:
                                break
                        if no_match:
                            log_info('logo',
                                     "no_match samples: " + " | ".join(no_match))
                        if match_no_logo:
                            log_info('logo',
                                     "match_no_logo samples: "
                                     + " | ".join(match_no_logo))
                    except Exception:
                        pass
                # Round 349: считаем медленный os.path.exists() здесь,
                # в bg-нитке — см. комментарий в _enrich_bg выше.
                to_queue = []
                try:
                    if self.logo_cache is not None:
                        urls = [ch.logo_url for ch in self.channels
                                if ch.logo_url]
                        to_queue = self.logo_cache._prescan_missing_bg(urls)
                except Exception:
                    pass
                # Возвращаемся в main thread для UI.
                def _ui():
                    try:
                        self._queue_logo_urls_chunked(
                            to_queue, tag='post-meta ')
                    except Exception:
                        pass
                    if enriched and hasattr(self, 'channels_page'):
                        self.channels_page.set_channels(
                            self.channels, name, self.epg_data)
                # Round 313: сигнал → main thread без QTimer-warning'а.
                self._invoke_on_main.emit(_ui)

            _th.Thread(target=_bg, daemon=True,
                       name='meta-fill').start()
        # Round 313: ensure_loaded зовёт on_loaded из своего worker'а
        # (не QThread). Через сигнал, чтобы on_meta_ready исполнилось
        # в main без warning'а.
        channel_meta_lookup.ensure_loaded(
            self.cache_dir,
            on_loaded=lambda: self._invoke_on_main.emit(on_meta_ready))

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
            # Round 308/311: восстановили автозагрузку, но с большой
            # задержкой и lazy-fallback. Юзер: «пропала тв программа,
            # нет её» — Round 308 ждал открытия вкладки TV-Гид, и до
            # тех пор EPG вообще не приходила. Возврат к авто-загрузке
            # с 60-секундным окном (VLC уже инициализирован, юзер
            # запустил первый канал), плюс ленивый триггер если юзер
            # открывает TvGuide раньше.
            # Round 317: первым делом пробуем on-disk кэш EPG. Если он
            # свежий (< 6 ч) — юзер видит ТВ-программу СРАЗУ, без
            # 60-секундного ожидания и без 30 сек парса XMLTV.
            # save/load_from_cache были в epg_parser.py, но вообще не
            # вызывались — каждый запуск качал и парсил 50 МБ XMLTV.
            # Кэш-load делаем в bg-нитке — JSON может быть 10+ МБ.
            try:
                import threading as _th
                def _load_epg_cache_bg():
                    try:
                        cached = load_epg_cache(self.cache_dir)
                        if cached:
                            log_info('epg',
                                     f"loaded {len(cached)} channels "
                                     f"from cache")
                            def _ui():
                                # Race-guard: если за время bg-load'а
                                # уже прилетели свежие данные через
                                # on_epg_loaded — не перезаписываем
                                # их кэшем.
                                if self.epg_data:
                                    log_info('epg',
                                             "cache load skipped: fresh "
                                             "data already present")
                                    return
                                self.epg_data = cached
                                self.channels_page.set_epg(cached)
                                if hasattr(self, 'tv_guide_page'):
                                    self.tv_guide_page.set_data(
                                        self.channels, cached)
                                # Round 341: та же синхронизация с
                                # PlayerPage.epg_data что и в
                                # on_epg_loaded — см. комментарий там.
                                try:
                                    self.player_page.epg_data = cached
                                    if (hasattr(self.player_page, 'channels_overlay')
                                            and self.player_page.channels_overlay.isVisible()):
                                        self.player_page._refresh_channels_overlay()
                                    if (hasattr(self.player_page, 'center_menu_overlay')
                                            and self.player_page.center_menu_overlay.isVisible()):
                                        self.player_page._update_center_menu_epg()
                                except Exception as e:
                                    log_error('epg.cache_load.player_page', e)
                            self._invoke_on_main.emit(_ui)
                    except Exception as e:
                        log_error('epg.load_from_cache.bg', e)
                _th.Thread(target=_load_epg_cache_bg, daemon=True,
                           name='epg-cache-load').start()
            except Exception as e:
                log_error('epg.load_from_cache', e)
            self._pending_epg_sources = list(epg_sources)
            log_info('epg',
                     f"scheduled load: {len(epg_sources)} sources in 60s "
                     f"(or sooner if TvGuide tab is opened)")
            QTimer.singleShot(60000, self._fire_deferred_epg)

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
            # Round 311: всегда обновляем TvGuide, даже если юзер сейчас
            # не на этой вкладке. Раньше при открытии TvGuide пока EPG
            # ещё грузилась, юзер видел «▶ —» в каждой строке, потом
            # уходил на другую вкладку, а когда возвращался — данных
            # всё ещё не было пока не свитчнется заново.
            try:
                self.tv_guide_page.set_data(self.channels, self.epg_data)
            except Exception as e:
                log_error('on_epg_loaded.tvguide', e)
            # Round 341: PlayerPage.epg_data — ОТДЕЛЬНАЯ копия, которую
            # play_channel() снимает ОДНОКРАТНО в момент запуска канала
            # и больше никогда не трогает. Юзер: «появилась программа
            # во вкладке тв гид но в списке нет» — EPG грузится через
            # 60с ПОСЛЕ старта первого канала (Round 311, специально
            # чтобы не тормозить старт), так что оверлей списка каналов
            # (LEFT-стрелка) весь сеанс смотрел на epg_data={} с
            # момента play_channel(), пока юзер сам не переключит
            # канал. Синхронизируем и, если оверлей сейчас открыт,
            # перерисовываем его немедленно.
            try:
                self.player_page.epg_data = self.epg_data
                if (hasattr(self.player_page, 'channels_overlay')
                        and self.player_page.channels_overlay.isVisible()):
                    self.player_page._refresh_channels_overlay()
                if (hasattr(self.player_page, 'center_menu_overlay')
                        and self.player_page.center_menu_overlay.isVisible()):
                    self.player_page._update_center_menu_epg()
            except Exception as e:
                log_error('on_epg_loaded.player_page', e)
            # Round 317: сохраняем в кэш в bg-нитке. json.dump на 5942
            # каналов с программами — это 5-50 МБ JSON, может занять
            # секунду на медленном диске. Не хотим вешать main.
            try:
                import threading as _th
                _th.Thread(
                    target=lambda: save_epg_cache(data, self.cache_dir),
                    daemon=True, name='epg-cache-save').start()
            except Exception as e:
                log_error('on_epg_loaded.cache_save', e)

    def _run_on_main(self, fn):
        """Round 313: слот для _invoke_on_main на MainWindow."""
        try:
            if callable(fn):
                fn()
        except Exception as e:
            log_error('MainWindow._run_on_main', e)

    def _fire_deferred_epg(self, reason: str = "timer"):
        """Round 311: одноразовый трамплин для отложенной EPG-загрузки.
        Вызывается из 60-секундного QTimer'а или из switch_page(5) — в
        обоих случаях стартует только один раз благодаря None-флагу."""
        try:
            pending = getattr(self, '_pending_epg_sources', None)
            if not pending:
                return
            self._pending_epg_sources = None
            log_info('epg', f"deferred load firing ({reason}): "
                            f"{len(pending)} sources")
            self.load_epg(pending)
        except Exception as e:
            log_error('_fire_deferred_epg', e)

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
            # авто-триггер: пропускаем если юзер сейчас в плеере.
            # Round 351: раньше тут был cur.player.is_playing() —
            # нативный libvlc-вызов синхронно на main thread, без
            # lock'а. Ирония: проверка, существующая чтобы НЕ мешать
            # просмотру, сама могла заморозить UI на мёртвом стриме.
            # «Открыт плеер + player инициализирован» — достаточная
            # эвристика без единого нативного вызова; авто-триггер
            # всё равно повторится следующим тиком, а ручная кнопка ↻
            # работает всегда.
            try:
                cur = self.stack.currentWidget()
                if isinstance(cur, PlayerPage) and getattr(cur, 'player', None):
                    log_info('epg-refresh',
                             'skipped: player page active, will retry next tick')
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
        # Round 337: раньше вход в плеер шёл в обход switch_page
        # (прямой setCurrentIndex(3)), а выход (show_channels →
        # switch_page(1)) — ЧЕРЕЗ switch_page, которая пушит текущую
        # страницу (в тот момент это как раз плеер, idx=3) в
        # _nav_history. Получалась асимметрия: вход не трекался, а
        # выход трекался — «висящая» запись 3 в истории потом
        # неожиданно перебрасывала юзера обратно в плеер по
        # Backspace с совершенно другой вкладки. switch_page(3)
        # делает и то и другое симметрично: push при входе, и
        # nav_bar/shortcut_bar/update_nav_highlight внутри неё уже
        # корректно обрабатывают idx==3 (никакая nav-кнопка не
        # маппится на страницу 3, так что update_nav_highlight(3)
        # эквивалентно прежнему update_nav_highlight(-1) — ни одна
        # кнопка не подсвечивается).
        self.switch_page(3)
        # Round 350: юзер — «канал запускается но не показывает а звук
        # идет» (чаще всего на самом первом воспроизведении за сессию,
        # например автозапуск последнего канала при старте). switch_page(3)
        # только ПЛАНИРУЕТ показ player_page у QStackedWidget — реальный
        # map/paint видео-окна происходит когда Qt обработает event loop.
        # play_channel ниже почти сразу стартует VLC-рендер в HWND
        # video_frame (в bg-нитке _swap) — если событие показа ещё не
        # успело домаппить окно на экран, VLC начинает рендерить «в
        # пустоту»: звук слышен (декодирование не зависит от видимости
        # окна), а картинки нет, пока не прилетит следующий repaint.
        # Прокачиваем event loop несколько раз ДО старта плеера, чтобы
        # video_frame гарантированно был на экране когда VLC привяжется
        # к его HWND.
        for _ in range(3):
            QApplication.processEvents()
        self.player_page.play_channel(index, self.channels, self.epg_data)
        # Apply remembered fullscreen preference
        if self.config.remember_fullscreen and not self.isFullScreen():
            self.showFullScreen()
        # Start sleep timer if configured
        if self.config.sleep_timer_minutes > 0:
            self.player_page._start_sleep_timer(self.config.sleep_timer_minutes)

    def show_channels(self):
        # Round 330: тоже не стопаем — пусть играет.
        # Round 326: fullscreen сохраняем при выходе из плеера тоже.
        # nav_bar появится автоматически (см. switch_page).
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
        QMessageBox.information(self, t('app_name'), t('no_playlist_yet'))
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
            # Round 306: юзер: «почему при открытии программы не
            # открывается первым окно Главная?». auto_load_last всегда
            # дёргал load_playlist → switch_page(1), поэтому Home
            # мелькала и тут же сменялась Channels. Грузим плейлист в
            # фоне, но СТРАНИЦУ не переключаем — пусть юзер сам
            # кликает «Каналы» когда захочет.
            self.load_playlist(name or "Playlist", url,
                               switch_to_channels=False)

    def _on_settings_changed(self):
        # Round 351: дебаунс 300мс. settings_changed эмитится на КАЖДЫЙ
        # шаг спинбокса громкости / смену комбо — а этот слот делает
        # полный filter_channels (проход по 4000 каналов) + retranslate-
        # свип по всем виджетам приложения. Зажатая стрелка спинбокса =
        # серия 100мс+ столлов. Коалесцируем: применяем один раз после
        # 300мс тишины.
        if not hasattr(self, '_settings_changed_timer'):
            self._settings_changed_timer = QTimer(self)
            self._settings_changed_timer.setSingleShot(True)
            self._settings_changed_timer.setInterval(300)
            self._settings_changed_timer.timeout.connect(
                self._apply_settings_changed)
        self._settings_changed_timer.start()

    def _apply_settings_changed(self):
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
        # Round 337: generic sweep for MainWindow-level tagged widgets
        # that live directly on the window (not inside any sub-page) —
        # e.g. `_btn_back`, which was hardcoded "← Назад" and never
        # updated by language changes because _update_nav_labels only
        # walks self.nav_buttons and no page-level retranslate_ui() has
        # access to it.
        try:
            _retranslate_widgets(self)
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
        # Round 337: раньше здесь спавнилась bg-нитка release_vlc() И
        # ОТДЕЛЬНО, синхронно, звался player_page.stop() (который сам
        # тоже спавнил свою bg-нитку на p.stop()) — то есть ДВЕ
        # независимые нитки трогали один и тот же self.player без
        # координации между собой, и .stop()/.release() могли
        # выполниться конкурентно на закрываемом libvlc-объекте.
        # release_vlc() теперь сам вызывает stop() ПЕРЕД release()
        # внутри своей же нитки — здесь зовём только его. Per-channel
        # состояние (позиция/громкость) всё ещё сохраняется —
        # _save_current_channel_state() читает VLC синхронно быстро
        # (get_time/get_length/audio_get_track — не блокирующие вызовы)
        # и сама уходит в свою daemon-нитку для записи на диск.
        self.player_page._save_current_channel_state()
        try:
            import threading as _th
            _th.Thread(target=self.player_page.release_vlc,
                       daemon=True, name='vlc-release').start()
        except Exception as e:
            log_error('closeEvent.release', e)
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
                btn_report = msg.addButton("Сообщить о проблеме", QMessageBox.AcceptRole)
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


def _acquire_single_instance():
    """Round 363 (Windows): запрет второго экземпляра. Юзер: «открылись
    две программы» — авто-обновление перезапускало приложение, а старый
    процесс не успевал закрыться (или юзер запускал вручную во время
    долгого обновления), и оказывалось ДВА окна с разными каналами.

    Именованный Win32-мьютекс: если он уже существует (программа
    запущена) — пытаемся вынести существующее окно на передний план и
    сообщаем, что нужно выйти. Возвращает (mutex_handle, already_running).
    mutex_handle держим живым весь сеанс (не даём GC закрыть его).
    На не-Windows — no-op."""
    if sys.platform != 'win32':
        return None, False
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.windll.kernel32
        user32 = ctypes.windll.user32
        ERROR_ALREADY_EXISTS = 183
        name = "TVViewer_SingleInstance_Mutex_donmax76"
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        handle = kernel32.CreateMutexW(None, wintypes.BOOL(True),
                                       ctypes.c_wchar_p(name))
        already = (kernel32.GetLastError() == ERROR_ALREADY_EXISTS)
        if already:
            # Выносим уже запущенное окно на передний план. Заголовок
            # приложения — "M3U IPTV" или "M3U IPTV - TVViewer".
            try:
                hwnd = 0
                for title in ("M3U IPTV", "M3U IPTV - TVViewer"):
                    hwnd = user32.FindWindowW(None, ctypes.c_wchar_p(title))
                    if hwnd:
                        break
                if hwnd:
                    SW_RESTORE = 9
                    user32.ShowWindow(hwnd, SW_RESTORE)
                    user32.SetForegroundWindow(hwnd)
            except Exception:
                pass
        return handle, already
    except Exception as e:
        log_error('single_instance', e)
        return None, False


def main():
    # Round 363: единственный экземпляр. Второй запуск — выходим сразу,
    # ДО создания QApplication/окна, чтобы не появлялось второе окно.
    _si_handle, _si_running = _acquire_single_instance()
    if _si_running:
        log_info('app', "second instance detected — exiting")
        try:
            os._exit(0)
        except Exception:
            return
    # Держим ссылку на мьютекс живой весь сеанс.
    globals()['_SINGLE_INSTANCE_HANDLE'] = _si_handle

    app = QApplication(sys.argv)
    app.setFont(QFont('Segoe UI', 12))
    # Round 291: forcing cursor blink ON — на Windows Tool-окнах
    # (overlay_host) каретка иногда не мигала. Юзер: «в списках
    # каналов нет видимого курсора в поле поиска».
    app.setCursorFlashTime(530)
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
        # Round 316: фильтр шумных libpng-варнингов из лого с битым ICC.
        # Юзер: «libpng warning: iCCP: known incorrect sRGB profile —
        # что это?». PNG'и с iptv-org/api/logos.json сделаны старым
        # Photoshop'ом и несут битый sRGB chunk — картинка рисуется
        # нормально, но libpng печатает варнинг через qWarning. Глушим
        # только этот класс сообщений, остальные Qt-варнинги (включая
        # любые наши собственные) пишем как раньше.
        _QT_NOISE_SUBSTRINGS = (
            'libpng warning: iCCP',
        )
        def _qt_msg_handler(mode, ctx, message):
            try:
                if mode == QtMsgType.QtWarningMsg and any(
                        s in message for s in _QT_NOISE_SUBSTRINGS):
                    return
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
    set_ui_language(getattr(_bootstrap_cfg, 'ui_language', 'en'))
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

    window = MainWindow(progress_cb=_progress, config=_bootstrap_cfg)
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
