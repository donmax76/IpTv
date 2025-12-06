# Установка зависимостей

## Автоматическая установка

Запустите файл `install_dependencies.bat` двойным кликом.

Или из командной строки:
```bash
install_dependencies.bat
```

## Ручная установка

### Все зависимости одной командой:
```bash
pip install -r requirements.txt
```

### Или по отдельности:
```bash
pip install mss pillow pyautogui pynput opencv-python numpy
```

Для Windows также рекомендуется:
```bash
pip install pywin32
```

## Проверка установки

Запустите:
```bash
python check_dependencies.py
```

Скрипт покажет, какие пакеты установлены, а какие отсутствуют.

## Требования

- Python 3.8 или выше
- pip (обычно идет вместе с Python)
- Доступ в интернет (для скачивания пакетов)

## Решение проблем

### Ошибка "pip не найден"
Установите Python с официального сайта: https://www.python.org/downloads/
При установке обязательно отметьте "Add Python to PATH"

### Ошибка прав доступа
Запустите командную строку от имени администратора и повторите установку.

### Медленная установка
Используйте зеркало PyPI:
```bash
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

