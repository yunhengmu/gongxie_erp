using System.Collections.Generic;
using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;

namespace AgvDemo.Core.Fleet
{
    /// <summary>
    /// 设备编队的只读视图。表现层只认这个契约 ——
    /// 不认识设备实现，也不认识传输实现，换实现不影响界面。
    /// </summary>
    public interface IFleetReadModel
    {
        IReadOnlyList<IDevice> Devices { get; }

        bool TryGetTelemetry(string deviceId, out DeviceTelemetry telemetry);

        int CountInState(MachineState state);

        string TransportName { get; }
        string TransportProtocol { get; }
        string TransportEndpoint { get; }
        string TransportState { get; }
    }
}