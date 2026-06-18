"""M3U playlist parser - matches Android PlaylistRepository logic."""

import re
import requests
from dataclasses import dataclass, field
from typing import List, Optional


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

    for line in lines:
        if line.startswith('#EXTINF:'):
            # Parse EXTINF line
            # Format: #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Channel Name
            info = line[8:]  # Remove #EXTINF:

            # Extract tvg-id
            m = re.search(r'tvg-id="([^"]*)"', info)
            current_tvg_id = m.group(1) if m else ""

            # Extract tvg-name
            m = re.search(r'tvg-name="([^"]*)"', info)
            current_tvg_name = m.group(1) if m else ""

            # Extract tvg-logo
            m = re.search(r'tvg-logo="([^"]*)"', info)
            current_logo = m.group(1) if m else ""

            # Extract group-title
            m = re.search(r'group-title="([^"]*)"', info)
            current_group = m.group(1) if m else ""

            # Extract channel name (after last comma)
            comma_idx = info.rfind(',')
            if comma_idx >= 0:
                current_name = info[comma_idx + 1:].strip()
            else:
                current_name = info.strip()

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


def fetch_playlist(url: str, timeout: int = 30) -> PlaylistResult:
    """Fetch and parse an M3U playlist from a URL.

    Round 282: `response.text` использует encoding из заголовков, и
    если сервер не присылает charset (как ucoz.ru), requests дефолтит
    к ISO-8859-1, в результате group-title="кино" приходит мусором и
    категории «не показываются». Сначала пробуем UTF-8 на байтах, и
    только если он рушится — отдаём requests парсить заголовки.
    """
    headers = {
        'User-Agent': 'TVViewer/5.3 (Windows Desktop)',
    }
    with requests.get(url, headers=headers, timeout=timeout, allow_redirects=True) as response:
        response.raise_for_status()
        raw = response.content
        # utf-8-sig: подъедает BOM (\xef\xbb\xbf) если он есть.
        content = None
        for enc in ('utf-8-sig', 'utf-8', 'cp1251', 'latin-1'):
            try:
                content = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if content is None:
            content = response.text
    return parse_m3u(content)


def load_playlist_file(filepath: str) -> PlaylistResult:
    """Load and parse an M3U playlist from a local file.

    Round 282: пробуем UTF-8 без `errors='ignore'` — иначе кириллица
    из cp1251-файлов молча терялась как «¤Õé¦®¬Ñð¦Ñ» и категории не
    распознавались. Если UTF-8 не подходит — fallback на cp1251."""
    with open(filepath, 'rb') as f:
        raw = f.read()
    for enc in ('utf-8', 'cp1251', 'latin-1'):
        try:
            return parse_m3u(raw.decode(enc))
        except UnicodeDecodeError:
            continue
    # Безнадёжный fallback — пропускаем плохие байты.
    return parse_m3u(raw.decode('utf-8', errors='ignore'))
