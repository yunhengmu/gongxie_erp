using AgvDemo.Core.Domain;
using AgvDemo.Core.Messaging;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Transport;
using AgvDemo.Devices.Uplink;
using MessagePipe;
using NUnit.Framework;

namespace AgvDemo.Devices.Tests
{
    /// <summary>
    /// 网关 → 上行出口 → 通道 的整条链路。
    /// 这条链是「上行」的核心，单独测上行出口不足以证明它真的被接上了。
    /// </summary>
    public class FleetUplinkIntegrationTests
    {
        static DeviceGateway CreateGateway()
        {
            var builder = new BuiltinContainerBuilder();
            builder.AddMessagePipe();
            builder.AddMessageBroker<DeviceStateChanged>();
            builder.AddMessageBroker<DeviceFaulted>();

            var provider = builder.BuildServiceProvider();
            return new DeviceGateway(
                provider.GetRequiredService<IPublisher<DeviceStateChanged>>(),
                provider.GetRequiredService<IPublisher<DeviceFaulted>>());
        }

        static SimulatedTransport ConnectedTransport()
        {
            var transport = new SimulatedTransport("PLC-SIM-01", 0.25f);
            transport.Connect();
            transport.Pump(0.3f);
            Assert.IsTrue(transport.IsConnected, "前置条件：模拟通道应已连接");
            return transport;
        }

        static DeviceGateway GatewayWithOneAgv(SimulatedTransport transport, Vda5050Uplink uplink)
        {
            var gateway = CreateGateway();
            gateway.PublishInterval = 0.5f;
            gateway.AddTransport(transport, autoConnect: false);
            gateway.AddSink(uplink);
            gateway.Register(new SimulatedDevice(
                new DeviceDescriptor("AGV-01", DeviceKind.Agv, "AGV-1000"),
                transport, 0f, 0f, 0f, 3f));
            return gateway;
        }

        [Test]
        public void Gateway_PushesVda5050StateThroughTransport()
        {
            var transport = ConnectedTransport();
            var uplink = new Vda5050Uplink(transport, new Vda5050UplinkOptions());
            var gateway = GatewayWithOneAgv(transport, uplink);

            gateway.Tick(0.6f); // 跨过 0.5s 上报周期

            Assert.AreEqual(1, uplink.PublishedCount);
            Assert.AreEqual("vda5050/3/AgvDemo/AGV-01/state", uplink.LastTopic);
            StringAssert.Contains("\"serialNumber\":\"AGV-01\"", uplink.LastPayload);

            // 消息真的落到了通道上，而不是只存在内存字段里
            Assert.IsTrue(transport.TryRead(uplink.LastTopic, out var stored));
            Assert.AreEqual(uplink.LastPayload, stored.AsText());
        }

        [Test]
        public void Gateway_HoldsMessagesUntilPublishIntervalElapses()
        {
            var transport = ConnectedTransport();
            var uplink = new Vda5050Uplink(transport, new Vda5050UplinkOptions());
            var gateway = GatewayWithOneAgv(transport, uplink);

            gateway.Tick(0.2f);
            Assert.AreEqual(0, uplink.PublishedCount, "未到上报周期不应上行");

            gateway.Tick(0.4f); // 累计 0.6s
            Assert.AreEqual(1, uplink.PublishedCount);
        }

        [Test]
        public void Gateway_ReportsOneMessagePerDevice()
        {
            var transport = ConnectedTransport();
            var uplink = new Vda5050Uplink(transport, new Vda5050UplinkOptions());
            var gateway = CreateGateway();
            gateway.PublishInterval = 0.5f;
            gateway.AddTransport(transport, autoConnect: false);
            gateway.AddSink(uplink);
            gateway.RegisterAll(SimulatedFleet.Create(transport));

            gateway.Tick(0.6f);

            Assert.AreEqual(SimulatedFleet.Descriptors.Count, uplink.PublishedCount);
            Assert.AreEqual(
                $"vda5050/3/AgvDemo/{SimulatedFleet.Descriptors[SimulatedFleet.Descriptors.Count - 1].Id}/state",
                uplink.LastTopic);
        }
    }
}