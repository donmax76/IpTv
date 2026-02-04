using System;
using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Reflection;
using Newtonsoft.Json;
using RemoteDesktopClient.Utils;

namespace RemoteDesktopClient.Services
{
    /// <summary>
    /// Сервис для работы с WebSocket соединениями
    /// Обеспечивает подключение к VPS серверу, отправку и получение сообщений
    /// </summary>
    public class WebSocketService : IDisposable
    {
        private ClientWebSocket _webSocket;
        private CancellationTokenSource _cancellationTokenSource;
        private bool _isConnected = false;
        private readonly object _lock = new object();
        /// <summary>Очередь бинарных сообщений: один потребитель сохраняет порядок кадров (убирает прыжки).</summary>
        private readonly ConcurrentQueue<byte[]> _binaryQueue = new ConcurrentQueue<byte[]>();

        /// <summary>
        /// Событие получения текстового сообщения
        /// </summary>
        public event EventHandler<string> TextMessageReceived;

        /// <summary>
        /// Событие получения бинарного сообщения (кадры экрана, файлы)
        /// </summary>
        public event EventHandler<byte[]> BinaryMessageReceived;

        /// <summary>
        /// Событие отключения
        /// </summary>
        public event EventHandler Disconnected;

        /// <summary>
        /// Проверяет, подключен ли WebSocket
        /// </summary>
        public bool IsConnected
        {
            get
            {
                lock (_lock)
                {
                    return _isConnected && _webSocket != null && _webSocket.State == WebSocketState.Open;
                }
            }
        }

        /// <summary>
        /// Подключается к WebSocket серверу
        /// </summary>
        /// <param name="uri">URI сервера (например, "ws://185.247.118.189/ws")</param>
        /// <param name="cancellationToken">Токен отмены</param>
        /// <returns>True если подключение успешно</returns>
        public async Task<bool> ConnectAsync(string uri, CancellationToken cancellationToken = default)
        {
            try
            {
                lock (_lock)
                {
                    if (_webSocket != null)
                    {
                        try
                        {
                            if (_webSocket.State == WebSocketState.Open || _webSocket.State == WebSocketState.Connecting)
                            {
                                try
                                {
                                    _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Reconnecting", CancellationToken.None).Wait(1000);
                                }
                                catch (Exception closeEx)
                                {
                                    Logger.LogWarning($"Error closing WebSocket during reconnect: {closeEx.GetType().Name}");
                                }
                            }
                            _webSocket.Dispose();
                        }
                        catch (Exception disposeEx)
                        {
                            Logger.LogWarning($"Error disposing old WebSocket: {disposeEx.GetType().Name}");
                        }
                    }
                    _webSocket = new ClientWebSocket();
                    // КРИТИЧНО: Устанавливаем заголовки для обхода блокировки VPS (403 Forbidden)
                    // VPS сервер проверяет User-Agent и блокирует подключения без него или с признаками ботов
                    try
                    {
                        // Устанавливаем Origin (это разрешено)
                        string originUri = uri.Replace("ws://", "http://").Replace("wss://", "https://");
                        _webSocket.Options.SetRequestHeader("Origin", originUri);
                        
                        // КРИТИЧНО: Пытаемся установить User-Agent через рефлексию (обходное решение для .NET Framework 4.7)
                        // В .NET Framework 4.7 нельзя установить User-Agent через SetRequestHeader (заблокировано)
                        // Используем несколько методов для максимальной совместимости
                        bool userAgentSet = false;
                        string userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
                        
                        try
                        {
                            var optionsType = _webSocket.Options.GetType();
                            
                            // Метод 1: Пробуем установить через внутреннее поле _requestHeaders
                            var headersField = optionsType.GetField("_requestHeaders", 
                                BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.FlattenHierarchy);
                            if (headersField == null)
                            {
                                // Метод 2: Пробуем другое имя поля
                                headersField = optionsType.GetField("requestHeaders", 
                                    BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.FlattenHierarchy);
                            }
                            if (headersField == null)
                            {
                                // Метод 3: Пробуем все поля типа HttpRequestHeaders или подобного
                                var allFields = optionsType.GetFields(BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.FlattenHierarchy);
                                foreach (var field in allFields)
                                {
                                    if (field.FieldType.Name.Contains("Header") || field.FieldType.Name.Contains("Collection"))
                                    {
                                        headersField = field;
                                        break;
                                    }
                                }
                            }
                            
                            if (headersField != null)
                            {
                                var headers = headersField.GetValue(_webSocket.Options);
                                if (headers != null)
                                {
                                    var headersType = headers.GetType();
                                    // Пробуем метод TryAddWithoutValidation
                                    var addMethod = headersType.GetMethod("TryAddWithoutValidation", 
                                        BindingFlags.Public | BindingFlags.Instance,
                                        null,
                                        new[] { typeof(string), typeof(string) },
                                        null);
                                    if (addMethod != null)
                                    {
                                        bool result = (bool)addMethod.Invoke(headers, new object[] { "User-Agent", userAgent });
                                        if (result)
                                        {
                                            userAgentSet = true;
                                            Logger.Log("User-Agent header set via reflection (TryAddWithoutValidation)");
                                        }
                                    }
                                    else
                                    {
                                        // Пробуем метод Add
                                        addMethod = headersType.GetMethod("Add", 
                                            BindingFlags.Public | BindingFlags.Instance,
                                            null,
                                            new[] { typeof(string), typeof(string) },
                                            null);
                                        if (addMethod != null)
                                        {
                                            try
                                            {
                                                addMethod.Invoke(headers, new object[] { "User-Agent", userAgent });
                                                userAgentSet = true;
                                                Logger.Log("User-Agent header set via reflection (Add)");
                                            }
                                            catch
                                            {
                                                // Метод Add может выбросить исключение для заблокированных заголовков
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Метод 4: Пробуем через свойство Headers (если есть)
                            if (!userAgentSet)
                            {
                                var headersProperty = optionsType.GetProperty("Headers", 
                                    BindingFlags.Public | BindingFlags.Instance);
                                if (headersProperty != null)
                                {
                                    var headers = headersProperty.GetValue(_webSocket.Options);
                                    if (headers != null)
                                    {
                                        var headersType = headers.GetType();
                                        var addMethod = headersType.GetMethod("TryAddWithoutValidation", 
                                            BindingFlags.Public | BindingFlags.Instance,
                                            null,
                                            new[] { typeof(string), typeof(string) },
                                            null);
                                        if (addMethod != null)
                                        {
                                            bool result = (bool)addMethod.Invoke(headers, new object[] { "User-Agent", userAgent });
                                            if (result)
                                            {
                                                userAgentSet = true;
                                                Logger.Log("User-Agent header set via Headers property");
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (!userAgentSet)
                            {
                                // В .NET Framework 4.7.2 нельзя установить User-Agent через SetRequestHeader
                                // Это нормально - VPS принимает соединения с Origin заголовком даже без User-Agent
                                Logger.Log("Note: User-Agent header not set (normal for .NET Framework 4.7.2 - using Origin instead)");
                            }
                        }
                        catch (Exception refEx)
                        {
                            // Если рефлексия не сработала - это нормально для .NET Framework 4.7.2
                            // VPS принимает соединения с Origin заголовком
                            Logger.Log($"Note: User-Agent reflection failed (normal for .NET Framework 4.7.2): {refEx.GetType().Name}");
                        }
                    }
                    catch (Exception headerEx)
                    {
                        // Если не удалось установить заголовки - продолжаем без них
                        Logger.LogWarning($"Failed to set WebSocket headers: {headerEx.GetType().Name}: {headerEx.Message}");
                    }
                    _cancellationTokenSource = new CancellationTokenSource();
                }

                // Проверяем валидность URI
                Uri serverUri;
                try
                {
                    serverUri = new Uri(uri);
                }
                catch (Exception uriEx)
                {
                    Logger.LogError($"Invalid WebSocket URI: {uri}", uriEx);
                    return false;
                }

                // Устанавливаем таймаут для подключения
                using (var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(10)))
                {
                    using (var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeoutCts.Token))
                    {
                        try
                        {
                            await _webSocket.ConnectAsync(serverUri, linkedCts.Token);
                        }
                        catch (OperationCanceledException)
                        {
                            if (timeoutCts.Token.IsCancellationRequested)
                            {
                                Logger.LogError($"Connection timeout to WebSocket: {uri}");
                            }
                            else
                            {
                                Logger.LogError($"Connection cancelled to WebSocket: {uri}");
                            }
                            return false;
                        }
                    }
                }
                
                lock (_lock)
                {
                    _isConnected = true;
                }

                Logger.Log($"Connected to WebSocket server: {uri}");

                // Приём сообщений
                _ = Task.Run(() => ReceiveLoopAsync(_cancellationTokenSource.Token));
                // Один потребитель очереди бинарных сообщений — сохраняет порядок кадров (убирает прыжки)
                _ = Task.Run(() => BinaryProcessLoopAsync(_cancellationTokenSource.Token));

                return true;
            }
            catch (System.Net.WebSockets.WebSocketException wsEx)
            {
                string errorMsg = $"WebSocket connection error: {uri} - {wsEx.WebSocketErrorCode}";
                if (wsEx.InnerException != null)
                {
                    var innerEx = wsEx.InnerException;
                    if (innerEx is System.Net.WebException webEx)
                    {
                        if (webEx.Response is System.Net.HttpWebResponse httpResponse)
                        {
                            errorMsg += $" (HTTP {httpResponse.StatusCode}: {httpResponse.StatusDescription})";
                            if (httpResponse.StatusCode == System.Net.HttpStatusCode.Forbidden)
                            {
                                errorMsg += " - Server rejected connection. Check room password or server configuration.";
                            }
                        }
                        else
                        {
                            errorMsg += $" - {innerEx.Message}";
                        }
                    }
                    else
                    {
                        errorMsg += $" - {innerEx.Message}";
                    }
                }
                Logger.LogError(errorMsg, wsEx);
                lock (_lock)
                {
                    _isConnected = false;
                    if (_webSocket != null)
                    {
                        try
                        {
                            _webSocket.Dispose();
                        }
                        catch (Exception disposeEx)
                        {
                            Logger.LogWarning($"Error disposing WebSocket on connection error: {disposeEx.GetType().Name}");
                        }
                        _webSocket = null;
                    }
                }
                return false;
            }
            catch (Exception ex)
            {
                Logger.LogError($"Failed to connect to WebSocket: {uri}", ex);
                lock (_lock)
                {
                    _isConnected = false;
                    if (_webSocket != null)
                    {
                        try
                        {
                            _webSocket.Dispose();
                        }
                        catch (Exception disposeEx)
                        {
                            Logger.LogWarning($"Error disposing WebSocket on connection error: {disposeEx.GetType().Name}");
                        }
                        _webSocket = null;
                    }
                }
                return false;
            }
        }

        /// <summary>
        /// Отправляет текстовое сообщение (JSON команда)
        /// </summary>
        /// <param name="message">Текстовое сообщение</param>
        /// <param name="cancellationToken">Токен отмены</param>
        public async Task SendTextAsync(string message, CancellationToken cancellationToken = default)
        {
            if (!IsConnected)
            {
                Logger.LogWarning("Cannot send message: WebSocket is not connected");
                return;
            }

            try
            {
                byte[] buffer = Encoding.UTF8.GetBytes(message);
                ClientWebSocket ws;
                lock (_lock)
                {
                    ws = _webSocket;
                }

                if (ws != null && ws.State == WebSocketState.Open)
                {
                    await ws.SendAsync(
                        new ArraySegment<byte>(buffer),
                        WebSocketMessageType.Text,
                        true,
                        cancellationToken
                    );
                }
            }
            catch (Exception ex)
            {
                Logger.LogError("Failed to send text message", ex);
                Disconnect();
            }
        }

        /// <summary>
        /// Отправляет JSON объект как текстовое сообщение
        /// </summary>
        /// <param name="obj">Объект для сериализации в JSON</param>
        /// <param name="cancellationToken">Токен отмены</param>
        public async Task SendJsonAsync(object obj, CancellationToken cancellationToken = default)
        {
            string json = JsonConvert.SerializeObject(obj);
            await SendTextAsync(json, cancellationToken);
        }

        /// <summary>
        /// Отправляет бинарное сообщение (кадры экрана, файлы)
        /// </summary>
        /// <param name="data">Бинарные данные</param>
        /// <param name="cancellationToken">Токен отмены</param>
        public async Task SendBinaryAsync(byte[] data, CancellationToken cancellationToken = default)
        {
            if (!IsConnected)
            {
                Logger.LogWarning("Cannot send binary: WebSocket is not connected");
                return;
            }

            try
            {
                ClientWebSocket ws;
                lock (_lock)
                {
                    ws = _webSocket;
                }

                if (ws != null && ws.State == WebSocketState.Open)
                {
                    await ws.SendAsync(
                        new ArraySegment<byte>(data),
                        WebSocketMessageType.Binary,
                        true,
                        cancellationToken
                    );
                }
            }
            catch (Exception ex)
            {
                Logger.LogError("Failed to send binary message", ex);
                Disconnect();
            }
        }

        /// <summary>
        /// Один потребитель очереди бинарных сообщений — сохраняет порядок кадров (FIFO), убирает прыжки.
        /// </summary>
        private async Task BinaryProcessLoopAsync(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    if (_binaryQueue.TryDequeue(out byte[] data))
                    {
                        try { BinaryMessageReceived?.Invoke(this, data); }
                        catch (Exception ex) { Logger.LogError("Binary handler error", ex); }
                    }
                    else
                        await Task.Delay(2, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) { break; }
            }
        }

        /// <summary>
        /// Цикл приема сообщений (работает в отдельном потоке)
        /// </summary>
        private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
        {
            byte[] buffer = new byte[1024 * 1024]; // 1MB буфер

            while (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    ClientWebSocket ws;
                    lock (_lock)
                    {
                        ws = _webSocket;
                        if (ws == null || ws.State != WebSocketState.Open)
                        {
                            break;
                        }
                    }

                    if (ws == null)
                        break;

                    WebSocketReceiveResult result = await ws.ReceiveAsync(
                        new ArraySegment<byte>(buffer),
                        cancellationToken
                    );

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Logger.Log("WebSocket closed by server");
                        break;
                    }

                    // Если сообщение не полное, собираем все части
                    byte[] messageData = new byte[result.Count];
                    Array.Copy(buffer, messageData, result.Count);

                    while (!result.EndOfMessage)
                    {
                        result = await ws.ReceiveAsync(
                            new ArraySegment<byte>(buffer),
                            cancellationToken
                        );
                        int oldLength = messageData.Length;
                        Array.Resize(ref messageData, oldLength + result.Count);
                        Array.Copy(buffer, 0, messageData, oldLength, result.Count);
                    }

                    // Обрабатываем сообщение
                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        string text = Encoding.UTF8.GetString(messageData);
                        TextMessageReceived?.Invoke(this, text);
                    }
                    else if (result.MessageType == WebSocketMessageType.Binary)
                    {
                        // Только ставим в очередь. Малая очередь (8) — не накапливать десятки старых кадров (прыжки ~40 сек).
                        const int maxBinaryQueue = 8;
                        while (_binaryQueue.Count >= maxBinaryQueue)
                            _binaryQueue.TryDequeue(out _);
                        _binaryQueue.Enqueue(messageData);
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    // Закрытие соединения удалённой стороной или сетью — ожидаемо, не ERROR
                    if (ex is System.Net.WebSockets.WebSocketException)
                        Logger.Log($"WebSocket receive loop ended: {ex.Message}");
                    else
                        Logger.LogError("Error in WebSocket receive loop", ex);
                    break;
                }
            }

            Disconnect();
        }

        /// <summary>
        /// Отключается от сервера
        /// </summary>
        public void Disconnect()
        {
            lock (_lock)
            {
                if (_isConnected)
                {
                    _isConnected = false;
                    _cancellationTokenSource?.Cancel();
                    
                    try
                    {
                        _webSocket?.CloseAsync(WebSocketCloseStatus.NormalClosure, "Client disconnect", CancellationToken.None).Wait(1000);
                    }
                    catch (Exception closeEx)
                    {
                        // При отключении CloseAsync часто даёт AggregateException — не спамим в лог
                        var unwrap = closeEx is AggregateException agg ? agg.InnerException : closeEx;
                        if (unwrap != null && !(unwrap is OperationCanceledException))
                            Logger.LogWarning($"Error closing WebSocket during disconnect: {unwrap.GetType().Name}");
                    }

                    if (_webSocket != null)
                    {
                        try
                        {
                            _webSocket.Dispose();
                        }
                        catch (Exception disposeEx)
                        {
                            Logger.LogWarning($"Error disposing WebSocket on connection error: {disposeEx.GetType().Name}");
                        }
                        _webSocket = null;
                    }
                    
                    Logger.Log("WebSocket disconnected");
                    Disconnected?.Invoke(this, EventArgs.Empty);
                }
            }
        }

        /// <summary>
        /// Освобождает ресурсы
        /// </summary>
        public void Dispose()
        {
            Disconnect();
            if (_cancellationTokenSource != null)
            {
                _cancellationTokenSource.Dispose();
                _cancellationTokenSource = null;
            }
        }
    }
}
