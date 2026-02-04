#!/usr/bin/env python3
"""
Remote Desktop Host - OpenCV для быстрого JPEG
"""

import sys
import os

# КРИТИЧНО: Проверка блокировки ДО всех импортов (чтобы предотвратить запуск нескольких процессов PyInstaller)
_host_mutex = None

def check_single_instance_early():
    """Проверяет блокировку ДО всех импортов"""
    if sys.platform == 'win32':
        try:
            import ctypes
            
            mutex_name = "Global\\RemoteDesktopHost_SingleInstance_Mutex"
            kernel32 = ctypes.windll.kernel32
            ERROR_ALREADY_EXISTS = 183
            
            mutex_handle = kernel32.CreateMutexW(None, True, mutex_name)
            
            if mutex_handle == 0:
                error_code = kernel32.GetLastError()
                print(f"[WARNING] Could not create mutex (error {error_code}), continuing anyway...", flush=True)
                return None
            
            last_error = kernel32.GetLastError()
            
            if last_error == ERROR_ALREADY_EXISTS:
                kernel32.CloseHandle(mutex_handle)
                print("\n[ERROR] Another instance of Host.exe is already running!", flush=True)
                print("Please close the existing instance before starting a new one.", flush=True)
                return False
            
            return mutex_handle
        except Exception as e:
            print(f"[WARNING] Mutex check failed: {e}, continuing anyway...", flush=True)
            return None
    return None

# Проверяем блокировку СРАЗУ
_host_mutex = check_single_instance_early()

if _host_mutex is False:
    input("\nPress Enter to exit...")
    sys.exit(1)

# Теперь импортируем остальные модули
import json, time, threading, asyncio, subprocess, base64, shutil, queue, uuid, struct
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse, urlunparse

def cleanup_lock_file():
    """Освобождает блокировку при завершении программы"""
    global _host_mutex
    import atexit
    
    def cleanup():
        if _host_mutex:
            try:
                if sys.platform == 'win32':
                    import ctypes
                    ctypes.windll.kernel32.CloseHandle(_host_mutex)
                _host_mutex = None
            except Exception:
                pass
    
    atexit.register(cleanup)

try:
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
except ImportError as e:
    print(f"Error importing pyautogui: {e}")
    print("pip install pyautogui")
    sys.exit(1)

try:
    import mss
    import numpy as np
    import cv2
except ImportError as e:
    print(f"Import error: {e}")
    print("pip install mss numpy opencv-python")
    sys.exit(1)
except Exception as e:
    print(f"Error importing libraries: {e}")
    print("pip install mss numpy opencv-python")
    sys.exit(1)

try:
    import aiohttp
except ImportError as e:
    print(f"Error importing aiohttp: {e}")
    print("pip install aiohttp")
    sys.exit(1)

SCRIPT_DIR = os.path.dirname(os.path.abspath(sys.argv[0]))
CONFIG_FILE = os.path.join(SCRIPT_DIR, "host_config.json")
DEFAULT = {
    "server": "ws://185.247.118.189/ws",
    "control_server": "",   # Опционально: порт управления (например ws://ip:8080/ws). Если пусто — используется server.
    "streaming_server": "",  # Опционально: порт стриминга (например ws://ip:8081/ws). Если пусто — используется server.
    "room": "my_session",
    "password": "",
    "quality": 70,  # Качество JPEG для экрана
    "fps": 15,  # FPS для экрана (целевой; фактический зависит от CPU)
    "scale": 80,  # Масштаб экрана (%)
    "file_connections": 8,  # Количество соединений для передачи файлов (0-20)
    "screen_connections": 1,  # Количество соединений для вещания экрана (рекомендуется 1 для последовательности)
    "connections": 8,  # Устаревшее: для обратной совместимости
    "chunk_size": 16 * 1024 * 1024  # Размер чанка для файлов (16MB для максимальной скорости)
}

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE) as f:
                return {**DEFAULT, **json.load(f)}
        except Exception as e:
            log(f"Error loading config: {e}, using defaults")
    return DEFAULT.copy()

def save_config(cfg):
    """Сохраняет конфигурацию в файл"""
    try:
        with open(CONFIG_FILE, 'w') as f:
            json.dump(cfg, f, indent=2)
        return True
    except Exception as e:
        log(f"Error saving config: {e}")
        return False

def log(msg):
    # UTC+4 (Московское время) - полное время с датой и миллисекундами
    utc_time = datetime.now(timezone.utc)
    local_time = utc_time + timedelta(hours=4)
    print(f"[{local_time.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}] {msg}", flush=True)

def _safe_send_json(ws, loop, data):
    try:
        if ws and not ws.closed:
            fut = asyncio.run_coroutine_threadsafe(ws.send_str(json.dumps(data)), loop)
            try:
                fut.add_done_callback(lambda f: f.exception())
            except Exception as e:
                # Игнорируем ошибки callback - это не критично
                pass
    except Exception as e:
        # Игнорируем ошибки отправки - соединение может быть закрыто
        pass


class Host:
    def __init__(self, cfg):
        self.cfg = cfg
        self.ws = None
        self.connections = []  # Устаревшие соединения (для обратной совместимости)
        self.file_connections = []  # Соединения для передачи файлов
        self.screen_connections = []  # Соединения для вещания экрана
        self.main_connection = None  # Основное соединение для команд
        self.loop = None
        self.running = True
        self.streaming = False
        self.monitors = {}
        # КРИТИЧНО: Увеличиваем размер очереди для стабильности стрима
        # Большая очередь позволяет накапливать кадры при временных задержках сети
        # и предотвращает потерю кадров при переполнении
        self.send_queue = queue.Queue(maxsize=30)  # больше буфер — меньше потерь при временной задержке релея
        self.frames_since_start = 0
        self.upload_sessions = {}
        self.upload_lock = threading.Lock()
        self.upload_queue = queue.Queue()
        threading.Thread(target=self._upload_worker, daemon=True).start()
        self.viewer_connected = False
        self.vps_connected = False  # Статус подключения к VPS
        self.last_viewer_msg_time = 0
        self._last_large_transfer_time = 0
        self._file_transfer_in_progress = False
        self._last_vps_status_log = 0  # Время последнего лога статуса VPS
        self._download_cancelled = {}  # ИСПРАВЛЕНО: инициализируем словарь
        self._download_sessions = {}  # Для отслеживания активных загрузок
        # Оптимизация: размер чанка из конфига (по умолчанию 8MB)
        self.chunk_size = cfg.get('chunk_size', 8 * 1024 * 1024)
        # КРИТИЧНО: Задержка переподключения с экспоненциальным увеличением (максимум 60 секунд)
        self._reconnect_delay = 2.0  # Начальная задержка 2 секунды
        self._max_reconnect_delay = 60.0  # Максимальная задержка 60 секунд
    
    def run(self):
        threading.Thread(target=self._run_async, daemon=True).start()
        try:
            while self.running:
                time.sleep(1)
        except KeyboardInterrupt:
            self.running = False
    
    def _run_async(self):
        # КРИТИЧНО: Хост работает в бесконечном цикле и НИКОГДА не закрывается сам
        # Единственный способ остановить хост - установить self.running = False (Ctrl+C)
        while self.running:
            try:
                self.loop = asyncio.new_event_loop()
                asyncio.set_event_loop(self.loop)
                self.loop.run_until_complete(self._main())
            except KeyboardInterrupt:
                # Пользователь прервал выполнение - останавливаем хост
                log("Keyboard interrupt - stopping host")
                self.running = False
                break
            except Exception as e:
                # КРИТИЧНО: Обрабатываем ВСЕ исключения и продолжаем работу
                # Хост НЕ должен закрываться из-за ошибок - только переподключаться
                log(f"Async loop error: {e} - restarting in 3 seconds...")
                import traceback
                log(f"Traceback: {traceback.format_exc()}")
                time.sleep(3)  # Небольшая задержка перед перезапуском
                # Продолжаем цикл - хост будет работать вечно  # Увеличена задержка для стабильности
                # Продолжаем цикл - хост должен продолжать работу
    
    def _url_with_port(self, base_url, port):
        """Подставляет порт в URL (для двухпортовой схемы VPS: 8080 control, 8081 streaming)."""
        try:
            p = urlparse(base_url)
            host = p.hostname or (p.netloc.split(':')[0] if p.netloc else 'localhost')
            path = p.path or '/ws'
            if not path.startswith('/'):
                path = '/' + path
            new_netloc = '%s:%d' % (host, port)
            return urlunparse((p.scheme or 'ws', new_netloc, path, p.params, p.query, p.fragment))
        except Exception as e:
            log(f"Warning: _url_with_port failed ({e}), using base_url")
            return base_url

    async def _main(self):
        # Два порта: control (8080) — управление, streaming (8081) — данные.
        # Если control_server/streaming_server не заданы — подставляем 8080/8081 в server (пазл с VPS).
        base = (self.cfg.get('server') or '').strip()
        ctrl_cfg = (self.cfg.get('control_server') or '').strip()
        stream_cfg = (self.cfg.get('streaming_server') or '').strip()
        if ctrl_cfg:
            url_control = ctrl_cfg
        else:
            url_control = self._url_with_port(base, 8080) if base else ''
        if stream_cfg:
            url_streaming = stream_cfg
        else:
            url_streaming = self._url_with_port(base, 8081) if base else url_control
        log(f"Connecting (control: %s, streaming: %s)..." % (url_control, url_streaming if url_streaming != url_control else "same"))
        
        while self.running:
            try:
                # КРИТИЧНО: Увеличиваем timeout для подключения при проблемах с сетью
                # Это позволяет хосту подключиться даже при медленной сети или временных проблемах
                timeout = aiohttp.ClientTimeout(total=None, sock_connect=30, sock_read=None)  # Увеличено с 10 до 30
                async with aiohttp.ClientSession(timeout=timeout) as session:
                    # КРИТИЧНО: Разделяем соединения на файловые и для экрана
                    num_file_connections = max(0, min(20, int(self.cfg.get('file_connections', 8))))
                    num_screen_connections = max(0, min(20, int(self.cfg.get('screen_connections', 2))))
                    
                    # Если не указаны новые параметры - используем старый 'connections' для обратной совместимости
                    if num_file_connections == 0 and num_screen_connections == 0:
                        old_connections = max(1, min(10, int(self.cfg.get('connections', 1))))
                        num_file_connections = old_connections
                        num_screen_connections = 1
                    
                    log(f"Using {num_file_connections} file connection(s) and {num_screen_connections} screen connection(s)")
                    
                    file_connections = []
                    screen_connections = []
                    main_connection = None
                    
                    # Создаём соединения для файлов
                    # КРИТИЧНО: Используем блокировку для правильной установки main_connection
                    connection_lock = asyncio.Lock()
                    
                    async def connect_file(i):
                        nonlocal main_connection
                        try:
                            # Главное соединение (i==0) — на порт управления, остальные файловые — на порт стриминга
                            url = url_control if i == 0 else url_streaming
                            # КРИТИЧНО: Увеличиваем timeout для подключения при проблемах с сетью
                            ws = await asyncio.wait_for(
                                session.ws_connect(url, max_msg_size=200*1024*1024, heartbeat=120),
                                timeout=30.0  # Увеличено с 10 до 30 секунд для отказоустойчивости
                            )
                            
                            # КРИТИЧНО: Первое соединение (i == 0) ВСЕГДА должно иметь role='host' для получения команды 'ready' от VPS
                            # Роль определяется по индексу, а не по состоянию main_connection
                            is_main_role = (i == 0)
                            
                            # Устанавливаем main_connection только один раз для первого соединения
                            async with connection_lock:
                                if is_main_role and main_connection is None:
                                    main_connection = ws
                            
                            await ws.send_str(json.dumps({
                                'cmd': 'join',
                                'room': self.cfg['room'],
                                'password': self.cfg.get('password', ''),
                                'role': 'host' if is_main_role else 'host_file',
                                'conn_id': i,
                                'connection_type': 'file'
                            }))
                            file_connections.append(ws)
                            log(f"[OK] Connected file connection #{i+1}/{num_file_connections} (role: {'host' if is_main_role else 'host_file'})")
                        except asyncio.TimeoutError:
                            log(f"File connection #{i+1} timeout - VPS may be unavailable, will retry")
                        except (aiohttp.ClientConnectorError, OSError) as e:
                            log(f"File connection #{i+1} network error: {e} - will retry when network available")
                        except Exception as e:
                            log(f"File connection #{i+1} error: {e} - will retry")
                    
                    # Создаём соединения для экрана (порт стриминга)
                    async def connect_screen(i):
                        nonlocal main_connection
                        try:
                            # КРИТИЧНО: Увеличиваем timeout для подключения при проблемах с сетью
                            ws = await asyncio.wait_for(
                                session.ws_connect(url_streaming, max_msg_size=10*1024*1024, heartbeat=30),
                                timeout=30.0  # Увеличено с 10 до 30 секунд для отказоустойчивости
                            )
                            await ws.send_str(json.dumps({
                                'cmd': 'join',
                                'room': self.cfg['room'],
                                'password': self.cfg.get('password', ''),
                                'role': 'host_screen',
                                'conn_id': i,
                                'connection_type': 'screen'
                            }))
                            screen_connections.append(ws)
                            if not main_connection:
                                main_connection = ws
                            log(f"[OK] Connected screen connection #{i+1}/{num_screen_connections}")
                        except asyncio.TimeoutError:
                            log(f"Screen connection #{i+1} timeout - VPS may be unavailable, will retry")
                        except (aiohttp.ClientConnectorError, OSError) as e:
                            log(f"Screen connection #{i+1} network error: {e} - will retry when network available")
                        except Exception as e:
                            log(f"Screen connection #{i+1} error: {e} - will retry")
                    
                    # КРИТИЧНО: Сначала подключаем соединение с role='host' (i == 0) синхронно,
                    # чтобы гарантировать, что оно подключится первым и VPS установит его как host_main_ws
                    if num_file_connections > 0:
                        await connect_file(0)  # Подключаем первое соединение синхронно
                    
                    # Затем подключаем остальные соединения параллельно
                    tasks = []
                    if num_file_connections > 1:
                        tasks.extend([connect_file(i) for i in range(1, num_file_connections)])
                    if num_screen_connections > 0:
                        tasks.extend([connect_screen(i) for i in range(num_screen_connections)])
                    
                    if tasks:
                        await asyncio.gather(*tasks)
                    
                    # Сохраняем соединения
                    self.file_connections = file_connections
                    self.screen_connections = screen_connections
                    self.main_connection = main_connection
                    # КРИТИЧНО: self.ws должен быть основным file_connection с role='host' для получения команд от VPS
                    # VPS отправляет команду 'ready' на соединение с role='host' (host_main_ws)
                    # main_connection устанавливается правильно для первого file_connection (i==0) с role='host'
                    # Это критично для правильной работы хоста - команды от VPS приходят на это соединение
                    if main_connection:
                        # main_connection - это первое file_connection с role='host'
                        self.ws = main_connection
                        log(f"[DEBUG] Set self.ws to main_connection (first file_connection with role='host') for receiving VPS commands")
                    elif file_connections:
                        # Fallback: используем первое file_connection если main_connection не установлен
                        self.ws = file_connections[0]
                        log(f"[WARNING] Set self.ws to first file_connection (fallback - main_connection not set)")
                    else:
                        self.ws = None
                        log(f"[WARNING] No connections available for self.ws")
                    self.connections = file_connections + screen_connections  # Для обратной совместимости
                    
                    # КРИТИЧНО: Проверяем, есть ли хотя бы одно соединение
                    # Если нет - продолжаем попытки переподключения, но НЕ останавливаем хост
                    if not file_connections and not screen_connections:
                        self.vps_connected = False  # VPS отключен
                        log("[FAIL] VPS Status: Failed to connect - no connections established")
                        log("Will retry connection automatically when VPS/network becomes available...")
                        # НЕ используем break - просто выходим из блока и продолжаем цикл переподключения
                        # Это позволяет хосту автоматически переподключаться при появлении VPS или сети
                        await asyncio.sleep(5)  # Небольшая задержка перед следующей попыткой
                        continue  # Продолжаем цикл while self.running
                    
                    # КРИТИЧНО: Сбрасываем задержку переподключения при успешном подключении
                    if hasattr(self, '_reconnect_delay'):
                        log(f"Connection successful - resetting reconnect delay to 2 seconds")
                        self._reconnect_delay = 2  # Сбрасываем на начальное значение
                    
                    self.vps_connected = True  # VPS подключен
                    total_connections = len(file_connections) + len(screen_connections)
                    log(f"Host connected: {len(file_connections)} file + {len(screen_connections)} screen = {total_connections} connections active")
                    log("[OK] Connected to VPS")
                    log(f"Room: {self.cfg['room']}")
                    log(f"[DEBUG] self.ws is {'set' if self.ws else 'None'}, main_connection is {'set' if main_connection else 'None'}")
                    if self.ws:
                        try:
                            log(f"[DEBUG] self.ws.closed = {self.ws.closed}")
                        except Exception:
                            log(f"[DEBUG] Cannot check self.ws.closed")
                    log("Waiting for viewer...")
                    # КРИТИЧНО: Пауза стабилизации после подключения — иначе первый CLOSE/ошибка от сети приводит к немедленному переподключению и клиент не видит хост
                    log("Stabilizing connection (3s)...")
                    await asyncio.sleep(3)
                    self._session_start_time = time.time()
                    self._recv_seen_close_once = False
                    self._recv_error_during_stabilization = False
                    log(f"[DEBUG] Starting _recv task to receive 'ready' command from VPS")
                    self._last_vps_status_log = time.time()
                    
                    capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
                    capture_thread.start()
                    
                    # КРИТИЧНО: Запускаем _recv только если self.ws установлен
                    if self.ws:
                        recv = asyncio.create_task(self._recv(), name="recv")
                        log(f"[DEBUG] Started _recv task for receiving VPS commands")
                    else:
                        log(f"[WARNING] Cannot start _recv task - self.ws is None")
                    send = asyncio.create_task(self._send(), name="send")
                    sender = asyncio.create_task(self._sender_loop(), name="sender")
                    
                    async def keepalive(ws, conn_id):
                        while self.running and ws and not ws.closed:
                            try:
                                await asyncio.wait_for(ws.receive(), timeout=5.0)
                            except asyncio.TimeoutError:
                                continue
                            except Exception:
                                break
                    
                    keepalive_tasks = []
                    # Используем все соединения для keepalive
                    all_conns = []
                    if hasattr(self, 'file_connections') and self.file_connections:
                        all_conns.extend(self.file_connections)
                    if hasattr(self, 'screen_connections') and self.screen_connections:
                        all_conns.extend(self.screen_connections)
                    elif hasattr(self, 'connections') and self.connections:
                        all_conns.extend(self.connections)
                    for i, ws in enumerate(all_conns):
                        if i == 0:
                            continue
                        task = asyncio.create_task(keepalive(ws, i), name=f"keepalive-{i}")
                        task.add_done_callback(lambda t, cid=i: log(f"Keepalive task for conn #{cid} stopped"))
                        keepalive_tasks.append(task)
                    
                    connection_lost_flag = {'value': False}
                    
                    async def check_connection():
                        while self.running and self.ws and not connection_lost_flag['value']:
                            try:
                                if self._file_transfer_in_progress:
                                    # Во время передачи файла проверяем соединение реже
                                    await asyncio.sleep(10)  # Увеличено с 5 до 10 секунд
                                    continue
                                
                                time_since_large = 0
                                if hasattr(self, '_last_large_transfer_time'):
                                    time_since_large = time.time() - self._last_large_transfer_time
                                
                                # Увеличиваем интервал проверки после передачи файла до 30 секунд
                                check_interval = 15.0 if time_since_large < 30 else 5.0  # Увеличено для стабильности
                                await asyncio.sleep(check_interval)
                                
                                # Периодически выводим статус VPS (каждые 60 секунд, было 30)
                                current_time = time.time()
                                if current_time - self._last_vps_status_log >= 60:
                                    if self.vps_connected:
                                        log("[OK] VPS Status: Connected")
                                    else:
                                        log("[FAIL] VPS Status: Disconnected")
                                    self._last_vps_status_log = current_time
                                
                                if self._file_transfer_in_progress:
                                    log("File transfer in progress - skipping connection check")
                                    continue
                                
                                try:
                                    if self.ws.closed:
                                        log("Main connection closed - will reconnect")
                                        self.vps_connected = False  # VPS отключен
                                        log("[FAIL] VPS Status: Disconnected")
                                        connection_lost_flag['value'] = True
                                        # НЕ закрываем соединения здесь - основной цикл переподключения обработает это
                                        # Соединения будут закрыты в основном цикле при переподключении
                                        break
                                except Exception as e:
                                    log(f"Connection check failed: {e} - will reconnect")
                                    self.vps_connected = False  # VPS отключен
                                    log("[FAIL] VPS Status: Disconnected")
                                    connection_lost_flag['value'] = True
                                    # НЕ закрываем соединения здесь - это временная ошибка
                                    # Основной цикл переподключения обработает это
                                    break
                                
                                time_since_large = 0
                                if hasattr(self, '_last_large_transfer_time'):
                                    time_since_large = time.time() - self._last_large_transfer_time
                                
                                if time_since_large > 5:
                                    # Увеличиваем таймаут ping после передачи файла до 60 секунд (было 30)
                                    ping_timeout = 60.0 if time_since_large < 60 else 5.0  # Увеличено для стабильности
                                    try:
                                        await asyncio.wait_for(self.ws.send_str('{"cmd":"ping"}'), timeout=ping_timeout)
                                    except asyncio.TimeoutError:
                                        if time_since_large < 60:
                                            log(f"Ping timeout after large transfer (normal, {time_since_large:.1f}s ago) - waiting...")
                                            continue
                                        else:
                                            log("Ping timeout - connection may be lost, will reconnect")
                                            self.vps_connected = False  # VPS отключен
                                            log("[FAIL] VPS Status: Disconnected")
                                            connection_lost_flag['value'] = True
                                            # НЕ закрываем соединения здесь - это временная ошибка
                                            # Основной цикл переподключения обработает это
                                            break
                                    except (ConnectionError, OSError, aiohttp.ClientError) as e:
                                        if time_since_large < 60:
                                            log(f"Ping error after large transfer (normal, {time_since_large:.1f}s ago): {e} - waiting...")
                                            continue
                                        else:
                                            log(f"Ping error: {e} - connection lost, will reconnect")
                                            self.vps_connected = False  # VPS отключен
                                            log("[FAIL] VPS Status: Disconnected")
                                            connection_lost_flag['value'] = True
                                            # НЕ закрываем соединения здесь - это временная ошибка
                                            # Основной цикл переподключения обработает это
                                            break
                                    except Exception as e:
                                        if time_since_large < 30:
                                            log(f"Ping unexpected error after large transfer (normal, {time_since_large:.1f}s ago): {e} - waiting...")
                                            continue
                                        else:
                                            log(f"Ping unexpected error: {e} - will reconnect")
                                            connection_lost_flag['value'] = True
                                            self.vps_connected = False  # VPS отключен
                                            log("[FAIL] VPS Status: Disconnected")
                                            # НЕ закрываем соединения здесь - это временная ошибка
                                            # Основной цикл переподключения обработает это
                                            break
                                else:
                                    # КРИТИЧНО: Проверяем реальное состояние передачи, а не только время
                                    # Если передача завершилась, не ждем полные 60 секунд
                                    if self._file_transfer_in_progress:
                                        # Передача идет - ждем немного
                                        log(f"Skipping ping - file transfer in progress")
                                        await asyncio.sleep(5)
                                    elif time_since_large < 10:
                                        # Недавно завершилась передача - ждем короткое время (10 секунд вместо 60)
                                        remaining_wait = max(0, 10 - time_since_large)
                                        if remaining_wait > 0:
                                            log(f"Skipping ping - recent file transfer completed ({time_since_large:.1f}s ago, waiting {remaining_wait:.1f}s more)")
                                            await asyncio.sleep(min(remaining_wait, 5))
                                        else:
                                            # Можно отправлять ping
                                            await asyncio.sleep(2)
                                    else:
                                        # Прошло достаточно времени - отправляем ping нормально
                                        await asyncio.sleep(2)
                            except Exception as e:
                                if self._file_transfer_in_progress:
                                    log(f"Connection check error during file transfer - ignoring: {e}")
                                    await asyncio.sleep(2)
                                    continue
                                log(f"Connection check error: {e} - will reconnect")
                                self.vps_connected = False  # VPS отключен
                                log("✗ VPS Status: Disconnected")
                                connection_lost_flag['value'] = True
                                # НЕ закрываем соединения здесь - это временная ошибка
                                # Основной цикл переподключения обработает это
                                break
                    
                    async def viewer_watchdog():
                        while self.running and self.ws:
                            await asyncio.sleep(5)  # Проверяем каждые 5 секунд вместо 2
                            
                            # КРИТИЧНО: Проверяем что соединение действительно закрыто перед проверкой таймаута
                            try:
                                if self.ws.closed:
                                    log("WebSocket closed in watchdog - will reconnect")
                                    break
                            except Exception:
                                log("WebSocket check failed in watchdog - will reconnect")
                                break
                            
                            if self._file_transfer_in_progress:
                                log("File transfer in progress - extending viewer timeout")
                                self.last_viewer_msg_time = time.time()
                                continue
                            
                            if self.streaming and self.last_viewer_msg_time:
                                # Увеличено время ожидания до 120 секунд для большей стабильности
                                # Это позволяет пережить временные проблемы с сетью и множественные ready команды
                                timeout_seconds = 120
                                time_since_last_msg = time.time() - self.last_viewer_msg_time
                                if time_since_last_msg > timeout_seconds:
                                    has_active_downloads = len(self._download_sessions) > 0
                                    if not has_active_downloads:
                                        log(f"Viewer timeout ({time_since_last_msg:.1f}s > {timeout_seconds}s) - stopping stream")
                                        self.streaming = False
                                        self.viewer_connected = False
                                        while not self.send_queue.empty():
                                            try:
                                                self.send_queue.get_nowait()
                                            except queue.Empty:
                                                break
                                            except Exception as e:
                                                log(f"Error clearing send queue: {e}")
                                                break
                                    else:
                                        log(f"Viewer timeout extended - file download in progress ({time_since_last_msg:.1f}s)")
                                        self.last_viewer_msg_time = time.time()
                                elif time_since_last_msg > 90:
                                    # Предупреждение если долго нет сообщений, но еще не timeout
                                    log(f"WARNING: No viewer messages for {time_since_last_msg:.1f}s (timeout in {timeout_seconds - time_since_last_msg:.1f}s)")
                    check = asyncio.create_task(check_connection(), name="check")
                    watchdog = asyncio.create_task(viewer_watchdog(), name="watchdog")
                    
                    try:
                        # Ждем только критичные задачи, чтобы не ронять соединение из-за keepalive
                        critical_tasks = [recv, sender, check]
                        done, pending = await asyncio.wait(
                            critical_tasks,
                            return_when=asyncio.FIRST_COMPLETED,
                            timeout=None
                        )
                        
                        done_names = [t.get_name() for t in done]
                        pending_names = [t.get_name() for t in pending]
                        log(f"Task completed: {len(done)} done ({done_names}), {len(pending)} pending ({pending_names})")
                        
                        # Проверяем, какая задача завершилась
                        for task in done:
                            try:
                                result = await task
                                if isinstance(result, Exception):
                                    log(f"Task completed with error: {result}")
                                    # Если это ошибка соединения во время передачи файла - не закрываем сразу
                                    if self._file_transfer_in_progress:
                                        log("Task error during file transfer - waiting for completion...")
                                        continue
                            except Exception as e:
                                log(f"Task error: {e}")
                                # Если это ошибка во время передачи файла - не закрываем сразу
                                if self._file_transfer_in_progress:
                                    log("Task exception during file transfer - waiting for completion...")
                                    continue
                        
                        # КРИТИЧНО: Проверяем, идет ли передача файла ПЕРЕД отменой задач
                        if self._file_transfer_in_progress:
                            log("File transfer in progress - NOT cancelling tasks, waiting for completion...")
                            # НЕ отменяем задачи во время передачи файла!
                            # Ждем завершения передачи с таймаутом
                            wait_start = time.time()
                            max_wait = 60  # Максимум 60 секунд ожидания
                            while self._file_transfer_in_progress and self.running:
                                if time.time() - wait_start > max_wait:
                                    log(f"WARNING: File transfer timeout after {max_wait}s, forcing completion")
                                    break
                                await asyncio.sleep(0.5)
                            log("File transfer completed - now can cancel tasks")
                        
                        # УБРАЛИ задержку после передачи файла - клиент обрабатывает FILE_END сразу
                        # Нет необходимости ждать - клиент уже получил все данные и FILE_END
                        
                        # Отменяем задачи только если передача файла не идет
                        if not self._file_transfer_in_progress:
                            log("Cancelling pending tasks...")
                            for task in pending:
                                task.cancel()
                                try:
                                    await task
                                except (asyncio.CancelledError, Exception) as e:
                                    # Игнорируем ошибки отмены задач - это нормально
                                    pass
                            log("All tasks cancelled")
                        else:
                            log("WARNING: File transfer still in progress, skipping task cancellation")
                    
                    except Exception as e:
                        log(f"Tasks wait error: {e}")
                    
                    # Дополнительная проверка перед закрытием соединений
                    if self._file_transfer_in_progress:
                        log("File transfer in progress - waiting before closing connections...")
                        while self._file_transfer_in_progress and self.running:
                            await asyncio.sleep(0.5)
                        log("File transfer completed - can now close connections")
                    
                    log("Closing all connections...")
                    # Закрываем все типы соединений
                    all_conns_to_close = []
                    if hasattr(self, 'file_connections') and self.file_connections:
                        all_conns_to_close.extend(list(self.file_connections))
                    if hasattr(self, 'screen_connections') and self.screen_connections:
                        all_conns_to_close.extend(list(self.screen_connections))
                    if hasattr(self, 'connections') and self.connections:
                        all_conns_to_close.extend(list(self.connections))
                    if hasattr(self, 'main_connection') and self.main_connection:
                        all_conns_to_close.append(self.main_connection)
                    
                    for conn in all_conns_to_close:
                        try:
                            if conn and not conn.closed:
                                await conn.close()
                        except Exception as e:
                            # Игнорируем ошибки закрытия - соединение может быть уже закрыто
                            pass
                    
                    # Очищаем списки соединений
                    if hasattr(self, 'file_connections'):
                        self.file_connections.clear()
                    if hasattr(self, 'screen_connections'):
                        self.screen_connections.clear()
                    if hasattr(self, 'connections'):
                        self.connections.clear()
                    self.main_connection = None
                    
                    log("All connections closed, ready for reconnection")
                    
                    for task in [recv, send, sender, check, watchdog] + keepalive_tasks:
                        if not task.done():
                            task.cancel()
                            try:
                                await task
                            except (asyncio.CancelledError, Exception) as e:
                                # Игнорируем ошибки отмены задач - это нормально
                                pass
                    
                    log("Exiting session context for reconnection...")
                    # КРИТИЧНО: НЕ используем raise - это может привести к закрытию хоста
                    # Вместо этого просто выходим из блока try и продолжаем цикл переподключения
                    # raise ConnectionError("Connection lost - reconnecting")  # УБРАНО
                    # Просто выходим из блока - цикл while self.running продолжит работу
                        
            except aiohttp.ClientConnectorError as e:
                # КРИТИЧНО: Ошибка подключения - VPS недоступен или нет сети
                # Хост должен продолжать попытки переподключения ВЕЧНО
                log(f"Connection error (network/VPS unavailable): {e}")
                log("Host will continue running and retry connection automatically when VPS/network becomes available...")
                self.vps_connected = False
                log("[FAIL] VPS Status: Connection error - will retry automatically")
                # Продолжаем цикл - хост работает вечно
            except aiohttp.ClientError as e:
                # КРИТИЧНО: Ошибка клиента - продолжаем попытки переподключения ВЕЧНО
                log(f"Connection error: {e}")
                log("Host will continue running and retry connection automatically...")
                self.vps_connected = False
                log("[FAIL] VPS Status: Client error - will retry automatically")
                # Продолжаем цикл - хост работает вечно
            except OSError as e:
                # КРИТИЧНО: Ошибка сети - нет интернета или проблемы с сетью
                # Хост должен продолжать попытки переподключения ВЕЧНО
                log(f"Network error (no internet or network issue): {e}")
                log("Host will continue running and retry connection automatically when network becomes available...")
                self.vps_connected = False
                log("[FAIL] VPS Status: Network error - will retry automatically")
                # Продолжаем цикл - хост работает вечно
            except Exception as e:
                # КРИТИЧНО: Обрабатываем ВСЕ исключения и продолжаем работу ВЕЧНО
                # Хост НИКОГДА не закрывается сам по себе
                log(f"Unexpected error in main loop: {e}")
                import traceback
                log(f"Traceback: {traceback.format_exc()}")
                self.vps_connected = False
                log("[FAIL] VPS Status: Unexpected error - will reconnect automatically")
                log("Host will continue running and retry connection automatically...")
                # НЕ останавливаем хост - продолжаем попытки переподключения ВЕЧНО
            finally:
                # КРИТИЧНО: Сохраняем состояние перед отключением для восстановления
                was_streaming = getattr(self, 'streaming', False)
                was_viewer_connected = getattr(self, 'viewer_connected', False)
                
                self.streaming = False
                self.viewer_connected = False
                self.ws = None
                # Очищаем все типы соединений
                if hasattr(self, 'connections'):
                    self.connections = []
                if hasattr(self, 'file_connections'):
                    self.file_connections = []
                if hasattr(self, 'screen_connections'):
                    self.screen_connections = []
                self.main_connection = None
                while not self.send_queue.empty():
                    try:
                        self.send_queue.get_nowait()
                    except queue.Empty:
                        break
                    except Exception as e:
                        log(f"Error clearing send queue in finally: {e}")
                        break
                
                # КРИТИЧНО: НЕ сохраняем состояние стрима для автоматического восстановления
                # Стрим должен запускаться ТОЛЬКО по команде stream_start от клиента
                # self._restore_streaming = was_streaming  # УБРАНО - не восстанавливаем автоматически
                self._restore_viewer_connected = was_viewer_connected
            
            # КРИТИЧНО: Всегда продолжаем попытки переподключения пока self.running = True
            # Хост НЕ должен закрываться сам по себе - только при явном прерывании (KeyboardInterrupt)
            # Хост автоматически переподключится при появлении VPS или сети
            # Хост работает ВЕЧНО и всегда на связи
            if self.running:
                # КРИТИЧНО: Используем экспоненциальную задержку для переподключения
                # Начинаем с 2 секунд, увеличиваем до максимума 60 секунд
                # Это позволяет хосту не перегружать сеть при длительном отсутствии VPS
                if not hasattr(self, '_reconnect_delay'):
                    self._reconnect_delay = 2.0
                else:
                    # Увеличиваем задержку экспоненциально, но не более максимума
                    self._reconnect_delay = min(self._reconnect_delay * 1.5, self._max_reconnect_delay)
                
                log(f"Reconnecting in {self._reconnect_delay:.1f} seconds... (will retry automatically when VPS/network available)")
                log("Host is always running - will automatically reconnect when network/VPS becomes available")
                await asyncio.sleep(self._reconnect_delay)
                
                # Задержка будет сброшена при успешном подключении (см. выше)
                # Продолжаем цикл - хост работает вечно
            else:
                # self.running = False только при KeyboardInterrupt - это нормально
                log("Host stopped (self.running = False)")
                break
    
    async def _recv(self):
        try:
            if not self.ws:
                log("[DEBUG] _recv: self.ws is None, cannot receive messages")
                return
            
            log(f"[DEBUG] _recv: Starting receive loop, self.ws is {'set' if self.ws else 'None'}")
            msg_count = 0
            
            while self.running and self.ws:
                try:
                    try:
                        if self.ws.closed:
                            log("WebSocket closed in recv - will reconnect")
                            break
                    except Exception:
                        log("WebSocket check failed in recv - will reconnect")
                        break
                    
                    msg = await asyncio.wait_for(self.ws.receive(), timeout=2.0)
                    msg_count += 1
                    if msg_count == 1:
                        log(f"[DEBUG] _recv: Received first message, type={msg.type}")
                except asyncio.TimeoutError:
                    # Таймаут - это нормально, продолжаем ждать сообщения
                    # КРИТИЧНО: Обновляем last_viewer_msg_time даже при таймауте, если соединение активно
                    # Это предотвращает ложные отключения из-за отсутствия сообщений
                    try:
                        if self.ws and not self.ws.closed:
                            # Соединение активно, просто нет сообщений - это нормально
                            # Обновляем last_viewer_msg_time чтобы watchdog не считал соединение мертвым
                            if self.viewer_connected:
                                self.last_viewer_msg_time = time.time()
                            continue
                        else:
                            log("Connection closed during receive timeout - will reconnect")
                            break
                    except Exception as e:
                        log(f"Connection check failed during receive timeout: {e} - will reconnect")
                        break
                except (ConnectionError, OSError, aiohttp.ClientError) as e:
                    session_age = time.time() - getattr(self, '_session_start_time', 0)
                    if session_age < 15.0 and not getattr(self, '_recv_error_during_stabilization', False):
                        self._recv_error_during_stabilization = True
                        log(f"[INFO] Receive error during stabilization ({session_age:.1f}s), retrying: {e}")
                        await asyncio.sleep(1)
                        continue
                    log(f"Receive error: {e} - will reconnect")
                    break
                except Exception as e:
                    session_age = time.time() - getattr(self, '_session_start_time', 0)
                    if session_age < 15.0 and not getattr(self, '_recv_error_during_stabilization', False):
                        self._recv_error_during_stabilization = True
                        log(f"[INFO] Receive unexpected error during stabilization ({session_age:.1f}s), retrying: {e}")
                        await asyncio.sleep(1)
                        continue
                    log(f"Receive unexpected error: {e} - will reconnect")
                    break
                
                if msg.type == aiohttp.WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        cmd = data.get('cmd')
                        if cmd != 'pong':  # Не логируем pong для уменьшения спама
                            log(f"[DEBUG] _recv: Received command: '{cmd}'")
                        self.last_viewer_msg_time = time.time()
                        
                        if cmd == 'error':
                            error_msg = data.get('message', 'Unknown error')
                            if 'access denied' in error_msg.lower() or 'wrong' in error_msg.lower():
                                log(f"Access denied: {error_msg}")
                                log(f"Please check room ID and password in host_config.json")
                                # КРИТИЧНО: НЕ останавливаем хост при ошибке доступа - продолжаем работу
                                # Хост должен продолжать попытки подключения, возможно проблема временная
                                log("WARNING: Access denied, but continuing to run - will retry connection")
                                # НЕ устанавливаем self.running = False - хост должен продолжать работу
                                # НЕ делаем break - продолжаем обработку сообщений
                                continue
                        
                        if cmd == 'ready':
                            was_connected = self.viewer_connected
                            self.viewer_connected = True
                            self.last_viewer_msg_time = time.time()
                            
                            if not was_connected:
                                log("[OK] Viewer connected!")
                                self.streaming = False
                                
                                # КРИТИЧНО: Отправляем текущие параметры стрима клиенту при подключении
                                # Это позволяет клиенту синхронизировать UI с реальными параметрами хоста
                                try:
                                    config_info = {
                                        'cmd': 'stream_config_info',
                                        'quality': self.cfg.get('quality', 70),
                                        'fps': self.cfg.get('fps', 30),
                                        'scale': self.cfg.get('scale', 80)
                                    }
                                    # Используем asyncio для отправки через WebSocket
                                    if self.loop and not self.loop.is_closed():
                                        asyncio.run_coroutine_threadsafe(
                                            self.ws.send_str(json.dumps(config_info)),
                                            self.loop
                                        )
                                        log(f"[OK] Sent stream config to client: quality={config_info['quality']}, fps={config_info['fps']}, scale={config_info['scale']}")
                                    else:
                                        log(f"[WARNING] Cannot send stream config - loop not available")
                                except Exception as e:
                                    log(f"[WARNING] Failed to send stream config to client: {e}")
                                # Отправляем список файлов при первом подключении зрителя (клиент ожидает список без ручного Refresh)
                                try:
                                    threading.Thread(target=self._file_list, args=({'path': '/'},), daemon=True).start()
                                    log("[OK] Sending initial file list to viewer")
                                except Exception as e:
                                    log(f"[WARNING] Failed to send initial file list: {e}")
                        elif cmd == 'viewer_left':
                            log("Viewer left - keeping connection")
                            self.streaming = False
                            self.viewer_connected = False
                            while not self.send_queue.empty():
                                try:
                                    self.send_queue.get_nowait()
                                except queue.Empty:
                                    break
                                except Exception as e:
                                    log(f"Error clearing send queue: {e}")
                                    break
                        elif cmd == 'stream_start':
                            log("Stream started")
                            self.streaming = True
                            self.frames_since_start = 0
                        elif cmd == 'stream_stop':
                            log("Stream stopped")
                            self.streaming = False
                        elif cmd == 'ping':
                            # КРИТИЧНО: Клиент отправляет ping для heartbeat - отвечаем pong
                            # Это поддерживает соединение и обновляет last_viewer_msg_time
                            self.last_viewer_msg_time = time.time()
                            try:
                                await self.ws.send_str('{"cmd":"pong"}')
                            except Exception as e:
                                log(f"Error sending pong response: {e}")
                        elif cmd == 'pong':
                            # КРИТИЧНО: Обновляем last_viewer_msg_time при получении pong
                            # Это может быть pong от VPS (на ping от хоста) или от клиента (на ping от хоста)
                            self.last_viewer_msg_time = time.time()
                            # НЕ логируем pong - это нормальная операция
                        elif cmd == 'control':
                            self._control(data)
                        elif cmd == 'terminal':
                            c = data.get('data', '')
                            if c:
                                threading.Thread(target=self._terminal, args=(c,), daemon=True).start()
                        elif cmd == 'file_list':
                            threading.Thread(target=self._file_list, args=(data,), daemon=True).start()
                        elif cmd == 'file_download':
                            threading.Thread(target=self._file_download, args=(data,), daemon=True).start()
                        elif cmd == 'set_stream_config':
                            # Изменение параметров стрима в реальном времени
                            log(f"[INFO] Received set_stream_config command: quality={data.get('quality')}, fps={data.get('fps')}, scale={data.get('scale')}")
                            threading.Thread(target=self._set_stream_config, args=(data,), daemon=True).start()
                        elif cmd == 'file_download_cancel':
                            # КРИТИЧНО: Моментальная отмена загрузки
                            download_id = data.get('download_id')
                            folder_id = data.get('folder_id')
                            if download_id:
                                self._download_cancelled[download_id] = True
                                log(f"Download cancelled immediately: {download_id}")
                                # Удаляем сессию для моментальной остановки
                                if download_id in self._download_sessions:
                                    del self._download_sessions[download_id]
                                # Отправляем подтверждение отмены клиенту
                                _safe_send_json(self.ws, self.loop, {
                                    'cmd': 'file_download_cancelled',
                                    'download_id': download_id
                                })
                            if folder_id:
                                self._download_cancelled[folder_id] = True
                                log(f"Folder download cancelled immediately: {folder_id}")
                                # Удаляем сессию для моментальной остановки
                                if folder_id in self._download_sessions:
                                    del self._download_sessions[folder_id]
                                # Отправляем подтверждение отмены клиенту
                                _safe_send_json(self.ws, self.loop, {
                                    'cmd': 'file_download_cancelled',
                                    'download_id': folder_id
                                })
                        elif cmd == 'file_upload':
                            threading.Thread(target=self._file_upload, args=(data,), daemon=True).start()
                        elif cmd == 'file_upload_info':
                            threading.Thread(target=self._file_upload_init, args=(data,), daemon=True).start()
                        elif cmd == 'file_upload_chunk':
                            self._file_upload_chunk(data)
                        elif cmd == 'file_delete':
                            threading.Thread(target=self._file_delete, args=(data,), daemon=True).start()
                        elif cmd == 'file_edit':
                            threading.Thread(target=self._file_edit, args=(data,), daemon=True).start()
                        elif cmd == 'file_monitor':
                            threading.Thread(target=self._file_monitor, args=(data,), daemon=True).start()
                        elif cmd == 'service_start':
                            threading.Thread(target=self._service_start, args=(data,), daemon=True).start()
                        elif cmd == 'service_stop':
                            threading.Thread(target=self._service_stop, args=(data,), daemon=True).start()
                        elif cmd == 'service_restart':
                            threading.Thread(target=self._service_restart, args=(data,), daemon=True).start()
                        elif cmd == 'program_run':
                            threading.Thread(target=self._program_run, args=(data,), daemon=True).start()
                    except json.JSONDecodeError as e:
                        log(f"[ERROR] _recv: Failed to parse JSON: {e}, raw data: {str(raw_data)[:500] if raw_data else 'None'}")
                    except Exception as e:
                        log(f"[ERROR] Error processing command '{cmd if 'cmd' in locals() else 'unknown'}': {e}")
                        import traceback
                        log(f"[ERROR] Traceback: {traceback.format_exc()}")
                elif msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.ERROR):
                    # КРИТИЧНО: В первые 15 сек после подключения игнорируем первый CLOSE/ERROR (часто ложное срабатывание от сети/прокси)
                    session_age = time.time() - getattr(self, '_session_start_time', 0)
                    if session_age < 15.0 and not getattr(self, '_recv_seen_close_once', False):
                        self._recv_seen_close_once = True
                        log(f"[INFO] Ignoring first CLOSE/ERROR during stabilization ({session_age:.1f}s) — continuing")
                        continue
                    self.streaming = False
                    self.viewer_connected = False
                    break
        except Exception as e:
            # КРИТИЧНО: Обрабатываем все исключения в recv loop
            # Хост НЕ должен закрываться из-за ошибок приема сообщений
            log(f"Error in recv loop: {e}")
            import traceback
            log(f"Recv traceback: {traceback.format_exc()}")
            # Продолжаем работу - хост должен переподключиться
    
    def _capture_loop(self):
        # КРИТИЧНО: НЕ логируем "Stream: Starting" здесь - это создает впечатление, что стрим запущен
        # Лог будет выведен только когда стрим действительно запустится (когда self.streaming = True)
        stream_started_logged = False
        _last_logged_fps = None
        _last_logged_quality = None
        _last_logged_scale = None
        
        fc = bs = 0
        t0 = time.time()
        next_frame = time.time()
        
        with mss.mss() as sct:
            monitor = sct.monitors[1]
            
            while self.running:
                # КРИТИЧНО: Перечитываем параметры из конфига при каждом цикле для поддержки изменения в реальном времени
                fps = self.cfg['fps']
                interval = 1.0 / fps
                scale = self.cfg['scale'] / 100.0
                base_quality = self.cfg['quality']
                # Начинаем с базового качества - оптимизация происходит через прогрессивный JPEG и оптимизацию Huffman
                quality = base_quality
                max_frame_size = 100 * 1024  # 100KB — меньший кадр быстрее доходит до клиента при медленном канале (Sent>>0)
                
                if not self.streaming:
                    if stream_started_logged:
                        log("Stream stopped - waiting for stream_start command")
                        stream_started_logged = False
                    time.sleep(0.01)  # Минимум — только чтобы не грузить CPU
                    next_frame = time.time()
                    continue
                
                # Логируем запуск стрима только один раз, когда он действительно запускается
                if not stream_started_logged:
                    log(f"Stream: FPS={fps} Scale={int(scale*100)}% Starting Q={quality}")
                    stream_started_logged = True
                    _last_logged_fps = fps
                    _last_logged_quality = quality
                    _last_logged_scale = int(scale*100)
                else:
                    # Логируем изменения параметров в реальном времени (только если они действительно изменились)
                    current_scale = int(scale*100)
                    if fps != _last_logged_fps or quality != _last_logged_quality or current_scale != _last_logged_scale:
                        log(f"[INFO] Stream config applied: FPS={fps} Scale={current_scale}% Quality={quality}")
                        _last_logged_fps = fps
                        _last_logged_quality = quality
                        _last_logged_scale = current_scale
                
                # КРИТИЧНО: Контроль FPS - ВСЕГДА ждем до next_frame для точного соблюдения целевого FPS
                # Это гарантирует что мы не превышаем целевой FPS
                current_time = time.time()
                if current_time < next_frame:
                    # Вовремя - ждем до следующего кадра
                    sleep_time = next_frame - current_time
                    if sleep_time > 0.001:  # Ждем только если больше 1мс
                        time.sleep(sleep_time)
                    next_frame += interval
                else:
                    # Опоздали - устанавливаем следующий кадр через interval от текущего времени
                    # КРИТИЧНО: Обрабатываем текущий кадр, но следующий будет строго через interval
                    # Это позволяет догнать целевую скорость, но не превышать её
                    next_frame = current_time + interval
                
                try:
                    # КРИТИЧНО: Оптимизированный захват экрана
                    screenshot = sct.grab(monitor)
                    # КРИТИЧНО: Используем более быстрый способ конвертации в numpy array
                    # np.array() может быть медленным, используем прямое преобразование через buffer
                    img = np.frombuffer(screenshot.rgb, dtype=np.uint8).reshape((screenshot.height, screenshot.width, 3))
                    # КРИТИЧНО: MSS возвращает RGB в .rgb, а OpenCV imencode ожидает BGR — конвертируем для правильных цветов
                    img = cv2.cvtColor(img, cv2.COLOR_RGB2BGR)
                    
                    # КРИТИЧНО: Используем INTER_LINEAR для уменьшения - быстрее чем INTER_AREA
                    # INTER_LINEAR быстрее на 10-15% чем INTER_AREA, что критично для высокого FPS
                    # Качество немного хуже, но для стрима это приемлемо
                    if scale < 1.0:
                        h, w = img.shape[:2]
                        img = cv2.resize(img, (int(w*scale), int(h*scale)), interpolation=cv2.INTER_NEAREST)  # быстрее INTER_LINEAR
                    
                    # КРИТИЧНО: Для высокой скорости FPS убираем прогрессивный JPEG и оптимизацию Huffman
                    # Они замедляют кодирование на 20-30%, что критично для высокого FPS
                    # Используем только базовое качество JPEG для максимальной скорости
                    encode_params = [cv2.IMWRITE_JPEG_QUALITY, quality]
                    _, data = cv2.imencode('.jpg', img, encode_params)
                    data = data.tobytes()
                    
                    self.frames_since_start += 1
                    
                    # КРИТИЧНО: Оптимизированное управление качеством для максимальной скорости
                    # Перекодируем только если кадр слишком большой, и делаем это максимально быстро
                    if len(data) > max_frame_size:
                        # Снижаем качество более агрессивно за один раз для минимизации перекодирований
                        quality = max(20, quality - 25)  # Более агрессивное снижение для скорости
                        encode_params = [cv2.IMWRITE_JPEG_QUALITY, quality]
                        _, data = cv2.imencode('.jpg', img, encode_params)
                        data = data.tobytes()
                        # Если все еще большой - еще раз, но это редко (только если кадр очень большой)
                        if len(data) > max_frame_size:
                            quality = max(15, quality - 15)
                            encode_params = [cv2.IMWRITE_JPEG_QUALITY, quality]
                            _, data = cv2.imencode('.jpg', img, encode_params)
                            data = data.tobytes()
                    elif len(data) < max_frame_size * 0.6 and self.frames_since_start > 50:
                        # Повышаем качество только если кадр маленький, стрим стабилен И прошло достаточно времени
                        # Это предотвращает постоянные перекодирования, которые замедляют FPS
                        if quality < base_quality:
                            quality = min(base_quality, quality + 2)  # Повышаем быстрее для стабильности
                    
                    # КРИТИЧНО: При переполнении очереди удаляем СТАРЫЕ кадры, чтобы отправлять свежие
                    # Это предотвращает задержку и прыгание - всегда отправляем последние кадры
                    # КРИТИЧНО: Первые 10 кадров не пропускаются даже при переполнении - это гарантирует плавный старт
                    try:
                        self.send_queue.put_nowait(data)
                    except queue.Full:
                        # Очередь переполнена
                        # КРИТИЧНО: Первые 10 кадров не пропускаются - удаляем старый, но новый добавляем
                        # Это гарантирует плавный старт стрима без перепрыгивания
                        if self.frames_since_start <= 10:
                            # Первые 10 кадров - удаляем самый старый, но новый добавляем
                            try:
                                self.send_queue.get_nowait()  # Удаляем самый старый кадр
                                self.send_queue.put_nowait(data)  # Добавляем новый свежий кадр
                            except (queue.Empty, queue.Full):
                                pass  # Если не получилось - пропускаем новый кадр
                        else:
                            # После первых 10 кадров - удаляем самый старый кадр и добавляем новый
                            try:
                                self.send_queue.get_nowait()  # Удаляем самый старый кадр
                                self.send_queue.put_nowait(data)  # Добавляем новый свежий кадр
                            except (queue.Empty, queue.Full):
                                pass  # Если не получилось - пропускаем новый кадр
                    
                    fc += 1
                    bs += len(data)
                    
                    if time.time() - t0 > 3:
                        actual_fps = fc/(time.time()-t0)
                        target_fps = self.cfg.get('fps', 15)
                        if actual_fps < target_fps * 0.8:  # Если реальный FPS меньше 80% от целевого
                            log(f"FPS: {actual_fps:.0f} (target: {target_fps}) | {bs/1024/(time.time()-t0):.0f} KB/s - WARNING: FPS below target")
                        else:
                            log(f"FPS: {actual_fps:.0f} | {bs/1024/(time.time()-t0):.0f} KB/s")
                        fc = bs = 0
                        t0 = time.time()
                        
                except Exception as e:
                    import traceback
                    log(f"Capture error: {e}")
                    log(f"Traceback: {traceback.format_exc()}")
                    time.sleep(0.01)  # Минимум перед повтором
    
    async def _send(self):
        while self.running:
            await asyncio.sleep(1)
    
    def _control(self, d):
        try:
            a = d.get('action')
            s = self.cfg['scale'] / 100.0
            
            if a == 'mouse_move':
                pyautogui.moveTo(int(d.get('x',0)/s), int(d.get('y',0)/s), _pause=False)
            elif a == 'mouse_click':
                pyautogui.click(button=d.get('button','left'), _pause=False)
            elif a == 'mouse_double':
                pyautogui.doubleClick(_pause=False)
            elif a == 'mouse_scroll':
                pyautogui.scroll(d.get('delta',0), _pause=False)
            elif a == 'key_press':
                pyautogui.press(d.get('key',''), _pause=False)
            elif a == 'key_type':
                pyautogui.write(d.get('text',''), interval=0.01, _pause=False)
        except Exception as e:
            # Игнорируем ошибки управления - могут быть проблемы с доступом к экрану
            log(f"Control error: {e}")
    
    async def _sender_loop(self):
        """Отправка кадров экрана через screen_connections"""
        last_send_time = time.time()
        consecutive_errors = 0
        # КРИТИЧНО: НЕ используем frame_index для распределения - только одно соединение для последовательности
        
        while self.running:
            try:
                try:
                    data = self.send_queue.get(timeout=0.1)
                except queue.Empty:
                    if self.streaming and time.time() - last_send_time > 2.0:
                        # КРИТИЧНО: Используем screen_connections для ping во время стрима
                        ws_to_ping = None
                        if hasattr(self, 'screen_connections') and self.screen_connections:
                            active_screen = [ws for ws in self.screen_connections if ws and not ws.closed]
                            if active_screen:
                                ws_to_ping = active_screen[0]
                        elif hasattr(self, 'file_connections') and self.file_connections:
                            active_file = [ws for ws in self.file_connections if ws and not ws.closed]
                            if active_file:
                                ws_to_ping = active_file[0]
                        elif self.ws and not self.ws.closed:
                            ws_to_ping = self.ws
                        
                        if ws_to_ping:
                            try:
                                await asyncio.wait_for(ws_to_ping.send_str('{"cmd":"ping"}'), timeout=1.0)
                            except Exception:
                                log("Connection check failed - may be stuck")
                                consecutive_errors += 1
                                if consecutive_errors > 10:
                                    # Только останавливаем стрим, но НЕ закрываем соединения
                                    # Основной цикл переподключения обработает это
                                    log(f"Too many connection check errors ({consecutive_errors}) - stopping stream, will reconnect")
                                    self.streaming = False
                                    break
                    await asyncio.sleep(0.001)  # Минимум — быстрая реакция на появление ws
                    continue
                
                try:
                    if self.ws and not self.ws.closed:
                        try:
                            timeout = 5.0
                            if hasattr(self, '_last_large_transfer_time'):
                                time_since_large = time.time() - self._last_large_transfer_time
                                if time_since_large < 10:
                                    timeout = 15.0
                            
                            # КРИТИЧНО: Для экрана используем ТОЛЬКО одно соединение для последовательной передачи
                            # Множественные соединения для экрана вызывают проблемы с порядком кадров
                            ws_to_use = None
                            
                            # Приоритет 1: screen_connections (специально для кадров)
                            if hasattr(self, 'screen_connections') and self.screen_connections:
                                # Фильтруем закрытые соединения
                                active_screen = [ws for ws in self.screen_connections if ws and not ws.closed]
                                if active_screen:
                                    # Используем только первое активное соединение для последовательной передачи кадров
                                    ws_to_use = active_screen[0]
                            
                            # КРИТИЧНО: НЕ используем file_connections для кадров - они для файлов!
                            # Это гарантирует независимость стрима и файлов
                            # Если нет screen_connections - используем только self.ws
                            
                            # КРИТИЧНО: НЕ используем self.ws или main_connection если они file_connections
                            # Проверяем что self.ws не является file_connection
                            if not ws_to_use and self.ws and not self.ws.closed:
                                # КРИТИЧНО: Проверяем что self.ws НЕ является file_connection
                                if not (hasattr(self, 'file_connections') and self.ws in self.file_connections):
                                    ws_to_use = self.ws
                            
                            # Приоритет 4: main_connection (последний резерв, только если не file_connection)
                            if not ws_to_use and hasattr(self, 'main_connection') and self.main_connection and not self.main_connection.closed:
                                # КРИТИЧНО: Проверяем что main_connection НЕ является file_connection
                                if not (hasattr(self, 'file_connections') and self.main_connection in self.file_connections):
                                    ws_to_use = self.main_connection
                            
                            if ws_to_use:
                                await asyncio.wait_for(ws_to_use.send_bytes(data), timeout=timeout)
                            else:
                                # Нет доступных соединений - пропускаем кадр
                                # Логируем только периодически, чтобы не спамить
                                if time.time() - last_send_time > 5.0:
                                    log("No screen connections available - skipping frame (will retry when connections available)")
                                    last_send_time = time.time()
                            last_send_time = time.time()
                            consecutive_errors = 0
                        except asyncio.TimeoutError:
                            log("Send timeout - connection may be lost")
                            consecutive_errors += 1
                            max_errors = 10 if hasattr(self, '_last_large_transfer_time') and time.time() - self._last_large_transfer_time < 10 else 5
                            if consecutive_errors > max_errors:
                                # Только останавливаем стрим, но НЕ закрываем соединения
                                # Основной цикл переподключения обработает это
                                log(f"Too many send timeouts ({consecutive_errors}) - stopping stream, will reconnect")
                                self.streaming = False
                                break
                        except (ConnectionError, OSError, aiohttp.ClientError, aiohttp.ClientConnectionResetError) as e:
                            log(f"Send error: {e}")
                            consecutive_errors += 1
                            max_errors = 10 if hasattr(self, '_last_large_transfer_time') and time.time() - self._last_large_transfer_time < 10 else 5
                            if consecutive_errors > max_errors:
                                # Только останавливаем стрим, но НЕ закрываем соединения
                                # Основной цикл переподключения обработает это
                                log(f"Too many send errors ({consecutive_errors}) - stopping stream, will reconnect")
                                self.streaming = False
                                break
                        except Exception as e:
                            consecutive_errors += 1
                            try:
                                if self.ws.closed:
                                    log("Connection closed during send")
                                    self.streaming = False
                                    break
                            except Exception:
                                log("Connection check failed during send")
                                self.streaming = False
                                break
                    else:
                        self.streaming = False
                        break
                except Exception as e:
                    log(f"Connection check error in sender: {e}")
                    self.streaming = False
                    break
            except Exception as e:
                log(f"Sender loop error: {e}")
                await asyncio.sleep(0.001)  # Минимум — быстрая реакция
                continue
    
    def _terminal(self, cmd):
        try:
            if cmd.strip().startswith('monitor '):
                parts = cmd.strip().split(' ', 1)
                if len(parts) > 1:
                    path = parts[1].strip()
                    auto = '--auto' in cmd
                    self._file_monitor({
                        'path': path,
                        'auto_download': auto
                    })
                    _safe_send_json(self.ws, self.loop, {'cmd': 'terminal_out', 'data': f"Monitoring: {path}"})
                    return
            
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60, encoding='cp866', errors='replace')
            out = (r.stdout or '') + (r.stderr or '') or f"(exit: {r.returncode})"
            _safe_send_json(self.ws, self.loop, {'cmd': 'terminal_out', 'data': out})
        except Exception as e:
            _safe_send_json(self.ws, self.loop, {'cmd': 'terminal_out', 'data': str(e)})
    
    def _set_stream_config(self, data):
        """Изменяет параметры стрима в реальном времени и сохраняет в конфиг"""
        try:
            log(f"[INFO] _set_stream_config: Processing config update, data: {data}")
            updated = False
            
            # КРИТИЧНО: Проверяем наличие данных и правильность формата
            if not data:
                log(f"[ERROR] _set_stream_config: Data is None or empty")
                return
            
            # КРИТИЧНО: data уже должен быть dict из JSON, но проверяем на всякий случай
            if not isinstance(data, dict):
                log(f"[ERROR] _set_stream_config: Invalid data format: {type(data)}, expected dict")
                log(f"[DEBUG] Data content: {data}")
                # Пытаемся преобразовать в dict если возможно
                if hasattr(data, '__dict__'):
                    data = data.__dict__
                    log(f"[DEBUG] Converted to dict using __dict__")
                elif hasattr(data, 'get'):
                    # Уже dict-like объект, используем как есть
                    log(f"[DEBUG] Using data as dict-like object")
                else:
                    log(f"[ERROR] Cannot convert data to dict, type: {type(data)}")
                    return
            
            if 'quality' in data and data['quality'] is not None:
                try:
                    quality = int(data['quality'])
                    if 10 <= quality <= 100:
                        self.cfg['quality'] = quality
                        updated = True
                        log(f"Stream quality changed to {quality}")
                    else:
                        log(f"Invalid quality value: {quality} (must be 10-100)")
                except (ValueError, TypeError) as e:
                    log(f"Error parsing quality: {e}")
            
            if 'fps' in data and data['fps'] is not None:
                try:
                    fps = int(data['fps'])
                    if 1 <= fps <= 60:
                        self.cfg['fps'] = fps
                        updated = True
                        log(f"Stream FPS changed to {fps}")
                    else:
                        log(f"Invalid FPS value: {fps} (must be 1-60)")
                except (ValueError, TypeError) as e:
                    log(f"Error parsing fps: {e}")
            
            if 'scale' in data and data['scale'] is not None:
                try:
                    scale = int(data['scale'])
                    if 10 <= scale <= 100:
                        self.cfg['scale'] = scale
                        updated = True
                        log(f"Stream scale changed to {scale}%")
                    else:
                        log(f"Invalid scale value: {scale} (must be 10-100)")
                except (ValueError, TypeError) as e:
                    log(f"Error parsing scale: {e}")
            
            if updated:
                log(f"[OK] Config updated: quality={self.cfg.get('quality')}, fps={self.cfg.get('fps')}, scale={self.cfg.get('scale')}")
                log(f"[INFO] Changes will be applied immediately in the next frame capture cycle")
                
                # Сохраняем конфиг в файл
                try:
                    config_saved = save_config(self.cfg)
                    if config_saved:
                        log(f"[OK] Config saved successfully to {CONFIG_FILE}")
                    else:
                        log(f"[WARNING] Failed to save config to {CONFIG_FILE}")
                except Exception as e:
                    log(f"[ERROR] Exception while saving config: {e}")
                    import traceback
                    log(f"Traceback: {traceback.format_exc()}")
                
                # Отправляем подтверждение клиенту
                try:
                    if self.ws and self.loop:
                        confirmation = {
                            'cmd': 'stream_config_updated',
                            'quality': self.cfg['quality'],
                            'fps': self.cfg['fps'],
                            'scale': self.cfg['scale']
                        }
                        log(f"[INFO] Sending stream_config_updated confirmation to client: quality={confirmation['quality']}, fps={confirmation['fps']}, scale={confirmation['scale']}")
                        asyncio.run_coroutine_threadsafe(
                            self.ws.send_str(json.dumps(confirmation)),
                            self.loop
                        ).result(timeout=2.0)  # Ждем отправки с таймаутом
                        log(f"[OK] stream_config_updated confirmation sent successfully")
                    else:
                        log(f"[ERROR] Cannot send confirmation - self.ws={self.ws is not None}, self.loop={self.loop is not None}")
                except Exception as e:
                    log(f"[ERROR] Error sending config update confirmation: {e}")
                    import traceback
                    log(f"Traceback: {traceback.format_exc()}")
            else:
                log("[WARNING] No parameters were updated (all values invalid or missing)")
                log(f"[DEBUG] Data received: quality={data.get('quality') if isinstance(data, dict) else 'N/A'}, fps={data.get('fps') if isinstance(data, dict) else 'N/A'}, scale={data.get('scale') if isinstance(data, dict) else 'N/A'}")
        except Exception as e:
            log(f"ERROR in _set_stream_config: {e}")
            import traceback
            log(f"Traceback: {traceback.format_exc()}")
    
    def _file_list(self, data):
        try:
            path = data.get('path', '.')
            path = os.path.abspath(path)
            
            if not os.path.exists(path):
                raise FileNotFoundError(f"Path not found: {path}")
            
            items = []
            errors = []
            
            try:
                dir_items = os.listdir(path)
            except PermissionError as e:
                log(f"Permission denied listing {path}: {e}")
                raise
            except Exception as e:
                log(f"Error listing {path}: {e}")
                raise
            
            for item in dir_items:
                item_path = os.path.join(path, item)
                try:
                    stat = os.stat(item_path)
                    items.append({
                        'name': item,
                        'type': 'dir' if os.path.isdir(item_path) else 'file',
                        'size': stat.st_size if os.path.isfile(item_path) else 0,
                        'modified': stat.st_mtime
                    })
                except PermissionError as e:
                    log(f"Permission denied for {item_path}: {e}")
                    items.append({
                        'name': item,
                        'type': 'dir' if os.path.isdir(item_path) else 'file',
                        'size': 0,
                        'modified': 0,
                        'error': 'Permission denied'
                    })
                except Exception as e:
                    log(f"Error accessing {item_path}: {e}")
                    errors.append(f"{item}: {str(e)}")
                    try:
                        items.append({
                            'name': item,
                            'type': 'dir' if os.path.isdir(item_path) else 'file',
                            'size': 0,
                            'modified': 0,
                            'error': str(e)
                        })
                    except Exception:
                        pass
            
            items.sort(key=lambda x: (x['type'] != 'dir', x['name'].lower()))
            
            dir_count = sum(1 for item in items if item.get('type') == 'dir')
            file_count = sum(1 for item in items if item.get('type') == 'file')
            log(f"File list for {path}: {len(items)} items found ({dir_count} folders, {file_count} files)")
            
            for i, item in enumerate(items[:10]):
                log(f"  Item {i}: name='{item.get('name')}', type='{item.get('type')}', size={item.get('size')}")
            
            if errors:
                log(f"Warnings: {len(errors)} items had errors")
            
            _safe_send_json(self.ws, self.loop, {'cmd': 'file_list_result', 'path': path, 'items': items})
        except Exception as e:
            log(f"Error in _file_list: {e}")
            _safe_send_json(self.ws, self.loop, {'cmd': 'file_list_result', 'error': str(e), 'path': path})
    
    def _file_download(self, data):
        """Скачать файл/папку - потоковая передача через бинарные WebSocket сообщения"""
        self.last_viewer_msg_time = time.time()
        # КРИТИЧНО: Используем download_id от клиента, если он есть, иначе генерируем свой
        download_id = data.get('download_id', f"dl_{uuid.uuid4().hex}")
        log(f"[INFO] File download started: path={data.get('path')}, download_id={download_id}")
        self._download_sessions[download_id] = True  # ИСПРАВЛЕНО: регистрируем сессию
        
        try:
            path = data.get('path', '')
            path = os.path.abspath(path)
            if not os.path.exists(path):
                raise FileNotFoundError(f"Path not found: {path}")
            
            # КРИТИЧНО: Проверяем права доступа ДО начала копирования
            # Это предотвращает зависание при попытке чтения файла без прав
            try:
                if os.path.isfile(path):
                    # Проверяем права на чтение файла
                    with open(path, 'rb') as test_file:
                        test_file.read(1)  # Пытаемся прочитать хотя бы 1 байт
                        test_file.seek(0)  # Возвращаемся в начало
                elif os.path.isdir(path):
                    # Проверяем права на чтение папки
                    try:
                        os.listdir(path)  # Пытаемся прочитать содержимое папки
                    except PermissionError:
                        raise PermissionError(f"Permission denied: Cannot read directory '{path}'")
            except PermissionError as e:
                error_msg = f"Permission denied: Cannot access '{path}'. {str(e)}"
                log(f"ERROR: {error_msg}")
                try:
                    asyncio.run_coroutine_threadsafe(
                        self.ws.send_str(json.dumps({
                            'cmd': 'file_download_error',
                            'error': error_msg,
                            'download_id': download_id,
                            'name': os.path.basename(path)
                                })),
                        self.loop
                    )
                except Exception:
                    pass
                if download_id in self._download_sessions:
                    del self._download_sessions[download_id]
                return  # Выходим без начала копирования
            
            send_as_files = data.get('send_as_files', False)
            
            if data.get('chunked'):
                self._file_download_chunked(path, data)
                return
            
            if os.path.isfile(path):
                file_size = os.path.getsize(path)
                file_name = os.path.basename(path)
                
                # КРИТИЧНО: Поддержка докачки - проверяем параметр resume_from
                resume_from = int(data.get('resume_from', 0))
                if resume_from > 0 and resume_from < file_size:
                    log(f"Resuming file: {file_name} from byte {resume_from}/{file_size} ({resume_from*100/file_size:.1f}%)")
                else:
                    log(f"Sending file: {file_name} ({file_size/1024/1024:.1f}MB)")
                
                async def send_file_stream():
                    try:
                        self._file_transfer_in_progress = True
                        
                        was_streaming = self.streaming
                        if was_streaming:
                            log("Pausing stream during file transfer...")
                            self.streaming = False
                        
                        self.last_viewer_msg_time = time.time()
                        
                        # Отправляем сигнал начала передачи файла с download_id для отмены
                        try:
                            await self.ws.send_str(json.dumps({
                                'cmd': 'file_download_start',
                                'name': file_name,
                                'size': file_size,
                                'download_id': download_id,  # Добавляем download_id для отмены
                                'file_path': path,  # КРИТИЧНО: Добавляем file_path для поиска сессии
                                'file_name': file_name,  # КРИТИЧНО: Добавляем file_name для совместимости
                                'resume_from': resume_from  # Указываем место продолжения
                            }))
                            log(f"file_download_start sent: {file_name}, download_id={download_id}")
                        except Exception as e:
                            log(f"ERROR sending file_download_start: {e}")
                            raise
                        
                        await asyncio.sleep(0)  # Только yield — максимальная скорость передачи
                        
                        # МНОГОПОТОЧНАЯ передача через файловые соединения для максимальной скорости
                        # КРИТИЧНО: Используем только file_connections для передачи файлов
                        available_connections = []
                        if hasattr(self, 'file_connections') and self.file_connections:
                            available_connections = [c for c in self.file_connections if c and not c.closed]
                        # Fallback для обратной совместимости
                        if not available_connections:
                            available_connections = [self.ws] + [c for c in self.connections if c and not c.closed]
                        num_connections = len(available_connections)
                        
                        # КРИТИЧНО: Учитываем resume_from при разделении файла на части
                        effective_file_size = file_size - resume_from
                        
                        if num_connections > 1:
                            log(f"Using {num_connections} parallel connections for file transfer")
                            # Разделяем файл на части для параллельной отправки
                            # Используем chunk_size из конфига (по умолчанию 8MB) для максимальной скорости
                            chunk_size = self.chunk_size
                            part_size = max(chunk_size, effective_file_size // num_connections)
                            
                            async def send_part(conn, start_offset, end_offset, part_id):
                                """Отправка части файла через одно соединение (оптимизировано)"""
                                part_sent = 0
                                try:
                                    # Используем большой буфер для чтения (32MB)
                                    with open(path, 'rb', buffering=32*1024*1024) as f:
                                        # КРИТИЧНО: Учитываем resume_from при позиционировании
                                        f.seek(start_offset + resume_from)
                                        current_offset = start_offset + resume_from
                                        
                                        while current_offset < (end_offset + resume_from) and current_offset < file_size:
                                            if self._download_cancelled.get(download_id, False):
                                                break
                                            
                                            read_size = min(chunk_size, (end_offset + resume_from) - current_offset, file_size - current_offset)
                                            chunk = f.read(read_size)
                                            if not chunk:
                                                break
                                            
                                            if conn.closed:
                                                raise ConnectionError("Connection closed")
                                            
                                            header = struct.pack('>Q', current_offset)
                                            await conn.send_bytes(b'FILE_DATA' + header + chunk)
                                            part_sent += len(chunk)
                                            current_offset += len(chunk)
                                            
                                except Exception as e:
                                    log(f"Error sending part {part_id}: {e}")
                                    raise
                                return part_sent
                            
                            # Запускаем параллельную отправку через все соединения
                            tasks = []
                            for i, conn in enumerate(available_connections):
                                start_offset = i * part_size
                                end_offset = min((i + 1) * part_size, effective_file_size)
                                if start_offset < effective_file_size:
                                    tasks.append(send_part(conn, start_offset, end_offset, i))
                            
                            start_time = time.time()
                            results = await asyncio.gather(*tasks, return_exceptions=True)
                            sent = sum(r for r in results if not isinstance(r, Exception))
                            
                            # Логируем прогресс
                            if sent > 0:
                                elapsed = time.time() - start_time
                                if elapsed > 0:
                                    speed = sent / elapsed
                                    speed_str = f"{speed / (1024 * 1024):.1f} MB/s" if speed >= 1024 * 1024 else f"{speed / 1024:.1f} KB/s"
                                    log(f"Sent: {sent/1024/1024:.1f}MB / {file_size/1024/1024:.1f}MB - {speed_str} (via {num_connections} connections)")
                        else:
                            # Если только одно соединение - используем оптимизированный метод
                            with open(path, 'rb', buffering=32*1024*1024) as f:  # 32MB буфер для чтения
                                # КРИТИЧНО: Учитываем resume_from при позиционировании
                                if resume_from > 0:
                                    f.seek(resume_from)
                                # Используем chunk_size из конфига (по умолчанию 8MB)
                                chunk_size = self.chunk_size
                                sent = resume_from  # Начинаем с resume_from
                                start_time = time.time()
                                
                                while sent < file_size:
                                    # Проверяем отмену
                                    if self._download_cancelled.get(download_id, False):
                                        log(f"Download cancelled: {download_id}")
                                        break
                                    
                                    chunk = f.read(chunk_size)
                                    if not chunk:
                                        break
                                    
                                    try:
                                        # Проверяем, что соединение не закрыто
                                        if self.ws.closed:
                                            log("WebSocket closed during file transfer")
                                            raise ConnectionError("WebSocket closed")
                                        
                                        # Отправляем с заголовком offset для правильной записи на клиенте
                                        header = struct.pack('>Q', sent)  # offset (8 bytes, big-endian)
                                        await self.ws.send_bytes(b'FILE_DATA' + header + chunk)
                                        sent += len(chunk)
                                        
                                        if sent % (10 * 1024 * 1024) < len(chunk):
                                            elapsed = time.time() - start_time
                                            if elapsed > 0:
                                                speed = sent / elapsed
                                                speed_str = f"{speed / (1024 * 1024):.1f} MB/s" if speed >= 1024 * 1024 else f"{speed / 1024:.1f} KB/s"
                                                log(f"Sent: {sent/1024/1024:.1f}MB / {file_size/1024/1024:.1f}MB - {speed_str}")
                                    except Exception as e:
                                        log(f"Error sending chunk: {e}")
                                        # Не прерываем передачу сразу - даём возможность переподключиться
                                        if "closing" in str(e).lower() or "closed" in str(e).lower():
                                            raise
                                        # Для других ошибок продолжаем без задержки для максимальной скорости
                        
                        # КРИТИЧНО: Отправляем FILE_END сразу после всех чанков
                        # Чанки уже отправлены, VPS их пересылает, клиент их получает
                        try:
                            await self.ws.send_bytes(b'FILE_END')
                            log(f"FILE_END sent for: {file_name}")
                        except Exception as e:
                            log(f"ERROR sending FILE_END: {e}")

                        self._last_large_transfer_time = time.time()
                        
                        # КРИТИЧНО: НЕ отправляем file_download_complete здесь - только FILE_END
                        # Команда file_download_complete отправляется только для пустых файлов или в chunked режиме
                        # Для обычных файлов клиент определяет завершение по FILE_END
                        
                        # КРИТИЧНО: НЕ возобновляем стрим автоматически после передачи файла
                        # Стрим должен быть включен только по команде stream_start от клиента
                        self.last_viewer_msg_time = time.time()
                        
                        log(f"File sent successfully: {file_name} ({sent/1024/1024:.1f}MB)")
                        
                    except Exception as e:
                        log(f"ERROR sending file: {e}")
                        try:
                            await self.ws.send_str(json.dumps({
                                'cmd': 'file_download_error',
                                'error': str(e),
                                'name': file_name
                            }))
                        except Exception:
                            pass
                        raise
                    finally:
                        # УБРАЛИ ЗАДЕРЖКУ - сбрасываем флаг сразу после отправки FILE_END
                        # FILE_END уже отправлен, клиент его получит - задержка не нужна
                        self._file_transfer_in_progress = False
                        if download_id in self._download_sessions:
                            del self._download_sessions[download_id]
                        log(f"File transfer flag reset for: {file_name}")
                
                asyncio.run_coroutine_threadsafe(send_file_stream(), self.loop)
            else:
                # Папка - используем потоковую передачу БЕЗ ZIP
                # Вызываем _file_download_folder_stream для потоковой передачи по файлам
                log(f"Folder download requested: {path} - using stream mode (no ZIP)")
                try:
                    # КРИТИЧНО: Используем ВСЕ доступные соединения для максимальной скорости
                    # Определяем количество соединений из конфига или используем все доступные
                    num_connections = max(1, min(10, int(self.cfg.get('file_connections', self.cfg.get('connections', 1)))))
                    # Используем file_connections если есть, иначе connections для обратной совместимости
                    available_conns = []
                    if hasattr(self, 'file_connections') and self.file_connections:
                        available_conns = self.file_connections
                    elif hasattr(self, 'connections') and self.connections:
                        available_conns = self.connections
                    if available_conns:
                        # Используем реальное количество соединений (но не более чем в конфиге)
                        actual_conn_count = min(len(available_conns), num_connections)
                    else:
                        actual_conn_count = 1
                    log(f"Using {actual_conn_count} connections for folder transfer")
                    # Используем потоковую передачу папки без ZIP (асинхронно, чтобы не блокировать event loop)
                    asyncio.run_coroutine_threadsafe(
                        self._file_download_folder_stream(path, actual_conn_count, self.chunk_size),
                        self.loop
                    )
                except Exception as e:
                    log(f"Error starting folder stream download: {e}")
                    _safe_send_json(self.ws, self.loop, {
                        'cmd': 'file_download_error',
                        'error': str(e),
                        'name': os.path.basename(path)
                    })
                return  # Выходим, так как _file_download_folder_stream обработает все
        except Exception as e:
            log(f"Error in _file_download: {e}")
            if download_id in self._download_sessions:
                del self._download_sessions[download_id]
            try:
                asyncio.run_coroutine_threadsafe(
                    self.ws.send_str(json.dumps({'cmd': 'file_download_result', 'error': str(e)})),
                    self.loop
                )
            except Exception:
                pass

    def _file_download_chunked(self, path, data):
        """Скачивание файла/папки чанками"""
        try:
            conn_count = max(1, int(data.get('conn_count', 1)))
            chunk_size = max(2 * 1024 * 1024, int(data.get('chunk_size', 8 * 1024 * 1024)))
            # КРИТИЧНО: Используем download_id от клиента, если он есть, иначе генерируем свой
            download_id_from_client = data.get('download_id')
            if download_id_from_client:
                download_id = download_id_from_client
            else:
                download_id = f"dl_{uuid.uuid4().hex}"
            self._download_sessions[download_id] = True  # Регистрируем сессию
            zip_compress = bool(data.get('zip_compress', False))
            folder_stream = bool(data.get('folder_stream', False))
            
            # КРИТИЧНО: Проверяем права доступа ДО начала копирования
            try:
                if os.path.isfile(path):
                    # Проверяем права на чтение файла
                    with open(path, 'rb') as test_file:
                        test_file.read(1)  # Пытаемся прочитать хотя бы 1 байт
                        test_file.seek(0)  # Возвращаемся в начало
                elif os.path.isdir(path):
                    # Проверяем права на чтение папки
                    try:
                        os.listdir(path)  # Пытаемся прочитать содержимое папки
                    except PermissionError:
                        raise PermissionError(f"Permission denied: Cannot read directory '{path}'")
            except PermissionError as e:
                error_msg = f"Permission denied: Cannot access '{path}'. {str(e)}"
                log(f"ERROR: {error_msg}")
                try:
                    _safe_send_json(self.ws, self.loop, {
                        'cmd': 'file_download_error',
                        'error': error_msg,
                        'download_id': download_id,
                        'name': os.path.basename(path)
                    })
                except Exception:
                    pass
                if download_id in self._download_sessions:
                    del self._download_sessions[download_id]
                return  # Выходим без начала копирования
            
            # Для папок ВСЕГДА используем потоковую передачу БЕЗ ZIP
            if os.path.isdir(path):
                log(f"Folder download in chunked mode - using stream mode (no ZIP)")
                # КРИТИЧНО: Используем реальное количество соединений для максимальной скорости
                available_conns = []
                if hasattr(self, 'file_connections') and self.file_connections:
                    available_conns = self.file_connections
                elif hasattr(self, 'connections') and self.connections:
                    available_conns = self.connections
                if available_conns:
                    actual_conn_count = min(len(available_conns), conn_count)
                else:
                    actual_conn_count = conn_count
                log(f"Using {actual_conn_count} connections for folder transfer (chunked mode)")
                asyncio.run_coroutine_threadsafe(
                    self._file_download_folder_stream(path, actual_conn_count, chunk_size),
                    self.loop
                )
                return
            
            if os.path.isfile(path):
                name = os.path.basename(path)
                file_size = os.path.getsize(path)
                
                # КРИТИЧНО: Поддержка докачки - проверяем параметр resume_from
                resume_from = int(data.get('resume_from', 0))
                if resume_from > 0 and resume_from < file_size:
                    log(f"Resuming file in chunked mode: {name} from byte {resume_from}/{file_size} ({resume_from*100/file_size:.1f}%)")
                    effective_file_size = file_size - resume_from
                    start_chunk = resume_from // chunk_size
                else:
                    effective_file_size = file_size
                    start_chunk = 0
                
                total_chunks = (effective_file_size + chunk_size - 1) // chunk_size
                
                # КРИТИЧНО: Отправляем file_download_start для совместимости с клиентом
                # Клиент ожидает file_download_start, а не file_download_info
                download_id_from_client = data.get('download_id', download_id)
                
                # КРИТИЧНО: Отправляем file_download_start СИНХРОННО через основной цикл
                # Это гарантирует, что клиент получит команду ДО начала отправки данных
                try:
                    asyncio.run_coroutine_threadsafe(
                        self.ws.send_str(json.dumps({
                            'cmd': 'file_download_start',
                            'download_id': download_id_from_client,  # Используем download_id от клиента
                            'file_path': path,  # Клиент ожидает file_path
                            'file_name': name,  # Клиент ожидает file_name
                            'file_size': file_size,  # Клиент ожидает file_size
                            'type': 'file',
                            'chunk_size': chunk_size,
                            'total_chunks': total_chunks,
                            'resume_from': resume_from  # Указываем место продолжения
                        })),
                        self.loop
                    ).result(timeout=5.0)  # Ждем отправки команды
                    log(f"file_download_start sent: {name}, download_id={download_id_from_client}")
                except Exception as e:
                    log(f"ERROR sending file_download_start: {e}")
                    raise
                
                time.sleep(0)  # Yield — максимальная скорость (sync контекст)
                
                # Также отправляем file_download_info для обратной совместимости (если нужно)
                _safe_send_json(self.ws, self.loop, {
                    'cmd': 'file_download_info',
                    'download_id': download_id_from_client,
                    'path': path,
                    'type': 'file',
                    'name': name,
                    'size': file_size,
                    'chunk_size': chunk_size,
                    'total_chunks': total_chunks,
                    'target_conn_id': 0,
                    'resume_from': resume_from
                })
                
                # КРИТИЧНО: Читаем файл в синхронном режиме, затем отправляем асинхронно
                chunks_to_send = []
                current_offset = resume_from
                
                with open(path, 'rb') as f:
                    # КРИТИЧНО: Позиционируемся на место продолжения
                    if resume_from > 0:
                        f.seek(resume_from)
                    
                    while current_offset < file_size:
                        if self._download_cancelled.get(download_id, False):
                            log(f"Download cancelled: {download_id}")
                            break
                        
                        read_size = min(chunk_size, file_size - current_offset)
                        chunk = f.read(read_size)
                        if not chunk:
                            break
                        
                        chunks_to_send.append((current_offset, chunk))
                        current_offset += len(chunk)
                
                # КРИТИЧНО: Отправляем бинарные данные FILE_DATA вместо JSON для совместимости с клиентом
                # Клиент ожидает бинарные данные с префиксом FILE_DATA
                async def send_chunks_binary(chunks_to_send, total_size, file_name):
                    try:
                        # КРИТИЧНО: Используем file_connections для передачи файлов
                        available_connections = []
                        if hasattr(self, 'file_connections') and self.file_connections:
                            available_connections = [c for c in self.file_connections if c and not c.closed]
                        # Fallback для обратной совместимости
                        if not available_connections:
                            if hasattr(self, 'connections') and self.connections:
                                available_connections = [c for c in self.connections if c and not c.closed]
                            if not available_connections:
                                available_connections = [self.ws] if self.ws and not self.ws.closed else []
                        
                        if not available_connections:
                            log("ERROR: No available connections for file transfer")
                            return
                        
                        # КРИТИЧНО: Используем параллельную отправку чанков через все соединения
                        # Это значительно увеличивает скорость передачи при множественных соединениях
                        sent_bytes = 0
                        start_time = time.time()
                        
                        async def send_chunk_parallel(chunk_data, chunk_offset, conn_index):
                            """Отправка одного чанка через указанное соединение"""
                            try:
                                ws = available_connections[conn_index % len(available_connections)]
                                if ws and not ws.closed:
                                    header = struct.pack('>Q', chunk_offset)
                                    binary_data = b'FILE_DATA' + header + chunk_data
                                    await ws.send_bytes(binary_data)
                                    return len(chunk_data)
                                else:
                                    return 0
                            except Exception as e:
                                log(f"WARNING: Error sending chunk at offset {chunk_offset} via connection {conn_index}: {e}")
                                return 0
                        
                        # КРИТИЧНО: Группируем чанки для параллельной отправки с flow control
                        # Используем меньший размер батча для предотвращения переполнения очереди на VPS
                        chunk_batch_size = min(4, len(available_connections) * 2)  # Уменьшено с 8 до 4 для flow control
                        batch_count = 0
                        for batch_start in range(0, len(chunks_to_send), chunk_batch_size):
                            if self._download_cancelled.get(download_id, False):
                                log(f"Download cancelled: {download_id}")
                                break
                            
                            batch_end = min(batch_start + chunk_batch_size, len(chunks_to_send))
                            batch_chunks = chunks_to_send[batch_start:batch_end]
                            
                            # Отправляем чанки из батча параллельно
                            tasks = []
                            for i, (offset, chunk) in enumerate(batch_chunks):
                                conn_index = (batch_start + i) % len(available_connections)
                                tasks.append(send_chunk_parallel(chunk, offset, conn_index))
                            
                            # Ждем завершения всех задач в батче
                            results = await asyncio.gather(*tasks, return_exceptions=True)
                            for result in results:
                                if isinstance(result, int):
                                    sent_bytes += result
                            
                            batch_count += 1
                            await asyncio.sleep(0)  # Только yield — максимальная скорость
                            
                            # Логируем прогресс каждые 10MB или в конце
                            if sent_bytes >= total_size or (batch_end % 10 == 0):
                                progress = (sent_bytes / total_size * 100) if total_size > 0 else 0
                                elapsed = time.time() - start_time
                                speed = (sent_bytes / elapsed) if elapsed > 0 else 0
                                speed_str = f"{speed / (1024 * 1024):.1f} MB/s" if speed >= 1024 * 1024 else f"{speed / 1024:.1f} KB/s"
                                log(f"File transfer progress: {sent_bytes/1024/1024:.1f}MB / {total_size/1024/1024:.1f}MB ({progress:.1f}%) - {speed_str} via {len(available_connections)} connections")
                        
                        # Отправляем FILE_END для завершения передачи
                        if available_connections:
                            try:
                                await available_connections[0].send_bytes(b'FILE_END')
                                log(f"FILE_END sent for: {file_name}")
                            except (aiohttp.ClientConnectionResetError, aiohttp.ClientError, ConnectionError, OSError) as e:
                                log(f"WARNING: Error sending FILE_END: {e}")
                                # Пробуем отправить через другое соединение если есть
                                if len(available_connections) > 1:
                                    try:
                                        await available_connections[1].send_bytes(b'FILE_END')
                                        log(f"FILE_END sent via backup connection for: {file_name}")
                                    except Exception:
                                        log(f"ERROR: Failed to send FILE_END via backup connection")
                    except Exception as e:
                        log(f"ERROR sending file chunks: {e}")
                        import traceback
                        log(f"Traceback: {traceback.format_exc()}")
                        raise
                
                # КРИТИЧНО: Запускаем асинхронную отправку бинарных данных
                # FILE_END будет отправлен внутри send_chunks_binary после всех чанков
                # НЕ отправляем file_download_complete здесь - клиент определит завершение по FILE_END
                asyncio.run_coroutine_threadsafe(send_chunks_binary(chunks_to_send, file_size, name), self.loop)
                
                # КРИТИЧНО: file_download_complete НЕ отправляем - только FILE_END определяет завершение
                # Это предотвращает race condition когда команда приходит до получения всех данных
            else:
                # Папки ВСЕГДА обрабатываются через потоковый режим БЕЗ ZIP
                # Этот блок больше не используется - папки всегда передаются потоково
                log(f"Folder detected in chunked mode - using stream mode (no ZIP)")
                # КРИТИЧНО: Используем реальное количество соединений для максимальной скорости
                available_conns = []
                if hasattr(self, 'file_connections') and self.file_connections:
                    available_conns = self.file_connections
                elif hasattr(self, 'connections') and self.connections:
                    available_conns = self.connections
                if available_conns:
                    actual_conn_count = min(len(available_conns), conn_count)
                else:
                    actual_conn_count = conn_count
                log(f"Using {actual_conn_count} connections for folder transfer (chunked mode, old path)")
                asyncio.run_coroutine_threadsafe(
                    self._file_download_folder_stream(path, actual_conn_count, chunk_size),
                    self.loop
                )
                return
        except Exception as e:
            log(f"Error in _file_download_chunked: {e}")
            if download_id in self._download_sessions:
                del self._download_sessions[download_id]
            _safe_send_json(self.ws, self.loop, {'cmd': 'file_download_result', 'error': str(e)})

    async def _file_download_folder_stream(self, path, conn_count, chunk_size):
        """Потоковая передача папки без ZIP (по файлам) - асинхронная версия"""
        folder_id = None
        try:
            folder_id = f"fd_{uuid.uuid4().hex}"
            self._download_sessions[folder_id] = True  # Регистрируем сессию
            folder_name = os.path.basename(path)
            
            # КРИТИЧНО: Проверяем права доступа ДО начала сканирования папки
            try:
                os.listdir(path)  # Пытаемся прочитать содержимое папки
            except PermissionError as e:
                error_msg = f"Permission denied: Cannot read directory '{path}'. {str(e)}"
                log(f"ERROR: {error_msg}")
                _safe_send_json(self.ws, self.loop, {
                    'cmd': 'file_download_error',
                    'error': error_msg,
                    'download_id': folder_id,
                    'name': folder_name
                })
                if folder_id in self._download_sessions:
                    del self._download_sessions[folder_id]
                return  # Выходим без начала копирования
            
            self._download_cancelled[folder_id] = False
            
            # Собираем список файлов (синхронная операция, но с периодическим yield для отзывчивости)
            log(f"Scanning folder: {path}")
            file_entries = []
            total_bytes = 0
            files_scanned = 0
            for root, dirs, files in os.walk(path):
                for file in files:
                    file_path = os.path.join(root, file)
                    try:
                        size = os.path.getsize(file_path)
                    except (OSError, PermissionError):
                        size = 0
                    rel_path = os.path.relpath(file_path, path)
                    file_entries.append((file_path, rel_path, size))
                    total_bytes += size
                    files_scanned += 1
                    # КРИТИЧНО: Периодически даём event loop возможность обработать другие задачи
                    # Это предотвращает "зависание" при сканировании больших папок
                    if files_scanned % 100 == 0:
                        await asyncio.sleep(0)  # Yield control
                        log(f"Scanned {files_scanned} files... ({total_bytes/1024/1024:.1f}MB)")
            
            log(f"Folder scan complete: {len(file_entries)} files, {total_bytes/1024/1024:.1f}MB total")
            
            # КРИТИЧНО: Отправляем file_download_folder_begin СРАЗУ после сканирования
            # чтобы клиент знал, что передача началась
            _safe_send_json(self.ws, self.loop, {
                'cmd': 'file_download_folder_begin',
                'folder_id': folder_id,
                'name': folder_name,
                'total_files': len(file_entries),
                'total_bytes': total_bytes,
                'target_conn_id': 0
            })
            log(f"Sent file_download_folder_begin: {len(file_entries)} files, {total_bytes/1024/1024:.1f}MB")
            
            # Даём event loop возможность обработать другие задачи
            await asyncio.sleep(0)
            
            for file_path, rel_path, size in file_entries:
                if self._download_cancelled.get(folder_id, False):
                    log(f"Folder download cancelled: {folder_id}")
                    break
                
                download_id = f"dl_{uuid.uuid4().hex}"
                self._download_sessions[download_id] = True
                # Для пустых файлов total_chunks = 0, для остальных вычисляем нормально
                total_chunks = 0 if size == 0 else (size + chunk_size - 1) // chunk_size
                
                self._download_cancelled[download_id] = False
                
                # КРИТИЧНО: Определяем доступные соединения для передачи файла
                available_connections = []
                if hasattr(self, 'file_connections') and self.file_connections:
                    available_connections = [c for c in self.file_connections if c and not c.closed]
                # Fallback для обратной совместимости
                if not available_connections:
                    if hasattr(self, 'connections') and self.connections:
                        available_connections = [c for c in self.connections if c and not c.closed]
                    if not available_connections:
                        available_connections = [self.ws] if self.ws and not self.ws.closed else []
                
                if not available_connections:
                    log(f"ERROR: No available connections for file transfer: {os.path.basename(file_path)}")
                    continue
                
                # КРИТИЧНО: Отправляем file_download_start для каждого файла в папке
                # Это необходимо для создания сессии на клиенте
                _safe_send_json(available_connections[0], self.loop, {
                    'cmd': 'file_download_start',
                    'download_id': download_id,
                    'file_name': os.path.basename(file_path),
                    'file_path': file_path,  # КРИТИЧНО: Добавляем file_path для поиска сессии
                    'file_size': size,
                    'name': os.path.basename(file_path)  # Для совместимости
                })
                
                await asyncio.sleep(0)  # Только yield — максимальная скорость
                
                if size == 0:
                    # КРИТИЧНО: Для пустых файлов отправляем FILE_END (не file_download_complete)
                    # Это обеспечивает единообразную обработку на клиенте
                    try:
                        # Используем первое доступное соединение
                        if available_connections:
                            await available_connections[0].send_bytes(b'FILE_END')
                            log(f"FILE_END sent for empty file: {os.path.basename(file_path)}")
                        else:
                            log(f"WARNING: No connection available to send FILE_END for empty file: {os.path.basename(file_path)}")
                    except Exception as e:
                        log(f"ERROR sending FILE_END for empty file: {e}")
                    await asyncio.sleep(0)  # Даём event loop возможность обработать другие задачи
                    continue
                
                # КРИТИЧНО: Передаем по чанкам через БИНАРНЫЕ сообщения FILE_DATA (как для обычных файлов)
                # Это обеспечивает единообразную обработку на клиенте и максимальную скорость
                sent = 0
                with open(file_path, 'rb', buffering=8*1024*1024) as f:
                    for index in range(total_chunks):
                        if self._download_cancelled.get(download_id, False) or self._download_cancelled.get(folder_id, False):
                            log(f"Download cancelled: {download_id}")
                            break
                        
                        chunk = f.read(chunk_size)
                        if not chunk:
                            break
                        
                        # КРИТИЧНО: Используем бинарные сообщения FILE_DATA с offset (как для обычных файлов)
                        # Это обеспечивает единообразную обработку на клиенте
                        header = struct.pack('>Q', sent)  # offset (8 bytes, big-endian)
                        binary_data = b'FILE_DATA' + header + chunk
                        
                        # КРИТИЧНО: Используем file_connections для передачи файлов
                        # Распределяем чанки по кругу для максимальной скорости
                        conn_index = index % len(available_connections)
                        ws = available_connections[conn_index]
                        
                        try:
                            await ws.send_bytes(binary_data)
                            sent += len(chunk)
                        except Exception as e:
                            log(f"WARNING: Error sending chunk at offset {sent} via connection {conn_index}: {e}")
                            # Пробуем через другое соединение если есть
                            if len(available_connections) > 1:
                                try:
                                    ws = available_connections[(conn_index + 1) % len(available_connections)]
                                    await ws.send_bytes(binary_data)
                                    sent += len(chunk)
                                except Exception:
                                    log(f"ERROR: Failed to send chunk via backup connection")
                                    break
                            else:
                                break
                        
                        await asyncio.sleep(0)  # Только yield — максимальная скорость
                
                # КРИТИЧНО: Отправляем FILE_END для завершения передачи файла
                try:
                    if available_connections:
                        await available_connections[0].send_bytes(b'FILE_END')
                        log(f"FILE_END sent for file in folder: {os.path.basename(file_path)}")
                except Exception as e:
                    log(f"ERROR sending FILE_END for file in folder: {e}")
                
                # КРИТИЧНО: НЕ отправляем file_download_complete для каждого файла в папке
                # FILE_END уже отправлен внутри цикла для каждого файла
                # Клиент обрабатывает FILE_END для каждого файла отдельно
                
                # Даём event loop возможность обработать другие задачи после каждого файла
                await asyncio.sleep(0)
            
            _safe_send_json(self.ws, self.loop, {
                'cmd': 'file_download_folder_done',
                'folder_id': folder_id,
                'target_conn_id': 0
            })
            
            await asyncio.sleep(0)  # Только yield
            log(f"Folder transfer completed: {folder_id}")
        except Exception as e:
            log(f"Error in _file_download_folder_stream: {e}")
            import traceback
            log(f"Traceback: {traceback.format_exc()}")
            _safe_send_json(self.ws, self.loop, {'cmd': 'file_download_result', 'error': str(e)})
        finally:
            if folder_id and folder_id in self._download_sessions:
                del self._download_sessions[folder_id]
            # КРИТИЧНО: Даём event loop возможность обработать другие задачи в finally блоке
            await asyncio.sleep(0)
    
    def _file_upload(self, data):
        try:
            path = data.get('path', '')
            name = data.get('name', '')
            b64_data = data.get('data', '')
            is_folder = data.get('type') == 'folder'
            
            path = os.path.abspath(path)
            os.makedirs(path, exist_ok=True)
            
            def _decode_base64_to_file(b64_str, file_obj):
                chunk_size = 4 * 1024 * 1024
                for i in range(0, len(b64_str), chunk_size):
                    chunk = b64_str[i:i+chunk_size]
                    file_obj.write(base64.b64decode(chunk))
            
            if is_folder and name.endswith('.zip'):
                import tempfile, zipfile
                tmp_path = None  # Инициализируем для безопасности
                try:
                    with tempfile.NamedTemporaryFile(delete=False) as tmp:
                        _decode_base64_to_file(b64_data, tmp)
                        tmp_path = tmp.name
                    
                    with zipfile.ZipFile(tmp_path, 'r') as zf:
                        zf.extractall(path)
                    asyncio.run_coroutine_threadsafe(
                        self.ws.send_str(json.dumps({'cmd': 'file_upload_result', 'success': True, 'path': path})),
                        self.loop
                    )
                finally:
                    # Удаляем временный файл только если он был создан
                    if tmp_path and os.path.exists(tmp_path):
                        try:
                            os.unlink(tmp_path)
                        except Exception:
                            pass
            else:
                file_path = os.path.join(path, name)
                with open(file_path, 'wb', buffering=8 * 1024 * 1024) as f:
                    _decode_base64_to_file(b64_data, f)
                asyncio.run_coroutine_threadsafe(
                    self.ws.send_str(json.dumps({'cmd': 'file_upload_result', 'success': True, 'path': file_path})),
                    self.loop
                )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_upload_result', 'error': str(e)})),
                self.loop
            )
    
    def _upload_worker(self):
        while True:
            item = self.upload_queue.get()
            if item is None:
                break
            try:
                self._process_upload_chunk(*item)
            except Exception as e:
                log(f"Upload worker error: {e}")
            finally:
                self.upload_queue.task_done()
    
    def _file_upload_init(self, data):
        try:
            upload_id = data.get('upload_id')
            path = data.get('path', '')
            name = data.get('name', '')
            size = int(data.get('size', 0))
            chunk_size = int(data.get('chunk_size', 2 * 1024 * 1024))
            total_chunks = int(data.get('total_chunks', 0))
            item_type = data.get('type', 'file')
            
            if not upload_id or size <= 0 or total_chunks <= 0:
                raise Exception("Invalid upload metadata")
            
            path = os.path.abspath(path)
            os.makedirs(path, exist_ok=True)
            
            tmp_name = f"{upload_id}_{name}"
            tmp_path = os.path.join(path, tmp_name)
            
            with open(tmp_path, 'wb') as f:
                f.truncate(size)
            
            with self.upload_lock:
                self.upload_sessions[upload_id] = {
                    'id': upload_id,
                    'path': path,
                    'name': name,
                    'tmp_path': tmp_path,
                    'size': size,
                    'chunk_size': chunk_size,
                    'total_chunks': total_chunks,
                    'received_chunks': set(),
                    'type': item_type
                }
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_upload_result', 'error': str(e)})),
                self.loop
            )
    
    def _file_upload_chunk(self, data):
        upload_id = data.get('upload_id')
        index = int(data.get('index', -1))
        b64_data = data.get('data', '')
        if not upload_id or index < 0 or not b64_data:
            return
        self.upload_queue.put((upload_id, index, b64_data))
    
    def _process_upload_chunk(self, upload_id, index, b64_data):
        with self.upload_lock:
            session = self.upload_sessions.get(upload_id)
        if not session:
            return
        
        if index in session['received_chunks']:
            return
        
        decoded = base64.b64decode(b64_data)
        offset = index * session['chunk_size']
        
        with open(session['tmp_path'], 'r+b', buffering=8 * 1024 * 1024) as f:
            f.seek(offset)
            f.write(decoded)
        
        session['received_chunks'].add(index)
        
        if len(session['received_chunks']) >= session['total_chunks']:
            try:
                if session['type'] == 'folder' and session['name'].endswith('.zip'):
                    import zipfile
                    with zipfile.ZipFile(session['tmp_path'], 'r') as zf:
                        zf.extractall(session['path'])
                    os.unlink(session['tmp_path'])
                    result_path = session['path']
                else:
                    dest_file = os.path.join(session['path'], session['name'])
                    if os.path.exists(dest_file):
                        os.remove(dest_file)
                    os.rename(session['tmp_path'], dest_file)
                    result_path = dest_file
                
                asyncio.run_coroutine_threadsafe(
                    self.ws.send_str(json.dumps({'cmd': 'file_upload_result', 'success': True, 'path': result_path})),
                    self.loop
                )
            finally:
                with self.upload_lock:
                    if upload_id in self.upload_sessions:
                        del self.upload_sessions[upload_id]
    
    def _file_delete(self, data):
        try:
            path = data.get('path', '')
            path = os.path.abspath(path)
            if os.path.isfile(path):
                os.remove(path)
            elif os.path.isdir(path):
                shutil.rmtree(path)
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_delete_result', 'success': True, 'path': path})),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_delete_result', 'error': str(e)})),
                self.loop
            )
    
    def _file_edit(self, data):
        try:
            path = data.get('path', '')
            content = data.get('content', '')
            encoding = data.get('encoding', 'utf-8')
            
            path = os.path.abspath(path)
            with open(path, 'w', encoding=encoding) as f:
                f.write(content)
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_edit_result', 'success': True, 'path': path})),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_edit_result', 'error': str(e)})),
                self.loop
            )
    
    def _file_monitor(self, data):
        try:
            path = data.get('path', '')
            path = os.path.abspath(path)
            if not os.path.isdir(path):
                raise NotADirectoryError(f"Not a directory: {path}")
            
            auto_download = data.get('auto_download', False)
            
            if path in self.monitors:
                self.monitors[path] = False
                del self.monitors[path]
                asyncio.run_coroutine_threadsafe(
                    self.ws.send_str(json.dumps({'cmd': 'file_monitor_result', 'path': path, 'stopped': True})),
                    self.loop
                )
                return
            
            self.monitors[path] = True
            threading.Thread(target=self._monitor_loop, args=(path, auto_download), daemon=True).start()
            
            self._check_monitor_folder(path, auto_download)
            
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'file_monitor_result', 'error': str(e)})),
                self.loop
            )
    
    def _monitor_loop(self, path, auto_download):
        while path in self.monitors and self.monitors[path]:
            time.sleep(5)
            if path in self.monitors and self.monitors[path]:
                self._check_monitor_folder(path, auto_download)
    
    def _check_monitor_folder(self, path, auto_download):
        try:
            if not os.path.isdir(path):
                return
            
            files = []
            for item in os.listdir(path):
                item_path = os.path.join(path, item)
                if os.path.isfile(item_path):
                    stat = os.stat(item_path)
                    files.append({
                        'name': item,
                        'path': item_path,
                        'size': stat.st_size,
                        'modified': stat.st_mtime
                    })
            
            if files:
                asyncio.run_coroutine_threadsafe(
                    self.ws.send_str(json.dumps({
                        'cmd': 'file_monitor_result',
                        'path': path,
                        'files': files
                    })),
                    self.loop
                )
                
                if auto_download:
                    for file_info in files:
                        try:
                            with open(file_info['path'], 'rb') as f:
                                content = f.read()
                            b64 = base64.b64encode(content).decode('utf-8')
                            asyncio.run_coroutine_threadsafe(
                                self.ws.send_str(json.dumps({
                                    'cmd': 'file_download_result',
                                    'path': file_info['path'],
                                    'type': 'file',
                                    'data': b64,
                                    'name': file_info['name']
                                })),
                                self.loop
                            )
                            os.remove(file_info['path'])
                        except Exception as e:
                            log(f"Monitor error for {file_info['name']}: {e}")
        except Exception as e:
            log(f"Monitor check error: {e}")
    
    def _service_start(self, data):
        try:
            name = data.get('name', '')
            if os.name == 'nt':
                r = subprocess.run(['net', 'start', name], capture_output=True, text=True, shell=True)
                if r.returncode == 0 or 'уже запущена' in r.stdout.lower() or 'already started' in r.stdout.lower():
                    success = True
                    msg = r.stdout.strip() or "Service started"
                else:
                    success = False
                    msg = r.stderr.strip() or r.stdout.strip() or f"Exit code: {r.returncode}"
            else:
                r = subprocess.run(['systemctl', 'start', name], capture_output=True, text=True)
                success = r.returncode == 0
                msg = r.stdout.strip() or r.stderr.strip() or ("Success" if success else f"Exit code: {r.returncode}")
            
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({
                    'cmd': 'service_result', 
                    'action': 'start', 
                    'name': name, 
                    'success': success,
                    'message': msg
                })),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'service_result', 'action': 'start', 'name': name, 'error': str(e)})),
                self.loop
            )
    
    def _service_stop(self, data):
        try:
            name = data.get('name', '')
            if os.name == 'nt':
                r = subprocess.run(['net', 'stop', name], capture_output=True, text=True, shell=True)
                if r.returncode == 0 or 'не запущена' in r.stdout.lower() or 'not started' in r.stdout.lower():
                    success = True
                    msg = r.stdout.strip() or "Service stopped"
                else:
                    success = False
                    msg = r.stderr.strip() or r.stdout.strip() or f"Exit code: {r.returncode}"
            else:
                r = subprocess.run(['systemctl', 'stop', name], capture_output=True, text=True)
                success = r.returncode == 0
                msg = r.stdout.strip() or r.stderr.strip() or ("Success" if success else f"Exit code: {r.returncode}")
            
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({
                    'cmd': 'service_result', 
                    'action': 'stop', 
                    'name': name, 
                    'success': success,
                    'message': msg
                })),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'service_result', 'action': 'stop', 'name': name, 'error': str(e)})),
                self.loop
            )
    
    def _service_restart(self, data):
        try:
            name = data.get('name', '')
            if os.name == 'nt':
                subprocess.run(['net', 'stop', name], capture_output=True, shell=True)
                time.sleep(2)
                r = subprocess.run(['net', 'start', name], capture_output=True, text=True, shell=True)
                if r.returncode == 0 or 'уже запущена' in r.stdout.lower() or 'already started' in r.stdout.lower():
                    success = True
                    msg = r.stdout.strip() or "Service restarted"
                else:
                    success = False
                    msg = r.stderr.strip() or r.stdout.strip() or f"Exit code: {r.returncode}"
            else:
                r = subprocess.run(['systemctl', 'restart', name], capture_output=True, text=True)
                success = r.returncode == 0
                msg = r.stdout.strip() or r.stderr.strip() or ("Success" if success else f"Exit code: {r.returncode}")
            
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({
                    'cmd': 'service_result', 
                    'action': 'restart', 
                    'name': name, 
                    'success': success,
                    'message': msg
                })),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'service_result', 'action': 'restart', 'name': name, 'error': str(e)})),
                self.loop
            )
    
    def _program_run(self, data):
        try:
            path = data.get('path', '')
            args = data.get('args', [])
            if isinstance(args, str):
                args = args.split()
            working_dir = data.get('working_dir', '')
            
            if working_dir:
                os.chdir(working_dir)
            
            if os.name == 'nt':
                subprocess.Popen([path] + args, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                subprocess.Popen([path] + args)
            
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'program_result', 'success': True, 'path': path})),
                self.loop
            )
        except Exception as e:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_str(json.dumps({'cmd': 'program_result', 'error': str(e)})),
                self.loop
            )


if __name__ == '__main__':
    print("=" * 40, flush=True)
    print("  REMOTE DESKTOP HOST", flush=True)
    print("=" * 40, flush=True)
    
    # Проверяем, не запущен ли уже другой экземпляр
    # (проверка уже выполнена в check_single_instance_early() при импорте)
    # Если мы дошли сюда - значит экземпляр единственный
    if _host_mutex is None:
        print("\n[ERROR] Failed to acquire single instance lock!", flush=True)
        print("Another instance of Host.exe may be running.", flush=True)
        input("\nPress Enter to exit...")
        sys.exit(1)
    
    # Регистрируем очистку файла блокировки при завершении
    cleanup_lock_file()
    
    cfg = load_config()
    try:
        if not os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE, 'w') as f:
                json.dump(cfg, f, indent=2)
    except Exception as e:
        log(f"Error saving default config: {e}")
    log(f"Server: {cfg['server']}")
    Host(cfg).run()