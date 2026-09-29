using System;
using System.Collections.Generic;
using UnityEngine.Networking;

namespace AgvDemo.Devices.Transport
{
    /// <summary>
    /// ABB 机器人通道，走 Robot Web Services（RWS）。
    ///
    /// **这一类不需要任何第三方库**：RWS 就是一个跑在控制器 80 端口的 REST 接口，
    /// 用 UnityWebRequest 直接请求即可。社区项目 Unity3D_Robotics_ABB（MIT）示范的也是同一套做法。
    ///
    /// 常用端点：
    ///   /rw/motionsystem/mechunits/ROB_1/jointtarget   关节角度
    ///   /rw/motionsystem/mechunits/ROB_1/cartesian     笛卡尔位姿
    ///   /rw/rapid/execution                            RAPID 执行状态
    ///   /rw/panel/ctrlstate                            控制器状态
    ///
    /// 请求串上带 ?json=1 让控制器返回 JSON；本类只做取回与缓存，
    /// 字段解析留给上层，避免把某个控制器版本的 schema 焊死。
    /// </summary>
    public sealed class AbbRwsTransport : ITransport
    {
        readonly string _host;
        readonly int _port;
        readonly string _user;
        readonly string _password;
        readonly float _pollInterval;
        readonly Dictionary<string, string> _endpoints = new Dictionary<string, string>();
        readonly Dictionary<string, TagValue> _cache = new Dictionary<string, TagValue>();

        UnityWebRequest _inFlight;
        string _inFlightTag;
        float _pollTimer;
        int _cursor;

        public AbbRwsTransport(
            string name,
            string host,
            int port = 80,
            string user = null,
            string password = null,
            float pollInterval = 0.5f)
        {
            Name = name;
            _host = host;
            _port = port;
            _user = user;
            _password = password;
            _pollInterval = pollInterval;
        }

        public string Name { get; }
        public ProtocolKind Protocol => ProtocolKind.AbbRws;
        public string Endpoint => $"http://{_host}:{_port}/rw";

        public TransportState State { get; private set; } = TransportState.Disconnected;
        public bool IsConnected => State == TransportState.Connected;

        public event Action<TransportState> StateChanged;

        public int ReceivedCount { get; private set; }
        public string LastError { get; private set; }

        /// <summary>登记一个逻辑标签与对应的 RWS 端点路径。</summary>
        public AbbRwsTransport Map(string tag, string path)
        {
            _endpoints[tag] = "/rw/" + path.TrimStart('/');
            return this;
        }

        public void Connect()
        {
            if (State == TransportState.Connecting || State == TransportState.Connected) return;
            SetState(TransportState.Connecting);
            // RWS 是无状态的 HTTP，首次请求成功即视为连通。
            RequestNext();
        }

        public void Disconnect()
        {
            Abort();
            SetState(TransportState.Disconnected);
        }

        public void Pump(float deltaTime)
        {
            switch (State)
            {
                case TransportState.Connecting:
                case TransportState.Connected:
                    break;

                case TransportState.Faulted:
                    _pollTimer += deltaTime;
                    if (_pollTimer >= 3f)
                    {
                        _pollTimer = 0f;
                        SetState(TransportState.Connecting);
                        RequestNext();
                    }
                    return;

                default:
                    return;
            }

            if (_inFlight != null)
            {
                if (!_inFlight.isDone) return;

                var finished = _inFlight;
                string tag = _inFlightTag;
                _inFlight = null;
                _inFlightTag = null;

                if (finished.result == UnityWebRequest.Result.Success)
                {
                    if (tag != null) _cache[tag] = TagValue.From(finished.downloadHandler.text);
                    ReceivedCount++;
                    if (State == TransportState.Connecting) SetState(TransportState.Connected);
                }
                else
                {
                    LastError = finished.error;
                    SetState(TransportState.Faulted);
                }

                finished.Dispose();
                return;
            }

            _pollTimer += deltaTime;
            if (_pollTimer < _pollInterval) return;
            _pollTimer = 0f;
            RequestNext();
        }

        /// <summary>轮转式请求：每次只发一个，避免把控制器 Web 服务打满。</summary>
        void RequestNext()
        {
            if (_endpoints.Count == 0) return;

            var keys = new List<string>(_endpoints.Keys);
            if (_cursor >= keys.Count) _cursor = 0;

            string tag = keys[_cursor];
            _cursor++;

            var request = UnityWebRequest.Get($"http://{_host}:{_port}{_endpoints[tag]}?json=1");
            request.timeout = 5;

            if (!string.IsNullOrEmpty(_user))
            {
                string token = Convert.ToBase64String(
                    System.Text.Encoding.UTF8.GetBytes($"{_user}:{_password}"));
                request.SetRequestHeader("Authorization", "Basic " + token);
            }

            _inFlight = request;
            _inFlightTag = tag;
            request.SendWebRequest();
        }

        void Abort()
        {
            if (_inFlight == null) return;
            try { _inFlight.Abort(); }
            catch (Exception) { /* 中止失败无需处理 */ }
            _inFlight.Dispose();
            _inFlight = null;
            _inFlightTag = null;
        }

        /// <summary>RWS 是只读遥测通道，写入一律拒绝。</summary>
        public bool TryRead(string tag, out TagValue value) => _cache.TryGetValue(tag, out value);

        public bool TryWrite(string tag, TagValue value) => false;

        void SetState(TransportState next)
        {
            if (State == next) return;
            State = next;
            StateChanged?.Invoke(next);
        }
    }
}
