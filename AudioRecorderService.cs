using System;
using System.IO;
using System.Timers;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Lame;
using Timer = System.Timers.Timer;

namespace AudioRecorder
{
    /// <summary>
    /// Сервис для записи аудио с микрофона (WASAPI)
    /// </summary>
    public class AudioRecorderService : IAudioRecorderController
    {
        private WasapiCapture? wasapiCapture;
        private WaveFileWriter? waveFileWriter;
        private LameMP3FileWriter? mp3Writer;
        private Stream? mp3Stream;
        private string? outputFilePath;
        private bool isRecording;
        private bool useSilenceFallback;
        private Timer? silenceTimer;
        private WaveFormat? silenceWaveFormat;
        private long totalBytesReceived = 0;
        private int dataAvailableEventsCount = 0;
        private MMDevice? currentDevice;
        private float microphoneVolume = 1.0f;

        public string? DeviceId { get; set; }
        public bool AllowFallbackToSilence { get; set; }
        public bool IsUsingSilenceFallback => useSilenceFallback;
        public float MicrophoneVolume 
        { 
            get => microphoneVolume;
            set 
            { 
                microphoneVolume = Math.Max(0f, Math.Min(1f, value));
                if (currentDevice != null)
                {
                    try
                    {
                        currentDevice.AudioEndpointVolume.MasterVolumeLevelScalar = microphoneVolume;
                    }
                    catch { }
                }
            }
        }

        public event EventHandler<string>? RecordingStarted;
        public event EventHandler? RecordingStopped;
        public event EventHandler<string>? ErrorOccurred;
        public event EventHandler<float>? AudioLevelChanged; // Событие для визуализации уровня звука

        public bool IsRecording => isRecording;
        public string? CurrentFilePath => outputFilePath;

        public void StartRecording(string filePath)
        {
            AppLogger.Log($"StartRecording requested. Path: {filePath}");

            if (isRecording)
            {
                AppLogger.Log("StartRecording aborted: already recording");
                throw new InvalidOperationException("Запись уже идет");
            }

            try
            {
                outputFilePath = filePath;
                totalBytesReceived = 0;
                dataAvailableEventsCount = 0;

                AppLogger.Log("Creating MMDeviceEnumerator");
                MMDeviceEnumerator enumerator = new MMDeviceEnumerator();
                MMDevice? device = null;
                
                System.Threading.Thread.Sleep(100);
                
                device = TryGetRequestedDevice(enumerator);
                AppLogger.Log(device != null
                    ? $"Device acquired: {device.FriendlyName} ({device.State})"
                    : "No capture device acquired");

                if (device == null)
                {
                    if (AllowFallbackToSilence)
                    {
                        AppLogger.Log("Fallback to silence requested. Starting fallback mode");
                        StartSilenceFallback();
                        return;
                    }

                    throw new InvalidOperationException("Нет доступного активного устройства записи.");
                }

                useSilenceFallback = false;

                if (device.State != DeviceState.Active)
                {
                    AppLogger.Log($"Device is not active: {device.State}");
                    device.Dispose();
                    if (AllowFallbackToSilence)
                    {
                        StartSilenceFallback();
                        return;
                    }
                    throw new InvalidOperationException($"Выбранное устройство неактивно (состояние: {device.State}).");
                }

                currentDevice = device;
                AppLogger.Log("Setting microphone volume");
                
                try
                {
                    currentDevice.AudioEndpointVolume.MasterVolumeLevelScalar = microphoneVolume;
                }
                catch (Exception volumeEx)
                {
                    AppLogger.Log("Failed to set microphone volume", volumeEx);
                }

                try
                {
                    AppLogger.Log("Creating WasapiCapture");
                    wasapiCapture = new WasapiCapture(device)
                    {
                        ShareMode = AudioClientShareMode.Shared
                    };
                    AppLogger.Log("WasapiCapture created successfully");
                }
                catch (System.UnauthorizedAccessException)
                {
                    AppLogger.Log("Shared mode capture denied. Retrying after releasing device");
                    try
                    {
                        device.Dispose();
                        device = null;
                        System.Threading.Thread.Sleep(200);

                        device = TryGetRequestedDevice(enumerator);
                        AppLogger.Log(device != null
                            ? $"Device re-acquired: {device?.FriendlyName} ({device?.State})"
                            : "Device re-acquire failed");

                        if (device == null || device.State != DeviceState.Active)
                        {
                            throw new InvalidOperationException("Не удалось получить доступ к устройству записи. Убедитесь, что микрофон не используется другим приложением.");
                        }

                        currentDevice = device;
                        wasapiCapture = new WasapiCapture(device)
                        {
                            ShareMode = AudioClientShareMode.Shared
                        };
                        AppLogger.Log("WasapiCapture created successfully on retry");
                    }
                    catch (Exception ex2)
                    {
                        AppLogger.Log("WasapiCapture creation failed after retry", ex2);
                        string errorMsg = "Отказано в доступе к микрофону (0x80070005).\n\n";
                        errorMsg += "ВОЗМОЖНАЯ ПРИЧИНА: После отключения индикаторов приватности Windows заблокировал доступ.\n\n";
                        errorMsg += "РЕШЕНИЕ:\n";
                        errorMsg += "1. Запустите скрипт: PrivacyIndicatorsManager\\ENABLE_MICROPHONE_ACCESS.cmd (от администратора)\n";
                        errorMsg += "2. Или вручную: Параметры -> Конфиденциальность -> Микрофон -> Включите доступ\n";
                        errorMsg += "3. Перезапустите приложение\n\n";
                        errorMsg += $"Детали: {ex2.Message}";
                        throw new InvalidOperationException(errorMsg);
                    }
                }
                catch (Exception ex)
                {
                    AppLogger.Log("Unexpected error while creating WasapiCapture", ex);
                    throw new InvalidOperationException($"Ошибка доступа к микрофону: {ex.Message}. Проверьте настройки конфиденциальности Windows.");
                }

                try
                {
                    AppLogger.Log("Opening MP3 writer");
                    mp3Stream = new FileStream(outputFilePath!, FileMode.Create);
                    mp3Writer = new LameMP3FileWriter(mp3Stream, wasapiCapture.WaveFormat, LAMEPreset.STANDARD);
                }
                catch (Exception mp3Ex)
                {
                    AppLogger.Log("Failed to create MP3 writer", mp3Ex);
                    throw;
                }

                wasapiCapture.DataAvailable += OnDataAvailable;
                wasapiCapture.RecordingStopped += OnRecordingStopped;

                try
                {
                    AppLogger.Log("Starting WasapiCapture");
                    wasapiCapture.StartRecording();
                    AppLogger.Log("WasapiCapture.StartRecording() completed successfully");
                }
                catch (System.UnauthorizedAccessException ex)
                {
                    AppLogger.Log("StartRecording denied: unauthorized access", ex);
                    throw new InvalidOperationException("Отказано в доступе к микрофону. Проверьте настройки конфиденциальности Windows: Параметры -> Конфиденциальность -> Микрофон -> Разрешить приложениям доступ к микрофону.");
                }
                catch (System.AccessViolationException ex)
                {
                    AppLogger.Log("StartRecording: AccessViolationException (possible hook issue)", ex);
                    throw new InvalidOperationException($"Критическая ошибка доступа к памяти при запуске записи. Возможно, проблема с хуком. Детали: {ex.Message}");
                }
                catch (System.Runtime.InteropServices.SEHException ex)
                {
                    AppLogger.Log("StartRecording: SEHException (native crash)", ex);
                    throw new InvalidOperationException($"Ошибка в нативном коде при запуске записи. Возможно, проблема с хуком. Детали: {ex.Message}");
                }
                catch (Exception ex)
                {
                    AppLogger.Log("StartRecording: Unexpected exception", ex);
                    throw;
                }
                
                isRecording = true;
                AppLogger.Log("Recording started successfully");
                RecordingStarted?.Invoke(this, outputFilePath!);
            }
            catch (Exception ex)
            {
                AppLogger.Log("StartRecording threw exception", ex);
                CleanupResources();
                ErrorOccurred?.Invoke(this, $"Ошибка при начале записи: {ex.Message}");
                throw;
            }
        }

        public void StopRecording()
        {
            if (!isRecording)
            {
                AppLogger.Log("StopRecording called while not recording");
                return;
            }

            AppLogger.Log("StopRecording requested");

            try
            {
                if (useSilenceFallback)
                {
                    StopSilenceFallback();
                }
                else
                {
                    wasapiCapture?.StopRecording();
                    System.Threading.Thread.Sleep(100);
                }

                if (mp3Writer != null)
                {
                    mp3Writer.Flush();
                }
                if (mp3Stream != null)
                {
                    mp3Stream.Flush();
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("StopRecording threw exception", ex);
                ErrorOccurred?.Invoke(this, $"Ошибка при остановке записи: {ex.Message}");
                throw;
            }
            finally
            {
                isRecording = false;
            }
        }

        private void OnDataAvailable(object? sender, WaveInEventArgs e)
        {
            try
            {
                if (e.BytesRecorded > 0)
                {
                    totalBytesReceived += e.BytesRecorded;
                    dataAvailableEventsCount++;
                    
                    float maxLevel = 0f;
                    if (wasapiCapture?.WaveFormat?.Encoding == WaveFormatEncoding.IeeeFloat)
                    {
                        for (int i = 0; i < e.BytesRecorded; i += 4)
                        {
                            if (i + 3 < e.Buffer.Length)
                            {
                                float sample = BitConverter.ToSingle(e.Buffer, i);
                                float abs = Math.Abs(sample);
                                if (abs > maxLevel) maxLevel = abs;
                            }
                        }
                    }
                    else
                    {
                        for (int i = 0; i < e.BytesRecorded; i += 2)
                        {
                            if (i + 1 < e.Buffer.Length)
                            {
                                short sample = BitConverter.ToInt16(e.Buffer, i);
                                float normalized = Math.Abs(sample / 32768f);
                                if (normalized > maxLevel) maxLevel = normalized;
                            }
                        }
                    }
                    
                    AudioLevelChanged?.Invoke(this, maxLevel);
                    
                    if (mp3Writer != null)
                    {
                        mp3Writer.Write(e.Buffer, 0, e.BytesRecorded);
                    }
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("OnDataAvailable error", ex);
                ErrorOccurred?.Invoke(this, $"Ошибка при записи данных: {ex.Message}");
            }
        }

        private void OnRecordingStopped(object? sender, StoppedEventArgs e)
        {
            AppLogger.Log("WasapiCapture signaled RecordingStopped");

            if (mp3Writer != null)
            {
                try
                {
                    mp3Writer.Flush();
                    mp3Writer.Dispose();
                    mp3Writer = null;
                }
                catch (Exception ex)
                {
                    AppLogger.Log("Failed to dispose mp3Writer", ex);
                }
            }
            
            if (mp3Stream != null)
            {
                try
                {
                    mp3Stream.Flush();
                    mp3Stream.Dispose();
                    mp3Stream = null;
                }
                catch (Exception ex)
                {
                    AppLogger.Log("Failed to dispose mp3Stream", ex);
                }
            }

            CleanupResources();
            
            if (!string.IsNullOrEmpty(outputFilePath) && File.Exists(outputFilePath))
            {
                var fileInfo = new FileInfo(outputFilePath);
                if (fileInfo.Length < 1000)
                {
                    ErrorOccurred?.Invoke(this, $"Внимание: Записанный файл очень мал ({fileInfo.Length} байт). Возможно, микрофон не получал данные.");
                }
                
                if (totalBytesReceived == 0 && !useSilenceFallback)
                {
                    ErrorOccurred?.Invoke(this, $"Внимание: Микрофон не передал данные (событий получения данных: {dataAvailableEventsCount}). Проверьте, что микрофон включен и имеет разрешения.");
                }
            }

            RecordingStopped?.Invoke(this, EventArgs.Empty);

            if (e.Exception != null)
            {
                ErrorOccurred?.Invoke(this, $"Ошибка во время записи: {e.Exception.Message}");
            }
        }

        private void StartSilenceFallback()
        {
            silenceWaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(44100, 1);
            mp3Stream = new FileStream(outputFilePath!, FileMode.Create);
            mp3Writer = new LameMP3FileWriter(mp3Stream, silenceWaveFormat, LAMEPreset.STANDARD);

            useSilenceFallback = true;
            isRecording = true;

            var buffer = new byte[silenceWaveFormat.AverageBytesPerSecond / 10]; // ~100мс тишины

            silenceTimer = new Timer(100);
            silenceTimer.AutoReset = true;
            silenceTimer.Elapsed += (_, _) =>
            {
                if (mp3Writer != null)
                {
                    mp3Writer.Write(buffer, 0, buffer.Length);
                }
            };
            silenceTimer.Start();

            RecordingStarted?.Invoke(this, outputFilePath!);
        }

        private void StopSilenceFallback()
        {
            useSilenceFallback = false;

            if (silenceTimer != null)
            {
                silenceTimer.Stop();
                silenceTimer.Dispose();
                silenceTimer = null;
            }

            if (mp3Writer != null)
            {
                try
                {
                    mp3Writer.Flush();
                    mp3Writer.Dispose();
                    mp3Writer = null;
                }
                catch (Exception ex)
                {
                    AppLogger.Log("Failed to dispose mp3Writer during StopSilenceFallback", ex);
                }
            }
            
            if (mp3Stream != null)
            {
                try
                {
                    mp3Stream.Flush();
                    mp3Stream.Dispose();
                    mp3Stream = null;
                }
                catch (Exception ex)
                {
                    AppLogger.Log("Failed to dispose mp3Stream during StopSilenceFallback", ex);
                }
            }

            CleanupResources();
            RecordingStopped?.Invoke(this, EventArgs.Empty);
        }

        private MMDevice? TryGetRequestedDevice(MMDeviceEnumerator enumerator)
        {
            MMDevice? device = null;

            if (!string.IsNullOrEmpty(DeviceId))
            {
                try
                {
                    device = enumerator.GetDevice(DeviceId);
                    if (device != null && device.State != DeviceState.Active)
                    {
                        device.Dispose();
                        device = null;
                    }
                }
                catch (Exception)
                {
                    device = null;
                }
            }

            if (device == null)
            {
                try
                {
                    var defaultDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Console);
                    if (defaultDevice != null && defaultDevice.State == DeviceState.Active)
                    {
                        device = defaultDevice;
                    }
                    else if (defaultDevice != null)
                    {
                        defaultDevice.Dispose();
                    }
                }
                catch (Exception)
                {
                    device = null;
                }
            }

            return device;
        }

        private void CleanupResources()
        {
            AppLogger.Log("CleanupResources invoked");

            try
            {
                wasapiCapture?.Dispose();
            }
            catch (Exception ex)
            {
                AppLogger.Log("Failed to dispose WasapiCapture", ex);
            }
            wasapiCapture = null;

            try
            {
                waveFileWriter?.Dispose();
            }
            catch (Exception ex)
            {
                AppLogger.Log("Failed to dispose waveFileWriter", ex);
            }
            waveFileWriter = null;

            try
            {
                mp3Writer?.Dispose();
            }
            catch (Exception ex)
            {
                AppLogger.Log("Failed to dispose mp3Writer during cleanup", ex);
            }
            mp3Writer = null;

            try
            {
                mp3Stream?.Dispose();
            }
            catch (Exception ex)
            {
                AppLogger.Log("Failed to dispose mp3Stream during cleanup", ex);
            }
            mp3Stream = null;

            try
            {
                currentDevice?.Dispose();
            }
            catch (Exception ex)
            {
                AppLogger.Log("Failed to dispose currentDevice", ex);
            }
            currentDevice = null;
        }

        public void Dispose()
        {
            if (isRecording)
            {
                StopRecording();
            }

            CleanupResources();
        }
    }
}

