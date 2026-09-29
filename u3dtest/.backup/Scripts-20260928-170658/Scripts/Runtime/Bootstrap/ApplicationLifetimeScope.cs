using AgvDemo.Devices.Configuration;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Messages;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;
using MessagePipe;
using VContainer;
using VContainer.Unity;

namespace AgvDemo.Bootstrap
{
    /// <summary>
    /// 唯一的组合根。应用启动时在这里组装整张对象图，
    /// 其他组件一律通过构造器注入拿依赖，不自己 new、也不去找静态入口。
    /// </summary>
    public sealed class ApplicationLifetimeScope : LifetimeScope
    {
        public DeviceFleetSettings Settings;

        protected override void Configure(IContainerBuilder builder)
        {
            if (Settings == null) return;

            // 配置资产本身就是依赖，注册进容器而不是到处静态访问
            builder.RegisterInstance(Settings);
            builder.RegisterInstance(Settings.Transport);

            // 消息：MessagePipe 接管发布/订阅，替掉静态事件总线
            var options = builder.RegisterMessagePipe();
            builder.RegisterMessageBroker<DeviceStateChanged>(options);
            builder.RegisterMessageBroker<DeviceFaulted>(options);

            builder.Register<DeviceGateway>(Lifetime.Singleton);
            builder.Register<ITransport>(BuildTransport, Lifetime.Singleton);
            builder.Register<InMemoryTelemetrySink>(Lifetime.Singleton);
            builder.Register<JsonLineTelemetrySink>(Lifetime.Singleton);
            builder.Register<DeviceFleetService>(Lifetime.Singleton);

            builder.RegisterBuildCallback(OnContainerBuilt);
        }

        static ITransport BuildTransport(IObjectResolver resolver)
        {
            var settings = resolver.Resolve<DeviceFleetSettings>();

            // 模拟通道带掉线模拟，方便验证上层对连接状态的处理
            if (settings.Transport.Kind == FleetTransportKind.Simulated)
                return new SimulatedTransport("PLC-SIM-01");

            return TransportFactory.Create(settings.Transport, SimulatedFleet.Descriptors);
        }

        void OnContainerBuilt(IObjectResolver resolver)
        {
            var service = resolver.Resolve<DeviceFleetService>();
            service.Start(Settings);
        }
    }
}
