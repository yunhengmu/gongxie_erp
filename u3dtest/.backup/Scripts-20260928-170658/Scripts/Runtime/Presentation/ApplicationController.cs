using AgvDemo.Bootstrap;
using UnityEngine;
using VContainer;

namespace AgvDemo.Presentation
{
    /// <summary>
    /// 表现层的唯一驱动入口。挂在场景里的 Startup 对象上，
    /// 自己不组装依赖，只从 LifetimeScope 取。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ApplicationController : MonoBehaviour
    {
        [SerializeField] private ApplicationLifetimeScope _scope;

        DeviceFleetService _fleet;

        void Awake()
        {
            if (_scope == null) _scope = GetComponentInParent<ApplicationLifetimeScope>();
            if (_scope == null || _scope.Container == null)
            {
                Debug.LogError("[AgvDemo] 找不到 ApplicationLifetimeScope，应用未装配。");
                enabled = false;
                return;
            }

            _fleet = _scope.Container.Resolve<DeviceFleetService>();
            Debug.Log($"[AgvDemo] 已装配 {_fleet.Devices.Count} 台设备，通道 {_fleet.Transport.Endpoint}。");
        }

        void Update()
        {
            if (_fleet != null) _fleet.Tick(Time.deltaTime);
        }
    }
}
