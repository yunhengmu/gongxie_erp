using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using S7.Net;

namespace AgvDemo.Devices.Transport
{
    /// <summary>
    /// 真实的 Siemens S7 通道，基于社区库 S7netplus（MIT，netstandard2.0）。
    ///
    /// 两条工业现场的硬性做法都体现在这里：
    ///  1. **读写都按周期批量做，绝不每帧打 PLC** —— 否则 102 端口的握手会被打满；
    ///  2. **IO 全部在工作线程执行**，主线程只读缓存，不会因网络抖动掉帧。
    ///
    /// 设备侧用的逻辑标签（如 AGV-01.PosX）通过 Map 映射到真实点位（如 DB1.DBD0）。
    /// </summary>
    public sealed class SiemensS7Transport : ITransport
    {
        readonly Plc _plc;
        readonly Dictionary<string, S7Address> _map = new Dictionary<string, S7Address>();
        readonly Dictionary<string, TagValue> _cache = new Dictionary<string, TagValue>();
        readonly Dictionary<string, TagValue> _pendingWrites = new Dictionary<string, TagValue>();
        readonly object _gate = new object();

        readonly float _pollInterval;
        float _pollTimer;
        int _busy;

        public SiemensS7Transport(
            string name,
            string ip,
            CpuType cpuType,
            short rack,
            short slot,
            float pollInterval = 0.2f)
        {
            Name = name;
            _plc = new Plc(cpuType, ip, rack, slot);
            _pollInterval = pollInterval;
        }

        public string Name { get; }
        public ProtocolKind Protocol => ProtocolKind.SiemensS7;
        public string Endpoint => $"{_plc.IP}:102";

        public TransportState State { get; private set; } = TransportState.Disconnected;
        public bool IsConnected => State == TransportState.Connected;

        public event Action<TransportState> StateChanged;

        /// <summary>把一个逻辑标签登记到 S7 点位。未登记的标签读写一律失败。</summary>
        public SiemensS7Transport Map(string tag, string address)
        {
            if (S7Address.TryParse(address, out var parsed)) _map[tag] = parsed;
            else throw new ArgumentException($"无法解析 S7 点位地址: {address}", nameof(address));
            return this;
        }

        public int MappedTagCount => _map.Count;

        public void Connect()
        {
            if (State == TransportState.Connecting || State == TransportState.Connected) return;
            SetState(TransportState.Connecting);

            Task.Run(() =>
            {
                try
                {
                    _plc.Open();
                    SetState(_plc.IsConnected ? TransportState.Connected : TransportState.Faulted);
                }
                catch (Exception)
                {
                    SetState(TransportState.Faulted);
                }
            });
        }

        public void Disconnect()
        {
            try { _plc.Close(); }
            catch (Exception) { /* 断开失败不影响状态归位 */ }
            SetState(TransportState.Disconnected);
        }

        public void Pump(float deltaTime)
        {
            if (_pollInterval <= 0f || _map.Count == 0) return;

            if (State != TransportState.Connected)
            {
                if (State == TransportState.Faulted)
                {
                    _pollTimer += deltaTime;
                    if (_pollTimer >= 2f)
                    {
                        _pollTimer = 0f;
                        Connect();
                    }
                }
                return;
            }

            _pollTimer += deltaTime;
            if (_pollTimer < _pollInterval) return;
            _pollTimer = 0f;

            if (System.Threading.Interlocked.Exchange(ref _busy, 1) == 1) return;
            Task.Run(Exchange);
        }

        /// <summary>一次完整的读写往返：先冲刷待写，再整批回读。</summary>
        void Exchange()
        {
            try
            {
                FlushWrites();
                ReadAll();
            }
            catch (Exception)
            {
                SetState(TransportState.Faulted);
            }
            finally
            {
                System.Threading.Interlocked.Exchange(ref _busy, 0);
            }
        }

        void FlushWrites()
        {
            List<KeyValuePair<string, TagValue>> batch;
            lock (_gate)
            {
                if (_pendingWrites.Count == 0) return;
                batch = new List<KeyValuePair<string, TagValue>>(_pendingWrites);
                _pendingWrites.Clear();
            }

            for (int i = 0; i < batch.Count; i++)
            {
                if (!_map.TryGetValue(batch[i].Key, out var address)) continue;
                var buffer = Encode(batch[i].Value, address);
                _plc.WriteBytes(DataType.DataBlock, address.DataBlock, address.StartByte, buffer);
            }
        }

        void ReadAll()
        {
            var fresh = new Dictionary<string, TagValue>();
            foreach (var pair in _map)
            {
                var address = pair.Value;
                var raw = _plc.ReadBytes(DataType.DataBlock, address.DataBlock, address.StartByte, address.ByteCount);
                fresh[pair.Key] = Decode(raw, address);
            }

            lock (_gate)
            {
                foreach (var pair in fresh) _cache[pair.Key] = pair.Value;
            }
        }

        public bool TryRead(string tag, out TagValue value)
        {
            if (!IsConnected)
            {
                value = default;
                return false;
            }

            lock (_gate)
            {
                return _cache.TryGetValue(tag, out value);
            }
        }

        public bool TryWrite(string tag, TagValue value)
        {
            if (!IsConnected || !_map.ContainsKey(tag)) return false;

            lock (_gate)
            {
                _pendingWrites[tag] = value;
            }
            return true;
        }

        void SetState(TransportState next)
        {
            if (State == next) return;
            State = next;
            StateChanged?.Invoke(next);
        }

        // ---------- S7 大端编解码 ----------

        static TagValue Decode(byte[] raw, S7Address address)
        {
            if (raw == null || raw.Length < address.ByteCount) return default;

            switch (address.Type)
            {
                case TagType.Bool:
                    return TagValue.From((raw[0] & (1 << address.Bit)) != 0);

                case TagType.Int:
                {
                    short signed = (short)((raw[0] << 8) | raw[1]);
                    return TagValue.From((int)signed);
                }

                default:
                {
                    int bits = (raw[0] << 24) | (raw[1] << 16) | (raw[2] << 8) | raw[3];
                    return TagValue.From(BitConverter.ToSingle(BitConverter.GetBytes(bits), 0));
                }
            }
        }

        static byte[] Encode(TagValue value, S7Address address)
        {
            switch (address.Type)
            {
                case TagType.Bool:
                    return new[] { value.AsBool() ? (byte)(1 << address.Bit) : (byte)0 };

                case TagType.Int:
                {
                    short signed = (short)value.AsInt();
                    return new[] { (byte)(signed >> 8), (byte)(signed & 0xFF) };
                }

                default:
                {
                    var raw = BitConverter.GetBytes(value.AsReal());
                    if (BitConverter.IsLittleEndian) Array.Reverse(raw);
                    return raw;
                }
            }
        }
    }
}
