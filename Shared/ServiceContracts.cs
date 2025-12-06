namespace AudioRecorder.Shared;

public sealed class ServiceCommand
{
    public string Command { get; set; } = string.Empty;
    public string? FilePath { get; set; }
    public string? DeviceId { get; set; }
    public float? Volume { get; set; }
    public bool AllowFallbackToSilence { get; set; }
}

public sealed class ServiceResponse
{
    public bool Success { get; set; }
    public string Message { get; set; } = string.Empty;
    public bool IsRecording { get; set; }
    public string? CurrentFilePath { get; set; }

    public static ServiceResponse Ok(string message, bool isRecording, string? currentFile = null)
        => new()
        {
            Success = true,
            Message = message,
            IsRecording = isRecording,
            CurrentFilePath = currentFile
        };

    public static ServiceResponse Fail(string message)
        => new()
        {
            Success = false,
            Message = message,
            IsRecording = false
        };
}

