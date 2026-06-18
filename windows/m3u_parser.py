"""M3U playlist parser - matches Android PlaylistRepository logic."""

import re
import requests
from dataclasses import dataclass, field
from typing import List, Optional
from urllib.parse import urlparse

# Round 287: SSL verify=False для IPTV-CDN'ов с самоподписанными
# сертификатами. Глушим urllib3 InsecureRequestWarning чтобы не
# спамить stderr.
try:
    from urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except Exception:
    pass


@dataclass
class Channel:
    name: str
    url: str
    group: str = ""
    logo_url: str = ""
    tvg_id: str = ""
    tvg_name: str = ""


@dataclass
class PlaylistResult:
    channels: List[Channel] = field(default_factory=list)
    epg_url: Optional[str] = None


def parse_m3u(content: str) -> PlaylistResult:
    """Parse M3U/M3U8 content into a list of channels."""
    result = PlaylistResult()
    lines = content.strip().split('\n')
    lines = [l.strip() for l in lines if l.strip()]

    if not lines:
        return result

    # Check for EPG URL in header
    if lines[0].startswith('#EXTM3U'):
        header = lines[0]
        epg_match = re.search(r'url-tvg="([^"]*)"', header)
        if epg_match:
            result.epg_url = epg_match.group(1)
        if not result.epg_url:
            epg_match = re.search(r'x-tvg-url="([^"]*)"', header)
            if epg_match:
                result.epg_url = epg_match.group(1)

    current_name = ""
    current_group = ""
    current_logo = ""
    current_tvg_id = ""
    current_tvg_name = ""

    # Round 287: case-insensitive поиск атрибутов с поддержкой
    # одинарных/двойных кавычек и без кавычек — некоторые плейлисты
    # пишут tvg-id='...' или tvg-id=... без кавычек.
    def _attr(info: str, name: str) -> str:
        # Двойные кавычки
        m = re.search(rf'{name}="([^"]*)"', info, re.IGNORECASE)
        if m: return m.group(1)
        # Одинарные кавычки
        m = re.search(rf"{name}='([^']*)'", info, re.IGNORECASE)
        if m: return m.group(1)
        # Без кавычек до пробела или запятой
        m = re.search(rf'{name}=([^\s,"]+)', info, re.IGNORECASE)
        if m: return m.group(1)
        return ""

    for line in lines:
        if line.startswith('#EXTINF:'):
            # Parse EXTINF line
            # Format: #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Channel Name
            info = line[8:]  # Remove #EXTINF:

            current_tvg_id = _attr(info, 'tvg-id') or _attr(info, 'channel-id')
            current_tvg_name = _attr(info, 'tvg-name')
            current_logo = _attr(info, 'tvg-logo') or _attr(info, 'logo')
            current_group = (_attr(info, 'group-title')
                             or _attr(info, 'tvg-group')
                             or _attr(info, 'group')
                             or current_group)  # сохраняем из #EXTGRP

            # Extract channel name (after last comma)
            comma_idx = info.rfind(',')
            if comma_idx >= 0:
                current_name = info[comma_idx + 1:].strip()
            else:
                current_name = info.strip()
            # Fallback на tvg-name если после запятой пусто
            if not current_name:
                current_name = current_tvg_name

        elif line.upper().startswith('#EXTGRP:'):
            # Round 287: отдельная строка с категорией — Android тоже
            # это парсит. Применяется к следующему каналу.
            current_group = line[8:].strip()
        elif line.upper().startswith('#EXTLOGO:'):
            current_logo = line[9:].strip()
        elif line.startswith('#'):
            continue
        elif line.startswith(('http://', 'https://', 'rtsp://', 'rtmp://', 'mms://')):
            if current_name:
                channel = Channel(
                    name=current_name,
                    url=line,
                    group=current_group,
                    logo_url=current_logo,
                    tvg_id=current_tvg_id or current_tvg_name or current_name,
                    tvg_name=current_tvg_name,
                )
                result.channels.append(channel)
            current_name = ""
            current_group = ""
            current_logo = ""
            current_tvg_id = ""
            current_tvg_name = ""

    return result


def _best_decode(raw: bytes) -> str:
    """Round 287: portируем Android PlaylistRepository.decodePlaylistBytes.
    Пробуем все кодировки, выбираем ту, где меньше всего '?'-replacement.
    Это лучше чем «первый успешный strict decode», потому что некоторые
    байтовые последовательности валидны во ВСЕХ кодировках, но
    осмысленный текст даёт только одна из них."""
    candidates = [
        ('utf-8-sig', 'strict'),
        ('utf-8', 'strict'),
        ('cp1251', 'replace'),
        ('koi8-r', 'replace'),
        ('cp1252', 'replace'),
        ('utf-8', 'replace'),
    ]
    best_text = None
    best_score = float('inf')
    for enc, errmode in candidates:
        try:
            text = raw.decode(enc, errors=errmode)
        except (UnicodeDecodeError, LookupError):
            continue
        # Считаем replacement-символы + сильно неестественные паттерны.
        bad = text.count('�') + text.count('?')
        # Бонус для UTF-8 без replacement — идеально.
        if errmode == 'strict':
            bad -= 1000
        if bad < best_score:
            best_score = bad
            best_text = text
            if errmode == 'strict' and bad < 0:
                break  # идеальный UTF-8 — дальше пробовать незачем
    return best_text or raw.decode('utf-8', errors='ignore')


def fetch_playlist(url: str, timeout: int = 30) -> PlaylistResult:
    """Fetch and parse an M3U playlist from a URL.

    Round 287: позаимствовали у Android — SSL отключён для IPTV-CDN'ов
    с самоподписанными сертификатами (streamlock.net и пр.), декодинг
    выбирает кодировку с наименьшим числом replacement-символов."""
    headers = {
        'User-Agent': 'TVViewer/5.4 (Windows Desktop)',
        'Accept': '*/*',
    }
    # SSL verify False — много IPTV-CDN'ов используют просроченные /
    # самоподписанные сертификаты. Android PlaylistRepository делает
    # ровно то же — без этого половина плейлистов не открывается.
    with requests.get(url, headers=headers, timeout=timeout,
                      allow_redirects=True, verify=False) as response:
        response.raise_for_status()
        content = _best_decode(response.content)
    return parse_m3u(content)


def load_playlist_file(filepath: str) -> PlaylistResult:
    """Round 282/287: единая логика декодинга с fetch_playlist."""
    with open(filepath, 'rb') as f:
        raw = f.read()
    return parse_m3u(_best_decode(raw))
