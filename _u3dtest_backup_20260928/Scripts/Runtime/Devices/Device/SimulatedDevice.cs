using System.Diagnostics;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices
{
    /// <summary>
    /// 通用模拟设备：按固定节拍在「取货点 ↔ 作业点」之间往返，并在每个周期把
    /// 位置、状态、电量写回通信通道的标签表 —— 也就是模拟 PLC 的数据块。
    /// 这样上层读到的数据与接真机时完全同构。
    /// </summary>
    public sealed class SimulatedDevice : IDevice
    {
        static readonly Stopwatch Clock = Stopwatch.StartNew();

        readonly ITransport _transport;
        readonly float _homeX;
        readonly float _homeZ;
        readonly float _targetX;
        readonly float _targetZ;
        readonly float _cycleSeconds;
        readonly float _batteryDrainPerSecond;

        float _cycleTime;
        float _x;
        float _z;
        float _battery = 1f;

        public SimulatedDevice(
            DeviceDescriptor descriptor,
            ITransport transport,
            float homeX,
            float homeZ,
            float targetX,
            float targetZ,
            float cycleSeconds,
            float batteryDrainPerSecond)
        {
            Descriptor = descriptor;
            _transport = transport;
            _homeX = homeX;
            _homeZ = homeZ;
            _targetX = targetX;
            _targetZ = targetZ;
            _cycleSeconds = cycleSeconds;
            _batteryDrainPerSecond = batteryDrainPerSecond;
            _x = homeX;
            _z = homeZ;
        }

        public DeviceDescriptor Descriptor { get; }

        public DeviceStatus Status
        {
            get
            {
                if (_transport == null || !_transport.IsConnected) return DeviceStatus.Offline;
                if (_battery <= 0.05f) return DeviceStatus.Fault;
                return Phase < 0.9f ? DeviceStatus.Working : DeviceStatus.Idle;
            }
        }

        float Phase => _cycleSeconds <= 0f ? 1f : (_cycleTime % _cycleSeconds) / _cycleSeconds;

        public void Tick(float deltaTime)
        {
            if (deltaTime <= 0f) return;

            if (Status == DeviceStatus.Offline) return;

            if (_battery <= 0.05f)
            {
                WriteTags();
                return;
            }

            _cycleTime += deltaTime;

            float p = Phase;
            if (p < 0.45f)
            {
                float k = p / 0.45f;
                _x = _homeX + (_targetX - _homeX) * k;
                _z = _homeZ + (_targetZ - _homeZ) * k;
                Drain(deltaTime);
            }
            else if (p < 0.55f)
            {
                _x = _targetX;
                _z = _targetZ;
            }
            else if (p < 0.95f)
            {
                float k = (p - 0.55f) / 0.4f;
                _x = _targetX + (_homeX - _targetX) * k;
                _z = _targetZ + (_homeZ - _targetZ) * k;
                Drain(deltaTime);
            }
            else
            {
                _x = _homeX;
                _z = _homeZ;
            }

            WriteTags();
        }

        void Drain(float deltaTime)
        {
            _battery -= _batteryDrainPerSecond * deltaTime;
            if (_battery < 0f) _battery = 0f;
        }

        /// <summary>把设备状态写回通信通道，等价于真机把状态写进 PLC 数据块。</summary>
        void WriteTags()
        {
            if (_transport == null || !_transport.IsConnected) return;

            string prefix = Descriptor.Id;
            _transport.TryWrite(prefix + ".PosX", TagValue.From(_x));
            _transport.TryWrite(prefix + ".PosZ", TagValue.From(_z));
            _transport.TryWrite(prefix + ".Battery", TagValue.From(_battery));
            _transport.TryWrite(prefix + ".Status", TagValue.From((int)Status));
            _transport.TryWrite(prefix + ".Online", TagValue.From(true));
        }

        public DeviceTelemetry CaptureTelemetry()
        {
            return new DeviceTelemetry(
                Descriptor.Id,
                Descriptor.Kind,
                Status,
                _x,
                _z,
                _battery,
                Phase,
                Clock.ElapsedMilliseconds);
        }
    }
}
