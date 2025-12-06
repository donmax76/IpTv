#include <windows.h>
#include <detours.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <combaseapi.h>
#include <audiopolicy.h>
#include <cstdio>
#include <cstdarg>
#include <fstream>
#include <iostream>
#include <string>

static std::ofstream logFile;
static bool logInitialized = false;

void InitLog()
{
    if (logInitialized) return;
    
    char logPath[MAX_PATH];
    GetTempPathA(MAX_PATH, logPath);
    strcat_s(logPath, "MicBypassHook.log");
    
    logFile.open(logPath, std::ios::app);
    logInitialized = true;
    
    SYSTEMTIME st;
    GetLocalTime(&st);
    logFile << "=== MicBypassHook Log Started: " 
            << st.wYear << "-" << st.wMonth << "-" << st.wDay << " "
            << st.wHour << ":" << st.wMinute << ":" << st.wSecond << " ===" << std::endl;
    logFile.flush();
}

void LogMessage(const char* format, ...)
{
    InitLog();
    
    char buffer[512];
    va_list args;
    va_start(args, format);
    _vsnprintf_s(buffer, sizeof(buffer), _TRUNCATE, format, args);
    va_end(args);
    
    OutputDebugStringA(buffer);
    
    if (logFile.is_open()) {
        SYSTEMTIME st;
        GetLocalTime(&st);
        logFile << "[" << st.wHour << ":" << st.wMinute << ":" << st.wSecond << "." << st.wMilliseconds << "] " 
                << buffer << std::endl;
        logFile.flush();
    }
}

// Глобальные указатели для перехвата IAudioClient::Initialize
static HRESULT (STDMETHODCALLTYPE* OriginalAudioClientInitialize)(IAudioClient* pThis, AUDCLNT_SHAREMODE ShareMode, DWORD StreamFlags, REFERENCE_TIME BufferDuration, const WAVEFORMATEX* pFormat, LPCGUID AudioSessionGuid) = nullptr;
static PVOID* PatchedInitializeSlot = nullptr;
static bool g_SessionManagerLogged = false;
static bool g_SessionManagerProcessed = false;
static bool g_SessionManagerAttempted = false;
static IAudioSessionManager2* g_SessionManagerForNotification = nullptr;
static IAudioSessionNotification* g_SessionNotification = nullptr;

void ProcessAudioSessionManager(IAudioSessionManager2* pManager);
void AnonymizeCurrentProcessSessions(IAudioSessionManager2* pManager);
void TryAcquireSessionManagerFromDevice(IMMDevice* pDevice);
void AnonymizeSessionControl(IAudioSessionControl* pControl, int sessionIndex);

// Хук: Подавляем GUID сессии (анонимная сессия, без уведомления)
HRESULT STDMETHODCALLTYPE HookedAudioClientInitialize(IAudioClient* pThis, AUDCLNT_SHAREMODE ShareMode, DWORD StreamFlags, REFERENCE_TIME BufferDuration, const WAVEFORMATEX* pFormat, LPCGUID AudioSessionGuid) {
    if (!OriginalAudioClientInitialize) {
        LogMessage("MicBypassHook: HookedAudioClientInitialize called but OriginalAudioClientInitialize is NULL\n");
        return E_POINTER;
    }

    // Логируем оригинальный GUID перед модификацией
    GUID originalGuid = {0};
    if (AudioSessionGuid) {
        originalGuid = *AudioSessionGuid;
        WCHAR guidBuffer[64];
        if (StringFromGUID2(originalGuid, guidBuffer, static_cast<int>(sizeof(guidBuffer) / sizeof(WCHAR))) > 0) {
            char utf8Guid[128];
            int converted = WideCharToMultiByte(CP_UTF8, 0, guidBuffer, -1, utf8Guid, static_cast<int>(sizeof(utf8Guid)), nullptr, nullptr);
            if (converted > 0) {
                LogMessage("MicBypassHook: Original session GUID: %s\n", utf8Guid);
            }
        }
    } else {
        LogMessage("MicBypassHook: Original session GUID pointer is NULL\n");
    }

    // Проблема: Windows не принимает модификацию GUID или StreamFlags
    // Переходим к другому подходу: просто вызываем оригинальную функцию
    // и пытаемся скрыть индикатор через другие методы (например, перехват системных уведомлений)
    LogMessage("MicBypassHook: Calling original Initialize (pThis=0x%p, ShareMode=%d, StreamFlags=0x%08X)\n", 
               pThis, static_cast<int>(ShareMode), StreamFlags);
    
    // Вызываем оригинальную функцию без модификаций
    HRESULT hr = OriginalAudioClientInitialize(pThis, ShareMode, StreamFlags, BufferDuration, pFormat, AudioSessionGuid);
    
    if (SUCCEEDED(hr)) {
        LogMessage("MicBypassHook: Original Initialize succeeded (0x%08X)\n", hr);
    } else {
        LogMessage("MicBypassHook: Original Initialize failed (0x%08X)\n", hr);
    }
    
    return hr;
}

// Функция для установки хука на IAudioClient::Initialize
void EnsureAudioClientHook(IAudioClient* pAudioClient) {
    if (!pAudioClient) {
        LogMessage("MicBypassHook: EnsureAudioClientHook called with NULL audio client\n");
        return;
    }

    PVOID* vtable = *(PVOID**)pAudioClient;
    if (!vtable) {
        LogMessage("MicBypassHook: EnsureAudioClientHook: vtable is NULL\n");
        return;
    }

    PVOID* slot = &vtable[3];
    auto candidate = reinterpret_cast<HRESULT (STDMETHODCALLTYPE*)(IAudioClient*, AUDCLNT_SHAREMODE, DWORD, REFERENCE_TIME, const WAVEFORMATEX*, LPCGUID)>(*slot);
    if (candidate == HookedAudioClientInitialize) {
        LogMessage("MicBypassHook: EnsureAudioClientHook: slot already hooked\n");
        return;
    }

    if (!candidate) {
        LogMessage("MicBypassHook: EnsureAudioClientHook: candidate initialize is NULL\n");
        return;
    }

    if (!OriginalAudioClientInitialize) {
        OriginalAudioClientInitialize = candidate;
        PatchedInitializeSlot = slot;
        LogMessage("MicBypassHook: Captured original Initialize at 0x%p\n", candidate);
    }

    DWORD oldProtect;
    if (VirtualProtect(slot, sizeof(PVOID), PAGE_EXECUTE_READWRITE, &oldProtect)) {
        *slot = HookedAudioClientInitialize;
        VirtualProtect(slot, sizeof(PVOID), oldProtect, &oldProtect);
        LogMessage("MicBypassHook: Patched IAudioClient::Initialize entry (slot=0x%p)\n", slot);
    } else {
        LogMessage("MicBypassHook: Failed to change protection for IAudioClient slot\n");
    }
}

// Перехватываем IMMDevice::Activate через vtable
typedef HRESULT(STDMETHODCALLTYPE* ActivateFunc)(IMMDevice* pThis, REFIID iid, DWORD dwClsCtx, PROPVARIANT* pActivationParams, void** ppInterface);

ActivateFunc TrueActivate = nullptr;

HRESULT STDMETHODCALLTYPE HookedActivate(IMMDevice* pThis, REFIID iid, DWORD dwClsCtx, PROPVARIANT* pActivationParams, void** ppInterface) {
    // Вызываем оригинальную функцию
    HRESULT hr = TrueActivate(pThis, iid, dwClsCtx, pActivationParams, ppInterface);
    
    // Если создан IAudioClient, устанавливаем хук на его vtable
    if (SUCCEEDED(hr) && ppInterface && *ppInterface) {
        if (IsEqualIID(iid, __uuidof(IAudioClient))) {
            IAudioClient* pAudioClient = (IAudioClient*)*ppInterface;
            LogMessage("MicBypassHook: IAudioClient created via Activate, hooking...\n");
            EnsureAudioClientHook(pAudioClient);
        }
        else if (IsEqualIID(iid, __uuidof(IAudioSessionManager2))) {
            IAudioSessionManager2* pManager = (IAudioSessionManager2*)*ppInterface;
            LogMessage("MicBypassHook: IAudioSessionManager2 created via Activate\n");
            ProcessAudioSessionManager(pManager);
        }
        // Также проверяем через QueryInterface на случай, если NAudio использует QueryInterface после Activate
        else {
            IUnknown* pUnknown = (IUnknown*)*ppInterface;
            IAudioClient* pAudioClient = nullptr;
            if (SUCCEEDED(pUnknown->QueryInterface(__uuidof(IAudioClient), (void**)&pAudioClient))) {
                LogMessage("MicBypassHook: IAudioClient obtained via QueryInterface after Activate, hooking...\n");
                EnsureAudioClientHook(pAudioClient);
                pAudioClient->Release();
            }
        }
    }
    
    return hr;
}

void HookIMMDeviceActivate(IMMDevice* pDevice) {
    LogMessage("MicBypassHook: HookIMMDeviceActivate called\n");
    if (!pDevice) {
        LogMessage("MicBypassHook: HookIMMDeviceActivate: pDevice is NULL\n");
        return;
    }
    
    PVOID* vtable = *(PVOID**)pDevice;
    if (!vtable) {
        LogMessage("MicBypassHook: HookIMMDeviceActivate: vtable is NULL\n");
        return;
    }
    
    // IMMDevice::Activate - это обычно 4-й метод (индекс 3) после QueryInterface, AddRef, Release
    // Сохраняем оригинальный указатель если еще не сохранен
    if (TrueActivate == nullptr) {
        TrueActivate = (ActivateFunc)vtable[3];
        LogMessage("MicBypassHook: Found Activate at index 3: 0x%p\n", TrueActivate);
        
        if (TrueActivate != nullptr && TrueActivate != HookedActivate) {
            // Заменяем указатель в vtable
            DWORD oldProtect;
            if (VirtualProtect(&vtable[3], sizeof(PVOID), PAGE_READWRITE, &oldProtect)) {
                vtable[3] = HookedActivate;
                VirtualProtect(&vtable[3], sizeof(PVOID), oldProtect, &oldProtect);
                LogMessage("MicBypassHook: Successfully hooked IMMDevice::Activate at index 3\n");

                // Пытаемся сразу получить AudioSessionManager2 с этого устройства
                TryAcquireSessionManagerFromDevice(pDevice);
            } else {
                LogMessage("MicBypassHook: Failed to VirtualProtect for Activate\n");
            }
        } else {
            LogMessage("MicBypassHook: Activate pointer is NULL or already hooked\n");
        }
    } else {
        LogMessage("MicBypassHook: Activate already hooked, skipping\n");
    }
}

// Перехватываем IMMDeviceEnumerator::GetDefaultAudioEndpoint
// EDataFlow и ERole уже определены в mmdeviceapi.h
typedef HRESULT(STDMETHODCALLTYPE* GetDefaultAudioEndpointFunc)(IMMDeviceEnumerator* pThis, int dataFlow, int role, IMMDevice** ppEndpoint);

GetDefaultAudioEndpointFunc TrueGetDefaultAudioEndpoint = nullptr;

HRESULT STDMETHODCALLTYPE HookedGetDefaultAudioEndpoint(IMMDeviceEnumerator* pThis, int dataFlow, int role, IMMDevice** ppEndpoint) {
    LogMessage("MicBypassHook: GetDefaultAudioEndpoint called (dataFlow=%d, role=%d)\n", dataFlow, role);
    HRESULT hr = TrueGetDefaultAudioEndpoint(pThis, dataFlow, role, ppEndpoint);
    LogMessage("MicBypassHook: GetDefaultAudioEndpoint returned: 0x%08X\n", hr);
    
    // Если получено устройство, устанавливаем хук на его Activate
    if (SUCCEEDED(hr) && ppEndpoint && *ppEndpoint) {
        LogMessage("MicBypassHook: IMMDevice obtained from GetDefaultAudioEndpoint, hooking Activate...\n");
        HookIMMDeviceActivate(*ppEndpoint);
    } else {
        LogMessage("MicBypassHook: GetDefaultAudioEndpoint failed or returned NULL\n");
    }
    
    return hr;
}

// Перехватываем IMMDeviceEnumerator::GetDevice
typedef HRESULT(STDMETHODCALLTYPE* GetDeviceFunc)(IMMDeviceEnumerator* pThis, LPCWSTR pwstrId, IMMDevice** ppDevice);

GetDeviceFunc TrueGetDevice = nullptr;

HRESULT STDMETHODCALLTYPE HookedGetDevice(IMMDeviceEnumerator* pThis, LPCWSTR pwstrId, IMMDevice** ppDevice) {
    LogMessage("MicBypassHook: GetDevice called\n");
    HRESULT hr = TrueGetDevice(pThis, pwstrId, ppDevice);
    LogMessage("MicBypassHook: GetDevice returned: 0x%08X\n", hr);
    
    // Если получено устройство, устанавливаем хук на его Activate
    if (SUCCEEDED(hr) && ppDevice && *ppDevice) {
        LogMessage("MicBypassHook: IMMDevice obtained from GetDevice, hooking Activate...\n");
        HookIMMDeviceActivate(*ppDevice);
    }
    
    return hr;
}

// Функция для установки хука на vtable IMMDeviceEnumerator
void HookIMMDeviceEnumerator(IMMDeviceEnumerator* pEnumerator) {
    if (!pEnumerator) {
        LogMessage("MicBypassHook: HookIMMDeviceEnumerator called with NULL enumerator\n");
        return;
    }
    
    PVOID* vtable = *(PVOID**)pEnumerator;
    if (!vtable) {
        LogMessage("MicBypassHook: HookIMMDeviceEnumerator: vtable is NULL\n");
        return;
    }
    
    DWORD oldProtect;
    
    if (TrueGetDefaultAudioEndpoint == nullptr) {
        TrueGetDefaultAudioEndpoint = (GetDefaultAudioEndpointFunc)vtable[4];
        if (VirtualProtect(&vtable[4], sizeof(PVOID), PAGE_READWRITE, &oldProtect)) {
            vtable[4] = HookedGetDefaultAudioEndpoint;
            VirtualProtect(&vtable[4], sizeof(PVOID), oldProtect, &oldProtect);
            LogMessage("MicBypassHook: Hooked IMMDeviceEnumerator::GetDefaultAudioEndpoint at index 4\n");
        }
    }
    
    if (TrueGetDevice == nullptr) {
        TrueGetDevice = (GetDeviceFunc)vtable[5];
        if (VirtualProtect(&vtable[5], sizeof(PVOID), PAGE_READWRITE, &oldProtect)) {
            vtable[5] = HookedGetDevice;
            VirtualProtect(&vtable[5], sizeof(PVOID), oldProtect, &oldProtect);
            LogMessage("MicBypassHook: Hooked IMMDeviceEnumerator::GetDevice at index 5\n");
        }
    }
}

class SessionNotification : public IAudioSessionNotification {
public:
    SessionNotification() : m_refCount(1) {}

    STDMETHODIMP QueryInterface(REFIID riid, void** ppvObject) override {
        if (!ppvObject) return E_POINTER;
        if (IsEqualIID(riid, __uuidof(IUnknown)) || IsEqualIID(riid, __uuidof(IAudioSessionNotification))) {
            *ppvObject = static_cast<IAudioSessionNotification*>(this);
            AddRef();
            return S_OK;
        }
        *ppvObject = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return (ULONG)InterlockedIncrement(&m_refCount);
    }

    ULONG STDMETHODCALLTYPE Release() override {
        ULONG value = (ULONG)InterlockedDecrement(&m_refCount);
        if (value == 0) {
            delete this;
        }
        return value;
    }

    STDMETHODIMP OnSessionCreated(IAudioSessionControl* NewSession) override {
        LogMessage("MicBypassHook: OnSessionCreated notification received\n");
        if (!NewSession) {
            LogMessage("MicBypassHook: OnSessionCreated: NewSession is NULL\n");
            return S_OK;
        }
        AnonymizeSessionControl(NewSession, -1);
        return S_OK;
    }

private:
    LONG m_refCount;
};

void TryAcquireSessionManagerFromDevice(IMMDevice* pDevice) {
    if (!pDevice) {
        LogMessage("MicBypassHook: TryAcquireSessionManagerFromDevice: device is NULL\n");
        return;
    }

    if (g_SessionManagerProcessed) {
        LogMessage("MicBypassHook: Session manager already processed, skipping acquisition\n");
        return;
    }

    if (!TrueActivate) {
        LogMessage("MicBypassHook: TryAcquireSessionManagerFromDevice: TrueActivate is NULL\n");
        return;
    }

    g_SessionManagerAttempted = true;
    IAudioSessionManager2* pManager = nullptr;
    HRESULT hr = TrueActivate(pDevice, __uuidof(IAudioSessionManager2), CLSCTX_INPROC_SERVER, nullptr, (void**)&pManager);
    if (SUCCEEDED(hr) && pManager) {
        LogMessage("MicBypassHook: Successfully acquired AudioSessionManager2 via device\n");
        ProcessAudioSessionManager(pManager);
        pManager->Release();
        g_SessionManagerProcessed = true;
    } else {
        LogMessage("MicBypassHook: Failed to acquire AudioSessionManager2 via device (hr=0x%08X)\n", hr);
    }
}

void ProcessAudioSessionManager(IAudioSessionManager2* pManager) {
    if (!pManager) {
        LogMessage("MicBypassHook: ProcessAudioSessionManager called with NULL manager\n");
        return;
    }

    if (!g_SessionManagerLogged) {
        LogMessage("MicBypassHook: Processing AudioSessionManager2\n");
        g_SessionManagerLogged = true;
    }

    if (!g_SessionNotification) {
        SessionNotification* notification = new SessionNotification();
        HRESULT hrReg = pManager->RegisterSessionNotification(notification);
        if (SUCCEEDED(hrReg)) {
            g_SessionNotification = notification;
            g_SessionManagerForNotification = pManager;
            g_SessionManagerForNotification->AddRef();
            LogMessage("MicBypassHook: Registered session notification\n");
        } else {
            LogMessage("MicBypassHook: Failed to register session notification (0x%08X)\n", hrReg);
            notification->Release();
        }
    }

    AnonymizeCurrentProcessSessions(pManager);
}

void AnonymizeCurrentProcessSessions(IAudioSessionManager2* pManager) {
    if (!pManager) {
        LogMessage("MicBypassHook: AnonymizeCurrentProcessSessions: manager is NULL\n");
        return;
    }

    IAudioSessionEnumerator* pEnumerator = nullptr;
    HRESULT hrEnum = pManager->GetSessionEnumerator(&pEnumerator);
    if (FAILED(hrEnum) || !pEnumerator) {
        LogMessage("MicBypassHook: GetSessionEnumerator failed: 0x%08X\n", hrEnum);
        return;
    }

    int sessionCount = 0;
    if (SUCCEEDED(pEnumerator->GetCount(&sessionCount))) {
        LogMessage("MicBypassHook: Session count = %d\n", sessionCount);
    }

    DWORD currentPid = GetCurrentProcessId();
    for (int i = 0; i < sessionCount; ++i) {
        IAudioSessionControl* pControl = nullptr;
        if (SUCCEEDED(pEnumerator->GetSession(i, &pControl)) && pControl) {
            AnonymizeSessionControl(pControl, i);
            pControl->Release();
        }
    }

    pEnumerator->Release();
}

void AnonymizeSessionControl(IAudioSessionControl* pControl, int sessionIndex) {
    if (!pControl) return;

    IAudioSessionControl2* pControl2 = nullptr;
    if (FAILED(pControl->QueryInterface(__uuidof(IAudioSessionControl2), (void**)&pControl2)) || !pControl2) {
        LogMessage("MicBypassHook: AnonymizeSessionControl: failed to get IAudioSessionControl2\n");
        return;
    }

    DWORD sessionPid = 0;
    if (SUCCEEDED(pControl2->GetProcessId(&sessionPid))) {
        DWORD currentPid = GetCurrentProcessId();
        if (sessionPid == currentPid) {
            GUID newGrouping = GUID_NULL;
            HRESULT hrSet = pControl->SetGroupingParam(&newGrouping, nullptr);
            if (SUCCEEDED(hrSet)) {
                if (sessionIndex >= 0) {
                    LogMessage("MicBypassHook: SetGroupingParam to GUID_NULL for session %d\n", sessionIndex);
                } else {
                    LogMessage("MicBypassHook: SetGroupingParam to GUID_NULL for new session (PID match)\n");
                }
            } else {
                LogMessage("MicBypassHook: SetGroupingParam failed (hr=0x%08X)\n", hrSet);
            }
        } else {
            LogMessage("MicBypassHook: Session PID %lu does not match current PID %lu\n", sessionPid, currentPid);
        }
    } else {
        LogMessage("MicBypassHook: Failed to get session PID\n");
    }

    pControl2->Release();
}

// Перехватываем CoCreateInstance для автоматического обнаружения создания IAudioClient
typedef HRESULT(WINAPI* CoCreateInstanceFunc)(REFCLSID rclsid, LPUNKNOWN pUnkOuter, DWORD dwClsContext, REFIID riid, LPVOID* ppv);

CoCreateInstanceFunc TrueCoCreateInstance = nullptr;

HRESULT WINAPI HookedCoCreateInstance(REFCLSID rclsid, LPUNKNOWN pUnkOuter, DWORD dwClsContext, REFIID riid, LPVOID* ppv) {
    HRESULT hr = TrueCoCreateInstance(rclsid, pUnkOuter, dwClsContext, riid, ppv);
    
    // Если создан IMMDeviceEnumerator, устанавливаем хук на его vtable
    if (SUCCEEDED(hr) && ppv && *ppv) {
        if (IsEqualIID(riid, __uuidof(IMMDeviceEnumerator))) {
            LogMessage("MicBypassHook: IMMDeviceEnumerator created via CoCreateInstance\n");
            IMMDeviceEnumerator* pEnumerator = (IMMDeviceEnumerator*)*ppv;
            HookIMMDeviceEnumerator(pEnumerator);
        }
    }
    
    return hr;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD dwReason, LPVOID lpReserved) {
    switch (dwReason) {
    case DLL_PROCESS_ATTACH: {
        DisableThreadLibraryCalls(hModule);
        LogMessage("MicBypassHook: DLL_PROCESS_ATTACH\n");
        
        // Перехватываем CoCreateInstance для автоматического обнаружения создания IAudioClient
        HMODULE hOle32 = GetModuleHandleA("ole32.dll");
        if (hOle32) {
            TrueCoCreateInstance = (CoCreateInstanceFunc)GetProcAddress(hOle32, "CoCreateInstance");
            if (TrueCoCreateInstance) {
                DetourRestoreAfterWith();
                DetourTransactionBegin();
                DetourUpdateThread(GetCurrentThread());
                DetourAttach(&(PVOID&)TrueCoCreateInstance, HookedCoCreateInstance);
                DetourTransactionCommit();
            }
        }
        
        break;
    }
    case DLL_PROCESS_DETACH:
        LogMessage("MicBypassHook: DLL_PROCESS_DETACH\n");
        // Отключаем хуки
        if (TrueCoCreateInstance) {
            DetourTransactionBegin();
            DetourUpdateThread(GetCurrentThread());
            DetourDetach(&(PVOID&)TrueCoCreateInstance, HookedCoCreateInstance);
            DetourTransactionCommit();
        }

        if (g_SessionManagerForNotification && g_SessionNotification) {
            g_SessionManagerForNotification->UnregisterSessionNotification(g_SessionNotification);
            g_SessionNotification->Release();
            g_SessionNotification = nullptr;
            g_SessionManagerForNotification->Release();
            g_SessionManagerForNotification = nullptr;
            LogMessage("MicBypassHook: Unregistered session notification\n");
        }
        
        if (PatchedInitializeSlot && OriginalAudioClientInitialize) {
            DWORD oldProtect;
            if (VirtualProtect(PatchedInitializeSlot, sizeof(PVOID), PAGE_EXECUTE_READWRITE, &oldProtect)) {
                *PatchedInitializeSlot = OriginalAudioClientInitialize;
                VirtualProtect(PatchedInitializeSlot, sizeof(PVOID), oldProtect, &oldProtect);
            }
            PatchedInitializeSlot = nullptr;
            OriginalAudioClientInitialize = nullptr;
        }
        
        if (logFile.is_open()) {
            logFile << "=== MicBypassHook Log Ended ===" << std::endl;
            logFile.close();
        }
        break;
    }
    return TRUE;
}

