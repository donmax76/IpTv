"""iptv-org channel database lookup — port of Android ChannelMetaLookup.

Downloads https://iptv-org.github.io/api/channels.json once a week,
indexes by normalized name (and alt_names) → (logo_url, tvg_id).
Used as a fallback when the user's M3U playlist doesn't include
tvg-logo / tvg-id attributes.
"""

import json
import os
import threading
import time
from dataclasses import dataclass
from typing import Dict, Optional

import requests

import re as _re

from epg_parser import normalize_id, fuzzy_key, trace


URL = "https://iptv-org.github.io/api/channels.json"
CACHE_FILE = "iptv_org_channels.json"
# Round 309: отдельный фид с логотипами. iptv-org/api/channels.json
# держит у многих каналов logo=null, а реальные картинки лежат тут
# и привязаны к channel_id (= tvg-id из channels.json). Лог build 93:
#   match_no_logo samples: 'Sky Sports News HD 50 UK'→id='SkySportsNews.ie'
#                          | 'Крик-ТВ'→id='KrikTV.ru'
#                          | 'РБК HD 50'→id='RBKTV.ru'
# Все три iptv-org знает, у всех в channels.json logo=null. Заливаем
# logos.json и подсасываем картинку по tvg_id.
LOGOS_URL = "https://iptv-org.github.io/api/logos.json"
LOGOS_CACHE_FILE = "iptv_org_logos.json"
CACHE_LIFETIME_SEC = 7 * 24 * 60 * 60  # 7 days

# Round 286: стрипаем «провайдерские» префиксы из имени канала перед
# матчингом с iptv-org. У юзера каналы идут как «VF Боевик 275», в базе
# хранится «Боевик ТВ» — точный матч не срабатывал, папка tvviewer_logos
# оставалась пустая.
_PROVIDER_PREFIX_RE = _re.compile(
    r'^(?:VF|VIP|HD|FHD|UHD|4K|8K|SD|TV|TV1|TV2|★|♥|HQ|LQ)\s+',
    _re.IGNORECASE)
# Также: ведущие/висящие цифры, флаги emoji.
_LEADING_TRAILING_TRASH_RE = _re.compile(
    r'^[\s\d._\-:|]+|[\s\d._\-:|]+$')


def _clean_for_lookup(name: str) -> str:
    if not name:
        return ""
    s = name
    # Несколько раз убираем разные префиксы (VF VIP Боевик → Боевик).
    for _ in range(4):
        new = _PROVIDER_PREFIX_RE.sub('', s).strip()
        if new == s:
            break
        s = new
    s = _LEADING_TRAILING_TRASH_RE.sub('', s)
    return s


@dataclass
class Meta:
    logo_url: Optional[str] = None
    tvg_id: Optional[str] = None


_lock = threading.Lock()
_by_name: Dict[str, Meta] = {}
# Round 309: tvg-id → logo URL из iptv-org/api/logos.json (отдельный
# фид, см. LOGOS_URL). Один tvg-id может иметь несколько лого
# (разные размеры/форматы) — сохраняем первый встретившийся.
_logos_by_id: Dict[str, str] = {}
_loaded = False
_loading = False


def is_loaded() -> bool:
    return _loaded


def lookup(channel_name: str) -> Optional[Meta]:
    """Round 286: трёхуровневый матч с iptv-org.
      1) точный normalize_id (как раньше);
      2) после очистки «провайдерских» префиксов VF/VIP/HD/...;
      3) fuzzy_key — стрипает HD/SD/4K и регион-суффиксы.
    Это даёт логотипы каналам типа «VF Боевик 275»."""
    if not _loaded or not channel_name:
        return None
    # 1) Точный матч.
    m = _by_name.get(normalize_id(channel_name))
    if m is not None:
        return m
    # 2) Чистка провайдерских префиксов.
    cleaned = _clean_for_lookup(channel_name)
    if cleaned and cleaned != channel_name:
        m = _by_name.get(normalize_id(cleaned))
        if m is not None:
            return m
        # 3) fuzzy_key на очищенной строке.
        fk = fuzzy_key(cleaned)
        if fk:
            m = _by_name.get(fk)
            if m is not None:
                return m
    # 4) fuzzy_key на исходном имени.
    fk = fuzzy_key(channel_name)
    if fk:
        return _by_name.get(fk)
    return None


def ensure_loaded(cache_dir: str = ".", on_loaded=None):
    """Kick off background load. Safe to call repeatedly.

    on_loaded: optional callback fired (on caller's thread) once the
               database is in memory.
    """
    global _loading
    with _lock:
        if _loaded:
            if on_loaded:
                on_loaded()
            return
        if _loading:
            return
        _loading = True

    def worker():
        global _loaded, _loading
        try:
            cache_path = os.path.join(cache_dir, CACHE_FILE)
            text: Optional[str] = None
            fresh = False
            if os.path.exists(cache_path):
                age = time.time() - os.path.getmtime(cache_path)
                if age < CACHE_LIFETIME_SEC:
                    try:
                        with open(cache_path, 'r', encoding='utf-8') as f:
                            text = f.read()
                        fresh = True
                    except Exception:
                        text = None
            if text is None:
                text = _fetch_and_cache(cache_path)
            if text:
                # Round 298/299: watchdog подавлялся на время парсинга
                # (50k+ каналов держали GIL и морозили main thread).
                # Round 351: подавление СНЯТО — _parse_and_index теперь
                # периодически уступает GIL (см. комментарий там), фриза
                # быть не должно, а если он всё же случится — watchdog
                # обязан его показать, а не прятать.
                _parse_and_index(text)
            if not fresh:
                # Even if we used a stale cache, re-fetch in background
                # so the next session has new data.
                threading.Thread(target=lambda: _fetch_and_cache(cache_path),
                                 daemon=True).start()
            # Round 309: рядом с channels.json грузим logos.json и
            # индексируем по tvg-id. Кэш такой же 7-дневный.
            try:
                logos_path = os.path.join(cache_dir, LOGOS_CACHE_FILE)
                ltext: Optional[str] = None
                lfresh = False
                if os.path.exists(logos_path):
                    lage = time.time() - os.path.getmtime(logos_path)
                    if lage < CACHE_LIFETIME_SEC:
                        try:
                            with open(logos_path, 'r', encoding='utf-8') as f:
                                ltext = f.read()
                            lfresh = True
                        except Exception:
                            ltext = None
                if ltext is None:
                    ltext = _fetch_and_cache_logos(logos_path)
                if ltext:
                    _parse_and_index_logos(ltext)
                if not lfresh:
                    threading.Thread(
                        target=lambda: _fetch_and_cache_logos(logos_path),
                        daemon=True).start()
            except Exception as le:
                trace("META", f"logos load failed: {type(le).__name__}: {le}",
                      cache_dir)
        except Exception as e:
            trace("META", f"ensure_loaded failed: {type(e).__name__}: {e}", cache_dir)
        finally:
            with _lock:
                _loaded = True
                _loading = False
            trace("META", f"loaded {len(_by_name)} channel meta entries, "
                          f"{len(_logos_by_id)} logos", cache_dir)
            if on_loaded:
                try:
                    on_loaded()
                except Exception:
                    pass

    threading.Thread(target=worker, daemon=True).start()


def _fetch_and_cache_logos(cache_path: str) -> Optional[str]:
    """Round 309: тот же тройной транспорт что у channels.json."""
    import urllib.request as _urlr
    text = None
    try:
        with requests.get(LOGOS_URL, timeout=60) as r:
            r.raise_for_status()
            text = r.text
        trace("META", f"logos fetched via requests: {len(text)} bytes")
    except Exception as e1:
        trace("META", f"logos requests failed: {type(e1).__name__}: {e1}")
        try:
            req = _urlr.Request(LOGOS_URL, headers={'User-Agent': 'TVViewer'})
            with _urlr.urlopen(req, timeout=60) as r:
                text = r.read().decode('utf-8', errors='replace')
            trace("META", f"logos fetched via urllib: {len(text)} bytes")
        except Exception as e2:
            trace("META", f"logos urllib failed: {type(e2).__name__}: {e2}")
            try:
                import ssl as _ssl
                ctx = _ssl._create_unverified_context()
                req = _urlr.Request(LOGOS_URL,
                                    headers={'User-Agent': 'TVViewer'})
                with _urlr.urlopen(req, timeout=60, context=ctx) as r:
                    text = r.read().decode('utf-8', errors='replace')
                trace("META", f"logos fetched via urllib (UNVERIFIED): "
                              f"{len(text)} bytes")
            except Exception as e3:
                trace("META", f"logos all transports failed: "
                              f"{type(e3).__name__}: {e3}")
                return None
    if not text:
        return None
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            f.write(text)
    except Exception as e:
        trace("META", f"logos cache write failed: {e}")
    return text


def _parse_and_index_logos(text: str):
    """Round 309: indexing iptv-org/api/logos.json — list of entries
    with `channel` (tvg-id) and `url` fields. Keep the first URL per id."""
    try:
        arr = json.loads(text)
    except Exception:
        return
    # Round 351: ~38k итераций — периодический yield GIL, как в
    # _parse_and_index (см. комментарий там).
    _n = 0
    for o in arr:
        _n += 1
        if _n % 500 == 0:
            time.sleep(0.001)
        if not isinstance(o, dict):
            continue
        ch_id = o.get("channel") or ""
        url = o.get("url") or ""
        if not ch_id or not url:
            continue
        if ch_id not in _logos_by_id:
            _logos_by_id[ch_id] = url


def lookup_logo_by_id(tvg_id: str) -> Optional[str]:
    """Round 309: подсасываем картинку по tvg-id из logos.json."""
    if not tvg_id:
        return None
    return _logos_by_id.get(tvg_id)


def _fetch_and_cache(cache_path: str) -> Optional[str]:
    """Round 280: тройной транспорт как у UpdateCheckThread —
    requests → urllib → urllib без SSL. PyInstaller-сборка без
    cacert.pem иначе молча падала и iptv-org логотипы не
    подтягивались, поэтому tvviewer_logos оставался пуст."""
    import urllib.request as _urlr
    text = None
    # 1) requests + certifi
    try:
        with requests.get(URL, timeout=60) as r:
            r.raise_for_status()
            text = r.text
        trace("META", f"fetched via requests: {len(text)} bytes")
    except Exception as e1:
        trace("META", f"requests failed: {type(e1).__name__}: {e1}")
        # 2) urllib системный SSL
        try:
            req = _urlr.Request(URL, headers={'User-Agent': 'TVViewer'})
            with _urlr.urlopen(req, timeout=60) as r:
                text = r.read().decode('utf-8', errors='replace')
            trace("META", f"fetched via urllib: {len(text)} bytes")
        except Exception as e2:
            trace("META", f"urllib failed: {type(e2).__name__}: {e2}")
            # 3) urllib unverified SSL
            try:
                import ssl as _ssl
                ctx = _ssl._create_unverified_context()
                req = _urlr.Request(URL, headers={'User-Agent': 'TVViewer'})
                with _urlr.urlopen(req, timeout=60, context=ctx) as r:
                    text = r.read().decode('utf-8', errors='replace')
                trace("META", f"fetched via urllib (UNVERIFIED): {len(text)} bytes")
            except Exception as e3:
                trace("META", f"all transports failed: {type(e3).__name__}: {e3}")
                return None
    if not text:
        return None
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            f.write(text)
        trace("META", f"saved to cache: {cache_path}")
    except Exception as e:
        trace("META", f"cache write failed: {e}")
    return text


def _parse_and_index(text: str):
    """Round 351: этот цикл — 64k+ записей × до 6 regex-вызовов на
    запись (normalize_id + fuzzy_key на имя и каждый alt_name) — был
    крупнейшим не-yield'ящим GIL-хогом в проекте: фоновая нитка
    держала GIL сотни тысяч итераций подряд, main thread вставал
    колом. Хуже того — на время парсинга watchdog ПОДАВЛЯЛСЯ
    (_WATCHDOG_SUPPRESS_CB), т.е. фриз был, но в лог не попадал.
    Теперь: периодический time.sleep(0.001) как в остальных
    парсерах (Round 344/348) — и подавление снято, чтобы реальные
    блокировки снова были видны в логе."""
    try:
        arr = json.loads(text)
    except Exception:
        return
    _n = 0
    for o in arr:
        _n += 1
        if _n % 200 == 0:
            time.sleep(0.001)
        if not isinstance(o, dict):
            continue
        name = o.get("name") or ""
        if not name:
            continue
        tvg_id = o.get("id") or None
        logo = o.get("logo") or None
        if not logo and not tvg_id:
            continue
        meta = Meta(logo_url=logo, tvg_id=tvg_id)
        # Round 286: индексируем под нормализованным именем, fuzzy_key
        # и под всеми alt_names (тоже под обеими ключами). Это даёт
        # шанс матча для «VF Боевик 275» → «Боевик».
        keys = []
        nk = normalize_id(name)
        if nk:
            keys.append(nk)
        fk = fuzzy_key(name)
        if fk and fk != nk:
            keys.append(fk)
        for alt in (o.get("alt_names") or []):
            alt_nk = normalize_id(alt)
            if alt_nk and alt_nk not in keys:
                keys.append(alt_nk)
            alt_fk = fuzzy_key(alt)
            if alt_fk and alt_fk not in keys:
                keys.append(alt_fk)
        for k in keys:
            if k not in _by_name:
                _by_name[k] = meta


def fill_missing_logos(channels) -> int:
    """For each channel without logo_url / tvg_id, try iptv-org by name.
    Returns number of channels enriched. Mutates Channel objects in place.
    No-op if database not loaded yet.

    Round 286: пишем в trace распределение — сколько уже было с tvg-logo,
    сколько подсосали из iptv-org, сколько так и не нашлось. Юзер: «папка
    tvviewer_logos пуста».
    """
    if not _loaded or not channels:
        return 0
    enriched = 0
    had_logo = 0
    no_match = 0
    match_no_logo = 0
    # Round 307/308: первые 3 «не нашли вовсе» + первые 3 «нашли но
    # лого пусто» — дампим в trace. Юзер: enriched 0/3639 при
    # db_size=63876, значит не сетевой сбой, а либо фуззи-индексы
    # расходятся, либо iptv-org держит у нужных каналов logo=null.
    no_match_sample = []
    match_no_logo_sample = []
    # Round 351: 4000 каналов × до 8-10 regex-вызовов на промах
    # (lookup → normalize_id + _clean + fuzzy_key×2) без единого
    # yield'а; вызывается дважды на каждую загрузку плейлиста из
    # bg-ниток — периодически уступаем GIL.
    _n = 0
    for ch in channels:
        _n += 1
        if _n % 200 == 0:
            time.sleep(0.001)
        if ch.logo_url:
            had_logo += 1
            continue
        meta = lookup(ch.name)
        if meta and meta.logo_url:
            ch.logo_url = meta.logo_url
            enriched += 1
            if not ch.tvg_id and meta.tvg_id:
                ch.tvg_id = meta.tvg_id
        elif meta:
            # Канал в iptv-org есть, но logo=null. Подсосём tvg_id если
            # его не было — пригодится для EPG-матча.
            if not ch.tvg_id and meta.tvg_id:
                ch.tvg_id = meta.tvg_id
            # Round 309: пробуем logos.json по tvg-id. iptv-org держит
            # картинки в отдельном фиде, и для каналов с null в
            # channels.json там часто есть валидный URL.
            alt_logo = lookup_logo_by_id(meta.tvg_id or ch.tvg_id)
            if alt_logo:
                ch.logo_url = alt_logo
                enriched += 1
                continue
            match_no_logo += 1
            if len(match_no_logo_sample) < 3:
                match_no_logo_sample.append(
                    f"'{ch.name}'→id='{meta.tvg_id or ''}'")
        else:
            no_match += 1
            if len(no_match_sample) < 3:
                from epg_parser import normalize_id, fuzzy_key
                no_match_sample.append(
                    f"'{ch.name}'→nk='{normalize_id(ch.name)}',"
                    f"fk='{fuzzy_key(ch.name)}'")
    trace("META",
          f"fill_missing_logos: had={had_logo} enriched={enriched} "
          f"match_no_logo={match_no_logo} no_match={no_match} "
          f"of {len(channels)}")
    if no_match_sample:
        trace("META", "no_match samples: " + " | ".join(no_match_sample))
    if match_no_logo_sample:
        trace("META", "match_no_logo samples: " + " | ".join(match_no_logo_sample))
    return enriched
