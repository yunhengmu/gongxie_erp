namespace AgvDemo.Devices
{
    /// <summary>
    /// 工位设备种类。每一种都对应工业现场一类真实的被控对象，
    /// 也对应一套现场接入协议（见 ProtocolKind）。
    /// </summary>
    public enum DeviceKind
    {
        /// <summary>AGV / RGV，地面移动设备。</summary>
        Agv,
        /// <summary>堆垛机，立体库巷道内的升降搬运设备。</summary>
        Stacker,
        /// <summary>PLC 控制器本身，作为可观测对象（CPU 负载、扫描周期）。</summary>
        Plc,
        /// <summary>工业机械臂。</summary>
        RobotArm,
        /// <summary>数控机床。</summary>
        Cnc,
        /// <summary>无人机。</summary>
        Drone,
    }

    public enum DeviceStatus
    {
        Offline,
        Idle,
        Working,
        Fault,
    }
}
