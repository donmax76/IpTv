# Windhawk Mod - Hide Microphone Icon

Этот проект создает DLL мод для Windhawk, который скрывает иконку микрофона в системном трее Windows.

## Требования

- Visual Studio 2019 или выше
- Windows SDK 10.0
- Windhawk установлен или portable версия
- C++17 или выше

## Структура проекта

- `HideMicrophoneIcon.cpp` - основной код мода
- `windhawk_utils.h` - заголовочный файл для Windhawk API (упрощенная версия)
- `WindhawkMod.vcxproj` - файл проекта Visual Studio

## Сборка

1. Откройте `WindhawkMod.vcxproj` в Visual Studio
2. Выберите конфигурацию Release x64
3. Соберите проект (Build -> Build Solution)

## Установка

1. Скопируйте собранный `WindhawkMod.dll` в папку модов Windhawk
2. Или используйте Windhawk для загрузки мода

## Использование в проекте

Для использования этого мода в вашем проекте:

1. Соберите DLL
2. Интегрируйте с Windhawk или используйте напрямую через DLL injection в explorer.exe

## Примечания

- Мод работает только с explorer.exe (x64)
- Требует Windows 10/11
- Использует WinRT API для манипуляции XAML элементами системного трея

