"""
TVViewer - Windows Desktop IPTV Player
Matches Android app design and functionality.
Requires: PyQt5, python-vlc, requests
Install VLC media player for playback.
"""

import sys
import os
import json
import time
import threading
from datetime import datetime
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QLineEdit, QListWidget, QListWidgetItem,
    QStackedWidget, QFrame, QProgressBar, QSplitter, QFileDialog,
    QDialog, QDialogButtonBox, QFormLayout, QMessageBox, QScrollArea,
    QComboBox, QSlider, QToolBar, QAction, QSizePolicy, QAbstractItemView
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QThread, QSize, QUrl
from PyQt5.QtGui import QFont, QColor, QPalette, QIcon, QPixmap, QKeySequence

try:
    import vlc
    HAS_VLC = True
except ImportError:
    HAS_VLC = False

from m3u_parser import fetch_playlist, load_playlist_file, Channel, PlaylistResult
from epg_parser import fetch_epg, get_now_next, get_current_progress, EpgData

# --- Colors matching Android dark theme ---
COLORS = {
    'background': '#0F0F1A',
    'surface': '#1A1A2E',
    'card': '#222240',
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
        self.volume = 80
        self.load()

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
                self.volume = data.get('volume', 80)
            except Exception:
                pass

    def save(self):
        data = {
            'playlists': self.playlists,
            'favorites': list(self.favorites),
            'last_playlist_url': self.last_playlist_url,
            'last_playlist_name': self.last_playlist_name,
            'last_epg_url': self.last_epg_url,
            'volume': self.volume,
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


class LoadEpgThread(QThread):
    """Background thread for loading EPG data."""
    finished = pyqtSignal(object)

    def __init__(self, url):
        super().__init__()
        self.url = url

    def run(self):
        try:
            data = fetch_epg(self.url)
            self.finished.emit(data)
        except Exception:
            self.finished.emit({})


# ============================================================
# Playlists Page
# ============================================================
class PlaylistsPage(QWidget):
    playlist_selected = pyqtSignal(str, str)  # name, url

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.init_ui()

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        title = QLabel("M3U IPTV")
        title.setFont(QFont('Segoe UI', 24, QFont.Bold))
        layout.addWidget(title)

        subtitle = QLabel("Select a playlist")
        subtitle.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 14px;")
        layout.addWidget(subtitle)
        layout.addSpacing(12)

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

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.channels = []
        self.filtered = []
        self.categories = []
        self.selected_category = "All"
        self.epg_data = {}
        self.init_ui()

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)

        header = QHBoxLayout()
        self.title_label = QLabel("Channels")
        self.title_label.setFont(QFont('Segoe UI', 22, QFont.Bold))
        header.addWidget(self.title_label)
        header.addStretch()
        self.count_label = QLabel("")
        self.count_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        header.addWidget(self.count_label)
        layout.addLayout(header)

        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search channels...")
        self.search_edit.textChanged.connect(self.filter_channels)
        layout.addWidget(self.search_edit)
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
        layout.addSpacing(8)

        # Channel list
        self.channel_list = QListWidget()
        self.channel_list.setSpacing(2)
        self.channel_list.itemDoubleClicked.connect(self.on_channel_click)
        self.channel_list.setSelectionMode(QAbstractItemView.SingleSelection)
        layout.addWidget(self.channel_list)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet(f"color: {COLORS['text_hint']}; font-size: 12px;")
        layout.addWidget(self.status_label)

    def set_channels(self, channels, name="", epg_data=None):
        self.channels = channels
        if epg_data:
            self.epg_data = epg_data
        self.title_label.setText(name or "Channels")
        cats = sorted(set(ch.group for ch in channels if ch.group))
        self.categories = ["All"] + cats
        self.selected_category = "All"
        self.rebuild_categories()
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
        self.rebuild_categories()
        self.filter_channels()

    def filter_channels(self):
        query = self.search_edit.text().strip().lower()
        self.filtered = []
        for ch in self.channels:
            if self.selected_category != "All" and ch.group != self.selected_category:
                continue
            if query and query not in ch.name.lower():
                continue
            self.filtered.append(ch)

        self.channel_list.clear()
        for i, ch in enumerate(self.filtered):
            now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id)
            epg_text = ""
            if now_prog:
                t = datetime.fromtimestamp(now_prog.start).strftime('%H:%M')
                epg_text = f"  {t} {now_prog.title}"
            fav = " ♥" if ch.url in self.config.favorites else ""
            group = f" [{ch.group}]" if ch.group else ""
            text = f"{i+1}. {ch.name}{fav}{group}{epg_text}"
            item = QListWidgetItem(text)
            item.setData(Qt.UserRole, self.channels.index(ch))
            self.channel_list.addItem(item)

        self.count_label.setText(f"{len(self.filtered)} channels")

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

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.channels = []
        self.fav_channels = []
        self.epg_data = {}
        self.init_ui()

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
        self.fav_list.itemDoubleClicked.connect(self.on_click)
        layout.addWidget(self.fav_list)

    def refresh(self, channels, epg_data):
        self.channels = channels
        self.epg_data = epg_data
        self.fav_channels = [ch for ch in channels if ch.url in self.config.favorites]
        self.fav_list.clear()
        for ch in self.fav_channels:
            now_prog, _ = get_now_next(epg_data, ch.tvg_id)
            epg = f"  {now_prog.title}" if now_prog else ""
            item = QListWidgetItem(f"♥ {ch.name}{epg}")
            item.setData(Qt.UserRole, channels.index(ch))
            self.fav_list.addItem(item)
        self.count_label.setText(f"{len(self.fav_channels)} favorites")

    def on_click(self, item):
        idx = item.data(Qt.UserRole)
        if idx is not None:
            self.channel_play.emit(idx)


# ============================================================
# Player Page
# ============================================================
class PlayerPage(QWidget):
    back_requested = pyqtSignal()

    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.channels = []
        self.current_index = 0
        self.epg_data = {}
        self.vlc_instance = None
        self.player = None
        self.init_ui()
        self.init_vlc()

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

        # Video frame
        self.video_frame = QFrame()
        self.video_frame.setStyleSheet("background-color: black;")
        self.video_frame.setMinimumHeight(400)
        self.video_frame.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        layout.addWidget(self.video_frame)

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

        ctrl.addStretch()
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

    def init_vlc(self):
        if not HAS_VLC:
            return
        try:
            self.vlc_instance = vlc.Instance('--no-xlib')
            self.player = self.vlc_instance.media_player_new()
        except Exception:
            self.vlc_instance = None
            self.player = None

    def play_channel(self, index, channels, epg_data):
        self.channels = channels
        self.current_index = index
        self.epg_data = epg_data
        ch = channels[index]
        self.channel_name_label.setText(ch.name)
        self.channel_number_label.setText(f"{index + 1} / {len(channels)}")
        self.update_fav_btn()
        self.update_epg_display()
        self.play_url(ch.url)

    def play_url(self, url):
        if not self.player:
            self.epg_bar.setText("VLC not installed. Install VLC and python-vlc.")
            return
        media = self.vlc_instance.media_new(url)
        media.add_option(':network-caching=3000')
        self.player.set_media(media)
        if sys.platform == "win32":
            self.player.set_hwnd(int(self.video_frame.winId()))
        elif sys.platform == "linux":
            self.player.set_xwindow(int(self.video_frame.winId()))
        elif sys.platform == "darwin":
            self.player.set_nsobject(int(self.video_frame.winId()))
        self.player.audio_set_volume(self.config.volume)
        self.player.play()
        self.btn_play.setText("Pause")

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
        self.current_index = (self.current_index + direction) % len(self.channels)
        self.play_channel(self.current_index, self.channels, self.epg_data)

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
        now_prog, next_prog = get_now_next(self.epg_data, ch.tvg_id)
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

    def stop(self):
        if self.player:
            self.player.stop()

    def keyPressEvent(self, event):
        key = event.key()
        if key == Qt.Key_Space:
            self.toggle_play()
        elif key == Qt.Key_Up:
            self.switch_channel(-1)
        elif key == Qt.Key_Down:
            self.switch_channel(1)
        elif key == Qt.Key_Escape:
            self.back_requested.emit()
        elif key == Qt.Key_F:
            self.toggle_favorite()
        elif key == Qt.Key_Plus or key == Qt.Key_VolumeUp:
            self.vol_slider.setValue(min(100, self.vol_slider.value() + 5))
        elif key == Qt.Key_Minus or key == Qt.Key_VolumeDown:
            self.vol_slider.setValue(max(0, self.vol_slider.value() - 5))
        else:
            super().keyPressEvent(event)


# ============================================================
# Settings Page
# ============================================================
class SettingsPage(QWidget):
    def __init__(self, config: Config):
        super().__init__()
        self.config = config
        self.init_ui()

    def init_ui(self):
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        container = QWidget()
        layout = QVBoxLayout(container)
        layout.setContentsMargins(16, 16, 16, 16)

        title = QLabel("Settings")
        title.setFont(QFont('Segoe UI', 22, QFont.Bold))
        layout.addWidget(title)
        layout.addSpacing(16)

        # VLC status
        vlc_status = "VLC: Installed" if HAS_VLC else "VLC: Not found - install VLC and python-vlc"
        vlc_label = QLabel(vlc_status)
        vlc_label.setStyleSheet(
            f"color: {'#4ECDC4' if HAS_VLC else COLORS['error']}; font-size: 14px;")
        layout.addWidget(vlc_label)
        layout.addSpacing(12)

        # Version
        ver_label = QLabel("TVViewer v5.3 (Windows Desktop)")
        ver_label.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 14px;")
        layout.addWidget(ver_label)
        layout.addSpacing(8)

        info = QLabel(
            "Keyboard shortcuts (Player):\n"
            "  Space - Play/Pause\n"
            "  Up/Down - Switch channels\n"
            "  +/- - Volume\n"
            "  F - Toggle favorite\n"
            "  Esc - Back to channels\n\n"
            "Double-click a channel or playlist to open it."
        )
        info.setStyleSheet(f"color: {COLORS['text_secondary']}; font-size: 13px;")
        info.setWordWrap(True)
        layout.addWidget(info)

        layout.addStretch()
        scroll.setWidget(container)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(scroll)


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
        self.setWindowTitle("M3U IPTV - TVViewer")
        self.setMinimumSize(900, 600)
        self.resize(1100, 700)
        self.init_ui()
        self.auto_load_last()

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

        self.channels_page = ChannelsPage(self.config)
        self.channels_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.channels_page)

        self.favorites_page = FavoritesPage(self.config)
        self.favorites_page.channel_play.connect(self.play_channel)
        self.stack.addWidget(self.favorites_page)

        self.player_page = PlayerPage(self.config)
        self.player_page.back_requested.connect(self.show_channels)
        self.stack.addWidget(self.player_page)

        self.settings_page = SettingsPage(self.config)
        self.stack.addWidget(self.settings_page)

        main_layout.addWidget(self.stack, 1)

        # Bottom navigation bar
        nav_bar = QWidget()
        nav_bar.setStyleSheet(f"background-color: {COLORS['surface']};")
        nav_bar.setFixedHeight(52)
        nav_layout = QHBoxLayout(nav_bar)
        nav_layout.setContentsMargins(0, 0, 0, 0)
        nav_layout.setSpacing(0)

        self.nav_buttons = []
        nav_items = [
            ("Playlists", 0),
            ("Channels", 1),
            ("Favorites", 2),
            ("Settings", 4),
        ]
        for label, page_idx in nav_items:
            btn = QPushButton(label)
            btn.setObjectName("navBtn")
            btn.clicked.connect(lambda checked, idx=page_idx: self.switch_page(idx))
            nav_layout.addWidget(btn)
            self.nav_buttons.append((btn, page_idx))

        main_layout.addWidget(nav_bar)
        self.update_nav_highlight(0)

    def switch_page(self, idx):
        if idx == 2:
            self.favorites_page.refresh(self.channels, self.epg_data)
        self.player_page.stop()
        self.stack.setCurrentIndex(idx)
        self.update_nav_highlight(idx)

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
        self.channels_page.set_channels(self.channels, name, self.epg_data)
        self.channels_page.status_label.setText(f"{len(self.channels)} channels loaded")

        if result.epg_url:
            self.config.last_epg_url = result.epg_url
            self.config.save()
            self.load_epg(result.epg_url)
        elif self.config.last_epg_url:
            self.load_epg(self.config.last_epg_url)

    def on_playlist_error(self, error: str):
        self.channels_page.status_label.setText(f"Error: {error}")

    def load_epg(self, url):
        self.epg_thread = LoadEpgThread(url)
        self.epg_thread.finished.connect(self.on_epg_loaded)
        self.epg_thread.start()

    def on_epg_loaded(self, data):
        if data:
            self.epg_data = data
            self.channels_page.set_epg(data)

    def play_channel(self, index):
        if index < 0 or index >= len(self.channels):
            return
        self.stack.setCurrentIndex(3)
        self.update_nav_highlight(-1)
        self.player_page.play_channel(index, self.channels, self.epg_data)

    def show_channels(self):
        self.player_page.stop()
        self.switch_page(1)

    def auto_load_last(self):
        url = self.config.last_playlist_url
        name = self.config.last_playlist_name
        if url:
            self.load_playlist(name or "Playlist", url)

    def closeEvent(self, event):
        self.player_page.stop()
        self.config.save()
        event.accept()


def main():
    app = QApplication(sys.argv)
    app.setStyleSheet(STYLESHEET)
    app.setFont(QFont('Segoe UI', 12))
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
