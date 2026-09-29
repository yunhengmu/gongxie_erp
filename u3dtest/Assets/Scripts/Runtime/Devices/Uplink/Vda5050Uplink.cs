using System;
using System.Globalization;
using System.Text;
using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices.Uplink
{
    /// <summary>
    /// 上行身份配置。VDA5050 用 manufacturer / serialNumber 唯一标识一台车，
    /// 这里把项目里的设备 id 直接当作 serialNumber。
    /// </summary>
    public sealed class Vda5050UplinkOptions
    {
        public string Manufacturer = "AgvDemo";
        public string TopicPrefix = "vda5050";
        public string MajorVersion = "3";
    }

    /// <summary>
    /// 上行出口：把设备遥测转成 VDA5050 state 消息，经 ITransport 发布。
    ///
    /// 主题（VDA5050 标准结构，前缀可配置）：
    ///   {TopicPrefix}/{MajorVersion}/{Manufacturer}/{serialNumber}/state
    ///   默认 → vda5050/3/AgvDemo/AGV-01/state
    ///
    /// 复用 ITransport.TryWrite 发布，所以模拟通道也能跑通全链路：
    /// MQTT 下会发到 {通道TopicPrefix}/{上行主题}，IoT 侧按同一主题订阅即可。
    ///
    /// 载荷是 VDA5050 state 消息的子集：只输出本项目有数据的字段，
    /// 嵌套结构与标准一致，对接标准 WCS 或自建平台都不用改结构。
    /// </summary>
    public sealed class Vda5050Uplink : ITelemetrySink
    {
        readonly ITransport _transport;
        readonly Vda5050UplinkOptions _options;
        readonly Func<DateTime> _utcNow;
        readonly StringBuilder _buffer = new StringBuilder(512);

        long _headerId;

        public Vda5050Uplink(
            ITransport transport,
            Vda5050UplinkOptions options,
            Func<DateTime> utcNow = null)
        {
            _transport = transport;
            _options = options ?? new Vda5050UplinkOptions();
            _utcNow = utcNow ?? (() => DateTime.UtcNow);
        }

        /// <summary>成功发布的条数。通道未连接时不计。</summary>
        public int PublishedCount { get; private set; }

        /// <summary>最近一次发布的主题，便于在 HUD 或日志里核对。</summary>
        public string LastTopic { get; private set; } = string.Empty;

        /// <summary>最近一次发布的载荷，便于离线断言与调试。</summary>
        public string LastPayload { get; private set; } = string.Empty;

        public void OnTelemetry(DeviceTelemetry telemetry)
        {
            if (_transport == null || !_transport.IsConnected) return;

            string topic = BuildTopic(telemetry.DeviceId);
            string payload = BuildStatePayload(telemetry);

            if (!_transport.TryWrite(topic, TagValue.From(payload))) return;

            PublishedCount++;
            LastTopic = topic;
            LastPayload = payload;
        }

        string BuildTopic(string serialNumber)
            => $"{_options.TopicPrefix}/{_options.MajorVersion}/{_options.Manufacturer}/{serialNumber}/state";

        string BuildStatePayload(DeviceTelemetry t)
        {
            _headerId++;
            _buffer.Clear();
            _buffer.Append('{');

            Field("headerId", _headerId);
            Field("timestamp", _utcNow().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture));
            Field("version", "3.0.0");
            Field("manufacturer", _options.Manufacturer);
            Field("serialNumber", t.DeviceId);
            Field("driving", t.State == MachineState.Moving);
            Field("state", ToVda5050State(t.State));
            Field("stateCode", t.StateCode);

            Open("agvPosition");
            Field("x", t.X);
            Field("y", t.Z);
            Field("theta", 0f);
            Close();

            Open("batteryState");
            Field("batteryCharge", t.Battery);
            Field("charging", t.State == MachineState.Charging);
            Close();

            OpenArray("errors");
            CloseArray();

            _buffer.Append('}');
            return _buffer.ToString();
        }

        /// <summary>MachineState → VDA5050 state 枚举。</summary>
        public static string ToVda5050State(MachineState state)
        {
            switch (state)
            {
                case MachineState.Offline: return "COMM_ERROR";
                case MachineState.Idle: return "IDLE";
                case MachineState.Moving: return "MOVING";
                case MachineState.Charging: return "CHARGING";
                case MachineState.Fault: return "ERROR";
                // Scanning / Loading / Processing / Unloading 在 VDA5050 里没有等价枚举，
                // 统一落 WAITING，需要区分时用 stateCode（本项目私有编码）在 IoT 侧展开。
                default: return "WAITING";
            }
        }

        void Separator()
        {
            // 紧跟 '{' 或 '[' 之后不需要逗号，其余位置都需要
            char last = _buffer[_buffer.Length - 1];
            if (last != '{' && last != '[') _buffer.Append(',');
        }

        void Field(string key, string value)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":\"").Append(value).Append('"');
        }

        void Field(string key, long value)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":").Append(value.ToString(CultureInfo.InvariantCulture));
        }

        void Field(string key, bool value)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":").Append(value ? "true" : "false");
        }

        void Field(string key, float value)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":")
                .Append(value.ToString("0.##", CultureInfo.InvariantCulture));
        }

        void Open(string key)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":{");
        }

        void OpenArray(string key)
        {
            Separator();
            _buffer.Append('"').Append(key).Append("\":[");
        }

        void Close() => _buffer.Append('}');

        void CloseArray() => _buffer.Append(']');
    }
}