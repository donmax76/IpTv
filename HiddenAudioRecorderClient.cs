using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using AudioRecorder.Shared;

namespace AudioRecorder;

internal sealed class HiddenAudioRecorderClient : IAudioRecorderController
{
    private const string PipeName = "AudioRecorderHiddenService";
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = false
    };

    private NamedPipeClientStream? _pipe;
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private Process? _serviceProcess;
    private readonly object _syncRoot = new();

    private string? _deviceId;
    private bool _allowFallback;
    private float _microphoneVolume = 1.0f;
    private bool _isRecording;
    private string? _currentFilePath;

    public string? DeviceId
    {
        get => _deviceId;
        set => _deviceId = value;
    }

    public bool AllowFallbackToSilence
    {
        get => _allowFallback;
        set => _allowFallback = value;
    }

    public bool IsRecording => _isRecording;
    public bool IsUsingSilenceFallback => false;

    public float MicrophoneVolume
    {
        get => _microphoneVolume;
        set => _microphoneVolume = Math.Clamp(value, 0f, 1f);
    }

    public event EventHandler<string>? RecordingStarted;
    public event EventHandler? RecordingStopped;
    public event EventHandler<string>? ErrorOccurred;
    public event EventHandler<float>? AudioLevelChanged;

    public void StartRecording(string filePath)
    {
        lock (_syncRoot)
        {
            EnsureConnection();
            var command = new ServiceCommand
            {
                Command = "start",
                FilePath = filePath,
                DeviceId = _deviceId,
                Volume = _microphoneVolume,
                AllowFallbackToSilence = _allowFallback
            };

            var response = SendCommand(command);
            if (!response.Success)
            {
                ErrorOccurred?.Invoke(this, response.Message);
                throw new InvalidOperationException(response.Message);
            }

            _isRecording = response.IsRecording;
            _currentFilePath = response.CurrentFilePath;
            RecordingStarted?.Invoke(this, _currentFilePath ?? filePath);
        }
    }

    public void StopRecording()
    {
        lock (_syncRoot)
        {
            if (!_isRecording)
            {
                return;
            }

            EnsureConnection();
            var response = SendCommand(new ServiceCommand { Command = "stop" });
            if (!response.Success)
            {
                ErrorOccurred?.Invoke(this, response.Message);
                throw new InvalidOperationException(response.Message);
            }

            _isRecording = false;
            RecordingStopped?.Invoke(this, EventArgs.Empty);
        }
    }

    public ServiceResponse QueryStatus()
    {
        lock (_syncRoot)
        {
            EnsureConnection();
            var response = SendCommand(new ServiceCommand { Command = "status" });
            _isRecording = response.IsRecording;
            _currentFilePath = response.CurrentFilePath;
            return response;
        }
    }

    private ServiceResponse SendCommand(ServiceCommand command)
    {
        if (_pipe == null || _reader == null || _writer == null)
        {
            throw new InvalidOperationException("Pipe connection is not established");
        }

        string json = JsonSerializer.Serialize(command, _jsonOptions);
        _writer.WriteLine(json);
        var line = _reader.ReadLine();
        if (string.IsNullOrWhiteSpace(line))
        {
            return ServiceResponse.Fail("Нет ответа от сервиса");
        }

        try
        {
            return JsonSerializer.Deserialize<ServiceResponse>(line, _jsonOptions)
                   ?? ServiceResponse.Fail("Неверный ответ от сервиса");
        }
        catch (Exception ex)
        {
            return ServiceResponse.Fail($"Ошибка разбора ответа сервиса: {ex.Message}");
        }
    }

    private void EnsureConnection()
    {
        if (_pipe is { IsConnected: true })
        {
            return;
        }

        EnsureServiceRunning();

        _pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.None);
        try
        {
            _pipe.Connect(3000);
        }
        catch (TimeoutException ex)
        {
            throw new InvalidOperationException("Не удалось подключиться к HiddenAudioService (таймаут)", ex);
        }

        _reader = new StreamReader(_pipe, Encoding.UTF8, leaveOpen: true);
        _writer = new StreamWriter(_pipe, new UTF8Encoding(false), leaveOpen: true)
        {
            AutoFlush = true
        };
    }

    private void EnsureServiceRunning()
    {
        if (_pipe != null && _pipe.IsConnected)
        {
            return;
        }

        if (_serviceProcess != null && !_serviceProcess.HasExited)
        {
            return;
        }

        string servicePath = FindServiceExecutable();
        if (!File.Exists(servicePath))
        {
            throw new FileNotFoundException("Не найден исполняемый файл HiddenAudioService", servicePath);
        }

        var psi = new ProcessStartInfo(servicePath)
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WorkingDirectory = Path.GetDirectoryName(servicePath) ?? Environment.CurrentDirectory
        };

        _serviceProcess = Process.Start(psi) ?? throw new InvalidOperationException("Не удалось запустить HiddenAudioService");
    }

    private static string FindServiceExecutable()
    {
        string baseDir = AppDomain.CurrentDomain.BaseDirectory;
        string candidate = Path.Combine(baseDir, "HiddenAudioService", "HiddenAudioService.exe");
        if (File.Exists(candidate))
        {
            return candidate;
        }

        string projectRoot = Path.GetFullPath(Path.Combine(baseDir, "..", "..", ".."));
        string binRoot = Path.Combine(projectRoot, "HiddenAudioService", "bin");
        if (Directory.Exists(binRoot))
        {
            var files = Directory.GetFiles(binRoot, "HiddenAudioService.exe", SearchOption.AllDirectories);
            if (files.Length > 0)
            {
                Array.Sort(files, (a, b) => File.GetLastWriteTime(b).CompareTo(File.GetLastWriteTime(a)));
                return files[0];
            }
        }

        return candidate;
    }

    public void Dispose()
    {
        try
        {
            if (_isRecording)
            {
                StopRecording();
            }
        }
        catch
        {
        }

        _reader?.Dispose();
        _writer?.Dispose();
        _pipe?.Dispose();

        if (_serviceProcess != null && !_serviceProcess.HasExited)
        {
            _serviceProcess.Kill(entireProcessTree: true);
            _serviceProcess.Dispose();
        }
    }
}

