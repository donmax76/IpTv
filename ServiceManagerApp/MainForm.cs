using System;
using System.ComponentModel;
using System.Drawing;
using System.IO;
using System.Linq;
using System.ServiceProcess;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Security.Cryptography;

namespace ServiceManagerApp
{
    public class MainForm : Form
    {
        private readonly JsonSerializerOptions _serializerOptions = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };

        private TextBox? _serviceNameTextBox;
        private Label? _serviceStatusLabel;
        private Button? _startButton;
        private Button? _stopButton;
        private Button? _restartButton;
        private Button? _refreshButton;

        private TextBox? _configPathTextBox;
        private Button? _browseConfigButton;
        private Button? _saveConfigButton;

        private NumericUpDown? _recordDurationNumeric;
        private NumericUpDown? _sampleRateNumeric;
        private NumericUpDown? _bitsPerSampleNumeric;
        private NumericUpDown? _channelsNumeric;
        private TrackBar? _microphoneVolumeTrackBar;
        private Label? _microphoneVolumeLabel;
        private TextBox? _outputFolderTextBox;
        private Button? _browseOutputButton;
        private ComboBox? _audioFormatComboBox;
        private NumericUpDown? _mp3BitrateNumeric;
        private CheckBox? _encryptAudioCheckBox;

        private Button? _healthCheckButton;
        private TextBox? _logTextBox;

        private ServiceConfigModel? _currentConfig;
        private string? _loadedConfigPath;

        // Те же ключ и IV, что и в сервисе AudioCore.Worker / AudioDecryptor
        private static readonly byte[] EncryptionKey = new byte[32]
        {
            0x3A, 0x7F, 0x21, 0x94, 0xC5, 0xD2, 0x6B, 0x11,
            0x8E, 0x4C, 0xF9, 0x53, 0x07, 0xB8, 0xDA, 0x62,
            0x19, 0xAF, 0x33, 0xE4, 0x5D, 0x70, 0x88, 0x9B,
            0xC1, 0x2E, 0x47, 0x6A, 0x8D, 0x90, 0xAB, 0xCD
        };

        private static readonly byte[] EncryptionIV = new byte[16]
        {
            0x12, 0x34, 0x56, 0x78,
            0x9A, 0xBC, 0xDE, 0xF0,
            0x0F, 0x1E, 0x2D, 0x3C,
            0x4B, 0x5A, 0x69, 0x78
        };

        public MainForm()
        {
            Text = "SilentMicService Control Center";
            MinimumSize = new Size(900, 640);
            StartPosition = FormStartPosition.CenterScreen;

            InitializeLayout();
            EnableConfigEditors(false);

            Load += async (_, _) =>
            {
                _serviceNameTextBox!.Text = string.IsNullOrWhiteSpace(_serviceNameTextBox.Text)
                    ? "AudioCoreService"
                    : _serviceNameTextBox.Text;

                if (string.IsNullOrWhiteSpace(_configPathTextBox!.Text))
                {
                    _configPathTextBox.Text = GuessDefaultConfigPath();
                }

                if (File.Exists(_configPathTextBox.Text))
                {
                    await LoadConfigAsync(showErrors: false);
                }
                else
                {
                    EnableConfigEditors(true);
                    _currentConfig = new ServiceConfigModel();
                    ApplyConfigToUi(_currentConfig);
                }

                await UpdateServiceStatusAsync(showErrors: false);
            };
        }

        private void InitializeLayout()
        {
            var root = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 4,
                Padding = new Padding(12),
                RowStyles =
                {
                    new RowStyle(SizeType.AutoSize),
                    new RowStyle(SizeType.AutoSize),
                    new RowStyle(SizeType.Percent, 60),
                    new RowStyle(SizeType.Percent, 40)
                }
            };

            root.Controls.Add(BuildServicePanel());
            root.Controls.Add(BuildConfigPathPanel());
            root.Controls.Add(BuildConfigEditorPanel());
            root.Controls.Add(BuildLogPanel());

            Controls.Add(root);
        }

        private Control BuildServicePanel()
        {
            var panel = new GroupBox
            {
                Text = "Управление службой",
                Dock = DockStyle.Top,
                AutoSize = true
            };

            var layout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 6,
                AutoSize = true
            };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            layout.Controls.Add(new Label
            {
                Text = "Имя сервиса:",
                AutoSize = true,
                Anchor = AnchorStyles.Left
            }, 0, 0);

            _serviceNameTextBox = new TextBox
            {
                Anchor = AnchorStyles.Left | AnchorStyles.Right,
                Text = "AudioCoreService",
                MinimumSize = new Size(200, 0)
            };
            layout.Controls.Add(_serviceNameTextBox, 1, 0);

            layout.Controls.Add(new Label
            {
                Text = "Статус:",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(16, 6, 4, 6)
            }, 2, 0);

            _serviceStatusLabel = new Label
            {
                AutoSize = true,
                ForeColor = Color.DarkGray,
                Text = "Неизвестно",
                Anchor = AnchorStyles.Left
            };
            layout.Controls.Add(_serviceStatusLabel, 3, 0);

            _refreshButton = new Button
            {
                Text = "Проверить",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(16, 2, 4, 2)
            };
            _refreshButton.Click += async (_, _) => await UpdateServiceStatusAsync();
            layout.Controls.Add(_refreshButton, 4, 0);

            _healthCheckButton = new Button
            {
                Text = "Диагностика",
                AutoSize = true,
                Anchor = AnchorStyles.Left
            };
            _healthCheckButton.Click += async (_, _) => await RunHealthCheckAsync();
            layout.Controls.Add(_healthCheckButton, 5, 0);

            var buttonsPanel = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                FlowDirection = FlowDirection.LeftToRight,
                AutoSize = true,
                Margin = new Padding(0, 8, 0, 0)
            };

            _startButton = new Button { Text = "Запустить", AutoSize = true };
            _startButton.Click += async (_, _) => await ControlServiceAsync(ServiceOperation.Start);
            buttonsPanel.Controls.Add(_startButton);

            _stopButton = new Button { Text = "Остановить", AutoSize = true };
            _stopButton.Click += async (_, _) => await ControlServiceAsync(ServiceOperation.Stop);
            buttonsPanel.Controls.Add(_stopButton);

            _restartButton = new Button { Text = "Перезапустить", AutoSize = true };
            _restartButton.Click += async (_, _) => await ControlServiceAsync(ServiceOperation.Restart);
            buttonsPanel.Controls.Add(_restartButton);

            layout.SetColumnSpan(buttonsPanel, 6);
            layout.Controls.Add(buttonsPanel, 0, 1);

            panel.Controls.Add(layout);
            return panel;
        }

        private Control BuildConfigPathPanel()
        {
            var panel = new GroupBox
            {
                Text = "Файл apts.sys",
                Dock = DockStyle.Top,
                AutoSize = true
            };

            var layout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 4,
                AutoSize = true
            };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

            layout.Controls.Add(new Label
            {
                Text = "Путь:",
                AutoSize = true,
                Anchor = AnchorStyles.Left
            }, 0, 0);

            _configPathTextBox = new TextBox
            {
                Anchor = AnchorStyles.Left | AnchorStyles.Right,
                MinimumSize = new Size(300, 0)
            };
            _configPathTextBox.Leave += async (_, _) => await LoadConfigAsync();
            layout.Controls.Add(_configPathTextBox, 1, 0);

            _browseConfigButton = new Button
            {
                Text = "Обзор...",
                AutoSize = true,
                Anchor = AnchorStyles.Left
            };
            _browseConfigButton.Click += async (_, _) => await BrowseForConfigAsync();
            layout.Controls.Add(_browseConfigButton, 2, 0);

            var decryptButton = new Button
            {
                Text = "DAT → MP3",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 0, 0, 0)
            };
            decryptButton.Click += async (_, _) => await ConvertDatToMp3Async();
            layout.Controls.Add(decryptButton, 3, 0);

            _saveConfigButton = new Button
            {
                Text = "Сохранить",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(0, 6, 0, 0)
            };
            _saveConfigButton.Click += async (_, _) => await SaveConfigAsync();

            layout.SetColumnSpan(_saveConfigButton, 3);
            layout.Controls.Add(_saveConfigButton, 0, 1);

            panel.Controls.Add(layout);
            return panel;
        }

        private Control BuildConfigEditorPanel()
        {
            var panel = new GroupBox
            {
                Text = "Параметры ServiceConfig",
                Dock = DockStyle.Fill
            };

            var layout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 4,
                RowCount = 5,
                AutoScroll = true
            };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

            int row = 0;

            // Диапазоны синхронизированы с ожиданиями сервиса:
            // DS: 1–600 сек
            AddNumericField(layout, ref row, "Длительность записи (сек)", 1, 600, out _recordDurationNumeric, 10);
            // SR: 8000–192000 Гц
            AddNumericField(layout, ref row, "Sample Rate (Гц)", 8000, 192000, out _sampleRateNumeric, 44100, 1000);
            // BPS: 8–32 бит
            AddNumericField(layout, ref row, "Разрядность (бит)", 8, 32, out _bitsPerSampleNumeric, 16, 2);
            // Ch: только 1 или 2 канала
            AddNumericField(layout, ref row, "Каналы", 1, 2, out _channelsNumeric, 2, 1);
            // MV: уровень микрофона 0.0–10.0 (х1 по умолчанию) - используем TrackBar
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            var microphoneVolumeLabel = new Label
            {
                Text = "Уровень микрофона (MV, x):",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 6, 4, 6)
            };
            layout.Controls.Add(microphoneVolumeLabel, 0, row);

            var microphoneVolumePanel = new Panel
            {
                Anchor = AnchorStyles.Left,
                AutoSize = true,
                Height = 50
            };
            var microphoneVolumeLayout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                RowCount = 1,
                AutoSize = true
            };
            microphoneVolumeLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            microphoneVolumeLayout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

            _microphoneVolumeTrackBar = new TrackBar
            {
                Minimum = 0,
                Maximum = 100, // 0-10 с шагом 0.1 (0, 0.1, 0.2, ..., 10.0)
                Value = 10, // 1.0 по умолчанию (10 * 0.1)
                TickFrequency = 10, // Каждые 1.0
                Width = 200,
                Anchor = AnchorStyles.Left
            };
            _microphoneVolumeLabel = new Label
            {
                Text = "1.0",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(8, 0, 0, 0),
                Width = 50
            };
            _microphoneVolumeTrackBar.ValueChanged += (_, _) =>
            {
                double value = _microphoneVolumeTrackBar.Value / 10.0;
                _microphoneVolumeLabel.Text = value.ToString("F1");
            };

            microphoneVolumeLayout.Controls.Add(_microphoneVolumeTrackBar, 0, 0);
            microphoneVolumeLayout.Controls.Add(_microphoneVolumeLabel, 1, 0);
            microphoneVolumePanel.Controls.Add(microphoneVolumeLayout);
            layout.Controls.Add(microphoneVolumePanel, 1, row);
            row++;

            AddPathField(layout, ref row, "Папка для записей", out _outputFolderTextBox, out _browseOutputButton);
            _browseOutputButton.Click += (_, _) => BrowseForFolder(_outputFolderTextBox);

            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            var audioFormatLabel = new Label
            {
                Text = "Формат аудио:",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 6, 4, 6)
            };
            layout.Controls.Add(audioFormatLabel, 0, row);

            _audioFormatComboBox = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Anchor = AnchorStyles.Left,
                Width = 150
            };
            _audioFormatComboBox.Items.AddRange(new object[] { "mp3", "wav" });
            _audioFormatComboBox.SelectedIndexChanged += (_, _) => UpdateMp3BitrateState();
            _audioFormatComboBox.SelectedIndex = 0;
            layout.Controls.Add(_audioFormatComboBox, 1, row);
            row++;

            // MP3 битрейт: 32–320 кбит/с
            AddNumericField(layout, ref row, "MP3 битрейт (кбит/с)", 32, 320, out _mp3BitrateNumeric, 192, 16);

            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            _encryptAudioCheckBox = new CheckBox
            {
                Text = "Шифровать аудио (ENC=0)",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 6, 4, 6)
            };
            _encryptAudioCheckBox.CheckedChanged += (_, _) =>
            {
                _encryptAudioCheckBox.Text = _encryptAudioCheckBox.Checked 
                    ? "Шифровать аудио (ENC=1)" 
                    : "Шифровать аудио (ENC=0)";
            };
            layout.SetColumnSpan(_encryptAudioCheckBox, 2);
            layout.Controls.Add(_encryptAudioCheckBox, 0, row);

            panel.Controls.Add(layout);
            return panel;
        }

        private Control BuildLogPanel()
        {
            var panel = new GroupBox
            {
                Text = "Журнал",
                Dock = DockStyle.Fill
            };

            _logTextBox = new TextBox
            {
                Multiline = true,
                Dock = DockStyle.Fill,
                ScrollBars = ScrollBars.Vertical,
                ReadOnly = true,
                BackColor = Color.Black,
                ForeColor = Color.LightGreen
            };

            panel.Controls.Add(_logTextBox);
            return panel;
        }

        private void AddNumericField(TableLayoutPanel layout, ref int row, string labelText, int min, int max,
            out NumericUpDown control, int defaultValue, int increment = 5)
        {
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            var label = new Label
            {
                Text = labelText + ":",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 6, 4, 6)
            };
            layout.Controls.Add(label, 0, row);

            control = new NumericUpDown
            {
                Minimum = min,
                Maximum = max,
                Value = defaultValue,
                Increment = increment,
                Anchor = AnchorStyles.Left | AnchorStyles.Right,
                ThousandsSeparator = true,
                Width = 120
            };
            layout.Controls.Add(control, 1, row);

            row++;
        }

        private void AddPathField(TableLayoutPanel layout, ref int row, string labelText,
            out TextBox textBox, out Button browseButton)
        {
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            var label = new Label
            {
                Text = labelText + ":",
                AutoSize = true,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(4, 6, 4, 6)
            };
            layout.Controls.Add(label, 0, row);

            textBox = new TextBox
            {
                Anchor = AnchorStyles.Left | AnchorStyles.Right,
                MinimumSize = new Size(200, 0)
            };
            layout.Controls.Add(textBox, 1, row);

            browseButton = new Button
            {
                Text = "Обзор...",
                AutoSize = true,
                Anchor = AnchorStyles.Left
            };
            layout.Controls.Add(browseButton, 2, row);

            row++;
        }

        private async Task BrowseForConfigAsync()
        {
            using var dialog = new OpenFileDialog
            {
                Title = "Select apts.sys",
                Filter = "Config file|apts.sys;*.sys;*.json|All files|*.*",
                CheckFileExists = true,
                FileName = "apts.sys"
            };

            if (File.Exists(_configPathTextBox?.Text))
            {
                dialog.InitialDirectory = Path.GetDirectoryName(_configPathTextBox!.Text);
                dialog.FileName = Path.GetFileName(_configPathTextBox.Text);
            }

            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                _configPathTextBox!.Text = dialog.FileName;
                await LoadConfigAsync();
            }
        }

        private void BrowseForFolder(TextBox? target)
        {
            if (target == null)
            {
                return;
            }

            using var dialog = new FolderBrowserDialog
            {
                Description = "Выберите папку",
                ShowNewFolderButton = true
            };

            if (Directory.Exists(target.Text))
            {
                dialog.SelectedPath = target.Text;
            }

            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                target.Text = dialog.SelectedPath;
            }
        }

        private async Task UpdateServiceStatusAsync(bool showErrors = true)
        {
            if (_serviceStatusLabel == null || _serviceNameTextBox == null)
            {
                return;
            }

            string serviceName = _serviceNameTextBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(serviceName))
            {
                _serviceStatusLabel.Text = "Имя сервиса не указано";
                _serviceStatusLabel.ForeColor = Color.DarkGray;
                return;
            }

            try
            {
                var status = await Task.Run(() =>
                {
                    using var controller = new ServiceController(serviceName);
                    controller.Refresh();
                    return controller.Status;
                });

                UpdateStatusLabel(status);
                AppendLog($"Статус {serviceName}: {status}");
            }
            catch (InvalidOperationException ex) when (ex.InnerException is Win32Exception { NativeErrorCode: 1060 })
            {
                _serviceStatusLabel.Text = "Служба не установлена";
                _serviceStatusLabel.ForeColor = Color.OrangeRed;
                if (showErrors)
                {
                    MessageBox.Show(this,
                        $"Служба '{serviceName}' не найдена. Установите её через install.bat.",
                        "Служба не найдена",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                }
            }
            catch (Exception ex)
            {
                _serviceStatusLabel.Text = "Ошибка";
                _serviceStatusLabel.ForeColor = Color.Red;
                if (showErrors)
                {
                    MessageBox.Show(this,
                        $"Не удалось получить статус сервиса: {ex.Message}",
                        "Ошибка",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                }
            }
        }

        private async Task ControlServiceAsync(ServiceOperation operation)
        {
            if (_serviceNameTextBox == null || string.IsNullOrWhiteSpace(_serviceNameTextBox.Text))
            {
                MessageBox.Show(this, "Укажите имя сервиса.", "Требуется имя", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return;
            }

            ToggleServiceButtons(false);
            string serviceName = _serviceNameTextBox.Text.Trim();

            try
            {
                string message = await Task.Run(() => ExecuteServiceOperation(serviceName, operation));
                AppendLog(message);
            }
            catch (InvalidOperationException ex) when (ex.InnerException is Win32Exception { NativeErrorCode: 1060 })
            {
                MessageBox.Show(this,
                    $"Служба '{serviceName}' не найдена. Установите её перед управлением.",
                    "Служба не найдена",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            catch (InvalidOperationException ex) when (ex.InnerException is Win32Exception { NativeErrorCode: 5 })
            {
                ShowAccessDeniedMessage(ex);
            }
            catch (Win32Exception ex) when (ex.NativeErrorCode == 5)
            {
                ShowAccessDeniedMessage(ex);
            }
            catch (System.TimeoutException)
            {
                MessageBox.Show(this,
                    "Сервис не успел изменить состояние за отведённое время.",
                    "Таймаут",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this,
                    $"Не удалось выполнить операцию: {ex.Message}",
                    "Ошибка",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
            finally
            {
                ToggleServiceButtons(true);
                await UpdateServiceStatusAsync(showErrors: false);
            }
        }

        private string ExecuteServiceOperation(string serviceName, ServiceOperation operation)
        {
            using var controller = new ServiceController(serviceName);
            controller.Refresh();

            switch (operation)
            {
                case ServiceOperation.Start:
                    if (controller.Status == ServiceControllerStatus.Running)
                    {
                        return $"Служба '{serviceName}' уже запущена.";
                    }
                    controller.Start();
                    controller.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(25));
                    return $"Служба '{serviceName}' успешно запущена.";

                case ServiceOperation.Stop:
                    if (controller.Status == ServiceControllerStatus.Stopped ||
                        controller.Status == ServiceControllerStatus.StopPending)
                    {
                        return $"Служба '{serviceName}' уже остановлена.";
                    }
                    controller.Stop();
                    controller.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(25));
                    return $"Служба '{serviceName}' успешно остановлена.";

                case ServiceOperation.Restart:
                    if (controller.Status != ServiceControllerStatus.Stopped &&
                        controller.Status != ServiceControllerStatus.StopPending)
                    {
                        controller.Stop();
                        controller.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(25));
                    }
                    controller.Start();
                    controller.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(25));
                    return $"Служба '{serviceName}' перезапущена.";

                default:
                    return "Неизвестная операция";
            }
        }

        private async Task LoadConfigAsync(bool showErrors = true)
        {
            if (_configPathTextBox == null)
            {
                return;
            }

            string path = _configPathTextBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(path))
            {
                if (showErrors)
                {
                    MessageBox.Show(this, "Укажите путь до apts.sys.", "Путь не задан",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                return;
            }

            if (!File.Exists(path))
            {
                if (showErrors)
                {
                    MessageBox.Show(this, $"Файл не найден:\n{path}", "Файл отсутствует",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
                return;
            }

            try
            {
                string json = await File.ReadAllTextAsync(path);
                var model = JsonSerializer.Deserialize<RootConfigForService>(json, _serializerOptions);

                if (model?.SrvC == null)
                {
                    throw new InvalidDataException("В файле отсутствует секция SrvC.");
                }

                _currentConfig = MapFromSrvC(model.SrvC);
                _loadedConfigPath = path;
                ApplyConfigToUi(_currentConfig);
                EnableConfigEditors(true);
                AppendLog($"Конфигурация загружена из {path}");
            }
            catch (Exception ex)
            {
                if (showErrors)
                {
                    MessageBox.Show(this,
                        $"Не удалось загрузить конфигурацию: {ex.Message}",
                        "Ошибка чтения",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                }
            }
        }

        private async Task SaveConfigAsync()
        {
            if (_configPathTextBox == null)
            {
                return;
            }

            string path = _configPathTextBox.Text.Trim();
            if (string.IsNullOrWhiteSpace(path))
            {
                MessageBox.Show(this, "Сначала укажите путь до apts.sys.", "Путь не указан",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            if (_currentConfig == null)
            {
                _currentConfig = new ServiceConfigModel();
            }

            _currentConfig = CollectConfigFromUi();
            var srvConfig = MapToSrvC(_currentConfig);
            var wrapper = new RootConfigForService { SrvC = srvConfig };

            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                string json = JsonSerializer.Serialize(wrapper, _serializerOptions);
                await File.WriteAllTextAsync(path, json);
                _loadedConfigPath = path;
                AppendLog($"Конфигурация сохранена в {path}");
                MessageBox.Show(this, "Изменения сохранены.", "Успех",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this,
                    $"Не удалось сохранить файл: {ex.Message}",
                    "Ошибка записи",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        private async Task RunHealthCheckAsync()
        {
            await UpdateServiceStatusAsync();

            if (_currentConfig == null)
            {
                AppendLog("Диагностика: конфигурация не загружена.");
                return;
            }

            var issues = new System.Collections.Generic.List<string>();

            if (!Directory.Exists(_currentConfig.OutputFolder))
            {
                issues.Add($"Папка записей отсутствует: {_currentConfig.OutputFolder}");
            }

            if (issues.Count == 0)
            {
                AppendLog("Диагностика: проблем не обнаружено.");
            }
            else
            {
                foreach (var issue in issues)
                {
                    AppendLog("Диагностика: " + issue);
                }
                MessageBox.Show(this,
                    string.Join(Environment.NewLine, issues),
                    "Обнаружены проблемы",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
        }

        private void ApplyConfigToUi(ServiceConfigModel config)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => ApplyConfigToUi(config)));
                return;
            }

            _recordDurationNumeric!.Value = Clamp(config.RecordingDurationSeconds,
                (int)_recordDurationNumeric.Minimum, (int)_recordDurationNumeric.Maximum);
            _sampleRateNumeric!.Value = Clamp(config.SampleRate,
                (int)_sampleRateNumeric.Minimum, (int)_sampleRateNumeric.Maximum);
            _bitsPerSampleNumeric!.Value = Clamp(config.BitsPerSample,
                (int)_bitsPerSampleNumeric.Minimum, (int)_bitsPerSampleNumeric.Maximum);
            _channelsNumeric!.Value = Clamp(config.Channels,
                (int)_channelsNumeric.Minimum, (int)_channelsNumeric.Maximum);
            _outputFolderTextBox!.Text = config.OutputFolder;
            SetAudioFormatSelection(config.AudioFormat);
            _mp3BitrateNumeric!.Value = Clamp(config.Mp3Bitrate,
                (int)_mp3BitrateNumeric.Minimum, (int)_mp3BitrateNumeric.Maximum);
            if (_microphoneVolumeTrackBar != null && _microphoneVolumeLabel != null)
            {
                double mv = config.MicrophoneVolume;
                if (mv < 0) mv = 0;
                if (mv > 10) mv = 10;
                _microphoneVolumeTrackBar.Value = (int)(mv * 10); // Конвертируем в целое (0-100)
                _microphoneVolumeLabel.Text = mv.ToString("F1");
            }
            if (_encryptAudioCheckBox != null)
            {
                _encryptAudioCheckBox.Checked = config.EncryptAudio;
                // Обновляем текст чекбокса
                _encryptAudioCheckBox.Text = config.EncryptAudio 
                    ? "Шифровать аудио (ENC=1)" 
                    : "Шифровать аудио (ENC=0)";
            }
            UpdateMp3BitrateState();
        }

        private ServiceConfigModel CollectConfigFromUi()
        {
            return new ServiceConfigModel
            {
                RecordingDurationSeconds = (int)_recordDurationNumeric!.Value,
                SampleRate = (int)_sampleRateNumeric!.Value,
                BitsPerSample = (int)_bitsPerSampleNumeric!.Value,
                Channels = (int)_channelsNumeric!.Value,
                OutputFolder = _outputFolderTextBox!.Text.Trim(),
                AudioFormat = (_audioFormatComboBox?.SelectedItem as string) ?? "mp3",
                Mp3Bitrate = (int)_mp3BitrateNumeric!.Value,
                MicrophoneVolume = _microphoneVolumeTrackBar != null ? (double)_microphoneVolumeTrackBar.Value / 10.0 : 1.0,
                EncryptAudio = _encryptAudioCheckBox?.Checked ?? false
            };
        }

        private void EnableConfigEditors(bool enabled)
        {
            foreach (var control in new Control?[]
            {
                _recordDurationNumeric,
                _sampleRateNumeric,
                _bitsPerSampleNumeric,
                _channelsNumeric,
                _microphoneVolumeTrackBar,
                _microphoneVolumeLabel,
                _outputFolderTextBox,
                _browseOutputButton,
                _audioFormatComboBox,
                _mp3BitrateNumeric,
                _encryptAudioCheckBox,
                _saveConfigButton
            })
            {
                if (control != null)
                {
                    control.Enabled = enabled;
                }
            }

            UpdateMp3BitrateState();
        }

        private void ToggleServiceButtons(bool enabled)
        {
            foreach (var button in new[] { _startButton, _stopButton, _restartButton, _refreshButton, _healthCheckButton })
            {
                if (button != null)
                {
                    button.Enabled = enabled;
                }
            }
        }

        private void UpdateStatusLabel(ServiceControllerStatus status)
        {
            if (_serviceStatusLabel == null)
            {
                return;
            }

            _serviceStatusLabel.Text = status switch
            {
                ServiceControllerStatus.Running => "Работает",
                ServiceControllerStatus.Stopped => "Остановлен",
                ServiceControllerStatus.Paused => "Приостановлен",
                ServiceControllerStatus.StartPending => "Запускается...",
                ServiceControllerStatus.StopPending => "Останавливается...",
                ServiceControllerStatus.PausePending => "Пауза...",
                ServiceControllerStatus.ContinuePending => "Возобновляется...",
                _ => status.ToString()
            };

            _serviceStatusLabel.ForeColor = status switch
            {
                ServiceControllerStatus.Running => Color.ForestGreen,
                ServiceControllerStatus.Stopped => Color.OrangeRed,
                ServiceControllerStatus.StartPending => Color.Goldenrod,
                ServiceControllerStatus.StopPending => Color.Goldenrod,
                _ => Color.DarkGray
            };
        }

        private void AppendLog(string message)
        {
            if (_logTextBox == null)
            {
                return;
            }

            string line = $"{DateTime.Now:HH:mm:ss} | {message}";
            _logTextBox.AppendText(line + Environment.NewLine);
        }

        private static int Clamp(int value, int min, int max)
        {
            return Math.Max(min, Math.Min(max, value));
        }

        private void SetAudioFormatSelection(string? format)
        {
            if (_audioFormatComboBox == null)
            {
                return;
            }

            string desired = string.IsNullOrWhiteSpace(format) ? "mp3" : format;
            string? matching = _audioFormatComboBox.Items
                .Cast<object>()
                .Select(item => item.ToString() ?? string.Empty)
                .FirstOrDefault(item => item.Equals(desired, StringComparison.OrdinalIgnoreCase));

            if (matching == null)
            {
                _audioFormatComboBox.Items.Add(desired);
                matching = desired;
            }

            _audioFormatComboBox.SelectedItem = matching;
            UpdateMp3BitrateState();
        }

        private void UpdateMp3BitrateState()
        {
            if (_mp3BitrateNumeric == null || _audioFormatComboBox == null)
            {
                return;
            }

            bool isMp3 = string.Equals(_audioFormatComboBox.SelectedItem as string, "mp3",
                StringComparison.OrdinalIgnoreCase);
            bool editorsEnabled = _recordDurationNumeric?.Enabled ?? true;
            _mp3BitrateNumeric.Enabled = isMp3 && editorsEnabled;
        }

        private void ShowAccessDeniedMessage(Exception ex)
        {
            MessageBox.Show(this,
                "Недостаточно прав для управления службой.\n" +
                "Запустите ServiceManagerApp от имени администратора или выполните действие из административной консоли.",
                "Доступ запрещён",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            AppendLog($"Нет прав управления службой: {ex.Message}");
        }

        private string GuessDefaultConfigPath()
        {
            string[] guesses =
            {
                @"C:\Windows\System32\drivers\apts.sys",
                Path.Combine(AppContext.BaseDirectory, "apts.sys"),
                Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "apts.sys"),
                Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "SilentMicRecorder", "apts.sys"),
                Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "SilentMicRecorder", "apts.sys")
            };

            foreach (var guess in guesses)
            {
                try
                {
                    var full = Path.GetFullPath(guess);
                    if (File.Exists(full))
                    {
                        return full;
                    }
                }
                catch
                {
                    // ignore path errors
                }
            }

            return guesses[^1];
        }

        private enum ServiceOperation
        {
            Start,
            Stop,
            Restart
        }

        private sealed class RootConfigForService
        {
            [JsonPropertyName("SrvC")]
            public SrvCConfig SrvC { get; set; } = new();
        }

        private sealed class SrvCConfig
        {
            [JsonPropertyName("DS")]
            public int DS { get; set; } = 10;

            [JsonPropertyName("OF")]
            public string OF { get; set; } = @"d:\SilentRecord";

            [JsonPropertyName("SR")]
            public int SR { get; set; } = 44100;

            [JsonPropertyName("BPS")]
            public int BPS { get; set; } = 16;

            [JsonPropertyName("Ch")]
            public int Ch { get; set; } = 2;

            [JsonPropertyName("AF")]
            public int AF { get; set; } = 1; // 0 = wav, 1 = mp3

            [JsonPropertyName("MV")]
            public double MV { get; set; } = 1.0;

            [JsonPropertyName("ENC")]
            public int ENC { get; set; } = 0; // 0 = без шифрования, 1 = шифровать MP3
        }

        private sealed class ServiceConfigModel
        {
            public int RecordingDurationSeconds { get; set; } = 10;
            public string OutputFolder { get; set; } = @"D:\MyRecordings";
            public int SampleRate { get; set; } = 44100;
            public int BitsPerSample { get; set; } = 16;
            public int Channels { get; set; } = 2;
            public string AudioFormat { get; set; } = "mp3";
            public int Mp3Bitrate { get; set; } = 192;
            public double MicrophoneVolume { get; set; } = 1.0;
            public bool EncryptAudio { get; set; } = false;
        }

        private static ServiceConfigModel MapFromSrvC(SrvCConfig src)
        {
            return new ServiceConfigModel
            {
                RecordingDurationSeconds = src.DS,
                OutputFolder = src.OF,
                SampleRate = src.SR,
                BitsPerSample = src.BPS,
                Channels = src.Ch,
                AudioFormat = src.AF == 1 ? "mp3" : "wav",
                Mp3Bitrate = 192,
                MicrophoneVolume = src.MV,
                EncryptAudio = src.ENC == 1
            };
        }

        private static SrvCConfig MapToSrvC(ServiceConfigModel cfg)
        {
            return new SrvCConfig
            {
                DS = cfg.RecordingDurationSeconds,
                OF = cfg.OutputFolder,
                SR = cfg.SampleRate,
                BPS = cfg.BitsPerSample,
                Ch = cfg.Channels,
                AF = string.Equals(cfg.AudioFormat, "mp3", StringComparison.OrdinalIgnoreCase) ? 1 : 0,
                MV = cfg.MicrophoneVolume,
                ENC = cfg.EncryptAudio ? 1 : 0
            };
        }

        private async Task ConvertDatToMp3Async()
        {
            using var dialog = new OpenFileDialog
            {
                Title = "Выберите зашифрованный файл для расшифровки",
                Filter = "Encrypted audio (*.dat;*)|*.dat;*|All files (*.*)|*.*",
                CheckFileExists = true
            };

            if (dialog.ShowDialog(this) != DialogResult.OK)
            {
                return;
            }

            string inputPath = dialog.FileName;
            string inputFileName = Path.GetFileName(inputPath);
            string inputExt = Path.GetExtension(inputPath);
            
            // Если файл уже имеет расширение .dat - расшифровываем напрямую
            bool isDirectDatFile = inputExt.Equals(".dat", StringComparison.OrdinalIgnoreCase);
            
            string? decryptedFileName = null;
            string renamedPath = inputPath;
            
            if (!isDirectDatFile)
            {
                // Шаг 1: Расшифровываем имя файла
                decryptedFileName = await Task.Run(() => DecryptFileName(inputFileName));
                
                if (decryptedFileName == null)
                {
                    MessageBox.Show(this,
                        "Не удалось расшифровать имя файла.",
                        "Ошибка",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                    return;
                }

                // Шаг 2: Переименовываем файл в расшифрованное имя
                renamedPath = Path.Combine(Path.GetDirectoryName(inputPath) ?? "", decryptedFileName);
                AppendLog($"Расшифрованное имя файла: {decryptedFileName}");
                
                if (File.Exists(renamedPath) && !inputPath.Equals(renamedPath, StringComparison.OrdinalIgnoreCase))
                {
                    File.Delete(renamedPath);
                }
                File.Move(inputPath, renamedPath);
                AppendLog($"Файл переименован: {inputFileName} -> {decryptedFileName}");
            }
            else
            {
                // Файл уже .dat - используем его как есть
                AppendLog($"Обнаружен .dat файл: {inputFileName}");
                decryptedFileName = inputFileName;
            }

            // Шаг 3: Проверяем расширение в переименованном файле
            string decryptedExt = Path.GetExtension(renamedPath);
            bool isContentEncrypted = decryptedExt.Equals(".dat", StringComparison.OrdinalIgnoreCase);

            try
            {
                if (isContentEncrypted)
                {
                    // Расширение .dat - расшифровываем содержимое файла
                    // Заменяем .dat на .mp3 для выходного файла
                    string finalOutputPath = Path.ChangeExtension(renamedPath, ".mp3");
                    
                    AppendLog($"========================================");
                    AppendLog($"Расшифровка содержимого файла...");
                    AppendLog($"Входной файл: {renamedPath}");
                    AppendLog($"Выходной файл: {finalOutputPath}");
                    AppendLog($"========================================");
                    
                    // Удаляем выходной файл, если он существует
                    if (File.Exists(finalOutputPath))
                    {
                        File.Delete(finalOutputPath);
                    }
                    
                    // Расшифровываем содержимое
                    await Task.Run(() => DecryptDatFile(renamedPath, finalOutputPath));
                    
                    // Удаляем исходный .dat файл после успешной расшифровки
                    try
                    {
                        File.Delete(renamedPath);
                        AppendLog($"Исходный .dat файл удалён: {Path.GetFileName(renamedPath)}");
                    }
                    catch (Exception delEx)
                    {
                        AppendLog($"Не удалось удалить исходный .dat файл: {delEx.Message}");
                    }
                    
                    AppendLog($"Расшифрованный файл создан: {Path.GetFileName(finalOutputPath)}");
                    AppendLog($"Файл расшифрован: {finalOutputPath}");
                    
                    MessageBox.Show(this,
                        $"Файл успешно обработан:\n{finalOutputPath}",
                        "Готово",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information);
                }
                else
                {
                    // Расширение .mp3 или .wav - файл уже переименован, ничего не делаем
                    AppendLog($"Файл переименован: {renamedPath}");
                    
                    MessageBox.Show(this,
                        $"Файл успешно обработан:\n{renamedPath}",
                        "Готово",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information);
                }
            }
            catch (Exception ex)
            {
                AppendLog($"Ошибка обработки файла: {ex.Message}");
                MessageBox.Show(this,
                    $"Ошибка при обработке файла:\n{ex.Message}",
                    "Ошибка",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        /// <summary>
        /// Расшифровывает имя файла из зашифрованного Base64 строки
        /// </summary>
        private static string? DecryptFileName(string encryptedFileName)
        {
            try
            {
                // Восстанавливаем Base64 формат
                string base64 = encryptedFileName.Replace('_', '/').Replace('-', '+');
                // Добавляем padding если нужно
                int mod4 = base64.Length % 4;
                if (mod4 != 0)
                    base64 += new string('=', 4 - mod4);

                byte[] encrypted = Convert.FromBase64String(base64);

                using var aes = Aes.Create();
                if (aes == null)
                    return null;

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var decryptor = aes.CreateDecryptor();
                byte[] decryptedName = decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
                return System.Text.Encoding.UTF8.GetString(decryptedName);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Читает оригинальное имя файла из начала файла (зашифрованное в метаданных)
        /// </summary>
        private static string? ReadOriginalFileName(string inputPath)
        {
            try
            {
                using var aes = Aes.Create();
                if (aes == null)
                    return null;

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var input = new FileStream(inputPath, FileMode.Open, FileAccess.Read, FileShare.Read);
                
                // Читаем длину зашифрованного имени (4 байта)
                byte[] lengthBytes = new byte[4];
                int bytesRead = input.Read(lengthBytes, 0, 4);
                if (bytesRead != 4)
                    return null;

                int nameLength = BitConverter.ToInt32(lengthBytes, 0);
                if (nameLength <= 0 || nameLength > 1024) // Защита от некорректных данных
                    return null;

                // Читаем зашифрованное имя
                byte[] encryptedName = new byte[nameLength];
                bytesRead = input.Read(encryptedName, 0, nameLength);
                if (bytesRead != nameLength)
                    return null;

                // Расшифровываем имя
                using var decryptor = aes.CreateDecryptor();
                byte[] decryptedName = decryptor.TransformFinalBlock(encryptedName, 0, encryptedName.Length);
                return System.Text.Encoding.UTF8.GetString(decryptedName);
            }
            catch
            {
                // Возвращаем null при ошибке - файл может быть без метаданных
                return null;
            }
        }

        private static void DecryptDatFile(string inputPath, string outputPath)
        {
            try
            {
                using var input = new FileStream(inputPath, FileMode.Open, FileAccess.Read, FileShare.Read);
                using var output = new FileStream(outputPath, FileMode.Create, FileAccess.Write, FileShare.None);
                
                // Расшифровка содержимого файла
                using var aes = Aes.Create();
                if (aes == null)
                    throw new InvalidOperationException("Не удалось создать AES-провайдер.");

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var crypto = new CryptoStream(input, aes.CreateDecryptor(), CryptoStreamMode.Read);
                crypto.CopyTo(output);
                output.Flush();
            }
            catch (Exception ex)
            {
                throw new Exception($"Ошибка при расшифровке содержимого файла: {ex.Message}", ex);
            }
        }
    }
}

