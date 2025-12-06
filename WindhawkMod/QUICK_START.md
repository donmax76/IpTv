# Быстрый старт - Windhawk Mod для скрытия иконки микрофона

## Что было сделано

1. ✅ Создан проект Windhawk мода (`WindhawkMod/`)
2. ✅ Реализован код для скрытия иконки микрофона в системном трее
3. ✅ Добавлены stub реализации Windhawk API для разработки
4. ✅ Созданы скрипты сборки и установки

## Следующие шаги

### 1. Получить Windhawk SDK (опционально)

Для production использования нужен полный Windhawk SDK. Stub реализации в `windhawk_utils.cpp` работают для разработки, но для реального использования нужны функции из Windhawk runtime.

**Варианты:**
- Использовать мод через Windhawk UI (SDK не нужен)
- Получить SDK с официального сайта Windhawk
- Использовать существующие моды как примеры

### 2. Собрать проект

```batch
cd WindhawkMod
BUILD.bat
```

Или откройте `WindhawkMod.sln` в Visual Studio и соберите.

### 3. Использовать мод

**Вариант A: Через Windhawk**
1. Установите Windhawk
2. Создайте новый мод
3. Укажите путь к `WindhawkMod.dll`
4. Активируйте мод

**Вариант B: Прямая интеграция**
Мод можно интегрировать в ваш проект через DLL injection в explorer.exe.

### 4. Проверить работу

1. Запустите `HiddenAudioService.exe` для записи аудио
2. Проверьте системный трей - иконка микрофона должна быть скрыта
3. Проверьте логи в `%TEMP%\WindhawkMod.log`

## Структура проекта

```
WindhawkMod/
├── HideMicrophoneIcon.cpp    # Основной код мода
├── windhawk_utils.h           # Заголовочный файл Windhawk API
├── windhawk_utils.cpp         # Stub реализации (заменить на реальные)
├── WindhawkMod.vcxproj        # Файл проекта Visual Studio
├── WindhawkMod.sln            # Решение Visual Studio
├── BUILD.bat                  # Скрипт сборки
├── INSTALL_WINDHAWK.bat       # Скрипт установки Windhawk
├── INTEGRATION.md             # Подробная инструкция по интеграции
└── README.md                  # Документация проекта
```

## Примечания

- Мод работает только с explorer.exe (x64)
- Требует Windows 10/11
- Использует WinRT API для манипуляции XAML элементами
- В production замените stub реализации на реальные функции Windhawk

## Отладка

Логи сохраняются в `%TEMP%\WindhawkMod.log`

Для просмотра в реальном времени используйте DebugView:
```batch
DebugView.exe /accepteula
```

