using System;
using System.IO;
using System.Text;
using System.Threading;

namespace AudioRecorder
{
    internal static class AppLogger
    {
        private static readonly object SyncRoot = new();
        private static string _logPath = Path.Combine(Path.GetTempPath(), "AudioRecorder.log");
        private static bool _initialized;

        public static string LogPath => _logPath;

        public static void Initialize()
        {
            if (_initialized)
            {
                return;
            }

            lock (SyncRoot)
            {
                if (_initialized)
                {
                    return;
                }

                try
                {
                    _logPath = Path.Combine(Path.GetTempPath(), "AudioRecorder.log");
                    using var writer = new StreamWriter(_logPath, append: true, Encoding.UTF8);
                    writer.WriteLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] --- Application started (PID {Environment.ProcessId}) ---");
                }
                catch
                {
                    // Игнорируем ошибки логирования
                }

                _initialized = true;
            }
        }

        public static void Log(string message)
        {
            if (!_initialized)
            {
                Initialize();
            }

            try
            {
                lock (SyncRoot)
                {
                    using var writer = new StreamWriter(_logPath, append: true, Encoding.UTF8);
                    writer.WriteLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {message}");
                }
            }
            catch
            {
                // Поглощаем любые ошибки логирования, чтобы не мешать приложению
            }
        }

        public static void Log(string message, Exception ex)
        {
            Log($"{message}: {ex}");
        }

        public static void Log(Exception ex)
        {
            Log($"Unhandled exception: {ex}");
        }
    }
}
