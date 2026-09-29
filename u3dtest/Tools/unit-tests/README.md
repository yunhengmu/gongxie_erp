# 离线单测（EditMode 的替代通道）

本机编辑器起不来（授权校验卡死，见 `Tools/compile-check/README.md`），
`Assets/Tests/EditMode` 下的测试因此无法在编辑器里跑。这个工程在 dotnet 下
复刻同一份测试源码，让单测可以离线执行。

## 用法

```bash
cd Tools/unit-tests
dotnet test UnitTests.csproj --nologo
```

当前 17 个用例，全部通过即绿。

## 覆盖范围

与 `Assets/Tests/EditMode` 完全一致：Core + Devices 的源码 + 测试源码。
只编译 Runtime，不含 Editor / VContainer / UI —— 单测不该依赖它们。

## 为什么这样可行

Core 程序集零引擎依赖（见 asmdef），Devices 里只有 Settings / Transport 用了
UnityEngine（`Serializable`、`UnityWebRequest`）。因此只需要：

- 编译时引 `UnityEngine.dll` + `CoreModule` / `PhysicsModule` / `UnityWebRequestModule`
- 测试进程要真的加载这些程序集，所以 `Private=true` 复制到输出目录
- MessagePipe 源码一起编译（DeviceGateway 用到），UniTask 顺带带上

## 关键配置（踩过的坑，别动）

| 项 | 值 | 原因 |
|---|---|---|
| `TargetFramework` | `net10.0` | 本机只有 .NET 10 与 .NET 6 运行时 |
| `LangVersion` | `9.0` | 与 Unity 2022.3 一致，避免用到引擎不支持的语法 |
| `DefineConstants` | 不含 `UNITY_EDITOR` | 含了会触发 UniTask 的 `InitializeOnLoadMethod`，需要 UnityEditor.dll |
| UnityEngine 引用 | `Private=true` | 假编译（`Private=false`）会让 NUnit 找不到程序集：`NUnit failed to load ...dll` |

## 局限

能验证逻辑正确性；**不能验证** asmdef 边界、序列化字段与场景 YAML 是否匹配
（仍需打开编辑器确认）。
