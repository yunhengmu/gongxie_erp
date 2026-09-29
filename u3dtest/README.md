# AgvDemo —— Unity 设备数字孪生（WCS 虚拟调试用）

目标：把 Unity 当作**虚拟设备**，让 WCS 的调度逻辑在真机到位前先跑通。

## 一、首次运行

1. 用团结引擎打开本工程
2. 菜单栏 **`AgvDemo → 一键生成场景与配置`**
3. 按 Play

菜单会做三件事：
- 生成 6 个设备 Prefab → `Assets/Prefabs/Devices/`
- 创建配置资产 → `Assets/Settings/DeviceFleetSettings.asset`
- 搭建场景 → `Assets/Scenes/AgvDemo.scene`（含设备 Prefab 实例）

> **运行时不再创建任何几何体。** 场景与设备全部由编辑器生成/摆放，之后可以直接在编辑器里
> 换模型、调位置、改材质，代码一行不用动。这与工业侧通行做法一致（设备即 Prefab）。

## 二、技术栈

| 位置 | 选型 | 说明 |
|---|---|---|
| DI 容器 | **VContainer 1.19.0** | Unity 官方 Boss Room 用的也是它 |
| 消息 | **MessagePipe 1.8.2** | 替代静态 EventBus |
| 异步 | **UniTask 2.5.11** | 替代协程 |
| 配置 | **ScriptableObject** | 现场调参不改代码 |
| 协议 | **S7netplus 0.20.0 / MQTTnet 4.3.7.1207** | Siemens S7 / MQTT |

四个包以**嵌入式包**形式放在 `Packages/`（本机 GitHub 不可达，只能走 OpenUPM）。

## 三、目录结构

```
Assets/
├── Prefabs/Devices/          设备 Prefab（由菜单生成，可自由替换模型）
├── Scenes/                   场景（由菜单生成）
├── Settings/                 配置资产（ScriptableObject）
├── Plugins/                  协议库 DLL
└── Scripts/
    ├── Runtime/              ← 唯一的运行时程序集 AgvDemo.Runtime
    │   ├── Bootstrap/           组合根：ApplicationLifetimeScope / DeviceFleetService
    │   ├── Devices/
    │   │   ├── Domain/          MachineState / IDevice / DeviceStateMachine
    │   │   ├── Transport/       ITransport + 4 实现（模拟 / S7 / MQTT / RWS）
    │   │   ├── Device/          SimulatedDevice / SimulatedFleet
    │   │   ├── Telemetry/       状态信号快照 + 出口
    │   │   ├── Gateway/         DeviceGateway
    │   │   ├── Messages/        MessagePipe 消息
    │   │   └── Configuration/   DeviceFleetSettings
    │   ├── Warehouse/           仓库领域（网格 / BFS 路径）
    │   └── Presentation/        DeviceView（Prefab 用）/ Presenter / HMI
    └── Editor/               ← AgvDemo.Editor（仅编辑器）
```

**只有 2 个程序集**：`AgvDemo.Runtime` + `AgvDemo.Editor`。
域之间靠**文件夹和命名空间**隔离，不靠程序集。

> 依据：查证 4 个真实工业/仓库 Unity 项目（Unity 官方 Robotics-Warehouse、Open-Industry-Project、
> smart-warehouse-digital-twin、SmartWarehouseSimulation），**全部是扁平类型目录 + 设备 Prefab，
> 零个使用多层 asmdef**。同时论坛共识明确警告「过早/过度拆程序集会增加管理复杂度，中小项目先用
> 文件夹和命名空间」。所以这里收回到 2 个。

## 四、设备状态信号

状态编码是**对外契约**，现场 HMI / WCS 按数值解析，改数值等于破坏契约。

| 状态 | 编码 | 状态 | 编码 |
|---|---|---|---|
| Offline 离线 | 0 | Processing 作业中 | 5 |
| Idle 待机 | 1 | Unloading 放货中 | 6 |
| Moving 移动中 | 2 | Charging 充电中 | 7 |
| Scanning 扫码中 | 3 | Fault 故障 | 9 |
| Loading 取货中 | 4 | | |

流转序列由 `DeviceKind` 决定，例如 AGV：
`Idle → Moving → Scanning → Loading → Moving → Unloading → Idle`

四个出口：
1. 通信通道标签（`<设备>.State` 整数）→ 与真机对接 PLC 时同构
2. MessagePipe 消息 `DeviceStateChanged` → 进程内解耦
3. 遥测快照 `DeviceTelemetry` → 含 JSON 载荷，可直接推 MQTT / 后端
4. HMI 面板 → 人看

## 五、逐个状态手动切换

HMI 面板每台设备两个按钮：**推进**（前进一个状态）、**切手动 / 切自动**。
也可在配置资产里勾 `StartInManualMode`，启动即全手动。

## 六、接真机

改 `Assets/Settings/DeviceFleetSettings.asset` 里的 `Transport.Kind`：

| Kind | 需要填 |
|---|---|
| `Simulated` | 不用填，直接跑 |
| `SiemensS7` | PLC 的 IP、机型、Rack/Slot |
| `Mqtt` | Broker 地址、主题前缀 |
| `AbbRws` | 控制器 IP、账号密码 |

**换通道不改代码。**

## 七、离线编译验证

```bash
cd Tools/compile-check && dotnet build CompileCheck.csproj -v q --nologo
```

不启动编辑器，约 3 秒验证全部脚本 + 嵌入式包。详见该目录 README。

> ⚠️ 只验证语法与类型。**asmdef 边界、.meta / GUID、场景引用仍需打开编辑器确认。**
