using AgvDemo.Core.Domain;
using AgvDemo.Core.Fleet;
using AgvDemo.Core.Telemetry;
using UnityEngine;

namespace AgvDemo.UI
{
    /// <summary>
    /// 设备视图：挂在设备 Prefab 上，只负责把遥测映射成画面。
    ///
    /// 设备在场景里是 **Prefab 实例**，可以在编辑器里直接摆位、换模型、调材质；
    /// 这个脚本只做数据绑定，不创建任何几何体。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DeviceView : MonoBehaviour
    {
        [Header("身份")]
        [Tooltip("对应设备编队里的设备 Id，例如 AGV-01")]
        [SerializeField] private string _deviceId = "AGV-01";
        [SerializeField] private DeviceKind _kind = DeviceKind.Agv;

        [Header("部件引用（在 Prefab 上挂好）")]
        [Tooltip("地面锚点。设备位置由它体现，模型放在它下面")]
        [SerializeField] private Transform _anchor;
        [Tooltip("随状态运动或旋转的部件，可留空")]
        [SerializeField] private Transform _movingPart;
        [Tooltip("状态指示灯，颜色随状态变化，可留空")]
        [SerializeField] private Renderer _indicator;

        [Header("外观")]
        [SerializeField] private Color _workingColor = new Color(0.13f, 0.77f, 0.37f);
        [SerializeField] private Color _idleColor = new Color(0.23f, 0.51f, 0.96f);
        [SerializeField] private Color _faultColor = new Color(0.94f, 0.27f, 0.27f);
        [SerializeField] private Color _offlineColor = new Color(0.42f, 0.45f, 0.50f);

        IFleetReadModel _fleet;
        float _spinAngle;

        public string DeviceId => _deviceId;
        public DeviceKind Kind => _kind;
        public Transform Anchor => _anchor != null ? _anchor : transform;

        /// <summary>由 DeviceFleetPresenter 在场景启动时绑定。</summary>
        public void Bind(IFleetReadModel fleet) => _fleet = fleet;

        void Update()
        {
            if (_fleet == null) return;
            if (!_fleet.TryGetTelemetry(_deviceId, out var telemetry)) return;

            Apply(telemetry, Time.time, Time.deltaTime);
        }

        void Apply(DeviceTelemetry telemetry, float time, float deltaTime)
        {
            var anchor = Anchor;
            anchor.localPosition = new Vector3(telemetry.X, anchor.localPosition.y, telemetry.Z);

            if (_kind == DeviceKind.Drone)
                anchor.localPosition += new Vector3(0f, Mathf.Sin(time * 1.6f) * 0.12f, 0f);

            if (_movingPart != null)
            {
                if (_kind == DeviceKind.Cnc)
                {
                    _spinAngle += deltaTime * (telemetry.State == MachineState.Processing ? 1100f : 0f);
                    if (_spinAngle > 360f) _spinAngle -= 360f;
                    _movingPart.localRotation = Quaternion.Euler(90f, _spinAngle, 0f);
                }
                else if (_kind == DeviceKind.RobotArm)
                {
                    float swing = Mathf.Sin(time * 0.9f) * 26f;
                    _movingPart.localRotation = Quaternion.Euler(swing, time * 22f, 0f);
                }
                else if (_kind == DeviceKind.Stacker)
                {
                    _movingPart.localPosition = new Vector3(0f, 1f + telemetry.Progress * 2.4f, 0f);
                }
            }

            if (_indicator == null) return;

            Color status = ColorFor(telemetry.State);
            float pulse = telemetry.State.IsWorking() ? 0.45f + 0.55f * Mathf.Abs(Mathf.Sin(time * 3.6f)) : 1f;
            _indicator.material.color = Color.Lerp(Color.black, status, pulse);
        }

        Color ColorFor(MachineState state)
        {
            switch (state)
            {
                case MachineState.Idle: return _idleColor;
                case MachineState.Offline: return _offlineColor;
                case MachineState.Fault: return _faultColor;
                default: return _workingColor;
            }
        }

        /// <summary>编辑器生成 Prefab 时用来注入部件引用。</summary>
        public void EditorAssign(Transform anchor, Transform movingPart, Renderer indicator)
        {
            _anchor = anchor;
            _movingPart = movingPart;
            _indicator = indicator;
        }

        public void EditorSetIdentity(string deviceId, DeviceKind kind)
        {
            _deviceId = deviceId;
            _kind = kind;
        }
    }
}