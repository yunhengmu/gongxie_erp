using System.Collections.Generic;
using AgvDemo.Devices.Configuration;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Domain;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Bootstrap
{
    /// <summary>
    /// 应用服务：把设备编队的启动与推进收敛到一处。
    /// 表现层不直接驱动设备，只调用这里，因此换界面不影响设备行为。
    /// </summary>
    public sealed class DeviceFleetService
    {
        readonly DeviceGateway _gateway;
        readonly ITransport _transport;
        readonly InMemoryTelemetrySink _inMemory;

        public DeviceFleetService(
            DeviceGateway gateway,
            ITransport transport,
            InMemoryTelemetrySink inMemory,
            JsonLineTelemetrySink jsonLine)
        {
            _gateway = gateway;
            _transport = transport;
            _inMemory = inMemory;
            JsonLine = jsonLine;
        }

        public DeviceGateway Gateway => _gateway;
        public ITransport Transport => _transport;
        public InMemoryTelemetrySink InMemory => _inMemory;
        public JsonLineTelemetrySink JsonLine { get; }

        public IReadOnlyList<IDevice> Devices => _gateway.Devices;

        public void Start(DeviceFleetSettings settings)
        {
            _gateway.PublishInterval = settings.PublishInterval;
            _gateway.AddTransport(_transport);
            _gateway.AddSink(_inMemory);
            _gateway.AddSink(JsonLine);
            _gateway.RegisterAll(SimulatedFleet.Create(_transport, settings.SpeedScale));

            if (!settings.StartInManualMode) return;

            for (int i = 0; i < _gateway.Devices.Count; i++)
                _gateway.Devices[i].AutoAdvance = false;
        }

        public void Tick(float deltaTime) => _gateway.Tick(deltaTime);

        /// <summary>手动推进一台设备一个状态。</summary>
        public bool Advance(string deviceId) => _gateway.Advance(deviceId);

        public void SetAutoAdvance(string deviceId, bool autoAdvance)
            => _gateway.SetAutoAdvance(deviceId, autoAdvance);

        public bool TryGetTelemetry(string deviceId, out DeviceTelemetry telemetry)
            => _inMemory.TryGet(deviceId, out telemetry);
    }
}
