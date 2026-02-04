#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Remote Desktop Server v2.0
Улучшенный сервер для удаленного доступа к компьютеру
- Стабильное соединение с keepalive
- Улучшенная обработка ошибок
- Настраиваемое качество
"""

import socket
import threading
import struct
import json
import os
import hashlib
import io
import time
import subprocess
import sys
from pathlib import Path
from typing import Optional, Tuple
from datetime import datetime

try:
    import mss
    from PIL import Image
    import pyautogui
except ImportError as e:
    print("=" * 60)
    print("ОШИБКА: Не найдены зависимости для сервера.")
    print(f"Отсутствует: {e}")
    print("Установите их командой:")
    print("  pip install -r requirements.txt")
    print("=" * 60)
    sys.exit(1)

# Отключаем failsafe pyautogui (мешает при работе в углах экрана)
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.01  # Уменьшаем задержку для быстрого отклика


class RemoteServer:
    VERSION = "2.0"
    
    def __init__(self, host='0.0.0.0', port=5900, password=None):
        self.host = host
        self.port = port
        self.password = password
        self.socket = None
        self.client_socket = None
        self.client_addr = None
        self.running = False
        self.client_connected = False
        
        # Потоки
        self.screen_thread = None
        self.input_thread = None
        self.keepalive_thread = None
        
        # Настройки
        self.screen_quality = 60  # JPEG качество (30-95)
        self.screen_fps = 15  # Кадров в секунду
        self.screen_scale = 1.0  # Масштаб (1.0 = 100%)
        
        # Статистика
        self.frames_sent = 0
        self.bytes_sent = 0
        self.last_activity = time.time()
        
        # Блокировки для потокобезопасности
        self.send_lock = threading.Lock()
        self.socket_lock = threading.Lock()
        
        self._print_banner()
    
    def _print_banner(self):
        """Выводит баннер при запуске"""
        local_ip = self._get_local_ip()
        print("\n" + "=" * 60)
        print(f"  Remote Desktop Server v{self.VERSION}")
        print("=" * 60)
        print(f"  IP адрес:  {local_ip}")
        print(f"  Порт:      {self.port}")
        if self.password:
            print(f"  Пароль:    {self.password}")
        else:
            print("  Пароль:    [ОТКЛЮЧЕН]")
        print(f"  Качество:  {self.screen_quality}%")
        print(f"  FPS:       {self.screen_fps}")
        print("=" * 60 + "\n")
    
    def _get_local_ip(self) -> str:
        """Получает локальный IP адрес"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"
    
    def _hash_password(self, password: str) -> str:
        """Хеширует пароль"""
        password = (password or "").strip()
        return hashlib.sha256(password.encode("utf-8")).hexdigest()
    
    def _send_data(self, data: bytes) -> bool:
        """Отправляет данные клиенту (потокобезопасно)"""
        with self.send_lock:
            if not self.client_socket or not self.client_connected:
                return False
            try:
                self.client_socket.sendall(struct.pack('!I', len(data)) + data)
                self.bytes_sent += len(data) + 4
                self.last_activity = time.time()
                return True
            except Exception as e:
                self._log(f"Ошибка отправки: {e}")
                return False
    
    def _send_json(self, data: dict) -> bool:
        """Отправляет JSON данные"""
        try:
            json_str = json.dumps(data, ensure_ascii=False)
            return self._send_data(json_str.encode('utf-8'))
        except Exception as e:
            self._log(f"Ошибка JSON: {e}")
            return False
    
    def _recv_exact(self, sock, n: int, timeout: float = 30) -> Optional[bytes]:
        """Принимает точно n байт"""
        if not sock:
            return None
        try:
            old_timeout = sock.gettimeout()
            sock.settimeout(timeout)
            
            data = b''
            while len(data) < n:
                try:
                    chunk = sock.recv(n - len(data))
                    if not chunk:
                        return None
                    data += chunk
                except socket.timeout:
                    return None
            
            sock.settimeout(old_timeout)
            return data
        except Exception:
            return None
    
    def _recv_json(self, sock, timeout: float = 30) -> Optional[dict]:
        """Принимает JSON данные"""
        header = self._recv_exact(sock, 4, timeout)
        if not header:
            return None
        
        length = struct.unpack('!I', header)[0]
        if length > 10 * 1024 * 1024:  # Максимум 10 MB
            return None
        
        data = self._recv_exact(sock, length, timeout)
        if not data:
            return None
        
        try:
            return json.loads(data.decode('utf-8'))
        except:
            return None
    
    def _log(self, message: str):
        """Логирует сообщение с временем"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        print(f"[{timestamp}] {message}")
    
    def _authenticate(self, sock) -> bool:
        """Аутентификация клиента"""
        if not self.password:
            # Без пароля - сразу успех
            self._send_json({'type': 'auth_not_required'})
            return True
        
        try:
            # Запрашиваем пароль
            self._send_json({'type': 'auth_required'})
            
            # Ждем ответ
            auth_data = self._recv_json(sock, timeout=30)
            if not auth_data or auth_data.get('type') != 'auth':
                self._log("Не получен ответ на аутентификацию")
                return False
            
            client_password = auth_data.get('password', '')
            if self._hash_password(client_password) == self._hash_password(self.password):
                self._send_json({'type': 'auth_success'})
                return True
            else:
                self._send_json({'type': 'auth_failed'})
                self._log("Неверный пароль")
                return False
        except Exception as e:
            self._log(f"Ошибка аутентификации: {e}")
            return False
    
    def _capture_screen(self) -> bytes:
        """Захватывает экран и возвращает JPEG"""
        try:
            with mss.mss() as sct:
                monitor = sct.monitors[1]  # Основной монитор
                screenshot = sct.grab(monitor)
                img = Image.frombytes("RGB", screenshot.size, screenshot.rgb)
                
                # Масштабирование если нужно
                if self.screen_scale != 1.0:
                    new_size = (int(img.width * self.screen_scale), 
                               int(img.height * self.screen_scale))
                    img = img.resize(new_size, Image.Resampling.LANCZOS)
                
                # Сжимаем в JPEG
                buf = io.BytesIO()
                img.save(buf, format='JPEG', quality=self.screen_quality, optimize=True)
                return buf.getvalue()
        except Exception as e:
            self._log(f"Ошибка захвата экрана: {e}")
            return b''
    
    def _handle_screen_stream(self):
        """Поток для отправки скриншотов"""
        frame_interval = 1.0 / self.screen_fps
        
        while self.running and self.client_connected:
            try:
                start_time = time.time()
                
                frame = self._capture_screen()
                if frame:
                    if not self._send_data(b'SCREEN' + frame):
                        break
                    self.frames_sent += 1
                
                # Поддерживаем стабильный FPS
                elapsed = time.time() - start_time
                sleep_time = frame_interval - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)
                    
            except Exception as e:
                self._log(f"Ошибка стрима экрана: {e}")
                break
        
        self._log("Поток экрана завершен")
    
    def _handle_keepalive(self):
        """Поток для keepalive пингов"""
        while self.running and self.client_connected:
            try:
                time.sleep(5)  # Каждые 5 секунд
                
                if not self.client_connected:
                    break
                
                # Отправляем ping
                if not self._send_json({'type': 'ping', 'time': time.time()}):
                    self._log("Keepalive не удался, клиент отключен")
                    break
                    
            except Exception as e:
                self._log(f"Ошибка keepalive: {e}")
                break
        
        self._log("Поток keepalive завершен")
    
    def _handle_mouse_input(self, x: int, y: int, button: str, action: str):
        """Обрабатывает команды мыши"""
        try:
            # Корректируем координаты если был масштаб
            if self.screen_scale != 1.0:
                x = int(x / self.screen_scale)
                y = int(y / self.screen_scale)
            
            if action == 'move':
                pyautogui.moveTo(x, y, _pause=False)
            elif action == 'click':
                pyautogui.click(x, y, button=button if button != 'middle' else 'middle')
            elif action == 'down':
                pyautogui.moveTo(x, y, _pause=False)
                pyautogui.mouseDown(button=button)
            elif action == 'up':
                pyautogui.moveTo(x, y, _pause=False)
                pyautogui.mouseUp(button=button)
            elif action == 'scroll':
                # button содержит delta для скролла
                try:
                    delta = int(button) // 120  # Windows delta обычно 120
                    pyautogui.scroll(delta, x, y)
                except:
                    pass
            elif action == 'doubleclick':
                pyautogui.doubleClick(x, y, button=button)
        except Exception as e:
            self._log(f"Ошибка мыши: {e}")
    
    def _handle_keyboard_input(self, key: str, action: str):
        """Обрабатывает команды клавиатуры"""
        try:
            if action == 'press':
                pyautogui.press(key)
            elif action == 'type':
                pyautogui.write(key, interval=0.02)
            elif action == 'keyDown':
                pyautogui.keyDown(key)
            elif action == 'keyUp':
                pyautogui.keyUp(key)
            elif action == 'hotkey':
                # Комбинация клавиш типа "ctrl+c"
                keys = key.split('+')
                pyautogui.hotkey(*keys)
        except Exception as e:
            self._log(f"Ошибка клавиатуры: {e}")
    
    def _handle_file_request(self, request: dict):
        """Обрабатывает запросы файлового менеджера"""
        cmd = request.get('command')
        
        if cmd == 'list_drives':
            drives = []
            try:
                import string
                for letter in string.ascii_uppercase:
                    drive_path = f"{letter}:\\"
                    if os.path.exists(drive_path):
                        try:
                            # Получаем информацию о диске
                            import ctypes
                            free_bytes = ctypes.c_ulonglong(0)
                            total_bytes = ctypes.c_ulonglong(0)
                            ctypes.windll.kernel32.GetDiskFreeSpaceExW(
                                ctypes.c_wchar_p(drive_path), None, 
                                ctypes.pointer(total_bytes), 
                                ctypes.pointer(free_bytes)
                            )
                            drives.append({
                                'path': drive_path,
                                'name': f'Диск {letter}:',
                                'type': 'drive',
                                'total': total_bytes.value,
                                'free': free_bytes.value
                            })
                        except:
                            drives.append({
                                'path': drive_path,
                                'name': f'Диск {letter}:',
                                'type': 'drive'
                            })
            except Exception as e:
                self._log(f"Ошибка получения дисков: {e}")
            
            self._send_json({
                'type': 'file_response',
                'command': 'list_drives',
                'data': drives
            })
        
        elif cmd == 'list_dir':
            path = request.get('path', '')
            try:
                items = []
                if os.path.exists(path) and os.path.isdir(path):
                    for item in os.listdir(path):
                        item_path = os.path.join(path, item)
                        try:
                            stat = os.stat(item_path)
                            is_dir = os.path.isdir(item_path)
                            items.append({
                                'name': item,
                                'path': item_path,
                                'type': 'directory' if is_dir else 'file',
                                'size': stat.st_size if not is_dir else 0,
                                'modified': stat.st_mtime
                            })
                        except (PermissionError, OSError):
                            # Пропускаем файлы без доступа
                            pass
                
                # Сортируем: папки сначала, потом файлы
                items.sort(key=lambda x: (0 if x['type'] == 'directory' else 1, x['name'].lower()))
                
                self._send_json({
                    'type': 'file_response',
                    'command': 'list_dir',
                    'path': path,
                    'data': items
                })
            except Exception as e:
                self._send_json({
                    'type': 'file_response',
                    'command': 'list_dir',
                    'error': str(e)
                })
        
        elif cmd == 'run_command':
            command_str = request.get('command_str', '') or request.get('shell', '')
            cwd = request.get('cwd') or os.getcwd()
            
            try:
                if not command_str.strip():
                    self._send_json({
                        'type': 'terminal_response',
                        'command': command_str,
                        'output': '',
                        'error': 'Пустая команда',
                        'returncode': -1,
                        'cwd': cwd
                    })
                    return
                
                result = subprocess.run(
                    command_str,
                    shell=True,
                    cwd=cwd,
                    capture_output=True,
                    text=True,
                    encoding='utf-8',
                    errors='replace',
                    timeout=120
                )
                
                self._send_json({
                    'type': 'terminal_response',
                    'command': command_str,
                    'output': result.stdout[-10000:],
                    'error': result.stderr[-5000:],
                    'returncode': result.returncode,
                    'cwd': cwd
                })
            except subprocess.TimeoutExpired:
                self._send_json({
                    'type': 'terminal_response',
                    'command': command_str,
                    'output': '',
                    'error': 'Таймаут команды (120 сек)',
                    'returncode': -1,
                    'cwd': cwd
                })
            except Exception as e:
                self._send_json({
                    'type': 'terminal_response',
                    'command': command_str,
                    'output': '',
                    'error': str(e),
                    'returncode': -1,
                    'cwd': cwd
                })
        
        elif cmd == 'download_file':
            file_path = request.get('path', '')
            try:
                if os.path.isfile(file_path):
                    file_size = os.path.getsize(file_path)
                    self._send_json({
                        'type': 'file_response',
                        'command': 'download_file',
                        'filename': os.path.basename(file_path),
                        'size': file_size
                    })
                    
                    with open(file_path, 'rb') as f:
                        chunk_size = 64 * 1024
                        while True:
                            chunk = f.read(chunk_size)
                            if not chunk:
                                break
                            self._send_data(b'FILE_DATA' + chunk)
                    
                    self._send_data(b'FILE_END')
                else:
                    self._send_json({
                        'type': 'file_response',
                        'command': 'download_file',
                        'error': 'Файл не найден'
                    })
            except Exception as e:
                self._send_json({
                    'type': 'file_response',
                    'command': 'download_file',
                    'error': str(e)
                })
        
        elif cmd == 'set_quality':
            quality = request.get('quality', 60)
            fps = request.get('fps', 15)
            scale = request.get('scale', 1.0)
            
            self.screen_quality = max(20, min(95, quality))
            self.screen_fps = max(1, min(30, fps))
            self.screen_scale = max(0.25, min(1.0, scale))
            
            self._log(f"Качество: {self.screen_quality}%, FPS: {self.screen_fps}, Масштаб: {self.screen_scale}")
            
            self._send_json({
                'type': 'settings_updated',
                'quality': self.screen_quality,
                'fps': self.screen_fps,
                'scale': self.screen_scale
            })
    
    def _handle_client_input(self):
        """Обрабатывает команды от клиента"""
        sock = self.client_socket
        
        while self.running and self.client_connected and sock:
            try:
                header = self._recv_exact(sock, 4, timeout=60)
                if not header:
                    self._log("Клиент не отвечает (таймаут)")
                    break
                
                length = struct.unpack('!I', header)[0]
                if length > 10 * 1024 * 1024:
                    self._log("Слишком большой пакет")
                    break
                
                data = self._recv_exact(sock, length, timeout=60)
                if not data:
                    break
                
                self.last_activity = time.time()
                
                # Обработка разных типов данных
                if data.startswith(b'FILE_DATA') or data.startswith(b'FILE_END'):
                    continue
                
                try:
                    request = json.loads(data.decode('utf-8'))
                    req_type = request.get('type')
                    
                    if req_type == 'mouse':
                        self._handle_mouse_input(
                            request.get('x', 0),
                            request.get('y', 0),
                            request.get('button', 'left'),
                            request.get('action', 'click')
                        )
                    elif req_type == 'keyboard':
                        self._handle_keyboard_input(
                            request.get('key', ''),
                            request.get('action', 'press')
                        )
                    elif req_type == 'file_request':
                        self._handle_file_request(request)
                    elif req_type == 'pong':
                        # Ответ на ping - клиент жив
                        pass
                    elif req_type == 'disconnect':
                        self._log("Клиент запросил отключение")
                        break
                except json.JSONDecodeError:
                    pass
                    
            except Exception as e:
                self._log(f"Ошибка обработки: {e}")
                break
        
        self._disconnect_client()
    
    def _disconnect_client(self):
        """Отключает текущего клиента"""
        self.client_connected = False
        
        with self.socket_lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
                self.client_socket = None
        
        if self.client_addr:
            self._log(f"Клиент {self.client_addr} отключен")
            self.client_addr = None
        
        # Сбрасываем статистику
        self.frames_sent = 0
        self.bytes_sent = 0
    
    def start(self):
        """Запускает сервер"""
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        
        # Включаем TCP keepalive на уровне сокета
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        
        try:
            self.socket.bind((self.host, self.port))
            self.socket.listen(1)
            self.running = True
            
            self._log("Сервер запущен, ожидание подключений...")
            
            while self.running:
                try:
                    # Ждем нового клиента только если текущий отключен
                    if self.client_connected:
                        time.sleep(0.5)
                        continue
                    
                    self.socket.settimeout(1.0)  # Таймаут для проверки self.running
                    try:
                        conn, addr = self.socket.accept()
                    except socket.timeout:
                        continue
                    
                    self._log(f"Новое подключение от {addr}")
                    
                    # Настраиваем сокет клиента
                    conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                    conn.settimeout(60)
                    
                    self.client_socket = conn
                    self.client_addr = addr
                    
                    # Аутентификация
                    if not self._authenticate(conn):
                        self._log("Аутентификация не пройдена")
                        self._disconnect_client()
                        continue
                    
                    self._log(f"Клиент {addr} успешно подключен!")
                    self.client_connected = True
                    
                    # Отправляем информацию о сервере
                    self._send_json({
                        'type': 'server_info',
                        'version': self.VERSION,
                        'quality': self.screen_quality,
                        'fps': self.screen_fps,
                        'scale': self.screen_scale
                    })
                    
                    # Запускаем потоки
                    self.screen_thread = threading.Thread(
                        target=self._handle_screen_stream,
                        name="ScreenThread",
                        daemon=True
                    )
                    self.input_thread = threading.Thread(
                        target=self._handle_client_input,
                        name="InputThread",
                        daemon=True
                    )
                    self.keepalive_thread = threading.Thread(
                        target=self._handle_keepalive,
                        name="KeepaliveThread",
                        daemon=True
                    )
                    
                    self.screen_thread.start()
                    self.input_thread.start()
                    self.keepalive_thread.start()
                    
                except Exception as e:
                    if self.running:
                        self._log(f"Ошибка: {e}")
                    self._disconnect_client()
                    
        except Exception as e:
            self._log(f"Критическая ошибка: {e}")
        finally:
            self.stop()
    
    def stop(self):
        """Останавливает сервер"""
        self._log("Остановка сервера...")
        self.running = False
        self._disconnect_client()
        
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
        
        self._log("Сервер остановлен")


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description='Remote Desktop Server v2.0')
    parser.add_argument('--host', default='0.0.0.0', help='IP адрес для прослушивания')
    parser.add_argument('--port', '-p', type=int, default=5900, help='Порт (по умолчанию 5900)')
    parser.add_argument('--password', '-P', default=None, help='Пароль для подключения')
    parser.add_argument('--quality', '-q', type=int, default=60, help='Качество JPEG (20-95)')
    parser.add_argument('--fps', '-f', type=int, default=15, help='Кадров в секунду (1-30)')
    
    args = parser.parse_args()
    
    server = RemoteServer(
        host=args.host,
        port=args.port,
        password=args.password
    )
    server.screen_quality = max(20, min(95, args.quality))
    server.screen_fps = max(1, min(30, args.fps))
    
    try:
        server.start()
    except KeyboardInterrupt:
        print("\n")
        server.stop()


if __name__ == '__main__':
    main()
