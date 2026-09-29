using AgvDemo.Core.Domain;

namespace AgvDemo.Core.Telemetry
{
    /// <summary>
    /// 一次状态信号快照。IoT 侧会把它序列化后推给 MQTT、时序库或后端，
    /// 因此只放平铺标量，不带任何引擎类型。
    /// </summary>
    public readonly struct DeviceTelemetry
    {
        public readonly string DeviceId;
        public readonly DeviceKind Kind;
        public readonly MachineState State;

        /// <summary>PLC 状态字：把 MachineState 直接转成整型下发。</summary>
        public readonly int StateCode;

        public readonly float X;
        public readonly float Z;
        public readonly float Battery;

        /// <summary>当前状态已完成的进度 0~1。</summary>
        public readonly float Progress;

        public readonly int CompletedCycles;
        public readonly long TimestampMs;

        public DeviceTelemetry(
            string deviceId,
            DeviceKind kind,
            MachineState state,
            float x,
            float z,
            float battery,
            float progress,
            int completedCycles,
            long timestampMs)
        {
            DeviceId = deviceId;
            Kind = kind;
            State = state;
            StateCode = (int)state;
            X = x;
            Z = z;
            Battery = battery;
            Progress = progress;
            CompletedCycles = completedCycles;
            TimestampMs = timestampMs;
        }
    }

    /// <summary>遥测出口。MQTT 客户端、后端上报、本地缓存都实现它，网关按序分发。</summary>
    public interface ITelemetrySink
    {
        void OnTelemetry(DeviceTelemetry telemetry);
    }
}
