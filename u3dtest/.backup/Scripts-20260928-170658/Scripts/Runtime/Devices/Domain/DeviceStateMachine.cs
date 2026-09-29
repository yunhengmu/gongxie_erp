using System;

namespace AgvDemo.Devices.Domain
{
    /// <summary>
    /// 设备状态机。纯 C#，不依赖引擎，因此可单测、可注入。
    ///
    /// 两种驱动方式：
    ///  - 自动：按各状态停留时长自动流转；
    ///  - 手动：AutoAdvance=false，由 TryAdvance 逐个状态切换（调试与演示用）。
    /// </summary>
    public sealed class DeviceStateMachine
    {
        readonly MachineState[] _cycle;
        readonly float[] _durations;

        int _index;
        float _timer;
        float _battery = 1f;
        string _faultReason;

        public DeviceStateMachine(DeviceKind kind, float speedScale = 1f)
        {
            _cycle = BuildCycle(kind);
            _durations = BuildDurations(kind, speedScale);
        }

        public MachineState State { get; private set; } = MachineState.Idle;

        public bool AutoAdvance { get; set; } = true;

        public int CompletedCycles { get; private set; }

        public string FaultReason => _faultReason;

        public float Battery => _battery;

        /// <summary>当前状态已停留的时长占该状态总时长的比例 0~1。</summary>
        public float Progress => _durations.Length == 0 ? 0f : Clamp01(_timer / _durations[_index]);

        public float ElapsedInState => _timer;

        /// <summary>离线由通道状态决定，不由状态机决定。</summary>
        public void SetOffline() => State = MachineState.Offline;

        public MachineState Tick(float deltaTime)
        {
            if (deltaTime <= 0f) return State;
            if (State == MachineState.Offline || State == MachineState.Fault) return State;

            if (_battery <= 0.05f && State != MachineState.Charging)
            {
                if (State == MachineState.Charging) return State;
                Enter(IndexOf(MachineState.Charging));
                return State;
            }

            _timer += deltaTime;

            if (State == MachineState.Charging)
            {
                _battery = Math.Min(1f, _battery + 0.08f * deltaTime);
                if (_battery >= 1f && AutoAdvance) Enter(IndexOf(MachineState.Idle));
                return State;
            }

            if (State.ConsumesPower())
                _battery = Math.Max(0f, _battery - 0.004f * deltaTime);

            if (!AutoAdvance) return State;
            if (_timer < _durations[_index]) return State;

            Advance();
            return State;
        }

        /// <summary>手动推进一个状态。自动模式下不生效。</summary>
        public bool TryAdvance()
        {
            if (AutoAdvance) return false;
            if (State == MachineState.Offline || State == MachineState.Fault) return false;

            Advance();
            return true;
        }

        public void RaiseFault(string reason)
        {
            _faultReason = reason;
            State = MachineState.Fault;
            _timer = 0f;
        }

        public void ClearFault()
        {
            _faultReason = null;
            Enter(IndexOf(MachineState.Idle));
        }

        void Advance()
        {
            int next = _index + 1;
            if (next >= _cycle.Length)
            {
                next = 0;
                CompletedCycles++;
            }
            Enter(next);
        }

        void Enter(int index)
        {
            if (index < 0 || index >= _cycle.Length) index = 0;
            _index = index;
            _timer = 0f;
            State = _cycle[index];
        }

        int IndexOf(MachineState state)
        {
            for (int i = 0; i < _cycle.Length; i++)
                if (_cycle[i] == state) return i;
            return 0;
        }

        static MachineState[] BuildCycle(DeviceKind kind)
        {
            switch (kind)
            {
                case DeviceKind.Agv:
                case DeviceKind.Stacker:
                case DeviceKind.Drone:
                    return new[]
                    {
                        MachineState.Idle, MachineState.Moving, MachineState.Scanning,
                        MachineState.Loading, MachineState.Moving, MachineState.Unloading,
                    };

                case DeviceKind.RobotArm:
                    return new[]
                    {
                        MachineState.Idle, MachineState.Scanning, MachineState.Loading,
                        MachineState.Processing, MachineState.Unloading,
                    };

                case DeviceKind.Cnc:
                    return new[]
                    {
                        MachineState.Idle, MachineState.Loading, MachineState.Processing,
                        MachineState.Unloading,
                    };

                default:
                    return new[] { MachineState.Idle };
            }
        }

        static float[] BuildDurations(DeviceKind kind, float speedScale)
        {
            float[] baseDurations;
            switch (kind)
            {
                case DeviceKind.Agv:
                    baseDurations = new[] { 1.2f, 3.4f, 0.9f, 1.1f, 3.0f, 1.0f }; break;
                case DeviceKind.Stacker:
                    baseDurations = new[] { 1.0f, 2.6f, 0.7f, 1.3f, 2.4f, 1.2f }; break;
                case DeviceKind.Drone:
                    baseDurations = new[] { 1.0f, 3.8f, 0.8f, 0.9f, 3.4f, 0.9f }; break;
                case DeviceKind.RobotArm:
                    baseDurations = new[] { 1.0f, 0.6f, 0.9f, 2.6f, 1.0f }; break;
                case DeviceKind.Cnc:
                    baseDurations = new[] { 1.4f, 1.0f, 5.2f, 1.2f }; break;
                default:
                    baseDurations = new[] { 2f }; break;
            }

            if (speedScale <= 0f) speedScale = 1f;
            var scaled = new float[baseDurations.Length];
            for (int i = 0; i < baseDurations.Length; i++) scaled[i] = baseDurations[i] / speedScale;
            return scaled;
        }

        static float Clamp01(float v) => v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
