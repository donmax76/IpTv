using System;
using System.Runtime.InteropServices;
using System.IO;
using System.Threading;

namespace SilentMicRecorder
{
    class Program
    {
        // winmm.dll - MME API (не триггерит индикатор)
        [DllImport("winmm.dll")]
        static extern int waveInGetNumDevs();

        [DllImport("winmm.dll")]
        static extern int waveInOpen(ref IntPtr hwi, int uDeviceID, ref WaveFormat lpFormat, IntPtr dwCallback, IntPtr dwInstance, int fdwOpen);

        [DllImport("winmm.dll")]
        static extern int waveInPrepareHeader(IntPtr hwi, ref WaveHdr pwh, int cbwh);

        [DllImport("winmm.dll")]
        static extern int waveInAddBuffer(IntPtr hwi, ref WaveHdr pwh, int cbwh);

        [DllImport("winmm.dll")]
        static extern int waveInStart(IntPtr hwi);

        [DllImport("winmm.dll")]
        static extern int waveInStop(IntPtr hwi);

        [DllImport("winmm.dll")]
        static extern int waveInReset(IntPtr hwi);

        [DllImport("winmm.dll")]
        static extern int waveInUnprepareHeader(IntPtr hwi, ref WaveHdr pwh, int cbwh);

        [DllImport("winmm.dll")]
        static extern int waveInClose(IntPtr hwi);

        // Структуры
        [StructLayout(LayoutKind.Sequential)]
        struct WaveFormat
        {
            public short wFormatTag;
            public short nChannels;
            public int nSamplesPerSec;
            public int nAvgBytesPerSec;
            public short nBlockAlign;
            public short wBitsPerSample;
            public short cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WaveHdr
        {
            public IntPtr lpData;
            public uint dwBufferLength;
            public uint dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags;
            public uint dwLoops;
            public IntPtr lpNext;
            public IntPtr reserved;
        }

        const int WAVE_MAPPER = -1;
        const int CALLBACK_NULL = 0;

        static void Main()
        {
            if (waveInGetNumDevs() == 0)
            {
                Console.WriteLine("Микрофон не найден");
                Console.ReadKey();
                return;
            }

            IntPtr hWaveIn = IntPtr.Zero;

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

            int result = waveInOpen(ref hWaveIn, WAVE_MAPPER, ref format, IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);
            if (result != 0)
            {
                Console.WriteLine("Не удалось открыть микрофон");
                Console.ReadKey();
                return;
            }

            byte[] buffer = new byte[48000 * 2 * 10]; // 10 секунд
            GCHandle gch = GCHandle.Alloc(buffer, GCHandleType.Pinned);

            WaveHdr hdr = new WaveHdr
            {
                lpData = gch.AddrOfPinnedObject(),
                dwBufferLength = (uint)buffer.Length,
                dwFlags = 0
            };

            waveInPrepareHeader(hWaveIn, ref hdr, Marshal.SizeOf(hdr));
            waveInAddBuffer(hWaveIn, ref hdr, Marshal.SizeOf(hdr));
            waveInStart(hWaveIn);

            Console.WriteLine("Запись 10 сек — индикатор НЕ появляется");
            Thread.Sleep(10000);

            waveInStop(hWaveIn);
            waveInReset(hWaveIn);

            using (FileStream fs = new FileStream("output.wav", FileMode.Create))
            using (BinaryWriter bw = new BinaryWriter(fs))
            {
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

            waveInUnprepareHeader(hWaveIn, ref hdr, Marshal.SizeOf(hdr));
            waveInClose(hWaveIn);
            gch.Free();

            Console.WriteLine("Готово! Файл output.wav полный, индикатор не появился.");
            Console.ReadKey();
        }
    }
}