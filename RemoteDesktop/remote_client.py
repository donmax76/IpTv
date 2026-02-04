#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Remote Desktop Client v2.0
Современный клиент с улучшенным UI и стабильным соединением
"""

import socket
import struct
import json
import threading
import os
import time
from pathlib import Path
from typing import Optional
from datetime import datetime

try:
    import customtkinter as ctk
    from PIL import Image, ImageTk
    import cv2
    import numpy as np
except ImportError:
    print("=" * 60)
    print("ОШИБКА: Не найдены зависимости для клиента.")
    print("Установите их командой:")
    print("  pip install -r requirements.txt")
    print("=" * 60)
    raise

# Настройка темы
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")


class RemoteClient:
    """Клиент удаленного рабочего стола"""
    
    VERSION = "2.0"
    HEARTBEAT_TIMEOUT = 20  # Секунды до отключения при отсутствии heartbeat
    
    def __init__(self):
        self.socket = None
        self.connected = False
        self.receive_thread = None
        self.heartbeat_check_thread = None
        
        # Файловый менеджер
        self.current_path = ""
        self.file_transfer_in_progress = False
        self.download_file_path = None
        self.download_file_handle = None
        self.download_file_size = 0
        self.download_file_received = 0
        self.upload_file_path = None
        self.upload_file_size = 0
        
        # Экран
        self.original_img_size = None
        self.displayed_img_size = None
        self.last_heartbeat = 0
        
        # Блокировка для отправки
        self.send_lock = threading.Lock()
        
        # GUI
        self.root = ctk.CTk()
        self.root.title(f"Remote Desktop Client v{self.VERSION}")
        self.root.geometry("1400x900")
        self.root.minsize(1100, 750)
        
        # Иконка (если есть)
        try:
            self.root.iconbitmap("icon.ico")
        except:
            pass
        
        self.setup_gui()
        self.center_window()
    
    def center_window(self):
        """Центрирует окно на экране"""
        self.root.update_idletasks()
        width = self.root.winfo_width()
        height = self.root.winfo_height()
        x = (self.root.winfo_screenwidth() // 2) - (width // 2)
        y = (self.root.winfo_screenheight() // 2) - (height // 2)
        self.root.geometry(f'{width}x{height}+{x}+{y}')
    
    def setup_gui(self):
        """Настройка интерфейса"""
        # Главный контейнер
        self.main_container = ctk.CTkFrame(self.root, fg_color="transparent")
        self.main_container.pack(fill="both", expand=True, padx=15, pady=15)
        
        # === Верхняя панель подключения ===
        self.setup_connection_panel()
        
        # === Табы ===
        self.setup_tabs()
    
    def setup_connection_panel(self):
        """Панель подключения"""
        conn_frame = ctk.CTkFrame(self.main_container, corner_radius=10)
        conn_frame.pack(fill="x", pady=(0, 10))
        
        # Заголовок и статус
        header_frame = ctk.CTkFrame(conn_frame, fg_color="transparent")
        header_frame.pack(fill="x", padx=15, pady=(15, 10))
        
        title_label = ctk.CTkLabel(
            header_frame, 
            text="🖥️ Remote Desktop Client",
            font=ctk.CTkFont(size=20, weight="bold")
        )
        title_label.pack(side="left")
        
        self.status_label = ctk.CTkLabel(
            header_frame,
            text="● Отключено",
            text_color="#ff6b6b",
            font=ctk.CTkFont(size=14)
        )
        self.status_label.pack(side="right", padx=10)
        
        # Поля подключения
        fields_frame = ctk.CTkFrame(conn_frame, fg_color="transparent")
        fields_frame.pack(fill="x", padx=15, pady=10)
        
        # IP
        ip_frame = ctk.CTkFrame(fields_frame, fg_color="transparent")
        ip_frame.pack(side="left", padx=(0, 15))
        
        ctk.CTkLabel(ip_frame, text="IP адрес:", font=ctk.CTkFont(size=13)).pack(anchor="w")
        self.ip_entry = ctk.CTkEntry(ip_frame, width=180, height=35, placeholder_text="192.168.1.100")
        self.ip_entry.insert(0, "127.0.0.1")
        self.ip_entry.pack()
        
        # Порт
        port_frame = ctk.CTkFrame(fields_frame, fg_color="transparent")
        port_frame.pack(side="left", padx=(0, 15))
        
        ctk.CTkLabel(port_frame, text="Порт:", font=ctk.CTkFont(size=13)).pack(anchor="w")
        self.port_entry = ctk.CTkEntry(port_frame, width=100, height=35, placeholder_text="5900")
        self.port_entry.insert(0, "5900")
        self.port_entry.pack()
        
        # Пароль
        pass_frame = ctk.CTkFrame(fields_frame, fg_color="transparent")
        pass_frame.pack(side="left", padx=(0, 15))
        
        ctk.CTkLabel(pass_frame, text="Пароль:", font=ctk.CTkFont(size=13)).pack(anchor="w")
        self.password_entry = ctk.CTkEntry(pass_frame, width=200, height=35, show="•", placeholder_text="Оставьте пустым если не требуется")
        self.password_entry.pack()
        
        # Чекбокс показать пароль
        self.show_password_var = ctk.BooleanVar(value=False)
        show_pass_cb = ctk.CTkCheckBox(
            pass_frame,
            text="Показать",
            variable=self.show_password_var,
            command=self._toggle_password,
            width=80,
            height=20
        )
        show_pass_cb.pack(anchor="w", pady=(5, 0))
        
        # Кнопки
        btn_frame = ctk.CTkFrame(fields_frame, fg_color="transparent")
        btn_frame.pack(side="left", padx=15)
        
        ctk.CTkLabel(btn_frame, text=" ", font=ctk.CTkFont(size=13)).pack()  # Spacer
        
        self.connect_btn = ctk.CTkButton(
            btn_frame,
            text="🔌 Подключиться",
            command=self.connect,
            width=140,
            height=35,
            fg_color="#2ecc71",
            hover_color="#27ae60"
        )
        self.connect_btn.pack(side="left", padx=(0, 10))
        
        self.disconnect_btn = ctk.CTkButton(
            btn_frame,
            text="❌ Отключиться",
            command=self.disconnect,
            width=140,
            height=35,
            fg_color="#e74c3c",
            hover_color="#c0392b",
            state="disabled"
        )
        self.disconnect_btn.pack(side="left")
        
        # Быстрые кнопки
        quick_frame = ctk.CTkFrame(conn_frame, fg_color="transparent")
        quick_frame.pack(fill="x", padx=15, pady=(0, 15))
        
        ctk.CTkButton(
            quick_frame,
            text="localhost",
            command=lambda: self._set_connection("127.0.0.1", "5900"),
            width=100,
            height=28,
            fg_color="#3498db",
            hover_color="#2980b9"
        ).pack(side="left", padx=(0, 10))
        
        ctk.CTkButton(
            quick_frame,
            text="🔍 Найти IP",
            command=self._find_local_ip,
            width=100,
            height=28,
            fg_color="#9b59b6",
            hover_color="#8e44ad"
        ).pack(side="left", padx=(0, 10))
        
        # Переключатель управления
        self.control_enabled_var = ctk.BooleanVar(value=True)
        control_cb = ctk.CTkCheckBox(
            quick_frame,
            text="🎮 Разрешить управление",
            variable=self.control_enabled_var,
            width=180
        )
        control_cb.pack(side="right")
    
    def setup_tabs(self):
        """Настройка вкладок"""
        self.tabview = ctk.CTkTabview(self.main_container, corner_radius=10)
        self.tabview.pack(fill="both", expand=True)
        
        # Вкладки
        self.tab_screen = self.tabview.add("🖥️ Рабочий стол")
        self.tab_files = self.tabview.add("📁 Файлы")
        self.tab_terminal = self.tabview.add("💻 Терминал")
        
        self.setup_screen_tab()
        self.setup_files_tab()
        self.setup_terminal_tab()
    
    def setup_screen_tab(self):
        """Вкладка рабочего стола"""
        # Подсказка
        hint_label = ctk.CTkLabel(
            self.tab_screen,
            text="💡 Кликните на экране для управления мышью. Используйте клавиатуру для ввода.",
            text_color="gray",
            font=ctk.CTkFont(size=12)
        )
        hint_label.pack(pady=(5, 10))
        
        # Контейнер для экрана
        screen_container = ctk.CTkFrame(self.tab_screen, corner_radius=10, fg_color="#1a1a2e")
        screen_container.pack(fill="both", expand=True, padx=5, pady=5)
        
        self.screen_label = ctk.CTkLabel(
            screen_container,
            text="Не подключено\n\n🔌 Запустите сервер и нажмите 'Подключиться'",
            font=ctk.CTkFont(size=16),
            text_color="#666"
        )
        self.screen_label.pack(expand=True, fill="both", padx=10, pady=10)
        
        # Привязка событий
        self.screen_label.bind("<Button-1>", self.on_mouse_click)
        self.screen_label.bind("<Button-3>", self.on_mouse_right_click)
        self.screen_label.bind("<B1-Motion>", self.on_mouse_drag)
        self.screen_label.bind("<ButtonRelease-1>", self.on_mouse_release)
        self.screen_label.bind("<MouseWheel>", self.on_mouse_wheel)
        self.screen_label.bind("<Double-Button-1>", self.on_mouse_double_click)
        
        # Привязка клавиатуры к корневому окну
        self.root.bind("<KeyPress>", self.on_key_press)
    
    def setup_files_tab(self):
        """Вкладка файлового менеджера"""
        # Панель навигации
        nav_frame = ctk.CTkFrame(self.tab_files, corner_radius=10)
        nav_frame.pack(fill="x", padx=5, pady=5)
        
        # Кнопки навигации
        btn_nav = ctk.CTkFrame(nav_frame, fg_color="transparent")
        btn_nav.pack(fill="x", padx=10, pady=10)
        
        ctk.CTkButton(btn_nav, text="🔄 Обновить", command=self.refresh_files, width=100).pack(side="left", padx=2)
        ctk.CTkButton(btn_nav, text="⬆️ Вверх", command=self.go_up, width=80).pack(side="left", padx=2)
        ctk.CTkButton(btn_nav, text="💾 Диски", command=self.list_drives, width=80).pack(side="left", padx=2)
        
        # Путь
        self.path_label = ctk.CTkLabel(
            btn_nav,
            text="📂 Не подключено",
            font=ctk.CTkFont(size=13),
            text_color="#888"
        )
        self.path_label.pack(side="left", padx=20)
        
        # Список файлов
        list_frame = ctk.CTkFrame(self.tab_files, corner_radius=10)
        list_frame.pack(fill="both", expand=True, padx=5, pady=5)
        
        # Используем Treeview из tkinter (CTk не имеет собственного)
        import tkinter.ttk as ttk
        
        # Стиль для темной темы
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Dark.Treeview",
                       background="#2b2b2b",
                       foreground="white",
                       fieldbackground="#2b2b2b",
                       rowheight=30)
        style.configure("Dark.Treeview.Heading",
                       background="#3d3d3d",
                       foreground="white",
                       font=('Arial', 11, 'bold'))
        style.map("Dark.Treeview",
                 background=[('selected', '#3498db')],
                 foreground=[('selected', 'white')])
        
        columns = ('name', 'type', 'size', 'modified')
        self.file_tree = ttk.Treeview(
            list_frame,
            columns=columns,
            show='headings',
            style="Dark.Treeview"
        )
        
        self.file_tree.heading('name', text='📄 Имя')
        self.file_tree.heading('type', text='Тип')
        self.file_tree.heading('size', text='Размер')
        self.file_tree.heading('modified', text='Изменен')
        
        self.file_tree.column('name', width=400)
        self.file_tree.column('type', width=100)
        self.file_tree.column('size', width=120)
        self.file_tree.column('modified', width=150)
        
        scrollbar = ttk.Scrollbar(list_frame, orient="vertical", command=self.file_tree.yview)
        self.file_tree.configure(yscrollcommand=scrollbar.set)
        
        self.file_tree.pack(side="left", fill="both", expand=True, padx=(10, 0), pady=10)
        scrollbar.pack(side="right", fill="y", padx=(0, 10), pady=10)
        
        self.file_tree.bind("<Double-1>", self.on_file_double_click)
        
        # Панель действий
        action_frame = ctk.CTkFrame(self.tab_files, corner_radius=10)
        action_frame.pack(fill="x", padx=5, pady=5)
        
        action_btn_frame = ctk.CTkFrame(action_frame, fg_color="transparent")
        action_btn_frame.pack(pady=10)
        
        ctk.CTkButton(
            action_btn_frame,
            text="⬇️ Скачать",
            command=self.download_file,
            width=120,
            fg_color="#2ecc71",
            hover_color="#27ae60"
        ).pack(side="left", padx=10)
        
        ctk.CTkButton(
            action_btn_frame,
            text="⬆️ Загрузить",
            command=self.upload_file,
            width=120,
            fg_color="#3498db",
            hover_color="#2980b9"
        ).pack(side="left", padx=10)
        
        ctk.CTkButton(
            action_btn_frame,
            text="🗑️ Удалить",
            command=self.delete_file,
            width=120,
            fg_color="#e74c3c",
            hover_color="#c0392b"
        ).pack(side="left", padx=10)
    
    def setup_terminal_tab(self):
        """Вкладка терминала"""
        # Поле ввода команды
        input_frame = ctk.CTkFrame(self.tab_terminal, corner_radius=10)
        input_frame.pack(fill="x", padx=5, pady=5)
        
        input_inner = ctk.CTkFrame(input_frame, fg_color="transparent")
        input_inner.pack(fill="x", padx=10, pady=10)
        
        ctk.CTkLabel(input_inner, text="Команда:", font=ctk.CTkFont(size=13)).pack(side="left", padx=(0, 10))
        
        self.terminal_entry = ctk.CTkEntry(
            input_inner,
            placeholder_text="Введите команду (например: dir, ipconfig, whoami)",
            height=35
        )
        self.terminal_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))
        self.terminal_entry.bind("<Return>", lambda e: self.send_terminal_command())
        
        ctk.CTkButton(
            input_inner,
            text="▶️ Выполнить",
            command=self.send_terminal_command,
            width=120,
            height=35,
            fg_color="#2ecc71",
            hover_color="#27ae60"
        ).pack(side="left")
        
        # Вывод терминала
        output_frame = ctk.CTkFrame(self.tab_terminal, corner_radius=10)
        output_frame.pack(fill="both", expand=True, padx=5, pady=5)
        
        self.terminal_text = ctk.CTkTextbox(
            output_frame,
            font=ctk.CTkFont(family="Consolas", size=12),
            fg_color="#1a1a2e",
            text_color="#00ff00",
            corner_radius=10
        )
        self.terminal_text.pack(fill="both", expand=True, padx=10, pady=10)
        self.terminal_text.insert("end", "🖥️ Удаленный терминал готов к работе\n")
        self.terminal_text.insert("end", "━" * 50 + "\n")
        self.terminal_text.configure(state="disabled")
    
    # === Вспомогательные методы ===
    
    def _toggle_password(self):
        """Переключает видимость пароля"""
        if self.show_password_var.get():
            self.password_entry.configure(show="")
        else:
            self.password_entry.configure(show="•")
    
    def _set_connection(self, ip: str, port: str):
        """Устанавливает параметры подключения"""
        self.ip_entry.delete(0, "end")
        self.ip_entry.insert(0, ip)
        self.port_entry.delete(0, "end")
        self.port_entry.insert(0, port)
    
    def _find_local_ip(self):
        """Находит локальный IP"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            self.ip_entry.delete(0, "end")
            self.ip_entry.insert(0, ip)
            self._show_message("IP найден", f"Локальный IP: {ip}", "info")
        except Exception as e:
            self._show_message("Ошибка", f"Не удалось определить IP: {e}", "error")
    
    def _show_message(self, title: str, message: str, msg_type: str = "info"):
        """Показывает сообщение"""
        from tkinter import messagebox
        if msg_type == "error":
            messagebox.showerror(title, message)
        elif msg_type == "warning":
            messagebox.showwarning(title, message)
        else:
            messagebox.showinfo(title, message)
    
    def _append_terminal(self, text: str):
        """Добавляет текст в терминал"""
        self.terminal_text.configure(state="normal")
        self.terminal_text.insert("end", text)
        self.terminal_text.see("end")
        self.terminal_text.configure(state="disabled")
    
    # === Сетевые методы ===
    
    def _send_data(self, data: bytes) -> bool:
        """Отправляет данные на сервер"""
        if not self.socket or not self.connected:
            return False
        try:
            with self.send_lock:
                self.socket.sendall(struct.pack('!I', len(data)) + data)
            return True
        except:
            return False
    
    def _send_json(self, data: dict) -> bool:
        """Отправляет JSON данные"""
        json_str = json.dumps(data, ensure_ascii=False)
        return self._send_data(json_str.encode('utf-8'))
    
    def _recv_exact(self, n: int, timeout=30) -> Optional[bytes]:
        """Принимает точно n байт"""
        if not self.socket:
            return None
        try:
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
        if length > 10 * 1024 * 1024:
            return None
        data = self._recv_exact(length, timeout)
        if not data:
            return None
        try:
            return json.loads(data.decode('utf-8'))
        except:
            return None
    
    # === Подключение ===
    
    def connect(self):
        """Подключение к серверу"""
        if self.connected:
            self.disconnect()
        
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
            self._show_message("Ошибка", "Некорректный порт", "error")
            return
        
        password = self.password_entry.get().strip()
        
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.settimeout(10)
            self.socket.connect((ip, port))
            self.socket.settimeout(30)
            
            # Аутентификация
            auth_response = self._recv_json(timeout=10)
            
            if auth_response:
                auth_type = auth_response.get('type')
                
                if auth_type == 'auth_required':
                    self._send_json({'type': 'auth', 'password': password})
                    auth_result = self._recv_json(timeout=10)
                    
                    if auth_result and auth_result.get('type') == 'auth_success':
                        self._connection_success()
                    else:
                        self._show_message("Ошибка", "Неверный пароль!", "error")
                        self._close_socket()
                        return
                        
                elif auth_type == 'auth_not_required':
                    self._connection_success()
                else:
                    self._connection_success()
            else:
                self._show_message("Ошибка", "Сервер не отвечает", "error")
                self._close_socket()
                
        except socket.timeout:
            self._show_message("Ошибка", "Таймаут подключения. Проверьте IP и порт.", "error")
            self._close_socket()
        except ConnectionRefusedError:
            self._show_message("Ошибка", "Сервер недоступен. Убедитесь, что сервер запущен.", "error")
            self._close_socket()
        except Exception as e:
            self._show_message("Ошибка", f"Не удалось подключиться: {e}", "error")
            self._close_socket()
    
    def _connection_success(self):
        """Успешное подключение"""
        self.connected = True
        self.last_heartbeat = time.time()
        
        self.connect_btn.configure(state="disabled")
        self.disconnect_btn.configure(state="normal")
        self.status_label.configure(text="● Подключено", text_color="#2ecc71")
        self.screen_label.configure(text="Подключено...\n\n⏳ Ожидание данных с сервера...")
        
        # Запускаем потоки
        self.receive_thread = threading.Thread(target=self._receive_data, daemon=True)
        self.receive_thread.start()
        
        self.heartbeat_check_thread = threading.Thread(target=self._check_heartbeat, daemon=True)
        self.heartbeat_check_thread.start()
        
        self._show_message("Успех", "Подключение установлено!", "info")
    
    def _close_socket(self):
        """Закрывает сокет"""
        if self.socket:
            try:
                self.socket.close()
            except:
                pass
            self.socket = None
    
    def disconnect(self):
        """Отключение от сервера"""
        was_connected = self.connected
        self.connected = False
        self._close_socket()
        
        self.connect_btn.configure(state="normal")
        self.disconnect_btn.configure(state="disabled")
        self.status_label.configure(text="● Отключено", text_color="#ff6b6b")
        self.screen_label.configure(
            text="Не подключено\n\n🔌 Запустите сервер и нажмите 'Подключиться'",
            image=None
        )
        
        if was_connected:
            self._show_message("Информация", "Отключено от сервера", "info")
    
    def _check_heartbeat(self):
        """Проверяет heartbeat"""
        while self.connected:
            try:
                if self.last_heartbeat > 0:
                    elapsed = time.time() - self.last_heartbeat
                    if elapsed > self.HEARTBEAT_TIMEOUT:
                        print(f"Heartbeat таймаут: {elapsed:.1f}s")
                        self.root.after(0, self._handle_disconnect)
                        break
                time.sleep(2)
            except:
                break
    
    def _handle_disconnect(self):
        """Обрабатывает отключение"""
        if self.connected:
            self.connected = False
            self._close_socket()
            
            self.connect_btn.configure(state="normal")
            self.disconnect_btn.configure(state="disabled")
            self.status_label.configure(text="● Отключено", text_color="#ff6b6b")
            self.screen_label.configure(
                text="⚠️ Соединение потеряно\n\nПроверьте сервер и попробуйте снова",
                image=None
            )
    
    def _receive_data(self):
        """Поток приема данных"""
        while self.connected and self.socket:
            try:
                header = self._recv_exact(4, timeout=30)
                if not header:
                    break
                
                length = struct.unpack('!I', header)[0]
                if length > 10 * 1024 * 1024:
                    continue
                
                data = self._recv_exact(length, timeout=30)
                if not data:
                    break
                
                # Обработка данных
                if data.startswith(b'SCREEN'):
                    frame_data = data[6:]
                    self.root.after(0, lambda d=frame_data: self._update_screen(d))
                elif data.startswith(b'FILE_DATA'):
                    file_data = data[9:]
                    self._handle_file_data(file_data)
                elif data.startswith(b'FILE_END'):
                    self.root.after(0, self._handle_file_end)
                else:
                    try:
                        response = json.loads(data.decode('utf-8'))
                        self.root.after(0, lambda r=response: self._handle_json_response(r))
                    except:
                        pass
            except Exception as e:
                if self.connected:
                    print(f"Ошибка приема: {e}")
                break
        
        self.root.after(0, self._handle_disconnect)
    
    def _update_screen(self, frame_data: bytes):
        """Обновляет экран"""
        try:
            if not frame_data:
                return
            
            arr = np.frombuffer(frame_data, dtype=np.uint8)
            img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            
            if img is not None:
                img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                
                try:
                    label_width = self.screen_label.winfo_width()
                    label_height = self.screen_label.winfo_height()
                except:
                    return
                
                if label_width > 1 and label_height > 1:
                    img_pil = Image.fromarray(img_rgb)
                    self.original_img_size = img_pil.size
                    
                    # Масштабируем
                    img_pil.thumbnail((label_width - 20, label_height - 20), Image.Resampling.LANCZOS)
                    self.displayed_img_size = img_pil.size
                    
                    img_tk = ImageTk.PhotoImage(img_pil)
                    
                    try:
                        self.screen_label.configure(image=img_tk, text="")
                        self.screen_label.image = img_tk
                    except:
                        pass
        except:
            pass
    
    def _get_screen_coords(self, event) -> tuple:
        """Получает координаты на реальном экране"""
        try:
            if self.original_img_size and self.displayed_img_size:
                orig_w, orig_h = self.original_img_size
                disp_w, disp_h = self.displayed_img_size
                
                if disp_w > 0 and disp_h > 0:
                    # Центрирование изображения
                    label_w = self.screen_label.winfo_width()
                    label_h = self.screen_label.winfo_height()
                    
                    offset_x = (label_w - disp_w) // 2
                    offset_y = (label_h - disp_h) // 2
                    
                    # Координаты относительно изображения
                    rel_x = event.x - offset_x
                    rel_y = event.y - offset_y
                    
                    if 0 <= rel_x <= disp_w and 0 <= rel_y <= disp_h:
                        scale_x = orig_w / disp_w
                        scale_y = orig_h / disp_h
                        
                        x = int(rel_x * scale_x)
                        y = int(rel_y * scale_y)
                        
                        x = max(0, min(x, orig_w - 1))
                        y = max(0, min(y, orig_h - 1))
                        
                        return (x, y)
            
            return (event.x, event.y)
        except:
            return (event.x, event.y)
    
    # === Обработчики событий ===
    
    def on_mouse_click(self, event):
        """Клик мыши"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': 'left', 'action': 'click'})
    
    def on_mouse_right_click(self, event):
        """Правый клик"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': 'right', 'action': 'click'})
    
    def on_mouse_double_click(self, event):
        """Двойной клик"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': 'left', 'action': 'double'})
    
    def on_mouse_drag(self, event):
        """Перетаскивание"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': 'left', 'action': 'move'})
    
    def on_mouse_release(self, event):
        """Отпускание мыши"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': 'left', 'action': 'up'})
    
    def on_mouse_wheel(self, event):
        """Прокрутка"""
        if not self.connected or not self.control_enabled_var.get():
            return
        x, y = self._get_screen_coords(event)
        self._send_json({'type': 'mouse', 'x': x, 'y': y, 'button': str(event.delta), 'action': 'scroll'})
    
    def on_key_press(self, event):
        """Нажатие клавиши"""
        if not self.connected or not self.control_enabled_var.get():
            return
        
        # Проверяем, что фокус на вкладке рабочего стола
        if self.tabview.get() != "🖥️ Рабочий стол":
            return
        
        # Игнорируем если фокус на поле ввода
        focused = self.root.focus_get()
        if isinstance(focused, (ctk.CTkEntry, ctk.CTkTextbox)):
            return
        
        key = event.char if event.char else event.keysym
        self._send_json({'type': 'keyboard', 'key': key, 'action': 'press'})
    
    def _handle_json_response(self, response: dict):
        """Обрабатывает JSON ответы"""
        resp_type = response.get('type')
        
        if resp_type == 'heartbeat':
            self.last_heartbeat = time.time()
            self._send_json({'type': 'heartbeat_response', 'time': time.time()})
        
        elif resp_type == 'file_response':
            command = response.get('command')
            
            if command == 'list_drives':
                data = response.get('data', [])
                self._update_file_list(data)
                self.path_label.configure(text="📂 Выберите диск", text_color="#888")
            
            elif command == 'list_dir':
                data = response.get('data', [])
                self._update_file_list(data)
                if 'path' in response:
                    self.current_path = response['path']
                    self.path_label.configure(text=f"📂 {self.current_path}", text_color="#fff")
            
            elif command == 'download_file':
                if 'error' in response:
                    self._show_message("Ошибка", response['error'], "error")
                else:
                    self.download_file_size = response.get('size', 0)
                    self.download_file_received = 0
                    if self.download_file_path:
                        try:
                            self.download_file_handle = open(self.download_file_path, 'wb')
                            self.file_transfer_in_progress = True
                        except Exception as e:
                            self._show_message("Ошибка", f"Не удалось создать файл: {e}", "error")
            
            elif command == 'upload_file':
                if response.get('status') == 'ready':
                    threading.Thread(target=self._send_file_data, daemon=True).start()
                elif response.get('status') == 'success':
                    self._show_message("Успех", "Файл успешно загружен!", "info")
                    self.file_transfer_in_progress = False
                    self.refresh_files()
                elif 'error' in response:
                    self._show_message("Ошибка", response['error'], "error")
                    self.file_transfer_in_progress = False
            
            elif command == 'delete':
                if response.get('status') == 'success':
                    self._show_message("Успех", "Удалено!", "info")
                    self.refresh_files()
                elif 'error' in response:
                    self._show_message("Ошибка", response['error'], "error")
        
        elif resp_type == 'terminal_response':
            cmd = response.get('command', '')
            output = response.get('output', '')
            error = response.get('error', '')
            code = response.get('returncode', 0)
            
            text = f"\n{'━' * 50}\n"
            text += f"$ {cmd}\n"
            if output:
                text += output
            if error:
                text += f"\n⚠️ {error}"
            text += f"\n[Код: {code}]\n"
            
            self._append_terminal(text)
    
    # === Файловый менеджер ===
    
    def list_drives(self):
        """Запрос списка дисков"""
        if not self.connected:
            self._show_message("Предупреждение", "Не подключено", "warning")
            return
        
        self.current_path = ""
        self._send_json({'type': 'file_request', 'command': 'list_drives'})
    
    def refresh_files(self):
        """Обновление списка файлов"""
        if not self.connected:
            return
        
        if self.current_path:
            self._send_json({'type': 'file_request', 'command': 'list_dir', 'path': self.current_path})
        else:
            self.list_drives()
    
    def go_up(self):
        """Переход вверх"""
        if not self.connected:
            return
        
        if not self.current_path:
            self.list_drives()
            return
        
        parent = os.path.dirname(self.current_path.rstrip('\\'))
        if parent and parent != self.current_path:
            self.current_path = parent
            self._send_json({'type': 'file_request', 'command': 'list_dir', 'path': self.current_path})
        else:
            self.list_drives()
    
    def on_file_double_click(self, event):
        """Двойной клик по файлу"""
        if not self.connected:
            return
        
        selection = self.file_tree.selection()
        if not selection:
            return
        
        item = self.file_tree.item(selection[0])
        values = item.get('values', [])
        
        if len(values) >= 2:
            file_path = values[0]
            file_type = values[1]
            
            if file_type in ('directory', 'drive'):
                self.current_path = file_path
                self._send_json({'type': 'file_request', 'command': 'list_dir', 'path': file_path})
    
    def _update_file_list(self, files: list):
        """Обновляет список файлов"""
        for item in self.file_tree.get_children():
            self.file_tree.delete(item)
        
        for file_info in files:
            if isinstance(file_info, dict):
                name = file_info.get('name', '')
                file_path = file_info.get('path', '')
                file_type = file_info.get('type', 'file')
                size = file_info.get('size', 0)
                modified = file_info.get('modified', 0)
                
                # Иконка
                icon = '📁' if file_type in ('directory', 'drive') else '📄'
                display_name = f"{icon} {name}"
                
                # Размер
                size_str = self._format_size(size) if file_type == 'file' else '-'
                
                # Дата
                if modified:
                    try:
                        mod_str = datetime.fromtimestamp(modified).strftime('%Y-%m-%d %H:%M')
                    except:
                        mod_str = '-'
                else:
                    mod_str = '-'
                
                self.file_tree.insert('', 'end', values=(file_path, file_type, size_str, mod_str), text=display_name)
    
    def _format_size(self, size: int) -> str:
        """Форматирует размер"""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024.0:
                return f"{size:.1f} {unit}"
            size /= 1024.0
        return f"{size:.1f} TB"
    
    def download_file(self):
        """Скачивание файла"""
        if not self.connected:
            self._show_message("Предупреждение", "Не подключено", "warning")
            return
        
        selection = self.file_tree.selection()
        if not selection:
            self._show_message("Предупреждение", "Выберите файл", "warning")
            return
        
        item = self.file_tree.item(selection[0])
        values = item.get('values', [])
        
        if len(values) >= 2:
            file_path = values[0]
            file_type = values[1]
            
            if file_type in ('directory', 'drive'):
                self._show_message("Предупреждение", "Выберите файл, а не папку", "warning")
                return
            
            from tkinter import filedialog
            save_path = filedialog.asksaveasfilename(
                title="Сохранить файл",
                initialfile=os.path.basename(file_path)
            )
            
            if save_path:
                self.download_file_path = save_path
                self._send_json({'type': 'file_request', 'command': 'download_file', 'path': file_path})
    
    def upload_file(self):
        """Загрузка файла"""
        if not self.connected:
            self._show_message("Предупреждение", "Не подключено", "warning")
            return
        
        if not self.current_path:
            self._show_message("Предупреждение", "Выберите папку для загрузки", "warning")
            return
        
        from tkinter import filedialog
        file_path = filedialog.askopenfilename(title="Выберите файл")
        
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
                self._show_message("Ошибка", f"Не удалось открыть файл: {e}", "error")
    
    def _send_file_data(self):
        """Отправка файла"""
        if not self.upload_file_path:
            return
        
        try:
            with open(self.upload_file_path, 'rb') as f:
                chunk_size = 64 * 1024
                while True:
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    self._send_data(b'FILE_DATA' + chunk)
            
            self._send_data(b'FILE_END')
            self.file_transfer_in_progress = False
        except Exception as e:
            self.root.after(0, lambda: self._show_message("Ошибка", f"Ошибка отправки: {e}", "error"))
            self.file_transfer_in_progress = False
    
    def delete_file(self):
        """Удаление файла"""
        if not self.connected:
            self._show_message("Предупреждение", "Не подключено", "warning")
            return
        
        selection = self.file_tree.selection()
        if not selection:
            self._show_message("Предупреждение", "Выберите файл или папку", "warning")
            return
        
        item = self.file_tree.item(selection[0])
        values = item.get('values', [])
        
        if len(values) >= 1:
            file_path = values[0]
            
            from tkinter import messagebox
            if messagebox.askyesno("Подтверждение", f"Удалить {os.path.basename(file_path)}?"):
                self._send_json({'type': 'file_request', 'command': 'delete', 'path': file_path})
    
    def _handle_file_data(self, data: bytes):
        """Обработка данных файла"""
        if self.file_transfer_in_progress and self.download_file_handle:
            try:
                self.download_file_handle.write(data)
                self.download_file_received += len(data)
            except:
                self.file_transfer_in_progress = False
    
    def _handle_file_end(self):
        """Конец передачи файла"""
        if self.download_file_handle:
            self.download_file_handle.close()
            self.download_file_handle = None
            self.file_transfer_in_progress = False
            if self.download_file_path:
                self._show_message("Успех", f"Файл сохранен: {self.download_file_path}", "info")
                self.download_file_path = None
    
    def send_terminal_command(self):
        """Отправляет команду терминала"""
        if not self.connected:
            self._show_message("Предупреждение", "Не подключено", "warning")
            return
        
        cmd = self.terminal_entry.get().strip()
        if not cmd:
            return
        
        self._append_terminal(f"\n> {cmd}\n")
        
        self._send_json({
            'type': 'file_request',
            'command': 'run_command',
            'command_str': cmd,
            'cwd': self.current_path or ""
        })
        
        self.terminal_entry.delete(0, "end")
    
    def run(self):
        """Запуск клиента"""
        self.root.mainloop()


def main():
    client = RemoteClient()
    client.run()


if __name__ == '__main__':
    main()
