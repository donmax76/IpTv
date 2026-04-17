"""XMLTV EPG parser - matches Android EpgRepository logic."""

import re
import gzip
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple
from datetime import datetime, timezone, timedelta
import requests
import json
import os
import time


@dataclass
class Programme:
    start: float  # timestamp in seconds
    end: float
    title: str
    description: str = ""


EpgData = Dict[str, List[Programme]]

CACHE_FILE = "epg_cache.json"
CACHE_LIFETIME = 6 * 3600  # 6 hours


def normalize_id(tvg_id: str) -> str:
    """Normalize a TVG ID for matching."""
    return re.sub(r'[^a-z0-9]', '', tvg_id.lower())


def parse_xmltv_time(time_str: str) -> float:
    """Parse XMLTV timestamp like '20240101120000 +0300' to epoch seconds."""
    time_str = time_str.strip()
    # Try with timezone offset
    m = re.match(r'(\d{14})\s*([+-]\d{4})?', time_str)
    if m:
        dt_str = m.group(1)
        tz_str = m.group(2)
        dt = datetime.strptime(dt_str, '%Y%m%d%H%M%S')
        if tz_str:
            sign = 1 if tz_str[0] == '+' else -1
            hours = int(tz_str[1:3])
            minutes = int(tz_str[3:5])
            offset = timedelta(hours=hours, minutes=minutes) * sign
            dt = dt.replace(tzinfo=timezone(offset))
        else:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    return 0.0


def fetch_epg(epg_url: str, cache_dir: str = ".") -> EpgData:
    """Fetch and parse EPG data from URL, with caching."""
    # Try loading from cache first
    cached = load_from_cache(cache_dir)
    if cached:
        return cached

    if not epg_url:
        return {}

    headers = {
        'User-Agent': 'TVViewer/5.3 (Windows Desktop)',
        'Accept-Encoding': 'gzip',
    }

    with requests.get(epg_url, headers=headers, timeout=120,
                      allow_redirects=True, stream=True) as response:
        response.raise_for_status()
        content = response.content

    # Check if gzipped
    if content[:2] == b'\x1f\x8b':
        content = gzip.decompress(content)

    xml_text = content.decode('utf-8', errors='ignore')
    epg_data = parse_xmltv(xml_text)

    # Save to cache
    save_to_cache(epg_data, cache_dir)

    return epg_data


def parse_xmltv(xml_text: str) -> EpgData:
    """Parse XMLTV format into EpgData dict."""
    epg: EpgData = {}

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return epg

    for prog_elem in root.findall('.//programme'):
        channel_id = prog_elem.get('channel', '')
        start_str = prog_elem.get('start', '')
        stop_str = prog_elem.get('stop', '')

        if not channel_id or not start_str or not stop_str:
            continue

        start = parse_xmltv_time(start_str)
        end = parse_xmltv_time(stop_str)
        if start <= 0 or end <= 0:
            continue

        title_elem = prog_elem.find('title')
        title = title_elem.text if title_elem is not None and title_elem.text else ""

        desc_elem = prog_elem.find('desc')
        description = desc_elem.text if desc_elem is not None and desc_elem.text else ""

        norm_id = normalize_id(channel_id)
        if norm_id not in epg:
            epg[norm_id] = []
        epg[norm_id].append(Programme(start=start, end=end, title=title, description=description))

    # Sort programmes by start time
    for channel_id in epg:
        epg[channel_id].sort(key=lambda p: p.start)

    return epg


def get_now_next(epg: EpgData, tvg_id: Optional[str]) -> Tuple[Optional[Programme], Optional[Programme]]:
    """Get current and next programme for a channel."""
    if not tvg_id:
        return None, None

    norm_id = normalize_id(tvg_id)
    programmes = epg.get(norm_id, [])
    now = time.time()

    current = None
    next_prog = None

    for i, prog in enumerate(programmes):
        if prog.start <= now <= prog.end:
            current = prog
            if i + 1 < len(programmes):
                next_prog = programmes[i + 1]
            break
        elif prog.start > now:
            next_prog = prog
            break

    return current, next_prog


def get_current_progress(programme: Optional[Programme]) -> float:
    """Get progress (0.0-1.0) of current programme."""
    if not programme:
        return 0.0
    now = time.time()
    total = programme.end - programme.start
    if total <= 0:
        return 0.0
    elapsed = now - programme.start
    return max(0.0, min(1.0, elapsed / total))


def save_to_cache(epg: EpgData, cache_dir: str = "."):
    """Save EPG data to JSON cache."""
    cache_path = os.path.join(cache_dir, CACHE_FILE)
    data = {
        "timestamp": time.time(),
        "channels": {}
    }
    for channel_id, programmes in epg.items():
        data["channels"][channel_id] = [
            {"start": p.start, "end": p.end, "title": p.title, "description": p.description}
            for p in programmes
        ]
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            json.dump(data, f)
    except Exception:
        pass


def load_from_cache(cache_dir: str = ".") -> Optional[EpgData]:
    """Load EPG data from JSON cache if fresh enough."""
    cache_path = os.path.join(cache_dir, CACHE_FILE)
    try:
        if not os.path.exists(cache_path):
            return None
        with open(cache_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        ts = data.get("timestamp", 0)
        if time.time() - ts > CACHE_LIFETIME:
            return None
        epg: EpgData = {}
        for channel_id, programmes in data.get("channels", {}).items():
            epg[channel_id] = [
                Programme(
                    start=p["start"],
                    end=p["end"],
                    title=p["title"],
                    description=p.get("description", "")
                )
                for p in programmes
            ]
        return epg
    except Exception:
        return None
