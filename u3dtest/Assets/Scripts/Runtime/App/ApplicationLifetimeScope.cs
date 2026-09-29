using AgvDemo.Core.Fleet;
using AgvDemo.Core.Messaging;
using AgvDemo.Devices.Configuration;
using AgvDemo.Devices.Device;
using AgvDemo.Devices.Gateway;
using AgvDemo.Devices.Telemetry;
using AgvDemo.Devices.Transport;
using AgvDemo.Devices.Uplink;
using AgvDemo.UI;
using AgvDemo.UI.Hmi;
using MessagePipe;
using UnityEngine;
using VContainer;
using VContainer.Unity;

namespace AgvDemo.App
{
    /// <summary>
    /// 唯一的组合根。应用启动时在这里组装整张对象图。
    ///
    /// 依赖方向：UI → Core ← Devices ← App。
    /// App 认识所有实现，是唯一允许 new 具体类型的地方；
    /// 其余各层只认 Core 的契约，场景组件由容器注入而不是自己去找入口。
    /// </summary>
    public sealed class ApplicationLifetimeScope : LifetimeScope
    {
        public DeviceFleetSettings Settings;

        protected override void Configure(IContainerBuilder builder)
        {
            if (Settings == null)
            {
                Debug.LogError("[AgvDemo] ApplicationLifetimeScope 没有配置 DeviceFleetSettings，应用未装配。");
                return;
            }

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
            builder.Register<Vda5050Uplink>(BuildUplink, Lifetime.Singleton);

            // 同一个单例同时以「具体类型 + 两个契约」暴露给不同层
            builder.Register<DeviceFleetService>(Lifetime.Singleton);
            builder.Register<IFleetReadModel>(r => r.Resolve<DeviceFleetService>(), Lifetime.Singleton);
            builder.Register<IFleetCommands>(r => r.Resolve<DeviceFleetService>(), Lifetime.Singleton);

            // 场景里的表现层组件由容器注入，不再用 GetComponentInParent 找入口
            builder.RegisterComponentInHierarchy<ApplicationController>();
            builder.RegisterComponentInHierarchy<DeviceFleetPresenter>();
            builder.RegisterComponentInHierarchy<DeviceFleetHud>();

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

        static Vda5050Uplink BuildUplink(IObjectResolver resolver)
        {
            var settings = resolver.Resolve<DeviceFleetSettings>();
            var uplink = settings.Transport.Uplink ?? new UplinkConfig();

            return new Vda5050Uplink(
                resolver.Resolve<ITransport>(),
                new Vda5050UplinkOptions
                {
                    Manufacturer = uplink.Manufacturer,
                    TopicPrefix = uplink.TopicPrefix,
                    MajorVersion = uplink.MajorVersion,
                });
        }

        void OnContainerBuilt(IObjectResolver resolver)
        {
            var service = resolver.Resolve<DeviceFleetService>();
            service.Start(Settings);
        }
    }
}