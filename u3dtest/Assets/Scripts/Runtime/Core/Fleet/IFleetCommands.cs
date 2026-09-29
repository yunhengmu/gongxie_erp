namespace AgvDemo.Core.Fleet
{
    /// <summary>
    /// 设备编队的操作入口。表现层只认这个契约，
    /// 不直接触碰设备，因此换界面不影响设备行为。
    /// </summary>
    public interface IFleetCommands
    {
        /// <summary>手动推进一台设备一个状态（该设备需处于手动模式）。</summary>
        bool Advance(string deviceId);

        /// <summary>切换一台设备的自动 / 手动流转模式。</summary>
        void SetAutoAdvance(string deviceId, bool autoAdvance);
    }
}