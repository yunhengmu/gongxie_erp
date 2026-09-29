using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using MQTTnet;
using MQTTnet.Client;
using MQTTnet.Formatter;

namespace AgvDemo.Devices.Transport
{
    /// <summary>
    /// MQTT 通道，基于社区库 MQTTnet（版本必须锁 4.3.x，见下方说明）。
    ///
    /// MQTT 在工业侧扮演的是「纵向打通」的角色：设备状态上行到平台/云端，
    /// 平台指令下行到设备。因此这里把逻辑标签直接当作主题后缀：
    ///   写 AGV-01.PosX  →  发布到 {TopicPrefix}/AGV-01.PosX
    ///   订阅 {TopicPrefix}/#  →  收到的值进入读缓存
    ///
    /// **版本约束**：MQTTnet 5.x 起要求 .NET 8，Unity 2022.3 只能用到 4.3.7。
    /// 这点在 Documents 里有说明，不要随手升级。
    /// </summary>
    public sealed class MqttTransport : ITransport
    {
        readonly string _host;
        readonly int _port;
        readonly string _clientId;
        readonly string _topicPrefix;
        readonly string _username;
        readonly string _password;
        readonly Dictionary<string, TagValue> _cache = new Dictionary<string, TagValue>();
        readonly object _gate = new object();

        IMqttClient _client;

        public MqttTransport(
            string name,
            string host,
            int port = 1883,
            string topicPrefix = "agv",
            string clientId = null,
            string username = null,
            string password = null)
        {
            Name = name;
            _host = host;
            _port = port;
            _topicPrefix = string.IsNullOrEmpty(topicPrefix) ? "agv" : topicPrefix.TrimEnd('/');
            _clientId = string.IsNullOrEmpty(clientId) ? $"unity-agv-{Guid.NewGuid():N}" : clientId;
            _username = username;
            _password = password;
        }

        public string Name { get; }
        public ProtocolKind Protocol => ProtocolKind.Mqtt;
        public string Endpoint => $"mqtt://{_host}:{_port}";

        public TransportState State { get; private set; } = TransportState.Disconnected;
        public bool IsConnected => State == TransportState.Connected;

        public event Action<TransportState> StateChanged;

        /// <summary>发布成功计数，用于在 HUD 上确认链路真的在动。</summary>
        public int PublishedCount { get; private set; }

        /// <summary>接收计数。</summary>
        public int ReceivedCount { get; private set; }

        public string LastError { get; private set; }

        public void Connect()
        {
            if (State == TransportState.Connecting || State == TransportState.Connected) return;
            SetState(TransportState.Connecting);
            Task.Run(ConnectAsync);
        }

        async Task ConnectAsync()
        {
            try
            {
                var factory = new MqttFactory();
                _client = factory.CreateMqttClient();
                _client.ApplicationMessageReceivedAsync += OnMessageReceived;

                var builder = new MqttClientOptionsBuilder()
                    .WithTcpServer(_host, _port)
                    .WithClientId(_clientId)
                    .WithCleanSession()
                    .WithProtocolVersion(MqttProtocolVersion.V500);

                if (!string.IsNullOrEmpty(_username))
                    builder = builder.WithCredentials(_username, _password);

                await _client.ConnectAsync(builder.Build(), CancellationToken.None).ConfigureAwait(false);

                var subscribe = new MqttClientSubscribeOptionsBuilder()
                    .WithTopicFilter($"{_topicPrefix}/#")
                    .Build();

                await _client.SubscribeAsync(subscribe, CancellationToken.None).ConfigureAwait(false);

                SetState(TransportState.Connected);
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
                SetState(TransportState.Faulted);
            }
        }

        Task OnMessageReceived(MqttApplicationMessageReceivedEventArgs args)
        {
            var message = args.ApplicationMessage;
            var topic = message?.Topic;
            if (string.IsNullOrEmpty(topic)) return Task.CompletedTask;

            string tag = topic.StartsWith(_topicPrefix + "/", StringComparison.Ordinal)
                ? topic.Substring(_topicPrefix.Length + 1)
                : topic;

            string payload = message.ConvertPayloadToString();

            lock (_gate)
            {
                _cache[tag] = ParsePayload(payload);
            }

            ReceivedCount++;
            return Task.CompletedTask;
        }

        /// <summary>上行值统一当作 real 解析；非数字则按文本存放。</summary>
        static TagValue ParsePayload(string payload)
        {
            if (string.IsNullOrEmpty(payload)) return TagValue.From(0f);
            if (payload == "true" || payload == "TRUE" || payload == "1" && payload.Length == 1) return TagValue.From(true);
            if (payload == "false" || payload == "FALSE" || payload == "0" && payload.Length == 1) return TagValue.From(false);
            if (float.TryParse(payload, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out float number))
                return TagValue.From(number);
            return TagValue.From(payload);
        }

        public void Disconnect()
        {
            Task.Run(async () =>
            {
                try
                {
                    if (_client != null && _client.IsConnected)
                        await _client.DisconnectAsync().ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    LastError = ex.Message;
                }
                finally
                {
                    SetState(TransportState.Disconnected);
                }
            });
        }

        public void Pump(float deltaTime)
        {
            if (State != TransportState.Faulted) return;

            _reconnectTimer += deltaTime;
            if (_reconnectTimer < 3f) return;
            _reconnectTimer = 0f;
            Connect();
        }

        float _reconnectTimer;

        public bool TryRead(string tag, out TagValue value)
        {
            if (!IsConnected)
            {
                value = default;
                return false;
            }

            lock (_gate)
            {
                return _cache.TryGetValue(tag, out value);
            }
        }

        public bool TryWrite(string tag, TagValue value)
        {
            if (!IsConnected || _client == null) return false;

            string topic = $"{_topicPrefix}/{tag}";
            string payload = value.ToString();

            Task.Run(async () =>
            {
                try
                {
                    await _client.PublishStringAsync(topic, payload).ConfigureAwait(false);
                    PublishedCount++;
                }
                catch (Exception ex)
                {
                    LastError = ex.Message;
                    SetState(TransportState.Faulted);
                }
            });

            return true;
        }

        void SetState(TransportState next)
        {
            if (State == next) return;
            State = next;
            StateChanged?.Invoke(next);
        }
    }
}
