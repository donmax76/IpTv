using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AudioRecorder
{
    static class Program
    {
        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr LoadLibrary(string lpFileName);

        private static void LoadMicBypassHook()
        {
            try
            {
                string baseDirectory = AppDomain.CurrentDomain.BaseDirectory;
                string dllPath = Path.Combine(baseDirectory, "MicBypassHook.dll");
                AppLogger.Log($"Attempting to load MicBypassHook from {dllPath}");

                if (!File.Exists(dllPath))
                {
                    string parentPath = Path.Combine(baseDirectory, "..", "MicBypassHook", "bin", "x64", "Release", "MicBypassHook.dll");
                    parentPath = Path.GetFullPath(parentPath);
                    AppLogger.Log($"Primary DLL not found, checking fallback path: {parentPath}");
                    if (File.Exists(parentPath))
                    {
                        dllPath = parentPath;
                    }
                    else
                    {
                        AppLogger.Log("MicBypassHook DLL not found. Hook will not be active.");
                        return;
                    }
                }

                System.Threading.Thread.Sleep(100);

                IntPtr handle = LoadLibrary(dllPath);
                if (handle == IntPtr.Zero)
                {
                    int error = Marshal.GetLastWin32Error();
                    AppLogger.Log($"MicBypassHook: failed to load DLL (error {error})");
                }
                else
                {
                    AppLogger.Log($"MicBypassHook loaded successfully from {dllPath}");
                    System.Threading.Thread.Sleep(200);
                }
            }
            catch (Exception ex)
            {
                AppLogger.Log("MicBypassHook load exception", ex);
            }
        }

        /// <summary>
        /// Главная точка входа для приложения.
        /// </summary>
        [STAThread]
        static void Main()
        {
            AppLogger.Initialize();
            AppLogger.Log("Application entry point reached");

            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);

            AppDomain.CurrentDomain.UnhandledException += (sender, args) =>
            {
                if (args.ExceptionObject is Exception ex)
                {
                    AppLogger.Log("AppDomain unhandled exception", ex);
                }
                else
                {
                    AppLogger.Log($"AppDomain unhandled non-exception object: {args.ExceptionObject}");
                }
            };

            Application.ThreadException += (sender, args) =>
            {
                AppLogger.Log("UI thread exception", args.Exception);
            };

            TaskScheduler.UnobservedTaskException += (sender, args) =>
            {
                AppLogger.Log("Unobserved task exception", args.Exception);
                args.SetObserved();
            };

            LoadMicBypassHook();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            AppLogger.Log("Starting MainForm");
            Application.Run(new MainForm());
        }
    }
}

