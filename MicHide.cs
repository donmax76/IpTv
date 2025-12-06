#include <windows.h>
#include <winrt/base.h>
#include <winrt/Windows.UI.Xaml.h>
#include <winrt/Windows.UI.Xaml.Controls.h>
#include <winrt/Windows.Foundation.h>
#include <detours.h>  // Microsoft.Detours NuGet

using namespace winrt;
using namespace Windows::UI::Xaml;
using namespace Windows::UI::Xaml::Controls;

// Original function pointer
static HRESULT (WINAPI *OriginalUpdateIconVisibility)(void* thisPtr, void* args) = nullptr;

// Хук функция (адаптация из Taskbar.cpp)
HRESULT WINAPI HookedUpdateIconVisibility(void* thisPtr, void* args)
{
    // Проверяем, если это PrivacyIndicator (микрофон)
    if (/* Проверка аргументов на "PrivacyIndicator" или ID */ true) {
        return S_OK;  // Возвращаем успех, но не рисуем
    }
    return OriginalUpdateIconVisibility(thisPtr, args);  // Оригинал для других
}

// Функция скрытия (адаптация Settings.cpp)
void HideMicrophoneIcon()
{
    try
    {
        winrt::init_apartment();  // Инициализация WinRT

        HWND taskbarWnd = FindWindow(L"Shell_TrayWnd", nullptr);
        if (taskbarWnd == nullptr) return;

        HWND trayWnd = FindWindowEx(taskbarWnd, nullptr, L"TrayNotifyWnd", nullptr);
        if (trayWnd == nullptr) return;

        HWND sysPager = FindWindowEx(trayWnd, nullptr, L"SysPager", nullptr);
        if (sysPager == nullptr) return;

        HWND iconView = FindWindowEx(sysPager, nullptr, L"ToolbarWindow32", nullptr);
        if (iconView == nullptr) return;

        // Хук на Taskbar.View.dll (как в ExplorerPatcher)
        HMODULE taskbarView = GetModuleHandle(L"Taskbar.View.dll");
        if (taskbarView == nullptr) return;

        FARPROC updateFunc = GetProcAddress(taskbarView, "UpdateIconVisibility");
        if (updateFunc != nullptr)
        {
            OriginalUpdateIconVisibility = (decltype(OriginalUpdateIconVisibility))updateFunc;

            DetourTransactionBegin();
            DetourUpdateThread(GetCurrentThread());
            DetourAttach(&(PVOID&)OriginalUpdateIconVisibility, HookedUpdateIconVisibility);
            DetourTransactionCommit();
        }

        // XAML Visibility = Collapsed для PrivacyIndicator (как в Settings.cpp)
        auto frameworkFactory = winrt::get_activation_factory<FrameworkElement, IFrameworkElementFactory>();
        auto iconElement = frameworkFactory.ActivateInstance();
        auto privacyIcon = iconElement.as<FrameworkElement>().FindName(L"PrivacyIndicatorIcon");  // ID из Win11
        if (privacyIcon)
        {
            privacyIcon.Visibility(Visibility::Collapsed);
        }
    }
    catch (...) { /* Игнорируем */ }
}

// DllMain
BOOL APIENTRY DllMain(HMODULE hModule, DWORD dwReason, LPVOID lpReserved)
{
    switch (dwReason)
    {
    case DLL_PROCESS_ATTACH:
        DisableThreadLibraryCalls(hModule);
        CreateThread(nullptr, 0, (LPTHREAD_START_ROUTINE)HideMicrophoneIcon, nullptr, 0, nullptr);
        break;
    case DLL_PROCESS_DETACH:
        DetourTransactionBegin();
        DetourUpdateThread(GetCurrentThread());
        if (OriginalUpdateIconVisibility)
        {
            DetourDetach(&(PVOID&)OriginalUpdateIconVisibility, HookedUpdateIconVisibility);
        }
        DetourTransactionCommit();
        break;
    }
    return TRUE;
}