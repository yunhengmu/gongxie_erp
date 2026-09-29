using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;
using AgvDemo.Devices.Telemetry;
using NUnit.Framework;

namespace AgvDemo.Devices.Tests
{
    public class JsonLineTelemetrySinkTests
    {
        [Test]
        public void OnTelemetry_EmitsOneLineJsonWithEveryContractField()
        {
            var sink = new JsonLineTelemetrySink();

            sink.OnTelemetry(new DeviceTelemetry(
                "AGV-01", DeviceKind.Agv, MachineState.Moving,
                1.25f, 2.5f, 0.8f, 0.5f, 3, 123456789L));

            // 这行载荷就是要推给 MQTT / 后端的形状，字段一旦对外就等同协议
            Assert.AreEqual(
                "{\"deviceId\":\"AGV-01\",\"kind\":\"Agv\",\"state\":\"Moving\",\"stateCode\":2," +
                "\"x\":1.25,\"z\":2.5,\"battery\":0.8,\"progress\":0.5,\"cycles\":3,\"ts\":123456789}",
                sink.LastPayload);
            Assert.AreEqual(1, sink.Count);
        }

        [Test]
        public void OnTelemetry_TrimsTrailingZerosFromFloats()
        {
            var sink = new JsonLineTelemetrySink();

            sink.OnTelemetry(new DeviceTelemetry(
                "AGV-02", DeviceKind.Agv, MachineState.Idle,
                0f, 1f, 1f, 0f, 0, 0L));

            StringAssert.Contains("\"x\":0", sink.LastPayload);
            StringAssert.Contains("\"z\":1,", sink.LastPayload);
            StringAssert.DoesNotContain("0.00", sink.LastPayload);
        }

        [Test]
        public void OnTelemetry_OverwritesPayloadAndKeepsCounting()
        {
            var sink = new JsonLineTelemetrySink();

            sink.OnTelemetry(new DeviceTelemetry("A", DeviceKind.Agv, MachineState.Idle, 0f, 0f, 1f, 0f, 0, 1L));
            sink.OnTelemetry(new DeviceTelemetry("B", DeviceKind.Cnc, MachineState.Processing, 0f, 0f, 1f, 0f, 0, 2L));

            StringAssert.Contains("\"deviceId\":\"B\"", sink.LastPayload);
            Assert.AreEqual(2, sink.Count);
        }
    }

    public class InMemoryTelemetrySinkTests
    {
        [Test]
        public void OnTelemetry_KeepsLatestSnapshotPerDevice()
        {
            var sink = new InMemoryTelemetrySink();

            sink.OnTelemetry(new DeviceTelemetry("AGV-01", DeviceKind.Agv, MachineState.Idle, 0f, 0f, 1f, 0f, 0, 1L));
            sink.OnTelemetry(new DeviceTelemetry("AGV-01", DeviceKind.Agv, MachineState.Moving, 5f, 0f, 1f, 0f, 0, 2L));
            sink.OnTelemetry(new DeviceTelemetry("AGV-02", DeviceKind.Agv, MachineState.Idle, 0f, 0f, 1f, 0f, 0, 3L));

            Assert.AreEqual(3, sink.Count, "计数按收到的帧算，不按设备数");
            Assert.AreEqual(2, sink.All.Count);

            Assert.IsTrue(sink.TryGet("AGV-01", out var latest));
            Assert.AreEqual(MachineState.Moving, latest.State);
            Assert.AreEqual(2L, latest.TimestampMs);

            Assert.IsFalse(sink.TryGet("AGV-99", out _));
        }

        [Test]
        public void Clear_DropsSnapshotsAndCounter()
        {
            var sink = new InMemoryTelemetrySink();
            sink.OnTelemetry(new DeviceTelemetry("AGV-01", DeviceKind.Agv, MachineState.Idle, 0f, 0f, 1f, 0f, 0, 1L));

            sink.Clear();

            Assert.AreEqual(0, sink.Count);
            Assert.AreEqual(0, sink.All.Count);
            Assert.IsFalse(sink.TryGet("AGV-01", out _));
        }
    }
}