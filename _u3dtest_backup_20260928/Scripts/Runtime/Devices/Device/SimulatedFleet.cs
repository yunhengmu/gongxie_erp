using System.Collections.Generic;
using AgvDemo.Devices.Transport;

namespace AgvDemo.Devices
{
    /// <summary>
    /// 设备编队定义：按工业现场的典型构成列出一套设备，每台标注它真实使用的接入协议。
    /// 清单是静态的（不依赖通道），因此点位表和通道都能先于设备实例生成。
    /// </summary>
    public static class SimulatedFleet
    {
        const float LaneZ = -11f;
        const float PatrolDepth = 3.2f;

        public readonly struct Spec
        {
            public readonly DeviceDescriptor Descriptor;
            public readonly float HomeX;
            public readonly float Travel;
            public readonly float BatteryDrain;
            public readonly float CycleSeconds;

            public Spec(DeviceDescriptor descriptor, float homeX, float travel, float batteryDrain, float cycleSeconds)
            {
                Descriptor = descriptor;
                HomeX = homeX;
                Travel = travel;
                BatteryDrain = batteryDrain;
                CycleSeconds = cycleSeconds;
            }
        }

        static readonly List<Spec> Table = new List<Spec>
        {
            new Spec(new DeviceDescriptor("PLC-01", DeviceKind.Plc, "Siemens S7-1500", ProtocolKind.SiemensS7),
                -7.5f, 0f, 0f, 12f),

            new Spec(new DeviceDescriptor("AGV-01", DeviceKind.Agv, "AGV-1000", ProtocolKind.SiemensS7),
                -4.5f, PatrolDepth, 0.6f, 26f),

            new Spec(new DeviceDescriptor("STK-01", DeviceKind.Stacker, "ASRS Stacker", ProtocolKind.SiemensS7),
                -1.5f, PatrolDepth * 0.8f, 1.1f, 34f),

            new Spec(new DeviceDescriptor("ARM-01", DeviceKind.RobotArm, "ABB IRB 120", ProtocolKind.AbbRws),
                1.5f, 0f, 0.4f, 18f),

            new Spec(new DeviceDescriptor("CNC-01", DeviceKind.Cnc, "3-Axis Mill", ProtocolKind.OpcUa),
                4.5f, 0f, 0.5f, 40f),

            new Spec(new DeviceDescriptor("UAV-01", DeviceKind.Drone, "Quadrotor", ProtocolKind.RosTcp),
                7.5f, PatrolDepth * 1.4f, 1.6f, 22f),
        };

        static readonly List<DeviceDescriptor> DescriptorList = BuildDescriptors();

        public static IReadOnlyList<Spec> Specs => Table;

        /// <summary>设备身份清单。生成点位表、构造通道时先用到它。</summary>
        public static IReadOnlyList<DeviceDescriptor> Descriptors => DescriptorList;

        public static List<IDevice> Create(ITransport transport)
        {
            var devices = new List<IDevice>(Table.Count);
            for (int i = 0; i < Table.Count; i++)
            {
                var spec = Table[i];
                devices.Add(new SimulatedDevice(
                    spec.Descriptor,
                    transport,
                    spec.HomeX,
                    LaneZ,
                    spec.HomeX,
                    LaneZ + spec.Travel,
                    spec.CycleSeconds,
                    spec.BatteryDrain));
            }
            return devices;
        }

        static List<DeviceDescriptor> BuildDescriptors()
        {
            var list = new List<DeviceDescriptor>(Table.Count);
            for (int i = 0; i < Table.Count; i++) list.Add(Table[i].Descriptor);
            return list;
        }
    }
}
