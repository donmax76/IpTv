// Alternative loader for WindhawkMod.dll without Windhawk
// This uses DLL injection directly into explorer.exe
// Based on MicBypassHook injection code

#include <windows.h>
#include <tlhelp32.h>
#include <stdio.h>

DWORD GetExplorerProcessId()
{
    DWORD processId = 0;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    
    if (snapshot != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32W entry;
        entry.dwSize = sizeof(PROCESSENTRY32W);
        
        if (Process32FirstW(snapshot, &entry)) {
            do {
                if (_wcsicmp(entry.szExeFile, L"explorer.exe") == 0) {
                    processId = entry.th32ProcessID;
                    break;
                }
            } while (Process32NextW(snapshot, &entry));
        }
        
        CloseHandle(snapshot);
    }
    
    return processId;
}

BOOL InjectDll(DWORD processId, const wchar_t* dllPath)
{
    HANDLE hProcess = OpenProcess(PROCESS_ALL_ACCESS, FALSE, processId);
    if (!hProcess) {
        return FALSE;
    }
    
    SIZE_T pathLen = (wcslen(dllPath) + 1) * sizeof(wchar_t);
    LPVOID pRemoteMemory = VirtualAllocEx(hProcess, NULL, pathLen, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    
    if (!pRemoteMemory) {
        CloseHandle(hProcess);
        return FALSE;
    }
    
    if (!WriteProcessMemory(hProcess, pRemoteMemory, dllPath, pathLen, NULL)) {
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return FALSE;
    }
    
    HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
    LPTHREAD_START_ROUTINE pLoadLibrary = (LPTHREAD_START_ROUTINE)GetProcAddress(hKernel32, "LoadLibraryW");
    
    HANDLE hThread = CreateRemoteThread(hProcess, NULL, 0, pLoadLibrary, pRemoteMemory, 0, NULL);
    
    if (!hThread) {
        VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        return FALSE;
    }
    
    WaitForSingleObject(hThread, INFINITE);
    
    DWORD exitCode = 0;
    GetExitCodeThread(hThread, &exitCode);
    
    CloseHandle(hThread);
    VirtualFreeEx(hProcess, pRemoteMemory, 0, MEM_RELEASE);
    CloseHandle(hProcess);
    
    return exitCode != 0;
}

int wmain(int argc, wchar_t* argv[])
{
    wchar_t dllPath[MAX_PATH];
    GetModuleFileNameW(NULL, dllPath, MAX_PATH);
    wchar_t* lastSlash = wcsrchr(dllPath, L'\\');
    if (lastSlash) {
        *lastSlash = L'\0';
    }
    wcscat_s(dllPath, MAX_PATH, L"\\WindhawkMod.dll");
    
    if (argc > 1) {
        wcscpy_s(dllPath, MAX_PATH, argv[1]);
    }
    
    if (GetFileAttributesW(dllPath) == INVALID_FILE_ATTRIBUTES) {
        wprintf(L"Error: DLL not found at %s\n", dllPath);
        return 1;
    }
    
    wprintf(L"Injecting %s into explorer.exe...\n", dllPath);
    
    DWORD processId = GetExplorerProcessId();
    if (!processId) {
        wprintf(L"Error: explorer.exe not found\n");
        return 1;
    }
    
    wprintf(L"Found explorer.exe (PID: %lu)\n", processId);
    
    if (InjectDll(processId, dllPath)) {
        wprintf(L"Success! DLL injected.\n");
        return 0;
    } else {
        wprintf(L"Error: Failed to inject DLL\n");
        return 1;
    }
}

