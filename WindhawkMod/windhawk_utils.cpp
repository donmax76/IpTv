// Windhawk Utils Implementation
// This is a stub implementation for development
// In production, these functions are provided by Windhawk runtime

#include "windhawk_utils.h"
#include <stdio.h>
#include <stdarg.h>
#include <map>
#include <string>

// Stub implementations - these will be replaced by Windhawk runtime
static std::map<std::wstring, int> g_intSettings;
static std::map<std::wstring, std::wstring> g_stringSettings;

extern "C" {
    void Wh_Log(const wchar_t* format, ...)
    {
        wchar_t buffer[1024];
        va_list args;
        va_start(args, format);
        _vsnwprintf_s(buffer, _countof(buffer), _TRUNCATE, format, args);
        va_end(args);
        
        OutputDebugStringW(buffer);
        OutputDebugStringW(L"\n");
        
        // Also log to file
        wchar_t logPath[MAX_PATH];
        GetTempPathW(MAX_PATH, logPath);
        wcscat_s(logPath, L"WindhawkMod.log");
        
        FILE* f = nullptr;
        _wfopen_s(&f, logPath, L"a");
        if (f) {
            fwprintf_s(f, L"%s\n", buffer);
            fclose(f);
        }
    }

    int Wh_GetIntSetting(const wchar_t* name)
    {
        auto it = g_intSettings.find(name);
        if (it != g_intSettings.end()) {
            return it->second;
        }
        // Default: hide microphone icon
        if (wcscmp(name, L"hideMicrophoneIcon") == 0) {
            return 1;
        }
        return 0;
    }

    const wchar_t* Wh_GetStringSetting(const wchar_t* name)
    {
        static std::wstring result;
        auto it = g_stringSettings.find(name);
        if (it != g_stringSettings.end()) {
            result = it->second;
            return result.c_str();
        }
        return L"";
    }

    void Wh_FreeStringSetting(const wchar_t* str)
    {
        // In stub, do nothing
        // In real Windhawk, this frees memory allocated by Wh_GetStringSetting
    }

    void Wh_ApplyHookOperations()
    {
        // In stub, do nothing
        // In real Windhawk, this applies pending hooks
        Wh_Log(L"Wh_ApplyHookOperations called (stub)");
    }
}

namespace WindhawkUtils {
    bool HookSymbols(HMODULE module, SYMBOL_HOOK* hooks, size_t count)
    {
        // Stub implementation
        // In real Windhawk, this uses symbol resolution and hooking
        Wh_Log(L"HookSymbols called for module %p, %zu hooks", module, count);
        
        // For now, just log
        for (size_t i = 0; i < count; i++) {
            Wh_Log(L"  Hook: %s", hooks[i].symbol);
        }
        
        return true;
    }

    void Wh_SetFunctionHookT(void* target, void* hook, void** original)
    {
        // Stub implementation
        // In real Windhawk, this uses Detours or similar
        Wh_Log(L"Wh_SetFunctionHookT called: target=%p, hook=%p", target, hook);
        
        if (original) {
            *original = target; // In stub, just point to original
        }
    }

    void Wh_ApplyHookOperations()
    {
        // Call the C function
        ::Wh_ApplyHookOperations();
    }
}

