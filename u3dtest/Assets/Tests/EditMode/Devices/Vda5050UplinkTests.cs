using System;
using AgvDemo.Core.Domain;
using AgvDemo.Core.Telemetry;
using AgvDemo.Devices.Transport;
using AgvDemo.Devices.Uplink;
using NUnit.Framework;

namespace AgvDemo.Devices.Tests
{
    public class Vda5050UplinkTests
    {
        static readonly DateTime FixedUtc = new DateTime(2026, 9, 28, 10, 0, 0, DateTimeKind.Utc);

        static Vda5050Uplink BuildUplink(ITransport transport)
            => new Vda5050Uplink(transport, new Vda5050UplinkOptions(), () => FixedUtc);

        static SimulatedTransport ConnectedTransport(float connectDelay = 0.25f)
        {
            var transport = new SimulatedTransport("TEST", connectDelay);
            transport.Connect();
            transport.Pump(connectDelay + 0.05f);
            Assert.IsTrue(transport.IsConnected, "前置条件：模拟通道应已连接");
            return transport;
        }

        static DeviceTelemetry Telemetry(
            string id,
            MachineState state,
            float x = 1.25f,
            float z = 2.5f,
            float battery = 0.8f)
            => new DeviceTelemetry(id, DeviceKind.Agv, state, x, z, battery, 0.5f, 3, 123456789L);

        [Test]
        public void Topic_FollowsVda5050Structure()
        {
            var transport = ConnectedTransport();
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Idle));

            Assert.AreEqual("vda5050/3/AgvDemo/AGV-01/state", uplink.LastTopic);
        }

        [Test]
        public void Topic_HonoursConfiguredManufacturerAndVersion()
        {
            var transport = ConnectedTransport();
            var uplink = new Vda5050Uplink(
                transport,
                new Vda5050UplinkOptions { Manufacturer = "ACME", TopicPrefix = "fleet", MajorVersion = "2" },
                () => FixedUtc);

            uplink.OnTelemetry(Telemetry("AGV-07", MachineState.Idle));

            Assert.AreEqual("fleet/2/ACME/AGV-07/state", uplink.LastTopic);
        }

        [Test]
        public void Payload_MatchesVda5050StateShape()
        {
            var transport = ConnectedTransport();
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Idle));

            Assert.AreEqual(
                "{\"headerId\":1," +
                "\"timestamp\":\"2026-09-28T10:00:00.000Z\"," +
                "\"version\":\"3.0.0\"," +
                "\"manufacturer\":\"AgvDemo\"," +
                "\"serialNumber\":\"AGV-01\"," +
                "\"driving\":false," +
                "\"state\":\"IDLE\"," +
                "\"stateCode\":1," +
                "\"agvPosition\":{\"x\":1.25,\"y\":2.5,\"theta\":0}," +
                "\"batteryState\":{\"batteryCharge\":0.8,\"charging\":false}," +
                "\"errors\":[]}",
                uplink.LastPayload);
        }

        [Test]
        public void Payload_IsActuallyHandedToTransport()
        {
            var transport = ConnectedTransport();
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Moving));

            Assert.IsTrue(transport.TryRead("vda5050/3/AgvDemo/AGV-01/state", out var stored));
            Assert.AreEqual(uplink.LastPayload, stored.AsText());
        }

        [Test]
        public void Payload_SetsDrivingAndChargingFlags()
        {
            var transport = ConnectedTransport();
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Moving));
            StringAssert.Contains("\"driving\":true", uplink.LastPayload);
            StringAssert.Contains("\"state\":\"MOVING\"", uplink.LastPayload);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Charging));
            StringAssert.Contains("\"driving\":false", uplink.LastPayload);
            StringAssert.Contains("\"charging\":true", uplink.LastPayload);
        }

        [Test]
        public void HeaderId_IncrementsPerMessage()
        {
            var transport = ConnectedTransport();
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Idle));
            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Idle));

            Assert.AreEqual(2, uplink.PublishedCount);
            StringAssert.Contains("\"headerId\":2", uplink.LastPayload);
        }

        [Test]
        public void DisconnectedTransport_DropsMessageWithoutCounting()
        {
            var transport = new SimulatedTransport("TEST");
            var uplink = BuildUplink(transport);

            uplink.OnTelemetry(Telemetry("AGV-01", MachineState.Idle));

            Assert.AreEqual(0, uplink.PublishedCount);
            Assert.AreEqual(string.Empty, uplink.LastPayload);
        }

        [TestCase(MachineState.Offline, "COMM_ERROR")]
        [TestCase(MachineState.Idle, "IDLE")]
        [TestCase(MachineState.Moving, "MOVING")]
        [TestCase(MachineState.Charging, "CHARGING")]
        [TestCase(MachineState.Fault, "ERROR")]
        [TestCase(MachineState.Scanning, "WAITING")]
        [TestCase(MachineState.Loading, "WAITING")]
        [TestCase(MachineState.Processing, "WAITING")]
        [TestCase(MachineState.Unloading, "WAITING")]
        public void StateMapping_CoversEveryMachineState(MachineState state, string expected)
        {
            Assert.AreEqual(expected, Vda5050Uplink.ToVda5050State(state));
        }
    }
}