#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Remote Desktop Client
Клиент для удаленного доступа к компьютеру
"""

import socket
import struct
import json
import threading
import os
import time
from pathlib import Path
from typing import Optional

try:
    import tkinter as tk
    from tkinter import ttk, messagebox, filedialog
    import cv2
    import numpy as np
    from PIL import Image, ImageTk
except ImportError:
    print("=" * 60)
    print("ОШИБКА: Не найдены зависимости для клиента.")
    print("Установите их ОДИН раз вручную (ничего скачиваться из кода не будет):")
    print("  pip install -r requirements.txt")
    print("или минимально:")
    print("  pip install pillow opencv-python numpy")
    print("=" * 60)
    raise


class RemoteClient:
    def __init__(self):
        self.socket = None
        self.connected = False
        self.screen_thread = None
        self.receive_thread = None
        self.current_path = ""
        self.file_transfer_in_progress = False
        self.download_file_path = None
        self.download_file_handle = None
        self.download_file_size = 0
        self.download_file_received = 0
        self.upload_file_path = None
        self.upload_file_size = 0
        self.original_img_size = None
        self.displayed_img_size = None
        
        # GUI
        self.root = tk.Tk()
        self.root.title("Remote Desktop Client - Удаленный доступ")
        self.root.geometry("1400x900")
        self.root.minsize(1000, 700)
        
        # Центрируем окно
        self.center_window()
        
        self.setup_gui()
    
    # ---------- Вспомогательные методы GUI ----------
    
    def _toggle_password_visibility(self):
        """Переключает отображение/скрытие пароля"""
        if self.show_password_var.get():
            self.password_entry.config(show="")
        else:
            self.password_entry.config(show="*")
    
    def _init_password_context_menu(self):
        """Создает контекстное меню для поля пароля (копировать/вставить)"""
        menu = tk.Menu(self.root, tearoff=0)
        menu.add_command(label="Копировать", command=lambda: self.password_entry.event_generate("<<Copy>>"))
        menu.add_command(label="Вставить", command=lambda: self.password_entry.event_generate("<<Paste>>"))
        menu.add_command(label="Вырезать", command=lambda: self.password_entry.event_generate("<<Cut>>"))
        
        def show_menu(event):
            try:
                menu.tk_popup(event.x_root, event.y_root)
            finally:
                menu.grab_release()
        
        # Правый клик мыши по полю пароля
        self.password_entry.bind("<Button-3>", show_menu)
    
    def _append_terminal_text(self, text: str):
        """Добавляет текст в окно терминала"""
        self.terminal_text.configure(state="normal")
        self.terminal_text.insert(tk.END, text)
        self.terminal_text.see(tk.END)
        self.terminal_text.configure(state="disabled")
    
    def send_terminal_command(self):
        """Отправляет команду на сервер для выполнения в терминале"""
        if not self.connected:
            messagebox.showwarning("Терминал", "Сначала подключитесь к серверу")
            return
        
        cmd = self.terminal_entry.get().strip()
        if not cmd:
            return
        
        self._append_terminal_text(f"\n> {cmd}\n")
        
        self._send_json({
            'type': 'file_request',
            'command': 'run_command',
            'command_str': cmd,
            'cwd': ""  # можно позже расширить до выбора директории
        })
        
        self.terminal_entry.delete(0, tk.END)
    
    def center_window(self):
        """Центрирует окно на экране"""
        self.root.update_idletasks()
        width = self.root.winfo_width()
        height = self.root.winfo_height()
        x = (self.root.winfo_screenwidth() // 2) - (width // 2)
        y = (self.root.winfo_screenheight() // 2) - (height // 2)
        self.root.geometry(f'{width}x{height}+{x}+{y}')
    
    def set_connection(self, ip: str, port: str):
        """Устанавливает параметры подключения"""
        self.ip_entry.delete(0, tk.END)
        self.ip_entry.insert(0, ip)
        self.port_entry.delete(0, tk.END)
        self.port_entry.insert(0, port)
    
    def find_local_ip(self):
        """Находит локальный IP адрес"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            self.ip_entry.delete(0, tk.END)
            self.ip_entry.insert(0, ip)
            messagebox.showinfo("IP найден", f"Локальный IP: {ip}")
        except Exception as e:
            messagebox.showerror("Ошибка", f"Не удалось определить IP: {e}")
    
    def setup_gui(self):
        """Настройка интерфейса"""
        # Главная панель подключения
        main_frame = ttk.Frame(self.root)
        main_frame.pack(fill=tk.X, padx=10, pady=10)
        
        # Заголовок
        title_frame = ttk.Frame(main_frame)
        title_frame.pack(fill=tk.X, pady=(0, 10))
        title_label = ttk.Label(title_frame, text="Remote Desktop Client", font=("Arial", 14, "bold"))
        title_label.pack(side=tk.LEFT)
        
        # Статус подключения
        self.status_label = ttk.Label(title_frame, text="● Отключено", foreground="red", font=("Arial", 10))
        self.status_label.pack(side=tk.RIGHT, padx=10)
        
        # Панель подключения
        conn_frame = ttk.LabelFrame(main_frame, text="Подключение", padding=10)
        conn_frame.pack(fill=tk.X, pady=5)
        
        # Первая строка - IP и быстрые кнопки
        ip_frame = ttk.Frame(conn_frame)
        ip_frame.pack(fill=tk.X, pady=5)
        
        ttk.Label(ip_frame, text="IP адрес:", width=10).pack(side=tk.LEFT, padx=5)
        self.ip_entry = ttk.Entry(ip_frame, width=20)
        self.ip_entry.insert(0, "127.0.0.1")
        self.ip_entry.pack(side=tk.LEFT, padx=5)
        
        # Быстрые кнопки подключения
        quick_frame = ttk.Frame(ip_frame)
        quick_frame.pack(side=tk.LEFT, padx=10)
        
        ttk.Button(quick_frame, text="localhost", width=10, 
                  command=lambda: self.set_connection("127.0.0.1", "5900")).pack(side=tk.LEFT, padx=2)
        
        ttk.Button(quick_frame, text="Найти IP", width=10,
                  command=self.find_local_ip).pack(side=tk.LEFT, padx=2)
        
        # Вторая строка - Порт и Пароль
        auth_frame = ttk.Frame(conn_frame)
        auth_frame.pack(fill=tk.X, pady=5)
        
        ttk.Label(auth_frame, text="Порт:", width=10).pack(side=tk.LEFT, padx=5)
        self.port_entry = ttk.Entry(auth_frame, width=10)
        self.port_entry.insert(0, "5900")
        self.port_entry.pack(side=tk.LEFT, padx=5)
        
        ttk.Label(auth_frame, text="Пароль:", width=10).pack(side=tk.LEFT, padx=5)
        self.password_entry = ttk.Entry(auth_frame, width=25, show="*")
        self.password_entry.pack(side=tk.LEFT, padx=5)
        
        # Чекбокс "Показать пароль"
        self.show_password_var = tk.BooleanVar(value=False)
        show_pass_cb = ttk.Checkbutton(
            auth_frame,
            text="Показать",
            variable=self.show_password_var,
            command=self._toggle_password_visibility
        )
        show_pass_cb.pack(side=tk.LEFT, padx=5)
        
        # Контекстное меню для пароля (копировать/вставить)
        self._init_password_context_menu()
        
        # Третья строка - Кнопки управления
        btn_frame = ttk.Frame(conn_frame)
        btn_frame.pack(fill=tk.X, pady=5)
        
        self.connect_btn = ttk.Button(btn_frame, text="🔌 Подключиться", command=self.connect, width=20)
        self.connect_btn.pack(side=tk.LEFT, padx=5)
        
        self.disconnect_btn = ttk.Button(btn_frame, text="❌ Отключиться", command=self.disconnect, 
                                        state=tk.DISABLED, width=20)
        self.disconnect_btn.pack(side=tk.LEFT, padx=5)
        
        # Переключатель управления (вкл/выкл отправку мыши и клавиатуры)
        self.control_enabled_var = tk.BooleanVar(value=True)
        control_cb = ttk.Checkbutton(
            btn_frame,
            text="Разрешить управление",
            variable=self.control_enabled_var
        )
        control_cb.pack(side=tk.LEFT, padx=10)
        
        # Информационная панель
        info_frame = ttk.Frame(main_frame)
        info_frame.pack(fill=tk.X, pady=5)
        info_text = "💡 Подсказка: Для подключения к самому себе используйте localhost или 127.0.0.1"
        ttk.Label(info_frame, text=info_text, foreground="gray", font=("Arial", 9)).pack()
        
        # Notebook для вкладок
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        # Вкладка удаленного рабочего стола
        self.screen_frame = ttk.Frame(self.notebook)
        self.notebook.add(self.screen_frame, text="🖥️ Рабочий стол")
        
        # Панель управления экраном
        screen_control = ttk.Frame(self.screen_frame)
        screen_control.pack(fill=tk.X, padx=5, pady=5)
        ttk.Label(screen_control, text="Кликните на экране для управления мышью. Используйте клавиатуру для ввода.", 
                 foreground="gray", font=("Arial", 9)).pack()
        
        # Контейнер для экрана с рамкой
        screen_container = ttk.Frame(self.screen_frame, relief=tk.SUNKEN, borderwidth=2)
        screen_container.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.screen_label = ttk.Label(screen_container, text="Не подключено\n\nЗапустите сервер и нажмите 'Подключиться'", 
                                     anchor=tk.CENTER, font=("Arial", 12))
        self.screen_label.pack(expand=True, fill=tk.BOTH, padx=10, pady=10)
        
        # Привязка событий мыши
        self.screen_label.bind("<Button-1>", self.on_mouse_click)
        self.screen_label.bind("<Button-3>", self.on_mouse_right_click)
        self.screen_label.bind("<B1-Motion>", self.on_mouse_drag)
        self.screen_label.bind("<ButtonRelease-1>", self.on_mouse_release)
        self.screen_label.bind("<MouseWheel>", self.on_mouse_wheel)
        self.screen_label.bind("<KeyPress>", self.on_key_press)
        self.screen_label.focus_set()
        
        # Вкладка файлового менеджера
        self.file_frame = ttk.Frame(self.notebook)
        self.notebook.add(self.file_frame, text="📁 Файлы")
        
        # Панель навигации
        nav_frame = ttk.LabelFrame(self.file_frame, text="Навигация", padding=5)
        nav_frame.pack(fill=tk.X, padx=5, pady=5)
        
        nav_btn_frame = ttk.Frame(nav_frame)
        nav_btn_frame.pack(fill=tk.X)
        
        ttk.Button(nav_btn_frame, text="🔄 Обновить", command=self.refresh_files).pack(side=tk.LEFT, padx=2)
        ttk.Button(nav_btn_frame, text="◀ Назад", command=self.go_back).pack(side=tk.LEFT, padx=2)
        ttk.Button(nav_btn_frame, text="⬆ Вверх", command=self.go_up).pack(side=tk.LEFT, padx=2)
        ttk.Button(nav_btn_frame, text="💾 Диски", command=self.list_drives).pack(side=tk.LEFT, padx=2)
        
        # Путь
        path_frame = ttk.Frame(nav_frame)
        path_frame.pack(fill=tk.X, pady=5)
        ttk.Label(path_frame, text="Путь:").pack(side=tk.LEFT, padx=5)
        self.path_label = ttk.Label(path_frame, text="Не подключено", foreground="gray", 
                                   relief=tk.SUNKEN, anchor=tk.W, padding=5)
        self.path_label.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=5)
        
        # Список файлов
        file_list_frame = ttk.Frame(self.file_frame)
        file_list_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # Treeview для файлов
        columns = ('name', 'type', 'size', 'modified')
        self.file_tree = ttk.Treeview(file_list_frame, columns=columns, show='tree headings')
        self.file_tree.heading('#0', text='Имя')
        self.file_tree.heading('name', text='Имя')
        self.file_tree.heading('type', text='Тип')
        self.file_tree.heading('size', text='Размер')
        self.file_tree.heading('modified', text='Изменен')
        
        self.file_tree.column('#0', width=300)
        self.file_tree.column('name', width=300)
        self.file_tree.column('type', width=100)
        self.file_tree.column('size', width=150)
        self.file_tree.column('modified', width=150)
        
        scrollbar = ttk.Scrollbar(file_list_frame, orient=tk.VERTICAL, command=self.file_tree.yview)
        self.file_tree.configure(yscrollcommand=scrollbar.set)
        
        self.file_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        # Двойной клик для открытия
        self.file_tree.bind("<Double-1>", self.on_file_double_click)
        
        # Панель действий
        action_frame = ttk.LabelFrame(self.file_frame, text="Действия", padding=5)
        action_frame.pack(fill=tk.X, padx=5, pady=5)
        
        ttk.Button(action_frame, text="⬇ Скачать", command=self.download_file).pack(side=tk.LEFT, padx=5)
        ttk.Button(action_frame, text="⬆ Загрузить", command=self.upload_file).pack(side=tk.LEFT, padx=5)
        ttk.Button(action_frame, text="🗑 Удалить", command=self.delete_file).pack(side=tk.LEFT, padx=5)
        
        # Вкладка удалённого терминала
        self.terminal_frame = ttk.Frame(self.notebook)
        self.notebook.add(self.terminal_frame, text="💻 Терминал")
        
        # Верхняя панель терминала
        term_top = ttk.Frame(self.terminal_frame)
        term_top.pack(fill=tk.X, padx=5, pady=5)
        
        ttk.Label(term_top, text="Команда:").pack(side=tk.LEFT, padx=5)
        self.terminal_entry = ttk.Entry(term_top)
        self.terminal_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=5)
        self.terminal_entry.bind("<Return>", lambda e: self.send_terminal_command())
        
        ttk.Button(term_top, text="▶ Выполнить", command=self.send_terminal_command).pack(side=tk.LEFT, padx=5)
        
        # Окно вывода терминала
        term_output_frame = ttk.Frame(self.terminal_frame)
        term_output_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.terminal_text = tk.Text(term_output_frame, wrap="word", state="disabled", font=("Consolas", 10))
        term_scroll = ttk.Scrollbar(term_output_frame, orient=tk.VERTICAL, command=self.terminal_text.yview)
        self.terminal_text.configure(yscrollcommand=term_scroll.set)
        
        self.terminal_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        term_scroll.pack(side=tk.RIGHT, fill=tk.Y)
    
    def _send_data(self, data: bytes):
        """Отправляет данные на сервер"""
        if self.socket and self.connected:
            try:
                self.socket.sendall(struct.pack('!I', len(data)) + data)
            except:
                self.disconnect()
    
    def _send_json(self, data: dict):
        """Отправляет JSON данные"""
        json_str = json.dumps(data, ensure_ascii=False)
        self._send_data(json_str.encode('utf-8'))
    
    def _recv_exact(self, n: int, timeout=30) -> Optional[bytes]:
        """Принимает точно n байт"""
        if not self.socket:
            return None
        try:
            # Сохраняем старый таймаут
            old_timeout = self.socket.gettimeout()
            self.socket.settimeout(timeout)
            
            data = b''
            while len(data) < n:
                chunk = self.socket.recv(n - len(data))
                if not chunk:
                    self.socket.settimeout(old_timeout)
                    return None
                data += chunk
            
            self.socket.settimeout(old_timeout)
            return data
        except socket.timeout:
            return None
        except Exception:
            return None
    
    def _recv_json(self, timeout=30) -> Optional[dict]:
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
    
    def connect(self):
        """Подключение к серверу"""
        # Если уже есть подключение, сначала аккуратно разрываем его
        if self.connected:
            self.disconnect()
        
        # На всякий случай закрываем старый сокет, если он остался
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
        
        ip = self.ip_entry.get().strip()
        try:
            port = int(self.port_entry.get().strip())
        except ValueError:
            messagebox.showerror("Ошибка", "Некорректный порт")
            return
        # Обрезаем пробелы по краям, чтобы не влияли лишние пробелы/переносы
        password = self.password_entry.get().strip()
        
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.settimeout(10)  # Таймаут подключения
            self.socket.connect((ip, port))
            self.socket.settimeout(30)  # Таймаут для операций
            
            # Аутентификация
            auth_response = self._recv_json(timeout=10)
            if auth_response and auth_response.get('type') == 'auth_required':
                self._send_json({'type': 'auth', 'password': password})
                auth_result = self._recv_json(timeout=10)
                
                if auth_result and auth_result.get('type') == 'auth_success':
                    self.connected = True
                    self.connect_btn.config(state=tk.DISABLED)
                    self.disconnect_btn.config(state=tk.NORMAL)
                    self.status_label.config(text="● Подключено", foreground="green")
                    self.screen_label.config(text="Подключено...\nОжидание данных...")
                    
                    # Запускаем поток приема данных
                    self.receive_thread = threading.Thread(target=self._receive_data, daemon=True)
                    self.receive_thread.start()
                    
                    messagebox.showinfo("Успех", "Подключение установлено!")
                else:
                    messagebox.showerror("Ошибка", "Неверный пароль!")
                    try:
                        self.socket.close()
                    except:
                        pass
                    self.socket = None
            else:
                # Сервер без пароля
                self.connected = True
                self.connect_btn.config(state=tk.DISABLED)
                self.disconnect_btn.config(state=tk.NORMAL)
                self.status_label.config(text="● Подключено", foreground="green")
                self.screen_label.config(text="Подключено...\nОжидание данных...")
                
                self.receive_thread = threading.Thread(target=self._receive_data, daemon=True)
                self.receive_thread.start()
                
                messagebox.showinfo("Успех", "Подключение установлено!")
                
        except socket.timeout:
            messagebox.showerror("Ошибка", "Таймаут подключения. Проверьте IP и порт.")
            if self.socket:
                try:
                    self.socket.close()
                except:
                    pass
            self.socket = None
        except Exception as e:
            messagebox.showerror("Ошибка", f"Не удалось подключиться: {e}")
            if self.socket:
                try:
                    self.socket.close()
                except:
                    pass
            self.socket = None
    
    def disconnect(self):
        """Отключение от сервера"""
        self.connected = False
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
        self.socket = None
        self.connect_btn.config(state=tk.NORMAL)
        self.disconnect_btn.config(state=tk.DISABLED)
        self.status_label.config(text="● Отключено", foreground="red")
        self.screen_label.config(text="Не подключено\n\nЗапустите сервер и нажмите 'Подключиться'", image='')
        if hasattr(self.screen_label, 'image'):
            self.screen_label.image = None
        messagebox.showinfo("Информация", "Отключено от сервера")
    
    def _receive_data(self):
        """Поток для приема данных от сервера"""
        while self.connected and self.socket:
            try:
                header = self._recv_exact(4)
                if not header:
                    break
                
                length = struct.unpack('!I', header)[0]
                if length > 10 * 1024 * 1024:  # Максимум 10 MB
                    continue
                
                data = self._recv_exact(length)
                if not data:
                    break
                
                # Проверяем тип данных
                if data.startswith(b'SCREEN'):
                    # Скриншот
                    frame_data = data[6:]  # Убираем префикс 'SCREEN'
                    self._update_screen(frame_data)
                elif data.startswith(b'FILE_DATA'):
                    # Данные файла
                    file_data = data[9:]  # Убираем префикс 'FILE_DATA'
                    self._handle_file_data(file_data)
                elif data.startswith(b'FILE_END'):
                    # Конец файла
                    self._handle_file_end()
                else:
                    # JSON ответ
                    try:
                        response = json.loads(data.decode('utf-8'))
                        self._handle_json_response(response)
                    except:
                        pass
            except Exception as e:
                if self.connected:
                    print(f"Ошибка приема данных: {e}")
                break
        
        self.connected = False
        self.root.after(0, lambda: self._update_disconnect_status())
    
    def _update_disconnect_status(self):
        """Обновляет статус при отключении"""
        self.status_label.config(text="● Отключено", foreground="red")
        self.connect_btn.config(state=tk.NORMAL)
        self.disconnect_btn.config(state=tk.DISABLED)
        self.screen_label.config(text="Соединение разорвано\n\nПроверьте подключение и попробуйте снова", image='')
        if hasattr(self.screen_label, 'image'):
            self.screen_label.image = None
    
    def _update_screen(self, frame_data: bytes):
        """Обновляет изображение экрана"""
        try:
            if not frame_data or len(frame_data) == 0:
                return
            
            arr = np.frombuffer(frame_data, dtype=np.uint8)
            img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            
            if img is not None:
                # Конвертируем BGR в RGB
                img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                
                # Масштабируем под размер окна
                try:
                    label_width = self.screen_label.winfo_width()
                    label_height = self.screen_label.winfo_height()
                except:
                    # Окно еще не создано или уничтожено
                    return
                
                if label_width > 1 and label_height > 1:
                    img_pil = Image.fromarray(img_rgb)
                    # Сохраняем оригинальный размер для правильного расчета координат
                    self.original_img_size = img_pil.size
                    
                    # Масштабируем с сохранением пропорций
                    img_pil.thumbnail((label_width - 20, label_height - 20), Image.Resampling.LANCZOS)
                    img_tk = ImageTk.PhotoImage(img_pil)
                    
                    try:
                        self.screen_label.config(image=img_tk, text="")
                        self.screen_label.image = img_tk  # Сохраняем ссылку
                        self.displayed_img_size = img_pil.size  # Сохраняем размер отображаемого изображения
                    except:
                        # Окно уничтожено
                        pass
        except Exception as e:
            # Тихая обработка ошибок для избежания спама в консоль
            pass
    
    def _get_screen_coords(self, event) -> tuple:
        """Получает координаты на реальном экране"""
        try:
            # Используем сохраненные размеры изображений
            if self.original_img_size and self.displayed_img_size:
                orig_w, orig_h = self.original_img_size
                disp_w, disp_h = self.displayed_img_size
                
                if disp_w > 0 and disp_h > 0:
                    # Вычисляем масштаб
                    scale_x = orig_w / disp_w
                    scale_y = orig_h / disp_h
                    
                    # Координаты относительно оригинального изображения
                    x = int(event.x * scale_x)
                    y = int(event.y * scale_y)
                    
                    # Ограничиваем координаты
                    x = max(0, min(x, orig_w - 1))
                    y = max(0, min(y, orig_h - 1))
                    
                    return (x, y)
            
            # Fallback на старый метод
            img = getattr(self.screen_label, 'image', None)
            if img:
                try:
                    label_width = self.screen_label.winfo_width()
                    label_height = self.screen_label.winfo_height()
                    img_width = img.width()
                    img_height = img.height()
                    
                    if img_width > 0 and img_height > 0 and label_width > 1 and label_height > 1:
                        scale_x = label_width / img_width
                        scale_y = label_height / img_height
                        x = int(event.x / scale_x)
                        y = int(event.y / scale_y)
                        return (x, y)
                except:
                    pass
            
            return (event.x, event.y)
        except:
            return (event.x, event.y)
    
    def on_mouse_click(self, event):
        """Обработка клика мыши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({
            'type': 'mouse',
            'x': x,
            'y': y,
            'button': 'left',
            'action': 'click'
        })
    
    def on_mouse_right_click(self, event):
        """Обработка правого клика мыши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({
            'type': 'mouse',
            'x': x,
            'y': y,
            'button': 'right',
            'action': 'click'
        })
    
    def on_mouse_drag(self, event):
        """Обработка перетаскивания мыши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({
            'type': 'mouse',
            'x': x,
            'y': y,
            'button': 'left',
            'action': 'move'
        })
    
    def on_mouse_release(self, event):
        """Обработка отпускания мыши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({
            'type': 'mouse',
            'x': x,
            'y': y,
            'button': 'left',
            'action': 'up'
        })
    
    def on_mouse_wheel(self, event):
        """Обработка прокрутки колесика мыши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        delta = event.delta
        self._send_json({
            'type': 'mouse',
            'x': x,
            'y': y,
            'button': delta,
            'action': 'scroll'
        })
    
    def on_key_press(self, event):
        """Обработка нажатия клавиши"""
        if not self.connected or not getattr(self, "control_enabled_var", None) or not self.control_enabled_var.get():
            return
        key = event.char if event.char else event.keysym
        self._send_json({
            'type': 'keyboard',
            'key': key,
            'action': 'press'
        })
    
    def _handle_json_response(self, response: dict):
        """Обрабатывает JSON ответы от сервера"""
        resp_type = response.get('type')
        
        if resp_type == 'file_response':
            command = response.get('command')
            
            if command == 'list_drives':
                data = response.get('data', [])
                self.root.after(0, lambda: self._update_file_list(data))
                if not data:
                    # Если сервер не вернул ни одного диска, показываем сообщение
                    self.root.after(0, lambda: (
                        self.path_label.config(text="Диски не найдены", foreground="red"),
                        messagebox.showwarning("Диски", "Не удалось получить список дисков с сервера")
                    ))
                else:
                    self.root.after(0, lambda: self.path_label.config(
                        text="Корневой уровень - выберите диск", foreground="blue"
                    ))
            elif command == 'list_dir':
                self.root.after(0, lambda: self._update_file_list(response.get('data', [])))
                if 'path' in response:
                    self.current_path = response['path']
                    self.root.after(0, lambda: self.path_label.config(text=self.current_path, foreground="black"))
            elif command == 'download_file':
                if 'error' in response:
                    self.root.after(0, lambda: messagebox.showerror("Ошибка", response['error']))
                else:
                    # Начинаем прием файла
                    self.download_file_size = response.get('size', 0)
                    self.download_file_received = 0
                    if self.download_file_path:
                        try:
                            self.download_file_handle = open(self.download_file_path, 'wb')
                            self.file_transfer_in_progress = True
                        except Exception as e:
                            self.root.after(0, lambda: messagebox.showerror("Ошибка", f"Не удалось создать файл: {e}"))
            elif command == 'upload_file':
                if response.get('status') == 'ready':
                    # Начинаем отправку файла
                    self._send_file_data()
                elif response.get('status') == 'success':
                    self.root.after(0, lambda: messagebox.showinfo("Успех", "Файл успешно загружен!"))
                    self.file_transfer_in_progress = False
                elif 'error' in response:
                    self.root.after(0, lambda: messagebox.showerror("Ошибка", response['error']))
                    self.file_transfer_in_progress = False
        
        elif resp_type == 'terminal_response':
            # Ответ терминала
            cmd = response.get('command', '')
            output = response.get('output', '')
            error = response.get('error', '')
            code = response.get('returncode', 0)
            cwd = response.get('cwd', '')
            
            text = "\n" + "=" * 60 + "\n"
            if cwd:
                text += f"{cwd}> {cmd}\n"
            else:
                text += f"> {cmd}\n"
            
            if output:
                text += output
            if error:
                if output and not output.endswith("\n"):
                    text += "\n"
                text += error
            
            text += f"\n[Код возврата: {code}]\n"
            
            self._append_terminal_text(text)
    
    def list_drives(self):
        """Запрос списка дисков"""
        if not self.connected:
            messagebox.showwarning("Предупреждение", "Не подключено к серверу")
            return
        
        self.current_path = ""
        self.path_label.config(text="Загрузка дисков...", foreground="gray")
        self._send_json({
            'type': 'file_request',
            'command': 'list_drives'
        })
    
    def refresh_files(self):
        """Обновление списка файлов"""
        if not self.connected:
            messagebox.showwarning("Предупреждение", "Не подключено к серверу")
            return
        
        if self.current_path:
            self.path_label.config(text="Обновление...", foreground="gray")
            self._send_json({
                'type': 'file_request',
                'command': 'list_dir',
                'path': self.current_path
            })
        else:
            self.list_drives()
    
    def go_back(self):
        """Назад в истории"""
        # TODO: Реализовать историю навигации
        self.go_up()
    
    def go_up(self):
        """Переход в родительскую директорию"""
        if not self.connected:
            messagebox.showwarning("Предупреждение", "Не подключено к серверу")
            return
        
        if not self.current_path:
            self.list_drives()
            return
        
        parent = os.path.dirname(self.current_path.rstrip('\\'))
        if parent and parent != self.current_path:
            self.current_path = parent
            self.path_label.config(text="Переход...", foreground="gray")
            self._send_json({
                'type': 'file_request',
                'command': 'list_dir',
                'path': self.current_path
            })
        else:
            # Если уже в корне, показываем диски
            self.list_drives()
    
    def on_file_double_click(self, event):
        """Обработка двойного клика по файлу"""
        if not self.connected:
            return
        
        selection = self.file_tree.selection()
        if not selection:
            return
        
        item = self.file_tree.item(selection[0])
        values = item.get('values', [])
        file_path = values[0] if len(values) > 0 and values[0] else ''
        
        if not file_path:
            # Пробуем получить из текста
            text = item.get('text', '')
            # Убираем иконку если есть
            if text.startswith('📁 ') or text.startswith('📄 '):
                text = text[2:]
            file_path = os.path.join(self.current_path, text) if self.current_path else text
        
        # Получаем полный путь
        if not os.path.isabs(file_path):
            if self.current_path:
                file_path = os.path.join(self.current_path, file_path)
        
        # Проверяем тип
        file_type = values[1] if len(values) > 1 else ''
        
        if file_type == 'directory' or file_type == 'drive':
            self.current_path = file_path
            self._send_json({
                'type': 'file_request',
                'command': 'list_dir',
                'path': file_path
            })
    
    def _update_file_list(self, files: list):
        """Обновляет список файлов в дереве"""
        # Очищаем дерево
        for item in self.file_tree.get_children():
            self.file_tree.delete(item)
        
        # Добавляем файлы
        for file_info in files:
            if isinstance(file_info, dict):
                name = file_info.get('name', '')
                file_path = file_info.get('path', '')
                file_type = file_info.get('type', 'file')
                size = file_info.get('size', 0)
                modified = file_info.get('modified', 0)
                
                # Форматируем размер
                if file_type == 'file':
                    size_str = self._format_size(size)
                else:
                    size_str = '-'
                
                # Форматируем дату
                if modified:
                    try:
                        from datetime import datetime
                        mod_str = datetime.fromtimestamp(modified).strftime('%Y-%m-%d %H:%M')
                    except:
                        mod_str = '-'
                else:
                    mod_str = '-'
                
                # Иконка для типа
                icon = '📁' if file_type in ('directory', 'drive') else '📄'
                
                self.file_tree.insert('', 'end', text=f"{icon} {name}", values=(file_path, file_type, size_str, mod_str))
            else:
                # Простая строка (путь)
                self.file_tree.insert('', 'end', text=file_info, values=(file_info, 'unknown', '-', '-'))
    
    def _format_size(self, size: int) -> str:
        """Форматирует размер файла"""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024.0:
                return f"{size:.1f} {unit}"
            size /= 1024.0
        return f"{size:.1f} TB"
    
    def download_file(self):
        """Скачивание файла"""
        if not self.connected:
            messagebox.showwarning("Предупреждение", "Не подключено к серверу")
            return
        
        selection = self.file_tree.selection()
        if not selection:
            messagebox.showwarning("Предупреждение", "Выберите файл для скачивания")
            return
        
        item = self.file_tree.item(selection[0])
        values = item.get('values', [])
        file_path = values[0] if len(values) > 0 and values[0] else ''
        file_type = values[1] if len(values) > 1 else ''
        
        if file_type == 'directory' or file_type == 'drive':
            messagebox.showwarning("Предупреждение", "Выберите файл, а не папку")
            return
        
        if not file_path:
            # Пробуем получить из текста
            text = item.get('text', '')
            if text.startswith('📁 ') or text.startswith('📄 '):
                text = text[2:]
            file_path = os.path.join(self.current_path, text) if self.current_path else text
        
        if not os.path.isabs(file_path):
            if self.current_path:
                file_path = os.path.join(self.current_path, file_path)
        
        # Выбираем место сохранения
        save_path = filedialog.asksaveasfilename(
            title="Сохранить файл",
            initialfile=os.path.basename(file_path)
        )
        
        if save_path:
            self.download_file_path = save_path
            self._send_json({
                'type': 'file_request',
                'command': 'download_file',
                'path': file_path
            })
    
    def upload_file(self):
        """Загрузка файла"""
        if not self.connected:
            messagebox.showwarning("Предупреждение", "Не подключено к серверу")
            return
        
        if not self.current_path:
            messagebox.showwarning("Предупреждение", "Выберите директорию для загрузки")
            return
        
        file_path = filedialog.askopenfilename(title="Выберите файл для загрузки")
        if file_path:
            try:
                file_size = os.path.getsize(file_path)
                filename = os.path.basename(file_path)
                
                self.upload_file_path = file_path
                self.upload_file_size = file_size
                
                self._send_json({
                    'type': 'file_request',
                    'command': 'upload_file',
                    'filename': filename,
                    'save_path': self.current_path,
                    'size': file_size
                })
                self.file_transfer_in_progress = True
            except Exception as e:
                messagebox.showerror("Ошибка", f"Не удалось открыть файл: {e}")
    
    def _send_file_data(self):
        """Отправка данных файла"""
        if not self.upload_file_path:
            return
        
        try:
            with open(self.upload_file_path, 'rb') as f:
                chunk_size = 64 * 1024  # 64 KB
                while True:
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    self._send_data(b'FILE_DATA' + chunk)
            
            self._send_data(b'FILE_END')
            self.file_transfer_in_progress = False
        except Exception as e:
            messagebox.showerror("Ошибка", f"Ошибка отправки файла: {e}")
            self.file_transfer_in_progress = False
    
    def delete_file(self):
        """Удаление файла"""
        messagebox.showinfo("Информация", "Функция удаления в разработке")
    
    def _handle_file_data(self, data: bytes):
        """Обработка данных файла"""
        if self.file_transfer_in_progress and self.download_file_handle:
            try:
                self.download_file_handle.write(data)
                self.download_file_received += len(data)
                
                # Обновляем прогресс (можно добавить progressbar)
                if self.download_file_size > 0:
                    progress = (self.download_file_received / self.download_file_size) * 100
                    # print(f"Прогресс: {progress:.1f}%")
            except Exception as e:
                print(f"Ошибка записи файла: {e}")
                self.file_transfer_in_progress = False
    
    def _handle_file_end(self):
        """Обработка конца передачи файла"""
        if self.download_file_handle:
            self.download_file_handle.close()
            self.download_file_handle = None
            self.file_transfer_in_progress = False
            if self.download_file_path:
                self.root.after(0, lambda: messagebox.showinfo("Успех", f"Файл сохранен: {self.download_file_path}"))
                self.download_file_path = None
    
    def run(self):
        """Запуск клиента"""
        self.root.mainloop()


def main():
    client = RemoteClient()
    client.run()


if __name__ == '__main__':
    main()

