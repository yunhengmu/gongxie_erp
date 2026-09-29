namespace AgvDemo.Core.Domain
{
    /// <summary>
    /// 设备状态信号。这是对外契约：上位机 / WCS / HMI 只认这个编码，
    /// 不关心设备内部实现。**数值一经现场使用就不能再改动**。
    /// </summary>
    public enum MachineState
    {
        /// <summary>通道断开，设备失联。</summary>
        Offline = 0,
        /// <summary>待机，等待任务。</summary>
        Idle = 1,
        /// <summary>行走中。</summary>
        Moving = 2,
        /// <summary>扫码 / 认址中。</summary>
        Scanning = 3,
        /// <summary>取货中。</summary>
        Loading = 4,
        /// <summary>作业中（加工 / 装配）。</summary>
        Processing = 5,
        /// <summary>放货中。</summary>
        Unloading = 6,
        /// <summary>充电中。</summary>
        Charging = 7,
        /// <summary>故障，已停机。</summary>
        Fault = 9,
    }

    public static class MachineStateExtensions
    {
        public static string DisplayName(this MachineState state)
        {
            switch (state)
            {
                case MachineState.Offline: return "离线";
                case MachineState.Idle: return "待机";
                case MachineState.Moving: return "移动中";
                case MachineState.Scanning: return "扫码中";
                case MachineState.Loading: return "取货中";
                case MachineState.Processing: return "作业中";
                case MachineState.Unloading: return "放货中";
                case MachineState.Charging: return "充电中";
                case MachineState.Fault: return "故障";
                default: return state.ToString();
            }
        }

        public static bool IsWorking(this MachineState state)
            => state == MachineState.Moving || state == MachineState.Scanning
               || state == MachineState.Loading || state == MachineState.Processing
               || state == MachineState.Unloading;

        public static bool ConsumesPower(this MachineState state)
            => state != MachineState.Idle && state != MachineState.Offline && state != MachineState.Fault;
    }
}
