"""XMLTV EPG parser - matches Android EpgRepository logic.

Mirrors Android Round 68-75 changes:
- Unicode-aware normalize_id (keeps Cyrillic / Azerbaijani letters).
- display-name indexing so playlists without tvg-id match by name.
- Trace logging into tvviewer_trace.txt for full-flow diagnostics.
- Streaming iterparse for big XMLTV files (40-80 MB+).
"""

import re
import gzip
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Set, Tuple
from datetime import datetime, timezone, timedelta
import requests
import json
import os
import time
import io
import hashlib


@dataclass
class Programme:
    start: float  # timestamp in seconds
    end: float
    title: str
    description: str = ""


EpgData = Dict[str, List[Programme]]

CACHE_FILE = "epg_cache.json"
# Round 319: 6 ч → 24 ч. На стороне юзера парсинг XMLTV занимает 30 сек
# и держит GIL — каретка/клики проваливаются. Раз кэш-load теперь
# подключён (Round 317), TTL имеет смысл расширить, чтобы свежий
# fetch требовался реже. EPG-сетка часто меняется только раз в сутки.
CACHE_LIFETIME = 24 * 3600  # 24 hours
TRACE_FILE = "tvviewer_trace.txt"
TRACE_MAX_BYTES = 500_000

# Совпадает с Android EpgRepository.normalizeId / TvGuideFragment.norm:
# \w в Python re по умолчанию Unicode-aware → держит кириллицу, ə, ç и пр.
_NORM_RE = re.compile(r'[^\w]', re.UNICODE)


def normalize_id(tvg_id: str) -> str:
    """Normalize a TVG ID for matching. Unicode-aware: keeps Cyrillic etc."""
    if not tvg_id:
        return ""
    return _NORM_RE.sub('', tvg_id.lower())


_FUZZY_TRAIL_DIGITS = re.compile(r'\d+$')
# Round 337: отсортировано по УБЫВАНИЮ длины. normalize_id() к этому
# моменту уже стёр все пробелы, так что суффиксы matching идёт по
# голой конкатенации букв без границ слов. Со старым порядком (короткие
# 2-буквенные коды раньше длинных) "Kino Plus" → "kinoplus" сначала
# терял «us» (кусок «Plus»!) → "kinopl", и только потом цикл
# останавливался — «plus» как отдельный суффикс так и не сработал.
# Длинные-первыми чинит это: "kinoplus".endswith('plus') матчится
# раньше чем endswith('us'), даёт корректное "kino".
_FUZZY_SUFFIXES = (
    'channel', 'канал',
    'plus', 'orig', 'uhd', 'fhd', 'qhd',
    'тв',
    'hd', 'sd', '4k', '8k', 'tv',
    'uk', 'ru', 'us', 'az', 'ua', 'by', 'kz', 'tr', 'ge', 'am', 'uz', 'tj', 'kg',
)
# Round 337: 2-буквенные «региональные» коды имеют ВЫСОКИЙ риск
# коллизии — они не редакция границ слов, поэтому запросто совпадают
# с концом обычного слова («Rugby»→…by, «Belarus»→…us, «Bonus»→…us,
# «Campus»→…us). Для них поднят минимальный остаток базы с 3 до 5
# символов — режем только когда после удаления кода остаётся
# достаточно длинная база, чтобы не превращать «Rugby TV» в «rug».
_FUZZY_SHORT_CODE_SUFFIXES = frozenset((
    'uk', 'ru', 'us', 'az', 'ua', 'by', 'kz', 'tr', 'ge', 'am', 'uz', 'tj', 'kg',
))


def fuzzy_key(s: str) -> str:
    """Aggressive normalization for fuzzy match: normalize_id then strip
    trailing digits and quality/region suffixes (HD/SD/4K/UK/RU/...).

    Used as a fallback when the exact normalize_id and display-name
    lookups produce no hit. Mirrors Android EpgRepository.fuzzyKey.

    Examples:
        "Sky Sports News HD 50 UK" -> "skysportsnews"
        "Первый HD"               -> "первый"
        "РБК HD 4K"               -> "рбк"

    Length floor of 4 chars protects "1tv" from being eaten down to "" by
    a "tv" suffix rule that doesn't exist anyway, but the floor also keeps
    over-aggressive stripping in check.
    """
    if not s:
        return ""
    # Round 294: стрипаем «(Тамбов)», «+0», «+1» и прочие региональные
    # маркеры ДО normalize_id. Юзер: канал «Россия 1 +0 (Тамбов)» не
    # матчился с iptv-org «Россия 1». Поэтому пре-кьюится 0 logo URLs.
    cleaned = s
    # 1) bracketed parts (Тамбов), [SD] и т.п.
    cleaned = re.sub(r'\([^)]*\)', '', cleaned)
    cleaned = re.sub(r'\[[^\]]*\]', '', cleaned)
    # 2) Timeshift markers «+0», «+1», «-1», «−2» и т.п.
    cleaned = re.sub(r'[+\-−]\d+', '', cleaned)
    # 3) Множественные пробелы → один.
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    t = normalize_id(cleaned)
    while len(t) > 3:
        nt = _FUZZY_TRAIL_DIGITS.sub('', t)
        if 3 <= len(nt) < len(t):
            t = nt
            continue
        stripped = False
        for suf in _FUZZY_SUFFIXES:
            # Round 337: 2-буквенные коды требуют остаток >=5 символов
            # (не 3) — иначе «Rugby»/«Belarus»/«Bonus»/«Campus» режутся
            # до бессмысленных 3-буквенных огрызков, которые потом
            # сталкиваются с другими такими же огрызками.
            min_remainder = 5 if suf in _FUZZY_SHORT_CODE_SUFFIXES else 3
            if t.endswith(suf) and len(t) - len(suf) >= min_remainder:
                t = t[:-len(suf)]
                stripped = True
                break
        if not stripped:
            break
    return t


def trace(tag: str, message: str, cache_dir: str = "."):
    """Append timestamped trace line to tvviewer_trace.txt + stdout.
    Bounded to 500 KB. Mirrors Android ErrorLogger.info()."""
    line = f"[{time.strftime('%H:%M:%S')}][{tag}] {message}\n"
    print(line, end='')
    try:
        path = os.path.join(cache_dir, TRACE_FILE)
        existing = b""
        if os.path.exists(path):
            try:
                with open(path, 'rb') as f:
                    existing = f.read()
            except Exception:
                existing = b""
        combined = (existing + line.encode('utf-8'))[-TRACE_MAX_BYTES:]
        with open(path, 'wb') as f:
            f.write(combined)
    except Exception:
        pass


def parse_xmltv_time(time_str: str) -> float:
    """Parse XMLTV timestamp like '20240101120000 +0300' to epoch seconds."""
    if not time_str:
        return 0.0
    time_str = time_str.strip()
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


def _source_cache_path(cache_dir: str, epg_url: str) -> str:
    """Round 337: отдельный кэш-файл НА КАЖДЫЙ EPG-источник, ключ —
    хэш URL. Раньше fetch_epg() читал/писал в общий CACHE_FILE
    (epg_cache.json) независимо от того, какой именно epg_url
    запрашивается. При 2+ источниках (config.epg_urls) это давало
    молчаливую потерю данных: fetch источника #1 писал свежий
    epg_cache.json, fetch источника #2 тут же читал ЕГО же кэш и
    возвращал данные источника #1 без единого сетевого запроса —
    источник #2 не подгружался вообще, пока не истечёт CACHE_LIFETIME.
    save_to_cache/load_from_cache (без url) остаются как были — их
    отдельно использует tvviewer.py для ОБЩЕГО объединённого кэша
    (Round 317), это не тот же кейс."""
    h = hashlib.sha1(epg_url.encode('utf-8', 'ignore')).hexdigest()[:16]
    return os.path.join(cache_dir, f"epg_src_cache_{h}.json")


def fetch_epg(epg_url: str, cache_dir: str = ".",
              progress: Optional[Callable[[str], None]] = None,
              channel_filter: Optional[Set[str]] = None) -> EpgData:
    """Fetch and parse EPG data from URL, with caching.

    progress: optional callback for live status updates (UI hookup).
    channel_filter: if provided, only programmes for these normalized
                    ids/names are kept. Drastically reduces memory.
    """
    # Round 337: кэш ПЕР-ИСТОЧНИК (см. _source_cache_path) — раньше
    # это был load_from_cache(cache_dir) без привязки к epg_url, и с
    # 2+ источниками второй тихо получал данные первого без сетевого
    # запроса.
    src_cache_path = _source_cache_path(cache_dir, epg_url)
    cached = load_from_cache(cache_dir, cache_path=src_cache_path)
    if cached:
        trace("EPG", f"using cached EPG ({len(cached)} channels)", cache_dir)
        return cached

    if not epg_url:
        return {}

    host = epg_url.split('://', 1)[-1].split('/', 1)[0][:30]
    if progress:
        progress(f"Подключаюсь к {host}…")
    trace("EPG", f"fetchSingle({host}) start", cache_dir)

    headers = {
        'User-Agent': 'TVViewer/5.4 (Windows Desktop)',
        'Accept-Encoding': 'gzip',
    }

    t0 = time.time()
    try:
        # Round 298: ИСТИННЫЙ streaming через ВРЕМЕННЫЙ ФАЙЛ на диске,
        # а не BytesIO. Юзер всё ещё ловил MemoryError на buf.write(c)
        # потому что Round 294 копил 100 МБ сжатых в BytesIO. Диск
        # вмещает любой размер, RAM не нужен.
        import tempfile as _tf
        tmp_fd, tmp_path = _tf.mkstemp(suffix='.epgdl', prefix='tvv_')
        os.close(tmp_fd)
        size_compressed = 0
        try:
            with requests.get(epg_url, headers=headers, timeout=90,
                              allow_redirects=True, stream=True,
                              verify=False) as response:
                response.raise_for_status()
                if progress:
                    progress(f"{host}: скачиваю EPG…")
                trace("EPG", f"fetchSingle({host}) HTTP {response.status_code}, "
                             f"streaming to disk…", cache_dir)
                with open(tmp_path, 'wb') as out_f:
                    for c in response.iter_content(chunk_size=64 * 1024):
                        if not c:
                            continue
                        out_f.write(c)
                        size_compressed += len(c)
            # Определяем gzip по первым 2 байтам файла.
            with open(tmp_path, 'rb') as in_f:
                first2 = in_f.read(2)
            is_gzip = first2 == b'\x1f\x8b'
            trace("EPG",
                  f"fetchSingle({host}) downloaded {size_compressed//1024} KB, "
                  f"gzip={is_gzip}, decompressing…", cache_dir)
            # Декомпрессируем (или просто читаем) и парсим — БЕЗ
            # промежуточного огромного буфера, поэлементно через
            # parse_xmltv_streaming. Buffer 4 МБ для GzipFile = быстрый
            # IO без давления на heap.
            if is_gzip:
                with gzip.open(tmp_path, 'rb') as gz:
                    epg_data = parse_xmltv_streaming_fileobj(
                        gz, channel_filter)
            else:
                with open(tmp_path, 'rb') as plain:
                    epg_data = parse_xmltv_streaming_fileobj(
                        plain, channel_filter)
        finally:
            try: os.remove(tmp_path)
            except Exception: pass
    except Exception as e:
        trace("EPG", f"fetchSingle({host}) HTTP ERROR: {type(e).__name__}: {e}", cache_dir)
        if progress:
            progress(f"{host}: ошибка — {type(e).__name__}")
        raise

    progs_total = sum(len(v) for v in epg_data.values())
    trace("EPG",
          f"fetchSingle({host}) parsed {len(epg_data)} channels, {progs_total} programmes",
          cache_dir)
    if progress:
        progress(f"{host}: {len(epg_data)} каналов, {progs_total} передач")

    save_to_cache(epg_data, cache_dir, cache_path=src_cache_path)
    return epg_data


def parse_xmltv_streaming_fileobj(
        fileobj,
        channel_filter: Optional[Set[str]] = None) -> EpgData:
    """Round 298: парсит XMLTV прямо из file-like (можно из gzip.GzipFile
    или открытого XML-файла). Никаких RAM-аллокаций на полный XML —
    ET.iterparse читает поток chunk-by-chunk сам."""
    return _parse_xmltv_from(fileobj, channel_filter)


def parse_xmltv_streaming(content: bytes,
                          channel_filter: Optional[Set[str]] = None) -> EpgData:
    """Backward-compat wrapper around _parse_xmltv_from(BytesIO(content))."""
    return _parse_xmltv_from(io.BytesIO(content), channel_filter)


def _parse_xmltv_from(fileobj,
                      channel_filter: Optional[Set[str]] = None) -> EpgData:
    """Round 298: общий поточный парсер. Принимает любой file-like
    (BytesIO для backward-compat, gzip.GzipFile или открытый файл для
    fetch_epg). ET.iterparse читает поток сам — без RAM-аллокаций."""
    epg: EpgData = {}
    display_names_by_id: Dict[str, List[str]] = {}

    cur_channel_id: Optional[str] = None
    cur_display_names: List[str] = []

    try:
        ctx = ET.iterparse(fileobj, events=('start', 'end'))
        ctx_iter = iter(ctx)
        # Round 341: классический ET.iterparse memory-leak gotcha —
        # elem.clear() очищает ТОЛЬКО текст/атрибуты/детей САМОГО
        # elem'а, но пустой (уже .clear()-нутый) elem всё равно
        # ОСТАЁТСЯ ребёнком root-элемента <tv> до конца парсинга. На
        # XMLTV с десятками тысяч <programme> (типично для 7+ дней ×
        # тысячи каналов, а тут ещё 2 EPG-источника мёржатся) это
        # накапливает МИЛЛИОНЫ пустых Element-объектов, висящих в
        # дереве root'а, пока вся функция не вернётся. Юзер: «жрёт
        # память компьютера, 34% от 64гб». Захватываем root на первом
        # событии и периодически root.clear() — так уже обработанные
        # (пустые) дети реально освобождаются, а не просто пустеют.
        try:
            _, root_elem = next(ctx_iter)
        except StopIteration:
            root_elem = None
        # Round 290: каждые 100 элементов отпускаем GIL (раньше 500
        # было недостаточно — watchdog ловил 11+ сек блокировки во
        # время EPG-парсинга на старте). 100 даёт 30+ yield'ов в
        # секунду, heartbeat успевает тикать.
        # Round 307: time.sleep(0) на Windows вызывает Sleep(0) который
        # уступает квант шедулеру, НО GIL остаётся у этого потока. В
        # итоге vlc.Instance() в bg-нитке всё равно не запускался —
        # libvlc_new занимал 30 сек вместо 1-2. time.sleep(0.001) —
        # 1мс — реально освобождает GIL (lock-acquire требует
        # non-zero delay). Парсер всего на ~5% медленнее, но VLC
        # успевает инициализироваться параллельно.
        # Round 319: 100 → 25 элементов, sleep остаётся 0.001. Юзер:
        # «через минуту каретка появляется», т.е. парсер всё ещё
        # держит GIL слишком долго между yield'ами. Учетверяем частоту
        # yield'ов — 25 элементов это ~0.3 мс работы, плюс 1 мс sleep =
        # ~30% времени Qt event loop получает CPU гарантированно.
        # Парсер замедляется на ~10%, но UI остаётся отзывчивым.
        _iter_n = 0
        for event, elem in ctx_iter:
            _iter_n += 1
            if _iter_n % 25 == 0:
                time.sleep(0.001)  # реально освобождает GIL на Windows
            tag = elem.tag
            if event == 'start':
                if tag == 'channel':
                    cur_channel_id = normalize_id(elem.get('id', ''))
                    cur_display_names = []
            elif event == 'end':
                if tag == 'display-name' and cur_channel_id is not None:
                    if elem.text:
                        norm = normalize_id(elem.text)
                        if norm and norm != cur_channel_id:
                            cur_display_names.append(norm)
                elif tag == 'channel':
                    if cur_channel_id and cur_display_names:
                        display_names_by_id[cur_channel_id] = list(cur_display_names)
                    cur_channel_id = None
                    cur_display_names = []
                    elem.clear()
                    # Round 341: см. комментарий выше про root_elem —
                    # реально освобождает уже обработанные <channel>/
                    # <programme> из дерева, а не просто опустошает их.
                    if root_elem is not None:
                        root_elem.clear()
                elif tag == 'programme':
                    channel_id = normalize_id(elem.get('channel', ''))
                    if channel_id:
                        title_elem = elem.find('title')
                        title = (title_elem.text or "").strip()[:120] if title_elem is not None else ""
                        if title:
                            start = parse_xmltv_time(elem.get('start', ''))
                            end = parse_xmltv_time(elem.get('stop', ''))
                            if start > 0 and end > 0:
                                desc_elem = elem.find('desc')
                                desc = (desc_elem.text or "")[:500] if desc_elem is not None else ""
                                epg.setdefault(channel_id, []).append(
                                    Programme(start=start, end=end, title=title, description=desc)
                                )
                    elem.clear()
                    if root_elem is not None:
                        root_elem.clear()
    except ET.ParseError:
        pass

    # Apply channel filter (matches by id OR by any display-name)
    if channel_filter:
        keep: EpgData = {}
        for cid, progs in epg.items():
            if cid in channel_filter:
                keep[cid] = progs
                continue
            names = display_names_by_id.get(cid, [])
            if any(n in channel_filter for n in names):
                keep[cid] = progs
        epg = keep

    # Sort by start
    for cid in epg:
        epg[cid].sort(key=lambda p: p.start)

    # Mirror under display-names so name-based lookups work
    for cid, names in display_names_by_id.items():
        progs = epg.get(cid)
        if not progs:
            continue
        for n in names:
            if n not in epg:
                epg[n] = progs

    # Fuzzy mirror: each existing key also indexed under fuzzy_key().
    # Snapshot first so we don't iterate while mutating; skip if the
    # fuzzy key already belongs to another channel.
    snapshot = list(epg.items())
    for cid, progs in snapshot:
        fk = fuzzy_key(cid)
        if fk and fk != cid and fk not in epg:
            epg[fk] = progs

    return epg


def parse_xmltv(xml_text: str) -> EpgData:
    """Backwards-compat wrapper for code that already had the string-based parser."""
    if isinstance(xml_text, str):
        content = xml_text.encode('utf-8', errors='ignore')
    else:
        content = xml_text
    return parse_xmltv_streaming(content)


def get_now_next(epg: EpgData, tvg_id: Optional[str], name: Optional[str] = None) -> Tuple[Optional[Programme], Optional[Programme]]:
    """Get current and next programme for a channel.

    Tries (in order): normalize_id(tvg_id), normalize_id(name),
    fuzzy_key(name), fuzzy_key(tvg_id). The fuzzy fallback catches
    M3U names like 'Sky Sports News HD 50 UK' that don't match the
    XMLTV id 'skysportsnews.uk' exactly.
    """
    programmes: List[Programme] = []
    for key in (
        normalize_id(tvg_id) if tvg_id else "",
        normalize_id(name) if name else "",
        fuzzy_key(name) if name else "",
        fuzzy_key(tvg_id) if tvg_id else "",
    ):
        if key and key in epg:
            programmes = epg[key]
            break
    if not programmes:
        return None, None
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


def get_upcoming_programmes(epg: EpgData, tvg_id: Optional[str], name: Optional[str] = None, count: int = 3) -> List[Programme]:
    """Return up to N upcoming (or currently-airing) programmes.
    Round 234 (Windows port of Android Round 212): used to render
    a 3-slot EPG grid in the overlay channel list."""
    programmes: List[Programme] = []
    for key in (
        normalize_id(tvg_id) if tvg_id else "",
        normalize_id(name) if name else "",
        fuzzy_key(name) if name else "",
        fuzzy_key(tvg_id) if tvg_id else "",
    ):
        if key and key in epg:
            programmes = epg[key]
            break
    if not programmes:
        return []
    now = time.time()
    upcoming: List[Programme] = []
    for prog in programmes:
        if prog.end < now:
            continue
        upcoming.append(prog)
        if len(upcoming) >= count:
            break
    return upcoming


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


def save_to_cache(epg: EpgData, cache_dir: str = ".", cache_path: str = None):
    """Save EPG data to JSON cache.

    Round 337: добавлен опциональный cache_path — если задан, пишем
    туда напрямую вместо os.path.join(cache_dir, CACHE_FILE). Позволяет
    fetch_epg() использовать отдельный файл на источник (см.
    _source_cache_path) не трогая сигнатуру для существующих вызовов
    (tvviewer.py Round 317 использует эту же функцию для ОБЩЕГО
    объединённого кэша без cache_path — поведение не меняется)."""
    if cache_path is None:
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


def load_from_cache(cache_dir: str = ".", cache_path: str = None) -> Optional[EpgData]:
    """Load EPG data from JSON cache if fresh enough.

    Round 337: тот же опциональный cache_path что и в save_to_cache."""
    if cache_path is None:
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
