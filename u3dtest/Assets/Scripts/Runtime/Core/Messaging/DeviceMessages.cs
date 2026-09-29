using AgvDemo.Core.Domain;

namespace AgvDemo.Core.Messaging
{
    /// <summary>
    /// 设备状态变化通知。由 MessagePipe 在进程内分发，
    /// 发布方与订阅方只认这个结构，互不持有引用。
    /// </summary>
    public readonly struct DeviceStateChanged
    {
        public readonly string DeviceId;
        public readonly DeviceKind Kind;
        public readonly MachineState Previous;
        public readonly MachineState Current;

        public DeviceStateChanged(string deviceId, DeviceKind kind, MachineState previous, MachineState current)
        {
            DeviceId = deviceId;
            Kind = kind;
            Previous = previous;
            Current = current;
        }
    }

    /// <summary>设备故障上报。</summary>
    public readonly struct DeviceFaulted
    {
        public readonly string DeviceId;
        public readonly string Reason;

        public DeviceFaulted(string deviceId, string reason)
        {
            DeviceId = deviceId;
            Reason = reason;
        }
    }

    /// <summary>请求把某台设备推进到下一个状态（手动模式下有效）。</summary>
    public readonly struct AdvanceDeviceRequest
    {
        public readonly string DeviceId;

        public AdvanceDeviceRequest(string deviceId) => DeviceId = deviceId;
    }

    /// <summary>切换某台设备的自动 / 手动流转模式。</summary>
    public readonly struct SetAutoAdvanceRequest
    {
        public readonly string DeviceId;
        public readonly bool AutoAdvance;

        public SetAutoAdvanceRequest(string deviceId, bool autoAdvance)
        {
            DeviceId = deviceId;
            AutoAdvance = autoAdvance;
        }
    }
}
