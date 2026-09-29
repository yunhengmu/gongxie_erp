using System;
using System.Collections.Generic;

namespace AgvDemo.Devices.Transport
{
    /// <summary>
    /// 内置 PLC 模拟器：用一个标签表充当数据块，对上层的读写语义与真实 S7 完全一致。
    /// 数字孪生开发期没有硬件时用它跑通整条链路，接现场时替换成 S7 通道即可，
    /// 因为设备驱动只认 ITransport。
    /// </summary>
    public sealed class SimulatedTransport : ITransport
    {
        readonly Dictionary<string, TagValue> _dataBlock = new Dictionary<string, TagValue>();
        readonly Random _random;
        readonly float _connectDelay;
        readonly float _faultProbability;
        readonly float _reconnectDelay;

        float _connectTimer;
        float _heartbeatTimer;

        public SimulatedTransport(
            string name,
            float connectDelay = 0.25f,
            float reconnectDelay = 1.5f,
            float faultProbability = 0f,
            int seed = 20260928)
        {
            Name = name;
            _connectDelay = connectDelay;
            _reconnectDelay = reconnectDelay;
            _faultProbability = faultProbability;
            _random = new Random(seed);
        }

        public string Name { get; }
        public ProtocolKind Protocol => ProtocolKind.Simulated;
        public string Endpoint => "sim://local/db1";
        public TransportState State { get; private set; } = TransportState.Disconnected;
        public bool IsConnected => State == TransportState.Connected;

        public event Action<TransportState> StateChanged;

        public void Connect()
        {
            if (State == TransportState.Connecting || State == TransportState.Connected) return;
            _connectTimer = _connectDelay;
            SetState(TransportState.Connecting);
        }

        public void Disconnect()
        {
            _connectTimer = 0f;
            SetState(TransportState.Disconnected);
        }

        public void Pump(float deltaTime)
        {
            switch (State)
            {
                case TransportState.Connecting:
                    _connectTimer -= deltaTime;
                    if (_connectTimer <= 0f) SetState(TransportState.Connected);
                    break;

                case TransportState.Faulted:
                    _connectTimer -= deltaTime;
                    if (_connectTimer <= 0f)
                    {
                        _connectTimer = _connectDelay;
                        SetState(TransportState.Connecting);
                    }
                    break;

                case TransportState.Connected:
                    if (_faultProbability <= 0f) break;
                    _heartbeatTimer += deltaTime;
                    if (_heartbeatTimer < 1f) break;
                    _heartbeatTimer = 0f;
                    if (_random.NextDouble() < _faultProbability)
                    {
                        _connectTimer = _reconnectDelay;
                        SetState(TransportState.Faulted);
                    }
                    break;
            }
        }

        public bool TryRead(string tag, out TagValue value)
        {
            if (!IsConnected)
            {
                value = default;
                return false;
            }
            return _dataBlock.TryGetValue(tag, out value);
        }

        public bool TryWrite(string tag, TagValue value)
        {
            if (!IsConnected) return false;
            _dataBlock[tag] = value;
            return true;
        }

        /// <summary>预置点位，模拟 PLC 上电时的初值。</summary>
        public void Seed(string tag, TagValue value) => _dataBlock[tag] = value;

        public IReadOnlyDictionary<string, TagValue> Snapshot() => _dataBlock;

        void SetState(TransportState next)
        {
            if (State == next) return;
            State = next;
            StateChanged?.Invoke(next);
        }
    }
}
