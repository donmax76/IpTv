# Настройка проекта MicBypassHook

## Проблема: `#include <detours.h>` не найден

В актуальной версии проекта библиотека **Microsoft Detours 4.0.1** уже включена (`third_party/Detours`). Файл `detours.h` находится по пути `third_party/Detours/src/detours.h`.

Если вы видите ошибку, убедитесь, что открыта последняя версия решения `MicBypassHook.sln`. В проекте уже прописаны все необходимые пути.

## Обновление Detours (опционально)

Если нужно заменить Detours на другую версию:

1. Удалите содержимое папки `MicBypassHook/third_party/Detours`
2. Склонируйте новую версию из репозитория Microsoft:
   ```cmd
   git clone https://github.com/microsoft/Detours.git --branch v4.0.1 --depth 1 tmp_detours
   ```
3. Скопируйте содержимое `tmp_detours` в `third_party/Detours`
4. Удалите временную папку `tmp_detours`
5. Убедитесь, что все `.cpp` файлы лежат в `third_party/Detours/src`

После обновления пересоберите проект:
```cmd
msbuild MicBypassHook.sln /p:Configuration=Release /p:Platform=x64
```

## Проверка в Visual Studio

1. Откройте `MicBypassHook.sln`
2. Правый клик на проекте → Properties
3. Убедитесь, что `Configuration: All Configurations`, `Platform: x64`
4. Configuration Properties → C/C++ → General → `Additional Include Directories` содержит `$(ProjectDir)third_party\Detours\src`
5. Configuration Properties → Linker → Input → `Additional Dependencies` достаточно оставить пустым (используем встроенные .cpp)

## После обновления

- Снова соберите проект (`Build > Build Solution`)
- Убедитесь, что `MicBypassHook.dll` находится в `bin\x64\Release`
- Выполните инжекцию DLL в целевой процесс

