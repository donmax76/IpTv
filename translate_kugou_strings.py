import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

from googletrans import Translator

BASE_STRINGS = r"D:\\SONG_APK\\kugou-music-12-0-2\\res\\values\\strings.xml"
RU_STRINGS = r"D:\\SONG_APK\\kugou-music-12-0-2\\res\\values-ru\\strings.xml"
CACHE_FILE = "translate_cache_ru.json"

PLACEHOLDER_PATTERN = re.compile(r"%(?:\d+\$)?[-+#0]?(?:\d+)?(?:\.\d+)?[dfsfg]|%%")
TAG_PATTERN = re.compile(r"<[^>]+>")

def load_cache():
    if os.path.exists(CACHE_FILE):
        with open(CACHE_FILE, "r", encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_cache(cache):
    with open(CACHE_FILE, "w", encoding="utf-8") as fh:
        json.dump(cache, fh, ensure_ascii=False, indent=2)


def contains_russian(text):
    return bool(re.search("[\u0400-\u04FF]", text))


def is_probably_data(text):
    stripped = text.strip()
    if not stripped:
        return True
    if "://" in stripped or stripped.startswith("http") or stripped.startswith("www"):
        return True
    if "@" in stripped and " " not in stripped and "." in stripped:
        return True
    if stripped.startswith("{") and stripped.endswith("}"):
        return True
    if stripped.startswith("[") and stripped.endswith("]"):
        return True
    if len(stripped) > 3800:
        return True
    return False


def mask_tokens(text):
    replacements = []

    def add(token_value):
        token = f"__PH_{len(replacements)}__"
        replacements.append((token, token_value))
        return token

    def repl(match):
        return add(match.group(0))

    masked = PLACEHOLDER_PATTERN.sub(repl, text)
    masked = TAG_PATTERN.sub(repl, masked)
    return masked, replacements


def unmask_text(text, replacements):
    for token, value in replacements:
        text = text.replace(token, value)
    return text


def translate_strings():
    if not os.path.exists(BASE_STRINGS):
        raise SystemExit("Base strings file not found")

    base_tree = ET.parse(BASE_STRINGS)
    base_root = base_tree.getroot()

    existing_ru = {}
    if os.path.exists(RU_STRINGS):
        ru_tree = ET.parse(RU_STRINGS)
        ru_root = ru_tree.getroot()
        for elem in ru_root.findall("string"):
            name = elem.attrib.get("name")
            if name:
                existing_ru[name] = (elem.text or "", elem.attrib)

    cache = load_cache()
    translator = Translator()

    new_root = ET.Element("resources")
    total = 0
    translated = 0
    skipped = 0

    for elem in base_root.findall("string"):
        total += 1
        name = elem.attrib.get("name")
        attrib = elem.attrib.copy()
        text = elem.text or ""
        new_elem = ET.SubElement(new_root, "string", attrib)

        if not text.strip():
            new_elem.text = text
            continue

        if attrib.get("translatable") == "false":
            new_elem.text = text
            continue

        if name in existing_ru:
            new_elem.text = existing_ru[name][0]
            continue

        if contains_russian(text) or is_probably_data(text):
            new_elem.text = text
            skipped += 1
            continue

        key = text
        if key in cache:
            new_text = cache[key]
        else:
            masked, replacements = mask_tokens(text)
            try:
                time.sleep(0.1)  # Rate limiting
                result = translator.translate(masked, src="auto", dest="ru")
                new_text = result.text
            except Exception as exc:
                print(f"[WARN] Failed to translate '{name}': {exc}")
                new_text = text
                cache[key] = text  # Cache to avoid retrying
            else:
                new_text = unmask_text(new_text, replacements)
                cache[key] = new_text
                translated += 1
                if translated % 20 == 0:
                    save_cache(cache)
                    print(f"Progress: {total} processed, {translated} translated, {skipped} skipped")
        new_elem.text = new_text

    tree = ET.ElementTree(new_root)
    try:
        ET.indent(tree, space="    ")
    except AttributeError:
        pass
    tree.write(RU_STRINGS, encoding="utf-8", xml_declaration=True)
    save_cache(cache)
    print(f"Processed {total} strings. Newly translated: {translated}.")


if __name__ == "__main__":
    translate_strings()
