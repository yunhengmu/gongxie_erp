using System.Diagnostics;
using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices.Device
{
    /// <summary>
    /// 通用模拟设备：状态机驱动，位置由当前状态决定，
    /// 每个周期把状态与位置写回通信通道的标签表（等价于真机写 PLC 数据块）。
    /// </summary>
    public sealed class SimulatedDevice : IDevice
    {
        static readonly Stopwatch Clock = Stopwatch.StartNew();

        readonly DeviceStateMachine _machine;
        readonly ITransport _transport;
        readonly float _homeX;
        readonly float _homeZ;
        readonly float _targetX;
        readonly float _targetZ;

        float _x;
        float _z;

        public SimulatedDevice(
            DeviceDescriptor descriptor,
            ITransport transport,
            float homeX,
            float homeZ,
            float targetX,
            float targetZ,
            float speedScale = 1f)
        {
            Descriptor = descriptor;
            _transport = transport;
            _homeX = homeX;
            _homeZ = homeZ;
            _targetX = targetX;
            _targetZ = targetZ;
            _machine = new DeviceStateMachine(descriptor.Kind, speedScale);
            _x = homeX;
            _z = homeZ;
        }

        public DeviceDescriptor Descriptor { get; }

        public MachineState State => _machine.State;

        public bool AutoAdvance
        {
            get => _machine.AutoAdvance;
            set => _machine.AutoAdvance = value;
        }

        public void Tick(float deltaTime)
        {
            if (deltaTime <= 0f) return;

            if (_transport != null && !_transport.IsConnected)
            {
                _machine.SetOffline();
                return;
            }

            if (_machine.State == MachineState.Offline) _machine.ClearFault();

            var previous = _machine.State;
            var current = _machine.Tick(deltaTime);
            if (current != previous) OnStateChanged(previous, current);

            ApplyMotion(current);
            WriteTags();
        }

        /// <summary>位置由状态决定：移动中按进度插值，其余状态保持不动。</summary>
        void ApplyMotion(MachineState state)
        {
            if (state == MachineState.Moving)
            {
                // 往返：Moving 出现在周期里的两个位置，用进度决定朝哪边走
                float k = _machine.Progress;
                bool outbound = _machine.CompletedCycles % 2 == 0;
                _x = outbound ? Lerp(_homeX, _targetX, k) : Lerp(_targetX, _homeX, k);
                _z = outbound ? Lerp(_homeZ, _targetZ, k) : Lerp(_targetZ, _homeZ, k);
            }
            else if (state == MachineState.Charging)
            {
                _x = _homeX;
                _z = _homeZ;
            }
        }

        void OnStateChanged(MachineState previous, MachineState current)
        {
            // 状态进入时的一次性副作用（音效 / 上报 / 计数）挂在这里
        }

        /// <summary>把状态信号写回通信通道，等价于真机把状态写进 PLC。</summary>
        void WriteTags()
        {
            if (_transport == null || !_transport.IsConnected) return;

            string prefix = Descriptor.Id;
            _transport.TryWrite(prefix + ".State", TagValue.From((int)State));
            _transport.TryWrite(prefix + ".PosX", TagValue.From(_x));
            _transport.TryWrite(prefix + ".PosZ", TagValue.From(_z));
            _transport.TryWrite(prefix + ".Progress", TagValue.From(_machine.Progress));
            _transport.TryWrite(prefix + ".Battery", TagValue.From(_machine.Battery));
            _transport.TryWrite(prefix + ".Online", TagValue.From(true));
        }

        public bool TryAdvance() => _machine.TryAdvance();

        public void RaiseFault(string reason) => _machine.RaiseFault(reason);

        public void ClearFault() => _machine.ClearFault();

        public DeviceTelemetry CaptureTelemetry()
        {
            return new DeviceTelemetry(
                Descriptor.Id,
                Descriptor.Kind,
                State,
                _x,
                _z,
                _machine.Battery,
                _machine.Progress,
                _machine.CompletedCycles,
                Clock.ElapsedMilliseconds);
        }

        static float Lerp(float a, float b, float t) => a + (b - a) * t;
    }
}
