// ==WindhawkMod==
// @id hide-microphone-icon
// @name Hide Microphone Icon
// @description Hides the microphone privacy indicator icon from the system tray
// @version 1.0.0
// @author YourName
// @include explorer.exe
// @architecture x86-64
// @compilerOptions -lole32 -loleaut32 -lruntimeobject
// ==/WindhawkMod==

#include <windhawk_utils.h>
#include <atomic>
#include <functional>
#include <list>
#include <string>

#undef GetCurrentTime
#include <winrt/Windows.UI.Core.h>
#include <winrt/Windows.UI.Xaml.Automation.h>
#include <winrt/Windows.UI.Xaml.Controls.h>
#include <winrt/Windows.UI.Xaml.Media.h>
#include <winrt/Windows.UI.Xaml.h>
#include <winrt/base.h>

using namespace winrt::Windows::UI::Xaml;

struct {
    bool hideMicrophoneIcon;
} g_settings;

std::atomic<bool> g_unloading;

using FrameworkElementLoadedEventRevoker = winrt::impl::event_revoker<
    IFrameworkElement,
    &winrt::impl::abi<IFrameworkElement>::type::remove_Loaded>;

std::list<FrameworkElementLoadedEventRevoker> g_autoRevokerList;

winrt::weak_ref<Controls::TextBlock> g_mainStackInnerTextBlock;
int64_t g_mainStackTextChangedToken;

HWND FindCurrentProcessTaskbarWnd()
{
    HWND hTaskbarWnd = nullptr;
    EnumWindows(
        [](HWND hWnd, LPARAM lParam) -> BOOL {
            DWORD dwProcessId;
            WCHAR className[32];
            if (GetWindowThreadProcessId(hWnd, &dwProcessId) &&
                dwProcessId == GetCurrentProcessId() &&
                GetClassName(hWnd, className, ARRAYSIZE(className)) &&
                _wcsicmp(className, L"Shell_TrayWnd") == 0) {
                *reinterpret_cast<HWND*>(lParam) = hWnd;
                return FALSE;
            }
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&hTaskbarWnd));
    return hTaskbarWnd;
}

FrameworkElement EnumParentElements(
    FrameworkElement element,
    std::function<bool(FrameworkElement)> enumCallback)
{
    auto parent = element;
    while (true) {
        parent = Media::VisualTreeHelper::GetParent(parent)
            .try_as<FrameworkElement>();
        if (!parent) {
            return nullptr;
        }
        if (enumCallback(parent)) {
            return parent;
        }
    }
    return nullptr;
}

FrameworkElement GetParentElementByName(FrameworkElement element, PCWSTR name)
{
    return EnumParentElements(element, [name](FrameworkElement parent) {
        return parent.Name() == name;
    });
}

bool IsChildOfElementByName(FrameworkElement element, PCWSTR name)
{
    return !!GetParentElementByName(element, name);
}

FrameworkElement EnumChildElements(
    FrameworkElement element,
    std::function<bool(FrameworkElement)> enumCallback)
{
    int childrenCount = Media::VisualTreeHelper::GetChildrenCount(element);
    for (int i = 0; i < childrenCount; i++) {
        auto child = Media::VisualTreeHelper::GetChild(element, i)
            .try_as<FrameworkElement>();
        if (!child) {
            Wh_Log(L"Failed to get child %d of %d", i + 1, childrenCount);
            continue;
        }
        if (enumCallback(child)) {
            return child;
        }
    }
    return nullptr;
}

FrameworkElement FindChildByName(FrameworkElement element, PCWSTR name)
{
    return EnumChildElements(element, [name](FrameworkElement child) {
        return child.Name() == name;
    });
}

FrameworkElement FindChildByClassName(FrameworkElement element, PCWSTR className)
{
    return EnumChildElements(element, [className](FrameworkElement child) {
        return winrt::get_class_name(child) == className;
    });
}

enum class SystemTrayIconIdent {
    kUnknown,
    kNone,
    kMicrophone,
};

SystemTrayIconIdent IdentifySystemTrayIconFromText(std::wstring_view text)
{
    switch (text.length()) {
    case 0:
        return SystemTrayIconIdent::kNone;
    case 1:
        break;
    default:
        return SystemTrayIconIdent::kUnknown;
    }

    switch (text[0]) {
    case L'\uE720': // Microphone
    case L'\uEC71': // MicOn
        return SystemTrayIconIdent::kMicrophone;
    }

    return SystemTrayIconIdent::kUnknown;
}

void ApplyMainStackIconViewStyle(FrameworkElement notifyIconViewElement)
{
    FrameworkElement systemTrayTextIconContent = nullptr;
    FrameworkElement child = notifyIconViewElement;
    if ((child = FindChildByName(child, L"ContainerGrid")) &&
        (child = FindChildByName(child, L"ContentPresenter")) &&
        (child = FindChildByName(child, L"ContentGrid")) &&
        (child = FindChildByClassName(child, L"SystemTray.TextIconContent"))) {
        systemTrayTextIconContent = child;
    } else {
        Wh_Log(L"Failed to get SystemTray.TextIconContent");
        return;
    }

    Controls::TextBlock innerTextBlock = nullptr;
    child = systemTrayTextIconContent;
    if ((child = FindChildByName(child, L"ContainerGrid")) &&
        (child = FindChildByName(child, L"Base")) &&
        (child = FindChildByName(child, L"InnerTextBlock"))) {
        innerTextBlock = child.as<Controls::TextBlock>();
    } else {
        Wh_Log(L"Failed to get InnerTextBlock");
        return;
    }

    auto shouldHide = [](Controls::TextBlock innerTextBlock) {
        auto text = innerTextBlock.Text();
        auto systemTrayIconIdent = IdentifySystemTrayIconFromText(text);
        bool hide = false;
        if (!g_unloading) {
            switch (systemTrayIconIdent) {
            case SystemTrayIconIdent::kMicrophone:
                hide = g_settings.hideMicrophoneIcon;
                break;
            case SystemTrayIconIdent::kNone:
                break;
            default:
                break;
            }
        }
        Wh_Log(L"Main stack icon %d, hide=%d", (int)systemTrayIconIdent, hide);
        return hide;
    };

    bool hide = shouldHide(innerTextBlock);
    notifyIconViewElement.Visibility(hide ? Visibility::Collapsed : Visibility::Visible);

    if (!g_unloading && !g_mainStackInnerTextBlock.get()) {
        auto notifyIconViewElementWeakRef = winrt::make_weak(notifyIconViewElement);
        g_mainStackInnerTextBlock = innerTextBlock;
        g_mainStackTextChangedToken = innerTextBlock.RegisterPropertyChangedCallback(
            Controls::TextBlock::TextProperty(),
            [notifyIconViewElementWeakRef, &shouldHide](
                DependencyObject sender, DependencyProperty property) {
                auto innerTextBlock = sender.try_as<Controls::TextBlock>();
                if (!innerTextBlock) {
                    return;
                }
                auto notifyIconViewElement = notifyIconViewElementWeakRef.get();
                if (!notifyIconViewElement) {
                    return;
                }
                bool hide = shouldHide(innerTextBlock);
                Wh_Log(L"Main stack icon, hide=%d", hide);
                notifyIconViewElement.Visibility(
                    hide ? Visibility::Collapsed : Visibility::Visible);
            });
    }
}

bool ApplyMainStackStyle(FrameworkElement container)
{
    FrameworkElement stackPanel = nullptr;
    FrameworkElement child = container;
    if ((child = FindChildByName(child, L"Content")) &&
        (child = FindChildByName(child, L"IconStack")) &&
        (child = FindChildByClassName(
            child, L"Windows.UI.Xaml.Controls.ItemsPresenter")) &&
        (child = FindChildByClassName(
            child, L"Windows.UI.Xaml.Controls.StackPanel"))) {
        stackPanel = child;
    }
    if (!stackPanel) {
        return false;
    }

    EnumChildElements(stackPanel, [](FrameworkElement child) {
        auto childClassName = winrt::get_class_name(child);
        if (childClassName != L"Windows.UI.Xaml.Controls.ContentPresenter") {
            return false;
        }
        FrameworkElement systemTrayIconElement = FindChildByName(child, L"SystemTrayIcon");
        if (!systemTrayIconElement) {
            return false;
        }
        ApplyMainStackIconViewStyle(systemTrayIconElement);
        return false;
    });
    return true;
}

bool ApplyStyle(XamlRoot xamlRoot)
{
    FrameworkElement systemTrayFrameGrid = nullptr;
    FrameworkElement child = xamlRoot.Content().try_as<FrameworkElement>();
    if (child &&
        (child = FindChildByClassName(child, L"SystemTray.SystemTrayFrame")) &&
        (child = FindChildByName(child, L"SystemTrayFrameGrid"))) {
        systemTrayFrameGrid = child;
    }
    if (!systemTrayFrameGrid) {
        return false;
    }

    bool somethingSucceeded = false;
    FrameworkElement mainStack = FindChildByName(systemTrayFrameGrid, L"MainStack");
    if (mainStack) {
        somethingSucceeded |= ApplyMainStackStyle(mainStack);
    }

    return somethingSucceeded;
}

using IconView_IconView_t = void*(WINAPI*)(void* pThis);
IconView_IconView_t IconView_IconView_Original;

void* WINAPI IconView_IconView_Hook(void* pThis)
{
    Wh_Log(L">");
    void* ret = IconView_IconView_Original(pThis);
    FrameworkElement iconView = nullptr;
    ((IUnknown**)pThis)[1]->QueryInterface(
        winrt::guid_of<FrameworkElement>(), winrt::put_abi(iconView));
    if (!iconView) {
        return ret;
    }

    g_autoRevokerList.emplace_back();
    auto autoRevokerIt = g_autoRevokerList.end();
    --autoRevokerIt;
    *autoRevokerIt = iconView.Loaded(
        winrt::auto_revoke_t{},
        [autoRevokerIt](winrt::Windows::Foundation::IInspectable const& sender,
            RoutedEventArgs const& e) {
            Wh_Log(L">");
            g_autoRevokerList.erase(autoRevokerIt);
            auto iconView = sender.try_as<FrameworkElement>();
            if (!iconView) {
                return;
            }
            auto className = winrt::get_class_name(iconView);
            Wh_Log(L"className: %s", className.c_str());
            if (className == L"SystemTray.IconView") {
                if (iconView.Name() == L"SystemTrayIcon") {
                    if (IsChildOfElementByName(iconView, L"MainStack")) {
                        ApplyMainStackIconViewStyle(iconView);
                    }
                }
            }
        });
    return ret;
}

bool HookTaskbarViewDllSymbols(HMODULE module)
{
    WindhawkUtils::SYMBOL_HOOK symbolHooks[] = {
        {{LR"(public: __cdecl winrt::SystemTray::implementation::IconView::IconView(void))"},
            reinterpret_cast<void**>(&IconView_IconView_Original), 
            reinterpret_cast<void*>(IconView_IconView_Hook)},
    };
    return WindhawkUtils::HookSymbols(module, symbolHooks, ARRAYSIZE(symbolHooks));
}

HMODULE GetTaskbarViewModuleHandle()
{
    HMODULE module = GetModuleHandle(L"Taskbar.View.dll");
    if (!module) {
        module = GetModuleHandle(L"ExplorerExtensions.dll");
    }
    return module;
}

void LoadSettings()
{
    g_settings.hideMicrophoneIcon = Wh_GetIntSetting(L"hideMicrophoneIcon");
}

void ApplySettings()
{
    Wh_Log(L"Applying settings");
    // Settings are applied automatically when IconView elements are created
    // via the IconView_IconView_Hook
}

using LoadLibraryExW_t = decltype(&LoadLibraryExW);
LoadLibraryExW_t LoadLibraryExW_Original;

HMODULE WINAPI LoadLibraryExW_Hook(LPCWSTR lpLibFileName, HANDLE hFile, DWORD dwFlags)
{
    HMODULE module = LoadLibraryExW_Original(lpLibFileName, hFile, dwFlags);
    if (module) {
        if (GetTaskbarViewModuleHandle() == module) {
            Wh_Log(L"Taskbar view module loaded: %s", lpLibFileName);
            if (HookTaskbarViewDllSymbols(module)) {
                WindhawkUtils::Wh_ApplyHookOperations();
            }
        }
    }
    return module;
}

BOOL Wh_ModInit()
{
    Wh_Log(L">");
    LoadSettings();

    HMODULE taskbarViewModule = GetTaskbarViewModuleHandle();
    if (taskbarViewModule) {
        Wh_Log(L"Taskbar view module already loaded");
        if (!HookTaskbarViewDllSymbols(taskbarViewModule)) {
            return FALSE;
        }
        WindhawkUtils::Wh_ApplyHookOperations();
    } else {
        Wh_Log(L"Taskbar view module not loaded yet, hooking LoadLibraryExW");
        HMODULE kernelBaseModule = GetModuleHandle(L"kernelbase.dll");
        if (kernelBaseModule) {
            auto pKernelBaseLoadLibraryExW = (decltype(&LoadLibraryExW))GetProcAddress(
                kernelBaseModule, "LoadLibraryExW");
            if (pKernelBaseLoadLibraryExW) {
                WindhawkUtils::Wh_SetFunctionHookT(
                    pKernelBaseLoadLibraryExW, LoadLibraryExW_Hook, 
                    (void**)&LoadLibraryExW_Original);
            }
        }
    }

    return TRUE;
}

void Wh_ModAfterInit()
{
    Wh_Log(L">");
    ApplySettings();
}

void Wh_ModBeforeUninit()
{
    Wh_Log(L">");
    g_unloading = true;
    ApplySettings();
}

void Wh_ModUninit()
{
    Wh_Log(L">");
}

void Wh_ModSettingsChanged()
{
    Wh_Log(L">");
    LoadSettings();
    ApplySettings();
}

