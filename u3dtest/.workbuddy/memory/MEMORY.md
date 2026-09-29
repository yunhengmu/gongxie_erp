# u3dtest 项目长期笔记

## 项目定位
用户从 Java/Spring + Angular/React/Vue + ABP(C#) 背景转入 Unity 学习，项目 `D:\yudao\u3dtest` 是学习载体。
引擎：**团结引擎 Tuanjie 1.10.4**（Unity 2022.3.62t16 内核），模板：3D Built-in（`com.unity.feature.development`）。
包源：`https://packages.tuanjie.cn`（**独立裁剪源，不是 Unity 官方 registry**，版本号不能照抄官方文档）。

## 讲解约定（重要）
- 用户明确要求：**用 Angular / React / Vue / ABP(C#) / Spring 的概念做对标讲解**，不要拿 Unity 术语孤立解释。
- 已确认好用的类比：MonoBehaviour ≈ Angular 组件；Inspector 字段 ≈ @Input()/props；ScriptableObject ≈ @ConfigurationProperties；asmdef ≈ Maven module；Editor 目录 ≈ 只能在编辑器存在的代码；`Update()` ≈ 每帧轮询（与 Angular 事件驱动相反）；Prefab ≈ Vue 的 .vue 模板。
- 用户说「看不懂」时，要给**图 + 并排对照表**，不要写成长文。
- **不要给「学习路线 / 阶段划分」**。不要推荐装不上的包 —— 必须先查团结源可用性再开口。

## 当前代码结构（2026-09-28 第二次重建，此为最终版）
第一次重建（5 程序集）已按论坛调研结论**再次推翻**。当前为**扁平结构 + 2 程序集**。

```
Assets/
├── Prefabs/Devices/     设备 Prefab（编辑器菜单生成）
├── Scenes/ Settings/ Plugins/
└── Scripts/
    ├── Runtime/         ← 唯一运行时程序集 AgvDemo.Runtime
    │   ├── Bootstrap/       ApplicationLifetimeScope / DeviceFleetService
    │   ├── Devices/         Domain / Transport / Device / Telemetry / Gateway / Messages / Configuration
    │   ├── Warehouse/       Domain / Messages
    │   └── Presentation/    DeviceView / DeviceFleetPresenter / Hmi
    └── Editor/          ← AgvDemo.Editor
```

**只有 2 个程序集**（`AgvDemo.Runtime` + `AgvDemo.Editor`）。域靠**文件夹与命名空间**隔离，不靠程序集。
命名空间 `AgvDemo.Bootstrap`（原 `AgvDemo.ApplicationLifecycle` 已改名）。

**依据**：查证 4 个真实工业/仓库 Unity 项目（Unity 官方 Robotics-Warehouse、Granberget/Open-Industry-Project、
dqvid3/smart-warehouse-digital-twin、auroraaazx/SmartWarehouseSimulation）**全部扁平 + 设备 Prefab + 零多层 asmdef**；
论坛共识明确警告「过早/过度拆程序集会增加管理复杂度」（CSDN 少女前线文、dev.to、hqwc）。
30 个脚本配 5 个程序集属"偏早偏碎"。

**设备即 Prefab**：`DeviceView`（MonoBehaviour）挂在设备 Prefab 上，只做数据绑定，**不创建几何体**。
`DeviceFleetPresenter` 收集场景里的 DeviceView 并绑定服务。设备数量由**编辑器摆放**决定。

**编辑器菜单**：`AgvDemo → 一键生成场景与配置`（生成 Prefab + 配置资产 + 场景）、`仅重建设备 Prefab`、`打开演示场景`。
运行时代码不再 `CreatePrimitive`。

## 编译验证（重要，别再用批处理）
`-batchmode -nographics -quit` 会卡在授权校验（`Tuanjie_lic.ulf` 写入 `C:\ProgramData` 被拒），
日志停在 `Application.AssetDatabase Initial Refresh Start`，**不要用它判断编译是否通过**。

**用 `Tools/compile-check/`**：`cd Tools/compile-check && dotnet build CompileCheck.csproj -v q --nologo`，3 秒出结果。
只验证语法/类型；**asmdef 边界、.meta/GUID、场景序列化字段仍需打开编辑器确认**。
已沉淀为技能 `unity-offline-compile-check`。

## 工业工具核实结论（2026-09-28，勿重复调研）
- **realvirtual.io Starter**：免费但**要求 Unity 6**，本工程装不上；且 AGV 路径仿真在付费 Simulation（540€）。
- **Unity3D_Robotics_ABB**：MIT，Unity 2020 的演示应用（非 package），仅可借鉴 RWS 客户端写法。
- **PyroNc**：独立应用（C# + Rust），非 Unity 库。
- **BADOSE**：依赖 HDRP，本工程是 Built-in，无法使用。
- **UNetyEmuROS**：Apache-2.0，要求 Unity 2022.3.62f2（与本工程同内核），唯一版本对得上的。
- **结论：无一可直接使用**，价值在于它们揭示了工业协议标准。

## ✅ 权威依据：Unity 官方 Boss Room 的架构（2026-09-28 查证）
来源：官方架构文档 `docs.unity3d.com/Packages/com.unity.netcode.gameobjects@1.15/manual/samples/bossroom/architecture.html`
与仓库 `Unity-Technologies/com.unity.multiplayer.samples.coop`

**原文**：
- "Boss Room's code is organized into multiple **domain-based assemblies**, each with a relatively self-contained purpose."
- "we eventually decided to **revert our initial client/server/shared assemblies to a more classic domain-driven assembly architecture**"
- "This assembly separation style enforces better separation of concerns... It also uses **more granular recompilation** during iterations to save time."

**真实目录**（GitHub API 实取 `Assets/Scripts/`）：
```
ApplicationLifecycle/ Audio/ CameraUtils/ ConnectionManagement/ Editor/
Gameplay/ Infrastructure/ Navigation/ UnityServices/ Utils/ VisualEffects/
```
注意：**直接在 `Assets/Scripts/` 下，没有 `_Project/` 前缀**（那是我从第三方 skill 文件读来的，非官方）。
注意：**同时存在领域目录（Gameplay/ConnectionManagement）和分层目录（Infrastructure/Utils）**。

**官方明确的 6 条做法（这才是差距所在）**：
1. `ApplicationController` 在 **Startup 场景**，是**唯一的** composition root（"serves as the application's entry point **and composition root**"），还有编辑器工具强制从 Startup 启动
2. **每个场景有自己的 scene-specific composition root**（根级 GameObject 上的 entry point 组件）
3. **用 VContainer**（`[Inject]` / `LifetimeScope` / `scope.BindAsSingle()` / `DIScope.RootScope`）
4. PubSub 用 **DI 友好的 `IPublisher`/`ISubscriber` 接口**，放 `Infrastructure/PubSub/` —— **不用静态 EventBus**
5. **asmdef 用 GUID 引用**（"switched all asmdefs to use GUIDs for better durability when refactoring"）
6. asmdef 统一前缀命名（`Unity.BossRoom.{Domain}`）

## 📊 最流行的框架（2026-09-28 查技术博客 + Star 数据）
### DI 容器（博客共识）
| 框架 | Stars | 结论 |
|---|---|---|
| **VContainer**（hadashiA） | 2,620 | **新项目首选**。反射集中在构建期、代码简洁、维护活跃。**Unity 官方 Boss Room 用的就是它** |
| Zenject / Extenject（modesttree） | 2,854 | **2020 年后停止维护**（博客原话 "halt of development" / "No new updates since 2020"）。仅老项目维护才用 |
| Reflex（gustavopsantos） | 1,393 | 最快（号称比 VContainer 快 414%），Roslyn 源码生成 + 不可变容器 |

IL2CPP / Unity 2022.3 实测：100 次 transient 构造 —— Zenject ~6.5ms、GC 80~100KB；VContainer ~0.9ms、**GC 0B**。

### Cysharp 全家桶（事实标准，MIT）
**UniTask 11.2k**（异步）· ZLinq 5.3k · MemoryPack 4.7k（二进制序列化）· MagicOnion 4.4k（gRPC）·
**R3 4.0k**（响应式，**UniRx 的继任者**，UniRx 7.3k 已停更）· ZString 2.8k · **MessagePipe 1.9k**（消息管道）· ZLogger 1.6k

### 消息/事件
**MessagePipe** —— "High performance in-memory/distributed messaging pipeline"。
支持 Pub/Sub、CQRS mediator、EventAggregator、IPC-RPC；**DI-first**、filter pipeline、buffered/keyed。
→ **替代静态 EventBus 的标准答案**。官方 Boss Room 也是 PubSub + DI（自研轻量版放 `Infrastructure/PubSub/`）。

## 🏭 Unity 官方数字孪生参考架构：Ditmara
来源：`unity.com/cn/blog/ditmara-digital-twin-reference-architecture`（开源、无供应商锁定、不限引擎）
**六层**：① 物理（IoT 传感器 / PLC / SCADA / 工业协议 MQTT·OPC UA·Modbus·PROFINET）
② 数据摄取（CAD/BIM、时序库、流处理、GIS）③ **核心处理（Unity 在这一层）**
④ 服务（分析 / ML / 培训 / 闭环指令调度）⑤ 云基础设施 ⑥（并行）MBSE 集成层

## 📐 数字孪生博客推荐的四层（与用户现有分层吻合）
① 表现层（场景/UI/相机/特效）② 业务逻辑层（数据管理器 / 设备行为控制器 / 交互处理器 / 仿真引擎）
③ **服务层（通信模块 MQTT·WebSocket·REST + 数据缓存预处理）—— 明确要求"与 MonoBehaviour 解耦，纯 C# 类"**
④ 数据层（后端接口）
其他博客共识做法：**ScriptableObject 存配置**（API 地址/更新频率/颜色映射）、**Input System**、
**MessagePack/Protobuf** 压缩、**开发阶段内置数据模拟器 + 数据录制回放**。

## ⚠️ 自我纠正（重要，别再犯）
我曾在 2026-09-28 告诉用户「你的 DDD 分层是错的，Unity 是 by-feature」——**这个说法是错的**，
依据来自一份第三方 skill 文件而非官方。**官方 Boss Room 用的正是 domain-based assemblies**。
本工程的 `Warehouse.Domain/Application/Infrastructure` 分层**方向正确**，与官方一致。
真正的差距不是分层，而是上面那 6 条具体做法（组合根数量、DI 容器、静态 EventBus、asmdef 引用方式）。

## 对口的现成社区方案（已查证）
- **`Unity-Technologies/Robotics-Warehouse`** —— Unity **官方**仓库机器人仿真环境，Apache-2.0，
  **双分发**：独立工程（URP/HDRP）+ UPM 包（`com.unity.robotics.warehouse.{base,urp,hdrp}`）。
  有 `WarehouseManager`、模板场景、Prefabs 文件夹。**领域最对口，优先参考。**
- **`Granberget/Open-Industry-Project`** —— 免费开源 **PLC 驱动的仓库/制造仿真**（Unity + libplctag.NET），
  支持 ControlLogix / Micro800 / PLC-5 / SLC 500 / MicroLogix / Omron NX-NJ / **Modbus TCP**。
  **做法就是成熟做法**："utilize the abilities of the Unity Editor itself to instantiate equipment"，
  在编辑器里摆设备、Inspector 填字段。
- `dqvid3/smart-warehouse-digital-twin` —— Unity 6 + Neo4j，按功能分目录（Orchestratore/A_star/Neo4j/QRCode）
- `auroraaazx/SmartWarehouseSimulation` —— MAPF + 中央调度，地图数据放 `StreamingAssets`

## 网络可达性（2026-09-28 实测，影响选型）
| 源 | 状态 | 影响 |
|---|---|---|
| github.com | ❌ 000 不可达 | UPM **Git URL 装包全部不可行**（UnityHFSM、ROS-TCP-Connector、URDF-Importer 都走不通） |
| package.openupm.com | ✅ 200 | UnityHFSM 等可走 **OpenUPM scoped registry** 安装 |
| nuget.azure.cn | ✅ 200 | NuGet 可装（`api.nuget.org` 会 302 到此镜像） |
| registry.npmjs.org | ✅ 200 | Node 生态（FUXA、Node-RED）可装 |
| hub.docker.com | ❌ 000 不可达 | **Docker 镜像路径不可行**，需源码/npm 安装 |

## 状态机与 B/S 面板的现成模块（已调研）
- **UnityHFSM**（Inspiaaa，MIT）—— 层级状态机，零 GC，专为 Unity。**走 OpenUPM 装**（GitHub 不通）。
- **Stateless**（dotnet-state-machine，Apache-2.0）—— .NET 主流状态机，netstandard2.0 **零依赖**，NuGet 可装。
- **FUXA**（MIT）—— 开源 Web SCADA/HMI，Node.js + Angular，**内置 Modbus/OPC-UA/MQTT/Siemens S7 驱动**，拖拽式编辑器，含 DAQ 历史库与报警。
- Node-RED / ThingsBoard / Grafana 为备选。

**架构判断**：FUXA 能直连 S7/MQTT/OPC UA，**B/S 面板不需要 Unity 参与** ——
Unity 是 3D 孪生，FUXA 是 2D 状态面板，两者并列消费同一份状态信号，不是二选一。

## 已装入的社区库（Assets/Plugins/）
| 库 | 版本 | 用途 | 依赖 |
|---|---|---|---|
| S7netplus（S7.Net.dll） | 0.20.0 | Siemens S7 实机 | System.Memory（引擎自带） |
| MQTTnet（MQTTnet.dll） | **4.3.7.1207** | IoT 平台 | **零依赖** |

⚠️ **MQTTnet 必须锁 4.3.x** —— 5.x 基于 .NET 8，Unity 2022.3 跑不了。

**判断一个 NuGet 库能否用于 Unity 只看两件事**：① 有无 `netstandard2.0/2.1` 构建；② 依赖能否满足。

**排除**：FluentModbus（依赖 `System.IO.Ports`，引擎不提供）、realvirtual.io（要 Unity 6 + 付费）、
ROS-TCP-Connector（版本本身无阻，但 GitHub 不可达 → UPM Git URL 装不了，且需 ROS2 环境）。

## 传输实现（Devices/Transport/）
`ITransport` 有 5 个实现：`SimulatedTransport`（默认可跑）/ `SiemensS7Transport` / `MqttTransport` /
`AbbRwsTransport`。**在 Inspector 的 Transport Config 里切 Kind 即可换现场通道，不改代码。**

- S7：批读批写（200ms）+ IO 全在 `Task.Run` 工作线程；大端手工编解码；逻辑标签→点位表映射；
  点位表由 `TransportFactory.BuildS7` 按设备自动生成，每台 16 字节。
- MQTT：逻辑标签直接作主题后缀（`agv/AGV-01.PosX`）；协议 V500。
- ABB RWS：**不需要任何库**，就是 HTTP；轮转式请求避免打满控制器 Web 服务。

⚠️ **三个通道只做过编译验证，未接真机**。API 已对着真实 DLL 核对。


## 关键坑（已踩过）
- **NuGet 包 Unity 不消费**：`S7.Net` / `MQTTnet` / `OPC Foundation` 都要手动取 dll 放 `Assets/Plugins/`。
- `.meta` 必须与同名文件同目录同级，孤立 meta 会被当垃圾资产。
- 场景 YAML 手写时 GameObject 的 `m_Component` 列表不能有空行。
- 静态相机不需要 AudioListener。
- VisualScripting 不用于业务逻辑（无法 code review / diff）。

## 已有资产
- `README.md` —— 新结构说明 + 首次运行步骤（菜单 `AgvDemo → 一键生成场景与配置`）。
- `Packages/{vcontainer, unitask, messagepipe, messagepipe.vcontainer}` —— 嵌入式包（**不要删**）。
- `Assets/Plugins/{S7.Net.dll, MQTTnet.dll}` —— 协议库。
- `Docs/IoT设备接入与工具选型.md`、`Docs/社区库选型与接入.md` —— 工具核实与协议实现要点。
- `Tools/compile-check/` —— 离线编译验证工程（已更新为含嵌入式包的版本）。
- **场景与配置资产由编辑器菜单生成**，工程当前无 `.scene` 文件（旧的已失效并移除）。

## ⚠️ 场景已移除的说明
旧 `Assets/Scenes/AgvDemo.scene` 引用了已被删除的旧脚本，会产生 Missing Script，已移除。
**首次打开工程后必须跑一次菜单 `AgvDemo → 一键生成场景与配置`**，否则没有可运行场景。

## ⚠️ 用户质疑「你确定是社区主流架构吗」——核实后承认过度自信（2026-09-28）
核实来源：dev.to 程序集架构文、CSDN 多篇程序集划分文、Unity 官方 scaling-workflows 博客。

**结论：Unity 社区不存在"唯一主流架构"。** 多套并行惯例，措辞必须谨慎，不能说"就是主流"。

**分类**：
1. **有明确依据**：VContainer（官方 Boss Room 用）、MessagePipe、UniTask、`ApplicationLifecycle` 目录名（Boss Room 实有）。
2. **只是"一种合理做法"，非唯一标准**：程序集命名。社区并行存在
   `Project.Core/UI/Rendering/Inventory`、`Project.Inventory.Presentation`、
   `Core/GameLogic/Data/UI/Audio/Editor`、DDD 精简版四层（表现/应用/领域/基础设施）等多种方案。
   我的 `Devices/Warehouse/ApplicationLifecycle/Presentation` 属于其中一种，**不是"the"主流**。
3. **明确偏离社区做法**（待改）：
   - **状态机自己写** —— 社区推荐 **UnityHFSM**（1,586★）或 Stateless；纯手写被明列为
     "缺点：需手动管理状态切换、新人上手难度较高"。**我调研了 UnityHFSM 却没用**，这是最明显的不一致。
   - **没有 Prefab** —— 社区反复强调 Prefab 模块化（"避免超级 Prefab"）。
   - **没有 per-module Editor 程序集** —— 社区惯例是每个模块下再分 `_Runtime/` + `Editor/`
     并各有 asmdef（如 `ProjectEditor.Core.asmdef`）；我只有**一个**总 Editor 程序集。
   - **asmdef 用名字引用而非 GUID** —— Boss Room 明确改用 GUID（"better durability when refactoring"）。
   - **没有 Tests 程序集** —— Boss Room 有。
   - **场景仍由代码 new GameObject 生成**（只是搬到了编辑器菜单），不是"在编辑器里摆 Prefab"。

## 📡 论坛与真实项目调研（2026-09-28，GitHub API 实取结构 + CSDN/dev.to 原声）

### 四个真实工业/仓库 Unity 项目的实际结构（全部实取，非推测）
| 项目 | 结构 | asmdef 分层 |
|---|---|---|
| **Unity 官方 Robotics-Warehouse**（`com.unity.robotics.warehouse.base`） | 只有 `Scripts/` + `Resources/` + `package.json` | **无** |
| **Granberget/Open-Industry-Project**（PLC 驱动仓库仿真） | `3DConveyor/ Editor/ Images/ Materials/ Models/ Pallet/ Scripts/ Textures/` + 一堆设备 `.prefab` | **无** |
| **dqvid3/smart-warehouse-digital-twin** | `Plugins/ Prefabs/ StarterAssets/ TextMesh Pro/ Textures/ UI Toolkit/ UnityWarehouseSceneHDRP/`，`RandomMovement.cs` 直接放 Assets 根 | **无** |
| **auroraaazx/SmartWarehouseSimulation** | `Prefabs/ Plugins/ Scenes/ Script/`，场景直接放 Assets 根 | **无** |

**Open-Industry-Project 的 `Scripts/` 内容**（最能说明问题）：
`Beacon.cs / BoxSpawner.cs / ConvTextureMover.cs / Conveyor.cs / Diverter.cs / ElevatorConveyor.cs / Photoeye.cs / Powerturn.cs / RetExtConveyor.cs / UpdateInEdit.cs`
→ **全部平铺，一个设备 = 一个 MonoBehaviour 脚本 + 一个 Prefab（PLC.prefab / Photoeye.prefab / StraightConveyor.prefab…）**

**结论：工业仿真项目的通行做法是「扁平类型目录 + 设备即 Prefab」，不用 DDD 分层、不用多层 asmdef。**
（Boss Room 有 10+ asmdef，但它是**多人游戏项目**，不是工业仿真 —— 参照系不同。）

### 论坛对 asmdef 的真实态度（有争议，不是"必须拆"）
- **CSDN《少女前线》架构文**：「**过早或过度地拆分程序集可能会增加管理复杂度。对于中小型项目，可以先用文件夹和命名空间进行逻辑划分，待模块边界清晰、依赖稳定后再引入多程序集。**」
- **dev.to 程序集文**明确列出「何时**不要**建程序集」：① 微程序集（别建 `.Swords.asmdef`/`.Bows.asmdef`，内聚的放一起）② 不要为存资产建程序集 ③ **原型期/Game Jam 不要写 asmdef**（"Let the code be messy in the default Assembly-CSharp until... the natural domain boundaries reveal themselves"）
- **hqwc《asmdef 依赖分层实战》**：「**别拆得过碎**…拆成几百个反而拖慢」「很多人拆 asmdef 拆到一半放弃 —— 原来纠缠的代码一拆就暴露出满地循环依赖」
- **tsight 方案对比表**：目录隔离（★★★☆☆ / **小型项目**）· 预编译指令（★★☆☆☆）· **asmdef（★★★★★ / 中大型工程）**
- **CSDN《Unity实战开发规范》**：反方观点——「很多团队不会在项目初期引入程序集定义，等到项目变大…整个工程都要重新编译十几秒」，建议一开始就拆 Framework/GamePlay/UI

### 对本项目的判断（有依据）
本项目 **27 个脚本 / 5 个程序集**。按 tsight 表属"小型项目"档；按 CSDN 少女前线文属"中小型项目应先用文件夹+命名空间"。
→ **就论坛共识而言，现在拆 5 个程序集偏早、偏碎。**

## 🔍 逐层核对：我的结构 vs 三个参照物（2026-09-28 实取数据）
GitHub API 实取，**决定性对比**：

| 参照物 | Scripts 层 | 下面有什么 |
|---|---|---|
| **Unity 官方 Robotics-Warehouse** | `Scripts/{Editor, Runtime}` | Runtime 下只有 **`AppParam.cs` + `WarehouseManager.cs`(14.6KB 单个大文件) + `PerceptionRandomizers/` + 一个 asmdef** |
| **Granberget/Open-Industry-Project** | `Scripts/` | **10 个 .cs 平铺，无子目录** |
| **auroraaazx/SmartWarehouseSimulation** | `Script/` | **按功能分 3 个：`Central Control/` `Map/` `PathPlanning/`** |
| **本项目** | `Scripts/{Editor, Runtime}` | Runtime 下 **4 个域目录；Devices 下再 7 个子目录 = 共 12 个目录 / 30 个 .cs** |

**结论：**
- ✅ **外层 `Scripts/{Editor, Runtime}` + 一个运行时 asmdef** —— 与官方 Robotics-Warehouse **完全一致**，有依据。
- ❌ **内层 12 个子目录**（Domain/Transport/Device/Telemetry/Gateway/Messages/Configuration）—— **三个参照物没有任何一个这么分**，是我自己加的。
- ❌ **VContainer / MessagePipe / UniTask** —— 三个工业参照物**一个都没用**（依据只在 Boss Room 那种游戏项目）。

**⚠️ 反讽**：官方 Robotics-Warehouse 自己用的就是 `WarehouseManager.cs`（14.6KB 单个大文件）——
正是我前面反复批评并极力避免的「Massive Manager classes」。**官方在工业项目里就是这么写的。**

**对比本质**：参照物按 **功能** 分（Central Control / Map / PathPlanning）或干脆平铺；
我按 **技术层** 分（Domain/Transport/Device…）。这是两种不同的切法。
