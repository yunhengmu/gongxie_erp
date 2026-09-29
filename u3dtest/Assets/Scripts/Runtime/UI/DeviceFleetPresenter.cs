using System.Collections.Generic;
using AgvDemo.Core.Fleet;
using UnityEngine;
using VContainer;

namespace AgvDemo.UI
{
    /// <summary>
    /// 把场景里已摆好的 DeviceView（Prefab 实例）绑定到设备读模型。
    /// 场景里摆几台就绑几台 —— 设备数量由**编辑器摆放**决定，不由代码决定。
    /// 依赖由容器注入，不依赖对象层级，也不去找静态入口。
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class DeviceFleetPresenter : MonoBehaviour
    {
        readonly List<DeviceView> _views = new List<DeviceView>();

        IFleetReadModel _fleet;

        public IReadOnlyList<DeviceView> Views => _views;

        [Inject]
        void Construct(IFleetReadModel fleet) => _fleet = fleet;

        void Start()
        {
            GetComponentsInChildren(true, _views);

            for (int i = 0; i < _views.Count; i++)
                _views[i].Bind(_fleet);

            Debug.Log($"[AgvDemo] 已绑定 {_views.Count} 个设备视图。");
        }
    }
}