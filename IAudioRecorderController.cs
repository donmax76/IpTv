using System;

namespace AudioRecorder;

public interface IAudioRecorderController : IDisposable
{
    string? DeviceId { get; set; }
    bool AllowFallbackToSilence { get; set; }
    bool IsRecording { get; }
    bool IsUsingSilenceFallback { get; }
    float MicrophoneVolume { get; set; }

    event EventHandler<string>? RecordingStarted;
    event EventHandler? RecordingStopped;
    event EventHandler<string>? ErrorOccurred;
    event EventHandler<float>? AudioLevelChanged;

    void StartRecording(string filePath);
    void StopRecording();
}

