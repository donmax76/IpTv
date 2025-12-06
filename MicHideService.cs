using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.IO;
using System.Threading;

namespace MicHideService
{
    class Program
    {
        // P/Invoke для инъекции
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr VirtualAllocEx(IntPtr hProcess, IntPtr lpAddress, uint dwSize, uint flAllocationType, uint flProtect);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool WriteProcessMemory(IntPtr hProcess, IntPtr lpBaseAddress, byte[] lpBuffer, uint nSize, out UIntPtr lpNumberOfBytesWritten);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr CreateRemoteThread(IntPtr hProcess, IntPtr lpThreadAttributes, uint dwStackSize, IntPtr lpStartAddress, IntPtr lpParameter, uint dwCreationFlags, IntPtr lpThreadId);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool CloseHandle(IntPtr hObject);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetModuleHandle(string lpModuleName);

        const uint PROCESS_ALL_ACCESS = 0x1F0FFF;
        const uint MEM_COMMIT = 0x00001000;
        const uint MEM_RESERVE = 0x00002000;
        const uint PAGE_EXECUTE_READWRITE = 0x40;

        static string dllPath = @"D:\Android_Projects\MicrophoneControlApp\MicHide.dll";  // Ваш путь к DLL

        static void Main(string[] args)
        {
            Console.WriteLine("Сервис скрытия индикатора микрофона запущен...");

            // Ждём explorer.exe
            Process explorer = null;
            while (explorer == null)
            {
                explorer = Process.GetProcessesByName("explorer").FirstOrDefault();
                if (explorer == null) Thread.Sleep(1000);
            }

            Console.WriteLine("Explorer.exe найден. Инъекция DLL...");
            if (InjectDll(explorer.Id, dllPath))
            {
                Console.WriteLine("Инъекция завершена. Сервис работает в фоне (Ctrl+C для выхода).");
            }
            else
            {
                Console.WriteLine("Инъекция не удалась.");
            }

            Console.ReadLine();  // Держим сервис запущенным
        }

        static bool InjectDll(int processId, string dllPath)
        {
            if (!File.Exists(dllPath))
            {
                Console.WriteLine($"DLL не найдена: {dllPath}");
                return false;
            }

            IntPtr procHandle = OpenProcess(PROCESS_ALL_ACCESS, false, processId);
            if (procHandle == IntPtr.Zero)
            {
                Console.WriteLine("Ошибка открытия процесса.");
                return false;
            }

            byte[] dllBytes = File.ReadAllBytes(dllPath);
            if (dllBytes.Length > 0x10000)  // Лимит 64KB
            {
                Console.WriteLine("DLL слишком большая.");
                CloseHandle(procHandle);
                return false;
            }

            IntPtr allocMem = VirtualAllocEx(procHandle, IntPtr.Zero, (uint)dllBytes.Length, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            if (allocMem == IntPtr.Zero)
            {
                CloseHandle(procHandle);
                Console.WriteLine("Ошибка аллокации памяти.");
                return false;
            }

            UIntPtr bytesWritten;
            if (!WriteProcessMemory(procHandle, allocMem, dllBytes, (uint)dllBytes.Length, out bytesWritten) || bytesWritten.ToUInt32() != dllBytes.Length)
            {
                CloseHandle(procHandle);
                Console.WriteLine("Ошибка записи в память.");
                return false;
            }

            IntPtr loadLibrary = GetProcAddress(GetModuleHandle("kernel32.dll"), "LoadLibraryA");
            if (loadLibrary == IntPtr.Zero)
            {
                CloseHandle(procHandle);
                Console.WriteLine("Ошибка LoadLibraryA.");
                return false;
            }

            IntPtr threadHandle = CreateRemoteThread(procHandle, IntPtr.Zero, 0, loadLibrary, allocMem, 0, IntPtr.Zero);
            if (threadHandle == IntPtr.Zero)
            {
                CloseHandle(procHandle);
                Console.WriteLine("Ошибка создания remote thread.");
                return false;
            }

            CloseHandle(threadHandle);
            CloseHandle(procHandle);
            Console.WriteLine("DLL инжектирована в explorer.exe.");
            return true;
        }
    }
}