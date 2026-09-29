using System.Collections.Generic;
using UnityEngine;
using AgvDemo.Devices;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Gameplay
{
    /// <summary>
    /// 设备编队的可视化层。所有显示数据都取自 DeviceGateway 的遥测出口，
    /// 不直接读设备内部状态 —— 「界面显示什么」与「设备实际是什么」彻底分开。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DeviceFleetView : MonoBehaviour
    {
        [Header("通道")]
        [Tooltip("现场接入配置。默认 Simulated 可直接跑；换实机只改这里，不用改代码")]
        public TransportConfig transportConfig = new TransportConfig();

        [Header("遥测")]
        [Tooltip("遥测上报周期（秒）")]
        public float publishInterval = 0.5f;
        [Tooltip("模拟通道的随机掉线概率，0 表示稳定连接（仅 Simulated 生效）")]
        [Range(0f, 0.2f)]
        public float faultProbability;

        [Header("显示")]
        public bool showHud = true;

        static readonly Color OfflineColor = new Color(0.42f, 0.45f, 0.50f);
        static readonly Color IdleColor = new Color(0.23f, 0.51f, 0.96f);
        static readonly Color WorkingColor = new Color(0.13f, 0.77f, 0.37f);
        static readonly Color FaultColor = new Color(0.94f, 0.27f, 0.27f);

        DeviceGateway _gateway;
        ITransport _transport;
        InMemoryTelemetrySink _memorySink;
        JsonLineTelemetrySink _jsonSink;
        Transform _root;
        readonly List<DeviceProxy> _proxies = new List<DeviceProxy>();

        GUIStyle _titleStyle;
        GUIStyle _rowStyle;
        GUIStyle _hintStyle;

        void Awake()
        {
            _root = new GameObject("DeviceFleet").transform;
            _root.SetParent(transform.parent, false);

            _memorySink = new InMemoryTelemetrySink();
            _jsonSink = new JsonLineTelemetrySink();

            _transport = transportConfig.Kind == FleetTransportKind.Simulated
                ? new SimulatedTransport("PLC-SIM-01", faultProbability: faultProbability)
                : TransportFactory.Create(transportConfig, SimulatedFleet.Descriptors);

            _gateway = new DeviceGateway { PublishInterval = publishInterval };
            _gateway.AddTransport(_transport);
            _gateway.RegisterAll(SimulatedFleet.Create(_transport));
            _gateway.AddSink(_memorySink);
            _gateway.AddSink(_jsonSink);

            DemoHost.Instance(_gateway);
            DemoHost.Instance<ITransport>(_transport);

            foreach (var device in _gateway.Devices)
                _proxies.Add(BuildProxy(device));

            Debug.Log($"[DeviceFleet] 已接入 {_gateway.Devices.Count} 台设备，通道 {_transport.Endpoint}，遥测周期 {publishInterval}s。");
        }

        void Update()
        {
            _gateway.Tick(Time.deltaTime);

            float time = Time.time;
            float deltaTime = Time.deltaTime;
            for (int i = 0; i < _proxies.Count; i++)
                _proxies[i].Apply(_memorySink, time, deltaTime);
        }

        void OnDestroy() => _transport?.Disconnect();

        // ---------- 可视对象 ----------

        DeviceProxy BuildProxy(IDevice device)
        {
            var root = new GameObject(device.Descriptor.Id).transform;
            root.SetParent(_root, false);

            var proxy = new DeviceProxy(device, root);

            switch (device.Descriptor.Kind)
            {
                case DeviceKind.Plc: BuildPlc(root, proxy); break;
                case DeviceKind.Agv: BuildAgv(root, proxy); break;
                case DeviceKind.Stacker: BuildStacker(root, proxy); break;
                case DeviceKind.RobotArm: BuildRobotArm(root, proxy); break;
                case DeviceKind.Cnc: BuildCnc(root, proxy); break;
                case DeviceKind.Drone: BuildDrone(root, proxy); break;
            }

            proxy.CaptureBaseHeight();
            return proxy;
        }

        void BuildPlc(Transform root, DeviceProxy proxy)
        {
            proxy.AddBox("Cabinet", new Vector3(0f, 1.1f, 0f), new Vector3(1.1f, 2.2f, 0.8f), new Color(0.30f, 0.33f, 0.38f));
            proxy.AddBox("Panel", new Vector3(0f, 1.5f, -0.42f), new Vector3(0.7f, 0.5f, 0.06f), new Color(0.10f, 0.12f, 0.16f));
            proxy.AddIndicator("Lamp", new Vector3(0.35f, 2.0f, -0.44f), new Vector3(0.16f, 0.16f, 0.08f));
        }

        void BuildAgv(Transform root, DeviceProxy proxy)
        {
            proxy.AddBox("Chassis", new Vector3(0f, 0.22f, 0f), new Vector3(1.5f, 0.3f, 1.1f), new Color(0.95f, 0.6f, 0.15f));
            proxy.AddBox("Deck", new Vector3(0f, 0.46f, 0f), new Vector3(1.3f, 0.16f, 0.95f), new Color(0.55f, 0.36f, 0.10f));
            proxy.AddIndicator("Beacon", new Vector3(0f, 0.70f, 0f), new Vector3(0.22f, 0.22f, 0.22f));
        }

        void BuildStacker(Transform root, DeviceProxy proxy)
        {
            proxy.AddBox("Base", new Vector3(0f, 0.12f, 0f), new Vector3(2.4f, 0.24f, 0.9f), new Color(0.32f, 0.35f, 0.40f));
            proxy.AddBox("Column", new Vector3(-0.95f, 2.0f, 0f), new Vector3(0.22f, 4f, 0.5f), new Color(0.40f, 0.44f, 0.50f));
            proxy.Moving = proxy.AddBox("Trolley", new Vector3(0f, 1.0f, 0f), new Vector3(1.2f, 0.35f, 0.8f), new Color(0.88f, 0.72f, 0.20f));
        }

        void BuildRobotArm(Transform root, DeviceProxy proxy)
        {
            var basePart = proxy.CreatePrimitive(PrimitiveType.Cylinder, "Base",
                new Vector3(0f, 0.3f, 0f), new Vector3(0.9f, 0.3f, 0.9f), new Color(0.30f, 0.33f, 0.38f));

            var shoulder = new GameObject("Shoulder").transform;
            shoulder.SetParent(root, false);
            shoulder.localPosition = new Vector3(0f, 0.6f, 0f);

            var upperArm = new GameObject("UpperArm").transform;
            upperArm.SetParent(shoulder, false);
            var upperProxy = proxy.ForChild(upperArm);
            upperProxy.AddBox("UpperArmMesh", new Vector3(0f, 0.55f, 0f), new Vector3(0.28f, 1.1f, 0.28f), new Color(0.91f, 0.55f, 0.16f));

            var elbow = new GameObject("Elbow").transform;
            elbow.SetParent(shoulder, false);
            elbow.localPosition = new Vector3(0f, 1.1f, 0f);
            var elbowProxy = proxy.ForChild(elbow);
            elbowProxy.AddBox("Forearm", new Vector3(0f, 0.45f, 0f), new Vector3(0.22f, 0.9f, 0.22f), new Color(0.91f, 0.55f, 0.16f));
            elbowProxy.AddBox("Tool", new Vector3(0f, 0.95f, 0f), new Vector3(0.16f, 0.2f, 0.16f), new Color(0.15f, 0.16f, 0.2f));

            proxy.Moving = shoulder;
            proxy.Secondary = elbow;
            basePart.name = "ArmBase";
        }

        void BuildCnc(Transform root, DeviceProxy proxy)
        {
            proxy.AddBox("Body", new Vector3(0f, 0.9f, 0f), new Vector3(2f, 1.8f, 1.6f), new Color(0.26f, 0.29f, 0.33f));
            proxy.AddBox("Door", new Vector3(0f, 0.95f, -0.83f), new Vector3(1.5f, 1.2f, 0.06f), new Color(0.13f, 0.15f, 0.19f));

            var spindle = proxy.CreatePrimitive(PrimitiveType.Cylinder, "Spindle",
                new Vector3(0f, 0.95f, -0.78f), new Vector3(0.22f, 0.22f, 0.22f), WorkingColor);
            spindle.localRotation = Quaternion.Euler(90f, 0f, 0f);

            proxy.AddIndicator("SpindleLamp", new Vector3(0.75f, 1.75f, -0.78f), new Vector3(0.14f, 0.14f, 0.07f));
            proxy.AddSpinner(spindle);
        }

        void BuildDrone(Transform root, DeviceProxy proxy)
        {
            proxy.AddBox("Frame", new Vector3(0f, 1.4f, 0f), new Vector3(0.7f, 0.12f, 0.7f), new Color(0.20f, 0.22f, 0.26f));
            proxy.AddBox("Payload", new Vector3(0f, 1.24f, 0f), new Vector3(0.26f, 0.2f, 0.26f), new Color(0.55f, 0.40f, 0.16f));

            var offsets = new[]
            {
                new Vector3(-0.38f, 1.44f, -0.38f),
                new Vector3(0.38f, 1.44f, -0.38f),
                new Vector3(-0.38f, 1.44f, 0.38f),
                new Vector3(0.38f, 1.44f, 0.38f),
            };

            for (int i = 0; i < offsets.Length; i++)
            {
                var rotor = proxy.CreatePrimitive(PrimitiveType.Cylinder, $"Rotor{i}",
                    offsets[i], new Vector3(0.34f, 0.015f, 0.34f), new Color(0.62f, 0.66f, 0.72f));
                proxy.AddSpinner(rotor);
            }
        }

        // ---------- HUD ----------

        void OnGUI()
        {
            if (!showHud || _gateway == null) return;

            if (_titleStyle == null)
            {
                _titleStyle = new GUIStyle(GUI.skin.label) { fontSize = 15, fontStyle = FontStyle.Bold };
                _rowStyle = new GUIStyle(GUI.skin.label) { fontSize = 12 };
                _hintStyle = new GUIStyle(GUI.skin.label) { fontSize = 11 };
            }

            const float width = 470f;
            const float rowHeight = 17f;
            float height = 128f + _proxies.Count * rowHeight;
            float x = Screen.width - width - 16f;
            float y = 16f;

            GUI.Box(new Rect(x, y, width, height), GUIContent.none);

            GUI.Label(new Rect(x + 14f, y + 8f, width - 28f, 22f), "设备接入层 · IoT", _titleStyle);

            GUI.Label(new Rect(x + 14f, y + 32f, width - 28f, 18f),
                $"通道 {_transport.Name} · {_transport.Protocol} · {_transport.Endpoint}", _rowStyle);

            GUI.Label(new Rect(x + 14f, y + 50f, width - 28f, 18f),
                $"连接状态 {_transport.State} ｜ 设备 {_gateway.Devices.Count} 台", _rowStyle);

            GUI.Label(new Rect(x + 14f, y + 68f, width - 28f, 18f),
                $"运行 {_gateway.CountByStatus(DeviceStatus.Working)} ｜ 空闲 {_gateway.CountByStatus(DeviceStatus.Idle)} ｜ 离线 {_gateway.CountByStatus(DeviceStatus.Offline)} ｜ 故障 {_gateway.CountByStatus(DeviceStatus.Fault)}", _rowStyle);

            GUI.Label(new Rect(x + 14f, y + 86f, width - 28f, 18f),
                $"遥测已上报 {_gateway.PublishedCount} 条 · 周期 {publishInterval:0.##}s · 本地缓存 {_memorySink.Count} 条", _hintStyle);

            float rowY = y + 108f;
            GUI.Label(new Rect(x + 14f, rowY, width - 28f, rowHeight), "设备          机型                  协议          状态", _hintStyle);
            rowY += rowHeight;

            for (int i = 0; i < _proxies.Count; i++)
            {
                var device = _proxies[i].Device;
                var descriptor = device.Descriptor;
                string battery = _memorySink.TryGet(descriptor.Id, out var telemetry)
                    ? $"{telemetry.Battery * 100f:0}%"
                    : "—";

                GUI.Label(new Rect(x + 14f, rowY, width - 28f, rowHeight),
                    $"{descriptor.Id,-12} {descriptor.Model,-21} {descriptor.Protocol,-12} {device.Status,-9} {battery}", _rowStyle);
                rowY += rowHeight;
            }

            GUI.Label(new Rect(x + 14f, rowY + 3f, width - 28f, 20f),
                "上报载荷: " + Truncate(_jsonSink.LastPayload, 70), _hintStyle);
        }

        static string Truncate(string value, int max)
        {
            if (string.IsNullOrEmpty(value)) return "—";
            return value.Length <= max ? value : value.Substring(0, max) + "…";
        }

        // ---------- 代理 ----------

        sealed class DeviceProxy
        {
            readonly IDevice _device;
            readonly Transform _root;
            readonly List<Renderer> _indicators = new List<Renderer>();
            readonly List<Transform> _spinners = new List<Transform>();

            float _spinAngle;

            public IDevice Device => _device;
            public Transform Moving;
            public Transform Secondary;
            public float BaseY;

            public DeviceProxy(IDevice device, Transform root)
            {
                _device = device;
                _root = root;
            }

            /// <summary>机械臂的关节需要各自独立的绘制上下文，这里派生一个共享设备身份的代理。</summary>
            public DeviceProxy ForChild(Transform child) => new DeviceProxy(_device, child);

            public void CaptureBaseHeight() => BaseY = _root.localPosition.y;

            public Transform AddBox(string name, Vector3 localPos, Vector3 scale, Color color)
            {
                var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
                go.name = name;
                go.transform.SetParent(_root, false);
                go.transform.localPosition = localPos;
                go.transform.localScale = scale;
                Paint(go.GetComponent<Renderer>(), color);
                return go.transform;
            }

            public Transform CreatePrimitive(PrimitiveType type, string name, Vector3 localPos, Vector3 scale, Color color)
            {
                var go = GameObject.CreatePrimitive(type);
                go.name = name;
                go.transform.SetParent(_root, false);
                go.transform.localPosition = localPos;
                go.transform.localScale = scale;
                Paint(go.GetComponent<Renderer>(), color);
                return go.transform;
            }

            /// <summary>状态指示灯：颜色随设备状态与心跳闪动。</summary>
            public void AddIndicator(string name, Vector3 localPos, Vector3 scale)
            {
                var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
                go.name = name;
                go.transform.SetParent(_root, false);
                go.transform.localPosition = localPos;
                go.transform.localScale = scale;
                var renderer = go.GetComponent<Renderer>();
                Paint(renderer, WorkingColor);
                _indicators.Add(renderer);
            }

            public void AddSpinner(Transform spinner) => _spinners.Add(spinner);

            void Paint(Renderer renderer, Color color)
            {
                var shader = Shader.Find("Standard");
                var material = new Material(shader) { color = color };
                renderer.material = material;
            }

            public void Apply(InMemoryTelemetrySink sink, float time, float deltaTime)
            {
                if (!sink.TryGet(_device.Descriptor.Id, out var telemetry)) return;
                ApplyInternal(telemetry, time, deltaTime);
            }

            void ApplyInternal(DeviceTelemetry telemetry, float time, float deltaTime)
            {
                _root.localPosition = new Vector3(telemetry.X, BaseY, telemetry.Z);

                if (_device.Descriptor.Kind == DeviceKind.Drone)
                    _root.localPosition += new Vector3(0f, Mathf.Sin(time * 1.6f) * 0.12f, 0f);

                _spinAngle += deltaTime * (_device.Status == DeviceStatus.Working ? 1100f : 0f);
                if (_spinAngle > 360f) _spinAngle -= 360f;

                if (_spinners.Count > 0)
                {
                    for (int i = 0; i < _spinners.Count; i++)
                    {
                        var spinner = _spinners[i];
                        if (spinner == null) continue;

                        if (_device.Descriptor.Kind == DeviceKind.Cnc)
                            spinner.localRotation = Quaternion.Euler(90f, _spinAngle, 0f);
                        else
                            spinner.localRotation = Quaternion.Euler(0f, _spinAngle * 2f, 0f);
                    }
                }

                if (Moving != null && _device.Descriptor.Kind == DeviceKind.RobotArm)
                {
                    float swing = Mathf.Sin(time * 0.9f) * 26f;
                    Moving.localRotation = Quaternion.Euler(swing, time * 22f, 0f);
                    if (Secondary != null)
                        Secondary.localRotation = Quaternion.Euler(-swing * 1.4f, 0f, 0f);
                }
                else if (Moving != null && _device.Descriptor.Kind == DeviceKind.Stacker)
                {
                    float lift = 0.1f + telemetry.Load * 0.85f;
                    Moving.localPosition = new Vector3(0f, 1.0f + lift * 2.4f, 0f);
                }

                Color statusColor = ColorFor(_device.Status);
                float pulse = _device.Status == DeviceStatus.Working
                    ? 0.45f + 0.55f * Mathf.Abs(Mathf.Sin(time * 3.6f))
                    : 1f;
                Color lit = Color.Lerp(Color.black, statusColor, pulse);

                for (int i = 0; i < _indicators.Count; i++)
                {
                    var indicator = _indicators[i];
                    if (indicator != null) indicator.material.color = lit;
                }
            }

            static Color ColorFor(DeviceStatus status)
            {
                switch (status)
                {
                    case DeviceStatus.Working: return WorkingColor;
                    case DeviceStatus.Idle: return IdleColor;
                    case DeviceStatus.Fault: return FaultColor;
                    default: return OfflineColor;
                }
            }
        }
    }
}
