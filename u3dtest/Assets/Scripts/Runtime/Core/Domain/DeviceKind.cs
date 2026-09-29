namespace AgvDemo.Core.Domain
{
    /// <summary>设备种类。每一类对应现场一种被控对象，也决定它的状态流转序列。</summary>
    public enum DeviceKind
    {
        Agv,
        Stacker,
        Plc,
        RobotArm,
        Cnc,
        Drone,
    }
}
