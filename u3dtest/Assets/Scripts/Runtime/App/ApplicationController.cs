using UnityEngine;
using VContainer;

namespace AgvDemo.App
{
    /// <summary>
    /// 应用驱动入口。挂在场景里的 Startup 对象上，依赖由容器注入。
    /// 它只负责把每帧时间喂给应用服务，不含任何业务规则。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ApplicationController : MonoBehaviour
    {
        DeviceFleetService _fleet;

        [Inject]
        void Construct(DeviceFleetService fleet) => _fleet = fleet;

        void Start()
        {
            Debug.Log($"[AgvDemo] 已装配 {_fleet.Devices.Count} 台设备，通道 {_fleet.TransportEndpoint}。");
        }

        void Update()
        {
            if (_fleet != null) _fleet.Tick(Time.deltaTime);
        }
    }
}