using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;
using NUnit.Framework;

namespace AgvDemo.Core.Tests
{
    public class DeviceTelemetryTests
    {
        [Test]
        public void StateCode_MirrorsMachineStateValues()
        {
            var telemetry = new DeviceTelemetry(
                "AGV-01", DeviceKind.Agv, MachineState.Moving,
                1f, 2f, 0.5f, 0.25f, 3, 100L);

            // PLC 状态字与 MachineState 的数值绑定，现场改枚举值即等于改协议
            Assert.AreEqual((int)MachineState.Moving, telemetry.StateCode);
        }

        [Test]
        public void Constructor_KeepsEveryField()
        {
            var telemetry = new DeviceTelemetry(
                "CNC-07", DeviceKind.Cnc, MachineState.Processing,
                1.25f, 2.5f, 0.8f, 0.5f, 12, 987654321L);

            Assert.AreEqual("CNC-07", telemetry.DeviceId);
            Assert.AreEqual(DeviceKind.Cnc, telemetry.Kind);
            Assert.AreEqual(MachineState.Processing, telemetry.State);
            Assert.AreEqual(1.25f, telemetry.X);
            Assert.AreEqual(2.5f, telemetry.Z);
            Assert.AreEqual(0.8f, telemetry.Battery);
            Assert.AreEqual(0.5f, telemetry.Progress);
            Assert.AreEqual(12, telemetry.CompletedCycles);
            Assert.AreEqual(987654321L, telemetry.TimestampMs);
        }
    }

    public class MachineStateExtensionsTests
    {
        [Test]
        public void WorkingStates_AreExactlyTheConsumingOnes()
        {
            Assert.IsTrue(MachineState.Moving.IsWorking());
            Assert.IsTrue(MachineState.Processing.IsWorking());
            Assert.IsFalse(MachineState.Idle.IsWorking());
            Assert.IsFalse(MachineState.Offline.IsWorking());
            Assert.IsFalse(MachineState.Fault.IsWorking());
        }

        [Test]
        public void DisplayName_IsLocalizedForEveryDeclaredState()
        {
            foreach (MachineState state in System.Enum.GetValues(typeof(MachineState)))
                Assert.AreNotEqual(state.ToString(), state.DisplayName(), state + " 缺少中文名");
        }
    }
}