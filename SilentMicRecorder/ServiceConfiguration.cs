namespace AudioCore
{
    public class ServiceConfiguration
    {
        public int SegmentDurationSeconds { get; set; }
        public string OutputFolder { get; set; } = string.Empty;
        public int SampleRate { get; set; }
        public int BitsPerSample { get; set; }
        public int Channels { get; set; }
        public double MicrophoneVolume { get; set; } = 1.0;
        /// <summary>
        /// Код формата аудио: 0 = WAV, 1 = MP3.
        /// </summary>
        public int AudioFormatCode { get; set; } = 0;
        /// <summary>
        /// Включить шифрование записанных аудиофайлов (0 = выкл, 1 = вкл).
        /// </summary>
        public bool EncryptAudio { get; set; } = false;
    }
}