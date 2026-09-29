using System.Collections.Generic;
using AgvDemo.Core.Domain;
using AgvDemo.Core.Fleet;
using AgvDemo.Core.Telemetry;
using AgvDemo.Devices.Configuration;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;
using AgvDemo.Devices.Uplink;

namespace AgvDemo.App
{
    /// <summary>
    /// 应用服务：把设备编队的启动与推进收敛到一处，并以 Core 的契约对外暴露。
    /// 表现层只依赖 IFleetReadModel / IFleetCommands，因此换界面不影响设备行为。
    /// </summary>
    public sealed class DeviceFleetService : IFleetReadModel, IFleetCommands
    {
        readonly DeviceGateway _gateway;
        readonly ITransport _transport;
        readonly InMemoryTelemetrySink _inMemory;
        readonly Vda5050Uplink _uplink;

        public DeviceFleetService(
            DeviceGateway gateway,
            ITransport transport,
            InMemoryTelemetrySink inMemory,
            JsonLineTelemetrySink jsonLine,
            Vda5050Uplink uplink)
        {
            _gateway = gateway;
            _transport = transport;
            _inMemory = inMemory;
            _uplink = uplink;
            JsonLine = jsonLine;
        }

        JsonLineTelemetrySink JsonLine { get; }

        public IReadOnlyList<IDevice> Devices => _gateway.Devices;

        public bool TryGetTelemetry(string deviceId, out DeviceTelemetry telemetry)
            => _inMemory.TryGet(deviceId, out telemetry);

        public int CountInState(MachineState state) => _gateway.CountInState(state);

        public string TransportName => _transport.Name;
        public string TransportProtocol => _transport.Protocol.ToString();
        public string TransportEndpoint => _transport.Endpoint;
        public string TransportState => _transport.State.ToString();

        public bool Advance(string deviceId) => _gateway.Advance(deviceId);

        public void SetAutoAdvance(string deviceId, bool autoAdvance)
            => _gateway.SetAutoAdvance(deviceId, autoAdvance);

        public void Start(DeviceFleetSettings settings)
        {
            _gateway.PublishInterval = settings.PublishInterval;
            _gateway.AddTransport(_transport);
            _gateway.AddSink(_inMemory);
            _gateway.AddSink(JsonLine);

            // 上行出口：把设备状态按 VDA5050 state 结构发到通道，供外部 IoT 平台消费
            if (settings.Transport.Uplink == null || settings.Transport.Uplink.Enabled)
                _gateway.AddSink(_uplink);

            _gateway.RegisterAll(SimulatedFleet.Create(_transport, settings.SpeedScale));

            if (!settings.StartInManualMode) return;

            for (int i = 0; i < _gateway.Devices.Count; i++)
                _gateway.Devices[i].AutoAdvance = false;
        }

        public void Tick(float deltaTime) => _gateway.Tick(deltaTime);
    }
}