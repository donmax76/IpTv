using System;
using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using RemoteDesktopClient.Utils;

namespace RemoteDesktopClient.Services
{
    /// <summary>
    /// Сервис для обработки потока экрана
    /// Получает кадры экрана от хоста и преобразует их в изображения для отображения
    /// </summary>
    public class ScreenStreamService
    {
        private readonly ConcurrentQueue<byte[]> _frameQueue = new ConcurrentQueue<byte[]>();
        private readonly object _lock = new object();
        private volatile bool _isStreaming = false;
        private DateTime _lastFrameTime = DateTime.MinValue;
        private Task _processingTask = null;
        private CancellationTokenSource _cancellationTokenSource = new CancellationTokenSource();

        /// <summary>
        /// Событие получения нового кадра экрана
        /// </summary>
        public event EventHandler<Image> FrameReceived;

        /// <summary>
        /// Максимальный размер очереди кадров (предотвращает переполнение памяти)
        /// </summary>
        public int MaxQueueSize { get; set; } = 8;  // Малая очередь — не накапливать старые кадры (прыжки ~40 сек)

        /// <summary>
        /// Включает или выключает поток экрана
        /// </summary>
        public bool IsStreaming
        {
            get { return _isStreaming; }
            set
            {
                bool startLoop = false;
                CancellationToken token = default;
                lock (_lock)
                {
                    bool wasStreaming = _isStreaming;
                    _isStreaming = value;
                    
                    if (value && !wasStreaming)
                    {
                        if (_cancellationTokenSource != null)
                        {
                            try { _cancellationTokenSource.Cancel(); } catch (Exception ex) { Logger.LogWarning($"Cancel CTS: {ex.Message}"); }
                            try { _cancellationTokenSource.Dispose(); } catch (Exception ex) { Logger.LogWarning($"Dispose CTS: {ex.Message}"); }
                        }
                        _cancellationTokenSource = new CancellationTokenSource();
                        token = _cancellationTokenSource.Token;
                        startLoop = (_processingTask == null || _processingTask.IsCompleted);
                    }
                    else if (!value && wasStreaming)
                    {
                        if (_cancellationTokenSource != null)
                        {
                            try { _cancellationTokenSource.Cancel(); } catch (Exception ex) { Logger.LogWarning($"Cancel CTS on stream stop: {ex.Message}"); }
                        }
                        while (_frameQueue.TryDequeue(out _)) { }
                        FrameReceived?.Invoke(this, null);
                    }
                }
                // КРИТИЧНО: Запускаем цикл в фоне (Task.Run), иначе он выполняется на UI-потоке до первого await и при пустой очереди зависает форма.
                if (startLoop)
                    _processingTask = Task.Run(() => ProcessFrameQueueLoop(token));
            }
        }

        /// <summary>
        /// Обрабатывает бинарные данные кадра экрана
        /// Добавляет кадр в очередь для обработки
        /// </summary>
        /// <param name="frameData">Бинарные данные кадра (JPEG/PNG)</param>
        public void HandleFrameData(byte[] frameData)
        {
            if (!IsStreaming)
                return;

            // Простая очередь FIFO: при переполнении удаляем один старый кадр (без агрессивного выкидывания — иначе прыжки старых/новых).
            if (_frameQueue.Count >= MaxQueueSize)
            {
                byte[] oldFrame;
                _frameQueue.TryDequeue(out oldFrame);
            }
            _frameQueue.Enqueue(frameData);
            _lastFrameTime = DateTime.Now;
        }

        /// <summary>
        /// Фиксированный интервал отображения (~20 FPS) — стабильный FPS, без прыжков на секунду.
        /// Всегда показываем последний кадр из очереди, чтобы убрать задержку.
        /// </summary>
        private static readonly int DisplayIntervalMs = 50; // 20 FPS — стабильно, без рывков

        private async Task ProcessFrameQueueLoop(CancellationToken cancellationToken)
        {
            var nextDisplayUtc = DateTime.UtcNow;
            while (!cancellationToken.IsCancellationRequested && IsStreaming)
            {
                try
                {
                    byte[] frame = null;
                    // Ждём до следующего тика отображения — стабильный FPS без прыжков
                    var now = DateTime.UtcNow;
                    if (now < nextDisplayUtc)
                    {
                        var waitMs = (int)Math.Max(1, (nextDisplayUtc - now).TotalMilliseconds);
                        await Task.Delay(Math.Min(waitMs, DisplayIntervalMs), cancellationToken).ConfigureAwait(false);
                        continue;
                    }
                    nextDisplayUtc = now.AddMilliseconds(DisplayIntervalMs);

                    // Всегда берём только последний кадр — убираем задержку в секунду
                    while (_frameQueue.Count > 1)
                        _frameQueue.TryDequeue(out _);
                    _frameQueue.TryDequeue(out frame);

                    if (frame != null && IsStreaming)
                    {
                        try
                        {
                            // Преобразуем бинарные данные в изображение
                            Bitmap bitmap = null;
                            try
                            {
                                // КРИТИЧНО: Используем using для автоматического освобождения MemoryStream
                                using (MemoryStream ms = new MemoryStream(frame))
                                {
                                    // КРИТИЧНО: Используем using для автоматического освобождения Image
                                    using (Image image = Image.FromStream(ms))
                                    {
                                        // Создаем копию изображения для передачи в UI поток
                                        bitmap = new Bitmap(image);
                                    }  // Image автоматически освобождается здесь
                                }  // MemoryStream автоматически освобождается здесь
                                
                                // При большом окне UI не успевает рисовать — масштабируем кадр в фоне до макс. 1920x1080, чтобы ускорить отрисовку
                                const int MaxDisplayW = 1920, MaxDisplayH = 1080;
                                if (bitmap != null && (bitmap.Width > MaxDisplayW || bitmap.Height > MaxDisplayH))
                                {
                                    int w = bitmap.Width, h = bitmap.Height;
                                    if (w > MaxDisplayW) { h = (int)(h * (double)MaxDisplayW / w); w = MaxDisplayW; }
                                    if (h > MaxDisplayH) { w = (int)(w * (double)MaxDisplayH / h); h = MaxDisplayH; }
                                    var scaled = new Bitmap(w, h);
                                    using (var g = Graphics.FromImage(scaled))
                                    {
                                        g.InterpolationMode = InterpolationMode.Low;
                                        g.DrawImage(bitmap, 0, 0, w, h);
                                    }
                                    bitmap.Dispose();
                                    bitmap = scaled;
                                }
                                
                                if (bitmap != null && IsStreaming)
                                {
                                    FrameReceived?.Invoke(this, bitmap);
                                    // КРИТИЧНО: НЕ освобождаем bitmap здесь - он будет освобожден в DisplayFrame
                                }
                                else
                                {
                                    // Стрим остановлен или bitmap null - освобождаем ресурсы
                                    bitmap?.Dispose();
                                    bitmap = null;
                                }
                            }
                            catch (Exception ex)
                            {
                                // КРИТИЧНО: Освобождаем bitmap в случае ошибки для предотвращения memory leak
                                if (bitmap != null)
                                {
                                    try
                                    {
                                        bitmap.Dispose();
                                    }
                                    catch (Exception exDispose)
                                    {
                                        Logger.LogWarning($"Bitmap Dispose: {exDispose.Message}");
                                    }
                                    bitmap = null;
                                }
                                // Логируем ошибку, но не прерываем обработку других кадров
                                Logger.LogError("Error processing frame", ex);
                            }
                        }
                        catch (Exception ex)
                        {
                            Logger.LogError("Error processing frame", ex);
                        }
                        
                    }
                    else if (_frameQueue.Count == 0)
                    {
                        await Task.Delay(DisplayIntervalMs, cancellationToken).ConfigureAwait(false);
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    Logger.LogError("Error in frame processing loop", ex);
                    await Task.Delay(1, cancellationToken);  // Минимум перед повтором
                }
            }
        }

        /// <summary>
        /// Получает время последнего полученного кадра
        /// </summary>
        public DateTime GetLastFrameTime()
        {
            return _lastFrameTime;
        }

        /// <summary>
        /// Получает количество кадров в очереди
        /// </summary>
        public int GetQueueSize()
        {
            return _frameQueue.Count;
        }

        /// <summary>
        /// Очищает очередь кадров
        /// </summary>
        public void ClearQueue()
        {
            while (_frameQueue.TryDequeue(out _)) { }
        }
    }
}
