param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ProcessArgument,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$DllPath
)

function Write-Info($message) { Write-Host $message -ForegroundColor Cyan }
function Write-Ok($message)   { Write-Host $message -ForegroundColor Green }
function Write-Err($message)  { Write-Host $message -ForegroundColor Red }

try {
    if (-not (Test-Path $DllPath)) {
        Write-Err "[ОШИБКА] DLL не найдена: $DllPath"
        exit 2
    }

    $dllFullPath = [System.IO.Path]::GetFullPath($DllPath)

    # Определяем имя процесса. Если передан путь, берем имя файла без расширения.
    if (Test-Path $ProcessArgument) {
        $processName = [System.IO.Path]::GetFileNameWithoutExtension($ProcessArgument)
    } else {
        $processName = [System.IO.Path]::GetFileNameWithoutExtension($ProcessArgument)
    }

    if ([string]::IsNullOrWhiteSpace($processName)) {
        Write-Err "[ОШИБКА] Некорректное имя процесса"
        exit 3
    }

    $process = Get-Process -Name $processName -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Err "[ОШИБКА] Процесс '$processName' не найден. Запустите приложение и повторите."
        exit 4
    }

    Write-Info "[ИНФО] Найден процесс $($process.ProcessName) (PID: $($process.Id))"
    Write-Info "[ИНФО] Полный путь DLL: $dllFullPath"

    $dllBytes = [System.Text.Encoding]::Unicode.GetBytes($dllFullPath + [char]0)

    $source = @"
using System;
using System.Runtime.InteropServices;

public static class NativeMethods
{
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr VirtualAllocEx(IntPtr hProcess, IntPtr lpAddress, uint dwSize, uint flAllocationType, uint flProtect);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool WriteProcessMemory(IntPtr hProcess, IntPtr lpBaseAddress, byte[] lpBuffer, uint nSize, out uint lpNumberOfBytesWritten);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr GetModuleHandle(string lpModuleName);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr CreateRemoteThread(IntPtr hProcess, IntPtr lpThreadAttributes, uint dwStackSize, IntPtr lpStartAddress, IntPtr lpParameter, uint dwCreationFlags, out IntPtr lpThreadId);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr hObject);
}
"@

    Add-Type -TypeDefinition $source -ErrorAction Stop | Out-Null

    $PROCESS_CREATE_THREAD = 0x0002
    $PROCESS_QUERY_INFORMATION = 0x0400
    $PROCESS_VM_OPERATION = 0x0008
    $PROCESS_VM_WRITE = 0x0020
    $PROCESS_VM_READ = 0x0010

    $MEM_COMMIT = 0x1000
    $MEM_RESERVE = 0x2000
    $PAGE_READWRITE = 0x04
    $INFINITE = [UInt32]::MaxValue

    $processHandle = [NativeMethods]::OpenProcess($PROCESS_CREATE_THREAD -bor $PROCESS_QUERY_INFORMATION -bor $PROCESS_VM_OPERATION -bor $PROCESS_VM_WRITE -bor $PROCESS_VM_READ, $false, $process.Id)
    if ($processHandle -eq [IntPtr]::Zero) {
        Write-Err "[ОШИБКА] Не удалось открыть процесс. Код ошибки: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
        exit 5
    }

    try {
        $remoteBuffer = [NativeMethods]::VirtualAllocEx($processHandle, [IntPtr]::Zero, [uint32]$dllBytes.Length, $MEM_RESERVE -bor $MEM_COMMIT, $PAGE_READWRITE)
        if ($remoteBuffer -eq [IntPtr]::Zero) {
            Write-Err "[ОШИБКА] VirtualAllocEx: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
            exit 6
        }

        $written = 0
        $writeResult = [NativeMethods]::WriteProcessMemory($processHandle, $remoteBuffer, $dllBytes, [uint32]$dllBytes.Length, [ref]$written)
        if (-not $writeResult -or $written -ne $dllBytes.Length) {
            Write-Err "[ОШИБКА] WriteProcessMemory: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
            exit 7
        }

        $kernel32 = [NativeMethods]::GetModuleHandle("kernel32.dll")
        if ($kernel32 -eq [IntPtr]::Zero) {
            Write-Err "[ОШИБКА] GetModuleHandle(kernel32.dll)"
            exit 8
        }

        $loadLibrary = [NativeMethods]::GetProcAddress($kernel32, "LoadLibraryW")
        if ($loadLibrary -eq [IntPtr]::Zero) {
            Write-Err "[ОШИБКА] GetProcAddress(LoadLibraryW)"
            exit 9
        }

        $threadId = [IntPtr]::Zero
        $remoteThread = [NativeMethods]::CreateRemoteThread($processHandle, [IntPtr]::Zero, 0, $loadLibrary, $remoteBuffer, 0, [ref]$threadId)
        if ($remoteThread -eq [IntPtr]::Zero) {
            Write-Err "[ОШИБКА] CreateRemoteThread: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
            exit 10
        }

        Write-Info "[ИНФО] Ожидание завершения удаленного потока..."
        $waitResult = [NativeMethods]::WaitForSingleObject($remoteThread, $INFINITE)
        if ($waitResult -eq 0xFFFFFFFF) {
            Write-Err "[ОШИБКА] WaitForSingleObject: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
            exit 11
        }
        [NativeMethods]::CloseHandle($remoteThread) | Out-Null

        Write-Ok "[OK] DLL успешно инжектирована в процесс $($process.ProcessName) (PID: $($process.Id))"
    }
    finally {
        if ($processHandle -ne [IntPtr]::Zero) {
            [NativeMethods]::CloseHandle($processHandle) | Out-Null
        }
    }

    exit 0
}
catch {
    Write-Err "[ОШИБКА] $($_.Exception.Message)"
    exit 100
}
