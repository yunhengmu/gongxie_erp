using System.Collections.Generic;
using System.Text;

namespace AgvDemo.Devices.Telemetry
{
    /// <summary>按设备保留最新一次遥测，供 HUD 或本地缓存读取。</summary>
    public sealed class InMemoryTelemetrySink : ITelemetrySink
    {
        readonly Dictionary<string, DeviceTelemetry> _latest = new Dictionary<string, DeviceTelemetry>();

        public int Count { get; private set; }

        public void OnTelemetry(DeviceTelemetry telemetry)
        {
            _latest[telemetry.DeviceId] = telemetry;
            Count++;
        }

        public bool TryGet(string deviceId, out DeviceTelemetry telemetry)
            => _latest.TryGetValue(deviceId, out telemetry);

        public IReadOnlyDictionary<string, DeviceTelemetry> All => _latest;

        public void Clear()
        {
            _latest.Clear();
            Count = 0;
        }
    }

    /// <summary>
    /// 把遥测压成一行 JSON。这就是推给 MQTT 主题或后端接口时的载荷形状，
    /// 在这里先落地，接真实出口时直接复用。
    /// </summary>
    public sealed class JsonLineTelemetrySink : ITelemetrySink
    {
        readonly StringBuilder _buffer = new StringBuilder(256);

        public string LastPayload { get; private set; } = string.Empty;
        public int Count { get; private set; }

        public void OnTelemetry(DeviceTelemetry telemetry)
        {
            _buffer.Clear();
            _buffer.Append('{');
            _buffer.Append("\"deviceId\":\"").Append(telemetry.DeviceId).Append("\",");
            _buffer.Append("\"kind\":\"").Append(telemetry.Kind).Append("\",");
            _buffer.Append("\"state\":\"").Append(telemetry.State).Append("\",");
            _buffer.Append("\"stateCode\":").Append(telemetry.StateCode).Append(',');
            _buffer.Append("\"x\":").Append(telemetry.X.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture)).Append(',');
            _buffer.Append("\"z\":").Append(telemetry.Z.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture)).Append(',');
            _buffer.Append("\"battery\":").Append(telemetry.Battery.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture)).Append(',');
            _buffer.Append("\"progress\":").Append(telemetry.Progress.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture)).Append(',');
            _buffer.Append("\"cycles\":").Append(telemetry.CompletedCycles).Append(',');
            _buffer.Append("\"ts\":").Append(telemetry.TimestampMs);
            _buffer.Append('}');

            LastPayload = _buffer.ToString();
            Count++;
        }
    }
}
