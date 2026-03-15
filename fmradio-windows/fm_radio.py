#!/usr/bin/env python3
"""
FM Radio Application for Windows 10 with RTL-SDR support.
Auto-scans FM band (87.5-108.0 MHz) and saves stations.

Requirements:
    pip install pyrtlsdr numpy scipy sounddevice

RTL-SDR driver:
    Install Zadig (https://zadig.akeo.ie/) and replace the RTL-SDR driver
    with WinUSB driver, or install rtl-sdr Windows drivers.
"""

import json
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
from pathlib import Path

import numpy as np

try:
    from scipy.signal import firwin, lfilter, decimate
except (ImportError, ModuleNotFoundError):
    # Pure-numpy fallback when scipy is incomplete (e.g. missing scipy.spatial)
    def firwin(numtaps, cutoff, fs=None):
        """FIR lowpass filter design via windowed sinc."""
        nyq = fs / 2.0
        fc = cutoff / nyq
        half = (numtaps - 1) / 2.0
        n = np.arange(numtaps)
        h = np.sinc(2 * fc * (n - half)) * 2 * fc
        # Hamming window
        h *= 0.54 - 0.46 * np.cos(2 * np.pi * n / (numtaps - 1))
        h /= np.sum(h)
        return h

    def lfilter(b, a, x, zi=None):
        """FIR filter (a=1) using numpy convolution with state."""
        M = len(b)
        N = len(x)
        x = np.asarray(x, dtype=np.float64)
        b = np.asarray(b, dtype=np.float64)
        y = np.convolve(x, b)[:N]
        if zi is not None:
            zi = np.asarray(zi, dtype=np.float64)
            n_zi = min(M - 1, N)
            y[:n_zi] += zi[:n_zi]
        # Compute output filter state
        zf = np.zeros(M - 1)
        x_rev = x[::-1]
        for j in range(M - 1):
            valid = min(M - 1 - j, N)
            zf[j] = np.dot(b[j + 1:j + 1 + valid], x_rev[:valid])
        if zi is not None:
            for j in range(M - 1):
                if N + j < M - 1:
                    zf[j] += zi[N + j]
            return y, zf
        return y, zf

    def decimate(x, q, ftype='fir'):
        """Downsample signal after anti-aliasing FIR filter."""
        n = 30 * q + 1
        b = firwin(n, 1.0 / q, fs=2.0)
        filtered = np.convolve(x, b, mode='same')
        return filtered[::q]

import sounddevice as sd

try:
    from rtlsdr import RtlSdr
except ImportError:
    print("ERROR: pyrtlsdr not installed. Run: pip install pyrtlsdr")
    sys.exit(1)


# --- Constants ---
FM_BAND_START = 87.5e6  # 87.5 MHz
FM_BAND_END = 108.0e6   # 108.0 MHz
FM_STEP = 100e3          # 100 kHz
SAMPLE_RATE = 1.024e6    # 1.024 MHz
AUDIO_RATE = 48000        # 48 kHz audio output
STATIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fm_stations.json")


class FmDemodulator:
    """FM demodulation: IQ -> audio PCM."""

    def __init__(self, sample_rate=SAMPLE_RATE, audio_rate=AUDIO_RATE):
        self.sample_rate = sample_rate
        self.audio_rate = audio_rate
        self.decimation = int(sample_rate / audio_rate)

        # IF low-pass filter (100 kHz cutoff)
        self.if_filter = firwin(64, 100e3, fs=sample_rate)
        self.if_state_i = np.zeros(len(self.if_filter) - 1)
        self.if_state_q = np.zeros(len(self.if_filter) - 1)

        # Audio low-pass filter (15 kHz cutoff)
        self.audio_filter = firwin(32, 15e3, fs=audio_rate)
        self.audio_state = np.zeros(len(self.audio_filter) - 1)

        # De-emphasis (75us time constant)
        tau = 75e-6
        dt = 1.0 / audio_rate
        self.deemph_alpha = dt / (tau + dt)
        self.deemph_state = 0.0

        # Previous sample for FM discriminator
        self.prev_sample = 0 + 0j

    def demodulate(self, iq_samples):
        """Demodulate IQ samples to audio."""
        if len(iq_samples) == 0:
            return np.array([], dtype=np.float32)

        # IF low-pass filter (I and Q separately)
        i_filtered, self.if_state_i = lfilter(
            self.if_filter, 1, iq_samples.real, zi=self.if_state_i
        )
        q_filtered, self.if_state_q = lfilter(
            self.if_filter, 1, iq_samples.imag, zi=self.if_state_q
        )
        filtered = i_filtered + 1j * q_filtered

        # FM discriminator (angle of conjugate product)
        shifted = filtered[1:] * np.conj(filtered[:-1])
        # Prepend using previous sample
        first = filtered[0] * np.conj(self.prev_sample)
        self.prev_sample = filtered[-1]
        discriminated = np.angle(np.concatenate([[first], shifted]))

        # Decimate to audio rate
        audio = decimate(discriminated, self.decimation, ftype='fir')

        # De-emphasis filter
        for i in range(len(audio)):
            self.deemph_state += self.deemph_alpha * (audio[i] - self.deemph_state)
            audio[i] = self.deemph_state

        # Audio low-pass filter
        audio, self.audio_state = lfilter(
            self.audio_filter, 1, audio, zi=self.audio_state
        )

        # Normalize
        audio = audio / (np.max(np.abs(audio)) + 1e-10) * 0.8

        return audio.astype(np.float32)

    def measure_power(self, iq_samples):
        """Measure signal power in dB."""
        if len(iq_samples) == 0:
            return -100.0
        power = np.mean(np.abs(iq_samples) ** 2)
        return 10 * np.log10(power + 1e-10)

    def reset(self):
        self.if_state_i = np.zeros(len(self.if_filter) - 1)
        self.if_state_q = np.zeros(len(self.if_filter) - 1)
        self.audio_state = np.zeros(len(self.audio_filter) - 1)
        self.deemph_state = 0.0
        self.prev_sample = 0 + 0j


class StationStorage:
    """Save/load radio stations to JSON file."""

    def __init__(self, filepath=STATIONS_FILE):
        self.filepath = filepath
        self.stations = []
        self.load()

    def load(self):
        if os.path.exists(self.filepath):
            try:
                with open(self.filepath, 'r') as f:
                    self.stations = json.load(f)
            except (json.JSONDecodeError, IOError):
                self.stations = []

    def save(self):
        with open(self.filepath, 'w') as f:
            json.dump(self.stations, f, indent=2)

    def add_station(self, freq_hz, name="", signal=0.0):
        # Avoid duplicates (within 50 kHz)
        for s in self.stations:
            if abs(s['freq'] - freq_hz) < 50000:
                return
        self.stations.append({
            'freq': freq_hz,
            'name': name,
            'signal': signal,
            'favorite': False
        })
        self.stations.sort(key=lambda s: s['freq'])
        self.save()

    def remove_station(self, freq_hz):
        self.stations = [s for s in self.stations if s['freq'] != freq_hz]
        self.save()

    def rename_station(self, freq_hz, name):
        for s in self.stations:
            if s['freq'] == freq_hz:
                s['name'] = name
                break
        self.save()

    def toggle_favorite(self, freq_hz):
        for s in self.stations:
            if s['freq'] == freq_hz:
                s['favorite'] = not s['favorite']
                break
        self.save()

    def clear_all(self):
        self.stations = []
        self.save()


class FmRadioApp:
    """Main FM Radio GUI application."""

    def __init__(self):
        self.sdr = None
        self.demodulator = FmDemodulator()
        self.storage = StationStorage()
        self.current_freq = 100.0e6  # 100.0 MHz
        self.is_playing = False
        self.is_scanning = False
        self.play_thread = None
        self.scan_thread = None
        self.volume = 0.8

        self._build_gui()

    def _build_gui(self):
        self.root = tk.Tk()
        self.root.title("FM Radio - RTL-SDR")
        self.root.geometry("700x650")
        self.root.configure(bg='#1A1A2E')
        self.root.resizable(True, True)

        style = ttk.Style()
        style.theme_use('clam')
        style.configure('Dark.TFrame', background='#1A1A2E')
        style.configure('Dark.TLabel', background='#1A1A2E', foreground='#B0BEC5',
                         font=('Segoe UI', 10))
        style.configure('Freq.TLabel', background='#16213E', foreground='#00E676',
                         font=('Consolas', 48, 'bold'))
        style.configure('FmLabel.TLabel', background='#16213E', foreground='#FF9100',
                         font=('Segoe UI', 11))
        style.configure('Status.TLabel', background='#1A1A2E', foreground='#78909C',
                         font=('Segoe UI', 9))
        style.configure('Green.TButton', font=('Segoe UI', 11, 'bold'))
        style.configure('Orange.TButton', font=('Segoe UI', 11, 'bold'))

        main = ttk.Frame(self.root, style='Dark.TFrame')
        main.pack(fill=tk.BOTH, expand=True, padx=12, pady=8)

        # --- Status bar ---
        status_frame = ttk.Frame(main, style='Dark.TFrame')
        status_frame.pack(fill=tk.X, pady=(0, 6))

        self.lbl_status = ttk.Label(status_frame, text="RTL-SDR: Disconnected",
                                     style='Dark.TLabel')
        self.lbl_status.pack(side=tk.LEFT)

        self.btn_connect = tk.Button(status_frame, text="Connect", bg='#448AFF',
                                      fg='white', font=('Segoe UI', 10),
                                      command=self.toggle_connect, relief=tk.FLAT,
                                      padx=12, pady=2)
        self.btn_connect.pack(side=tk.RIGHT)

        self.lbl_device = ttk.Label(main, text="", style='Status.TLabel')
        self.lbl_device.pack(fill=tk.X, pady=(0, 8))

        # --- Frequency display ---
        freq_frame = tk.Frame(main, bg='#16213E', highlightbackground='#00E676',
                               highlightthickness=2, padx=16, pady=12)
        freq_frame.pack(fill=tk.X, pady=(0, 8))

        ttk.Label(freq_frame, text="FM MHz", style='FmLabel.TLabel').pack()
        self.lbl_freq = ttk.Label(freq_frame, text="100.0", style='Freq.TLabel')
        self.lbl_freq.pack()

        # --- Frequency slider ---
        slider_frame = ttk.Frame(main, style='Dark.TFrame')
        slider_frame.pack(fill=tk.X, pady=(0, 6))

        ttk.Label(slider_frame, text="87.5", style='Dark.TLabel').pack(side=tk.LEFT)
        self.freq_scale = tk.Scale(
            slider_frame, from_=875, to=1080, orient=tk.HORIZONTAL,
            resolution=1, showvalue=False, bg='#1A1A2E', fg='#00E676',
            troughcolor='#16213E', highlightthickness=0,
            command=self._on_freq_slider
        )
        self.freq_scale.set(int(self.current_freq / 1e5))
        self.freq_scale.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=6)
        ttk.Label(slider_frame, text="108.0", style='Dark.TLabel').pack(side=tk.LEFT)

        # --- Control buttons ---
        ctrl_frame = ttk.Frame(main, style='Dark.TFrame')
        ctrl_frame.pack(fill=tk.X, pady=(0, 8))

        self.btn_prev = tk.Button(ctrl_frame, text="\u25C0 -0.1", bg='#263859',
                                   fg='white', font=('Segoe UI', 12),
                                   command=lambda: self.step_freq(-1),
                                   relief=tk.FLAT, padx=16, pady=8)
        self.btn_prev.pack(side=tk.LEFT, padx=(0, 8))

        self.btn_play = tk.Button(ctrl_frame, text="\u25B6  PLAY", bg='#00C853',
                                   fg='white', font=('Segoe UI', 14, 'bold'),
                                   command=self.toggle_play, relief=tk.FLAT,
                                   padx=24, pady=8, state=tk.DISABLED)
        self.btn_play.pack(side=tk.LEFT, expand=True, fill=tk.X, padx=8)

        self.btn_next = tk.Button(ctrl_frame, text="+0.1 \u25B6", bg='#263859',
                                   fg='white', font=('Segoe UI', 12),
                                   command=lambda: self.step_freq(1),
                                   relief=tk.FLAT, padx=16, pady=8)
        self.btn_next.pack(side=tk.LEFT, padx=(8, 0))

        # --- Volume ---
        vol_frame = ttk.Frame(main, style='Dark.TFrame')
        vol_frame.pack(fill=tk.X, pady=(0, 6))

        ttk.Label(vol_frame, text="Volume:", style='Dark.TLabel').pack(side=tk.LEFT)
        self.vol_scale = tk.Scale(
            vol_frame, from_=0, to=100, orient=tk.HORIZONTAL,
            resolution=1, showvalue=True, bg='#1A1A2E', fg='#FF9100',
            troughcolor='#16213E', highlightthickness=0,
            command=self._on_volume
        )
        self.vol_scale.set(int(self.volume * 100))
        self.vol_scale.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=6)

        # --- Scan button ---
        self.btn_scan = tk.Button(main, text="\U0001F50D  Auto Scan FM Band",
                                   bg='#FF9100', fg='white',
                                   font=('Segoe UI', 12, 'bold'),
                                   command=self.toggle_scan, relief=tk.FLAT,
                                   pady=8, state=tk.DISABLED)
        self.btn_scan.pack(fill=tk.X, pady=(0, 4))

        # Scan progress
        self.scan_progress = ttk.Progressbar(main, mode='determinate')
        self.lbl_scan = ttk.Label(main, text="", style='Status.TLabel')

        # --- Station list ---
        list_frame = ttk.Frame(main, style='Dark.TFrame')
        list_frame.pack(fill=tk.BOTH, expand=True, pady=(6, 0))

        ttk.Label(list_frame, text="Saved Stations", style='Dark.TLabel',
                   font=('Segoe UI', 12, 'bold')).pack(anchor=tk.W)

        # Treeview for stations
        tree_frame = tk.Frame(list_frame, bg='#1F2B47')
        tree_frame.pack(fill=tk.BOTH, expand=True, pady=(4, 0))

        style.configure('Station.Treeview',
                         background='#1F2B47', foreground='#FFFFFF',
                         fieldbackground='#1F2B47', rowheight=32,
                         font=('Consolas', 11))
        style.configure('Station.Treeview.Heading',
                         background='#16213E', foreground='#B0BEC5',
                         font=('Segoe UI', 10, 'bold'))
        style.map('Station.Treeview',
                   background=[('selected', '#448AFF')])

        self.tree = ttk.Treeview(
            tree_frame, columns=('freq', 'name', 'signal', 'fav'),
            show='headings', style='Station.Treeview', selectmode='browse'
        )
        self.tree.heading('freq', text='Frequency')
        self.tree.heading('name', text='Name')
        self.tree.heading('signal', text='Signal')
        self.tree.heading('fav', text='\u2605')
        self.tree.column('freq', width=120, anchor=tk.CENTER)
        self.tree.column('name', width=200)
        self.tree.column('signal', width=80, anchor=tk.CENTER)
        self.tree.column('fav', width=50, anchor=tk.CENTER)

        scrollbar = ttk.Scrollbar(tree_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        self.tree.bind('<Double-1>', self._on_station_double_click)
        self.tree.bind('<Button-3>', self._on_station_right_click)

        self._refresh_station_list()

        # Context menu
        self.ctx_menu = tk.Menu(self.root, tearoff=0, bg='#16213E', fg='white')
        self.ctx_menu.add_command(label="Tune to station", command=self._ctx_tune)
        self.ctx_menu.add_command(label="Rename", command=self._ctx_rename)
        self.ctx_menu.add_command(label="Toggle Favorite", command=self._ctx_favorite)
        self.ctx_menu.add_separator()
        self.ctx_menu.add_command(label="Delete", command=self._ctx_delete)

    def run(self):
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.mainloop()

    # --- Device connection ---

    def toggle_connect(self):
        if self.sdr is None:
            self._connect()
        else:
            self._disconnect()

    def _connect(self):
        try:
            self.lbl_status.config(text="Connecting...")
            self.root.update()
            self.sdr = RtlSdr()
            self.sdr.sample_rate = SAMPLE_RATE
            self.sdr.center_freq = self.current_freq
            self.sdr.gain = 'auto'

            self.lbl_status.config(text="RTL-SDR: Connected")
            self.lbl_device.config(text=f"Device: RTL-SDR | Sample rate: {SAMPLE_RATE/1e6} MHz")
            self.btn_connect.config(text="Disconnect", bg='#FF5252')
            self.btn_play.config(state=tk.NORMAL)
            self.btn_scan.config(state=tk.NORMAL)
        except Exception as e:
            self.lbl_status.config(text=f"Error: {e}")
            messagebox.showerror("Connection Error",
                                  f"Could not open RTL-SDR device.\n\n{e}\n\n"
                                  "Make sure:\n"
                                  "1. RTL-SDR is connected via USB\n"
                                  "2. Zadig driver (WinUSB) is installed\n"
                                  "3. No other program is using the device")
            self.sdr = None

    def _disconnect(self):
        if self.is_playing:
            self.toggle_play()
        if self.sdr:
            try:
                self.sdr.close()
            except Exception:
                pass
            self.sdr = None
        self.lbl_status.config(text="RTL-SDR: Disconnected")
        self.lbl_device.config(text="")
        self.btn_connect.config(text="Connect", bg='#448AFF')
        self.btn_play.config(state=tk.DISABLED)
        self.btn_scan.config(state=tk.DISABLED)

    # --- Playback ---

    def toggle_play(self):
        if self.is_playing:
            self._stop_playback()
        else:
            self._start_playback()

    def _start_playback(self):
        if not self.sdr:
            return
        self.is_playing = True
        self.btn_play.config(text="\u25A0  STOP", bg='#FF5252')
        self.demodulator.reset()
        self.sdr.center_freq = self.current_freq

        self.play_thread = threading.Thread(target=self._playback_loop, daemon=True)
        self.play_thread.start()

    def _stop_playback(self):
        self.is_playing = False
        self.btn_play.config(text="\u25B6  PLAY", bg='#00C853')
        time.sleep(0.2)

    def _playback_loop(self):
        stream = None
        try:
            stream = sd.OutputStream(
                samplerate=AUDIO_RATE, channels=1, dtype='float32',
                blocksize=1024
            )
            stream.start()

            while self.is_playing:
                iq = self.sdr.read_samples(16384)
                audio = self.demodulator.demodulate(iq)
                audio = audio * self.volume
                if len(audio) > 0:
                    stream.write(audio.reshape(-1, 1))
        except Exception as e:
            self.root.after(0, lambda: self._handle_play_error(str(e)))
        finally:
            if stream is not None:
                try:
                    stream.stop()
                    stream.close()
                except Exception:
                    pass

    def _handle_play_error(self, msg):
        self.is_playing = False
        self.btn_play.config(text="\u25B6  PLAY", bg='#00C853')
        self.lbl_status.config(text=f"Playback error: {msg}")

    # --- Frequency control ---

    def set_frequency(self, freq_hz):
        freq_hz = max(FM_BAND_START, min(FM_BAND_END, freq_hz))
        self.current_freq = freq_hz
        self.lbl_freq.config(text=f"{freq_hz / 1e6:.1f}")
        self.freq_scale.set(int(freq_hz / 1e5))
        if self.sdr and self.is_playing:
            self.sdr.center_freq = freq_hz
            self.demodulator.reset()

    def step_freq(self, steps):
        self.set_frequency(self.current_freq + steps * FM_STEP)

    def _on_freq_slider(self, val):
        freq = int(val) * 1e5
        self.current_freq = freq
        self.lbl_freq.config(text=f"{freq / 1e6:.1f}")
        if self.sdr and self.is_playing:
            self.sdr.center_freq = freq
            self.demodulator.reset()

    def _on_volume(self, val):
        self.volume = int(val) / 100.0

    # --- Auto scan ---

    def toggle_scan(self):
        if self.is_scanning:
            self.is_scanning = False
            self.btn_scan.config(text="\U0001F50D  Auto Scan FM Band")
        else:
            self._start_scan()

    def _start_scan(self):
        if not self.sdr:
            return

        # Stop playback during scan
        if self.is_playing:
            self._stop_playback()

        self.is_scanning = True
        self.btn_scan.config(text="\u25A0  Stop Scan")

        self.scan_progress.pack(fill=tk.X, pady=(0, 2))
        self.lbl_scan.pack(fill=tk.X, pady=(0, 4))

        self.scan_thread = threading.Thread(target=self._scan_loop, daemon=True)
        self.scan_thread.start()

    def _scan_loop(self):
        try:
            self.sdr.sample_rate = SAMPLE_RATE
            self.sdr.gain = 'auto'

            freq = FM_BAND_START
            total_steps = int((FM_BAND_END - FM_BAND_START) / FM_STEP)
            step = 0
            found = []
            threshold = -20.0

            while freq <= FM_BAND_END and self.is_scanning:
                self.sdr.center_freq = freq
                time.sleep(0.05)  # PLL settle

                iq = self.sdr.read_samples(32768)
                power = self.demodulator.measure_power(iq)

                step += 1
                progress = step / total_steps * 100

                self.root.after(0, lambda f=freq, p=progress:
                                self._update_scan_ui(f, p))

                if power > threshold:
                    found.append({'freq': freq, 'power': power})
                    self.root.after(0, lambda f=freq, p=power:
                                    self._on_station_found(f, p))

                freq += FM_STEP

            # Merge close stations
            merged = self._merge_stations(found)

            self.root.after(0, lambda: self._on_scan_complete(merged))

        except Exception as e:
            self.root.after(0, lambda: self._on_scan_error(str(e)))

    def _update_scan_ui(self, freq, progress):
        self.scan_progress['value'] = progress
        self.lbl_scan.config(text=f"Scanning {freq/1e6:.1f} MHz ({progress:.0f}%)")
        self.lbl_freq.config(text=f"{freq/1e6:.1f}")

    def _on_station_found(self, freq, power):
        self.storage.add_station(freq, signal=power)
        self._refresh_station_list()

    def _on_scan_complete(self, stations):
        self.is_scanning = False
        self.btn_scan.config(text="\U0001F50D  Auto Scan FM Band")
        self.scan_progress.pack_forget()
        self.lbl_scan.pack_forget()
        messagebox.showinfo("Scan Complete",
                             f"Found {len(stations)} FM stations!")
        self._refresh_station_list()

    def _on_scan_error(self, msg):
        self.is_scanning = False
        self.btn_scan.config(text="\U0001F50D  Auto Scan FM Band")
        self.scan_progress.pack_forget()
        self.lbl_scan.pack_forget()
        messagebox.showerror("Scan Error", msg)

    def _merge_stations(self, stations):
        if not stations:
            return []
        stations.sort(key=lambda s: s['freq'])
        merged = [stations[0]]
        for s in stations[1:]:
            if s['freq'] - merged[-1]['freq'] < 200e3:
                if s['power'] > merged[-1]['power']:
                    merged[-1] = s
            else:
                merged.append(s)
        return merged

    # --- Station list ---

    def _refresh_station_list(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        for s in self.storage.stations:
            freq_str = f"{s['freq']/1e6:.1f} FM"
            name = s.get('name', '')
            signal = f"{s.get('signal', 0):.0f} dB"
            fav = "\u2605" if s.get('favorite', False) else ""
            self.tree.insert('', tk.END, values=(freq_str, name, signal, fav),
                              iid=str(int(s['freq'])))

    def _on_station_double_click(self, event):
        sel = self.tree.selection()
        if sel:
            freq = int(sel[0])
            self.set_frequency(freq)
            if not self.is_playing and self.sdr:
                self._start_playback()

    def _on_station_right_click(self, event):
        item = self.tree.identify_row(event.y)
        if item:
            self.tree.selection_set(item)
            self.ctx_menu.post(event.x_root, event.y_root)

    def _get_selected_freq(self):
        sel = self.tree.selection()
        if sel:
            return int(sel[0])
        return None

    def _ctx_tune(self):
        freq = self._get_selected_freq()
        if freq:
            self.set_frequency(freq)
            if not self.is_playing and self.sdr:
                self._start_playback()

    def _ctx_rename(self):
        freq = self._get_selected_freq()
        if freq:
            name = simpledialog.askstring("Rename Station",
                                           f"Enter name for {freq/1e6:.1f} FM:",
                                           parent=self.root)
            if name:
                self.storage.rename_station(freq, name)
                self._refresh_station_list()

    def _ctx_favorite(self):
        freq = self._get_selected_freq()
        if freq:
            self.storage.toggle_favorite(freq)
            self._refresh_station_list()

    def _ctx_delete(self):
        freq = self._get_selected_freq()
        if freq:
            if messagebox.askyesno("Delete Station",
                                     f"Delete {freq/1e6:.1f} FM?"):
                self.storage.remove_station(freq)
                self._refresh_station_list()

    # --- Cleanup ---

    def _on_close(self):
        self.is_playing = False
        self.is_scanning = False
        if self.sdr:
            try:
                self.sdr.close()
            except Exception:
                pass
        self.root.destroy()


def main():
    app = FmRadioApp()
    app.run()


if __name__ == '__main__':
    main()
