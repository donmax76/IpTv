using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AudioRecorder
{
    public partial class MainForm : Form
    {
        private readonly IAudioRecorderController audioRecorder;
        private readonly MMDeviceEnumerator deviceEnumerator;
        private WaveOutEvent? waveOut;
        private AudioFileReader? audioFileReader;
        private VolumeSampleProvider? volumeProvider;
        private MeteringSampleProvider? meterProvider;
        private string lastRecordedFilePath = string.Empty;
        private System.Windows.Forms.Timer recordingTimer;
        private DateTime recordingStartTime;
        private string currentDeviceName = "(устройство не выбрано)";
        private ComboBox? deviceComboBox;
        private CheckBox? recordWithoutDeviceCheckBox;
        private ProgressBar? audioLevelProgressBar;
        private Label? audioLevelLabel;
        private PictureBox? waveformPictureBox;
        private TrackBar? microphoneVolumeTrackBar;
        private Label? microphoneVolumeLabel;
        private TrackBar? playbackVolumeTrackBar;
        private Label? playbackVolumeLabel;
        private float playbackVolume = 1.0f;
        private TrackBar? playbackPositionTrackBar;
        private Label? playbackPositionLabel;
        private System.Windows.Forms.Timer? playbackTimer;
        private long totalPlaybackLength = 0;
        private bool isSeeking = false;
        private MMDevice? monitoringDevice;
        private System.Windows.Forms.Timer? monitoringLevelTimer;
        private Button? recordButton;
        private Button? playButton;
        private Button? stopButton;
        private Button? pauseButton;
        private bool isPaused = false;
        private bool monitoringReadyRequested = false;

        public MainForm()
        {
            InitializeComponent();
            audioRecorder = CreateAudioRecorder();
            audioRecorder.RecordingStarted += OnRecordingStarted;
            audioRecorder.RecordingStopped += OnRecordingStopped;
            audioRecorder.ErrorOccurred += OnErrorOccurred;
            audioRecorder.AudioLevelChanged += OnAudioLevelChanged;
            audioRecorder.AllowFallbackToSilence = false;

            recordingTimer = new System.Windows.Forms.Timer();
            recordingTimer.Interval = 100; // Обновление каждые 100мс
            recordingTimer.Tick += RecordingTimer_Tick;

            playbackTimer = new System.Windows.Forms.Timer();
            playbackTimer.Interval = 100; // Обновление каждые 100мс
            playbackTimer.Tick += PlaybackTimer_Tick;

            deviceEnumerator = new MMDeviceEnumerator();
            PopulateAudioDevices();

            this.Shown += MainForm_Shown;
            this.FormClosed += MainForm_FormClosed;
        }

        private IAudioRecorderController CreateAudioRecorder()
        {
            try
            {
                var hidden = new HiddenAudioRecorderClient();
                AppLogger.Log("MainForm: HiddenAudioRecorderClient initialized");
                return hidden;
            }
            catch (Exception ex)
            {
                AppLogger.Log("MainForm: Failed to initialize HiddenAudioRecorderClient, fallback to local", ex);
                return new AudioRecorderService();
            }
        }

        private void InitializeComponent()
        {
            this.Text = "Audio Recorder";
            this.Size = new Size(800, 650);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.FormBorderStyle = FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.BackColor = Color.FromArgb(25, 25, 30);

            // Заголовок
            Label titleLabel = new Label
            {
                Text = "🎙️ Audio Recorder",
                Location = new Point(20, 10),
                Size = new Size(760, 40),
                Font = new Font("Segoe UI", 18, FontStyle.Bold),
                ForeColor = Color.White,
                TextAlign = ContentAlignment.MiddleLeft,
                BackColor = Color.Transparent
            };
            this.Controls.Add(titleLabel);

            // Визуализация waveform (большая, в центре)
            waveformPictureBox = new PictureBox
            {
                Name = "waveformPictureBox",
                Location = new Point(20, 60),
                Size = new Size(760, 180),
                BackColor = Color.Black,
                BorderStyle = BorderStyle.FixedSingle
            };
            this.Controls.Add(waveformPictureBox);

            // Время записи/воспроизведения над waveform
            Label timeLabel = new Label
            {
                Name = "timeLabel",
                Text = "00:00",
                Location = new Point(30, 65),
                Size = new Size(120, 35),
                Font = new Font("Segoe UI", 20, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 200, 255),
                BackColor = Color.Transparent
            };
            this.Controls.Add(timeLabel);

            // Общее время справа
            Label totalTimeLabel = new Label
            {
                Name = "totalTimeLabel",
                Text = "00:00",
                Location = new Point(660, 65),
                Size = new Size(120, 35),
                Font = new Font("Segoe UI", 20, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 200, 255),
                BackColor = Color.Transparent,
                TextAlign = ContentAlignment.TopRight
            };
            this.Controls.Add(totalTimeLabel);

            // Классические кнопки управления (центрированные)
            Panel controlsPanel = new Panel
            {
                Location = new Point(20, 250),
                Size = new Size(760, 80),
                BackColor = Color.Transparent
            };
            this.Controls.Add(controlsPanel);

            int buttonSize = 65;
            int buttonSpacing = 15;
            int totalButtonsWidth = (buttonSize * 4) + (buttonSpacing * 3);
            int startX = (controlsPanel.Width - totalButtonsWidth) / 2;
            int buttonY = (controlsPanel.Height - buttonSize) / 2;

            // Кнопка Record
            recordButton = new Button
            {
                Name = "recordButton",
                Text = "●",
                Location = new Point(startX, buttonY),
                Size = new Size(buttonSize, buttonSize),
                Font = new Font("Arial", 26, FontStyle.Bold),
                BackColor = Color.Red,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand
            };
            recordButton.FlatAppearance.BorderSize = 0;
            recordButton.FlatAppearance.MouseOverBackColor = Color.FromArgb(220, 0, 0);
            recordButton.Click += RecordButton_Click;
            controlsPanel.Controls.Add(recordButton);

            // Кнопка Play
            playButton = new Button
            {
                Name = "playButton",
                Text = "▶",
                Location = new Point(startX + buttonSize + buttonSpacing, buttonY),
                Size = new Size(buttonSize, buttonSize),
                Font = new Font("Segoe UI", 22),
                BackColor = Color.FromArgb(0, 150, 255),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand,
                Enabled = false
            };
            playButton.FlatAppearance.BorderSize = 0;
            playButton.FlatAppearance.MouseOverBackColor = Color.FromArgb(0, 170, 255);
            playButton.Click += PlayButton_Click;
            controlsPanel.Controls.Add(playButton);

            // Кнопка Pause
            pauseButton = new Button
            {
                Name = "pauseButton",
                Text = "⏸",
                Location = new Point(startX + (buttonSize + buttonSpacing) * 2, buttonY),
                Size = new Size(buttonSize, buttonSize),
                Font = new Font("Segoe UI", 22),
                BackColor = Color.FromArgb(255, 165, 0),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand,
                Enabled = false
            };
            pauseButton.FlatAppearance.BorderSize = 0;
            pauseButton.FlatAppearance.MouseOverBackColor = Color.FromArgb(255, 185, 0);
            pauseButton.Click += PauseButton_Click;
            controlsPanel.Controls.Add(pauseButton);

            // Кнопка Stop
            stopButton = new Button
            {
                Name = "stopButton",
                Text = "■",
                Location = new Point(startX + (buttonSize + buttonSpacing) * 3, buttonY),
                Size = new Size(buttonSize, buttonSize),
                Font = new Font("Segoe UI", 22),
                BackColor = Color.FromArgb(60, 60, 70),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand,
                Enabled = false
            };
            stopButton.FlatAppearance.BorderSize = 0;
            stopButton.FlatAppearance.MouseOverBackColor = Color.FromArgb(80, 80, 90);
            stopButton.Click += StopButton_Click;
            controlsPanel.Controls.Add(stopButton);

            // Прогресс-бар воспроизведения (под кнопками)
            playbackPositionTrackBar = new TrackBar
            {
                Name = "playbackPositionTrackBar",
                Location = new Point(20, 340),
                Size = new Size(760, 45),
                Minimum = 0,
                Maximum = 1000,
                Value = 0,
                TickStyle = TickStyle.None,
                Enabled = false
            };
            playbackPositionTrackBar.ValueChanged += PlaybackPositionTrackBar_ValueChanged;
            playbackPositionTrackBar.MouseDown += PlaybackPositionTrackBar_MouseDown;
            playbackPositionTrackBar.MouseUp += PlaybackPositionTrackBar_MouseUp;
            this.Controls.Add(playbackPositionTrackBar);

            playbackPositionLabel = new Label
            {
                Name = "playbackPositionLabel",
                Text = "00:00 / 00:00",
                Location = new Point(20, 385),
                Size = new Size(760, 20),
                Font = new Font("Segoe UI", 9),
                ForeColor = Color.FromArgb(150, 150, 150),
                TextAlign = ContentAlignment.MiddleCenter,
                BackColor = Color.Transparent
            };
            this.Controls.Add(playbackPositionLabel);

            // Панель настроек
            Panel settingsPanel = new Panel
            {
                Location = new Point(20, 415),
                Size = new Size(760, 220),
                BackColor = Color.FromArgb(35, 35, 40),
                BorderStyle = BorderStyle.FixedSingle,
                Padding = new Padding(15)
            };
            this.Controls.Add(settingsPanel);

            Label deviceLabel = new Label
            {
                Text = "🎤 Устройство:",
                Location = new Point(0, 0),
                Size = new Size(120, 25),
                Font = new Font("Segoe UI", 10),
                ForeColor = Color.White
            };
            settingsPanel.Controls.Add(deviceLabel);

            deviceComboBox = new ComboBox
            {
                Name = "deviceComboBox",
                Location = new Point(130, 0),
                Size = new Size(600, 28),
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = Color.FromArgb(50, 50, 55),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            deviceComboBox.SelectedIndexChanged += DeviceComboBox_SelectedIndexChanged;
            settingsPanel.Controls.Add(deviceComboBox);

            recordWithoutDeviceCheckBox = new CheckBox
            {
                Name = "recordWithoutDeviceCheckBox",
                Text = "Записывать даже без подключенного устройства",
                Location = new Point(0, 35),
                AutoSize = true,
                Checked = false,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9)
            };
            recordWithoutDeviceCheckBox.CheckedChanged += RecordWithoutDeviceCheckBox_CheckedChanged;
            settingsPanel.Controls.Add(recordWithoutDeviceCheckBox);

            // Регулятор громкости микрофона
            Label micVolLabel = new Label
            {
                Text = "🔊 Громкость микрофона:",
                Location = new Point(0, 70),
                Size = new Size(180, 25),
                Font = new Font("Segoe UI", 10),
                ForeColor = Color.White
            };
            settingsPanel.Controls.Add(micVolLabel);

            microphoneVolumeTrackBar = new TrackBar
            {
                Name = "microphoneVolumeTrackBar",
                Location = new Point(190, 65),
                Size = new Size(400, 35),
                Minimum = 0,
                Maximum = 100,
                Value = 100,
                TickFrequency = 10
            };
            microphoneVolumeTrackBar.ValueChanged += MicrophoneVolumeTrackBar_ValueChanged;
            settingsPanel.Controls.Add(microphoneVolumeTrackBar);

            microphoneVolumeLabel = new Label
            {
                Name = "microphoneVolumeLabel",
                Text = "100%",
                Location = new Point(600, 70),
                Size = new Size(60, 25),
                Font = new Font("Segoe UI", 10, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 200, 255),
                TextAlign = ContentAlignment.MiddleLeft
            };
            settingsPanel.Controls.Add(microphoneVolumeLabel);

            // Индикатор уровня звука
            Label levelLabel = new Label
            {
                Text = "📊 Уровень звука:",
                Location = new Point(0, 110),
                Size = new Size(180, 25),
                Font = new Font("Segoe UI", 10),
                ForeColor = Color.White
            };
            settingsPanel.Controls.Add(levelLabel);

            audioLevelProgressBar = new ProgressBar
            {
                Name = "audioLevelProgressBar",
                Location = new Point(190, 110),
                Size = new Size(400, 25),
                Style = ProgressBarStyle.Continuous,
                Minimum = 0,
                Maximum = 100,
                Value = 0
            };
            settingsPanel.Controls.Add(audioLevelProgressBar);

            audioLevelLabel = new Label
            {
                Name = "audioLevelLabel",
                Text = "0%",
                Location = new Point(600, 110),
                Size = new Size(60, 25),
                Font = new Font("Segoe UI", 10, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 200, 255),
                TextAlign = ContentAlignment.MiddleLeft
            };
            settingsPanel.Controls.Add(audioLevelLabel);

            // Громкость воспроизведения
            Label playbackVolLabel = new Label
            {
                Text = "🔊 Громкость воспроизведения:",
                Location = new Point(0, 150),
                Size = new Size(180, 25),
                Font = new Font("Segoe UI", 10),
                ForeColor = Color.White
            };
            settingsPanel.Controls.Add(playbackVolLabel);

            playbackVolumeTrackBar = new TrackBar
            {
                Name = "playbackVolumeTrackBar",
                Location = new Point(190, 145),
                Size = new Size(400, 35),
                Minimum = 0,
                Maximum = 100,
                Value = 100,
                TickFrequency = 10
            };
            playbackVolumeTrackBar.ValueChanged += PlaybackVolumeTrackBar_ValueChanged;
            settingsPanel.Controls.Add(playbackVolumeTrackBar);

            playbackVolumeLabel = new Label
            {
                Name = "playbackVolumeLabel",
                Text = "100%",
                Location = new Point(600, 150),
                Size = new Size(60, 25),
                Font = new Font("Segoe UI", 10, FontStyle.Bold),
                ForeColor = Color.FromArgb(100, 200, 255),
                TextAlign = ContentAlignment.MiddleLeft
            };
            settingsPanel.Controls.Add(playbackVolumeLabel);

        }

        private void PopulateAudioDevices()
        {
            if (deviceComboBox == null)
            {
                return;
            }

            deviceComboBox.Items.Clear();

            int index = 0;
            int firstActiveIndex = -1;
            foreach (MMDevice device in deviceEnumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active | DeviceState.Disabled | DeviceState.Unplugged))
            {
                string name;
                try
                {
                    name = device.FriendlyName;
                }
                catch (COMException)
                {
                    name = "(неизвестное устройство)";
                }

                DeviceState state = device.State;

                string displayName = state switch
                {
                    DeviceState.Active => name,
                    _ => $"{name} ({state})"
                };

                var item = new WasapiDeviceItem(device.ID, displayName, state);
                deviceComboBox.Items.Add(item);

                if (state == DeviceState.Active && firstActiveIndex == -1)
                {
                    firstActiveIndex = index;
                }

                index++;
            }

            if (deviceComboBox.Items.Count > 0)
            {
                int targetIndex = firstActiveIndex >= 0 ? firstActiveIndex : 0;
                deviceComboBox.SelectedIndex = targetIndex;
            }
            else
            {
                audioRecorder.DeviceId = null;
                bool allowFallback = recordWithoutDeviceCheckBox?.Checked ?? false;
                UpdateSelectedDeviceInfo(null, allowFallback);

                if (!allowFallback)
                {
                    MessageBox.Show(
                        "В системе не найдено активных устройств записи. Проверьте, включен ли микрофон и установлен ли драйвер.",
                        "Устройства не найдены",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Warning
                    );
                }
            }
        }

        private void DeviceComboBox_SelectedIndexChanged(object? sender, EventArgs e)
        {
            if (deviceComboBox?.SelectedItem is WasapiDeviceItem selected)
            {
                if (selected.State != DeviceState.Active)
                {
                    bool allowFallback = recordWithoutDeviceCheckBox?.Checked ?? false;
                    audioRecorder.DeviceId = null;
                    UpdateSelectedDeviceInfo(selected, allowFallback);

                    if (!allowFallback)
                    {
                        MessageBox.Show(
                            "Выбрано устройство, находящееся не в состоянии Active. Включите устройство или выберите другое.",
                            "Устройство недоступно",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Warning
                        );
                    }
                    return;
                }

                audioRecorder.DeviceId = selected.Id;
                UpdateSelectedDeviceInfo(selected, false);
            }
        }

        private void RecordWithoutDeviceCheckBox_CheckedChanged(object? sender, EventArgs e)
        {
            bool allowFallback = recordWithoutDeviceCheckBox?.Checked ?? false;
            audioRecorder.AllowFallbackToSilence = allowFallback;

            var selectedItem = deviceComboBox?.SelectedItem as WasapiDeviceItem;

            if (allowFallback && selectedItem?.State != DeviceState.Active)
            {
                audioRecorder.DeviceId = null;
                UpdateSelectedDeviceInfo(selectedItem, true);
            }
            else if (!allowFallback)
            {
                if (selectedItem?.State == DeviceState.Active)
                {
                    audioRecorder.DeviceId = selectedItem.Id;
                    UpdateSelectedDeviceInfo(selectedItem, false);
                }
                else
                {
                    audioRecorder.DeviceId = null;
                    currentDeviceName = "(устройство не выбрано)";
                    UpdateStatus("Устройство не выбрано", Color.FromArgb(200, 0, 0));
                }
            }
            else if (allowFallback && selectedItem?.State == DeviceState.Active)
            {
                audioRecorder.DeviceId = selectedItem.Id;
                UpdateSelectedDeviceInfo(selectedItem, false);
            }
        }

        private void UpdateSelectedDeviceInfo(WasapiDeviceItem? item, bool fallbackMode)
        {
            if (fallbackMode)
            {
                currentDeviceName = item?.Name ?? "(будет использовано устройство по умолчанию, индикатор активности)";
                UpdateStatus("Готов к записи (индикатор включится при старте)", Color.FromArgb(0, 120, 215));
                return;
            }

            currentDeviceName = item?.Name ?? "(устройство не найдено)";
            UpdateStatus("Готов к записи", Color.FromArgb(0, 120, 215));
        }

        private void UpdateMicrophoneInfo()
        {
            // This method is not implemented in the original file,
            // so it will not have an effect on the current form's UI.
        }

        private void RecordButton_Click(object? sender, EventArgs e)
        {
            if (audioRecorder.IsRecording)
            {
                // Если идет запись, останавливаем
                StopButton_Click(sender, e);
            }
            else
            {
                // Начинаем запись
                StartButton_Click(sender, e);
            }
        }

        private void PauseButton_Click(object? sender, EventArgs e)
        {
            if (waveOut != null && audioFileReader != null)
            {
                if (isPaused)
                {
                    // Возобновляем воспроизведение
                    waveOut.Play();
                    isPaused = false;
                    if (pauseButton != null) pauseButton.Text = "⏸";
                    playbackTimer?.Start();
                }
                else
                {
                    // Ставим на паузу
                    waveOut.Pause();
                    isPaused = true;
                    if (pauseButton != null) pauseButton.Text = "▶";
                    playbackTimer?.Stop();
                }
            }
        }

        private void StartButton_Click(object? sender, EventArgs e)
        {
            AppLogger.Log("StartButton_Click invoked");
            try
            {
                // Останавливаем мониторинг микрофона перед записью (если был запущен)
                StopMicrophoneLevelMonitoring();
                
                // Даем время на освобождение устройства
                System.Threading.Thread.Sleep(200);

                bool allowFallback = recordWithoutDeviceCheckBox?.Checked ?? false;
                audioRecorder.AllowFallbackToSilence = allowFallback;

                // Проверяем, что выбрано активное устройство (если не включен fallback)
                if (!allowFallback)
                {
                    var selectedItem = deviceComboBox?.SelectedItem as WasapiDeviceItem;
                    if (selectedItem == null || selectedItem.State != DeviceState.Active)
                    {
                        MessageBox.Show(
                            "Выберите активное устройство записи из списка.\n\nЕсли устройств нет, включите галочку 'Записывать даже без подключенного микрофона'.",
                            "Устройство не выбрано",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Warning
                        );
                        return;
                    }
                    audioRecorder.DeviceId = selectedItem.Id;
                }
                else
                {
                    // При fallback режиме используем устройство по умолчанию, если оно есть
                    var selectedItem = deviceComboBox?.SelectedItem as WasapiDeviceItem;
                    if (selectedItem != null && selectedItem.State == DeviceState.Active)
                    {
                        audioRecorder.DeviceId = selectedItem.Id;
                    }
                    else
                    {
                        audioRecorder.DeviceId = null; // Будет использовано устройство по умолчанию или fallback
                    }
                }

                // Создаем папку для записей, если её нет
                string recordingsFolder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                    "AudioRecordings"
                );
                Directory.CreateDirectory(recordingsFolder);

                // Генерируем имя файла с датой и временем
                string fileName = $"Recording_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.mp3";
                lastRecordedFilePath = Path.Combine(recordingsFolder, fileName);

                // Начинаем запись
                audioRecorder.StartRecording(lastRecordedFilePath);
                AppLogger.Log("StartRecording completed without exception");
            }
            catch (Exception ex)
            {
                AppLogger.Log("StartButton_Click caught exception", ex);
                MessageBox.Show(
                    $"Ошибка при начале записи:\n{ex.Message}\n\nПроверьте:\n- Микрофон подключен и включен\n- Разрешения на использование микрофона\n- Другое приложение не использует микрофон",
                    "Ошибка",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
        }

        private void StopButton_Click(object? sender, EventArgs e)
        {
            AppLogger.Log("StopButton_Click invoked");
            try
            {
                audioRecorder.StopRecording();
                AppLogger.Log("StopRecording completed without exception");
            }
            catch (Exception ex)
            {
                AppLogger.Log("StopButton_Click caught exception", ex);
                MessageBox.Show(
                    $"Ошибка при остановке записи:\n{ex.Message}",
                    "Ошибка",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
        }

        private void PlayButton_Click(object? sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(lastRecordedFilePath) || !File.Exists(lastRecordedFilePath))
            {
                MessageBox.Show(
                    "Нет записанного файла для воспроизведения",
                    "Информация",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information
                );
                return;
            }

            // Проверяем размер файла
            var fileInfo = new FileInfo(lastRecordedFilePath);
            if (fileInfo.Length < 1000)
            {
                var result = MessageBox.Show(
                    $"Внимание: Файл очень мал ({fileInfo.Length} байт). Возможно, записана только тишина.\n\nПродолжить воспроизведение?",
                    "Предупреждение",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning
                );
                if (result == DialogResult.No)
                {
                    return;
                }
            }

            try
            {
                // Останавливаем предыдущее воспроизведение, если оно было
                StopPlayback();

                // Начинаем воспроизведение
                audioFileReader = new AudioFileReader(lastRecordedFilePath);
                
                // Проверяем, что файл можно прочитать
                if (audioFileReader.Length == 0)
                {
                    MessageBox.Show(
                        "Файл пуст или поврежден",
                        "Ошибка",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error
                    );
                    audioFileReader.Dispose();
                    audioFileReader = null;
                    return;
                }

                // Инициализируем полосу прокрутки
                totalPlaybackLength = audioFileReader.Length;
                if (playbackPositionTrackBar != null)
                {
                    playbackPositionTrackBar.Maximum = 1000;
                    playbackPositionTrackBar.Value = 0;
                    playbackPositionTrackBar.Enabled = true;
                }
                if (playbackPositionLabel != null)
                {
                    TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                    playbackPositionLabel.Text = $"00:00 / {totalTime:mm\\:ss}";
                }
                
                // Обновляем время над waveform
                Label? totalTimeLabel = this.Controls.Find("totalTimeLabel", true).FirstOrDefault() as Label;
                if (totalTimeLabel != null && audioFileReader.TotalTime.TotalSeconds > 0)
                {
                    TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                    totalTimeLabel.Text = $"{totalTime:mm\\:ss}";
                }
                
                // Запускаем таймер обновления позиции
                playbackTimer?.Start();

                // Добавляем обработчик для визуализации при воспроизведении с поддержкой громкости
                var sampleProvider = audioFileReader.ToSampleProvider();
                volumeProvider = new VolumeSampleProvider(sampleProvider) { Volume = playbackVolume };
                meterProvider = new MeteringSampleProvider(volumeProvider);
                meterProvider.StreamVolume += (sender, args) =>
                {
                    float maxLevel = Math.Max(args.MaxSampleValues[0], args.MaxSampleValues.Length > 1 ? args.MaxSampleValues[1] : 0);
                    if (InvokeRequired)
                    {
                        Invoke(new Action<float>(level => {
                            if (audioLevelProgressBar != null) audioLevelProgressBar.Value = (int)(level * 100);
                            if (audioLevelLabel != null) audioLevelLabel.Text = $"{(int)(level * 100)}%";
                            DrawWaveform(level);
                        }), maxLevel);
                    }
                    else
                    {
                        if (audioLevelProgressBar != null) audioLevelProgressBar.Value = (int)(maxLevel * 100);
                        if (audioLevelLabel != null) audioLevelLabel.Text = $"{(int)(maxLevel * 100)}%";
                        DrawWaveform(maxLevel);
                    }
                };
                
                waveOut = new WaveOutEvent();
                waveOut.Init(meterProvider);
                waveOut.PlaybackStopped += (s, e) => StopPlayback();
                waveOut.Play();
                
                isPaused = false;
                if (pauseButton != null) pauseButton.Enabled = true;
                if (playButton != null) playButton.Enabled = false;
                StopMicrophoneLevelMonitoring();

                UpdateStatus("Воспроизведение...", Color.FromArgb(0, 120, 215));
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    $"Ошибка при воспроизведении:\n{ex.Message}\n\nУбедитесь, что файл не поврежден и имеет правильный формат MP3.",
                    "Ошибка",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                StopPlayback();
            }
        }

        private void OpenFolderButton_Click(object? sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(lastRecordedFilePath))
            {
                string recordingsFolder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                    "AudioRecordings"
                );
                if (Directory.Exists(recordingsFolder))
                {
                    System.Diagnostics.Process.Start("explorer.exe", recordingsFolder);
                }
                return;
            }

            string? folderPath = Path.GetDirectoryName(lastRecordedFilePath);
            if (!string.IsNullOrEmpty(folderPath) && Directory.Exists(folderPath))
            {
                System.Diagnostics.Process.Start("explorer.exe", folderPath);
            }
        }

        private void OnRecordingStarted(object? sender, string filePath)
        {
            AppLogger.Log($"OnRecordingStarted received. File: {filePath}");
            if (InvokeRequired)
            {
                Invoke(new Action<object?, string>(OnRecordingStarted), sender, filePath);
                return;
            }

            recordingStartTime = DateTime.Now;
            recordingTimer.Start();

            bool silenceMode = audioRecorder.IsUsingSilenceFallback;

            if (silenceMode)
            {
                currentDeviceName = "(режим записи тишины - микрофон не используется)";
                UpdateStatus("Запись тишины (микрофон не используется)", Color.Orange);
            }
            else
            {
                UpdateStatus("Запись идет... (используется микрофон)", Color.Red);
            }

            EnableControls(false, true, false, false);
            // Мониторинг уже остановлен в StartButton_Click, не останавливаем повторно
        }

        private void OnRecordingStopped(object? sender, EventArgs e)
        {
            AppLogger.Log("OnRecordingStopped received");
            if (InvokeRequired)
            {
                Invoke(new Action<object?, EventArgs>(OnRecordingStopped), sender, e);
                return;
            }

            recordingTimer.Stop();
            
            // Сбрасываем визуализацию
            if (audioLevelProgressBar != null) audioLevelProgressBar.Value = 0;
            if (audioLevelLabel != null) audioLevelLabel.Text = "0%";
            DrawWaveform(0);

            UpdateStatus("Запись завершена", Color.Green);
            EnableControls(true, false, !string.IsNullOrEmpty(lastRecordedFilePath), true);
            
            // Даем время на освобождение устройства перед запуском мониторинга
            System.Threading.Thread.Sleep(300);
            StartMicrophoneLevelMonitoring();

            var selectedItem = deviceComboBox?.SelectedItem as WasapiDeviceItem;
            bool fallback = recordWithoutDeviceCheckBox?.Checked ?? false;
            if (fallback)
            {
                currentDeviceName = selectedItem?.Name ?? "(будет использовано устройство по умолчанию, индикатор активности)";
            }
            else
            {
                currentDeviceName = selectedItem?.Name ?? "(устройство не найдено)";
            }

            MessageBox.Show(
                $"Запись сохранена:\n{lastRecordedFilePath}",
                "Успешно",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information
            );
        }

        private void OnErrorOccurred(object? sender, string errorMessage)
        {
            AppLogger.Log($"OnErrorOccurred: {errorMessage}");
            if (InvokeRequired)
            {
                Invoke(new Action<object?, string>(OnErrorOccurred), sender, errorMessage);
                return;
            }

            MessageBox.Show(errorMessage, "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
            UpdateStatus("Ошибка", Color.Red);
            EnableControls(true, false, !string.IsNullOrEmpty(lastRecordedFilePath), true);
        }

        private void OnAudioLevelChanged(object? sender, float level)
        {
            UpdateMicrophoneVisualization(level);
        }

        private void MicrophoneVolumeTrackBar_ValueChanged(object? sender, EventArgs e)
        {
            if (microphoneVolumeTrackBar != null && microphoneVolumeLabel != null)
            {
                int value = microphoneVolumeTrackBar.Value;
                microphoneVolumeLabel.Text = $"{value}%";
                float volume = value / 100f;
                audioRecorder.MicrophoneVolume = volume;
                
                // Также устанавливаем громкость для выбранного устройства напрямую
                try
                {
                    if (deviceComboBox?.SelectedItem is WasapiDeviceItem selectedItem && selectedItem.State == DeviceState.Active)
                    {
                        MMDevice? device = deviceEnumerator.GetDevice(selectedItem.Id);
                        if (device != null)
                        {
                            try
                            {
                                device.AudioEndpointVolume.MasterVolumeLevelScalar = volume;
                            }
                            catch { }
                            finally
                            {
                                device.Dispose();
                            }
                        }
                    }
                }
                catch { }
            }
        }

        private void PlaybackVolumeTrackBar_ValueChanged(object? sender, EventArgs e)
        {
            if (playbackVolumeTrackBar != null && playbackVolumeLabel != null)
            {
                int value = playbackVolumeTrackBar.Value;
                playbackVolumeLabel.Text = $"{value}%";
                playbackVolume = value / 100f;
                
                // Обновляем громкость без пересоздания цепочки
                if (volumeProvider != null)
                {
                    volumeProvider.Volume = playbackVolume;
                }
            }
        }

        private void DrawWaveform(float level)
        {
            if (waveformPictureBox == null) return;

            try
            {
                Bitmap bmp = new Bitmap(waveformPictureBox.Width, waveformPictureBox.Height);
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.Clear(Color.FromArgb(20, 20, 30));

                    // Рисуем центральную линию
                    int centerY = bmp.Height / 2;
                    g.DrawLine(new Pen(Color.FromArgb(60, 60, 70), 1), 0, centerY, bmp.Width, centerY);

                    // Рисуем waveform на основе уровня звука
                    int amplitude = (int)(level * (bmp.Height / 2 - 5));
                    Color waveColor = level > 0.8f ? Color.Red : (level > 0.5f ? Color.Orange : Color.Green);

                    // Простая визуализация - рисуем синусоиду
                    Point[] points = new Point[bmp.Width];
                    for (int x = 0; x < bmp.Width; x++)
                    {
                        float y = centerY + (float)(amplitude * Math.Sin(x * 0.1 + DateTime.Now.Millisecond * 0.01));
                        points[x] = new Point(x, (int)y);
                    }

                    if (points.Length > 1)
                    {
                        g.DrawLines(new Pen(waveColor, 2), points);
                    }

                    // Заливка под волной
                    if (amplitude > 0)
                    {
                        Point[] fillPoints = new Point[bmp.Width + 2];
                        fillPoints[0] = new Point(0, centerY);
                        for (int i = 0; i < points.Length; i++)
                        {
                            fillPoints[i + 1] = points[i];
                        }
                        fillPoints[fillPoints.Length - 1] = new Point(bmp.Width, centerY);

                        using (Brush brush = new SolidBrush(Color.FromArgb(50, waveColor.R, waveColor.G, waveColor.B)))
                        {
                            g.FillPolygon(brush, fillPoints);
                        }
                    }
                }

                // Обновляем PictureBox
                if (waveformPictureBox.Image != null)
                {
                    waveformPictureBox.Image.Dispose();
                }
                waveformPictureBox.Image = bmp;
            }
            catch
            {
                // Игнорируем ошибки визуализации
            }
        }

        private void RecordingTimer_Tick(object? sender, EventArgs e)
        {
            try
            {
                TimeSpan elapsed = DateTime.Now - recordingStartTime;
                Label? timeLabel = this.Controls.Find("timeLabel", true).FirstOrDefault() as Label;
                if (timeLabel != null && InvokeRequired)
                {
                    Invoke(new Action(() => timeLabel.Text = $"{elapsed:mm\\:ss}"));
                }
                else if (timeLabel != null)
                {
                    timeLabel.Text = $"{elapsed:mm\\:ss}";
                }
            }
            catch { }
        }

        private void PlaybackTimer_Tick(object? sender, EventArgs e)
        {
            if (audioFileReader == null || isSeeking) return;

            try
            {
                if (audioFileReader.Position >= audioFileReader.Length)
                {
                    StopPlayback();
                    return;
                }

                double position = audioFileReader.Position;
                double length = audioFileReader.Length;
                double progress = length > 0 ? (position / length) * 1000 : 0;

                if (playbackPositionTrackBar != null)
                {
                    playbackPositionTrackBar.Value = Math.Min(1000, Math.Max(0, (int)progress));
                }

                if (playbackPositionLabel != null && audioFileReader.TotalTime.TotalSeconds > 0)
                {
                    TimeSpan currentTime = TimeSpan.FromSeconds(audioFileReader.CurrentTime.TotalSeconds);
                    TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                    playbackPositionLabel.Text = $"{currentTime:mm\\:ss} / {totalTime:mm\\:ss}";
                }
                
                // Обновляем время над waveform
                Label? timeLabel = this.Controls.Find("timeLabel", true).FirstOrDefault() as Label;
                Label? totalTimeLabel = this.Controls.Find("totalTimeLabel", true).FirstOrDefault() as Label;
                if (timeLabel != null && audioFileReader.TotalTime.TotalSeconds > 0)
                {
                    TimeSpan currentTime = TimeSpan.FromSeconds(audioFileReader.CurrentTime.TotalSeconds);
                    timeLabel.Text = $"{currentTime:mm\\:ss}";
                }
                if (totalTimeLabel != null && audioFileReader.TotalTime.TotalSeconds > 0)
                {
                    TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                    totalTimeLabel.Text = $"{totalTime:mm\\:ss}";
                }
            }
            catch { }
        }

        private void PlaybackPositionTrackBar_MouseDown(object? sender, MouseEventArgs e)
        {
            isSeeking = true;
        }

        private void PlaybackPositionTrackBar_MouseUp(object? sender, MouseEventArgs e)
        {
            if (audioFileReader == null || waveOut == null || playbackPositionTrackBar == null)
            {
                isSeeking = false;
                return;
            }

            try
            {
                double progress = playbackPositionTrackBar.Value / 1000.0;
                TimeSpan newTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds * progress);
                
                // Останавливаем воспроизведение для перемотки
                bool wasPlaying = (waveOut.PlaybackState == PlaybackState.Playing);
                waveOut.Stop();
                
                // Устанавливаем новую позицию через CurrentTime
                audioFileReader.CurrentTime = newTime;
                
                // Обновляем время в UI
                if (InvokeRequired)
                {
                    Invoke(new Action(() =>
                    {
                        Label? timeLabel = this.Controls.Find("timeLabel", true).FirstOrDefault() as Label;
                        if (timeLabel != null)
                        {
                            timeLabel.Text = $"{newTime:mm\\:ss}";
                        }
                        if (playbackPositionLabel != null)
                        {
                            TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                            playbackPositionLabel.Text = $"{newTime:mm\\:ss} / {totalTime:mm\\:ss}";
                        }
                    }));
                }
                else
                {
                    Label? timeLabel = this.Controls.Find("timeLabel", true).FirstOrDefault() as Label;
                    if (timeLabel != null)
                    {
                        timeLabel.Text = $"{newTime:mm\\:ss}";
                    }
                    if (playbackPositionLabel != null)
                    {
                        TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                        playbackPositionLabel.Text = $"{newTime:mm\\:ss} / {totalTime:mm\\:ss}";
                    }
                }
                
                // Пересоздаем цепочку воспроизведения с новой позицией
                var sampleProvider = audioFileReader.ToSampleProvider();
                volumeProvider = new VolumeSampleProvider(sampleProvider) { Volume = playbackVolume };
                meterProvider = new MeteringSampleProvider(volumeProvider);
                meterProvider.StreamVolume += (s, args) =>
                {
                    float maxLevel = Math.Max(args.MaxSampleValues[0], args.MaxSampleValues.Length > 1 ? args.MaxSampleValues[1] : 0);
                    if (InvokeRequired)
                    {
                        Invoke(new Action<float>(level => {
                            if (audioLevelProgressBar != null) audioLevelProgressBar.Value = (int)(level * 100);
                            if (audioLevelLabel != null) audioLevelLabel.Text = $"{(int)(level * 100)}%";
                            DrawWaveform(level);
                        }), maxLevel);
                    }
                    else
                    {
                        if (audioLevelProgressBar != null) audioLevelProgressBar.Value = (int)(maxLevel * 100);
                        if (audioLevelLabel != null) audioLevelLabel.Text = $"{(int)(maxLevel * 100)}%";
                        DrawWaveform(maxLevel);
                    }
                };
                
                waveOut.Init(meterProvider);
                if (wasPlaying)
                {
                    waveOut.Play();
                }
            }
            catch { }
            finally
            {
                isSeeking = false;
            }
        }

        private void PlaybackPositionTrackBar_ValueChanged(object? sender, EventArgs e)
        {
            // Обновляем только если пользователь перематывает
            if (isSeeking && audioFileReader != null && playbackPositionLabel != null)
            {
                try
                {
                    if (playbackPositionTrackBar != null)
                    {
                        double progress = playbackPositionTrackBar.Value / 1000.0;
                        TimeSpan currentTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds * progress);
                        TimeSpan totalTime = TimeSpan.FromSeconds(audioFileReader.TotalTime.TotalSeconds);
                        playbackPositionLabel.Text = $"{currentTime:mm\\:ss} / {totalTime:mm\\:ss}";
                    }
                }
                catch { }
            }
        }

        private void StartMicrophoneLevelMonitoring()
        {
            try
            {
                StopMicrophoneLevelMonitoring();

                if (audioRecorder.IsRecording || waveOut != null)
                {
                    return;
                }

                monitoringDevice = deviceEnumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Console);
                if (monitoringDevice == null || monitoringDevice.State != DeviceState.Active)
                {
                    return;
                }

                monitoringLevelTimer = new System.Windows.Forms.Timer();
                monitoringLevelTimer.Interval = 50;
                monitoringLevelTimer.Tick += MonitoringLevelTimer_Tick;
                monitoringLevelTimer.Start();
            }
            catch
            {
            }
        }

        private void MonitoringLevelTimer_Tick(object? sender, EventArgs e)
        {
            if (monitoringDevice == null)
            {
                return;
            }

            try
            {
                float peak = monitoringDevice.AudioMeterInformation.MasterPeakValue;
                UpdateMicrophoneVisualization(peak);
            }
            catch
            {
            }
        }

        private void StopMicrophoneLevelMonitoring()
        {
            try
            {
                monitoringLevelTimer?.Stop();
                if (monitoringLevelTimer != null)
                {
                    monitoringLevelTimer.Tick -= MonitoringLevelTimer_Tick;
                    monitoringLevelTimer.Dispose();
                    monitoringLevelTimer = null;
                }
            }
            catch { }

            try
            {
                monitoringDevice?.Dispose();
            }
            catch { }

            monitoringDevice = null;
        }

        private void UpdateMicrophoneVisualization(float level)
        {
            float clamped = Math.Max(0f, Math.Min(1f, level));

            if (InvokeRequired)
            {
                Invoke(new Action<float>(UpdateMicrophoneVisualization), clamped);
                return;
            }

            if (audioLevelProgressBar != null)
            {
                audioLevelProgressBar.Value = (int)(clamped * 100);
            }

            if (audioLevelLabel != null)
            {
                audioLevelLabel.Text = $"{(int)(clamped * 100)}%";
            }

            DrawWaveform(clamped);
        }

        private void UpdateStatus(string status, Color color)
        {
            Label? statusLabel = this.Controls.Find("statusLabel", true).FirstOrDefault() as Label;
            if (statusLabel != null)
            {
                string suffix = string.IsNullOrEmpty(currentDeviceName) ? string.Empty : $" — {currentDeviceName}";
                statusLabel.Text = status + suffix;
                statusLabel.ForeColor = color;
            }
        }

        private void EnableControls(bool canRecord, bool isRecording, bool canPlay, bool canOpenFolder)
        {
            if (recordButton != null)
            {
                recordButton.Enabled = canRecord;
                recordButton.BackColor = isRecording ? Color.DarkRed : Color.Red;
            }
            if (stopButton != null) stopButton.Enabled = isRecording;
            if (playButton != null) playButton.Enabled = canPlay;
            if (pauseButton != null) pauseButton.Enabled = false;
        }

        private void StopPlayback()
        {
            if (isClosing) return;
            
            playbackTimer?.Stop();

            if (waveOut != null)
            {
                try
                {
                    waveOut.Stop();
                    waveOut.Dispose();
                }
                catch { }
                waveOut = null;
            }

            if (audioFileReader != null)
            {
                try
                {
                    audioFileReader.Dispose();
                }
                catch { }
                audioFileReader = null;
            }

            // Сбрасываем визуализацию
            if (audioLevelProgressBar != null) audioLevelProgressBar.Value = 0;
            if (audioLevelLabel != null) audioLevelLabel.Text = "0%";
            DrawWaveform(0);

            // Сбрасываем полосу прокрутки
            if (playbackPositionTrackBar != null)
            {
                playbackPositionTrackBar.Value = 0;
                playbackPositionTrackBar.Enabled = false;
            }
            if (playbackPositionLabel != null)
            {
                playbackPositionLabel.Text = "00:00 / 00:00";
            }
            
            // Сбрасываем время
            Label? timeLabel = this.Controls.Find("timeLabel", true).FirstOrDefault() as Label;
            Label? totalTimeLabel = this.Controls.Find("totalTimeLabel", true).FirstOrDefault() as Label;
            if (timeLabel != null) timeLabel.Text = "00:00";
            if (totalTimeLabel != null) totalTimeLabel.Text = "00:00";
            
            totalPlaybackLength = 0;
            
            volumeProvider = null;
            meterProvider = null;
            isPaused = false;
            if (pauseButton != null) pauseButton.Enabled = false;
            if (playButton != null) playButton.Enabled = !string.IsNullOrEmpty(lastRecordedFilePath);
            
            if (!isClosing)
            {
                if (monitoringReadyRequested)
                {
                    StartMicrophoneLevelMonitoring();
                }
                UpdateStatus("Готов к записи", Color.FromArgb(0, 120, 215));
            }
        }

        private bool isClosing = false;

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (isClosing) return;
            isClosing = true;

            try
            {
                // Останавливаем таймеры первыми
                if (playbackTimer != null)
                {
                    playbackTimer.Stop();
                    playbackTimer.Tick -= PlaybackTimer_Tick;
                }
                
                if (recordingTimer != null)
                {
                    recordingTimer.Stop();
                    recordingTimer.Tick -= RecordingTimer_Tick;
                }
                
                // Останавливаем мониторинг
                StopMicrophoneLevelMonitoring();
                
                // Останавливаем воспроизведение
                if (waveOut != null)
                {
                    try
                    {
                        waveOut.Stop();
                        waveOut.Dispose();
                    }
                    catch { }
                    waveOut = null;
                }
                
                if (audioFileReader != null)
                {
                    try
                    {
                        audioFileReader.Dispose();
                    }
                    catch { }
                    audioFileReader = null;
                }
                
                // Останавливаем запись
                if (audioRecorder != null)
                {
                    try
                    {
                        if (audioRecorder.IsRecording)
                        {
                            audioRecorder.StopRecording();
                        }
                    }
                    catch { }
                    try
                    {
                        audioRecorder.Dispose();
                    }
                    catch { }
                }
                
                // Освобождаем enumerator
                if (deviceEnumerator != null)
                {
                    try
                    {
                        deviceEnumerator.Dispose();
                    }
                    catch { }
                }
            }
            catch { }
            
            // Отменяем закрытие если что-то пошло не так, но все равно закрываем
            e.Cancel = false;
            base.OnFormClosing(e);
            
            // Принудительно завершаем если что-то зависло
            Task.Run(async () =>
            {
                await Task.Delay(500);
                Environment.Exit(0);
            });
        }

        private async void MainForm_Shown(object? sender, EventArgs e)
        {
            if (monitoringReadyRequested)
            {
                return;
            }

            monitoringReadyRequested = true;
            // Увеличиваем задержку чтобы хук точно успел установиться
            await Task.Delay(800);
            if (!isClosing)
            {
                StartMicrophoneLevelMonitoring();
                UpdateStatus("Готов к записи", Color.FromArgb(0, 120, 215));
            }
        }

        private void MainForm_FormClosed(object? sender, FormClosedEventArgs e)
        {
            // Немедленно завершаем процесс без ожидания
            Task.Run(() =>
            {
                System.Threading.Thread.Sleep(100);
                Environment.Exit(0);
            });
        }

        private class WasapiDeviceItem
        {
            public WasapiDeviceItem(string id, string name, DeviceState state)
            {
                Id = id;
                Name = name;
                State = state;
            }

            public string Id { get; }
            public string Name { get; }
            public DeviceState State { get; }

            public override string ToString() => Name;
        }
    }
}

