using System.Globalization;
using AudioRecorder;

AppLogger.Initialize();

if (args.Length == 0)
{
    PrintUsage();
    return;
}

string command = args[0].ToLowerInvariant();
string? optionalPath = args.Length > 1 && !args[1].StartsWith("--") ? args[1] : null;

var options = ParseOptions(args);

try
{
    using var client = new HiddenAudioRecorderClient();

    switch (command)
    {
        case "start":
            HandleStart(client, optionalPath, options);
            break;
        case "stop":
            HandleStop(client);
            break;
        case "status":
            HandleStatus(client);
            break;
        case "logs":
            TailFile(AppLogger.LogPath);
            break;
        case "hooklog":
            string hookPath = Path.Combine(Path.GetTempPath(), "MicBypassHook.log");
            TailFile(hookPath);
            break;
        default:
            PrintUsage();
            break;
    }
}
catch (Exception ex)
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine($"Ошибка: {ex.Message}");
    Console.ResetColor();
}

static void HandleStart(HiddenAudioRecorderClient client, string? optionalPath, Dictionary<string, string> options)
{
    string targetPath = optionalPath ?? GenerateDefaultPath();

    if (options.TryGetValue("device", out var device))
    {
        client.DeviceId = device;
        Console.WriteLine($"Устройство: {device}");
    }

    if (options.TryGetValue("volume", out var volumeRaw) && float.TryParse(volumeRaw, NumberStyles.Float, CultureInfo.InvariantCulture, out var vol))
    {
        client.MicrophoneVolume = Math.Clamp(vol, 0f, 1f);
        Console.WriteLine($"Громкость: {client.MicrophoneVolume:P0}");
    }

    client.AllowFallbackToSilence = options.ContainsKey("fallback");

    Console.WriteLine($"Начинаю запись: {targetPath}");
    client.RecordingStarted += (_, path) =>
    {
        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine($"[Recorder] Recording started -> {path}");
        Console.ResetColor();
    };
    client.ErrorOccurred += (_, msg) =>
    {
        Console.ForegroundColor = ConsoleColor.Red;
        Console.WriteLine($"[Recorder] Ошибка: {msg}");
        Console.ResetColor();
    };
    client.StartRecording(targetPath);
}

static void HandleStop(HiddenAudioRecorderClient client)
{
    client.RecordingStopped += (_, _) =>
    {
        Console.ForegroundColor = ConsoleColor.Yellow;
        Console.WriteLine("[Recorder] Запись остановлена");
        Console.ResetColor();
    };
    client.StopRecording();
}

static void HandleStatus(HiddenAudioRecorderClient client)
{
    var status = client.QueryStatus();
    Console.WriteLine($"Success: {status.Success}");
    Console.WriteLine($"Message: {status.Message}");
    Console.WriteLine($"IsRecording: {status.IsRecording}");
    Console.WriteLine($"CurrentFile: {status.CurrentFilePath}");
}

static void PrintUsage()
{
    Console.WriteLine("RecorderConsole usage:");
    Console.WriteLine("  start [filePath] [--device=id] [--volume=0.8] [--fallback]");
    Console.WriteLine("  stop");
    Console.WriteLine("  status");
    Console.WriteLine("  logs        # tail AudioRecorder.log");
    Console.WriteLine("  hooklog     # tail MicBypassHook.log");
}

static Dictionary<string, string> ParseOptions(string[] args)
{
    var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    foreach (var arg in args)
    {
        if (!arg.StartsWith("--"))
            continue;

        var parts = arg.Substring(2).Split('=', 2);
        if (parts.Length == 1)
        {
            dict[parts[0]] = string.Empty;
        }
        else
        {
            dict[parts[0]] = parts[1];
        }
    }
    return dict;
}

static void TailFile(string path, int lines = 40)
{
    if (!File.Exists(path))
    {
        Console.WriteLine($"Файл не найден: {path}");
        return;
    }

    var allLines = File.ReadAllLines(path);
    foreach (var line in allLines.Skip(Math.Max(0, allLines.Length - lines)))
    {
        Console.WriteLine(line);
    }
}

static string GenerateDefaultPath()
{
    string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "AudioRecordings");
    Directory.CreateDirectory(folder);
    string fileName = $"Recording_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.mp3";
    return Path.Combine(folder, fileName);
}
