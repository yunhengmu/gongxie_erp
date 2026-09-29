using AgvDemo.Devices.Telemetry;

namespace AgvDemo.Devices.Domain
{
    /// <summary>设备身份信息，运行期不变。</summary>
    public sealed class DeviceDescriptor
    {
        public DeviceDescriptor(string id, DeviceKind kind, string model)
        {
            Id = id;
            Kind = kind;
            Model = model;
        }

        public string Id { get; }
        public DeviceKind Kind { get; }
        public string Model { get; }

        public override string ToString() => $"{Id} · {Kind} · {Model}";
    }

    /// <summary>
    /// 被控对象抽象。上层用一份列表统一推进、统一采集状态信号，
    /// 不需要知道具体是 AGV 还是机床。
    /// </summary>
    public interface IDevice
    {
        DeviceDescriptor Descriptor { get; }
        MachineState State { get; }

        /// <summary>推进设备自身状态。</summary>
        void Tick(float deltaTime);

        /// <summary>手动推进到下一个状态；自动模式下返回 false。</summary>
        bool TryAdvance();

        /// <summary>是否由状态机自动流转。关掉后可逐个状态手动切换。</summary>
        bool AutoAdvance { get; set; }

        /// <summary>标记故障并停机。</summary>
        void RaiseFault(string reason);

        /// <summary>故障复位。</summary>
        void ClearFault();

        /// <summary>采集一次状态信号快照。上层不需要知道设备内部结构。</summary>
        DeviceTelemetry CaptureTelemetry();
    }
}
