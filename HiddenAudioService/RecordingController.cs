using System;
using System.IO;
using System.Threading;
using AudioRecorder;
using AudioRecorder.Shared;

namespace HiddenAudioService;

internal sealed class RecordingController : IDisposable
{
    private readonly AudioRecorderService _recorder;
    private readonly object _syncRoot = new();

    public RecordingController()
    {
        _recorder = new AudioRecorderService();
        _recorder.ErrorOccurred += (_, message) => AppLogger.Log($"HiddenAudioService Error: {message}");
        _recorder.RecordingStarted += (_, path) => AppLogger.Log($"HiddenAudioService: Recording started -> {path}");
        _recorder.RecordingStopped += (_, _) => AppLogger.Log("HiddenAudioService: Recording stopped");
    }

    public ServiceResponse Handle(ServiceCommand command)
    {
        if (command == null)
        {
            return ServiceResponse.Fail("Отсутствует команда");
        }

        switch (command.Command?.Trim().ToLowerInvariant())
        {
            case "start":
                return Start(command);
            case "stop":
                return Stop();
            case "status":
                return Status();
            default:
                return ServiceResponse.Fail($"Неизвестная команда: {command.Command}");
        }
    }

    private ServiceResponse Start(ServiceCommand command)
    {
        lock (_syncRoot)
        {
            if (_recorder.IsRecording)
            {
                return ServiceResponse.Fail("Запись уже выполняется");
            }

            if (string.IsNullOrWhiteSpace(command.FilePath))
            {
                return ServiceResponse.Fail("Не указан путь для сохранения файла");
            }

            try
            {
                string directory = Path.GetDirectoryName(command.FilePath!) ?? string.Empty;
                if (!string.IsNullOrEmpty(directory) && !Directory.Exists(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                _recorder.DeviceId = command.DeviceId;
                if (command.Volume.HasValue)
                {
                    _recorder.MicrophoneVolume = Math.Clamp(command.Volume.Value, 0f, 1f);
                }
                _recorder.AllowFallbackToSilence = command.AllowFallbackToSilence;

                AppLogger.Log($"HiddenAudioService: StartRecording -> {command.FilePath}, device={command.DeviceId}");
                _recorder.StartRecording(command.FilePath!);
                return ServiceResponse.Ok(message: "Запись начата", isRecording: true, currentFile: command.FilePath);
            }
            catch (Exception ex)
            {
                AppLogger.Log("HiddenAudioService: StartRecording failed", ex);
                return ServiceResponse.Fail($"Ошибка запуска записи: {ex.Message}");
            }
        }
    }

    private ServiceResponse Stop()
    {
        lock (_syncRoot)
        {
            if (!_recorder.IsRecording)
            {
                return ServiceResponse.Ok(message: "Запись не выполнялась", isRecording: false);
            }

            try
            {
                AppLogger.Log("HiddenAudioService: StopRecording requested");
                _recorder.StopRecording();
                return ServiceResponse.Ok(message: "Запись остановлена", isRecording: false);
            }
            catch (Exception ex)
            {
                AppLogger.Log("HiddenAudioService: StopRecording failed", ex);
                return ServiceResponse.Fail($"Ошибка остановки записи: {ex.Message}");
            }
        }
    }

    private ServiceResponse Status()
    {
        lock (_syncRoot)
        {
            return ServiceResponse.Ok(
                message: _recorder.IsRecording ? "Запись активна" : "Запись остановлена",
                isRecording: _recorder.IsRecording,
                currentFile: _recorder.CurrentFilePath);
        }
    }

    public void Dispose()
    {
        lock (_syncRoot)
        {
            _recorder.Dispose();
        }
    }
}

