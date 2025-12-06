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
        private const string OutputPath = @"C:\SilentRecord\record.wav";

        // P/Invoke (все функции с правильными сигнатурами)
        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInOpen(out IntPtr lphWaveIn, uint uDeviceID, ref WaveFormat lpFormat, IntPtr dwCallback, IntPtr dwInstance, uint dwFlags);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInPrepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInAddBuffer(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInStart(IntPtr hWaveIn);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInStop(IntPtr hWaveIn);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInReset(IntPtr hWaveIn);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInUnprepareHeader(IntPtr hWaveIn, ref WaveHdr lpWaveInHdr, uint cbWaveHdr);

        [DllImport("winmm.dll", SetLastError = true)]
        private static extern uint waveInClose(IntPtr hWaveIn);

        // Структуры (точно как в winmm.h)
        [StructLayout(LayoutKind.Sequential)]
        private struct WaveFormat
        {
            public ushort wFormatTag;
            public ushort nChannels;
            public uint nSamplesPerSec;
            public uint nAvgBytesPerSec;
            public ushort nBlockAlign;
            public ushort wBitsPerSample;
            public ushort cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WaveHdr
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

        const uint WAVE_MAPPER = 0xFFFFFFFF;
        const uint CALLBACK_NULL = 0;

        protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(OutputPath)!);

            while (!stoppingToken.IsCancellationRequested)
            {
                Record10Seconds();
                await Task.Delay(100, stoppingToken);
            }
        }

        private void Record10Seconds()
        {
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

            uint result = waveInOpen(out hWaveIn, WAVE_MAPPER, ref format, IntPtr.Zero, IntPtr.Zero, CALLBACK_NULL);
            if (result != 0 || hWaveIn == IntPtr.Zero) return;

            byte[] buffer = new byte[48000 * 2 * 10]; // 10 секунд
            GCHandle gch = GCHandle.Alloc(buffer, GCHandleType.Pinned);

            WaveHdr hdr = new WaveHdr
            {
                lpData = gch.AddrOfPinnedObject(),
                dwBufferLength = (uint)buffer.Length,
                dwFlags = 0
            };

            result = waveInPrepareHeader(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());
            if (result != 0) goto cleanup;

            result = waveInAddBuffer(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());
            if (result != 0) goto cleanup;

            result = waveInStart(hWaveIn);
            if (result != 0) goto cleanup;

            Thread.Sleep(10000); // 10 секунд

            waveInStop(hWaveIn);
            waveInReset(hWaveIn);

            string finalFile = OutputPath.Replace(".wav", $"_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.wav");

            using (FileStream fs = new FileStream(finalFile, FileMode.Create))
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

        cleanup:
            waveInUnprepareHeader(hWaveIn, ref hdr, (uint)Marshal.SizeOf<WaveHdr>());
            waveInClose(hWaveIn);
            if (gch.IsAllocated) gch.Free();
        }
    }
}