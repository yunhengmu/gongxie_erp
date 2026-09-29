using System;

namespace AgvDemo.Devices.Transport
{
    public enum TransportState
    {
        Disconnected,
        Connecting,
        Connected,
        Faulted,
    }

    /// <summary>
    /// 工业现场主流通信协议。每一种对应一个 ITransport 实现，
    /// 目前只有 Simulated 是可运行的，其余为现场接入的扩展位。
    /// </summary>
    public enum ProtocolKind
    {
        /// <summary>内置模拟，无硬件时的开发与演示通道。</summary>
        Simulated,
        /// <summary>Siemens S7，ISO-on-TCP（RFC1006），端口 102。</summary>
        SiemensS7,
        /// <summary>OPC UA，通用工业设备与上位系统。</summary>
        OpcUa,
        /// <summary>MQTT，IoT 平台与云端。</summary>
        Mqtt,
        /// <summary>ROS-TCP-Connector，端口 10000，对接 ROS2 仿真与本机。</summary>
        RosTcp,
        /// <summary>ABB Robot Web Services，HTTP 上的 REST 接口。</summary>
        AbbRws,
    }

    /// <summary>
    /// 通信通道抽象。设备驱动只依赖此接口，不关心底下是 S7 还是 ROS2，
    /// 因此同一套设备逻辑可以在模拟通道与现场通道之间整体切换。
    /// </summary>
    public interface ITransport
    {
        string Name { get; }
        ProtocolKind Protocol { get; }
        string Endpoint { get; }
        TransportState State { get; }
        bool IsConnected { get; }

        event Action<TransportState> StateChanged;

        void Connect();
        void Disconnect();

        /// <summary>驱动一次状态机（连接超时、心跳、重连）。由上层按帧或按周期调用。</summary>
        void Pump(float deltaTime);

        bool TryRead(string tag, out TagValue value);
        bool TryWrite(string tag, TagValue value);
    }
}
