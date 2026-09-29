# 离线编译验证

不启动编辑器，直接用 dotnet 编译 `Assets/Scripts` 全部脚本 **以及 `Packages/` 下的嵌入式包**。

## 用法

```bash
cd Tools/compile-check
dotnet build CompileCheck.csproj -v q --nologo
```

约 3 秒出结果。`0 个错误` 即通过。

## 为什么需要它

编辑器批处理（`-batchmode -nographics -quit`）在本机卡在授权校验：
`Cannot save ULF license file ... Tuanjie_lic.ulf is denied`，日志停在
`Application.AssetDatabase Initial Refresh Start` 不再前进。这个验证工程完全绕开编辑器。

## 覆盖范围

同时编译：`Assets/Scripts`（Runtime + Editor）、`Assets/Tests`（EditMode 测试）+ VContainer + UniTask + MessagePipe（含 DI 集成）。
因此**第三方库的 API 用法、测试代码本身也一并验证**，不必等编辑器。

想真正**执行**测试而不是只编译，用隔壁的 [`Tools/unit-tests`](../unit-tests/README.md)。

## 关键配置（踩过的坑，别动）

| 项 | 值 | 原因 |
|---|---|---|
| `LangVersion` | `9.0` | 必须与 Unity 2022.3 一致，调高会用上引擎不支持的语法 |
| `DefineConstants` | 完整的 `UNITY_20xx_x_OR_NEWER` 集合 | 缺 `UNITY_2019_3_OR_NEWER` → UniTask 走错 PlayerLoop 分支；缺 `UNITY_2018_3_OR_NEWER` → MessagePipe 会去找 `System.Threading.Channels` 与 `Microsoft.Extensions.DI` |
| `UNITY_EDITOR` | **必须定义** | 否则 VContainer 的编辑器扩展编译不过；同时要引 `UnityEditor.dll` |
| `AllowUnsafeBlocks` | `true` | VContainer 的 `FreeList` 用了 unsafe |
| `UnityEngine.dll` 门面 | 必须引 | VContainer 需要 `UnityEngine.Object` |
| `UnityEditor.CoreModule.dll` | **不要引** | 与 `UnityEditor.dll` 同时引会 CS0433 重复类型 |
| 包源码 | 用 `<Compile Remove="**/Editor/**" />` 排除 | 包里的编辑器代码需要完整 UnityEditor 上下文 |
| `nunit.framework.dll` | 从 `Library/PackageCache/com.unity.ext.nunit@*/` 用通配符引 | 版本号在目录名里，写死会在包升级后失配 |

## 局限

**能验证**：语法、类型、API 签名、跨程序集调用。

**不能验证**（仍需打开编辑器确认）：

| 项 | 说明 |
|---|---|
| asmdef 程序集边界 | 引用是否成立、有无循环依赖 |
| `.meta` / GUID | 场景里的 Missing Script |
| 序列化字段名 | 与场景 YAML 是否匹配 |
| 运行时行为 | 逻辑是否正确 |

## 参考程序集路径

取自 `D:/tool/Tuanjie Cowork/resource/2022.3.62t16/Editor/Data/Managed/`。
升级引擎版本后需同步更新 csproj 里的 `HintPath`。
