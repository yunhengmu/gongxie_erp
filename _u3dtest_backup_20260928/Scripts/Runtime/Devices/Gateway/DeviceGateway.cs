using System.Collections.Generic;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices.Gateway
{
    /// <summary>
    /// 设备网关：设备接入层的唯一出口。统一推进通信通道与设备状态、按周期采集遥测
    /// 并分发给所有出口。上层（表现层 / MQTT / 后端上报）只跟它打交道，
    /// 因此新增一种设备或换一种协议都不会波及上层。
    /// </summary>
    public sealed class DeviceGateway
    {
        readonly List<ITransport> _transports = new List<ITransport>();
        readonly List<IDevice> _devices = new List<IDevice>();
        readonly List<ITelemetrySink> _sinks = new List<ITelemetrySink>();

        float _publishTimer;

        /// <summary>遥测上报周期（秒）。现场通常 100ms ~ 1s。</summary>
        public float PublishInterval { get; set; } = 0.5f;

        public IReadOnlyList<ITransport> Transports => _transports;
        public IReadOnlyList<IDevice> Devices => _devices;
        public IReadOnlyList<ITelemetrySink> Sinks => _sinks;

        public int PublishedCount { get; private set; }

        public DeviceGateway AddTransport(ITransport transport, bool autoConnect = true)
        {
            _transports.Add(transport);
            if (autoConnect) transport.Connect();
            return this;
        }

        public DeviceGateway Register(IDevice device)
        {
            _devices.Add(device);
            return this;
        }

        public DeviceGateway RegisterAll(IEnumerable<IDevice> devices)
        {
            _devices.AddRange(devices);
            return this;
        }

        public DeviceGateway AddSink(ITelemetrySink sink)
        {
            _sinks.Add(sink);
            return this;
        }

        public void Tick(float deltaTime)
        {
            for (int i = 0; i < _transports.Count; i++)
                _transports[i].Pump(deltaTime);

            for (int i = 0; i < _devices.Count; i++)
                _devices[i].Tick(deltaTime);

            _publishTimer += deltaTime;
            if (_publishTimer < PublishInterval) return;
            _publishTimer = 0f;
            Publish();
        }

        public void Publish()
        {
            for (int i = 0; i < _devices.Count; i++)
            {
                DeviceTelemetry snapshot = _devices[i].CaptureTelemetry();
                for (int s = 0; s < _sinks.Count; s++)
                    _sinks[s].OnTelemetry(snapshot);
                PublishedCount++;
            }
        }

        public int CountByStatus(DeviceStatus status)
        {
            int count = 0;
            for (int i = 0; i < _devices.Count; i++)
                if (_devices[i].Status == status) count++;
            return count;
        }

        public bool AllTransportsConnected
        {
            get
            {
                for (int i = 0; i < _transports.Count; i++)
                    if (!_transports[i].IsConnected) return false;
                return _transports.Count > 0;
            }
        }
    }
}
