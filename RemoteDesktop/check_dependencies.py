#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка наличия всех необходимых зависимостей
"""

import sys

REQUIRED_PACKAGES = {
    'mss': 'mss',
    'PIL': 'pillow',
    'pyautogui': 'pyautogui',
    'pynput': 'pynput',
    'cv2': 'opencv-python',
    'numpy': 'numpy',
    'tkinter': None  # Встроен в Python
}

missing = []
installed = []

print("Проверка зависимостей...")
print("=" * 60)

for module_name, package_name in REQUIRED_PACKAGES.items():
    if package_name is None:
        continue
    try:
        __import__(module_name)
        installed.append(package_name)
        print(f"✓ {package_name}")
    except ImportError:
        missing.append(package_name)
        print(f"✗ {package_name} - ОТСУТСТВУЕТ")

print("=" * 60)

if missing:
    print(f"\nОтсутствуют {len(missing)} пакетов:")
    for pkg in missing:
        print(f"  - {pkg}")
    print("\nУстановите их командой:")
    print(f"pip install {' '.join(missing)}")
    print("\nИли запустите: install_dependencies.bat")
    sys.exit(1)
else:
    print("\n✓ Все зависимости установлены!")
    sys.exit(0)

