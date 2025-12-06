// Windhawk Utils Header
// This is a simplified version for integration
// Full version available from Windhawk SDK

#ifndef WINDHAWK_UTILS_H
#define WINDHAWK_UTILS_H

#include <windows.h>
#include <string>

// Windhawk API functions (simplified)
extern "C" {
    void Wh_Log(const wchar_t* format, ...);
    int Wh_GetIntSetting(const wchar_t* name);
    const wchar_t* Wh_GetStringSetting(const wchar_t* name);
    void Wh_FreeStringSetting(const wchar_t* str);
    void Wh_ApplyHookOperations();
}

// Symbol hook structure
namespace WindhawkUtils {
    struct SYMBOL_HOOK {
        const wchar_t* symbol;
        void** original;
        void* hook;
    };

    bool HookSymbols(HMODULE module, SYMBOL_HOOK* hooks, size_t count);
    void Wh_SetFunctionHookT(void* target, void* hook, void** original);
    void Wh_ApplyHookOperations();
}

#endif // WINDHAWK_UTILS_H

