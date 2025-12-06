# Интеграция Windhawk Mod в проект

## Шаг 1: Установка Windhawk

1. Скачайте Windhawk portable с официального сайта: https://windhawk.net/
2. Распакуйте в папку `Windhawk/` в корне проекта
3. Или установите Windhawk в систему

## Шаг 2: Сборка мода

### Вариант A: Через Visual Studio
1. Откройте `WindhawkMod.sln` в Visual Studio
2. Выберите конфигурацию Release x64
3. Build -> Build Solution (F7)

### Вариант B: Через командную строку
```batch
cd WindhawkMod
BUILD.bat
```

Результат: `bin\x64\Release\WindhawkMod.dll`

## Шаг 3: Использование мода

### Вариант A: Через Windhawk UI
1. Запустите Windhawk
2. Перейдите в "My Mods" (Мои моды)
3. Нажмите "Create a new mod" (Создать новый мод)
4. Укажите путь к `WindhawkMod.dll`
5. Активируйте мод

### Вариант B: Прямая интеграция в проект
Мод можно интегрировать напрямую в ваш проект через DLL injection в explorer.exe.

## Шаг 4: Проверка работы

1. Запустите запись аудио через `HiddenAudioService.exe`
2. Проверьте системный трей - иконка микрофона должна быть скрыта
3. Проверьте логи в `%TEMP%\WindhawkMod.log`

## Примечания

- Мод работает только с explorer.exe (x64)
- Требует Windows 10/11
- Использует WinRT API для манипуляции XAML элементами
- В production версии замените stub реализации в `windhawk_utils.cpp` на реальные функции из Windhawk SDK

## Отладка

Логи мода сохраняются в:
- `%TEMP%\WindhawkMod.log` - файл логов
- OutputDebugString - для отладки через DebugView

Для просмотра логов в реальном времени:
```batch
DebugView.exe /accepteula
```

