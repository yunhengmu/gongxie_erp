# MEMORY.md — ABP 学习项目约定

工作区：`D:\yudao\abp\ABP-Framework-rel-10.6`（ABP 10.6 源码仓库 + 学习产物）
用户背景：Java / Spring 出身，正在学 .NET / ABP。讲解一律优先用 Spring 生态类比。

## 文档编辑铁律（用户反复强调，违反必被纠正）

1. **最小化改动**：能改一行就不加一段，能改一段就不加一节。补内容优先塞进既有段落 / 表格 / note，只有用户明确要求才新增 `h3` / `h4`。
2. **不改代码块**：文中 C# / TS 属**伪代码**，不要修正其中 API、补构造函数、补领域方法。原话：「不要改动代码，那个是伪代码没必要改」。
3. **只做被要求的那件事**：说「把 X 写进文档」= 只写 X，不要顺手补 X 的相关话题。曾因擅自扩充整块被要求「删干净」。
4. **用户报「文档没写 X」= 要你补 X**：补完要在回复里逐条列出改了哪几处，便于核验。
5. **行文**：先讲清「这解决什么问题 / 涉及对象与前文什么关系 / 谁需要它」，再贴代码。表格是结论的呈现，不能替代理路。示例代码不能悬空。
6. **未获明确指示，绝对不碰 HTML 文档**：用户问技术问题时**就在对话里回答**，不要「顺手把答案也写进文档」。原话：「你不要乱改我的html文档，我没让你改你不要改」。
7. **代码同理：只答不做**。问目录结构 / 文件该放哪，给分析即可，不要顺手建文件、建目录、删目录。原话：「你不要写代码」（2026-09-12，Acme.Learn 讨论 WMS 落点目录时）。
8. **对话里给 C# 片段必须带全 `namespace` 与 `using`**：用户会逐字照抄。项目未启用 ImplicitUsings，且 `Acme.Learn.Domain` / `Acme.Learn.Application.Contracts` 的 RootNamespace 都被模板设成 `Acme.Learn`（刻意去后缀）。2026-09-14 因 DTO 示例漏 namespace + using，用户照抄后三个文件共报 13 个 CS0246。
9. **前端 / 新领域代码要逐行注释**：用户 Angular 与 RxJS 零基础。模板语法用 Vue 对照：`v-for`≈`@for`/`*ngFor`、`:key`≈`track`、`v-if`≈`@if`、`v-model`≈`[(ngModel)]`、`:prop`≈`[prop]`、`@click`≈`(click)`、`ref`≈`#var`。**RxJS 在 Vue 无对应物**，用 Reactor 类比：`Observable`≈`Flux`、`subscribe()` 同 `Flux.subscribe()`。`ngOnInit`≈`onMounted`/`@PostConstruct`、`inject()`≈`@Autowired`。原话：「把这里的注释写满，不然我看不懂」。

## 交付物

- 主交付物：`.workbuddy\outputs\WMS领域设计-ABP-DDD.html`（单文件 HTML，ABP/DDD 视角的简化 WMS 领域设计，11 章 + Angular 前端章节）
- 同目录另有：`ABP-Framework-应用开发指南.html`、`ABP-模块代码详解.html`、`Spring到ABP概念对照.html`、`WMS前端示例.html`
- 验证方法：改完用脚本比对 `<pre>` / `<svg>` / `<table>` 是否逐字节未变 + 标签计数是否平衡（`-X utf8` 必须加，否则 Windows Git Bash 下中文乱码）

## 工具链与运行环境（Acme.Learn）

- **编辑器是 VS Code**（非 VS 2022 / Rider）。VS Code 无原生「新建类」，靠 C# Dev Kit 命令面板 `Ctrl+Shift+P` → `.NET: New C# Class`。
- .NET SDK `10.0.401`（系统唯一），ABP 10.6.0，target `net10.0`；解决方案 `Acme.Learn.slnx`（.NET 10 新格式）；`dotnet build` 全解决方案 0 warning / 0 error。
- **未开启 ImplicitUsings** → 所有 `using` 必须显式写，连 `System` 都要。
- `Acme.Learn.Domain.csproj` RootNamespace = `Acme.Learn`（去 `.Domain`）→ `Wms\Warehouse\Warehouse.cs` 是 `namespace Acme.Learn.Wms.Warehouse;`；`Acme.Learn.EntityFrameworkCore` 的 RootNamespace 是全名。两项目规则不同。
- `dotnet-ef` 10.0.12、`abp` CLI 3.1.1 已全局安装。
- **数据库 SQLite**：`D:\yudao\abp\Acme.Learn\Learn.db`；DbMigrator 连接串 `Data Source=../../Learn.db;`。**应用迁移必须用 `DbMigrator`（`dotnet run`）**，它同时跑 Seed；只用 `dotnet ef database update` 会跳过 Seed，缺权限定义 / OpenIddict 客户端而登录不了。
- **SQLite 的 `TEXT` 不强制长度**：`HasMaxLength(n)` 只是 EF 元数据。长度约束靠 DTO 上的 DataAnnotations 兜。
- **新增权限后必须重跑 DbMigrator**：`IdentityDataSeedContributor` 每次运行都会把「已定义」权限重新 Grant 给 admin 角色（实测 21→25 条），否则接口 403。
- **浏览器里前端报 `status: 0 undefined`，八成是 HTTPS 开发证书没信任**（不是 CORS）。已用 `dotnet dev-certs https --trust` 修好，证书在 `Cert:\CurrentUser\Root`。注意 `curl -k` 会掩盖这个问题，命令行永远通；要验信任得用不带跳过校验的 `Invoke-WebRequest`。修完必须**完全重启浏览器**。
- **Angular CLI 锁 Node 版本**：`node_modules/@angular/cli/src/utilities/node-version.js` 硬编码 `SUPPORTED_NODE_VERSIONS`，本机 Node 22.22.2 差一个 patch 会被拒。已改下限重，`npm install` 会还原；根治是升级 Node。`abp install-libs` 报 yarn engine 不兼容时，在 `HttpApi.Host/.yarnrc` 放 `ignore-engines true`。

## Acme.Learn 前端技术栈（实测自 `D:\yudao\abp\Acme.Learn\angular`）

「原生 HTML + Bootstrap 类名 + ABP/LeptonX 组件」三层拼成，**不是单一组件库**。

| 层 | 用什么 | 证据 |
|---|---|---|
| 主题 | **LeptonX Lite**（免费版） | `package.json`: `@abp/ng.theme.lepton-x ~5.6.0` |
| CSS | **Bootstrap**（LeptonX 变体） | `angular.json` 引 `bootstrap-dim.css`，含 `.card/.table/.badge/.btn` |
| 数据表格 | **ngx-datatable** | `@swimlane/ngx-datatable` |
| 树 | **NG-ZORRO** 只引了 tree 样式 | `ng-zorro-antd/tree/style/index.min.css` |
| 图标 | FontAwesome + Bootstrap Icons | `angular.json` |
| ABP 组件 | 实测 **72 个 `abp-*` selector** | 扫 `node_modules/@abp/*/fesm2022/*.mjs` 的 `selector:` |

对比：芋道 / Element Plus 给**通用控件库**（几百控件，业务页自己拼）；ABP 反过来 —— 通用控件只一小撮，重点是**业务级现成整页**（`abp-users` `abp-roles` `abp-tenants` `abp-permission-management` `abp-login` 等）。

ABP 有页面骨架件（`abp-page` `abp-page-toolbar` `abp-dynamic-form` `abp-extensible-table` `abp-lookup-search` `abp-chart`）与基础件（`abp-button` `abp-card` `abp-modal` `abp-toast` `abp-confirmation`）。

**⚠️ ABP 没有 `abp-pagination` 组件**（扫遍 `@abp`/`@volo` 零命中）→ 用 Bootstrap `.pagination` 自己写，或 ngx-datatable 自带 footer。**`WMS领域设计-ABP-DDD.html` 第 09 节 ④ 里的 `<abp-pagination>` 是错误组件名，需修正**（尚未改，等用户发话）。

**给用户的建议（Angular + WMS）**：别换组件库 —— ABP 自带模块全是 Bootstrap 风格，换库会视觉撕裂。重表格用已装的 ngx-datatable；关联选择优先 `abp-lookup-search` / `abp-dynamic-form`。

## ABP 各前端版本的 UI 栈（实测自 `templates/` 与 `framework/src/`）

| 前端 | 主题 / 组件库 |
|---|---|
| Angular SPA | **LeptonX Lite**；备选 Basic 主题。无整套组件库 |
| MVC / Razor Pages | `Volo.Abp.AspNetCore.Mvc.UI.Theme.LeptonXLite`（Bootstrap） |
| Blazor | **MudBlazor 9.4.0**（`Volo.Abp.AspNetCore.Components.*.Theming.MudBlazor`）；旧版 **Blazorise 2.2.1**（`Volo.Abp.BlazoriseUI`）框架仍留 |

- 框架内两套 Blazor UI 并存：Blazorise（Bootstrap 5，长期默认）与 MudBlazor（Material，10.5.0 新增，MIT）。
- 但本仓库 10.6 `templates/` 里**只有 MudBlazor 版本**（全量 grep 无 Blazorise）→ 说法冲突，实际以 ABP CLI / Studio 新建时的选项为准，**不要断言「模板已全面切换」**。
- Blazor 模板同时引 LeptonXLite 是给登录页 / 错误页用的；MVC 与 Angular 共用 LeptonX Lite 品牌，底层都是 Bootstrap。

**主流搭配**：Angular 常配 PrimeNG（ABP 官方社区有教程，最"官方背书"）/ Angular Material / NG-ZORRO（国内常用）/ Tailwind + CDK；MVC 默认 Bootstrap + DevExtreme / Syncfusion；Blazor 官方两套 + 社区 `Lsw.Abp.AntDesignUI`、Masa.Blazor、`Abp.RadzenUI`。

**2026 趋势（供选型参考）**：Tailwind 主导、CSS-in-JS 退潮、shadcn/ui 范式（复制源码而非装依赖）胜出、headless 无样式化主流。ABP 的 Bootstrap + LeptonX 属上一代「主题式」方案，但定位企业后台，更贴近 antd / Material 那一派。

## 遗留待办

- `WMS领域设计-ABP-DDD.html` 第 09 节内容矛盾：`WarehouseService` 声明 `@Injectable({providedIn:'root'})` 却又用 `providers: [WarehouseService]` 演示「必须手动 providers」。正确主角应是 `ListService`。已提两方案（① 换 `ListService`，推荐；② 去掉 `providedIn`），用户未拍板。
- 实体 `Warehouse` 构造函数里写死 `IsActive = false`，且 `CreateUpdateWarehouseDto` 无 `IsActive` 字段、更新只调 `Rename()` → `Activate()` 永远走不到，「新建的仓库必然是停用状态」。已给两个方案（构造函数改 true，或 DTO 加字段），用户未选。
- 「仓库编码不能重复」规则尚未落地（`LearnDomainErrorCodes` 仍是空的）。
- 是否动手落地 WMS 代码（方案 A：主数据三聚合 + Migration；方案 B：全量）—— 始终未选。
