using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace TrayInspector;

internal static class Program
{
    private static void Main(string[] args)
    {
        var argsDict = ParseArgs(args);
        string outputPath = argsDict.TryGetValue("output", out var path)
            ? Path.GetFullPath(path)
            : Path.Combine(Environment.CurrentDirectory, $"tray_{DateTime.Now:HHmmss}.log");

        var sb = new StringBuilder();
        sb.AppendLine($"TrayInspector dump at {DateTime.Now:O}");
        sb.AppendLine();

        var targetProcesses = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "explorer.exe",
            "ShellExperienceHost.exe"
        };

        Native.EnumWindows((hWnd, lParam) =>
        {
            DumpWindow(hWnd, 0, sb, targetProcesses);
            return true;
        }, IntPtr.Zero);

        File.WriteAllText(outputPath, sb.ToString(), Encoding.UTF8);
        Console.WriteLine($"Dump saved to {outputPath}");
    }

    private static void DumpWindow(IntPtr hWnd, int indent, StringBuilder sb, HashSet<string> targets)
    {
        uint pid;
        Native.GetWindowThreadProcessId(hWnd, out pid);
        if (pid == 0) return;

        string processName;
        try
        {
            processName = Process.GetProcessById((int)pid).ProcessName + ".exe";
        }
        catch
        {
            return;
        }

        bool include = targets.Contains(processName);
        if (!include && indent == 0)
        {
            return;
        }

        string className = GetClassName(hWnd);
        string text = GetWindowText(hWnd);
        bool visible = Native.IsWindowVisible(hWnd);
        var rect = Native.GetWindowRectManaged(hWnd);

        sb.AppendLine($"{new string(' ', indent * 2)}Handle=0x{hWnd.ToInt64():X} Class='{className}' Text='{text}' Visible={visible} PID={pid} Proc={processName} Rect=({rect.left},{rect.top})-({rect.right},{rect.bottom})");

        Native.EnumChildWindows(hWnd, (child, lParam) =>
        {
            DumpWindow(child, indent + 1, sb, targets);
            return true;
        }, IntPtr.Zero);
    }

    private static string GetClassName(IntPtr hWnd)
    {
        Span<char> buffer = stackalloc char[256];
        int length = Native.GetClassNameW(hWnd, buffer, buffer.Length);
        return length > 0 ? new string(buffer[..length]) : string.Empty;
    }

    private static string GetWindowText(IntPtr hWnd)
    {
        int length = Native.GetWindowTextLengthW(hWnd);
        if (length == 0) return string.Empty;
        Span<char> buffer = stackalloc char[length + 1];
        Native.GetWindowTextW(hWnd, buffer, buffer.Length);
        return new string(buffer[..Math.Min(buffer.Length - 1, length)]);
    }

    private static Dictionary<string, string> ParseArgs(string[] args)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var arg in args)
        {
            if (!arg.StartsWith("--")) continue;
            var parts = arg[2..].Split('=', 2);
            if (parts.Length == 2)
            {
                dict[parts[0]] = parts[1];
            }
        }
        return dict;
    }
}

internal static class Native
{
    internal delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    internal static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    internal static extern bool EnumChildWindows(IntPtr hWnd, EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    internal static extern int GetClassNameW(IntPtr hWnd, Span<char> lpClassName, int nMaxCount);

    [DllImport("user32.dll")]
    internal static extern int GetWindowTextLengthW(IntPtr hWnd);

    [DllImport("user32.dll")]
    internal static extern int GetWindowTextW(IntPtr hWnd, Span<char> lpString, int nMaxCount);

    [DllImport("user32.dll")]
    internal static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    internal static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    internal static RECT GetWindowRectManaged(IntPtr hWnd)
    {
        GetWindowRect(hWnd, out var rect);
        return rect;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct RECT
    {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }
}
