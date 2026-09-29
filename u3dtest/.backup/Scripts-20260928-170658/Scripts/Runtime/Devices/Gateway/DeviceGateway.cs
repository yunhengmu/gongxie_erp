using System.Collections.Generic;
using AgvDemo.Devices.Domain;
using AgvDemo.Devices.Messages;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;
using MessagePipe;

namespace AgvDemo.Devices.Gateway
{
    /// <summary>
    /// 设备接入层唯一出口。统一推进通道与设备、采集状态信号并分发。
    ///
    /// 事件出口通过 MessagePipe 的 IPublisher 注入，不再使用静态 EventBus ——
    /// 全局可变状态无法测试替换，也让依赖方向变得不可见。
    /// </summary>
    public sealed class DeviceGateway
    {
        readonly List<ITransport> _transports = new List<ITransport>();
        readonly List<IDevice> _devices = new List<IDevice>();
        readonly List<ITelemetrySink> _sinks = new List<ITelemetrySink>();
        readonly Dictionary<string, MachineState> _lastStates = new Dictionary<string, MachineState>();

        readonly IPublisher<DeviceStateChanged> _statePublisher;
        readonly IPublisher<DeviceFaulted> _faultPublisher;

        float _publishTimer;

        public DeviceGateway(
            IPublisher<DeviceStateChanged> statePublisher,
            IPublisher<DeviceFaulted> faultPublisher)
        {
            _statePublisher = statePublisher;
            _faultPublisher = faultPublisher;
        }

        /// <summary>状态信号上报周期（秒）。现场通常 100ms ~ 1s。</summary>
        public float PublishInterval { get; set; } = 0.5f;

        public IReadOnlyList<ITransport> Transports => _transports;
        public IReadOnlyList<IDevice> Devices => _devices;

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
            _lastStates[device.Descriptor.Id] = device.State;
            return this;
        }

        public DeviceGateway RegisterAll(IEnumerable<IDevice> devices)
        {
            foreach (var device in devices) Register(device);
            return this;
        }

        public DeviceGateway AddSink(ITelemetrySink sink)
        {
            _sinks.Add(sink);
            return this;
        }

        public IDevice Find(string deviceId)
        {
            for (int i = 0; i < _devices.Count; i++)
                if (_devices[i].Descriptor.Id == deviceId) return _devices[i];
            return null;
        }

        /// <summary>手动推进某台设备一个状态（该设备需处于手动模式）。</summary>
        public bool Advance(string deviceId) => Find(deviceId)?.TryAdvance() ?? false;

        public void SetAutoAdvance(string deviceId, bool autoAdvance)
        {
            var device = Find(deviceId);
            if (device != null) device.AutoAdvance = autoAdvance;
        }

        public void MarkFault(string deviceId, string reason)
        {
            var device = Find(deviceId);
            if (device == null) return;

            device.RaiseFault(reason);
            _faultPublisher.Publish(new DeviceFaulted(deviceId, reason));
        }

        public void Tick(float deltaTime)
        {
            for (int i = 0; i < _transports.Count; i++)
                _transports[i].Pump(deltaTime);

            for (int i = 0; i < _devices.Count; i++)
                _devices[i].Tick(deltaTime);

            DetectStateChanges();

            _publishTimer += deltaTime;
            if (_publishTimer < PublishInterval) return;
            _publishTimer = 0f;
            PublishTelemetry();
        }

        /// <summary>状态一旦变化立刻发事件，不受上报周期限制。</summary>
        void DetectStateChanges()
        {
            for (int i = 0; i < _devices.Count; i++)
            {
                var device = _devices[i];
                string id = device.Descriptor.Id;

                if (!_lastStates.TryGetValue(id, out var previous)) previous = device.State;
                if (previous == device.State) continue;

                _lastStates[id] = device.State;
                _statePublisher.Publish(new DeviceStateChanged(id, device.Descriptor.Kind, previous, device.State));
            }
        }

        void PublishTelemetry()
        {
            for (int i = 0; i < _devices.Count; i++)
            {
                var snapshot = _devices[i].CaptureTelemetry();
                for (int s = 0; s < _sinks.Count; s++)
                    _sinks[s].OnTelemetry(snapshot);
                PublishedCount++;
            }
        }

        public int CountInState(MachineState state)
        {
            int count = 0;
            for (int i = 0; i < _devices.Count; i++)
                if (_devices[i].State == state) count++;
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
