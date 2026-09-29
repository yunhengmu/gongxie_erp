namespace AgvDemo.Devices.Telemetry
{
    /// <summary>
    /// 一次遥测快照。IoT 侧会把它序列化后推给 MQTT、时序库或后端接口，
    /// 因此这里只放平铺的标量，不带任何引擎类型。
    /// </summary>
    public readonly struct DeviceTelemetry
    {
        public readonly string DeviceId;
        public readonly DeviceKind Kind;
        public readonly DeviceStatus Status;

        public readonly float X;
        public readonly float Z;
        public readonly float Battery;
        public readonly float Load;
        public readonly long TimestampMs;

        public DeviceTelemetry(
            string deviceId,
            DeviceKind kind,
            DeviceStatus status,
            float x,
            float z,
            float battery,
            float load,
            long timestampMs)
        {
            DeviceId = deviceId;
            Kind = kind;
            Status = status;
            X = x;
            Z = z;
            Battery = battery;
            Load = load;
            TimestampMs = timestampMs;
        }
    }

    /// <summary>
    /// 遥测出口。MQTT 客户端、后端上报、本地日志都实现这个接口，
    /// 网关按序分发，互不感知对方存在。
    /// </summary>
    public interface ITelemetrySink
    {
        void OnTelemetry(DeviceTelemetry telemetry);
    }
}
