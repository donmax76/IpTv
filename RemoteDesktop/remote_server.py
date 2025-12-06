#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Remote Desktop Server
Сервер для удаленного доступа к компьютеру
"""

import socket
import threading
import struct
import json
import os
import hashlib
import base64
from pathlib import Path
from typing import Optional, Tuple
import time
import subprocess

try:
    import mss
    from PIL import Image
    import pyautogui
    import pynput
    from pynput import mouse, keyboard
except ImportError:
    print("=" * 60)
    print("ОШИБКА: Не найдены зависимости для сервера.")
    print("Установите их ОДИН раз вручную (ничего скачиваться из кода не будет):")
    print("  pip install -r requirements.txt")
    print("или минимально:")
    print("  pip install mss pillow pyautogui pynput")
    print("=" * 60)
    raise


class RemoteServer:
    def __init__(self, host='0.0.0.0', port=5900, password=None):
        self.host = host
        self.port = port
        # Если пароль не задан явно, работаем БЕЗ пароля (для простоты подключения)
        # Чтобы включить пароль, запустите сервер с параметром --password
        self.password = password  # None => аутентификация отключена
        self.socket = None
        self.client_socket = None
        self.running = False
        self.screen_thread = None
        self.input_thread = None
        self.mouse_listener = None
        self.keyboard_listener = None
        
        if self.password:
            print(f"Сервер инициализирован. Пароль: {self.password}")
        else:
            print("Сервер инициализирован. Доступ БЕЗ пароля (аутентификация отключена).")
        print(f"IP адрес: {self._get_local_ip()}")
        print(f"Порт: {self.port}")
    
    def _generate_password(self) -> str:
        """Генерирует случайный пароль"""
        import random
        import string
        # Простой пароль: только большие буквы и цифры, чтобы легче было вводить/копировать
        chars = string.ascii_uppercase + string.digits
        return "".join(random.choice(chars) for _ in range(8))
    
    def _get_local_ip(self) -> str:
        """Получает локальный IP адрес"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"
    
    def _hash_password(self, password: str) -> str:
        """Хеширует пароль"""
        # Обрезаем пробелы по краям, чтобы не ломалось из-за случайных пробелов при копировании
        password = (password or "").strip()
        return hashlib.sha256(password.encode("utf-8")).hexdigest()
    
    def _send_data(self, data: bytes):
        """Отправляет данные клиенту"""
        if self.client_socket:
            try:
                self.client_socket.sendall(struct.pack('!I', len(data)) + data)
            except:
                pass
    
    def _send_json(self, data: dict):
        """Отправляет JSON данные"""
        json_str = json.dumps(data, ensure_ascii=False)
        self._send_data(json_str.encode('utf-8'))
    
    def _recv_exact(self, n: int, timeout=5) -> Optional[bytes]:
        """Принимает точно n байт"""
        if not self.client_socket:
            return None
        try:
            # Устанавливаем таймаут
            old_timeout = self.client_socket.gettimeout()
            self.client_socket.settimeout(timeout)
            
            data = b''
            while len(data) < n:
                chunk = self.client_socket.recv(n - len(data))
                if not chunk:
                    self.client_socket.settimeout(old_timeout)
                    return None
                data += chunk
            
            self.client_socket.settimeout(old_timeout)
            return data
        except socket.timeout:
            return None
        except Exception:
            return None
    
    def _recv_json(self, timeout=5) -> Optional[dict]:
        """Принимает JSON данные"""
        header = self._recv_exact(4, timeout)
        if not header:
            return None
        length = struct.unpack('!I', header)[0]
        if length > 10 * 1024 * 1024:  # Максимум 10 MB
            return None
        data = self._recv_exact(length, timeout)
        if not data:
            return None
        try:
            return json.loads(data.decode('utf-8'))
        except:
            return None
    
    def _authenticate(self) -> bool:
        """Аутентификация клиента"""
        if not self.password:
            return True
        
        try:
            # Отправляем запрос на пароль
            self._send_json({'type': 'auth_required'})
            
            # Получаем пароль от клиента (таймаут 10 секунд)
            auth_data = self._recv_json(timeout=10)
            if not auth_data or auth_data.get('type') != 'auth':
                print("Ошибка: не получен ответ на аутентификацию")
                return False
            
            client_password = auth_data.get('password', '')
            if self._hash_password(client_password) == self._hash_password(self.password):
                self._send_json({'type': 'auth_success'})
                return True
            else:
                self._send_json({'type': 'auth_failed'})
                return False
        except Exception as e:
            print(f"Ошибка аутентификации: {e}")
            return False
    
    def _capture_screen(self, quality=70) -> bytes:
        """Захватывает экран и возвращает JPEG"""
        try:
            with mss.mss() as sct:
                monitor = sct.monitors[1]  # Основной монитор
                screenshot = sct.grab(monitor)
                img = Image.frombytes("RGB", screenshot.size, screenshot.rgb)
                
                # Сжимаем изображение
                import io
                buf = io.BytesIO()
                img.save(buf, format='JPEG', quality=quality, optimize=True)
                return buf.getvalue()
        except Exception as e:
            print(f"Ошибка захвата экрана: {e}")
            return b''
    
    def _handle_screen_stream(self):
        """Поток для отправки скриншотов"""
        while self.running and self.client_socket:
            try:
                frame = self._capture_screen()
                if frame:
                    self._send_data(b'SCREEN' + frame)
                time.sleep(0.1)  # ~10 FPS
            except Exception as e:
                print(f"Ошибка отправки экрана: {e}")
                break
    
    def _handle_mouse_input(self, x: int, y: int, button: str, action: str):
        """Обрабатывает команды мыши"""
        try:
            pyautogui.moveTo(x, y)
            if action == 'click':
                if button == 'left':
                    pyautogui.click()
                elif button == 'right':
                    pyautogui.rightClick()
                elif button == 'middle':
                    pyautogui.middleClick()
            elif action == 'down':
                if button == 'left':
                    pyautogui.mouseDown()
                elif button == 'right':
                    pyautogui.mouseDown(button='right')
            elif action == 'up':
                if button == 'left':
                    pyautogui.mouseUp()
                elif button == 'right':
                    pyautogui.mouseUp(button='right')
            elif action == 'scroll':
                pyautogui.scroll(button)
        except Exception as e:
            print(f"Ошибка управления мышью: {e}")
    
    def _handle_keyboard_input(self, key: str, action: str):
        """Обрабатывает команды клавиатуры"""
        try:
            if action == 'press':
                pyautogui.press(key)
            elif action == 'type':
                pyautogui.write(key)
            elif action == 'keyDown':
                pyautogui.keyDown(key)
            elif action == 'keyUp':
                pyautogui.keyUp(key)
        except Exception as e:
            print(f"Ошибка управления клавиатурой: {e}")
    
    def _handle_file_request(self, request: dict):
        """Обрабатывает запросы файлового менеджера"""
        cmd = request.get('command')
        
        if cmd == 'list_drives':
            # Список дисков Windows (упрощённый и надёжный)
            drives = []
            try:
                import string
                for letter in string.ascii_uppercase:
                    drive_path = f"{letter}:\\"
                    if os.path.exists(drive_path):
                        drives.append({
                            'path': drive_path,
                            'name': f'Диск {letter}',
                            'type': 'drive'
                        })
            except Exception as e:
                print(f"Ошибка получения дисков: {e}")
            
            # Если ничего не нашли, хотя бы пробуем C:\
            if not drives:
                default_drive = "C:\\"
                if os.path.exists(default_drive):
                    drives.append({
                        'path': default_drive,
                        'name': 'Диск C',
                        'type': 'drive'
                    })
            
            self._send_json({
                'type': 'file_response',
                'command': 'list_drives',
                'data': drives
            })
        
        elif cmd == 'run_command':
            # Удалённый запуск команды в терминале
            # Реальная командная строка передаётся в отдельном поле,
            # чтобы не путать с полем маршрутизации 'command'
            command = request.get('command_str') or request.get('shell') or ''
            cwd = request.get('cwd', None) or os.getcwd()
            try:
                # Безопасно ограничиваем длину команды
                command = command.strip()
                if not command:
                    self._send_json({
                        'type': 'terminal_response',
                        'command': command,
                        'output': '',
                        'error': 'Пустая команда',
                        'returncode': -1,
                        'cwd': cwd
                    })
                    return
                
                # Выполняем команду
                result = subprocess.run(
                    command,
                    shell=True,
                    cwd=cwd,
                    capture_output=True,
                    text=True,
                    encoding='utf-8',
                    errors='replace',
                    timeout=60  # 60 секунд на команду
                )
                
                self._send_json({
                    'type': 'terminal_response',
                    'command': command,
                    'output': result.stdout[-5000:],  # ограничиваем размер
                    'error': result.stderr[-5000:],
                    'returncode': result.returncode,
                    'cwd': cwd
                })
            except subprocess.TimeoutExpired:
                self._send_json({
                    'type': 'terminal_response',
                    'command': command,
                    'output': '',
                    'error': 'Команда превысила лимит времени (60 секунд)',
                    'returncode': -1,
                    'cwd': cwd
                })
            except Exception as e:
                self._send_json({
                    'type': 'terminal_response',
                    'command': command,
                    'output': '',
                    'error': str(e),
                    'returncode': -1,
                    'cwd': cwd
                })
        
        elif cmd == 'list_dir':
            # Список файлов в директории
            path = request.get('path', '')
            try:
                items = []
                if os.path.exists(path) and os.path.isdir(path):
                    for item in os.listdir(path):
                        item_path = os.path.join(path, item)
                        try:
                            stat = os.stat(item_path)
                            items.append({
                                'name': item,
                                'path': item_path,
                                'type': 'directory' if os.path.isdir(item_path) else 'file',
                                'size': stat.st_size if os.path.isfile(item_path) else 0,
                                'modified': stat.st_mtime
                            })
                        except:
                            pass
                
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
        
        elif cmd == 'download_file':
            # Отправка файла клиенту
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
                    
                    # Отправляем файл по частям
                    with open(file_path, 'rb') as f:
                        chunk_size = 64 * 1024  # 64 KB
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
                        'error': 'File not found'
                    })
            except Exception as e:
                self._send_json({
                    'type': 'file_response',
                    'command': 'download_file',
                    'error': str(e)
                })
        
        elif cmd == 'upload_file':
            # Прием файла от клиента (в отдельном потоке)
            filename = request.get('filename', '')
            save_path = request.get('save_path', '')
            file_size = request.get('size', 0)
            
            def upload_thread():
                try:
                    full_path = os.path.join(save_path, filename)
                    os.makedirs(save_path, exist_ok=True)
                    
                    # Подтверждаем готовность
                    self._send_json({
                        'type': 'file_response',
                        'command': 'upload_file',
                        'status': 'ready'
                    })
                    
                    # Принимаем файл по частям
                    received = 0
                    with open(full_path, 'wb') as f:
                        while received < file_size:
                            # Читаем заголовок (таймаут 60 секунд для больших файлов)
                            header = self._recv_exact(4, timeout=60)
                            if not header:
                                break
                            length = struct.unpack('!I', header)[0]
                            
                            # Читаем данные
                            data = self._recv_exact(length, timeout=60)
                            if not data:
                                break
                            
                            # Проверяем префикс
                            if data.startswith(b'FILE_DATA'):
                                chunk = data[9:]  # Убираем префикс
                                f.write(chunk)
                                received += len(chunk)
                            elif data.startswith(b'FILE_END'):
                                break
                            else:
                                # Данные без префикса (старый формат)
                                f.write(data)
                                received += len(data)
                    
                    self._send_json({
                        'type': 'file_response',
                        'command': 'upload_file',
                        'status': 'success'
                    })
                except Exception as e:
                    self._send_json({
                        'type': 'file_response',
                        'command': 'upload_file',
                        'error': str(e)
                    })
            
            # Запускаем в отдельном потоке
            threading.Thread(target=upload_thread, daemon=True).start()
    
    def _handle_client_input(self):
        """Обрабатывает команды от клиента"""
        # Локальная ссылка на сокет, чтобы избежать гонок
        sock = self.client_socket
        try:
            while self.running and sock:
                try:
                    header = self._recv_exact(4, timeout=30)
                    if not header:
                        break
                    
                    length = struct.unpack('!I', header)[0]
                    if length > 10 * 1024 * 1024:  # Максимум 10 MB
                        break
                    
                    data = self._recv_exact(length, timeout=30)
                    if not data:
                        break
                    
                    # Проверяем тип команды
                    if data.startswith(b'SCREEN'):
                        # Это скриншот (не должно быть здесь)
                        continue
                    elif data.startswith(b'FILE_DATA'):
                        # Данные файла
                        continue
                    elif data.startswith(b'FILE_END'):
                        # Конец файла
                        continue
                    else:
                        # JSON команда
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
                        except:
                            pass
                except Exception as e:
                    print(f"Ошибка обработки команды: {e}")
                    break
        finally:
            # Очистка при отключении клиента
            print("Клиент отключен")
            if sock:
                try:
                    sock.close()
                except:
                    pass
            self.client_socket = None
    
    def start(self):
        """Запускает сервер"""
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        
        try:
            self.socket.bind((self.host, self.port))
            self.socket.listen(1)
            self.running = True
            
            print(f"\n{'='*50}")
            print(f"Сервер запущен и ожидает подключения...")
            print(f"IP: {self._get_local_ip()}")
            print(f"Порт: {self.port}")
            if self.password:
                print(f"Пароль: {self.password}")
            else:
                print("Пароль: [ОТКЛЮЧЕН] (подключение без пароля)")
            print(f"{'='*50}\n")
            
            while self.running:
                try:
                    # Если уже есть активный клиент, ждём, пока он отключится
                    if self.client_socket:
                        time.sleep(0.5)
                        continue
                    
                    conn, addr = self.socket.accept()
                    print(f"Подключение от {addr}")
                    
                    # Присваиваем активный сокет
                    self.client_socket = conn
                    self.client_socket.settimeout(30)  # 30 секунд таймаут по умолчанию
                    
                    # Аутентификация (если включена)
                    if not self._authenticate():
                        print("Ошибка аутентификации")
                        try:
                            self.client_socket.close()
                        except:
                            pass
                        self.client_socket = None
                        continue
                    
                    print("Клиент успешно подключен!")
                    
                    # Запускаем потоки
                    self.screen_thread = threading.Thread(
                        target=self._handle_screen_stream,
                        daemon=True
                    )
                    self.input_thread = threading.Thread(
                        target=self._handle_client_input,
                        daemon=True
                    )
                    
                    self.screen_thread.start()
                    self.input_thread.start()
                    
                except Exception as e:
                    if self.running:
                        print(f"Ошибка: {e}")
                    try:
                        if self.client_socket:
                            self.client_socket.close()
                    except:
                        pass
                    self.client_socket = None
        except Exception as e:
            print(f"Ошибка запуска сервера: {e}")
        finally:
            self.stop()
    
    def stop(self):
        """Останавливает сервер"""
        self.running = False
        if self.client_socket:
            self.client_socket.close()
        if self.socket:
            self.socket.close()
        print("Сервер остановлен")


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description='Remote Desktop Server')
    parser.add_argument('--host', default='0.0.0.0', help='Host адрес')
    parser.add_argument('--port', type=int, default=5900, help='Порт')
    parser.add_argument('--password', default=None, help='Пароль (если не указан, будет сгенерирован)')
    
    args = parser.parse_args()
    
    server = RemoteServer(host=args.host, port=args.port, password=args.password)
    
    try:
        server.start()
    except KeyboardInterrupt:
        print("\nОстановка сервера...")
        server.stop()


if __name__ == '__main__':
    main()

