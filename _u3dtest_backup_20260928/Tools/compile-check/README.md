# 离线编译验证

不启动编辑器，直接用 dotnet 编译 `Assets/Scripts` 下的全部脚本。

## 用法

```bash
cd Tools/compile-check
dotnet build CompileCheck.csproj -v q --nologo
```

约 3 秒出结果。`0 个错误` 即通过。

## 为什么需要它

编辑器批处理（`-batchmode -nographics -quit`）在本机会卡在授权校验：
`Cannot save ULF license file ... Tuanjie_lic.ulf is denied`，日志停在
`Application.AssetDatabase Initial Refresh Start` 不再前进。

这个验证工程完全绕开编辑器，直接调用引擎自带的参考程序集编译。

## 覆盖范围与局限

**能验证**：语法错误、类型错误、API 签名、命名空间。

**不能验证**（仍需打开编辑器确认）：

| 项 | 说明 |
|---|---|
| asmdef 程序集边界 | 引用是否成立、有无循环依赖 |
| `.meta` / GUID | 场景里的 Missing Script |
| 序列化字段名 | 与场景 YAML 是否匹配 |
| 运行时行为 | 逻辑是否正确 |

## 参考程序集路径

取自 `D:/tool/Tuanjie Cowork/resource/2022.3.62t16/Editor/Data/Managed/`。
升级引擎版本后需要同步更新 csproj 里的 `HintPath`。

## 第三方插件

csproj 同时引用了 `Assets/Plugins/` 下的托管插件（S7.Net / MQTTnet），
因此验证能覆盖到真实库的 API 调用 —— 这也是确认「某个库的 API 用法对不对」的手段。
新增插件后记得在 csproj 里补 `<Reference>`。

## 约束

`LangVersion` 固定为 `9.0`，与 Unity/团结 2022.3 一致。**不要调高**，否则会用上引擎不支持的语法，验证结果就失真了。
