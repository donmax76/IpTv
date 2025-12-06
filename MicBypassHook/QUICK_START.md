# Быстрый старт MicBypassHook

## Шаг 1: Подготовка Detours

Ничего делать не нужно — библиотека **Microsoft Detours 4.0.1** уже включена в репозиторий (`third_party/Detours`).

Если захотите обновить Detours:
1. Замените содержимое папки `third_party/Detours` на новую версию
2. Убедитесь, что файлы `.cpp` находятся в `third_party/Detours/src`

## Шаг 2: Сборка DLL

### Вариант A: Через Visual Studio
1. Откройте `MicBypassHook.sln`
2. Выберите конфигурацию: **Release**, платформа: **x64**
3. Build > Build Solution (F7)
4. DLL будет в `bin\x64\Release\MicBypassHook.dll`

### Вариант B: Через командную строку
```cmd
cd MicBypassHook
BUILD.bat
```

### Вариант C: Через MSBuild напрямую
```cmd
msbuild MicBypassHook.sln /p:Configuration=Release /p:Platform=x64
```

## Шаг 3: Инжекция DLL в процесс

### Способ 1: Через Process Hacker (рекомендуется)
1. Скачайте Process Hacker: https://processhacker.sourceforge.io/
2. Запустите ваше приложение (AudioRecorder.exe)
3. В Process Hacker найдите процесс
4. Правый клик > Miscellaneous > Inject DLL
5. Выберите `MicBypassHook.dll`
6. Нажмите OK

### Способ 2: Через скрипт
```cmd
cd MicBypassHook
INJECT_DLL.bat AudioRecorder.exe
```

### Способ 3: Через AppInit_DLLs (требует прав администратора)
⚠️ **ВНИМАНИЕ:** Это загрузит DLL во все процессы!

1. Откройте реестр: `Win+R` → `regedit`
2. Перейдите: `HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Windows`
3. Установите:
   - `AppInit_DLLs` = полный путь к `MicBypassHook.dll`
   - `LoadAppInit_DLLs` = `1` (DWORD)
4. Перезагрузите компьютер

## Шаг 4: Проверка работы

1. Запустите ваше приложение для записи аудио
2. Начните запись
3. Проверьте, что индикатор микрофона **не появляется** в Windows

## Устранение проблем

### DLL не инжектируется
- Убедитесь, что процесс запущен
- Проверьте, что DLL существует по указанному пути
- Запустите инжектор от имени администратора

### Индикатор все еще появляется
- Убедитесь, что DLL успешно инжектирована (проверьте через Process Hacker)
- Убедитесь, что используется версия x64
- Попробуйте перезапустить приложение после инжекции

### Ошибки при сборке
- Убедитесь, что установлен Visual Studio 2019+ с C++
- Убедитесь, что установлен Windows 10/11 SDK
- Проверьте, что папка `third_party/Detours/src` присутствует

## Примечания

- DLL работает только для приложений, использующих WASAPI через `IAudioClient`
- Требуется инжекция DLL в процесс приложения
- Может не работать с защищенными приложениями (антивирусы, DRM)
- Требует права администратора для некоторых методов инжекции

