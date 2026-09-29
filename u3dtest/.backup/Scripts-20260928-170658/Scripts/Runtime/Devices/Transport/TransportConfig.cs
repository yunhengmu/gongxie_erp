using System;
using System.Collections.Generic;
using AgvDemo.Devices.Domain;
using S7.Net;
using UnityEngine;

namespace AgvDemo.Devices.Transport
{
    public enum FleetTransportKind
    {
        /// <summary>内置模拟，无硬件可直接跑通全链路。</summary>
        Simulated = 0,
        /// <summary>Siemens S7 实机（S7netplus）。</summary>
        SiemensS7 = 1,
        /// <summary>MQTT Broker（MQTTnet）。</summary>
        Mqtt = 2,
        /// <summary>ABB 控制器 RWS（纯 HTTP，无需库）。</summary>
        AbbRws = 3,
    }

    /// <summary>
    /// 通道配置。字段全部可序列化，因此能在 Inspector 里直接改，
    /// 换现场时不需要重新编译代码。
    /// </summary>
    [Serializable]
    public sealed class TransportConfig
    {
        public FleetTransportKind Kind = FleetTransportKind.Simulated;

        [Header("目标")]
        public string Host = "127.0.0.1";
        [Tooltip("0 表示使用该协议的默认端口：S7=102 / MQTT=1883 / RWS=80")]
        public int Port;
        public string Username;
        public string Password;

        [Header("S7")]
        public CpuType Cpu = CpuType.S71500;
        public short Rack;
        public short Slot = 1;
        [Tooltip("点位表起始 DB 块号，每台设备占 16 字节")]
        public int DataBlock = 1;

        [Header("MQTT")]
        public string TopicPrefix = "agv";

        [Header("通用")]
        [Tooltip("轮询周期（毫秒）。现场不要低于 100ms，否则会打满控制器通信")]
        public int PollIntervalMs = 200;

        public float PollIntervalSeconds => Math.Max(0.02f, PollIntervalMs / 1000f);

        public int ResolvePort()
        {
            if (Port > 0) return Port;
            switch (Kind)
            {
                case FleetTransportKind.SiemensS7: return 102;
                case FleetTransportKind.Mqtt: return 1883;
                default: return 80;
            }
        }
    }

    /// <summary>
    /// 按配置造通道。设备侧的逻辑标签在这里被翻译成各协议的真实点位：
    /// S7 走自动生成的点位表，MQTT 用主题后缀，RWS 只做只读遥测。
    /// </summary>
    public static class TransportFactory
    {
        public const int BytesPerDevice = 16;

        public static ITransport Create(TransportConfig config, IReadOnlyList<DeviceDescriptor> descriptors)
        {
            if (config == null) return new SimulatedTransport("SIM");

            switch (config.Kind)
            {
                case FleetTransportKind.SiemensS7:
                    return BuildS7(config, descriptors);

                case FleetTransportKind.Mqtt:
                    return new MqttTransport(
                        "MQTT",
                        config.Host,
                        config.ResolvePort(),
                        config.TopicPrefix,
                        username: config.Username,
                        password: config.Password);

                case FleetTransportKind.AbbRws:
                    return BuildRws(config);

                default:
                    return new SimulatedTransport("PLC-SIM-01");
            }
        }

        static SiemensS7Transport BuildS7(TransportConfig config, IReadOnlyList<DeviceDescriptor> descriptors)
        {
            var transport = new SiemensS7Transport(
                "S7",
                config.Host,
                config.Cpu,
                config.Rack,
                config.Slot,
                config.PollIntervalSeconds);

            if (descriptors == null) return transport;

            // 每台设备占 16 字节：PosX(real) / PosZ(real) / Battery(real) / Status(int) / Online(bool)
            for (int i = 0; i < descriptors.Count; i++)
            {
                int offset = i * BytesPerDevice;
                string id = descriptors[i].Id;

                transport.Map($"{id}.PosX", $"DB{config.DataBlock}.DBD{offset}");
                transport.Map($"{id}.PosZ", $"DB{config.DataBlock}.DBD{offset + 4}");
                transport.Map($"{id}.Battery", $"DB{config.DataBlock}.DBD{offset + 8}");
                transport.Map($"{id}.Status", $"DB{config.DataBlock}.DBW{offset + 12}");
                transport.Map($"{id}.Online", $"DB{config.DataBlock}.DBX{offset + 14}.0");
            }

            return transport;
        }

        static AbbRwsTransport BuildRws(TransportConfig config)
        {
            return new AbbRwsTransport("RWS", config.Host, config.ResolvePort(), config.Username, config.Password, config.PollIntervalSeconds)
                .Map("ARM-01.Joints", "motionsystem/mechunits/ROB_1/jointtarget")
                .Map("ARM-01.Cartesian", "motionsystem/mechunits/ROB_1/cartesian")
                .Map("ARM-01.Execution", "rapid/execution")
                .Map("ARM-01.CtrlState", "panel/ctrlstate");
        }
    }
}
