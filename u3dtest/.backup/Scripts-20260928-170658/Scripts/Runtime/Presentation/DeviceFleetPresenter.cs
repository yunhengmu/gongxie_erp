using System.Collections.Generic;
using AgvDemo.Bootstrap;
using UnityEngine;
using VContainer;

namespace AgvDemo.Presentation
{
    /// <summary>
    /// 把场景里已摆好的 DeviceView（Prefab 实例）绑定到设备服务。
    /// 场景里摆几台就绑几台 —— 设备数量由**编辑器摆放**决定，不由代码决定。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DeviceFleetPresenter : MonoBehaviour
    {
        [SerializeField] private ApplicationLifetimeScope _scope;

        readonly List<DeviceView> _views = new List<DeviceView>();

        public IReadOnlyList<DeviceView> Views => _views;

        void Awake()
        {
            if (_scope == null) _scope = GetComponentInParent<ApplicationLifetimeScope>();
            if (_scope == null || _scope.Container == null)
            {
                enabled = false;
                return;
            }

            var fleet = _scope.Container.Resolve<DeviceFleetService>();
            GetComponentsInChildren(true, _views);

            for (int i = 0; i < _views.Count; i++)
                _views[i].Bind(fleet);

            Debug.Log($"[AgvDemo] 已绑定 {_views.Count} 个设备视图。");
        }
    }
}
