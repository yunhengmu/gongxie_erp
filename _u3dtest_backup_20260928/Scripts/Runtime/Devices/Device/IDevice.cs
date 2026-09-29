using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices
{
    /// <summary>设备的静态身份信息，运行期不变。</summary>
    public sealed class DeviceDescriptor
    {
        public readonly string Id;
        public readonly DeviceKind Kind;
        public readonly string Model;
        public readonly ProtocolKind Protocol;

        public DeviceDescriptor(string id, DeviceKind kind, string model, ProtocolKind protocol)
        {
            Id = id;
            Kind = kind;
            Model = model;
            Protocol = protocol;
        }

        public override string ToString() => $"{Id} · {Kind} · {Model}";
    }

    /// <summary>
    /// 被控对象抽象。所有设备（AGV / 堆垛机 / 机械臂 / 机床 / 无人机）都实现它，
    /// 上层因此可以用一个列表统一驱动、统一采集遥测，不需要知道具体是什么设备。
    /// </summary>
    public interface IDevice
    {
        DeviceDescriptor Descriptor { get; }
        DeviceStatus Status { get; }

        /// <summary>推进设备自身状态（运动、作业进度、故障计时）。</summary>
        void Tick(float deltaTime);

        DeviceTelemetry CaptureTelemetry();
    }
}
