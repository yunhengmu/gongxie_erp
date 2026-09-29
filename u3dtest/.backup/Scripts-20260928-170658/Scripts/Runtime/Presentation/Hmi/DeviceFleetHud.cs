using System;
using System.Collections.Generic;
using AgvDemo.Bootstrap;
using AgvDemo.Devices.Domain;
using AgvDemo.Devices.Messages;
using AgvDemo.Devices.Telemetry;
using MessagePipe;
using UnityEngine;
using VContainer;

namespace AgvDemo.Presentation.Hmi
{
    /// <summary>
    /// 设备状态面板（HMI）。数据全部取自遥测，不直接读设备内部状态。
    /// 提供逐台设备的「推进一个状态」与「自动/手动」切换，用于观察状态信号流转。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DeviceFleetHud : MonoBehaviour
    {
        [SerializeField] private ApplicationLifetimeScope _scope;
        [SerializeField] private bool _visible = true;

        DeviceFleetService _fleet;
        IDisposable _subscription;
        readonly List<string> _log = new List<string>();
        GUIStyle _title, _row, _hint;

        void Awake()
        {
            if (_scope == null) _scope = GetComponentInParent<ApplicationLifetimeScope>();
            if (_scope == null || _scope.Container == null)
            {
                enabled = false;
                return;
            }

            _fleet = _scope.Container.Resolve<DeviceFleetService>();

            // 订阅状态变化：HMI 只认消息，不认识设备
            var subscriber = _scope.Container.Resolve<ISubscriber<DeviceStateChanged>>();
            _subscription = subscriber.Subscribe(OnStateChanged);
        }

        void OnDestroy() => _subscription?.Dispose();

        void OnStateChanged(DeviceStateChanged message)
        {
            _log.Insert(0, $"{message.DeviceId,-8} {message.Previous.DisplayName(),-6} → {message.Current.DisplayName()}");
            if (_log.Count > 8) _log.RemoveAt(_log.Count - 1);
        }

        void OnGUI()
        {
            if (!_visible || _fleet == null) return;

            if (_title == null)
            {
                _title = new GUIStyle(GUI.skin.label) { fontSize = 15, fontStyle = FontStyle.Bold };
                _row = new GUIStyle(GUI.skin.label) { fontSize = 12 };
                _hint = new GUIStyle(GUI.skin.label) { fontSize = 11 };
            }

            const float width = 520f;
            const float rowHeight = 26f;
            float height = 150f + _fleet.Devices.Count * rowHeight + _log.Count * 16f;
            float x = Screen.width - width - 16f;
            const float y = 16f;

            GUI.Box(new Rect(x, y, width, height), GUIContent.none);
            GUI.Label(new Rect(x + 14f, y + 8f, width - 28f, 22f), "设备状态信号 · HMI", _title);

            var transport = _fleet.Transport;
            GUI.Label(new Rect(x + 14f, y + 32f, width - 28f, 18f),
                $"通道 {transport.Name} · {transport.Protocol} · {transport.Endpoint} → {transport.State}", _row);

            GUI.Label(new Rect(x + 14f, y + 50f, width - 28f, 18f),
                $"运行 {Count(MachineState.Moving) + Count(MachineState.Scanning) + Count(MachineState.Loading) + Count(MachineState.Processing) + Count(MachineState.Unloading)}"
                + $" ｜ 待机 {Count(MachineState.Idle)} ｜ 离线 {Count(MachineState.Offline)} ｜ 故障 {Count(MachineState.Fault)}", _row);

            float rowY = y + 76f;
            GUI.Label(new Rect(x + 14f, rowY, width - 28f, 18f),
                $"设备      状态        进度    电量    模式      操作", _hint);
            rowY += 20f;

            for (int i = 0; i < _fleet.Devices.Count; i++)
            {
                var device = _fleet.Devices[i];
                string id = device.Descriptor.Id;
                bool hasTelemetry = _fleet.TryGetTelemetry(id, out var telemetry);

                float progress = hasTelemetry ? telemetry.Progress : 0f;
                float battery = hasTelemetry ? telemetry.Battery : 0f;

                GUI.Label(new Rect(x + 14f, rowY, width - 320f, rowHeight),
                    $"{id,-9} {device.State.DisplayName(),-10} {progress * 100f,4:0}%  {battery * 100f,4:0}%  {(device.AutoAdvance ? "自动" : "手动")}", _row);

                if (GUI.Button(new Rect(x + width - 200f, rowY, 80f, rowHeight - 4f), "推进"))
                    _fleet.Advance(id);

                if (GUI.Button(new Rect(x + width - 114f, rowY, 100f, rowHeight - 4f),
                        device.AutoAdvance ? "切手动" : "切自动"))
                    _fleet.SetAutoAdvance(id, !device.AutoAdvance);

                rowY += rowHeight;
            }

            rowY += 4f;
            GUI.Label(new Rect(x + 14f, rowY, width - 28f, 18f), "状态变化（最新在上）", _hint);
            rowY += 16f;

            for (int i = 0; i < _log.Count; i++)
            {
                GUI.Label(new Rect(x + 14f, rowY, width - 28f, 16f), _log[i], _hint);
                rowY += 16f;
            }
        }

        int Count(MachineState state) => _fleet.Gateway.CountInState(state);
    }
}
