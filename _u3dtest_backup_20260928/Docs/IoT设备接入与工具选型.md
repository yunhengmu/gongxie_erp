# 设备接入层（IoT）设计与工具选型核实

> 本文档回答两个问题：**你列的那五个工具到底能不能用**、**IoT 侧的正确架构长什么样**。
> 所有结论均以实际查证为准，附来源与版本约束。

## 一、五个工具的核实结论

**核心结论：这五个工具没有一个是能直接拖进本工程使用的。** 原因分三类。

| 工具 | 存在性 | 许可 | 引擎要求 | 能否直接用 | 症结 |
|---|---|---|---|---|---|
| realvirtual.io Starter | ✅ Unity Asset Store | 免费 | **Unity 6** | ❌ | 本工程内核 2022.3，装不上 |
| Unity3D_Robotics_ABB | ✅ GitHub | MIT | Unity 2020.1.8f1 | ⚠️ | 是**演示应用**，非 package |
| PyroNc | ✅ GitHub | 未核实 | 独立 .NET 方案 | ⚠️ | 是**独立应用**（Pyro.sln），非 Unity 库 |
| BADOSE | ✅ GitHub | 开源 | Unity 2022.3.30f1 + **HDRP** | ❌ | 依赖 HDRP 水面系统，本工程是 Built-in |
| UNetyEmuROS | ✅ GitHub | Apache-2.0 | Unity **2022.3.62f2** | ⚠️ | 版本对得上，但需 ROS2 Humble + Ubuntu |

### 关键细节

**realvirtual.io** —— 三层版本，能力差异很大，容易被"免费"误导：

| 能力 | Starter（免费） | Simulation（540€） | Professional（1098€） |
|---|---|---|---|
| 基础框架 + C# 源码 | ✅ | ✅ | ✅ |
| Siemens S7 TCP/IP 接口 | ✅ | ✅ | ✅ |
| **AGV 路径仿真** | ❌ | ✅ | ✅ |
| 传送带库（物理仿真） | ❌ | ✅ | ✅ |
| OPC UA / MQTT / Modbus / TwinCAT | ❌ | ❌ | ✅ |
| ABB RobotStudio 接口 | ❌ | ❌ | ✅ |

即：**免费的 Starter 不含 AGV 路径仿真**，那部分在付费的 Simulation 里，且 Simulation 必须以 Starter 或 Professional 为底座。同时三者都要求 Unity 6。

**Unity3D_Robotics_ABB** —— 价值在于它示范了 ABB RWS（Robot Web Services）的客户端写法，是纯 C# 的 HTTP 调用，可以借鉴并移植。但工程本身基于 Unity 2020，资产与场景结构都需要重做。作者另有 [Unity3D_Robotics_Overview](https://github.com/rparak/Unity3D_Robotics_Overview) 汇总了 UR、B&R、ACOPOStrak 等多个数字孪生示例，参考价值高于单个仓库。

**UNetyEmuROS** —— 唯一引擎版本对得上的，要求 Unity **2022.3.62f2**，本工程是 `2022.3.62t16`（同一 2022.3.62 内核，t 为团结分支标记）。它的价值是示范了 **ROS-TCP-Connector** 的用法：Unity 侧发布/订阅，ROS 侧跑 `ros_tcp_endpoint`，默认端口 10000。

**BADOSE** —— 依赖 HDRP 的海洋水面系统，本工程是 Built-in RP，无法直接使用。

**PyroNc** —— 它本质上是一个完整的 G-code 仿真**应用**（`Pyro.sln`，C# + Rust + OpenCL/CUDA），不是可引用的库。`Pyro.Nc.Parser` 理论上可以抽出来，但它绑定了自家的 `Pyro.IO`（内存管理、INTEROP），抽取成本高于自己写一个 G-code 解析器。

## 二、所以正确的做法是什么

这五个工具看似无关，实际揭示了一件事：**工业侧接入的本质是"协议 × 设备"的组合**。

| 工具 | 它真正的接口 | 协议与端口 |
|---|---|---|
| realvirtual.io | Siemens S7 TCP/IP | ISO-on-TCP (RFC1006)，端口 102 |
| Unity3D_Robotics_ABB | ABB RWS | HTTP，端口 80 |
| UNetyEmuROS / BADOSE | ROS-TCP-Connector | TCP，端口 10000 |
| （IoT 平台侧） | MQTT / OPC UA | 1883 / 4840 |

因此架构不该围绕"某个工具"设计，而要把**协议**和**设备**各自抽象出来：

```
            ┌──────────────────────────────────┐
            │  Gameplay（MonoBehaviour 可视化）  │
            └────────────────┬─────────────────┘
                             │ 只读遥测
            ┌────────────────▼─────────────────┐
            │  DeviceGateway（设备接入层出口）    │
            │  统一 Tick / 采集 / 分发遥测        │
            └───┬──────────────────────────┬───┘
                │                          │
      ┌─────────▼─────────┐      ┌─────────▼─────────┐
      │  IDevice（设备抽象）│      │ ITelemetrySink    │
      │  AGV / 堆垛机 / PLC │      │ 日志 / MQTT / 后端 │
      │  机械臂 / 机床 / 无人机│    └───────────────────┘
      └─────────┬─────────┘
                │ 只依赖接口，不关心协议
      ┌─────────▼──────────────────────────────────┐
      │  ITransport（通信抽象）                       │
      │  Simulated ✅ / SiemensS7 / OpcUa / Mqtt     │
      │  RosTcp / AbbRws                             │
      └─────────────────────────────────────────────┘
```

**关键设计**：`IDevice` 只依赖 `ITransport` 接口。因此同一台 AGV 的逻辑，今天跑在 `SimulatedTransport` 上（开发期无硬件），明天换成 `SiemensS7Transport` 就能对接现场 PLC，**设备逻辑一行都不用改**。这就是数字孪生的基本工作方式，也是 realvirtual.io 这类产品的核心价值。

## 三、本工程的实现

新增程序集 `AgvDemo.Devices`（设备接入层），零外部依赖：

```
Assets/Scripts/Runtime/Devices/
├── AgvDemo.Devices.asmdef
├── Transport/
│   ├── ITransport.cs          通信抽象 + TransportState + ProtocolKind
│   ├── TagValue.cs            PLC 标签值（对齐 S7 的 BOOL/INT/REAL/STRING）
│   └── SimulatedTransport.cs  可运行的模拟通道（连接状态机 + 掉线重连）
├── Device/
│   ├── DeviceKind.cs          设备种类 / 状态枚举
│   ├── IDevice.cs             设备抽象 + DeviceDescriptor
│   ├── SimulatedDevice.cs     通用模拟设备（往返作业 + 写回 PLC 数据块）
│   └── SimulatedFleet.cs      六台设备的编队定义
├── Telemetry/
│   ├── DeviceTelemetry.cs     遥测快照 + ITelemetrySink
│   └── TelemetrySinks.cs      内存缓存出口 / JSON 载荷出口
└── Gateway/
    └── DeviceGateway.cs       设备接入层唯一出口
```

### 六台模拟设备（对应你工具表里的每一类）

| ID | 种类 | 机型 | 标注协议 | 行为 |
|---|---|---|---|---|
| PLC-01 | Plc | Siemens S7-1500 | SiemensS7 | 静止，指示灯随连接状态闪动 |
| AGV-01 | Agv | AGV-1000 | SiemensS7 | 在取货点与作业点之间往返 |
| STK-01 | Stacker | ASRS Stacker | SiemensS7 | 载货台沿立柱升降 |
| ARM-01 | RobotArm | ABB IRB 120 | AbbRws | 两节臂摆动 |
| CNC-01 | Cnc | 3-Axis Mill | OpcUa | 主轴旋转 |
| UAV-01 | Drone | Quadrotor | RosTcp | 悬停起伏 + 旋翼旋转 |

每台设备在每个周期都会把 `PosX / PosZ / Battery / Status / Online` 写回通信通道的标签表——**等价于真机把状态写进 PLC 数据块**，因此上层读到的数据结构与接真机时完全一致。

### 装配方式

`DeviceFleetView`（Gameplay 层）在 `Awake` 中组装接入层，并注册进组合根：

```csharp
var transport = new SimulatedTransport("PLC-SIM-01");
_gateway = new DeviceGateway { PublishInterval = 0.5f };
_gateway.AddTransport(transport);
_gateway.RegisterAll(SimulatedFleet.Create(transport));
_gateway.AddSink(new InMemoryTelemetrySink());
_gateway.AddSink(new JsonLineTelemetrySink());
DemoHost.Instance(_gateway);
```

换现场协议时，只改第一行。

## 四、接真实现场时要做的事

### 4.1 各协议的实现要点

| 协议 | 实现要点 | 可借力的东西 |
|---|---|---|
| **Siemens S7** | ISO-on-TCP 握手（RFC1006）+ S7comm PDU 编解码；读 `DB` 区按字节偏移解析 | `S7.Net`（.NET 库）；`Snap7`（C++，有 C# 封装） |
| **ABB RWS** | HTTP REST：`GET /rw/motionsystem/mechunits/ROB_1/jointtarget` 等；订阅用 WebSocket | `Unity3D_Robotics_ABB` 的 RWS 客户端代码 |
| **ROS-TCP** | Unity 侧装 `com.unity.robotics.ros-tcp-connector`；ROS 侧跑 `ros_tcp_endpoint`；端口 10000 | `UNetyEmuROS` 的 Publisher/Subscriber 写法 |
| **MQTT** | Unity 不内置客户端，需引第三方（`MQTTnet` 或 Paho） | realvirtual.io Professional 的接口清单可作参考 |
| **OPC UA** | 需完整协议栈，通常引 `OPCFoundation.NetStandard.Opc.Ua` | — |

### 4.2 一个必须知道的坑：NuGet 不能直接用

Unity **不消费 NuGet 包**。上面提到的 `S7.Net`、`MQTTnet`、`OPC Foundation` 都是 NuGet 分发，要接进来必须：
1. 用 NuGet 下载 `.nupkg`，解压取出 `lib/netstandard2.1/*.dll`
2. 把 dll 放进 `Assets/Plugins/`（配合 `.asmdef` 的 `overrideReferences` + `precompiledReferences`）
3. 依赖的卫星 dll 要一并放入

这一点在团结引擎上同样成立。

### 4.3 现场必备但容易被忽略的三件事

1. **连接状态机**：工业现场断线是常态，不是异常。`TransportState` 里的 `Connecting` / `Faulted` 与自动重连是必需品，不是可选项。本工程的 `SimulatedTransport` 已实现完整状态机，可直接复制这套逻辑到真实实现。
2. **数值单位与字节序**：S7 是大端，浮点数按 IEEE754 解析，工程量与原始值之间通常有线性换算（例如 27648 对应 100%）。
3. **写入的节流与去重**：不要每帧写 PLC。按周期（本工程 500ms）批量写，且值未变化时跳过，否则会把 PLC 通信负载打满。

## 五、模拟通道的调试价值

`SimulatedTransport` 的构造参数里有 `faultProbability`，在 Inspector 上把 `DeviceFleetView` 的 `faultProbability` 调到 `0.05` 左右再运行，可以看到设备随机掉线、指示灯变灰、网关统计里的"离线"数量跳动的完整过程。

**这条链路在接真机之前就应该全部跑通** —— 否则现场出问题时，你无法区分是协议实现错了还是设备本身的状态就那样。

## 六、附：本次一并修正的程序集分层问题

### 6.1 修正前的两个问题

**问题一：`Warehouse.Infrastructure` 引用了 `Warehouse.Application`，同时应用层又反过来 `using` 基础设施层。**
接口 `IWarehouseLayoutSource` 定义在基础设施层，而消费它的应用层引用了它 —— 依赖方向倒置，
应用层被迫认识具体实现。而且应用层的 asmdef 里根本没有基础设施层的引用，
意味着**这份代码在编辑器里是编译不过的**（只是在批处理卡死的情况下没被发现）。

**问题二：`Warehouse.Domain` 是空程序集**，只有 asmdef 没有代码；本该属于领域层的
坐标换算（`GridMath`）与路径规划（`IPathFinder` / `BfsPathFinder`）滞留在 `AgvDemo.Core`。
领域事件 `AgvTaskAssigned` / `AgvArrived` / `AgvBlocked` 也被塞在通用的 `EventBus.cs` 里。

另有一个孤立文件 `Core/AssemblyInfo.meta`（写入时混入了 `/n`，无对应文件）已清除。

### 6.2 修正后的依赖方向

接口按**依赖倒置**归位到消费方（应用层），实现留在基础设施层，于是依赖箭头全部单向朝下：

| 程序集 | 引用 | 职责 |
|---|---|---|
| `AgvDemo.Core` | — | 通用基础件（事件总线、DI 容器），无引擎依赖 |
| `AgvDemo.Devices` | — | 设备接入层，无任何外部依赖 |
| `AgvDemo.Warehouse.Domain` | — | 领域模型：`GridMath`、`IPathFinder`/`BfsPathFinder`、`AgvEvents` |
| `AgvDemo.Warehouse.Application` | Core, Domain | 用例编排 + **端口定义**（`IWarehouseLayoutSource`） |
| `AgvDemo.Warehouse.Infrastructure` | Application | 端口实现（`HardcodedLayoutSource`） |
| `AgvDemo.Gameplay` | Core, Devices, Domain, Application, Infrastructure | **组合根**：把实现装配给端口 |
| `AgvDemo.Editor` | 全部 | 仅编辑器 |

**关键点**：`Infrastructure → Application` 是正确方向（实现依赖接口），
而 `Application` **不**引用 `Infrastructure` —— 谁来把两者接起来？`Gameplay` 这个组合根。
这正是 Spring 里「`@Service` 不 `new` 具体实现，由 `@Configuration` 装配」的同一件事。

### 6.3 一个有意保留的取舍

`Warehouse.Domain` 仍然使用 `UnityEngine.Vector2Int` / `Vector3`。严格 DDD 会自定义坐标类型以彻底摆脱引擎依赖，
但 `Vector3`/`Vector2Int` 是纯值类型且经过了充分验证，Unity 生态的通行做法是直接使用。
真正的引擎耦合（场景对象、`MonoBehaviour` 生命周期）全部挡在 `Gameplay` 之外，所以领域层依然可以单测。
若要求领域层零引擎依赖，可引入自定义 `Cell` 结构体替换 `Vector2Int`（会波及 5 个文件）。

