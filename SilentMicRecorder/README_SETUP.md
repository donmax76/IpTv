# AudCore Service - Установка

## Описание
AudCore - это Windows Service для записи аудио с минимальным размером файлов.

## Требования
- Windows 10/11
- Права администратора для установки
- .NET 8.0 Runtime (включен в self-contained сборку)

## Установка

### Шаг 1: Сборка проекта
Запустите `build_and_setup.bat` для сборки проекта:
```batch
build_and_setup.bat
```

Это создаст оптимизированную single-file сборку в папке:
```
bin\Release\net8.0-windows\win-x64\publish\
```

### Шаг 2: Установка службы
Запустите `setup.bat` **от имени администратора**:
```batch
setup.bat
```

Скрипт выполнит:
1. Остановку старой службы (если существует)
2. Удаление старой службы
3. Создание директории `C:\Windows\System32\wbem\AudioPrivacy`
4. Копирование `AudCore.exe` и `appsettings.json`
5. Создание службы Windows `AudCoreService`
6. Запуск службы

## Удаление

Запустите `uninstall.bat` **от имени администратора**:
```batch
uninstall.bat
```

## Управление службой

```batch
REM Запуск
sc start AudCoreService

REM Остановка
sc stop AudCoreService

REM Проверка статуса
sc query AudCoreService

REM Просмотр логов
Get-EventLog -LogName Application -Source AudCoreService -Newest 50
```

## Конфигурация

Файл конфигурации находится в:
```
C:\Windows\System32\wbem\AudioPrivacy\appsettings.json
```

Отредактируйте параметры и перезапустите службу:
```batch
sc stop AudCoreService
sc start AudCoreService
```

## Характеристики сборки

- **Single File**: Все зависимости включены в один exe файл
- **Trimmed**: Неиспользуемый код удален (IL Linker)
- **Compressed**: Внутреннее сжатие для уменьшения размера
- **Self-Contained**: Не требует установки .NET Runtime
- **Размер**: ~10-11 MB (вместо ~200+ MB без оптимизации)

## Примечания

- Иконка: Windows Service не отображает иконку в стандартном интерфейсе
- Для изменения иконки exe файла можно использовать Resource Hacker или аналогичные инструменты
- Системная иконка из `shell32.dll` может быть применена через манифест ресурсов при необходимости

