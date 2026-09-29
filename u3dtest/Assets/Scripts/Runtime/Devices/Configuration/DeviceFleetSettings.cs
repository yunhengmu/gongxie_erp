using AgvDemo.Devices.Transport;
using UnityEngine;

namespace AgvDemo.Devices.Configuration
{
    /// <summary>
    /// 设备编队的运行配置。用 ScriptableObject 承载，现场调参不需要改代码、也不需要重新编译，
    /// 非程序员也能在 Inspector 里改。
    /// </summary>
    [CreateAssetMenu(menuName = "AgvDemo/Device Fleet Settings", fileName = "DeviceFleetSettings")]
    public sealed class DeviceFleetSettings : ScriptableObject
    {
        [Header("通道")]
        public TransportConfig Transport = new TransportConfig();

        [Header("运行")]
        [Tooltip("状态机流转速度倍率。1 为正常，调大则状态切换更快")]
        [Range(0.1f, 5f)]
        public float SpeedScale = 1f;

        [Tooltip("勾选后所有设备进入手动模式，用按钮/接口逐个切换状态")]
        public bool StartInManualMode;

        [Header("遥测")]
        [Tooltip("状态信号上报周期（秒）。现场不低于 0.1")]
        [Range(0.05f, 5f)]
        public float PublishInterval = 0.5f;
    }
}
