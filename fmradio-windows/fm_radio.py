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

# --- Ensure pkg_resources is available BEFORE any third-party imports ---
# pyrtlsdr requires pkg_resources (from setuptools). If setuptools is missing
# in the portable build, create a minimal shim so imports don't fail.
try:
    import pkg_resources  # noqa: F401
except ImportError:
    import types as _types
    _fake_pr = _types.ModuleType('pkg_resources')
    class _FakeDist:
        version = '0.3.0'
    _fake_pr.get_distribution = lambda name: _FakeDist()
    _fake_pr.DistributionNotFound = type('DistributionNotFound', (Exception,), {})
    _fake_pr.VersionConflict = type('VersionConflict', (Exception,), {})
    sys.modules['pkg_resources'] = _fake_pr
    del _types, _fake_pr

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

RtlSdr = None

def _find_rtlsdr_dll():
    """Try to locate rtlsdr DLL and add its directory to PATH."""
    import ctypes.util
    # Common DLL names for RTL-SDR on Windows
    dll_names = ['rtlsdr', 'librtlsdr', 'rtlsdr.dll', 'librtlsdr.dll']

    # Check if already loadable
    for name in dll_names:
        if ctypes.util.find_library(name):
            return True

    # Search in common locations relative to this script
    script_dir = os.path.dirname(os.path.abspath(__file__))
    search_dirs = [
        script_dir,
        os.path.join(script_dir, 'python'),
        os.path.join(script_dir, 'python', 'DLLs'),
    ]

    for d in search_dirs:
        for dll in ['rtlsdr.dll', 'librtlsdr.dll']:
            if os.path.isfile(os.path.join(d, dll)):
                if d not in os.environ.get('PATH', ''):
                    os.environ['PATH'] = d + os.pathsep + os.environ.get('PATH', '')
                return True
    return False


def _load_rtlsdr():
    global RtlSdr
    if RtlSdr is None:
        # Try to find DLL on PATH
        _find_rtlsdr_dll()

        # Step 3: Import pyrtlsdr
        try:
            from rtlsdr import RtlSdr as _Sdr
            RtlSdr = _Sdr
        except ImportError as e:
            err = str(e)
            if 'pkg_resources' in err:
                hint = ("Python package 'setuptools' is missing.\n"
                        "Run: pip install setuptools\n\n")
            elif 'rtlsdr' in err.lower() and 'dll' in err.lower():
                hint = ("RTL-SDR native library (rtlsdr.dll) not found.\n"
                        "Make sure the portable ZIP was fully extracted.\n\n")
            else:
                hint = ""
            raise RuntimeError(
                f"RTL-SDR driver not found.\n\n{hint}"
                "Install steps:\n"
                "1. Download Zadig: https://zadig.akeo.ie\n"
                "2. Plug in RTL-SDR device\n"
                "3. In Zadig: Options -> List All Devices\n"
                "4. Select 'RTL2832U' or 'Bulk-In, Interface (Interface 0)'\n"
                "5. Install WinUSB driver\n\n"
                f"Details: {e}"
            )
        except OSError as e:
            raise RuntimeError(
                "RTL-SDR native library failed to load.\n\n"
                "This usually means rtlsdr.dll is missing or corrupted.\n"
                "Make sure the portable ZIP was fully extracted.\n\n"
                "If using the portable version, check that these files exist:\n"
                "  python\\rtlsdr.dll\n"
                "  python\\librtlsdr.dll\n\n"
                f"Details: {e}"
            )
    return RtlSdr


# --- Constants ---
FM_BAND_START = 87.5e6  # 87.5 MHz
FM_BAND_END = 108.0e6   # 108.0 MHz
FM_STEP = 100e3          # 100 kHz
SAMPLE_RATE = 1.152e6    # 1.152 MHz (divides cleanly: /6→192kHz, /4→48kHz)
AUDIO_RATE = 48000        # 48 kHz audio output
STATIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fm_stations.json")


class FmDemodulator:
    """High-quality stereo FM demodulation based on SDR++/rtl_fm/librtlsdr.

    IQ (1152kHz) → DC removal → IF LPF (120kHz) → ÷6 → FM discriminator (192kHz)
    → Pilot PLL (19kHz lock) → Stereo decode (38kHz L-R recovery)
    → Audio LPF (15kHz) → ÷4 → De-emphasis (50µs) → Stereo PCM (48kHz)

    References:
      - SDR++ broadcast_fm.h: PLL-locked stereo, separate L/R filters
      - librtlsdr rtl_fm.c: fast_atan2, polar_discriminant
      - osmocom rtl-sdr wiki: RTL2832U device parameters
    """

    def __init__(self, sample_rate=SAMPLE_RATE, audio_rate=AUDIO_RATE):
        self.sample_rate = sample_rate
        self.audio_rate = audio_rate
        self.stage1_dec = 6
        self.intermediate_rate = sample_rate / self.stage1_dec  # 192000
        self.stage2_dec = int(self.intermediate_rate / audio_rate)  # 4

        # DC offset removal
        self.dc_i = 0.0
        self.dc_q = 0.0
        self.dc_alpha = 0.9999

        # FM gain — normalize atan2 output to proper audio level
        self.fm_gain = (self.intermediate_rate / (2 * np.pi * 75000)) * 0.7

        # IF low-pass filter (120 kHz cutoff, 128 taps, Blackman-Harris)
        self.if_filter = self._design_filter(128, 120e3, sample_rate)
        self.if_state_i = np.zeros(len(self.if_filter) - 1)
        self.if_state_q = np.zeros(len(self.if_filter) - 1)

        # Audio low-pass filters (15 kHz cutoff, 96 taps) — for mono and diff
        self.audio_filter = self._design_filter(96, 15e3, self.intermediate_rate)
        self.mono_state = np.zeros(len(self.audio_filter) - 1)
        self.diff_state = np.zeros(len(self.audio_filter) - 1)

        # De-emphasis (50µs time constant for Europe/Russia)
        tau = 50e-6
        dt = 1.0 / audio_rate
        self.deemph_alpha = dt / (tau + dt)
        self.deemph_state_l = 0.0
        self.deemph_state_r = 0.0

        # Previous sample for FM discriminator
        self.prev_sample = 0 + 0j

        # Pilot PLL (19 kHz, SDR++/gr-rds approach)
        self.pilot_nco_phase = 0.0
        self.pilot_nco_freq = 2.0 * np.pi * 19000.0 / self.intermediate_rate
        pilot_loop_bw = 2.0 * np.pi * 5.0 / self.intermediate_rate
        damp = 0.707
        self.pilot_alpha = 2.0 * damp * pilot_loop_bw
        self.pilot_beta = pilot_loop_bw ** 2

        # Pilot bandpass biquad (Q=80 at 19 kHz)
        w0 = 2.0 * np.pi * 19000.0 / self.intermediate_rate
        bpf_q = 80.0
        bpf_alpha = np.sin(w0) / (2.0 * bpf_q)
        a0 = 1.0 + bpf_alpha
        self.pilot_bpf_b0 = bpf_alpha / a0
        self.pilot_bpf_b2 = -bpf_alpha / a0
        self.pilot_bpf_a1 = (-2.0 * np.cos(w0)) / a0
        self.pilot_bpf_a2 = (1.0 - bpf_alpha) / a0
        self.pilot_bpf_state = [0.0, 0.0, 0.0, 0.0]  # x1, x2, y1, y2

        # Stereo detection
        self.is_stereo = False
        self.pilot_strength_acc = 0.0
        self.pilot_strength_count = 0
        self.pilot_detect_window = int(self.intermediate_rate / 4)

    def _design_filter(self, numtaps, cutoff, fs):
        """FIR lowpass with Blackman-Harris window (~92 dB stopband)."""
        nyq = fs / 2.0
        fc = cutoff / nyq
        half = (numtaps - 1) / 2.0
        n = np.arange(numtaps)
        h = np.where(n == half, 2 * fc, np.sinc(2 * fc * (n - half)) * 2 * fc)
        w = n / (numtaps - 1)
        window = (0.35875 - 0.48829 * np.cos(2 * np.pi * w)
                  + 0.14128 * np.cos(4 * np.pi * w)
                  - 0.01168 * np.cos(6 * np.pi * w))
        h *= window
        h /= np.sum(h)
        return h

    def _pilot_bpf(self, x):
        """Process one sample through pilot bandpass biquad."""
        y = (self.pilot_bpf_b0 * x + self.pilot_bpf_b2 * self.pilot_bpf_state[1]
             - self.pilot_bpf_a1 * self.pilot_bpf_state[2]
             - self.pilot_bpf_a2 * self.pilot_bpf_state[3])
        self.pilot_bpf_state[1] = self.pilot_bpf_state[0]
        self.pilot_bpf_state[0] = x
        self.pilot_bpf_state[3] = self.pilot_bpf_state[2]
        self.pilot_bpf_state[2] = y
        return y

    def demodulate(self, iq_samples):
        """Demodulate IQ samples to stereo audio (Nx2 float32 array)."""
        if len(iq_samples) == 0:
            return np.array([], dtype=np.float32).reshape(0, 2)

        # DC offset removal (vectorized for performance)
        i_raw = iq_samples.real.astype(np.float64)
        q_raw = iq_samples.imag.astype(np.float64)
        for k in range(len(i_raw)):
            self.dc_i = self.dc_alpha * self.dc_i + (1 - self.dc_alpha) * i_raw[k]
            self.dc_q = self.dc_alpha * self.dc_q + (1 - self.dc_alpha) * q_raw[k]
            i_raw[k] -= self.dc_i
            q_raw[k] -= self.dc_q

        # IF low-pass filter
        i_filt, self.if_state_i = lfilter(self.if_filter, 1, i_raw, zi=self.if_state_i)
        q_filt, self.if_state_q = lfilter(self.if_filter, 1, q_raw, zi=self.if_state_q)
        filtered = i_filt + 1j * q_filt

        # Stage 1 decimation to 192 kHz
        filtered = filtered[::self.stage1_dec]

        # FM discriminator (conjugate multiply + angle)
        shifted = filtered[1:] * np.conj(filtered[:-1])
        first = filtered[0] * np.conj(self.prev_sample)
        self.prev_sample = filtered[-1]
        raw_baseband = np.angle(np.concatenate([[first], shifted]))

        # Process pilot PLL and stereo decoding per-sample at 192 kHz
        n_inter = len(raw_baseband)
        mono_signal = np.empty(n_inter)
        diff_signal = np.empty(n_inter)

        for k in range(n_inter):
            bb = raw_baseband[k]

            # Pilot PLL: bandpass → phase detector → NCO
            pilot_sig = self._pilot_bpf(bb)
            pilot_error = pilot_sig * np.cos(self.pilot_nco_phase)
            self.pilot_nco_freq += self.pilot_beta * pilot_error
            self.pilot_nco_phase += self.pilot_nco_freq + self.pilot_alpha * pilot_error
            if self.pilot_nco_phase > 2 * np.pi:
                self.pilot_nco_phase -= 2 * np.pi
            elif self.pilot_nco_phase < 0:
                self.pilot_nco_phase += 2 * np.pi

            # Pilot strength for stereo detection
            self.pilot_strength_acc += pilot_sig * pilot_sig
            self.pilot_strength_count += 1
            if self.pilot_strength_count >= self.pilot_detect_window:
                avg = self.pilot_strength_acc / self.pilot_strength_count
                self.is_stereo = avg > 0.0005
                self.pilot_strength_acc = 0.0
                self.pilot_strength_count = 0

            # Scale for audio path
            scaled = bb * self.fm_gain

            # L+R (mono)
            mono_signal[k] = scaled

            # L-R: demodulate 38 kHz (2× pilot) DSB-SC subcarrier
            stereo_carrier = np.cos(2.0 * self.pilot_nco_phase)
            diff_signal[k] = scaled * stereo_carrier * 2.0

        # Audio LPF for both mono and diff channels
        mono_filt, self.mono_state = lfilter(
            self.audio_filter, 1, mono_signal, zi=self.mono_state)
        diff_filt, self.diff_state = lfilter(
            self.audio_filter, 1, diff_signal, zi=self.diff_state)

        # Stage 2 decimation to 48 kHz
        mono_dec = mono_filt[::self.stage2_dec]
        diff_dec = diff_filt[::self.stage2_dec]

        # Stereo matrix: L = (L+R + L-R)/2, R = (L+R - L-R)/2
        if self.is_stereo:
            left = (mono_dec + diff_dec) * 0.5
            right = (mono_dec - diff_dec) * 0.5
        else:
            left = mono_dec
            right = mono_dec

        # De-emphasis filter (50µs, separate L/R states)
        for i in range(len(left)):
            self.deemph_state_l += self.deemph_alpha * (left[i] - self.deemph_state_l)
            left[i] = self.deemph_state_l
            self.deemph_state_r += self.deemph_alpha * (right[i] - self.deemph_state_r)
            right[i] = self.deemph_state_r

        # Clip and return stereo (Nx2)
        left = np.clip(left, -1.0, 1.0)
        right = np.clip(right, -1.0, 1.0)
        stereo = np.column_stack([left, right]).astype(np.float32)

        return stereo

    def measure_power(self, iq_samples):
        """Measure signal power in dB."""
        if len(iq_samples) == 0:
            return -100.0
        power = np.mean(np.abs(iq_samples) ** 2)
        return 10 * np.log10(power + 1e-10)

    def reset(self):
        self.if_state_i = np.zeros(len(self.if_filter) - 1)
        self.if_state_q = np.zeros(len(self.if_filter) - 1)
        self.mono_state = np.zeros(len(self.audio_filter) - 1)
        self.diff_state = np.zeros(len(self.audio_filter) - 1)
        self.deemph_state_l = 0.0
        self.deemph_state_r = 0.0
        self.prev_sample = 0 + 0j
        self.dc_i = 0.0
        self.dc_q = 0.0
        self.pilot_nco_phase = 0.0
        self.pilot_nco_freq = 2.0 * np.pi * 19000.0 / self.intermediate_rate
        self.pilot_bpf_state = [0.0, 0.0, 0.0, 0.0]
        self.is_stereo = False
        self.pilot_strength_acc = 0.0
        self.pilot_strength_count = 0


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
            self.sdr = _load_rtlsdr()()
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
                samplerate=AUDIO_RATE, channels=2, dtype='float32',
                blocksize=1024
            )
            stream.start()

            while self.is_playing:
                iq = self.sdr.read_samples(16384)
                stereo = self.demodulator.demodulate(iq)
                stereo = stereo * self.volume
                if len(stereo) > 0:
                    stream.write(stereo)
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
