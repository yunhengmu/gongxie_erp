# ABP Framework 深度解析（rel-10.6）

> 基于源码仓库 `D:\yudao\abp\ABP-Framework-rel-10.6` 逐文件精读，结论精确到方法/函数级，附文件路径与行号。
> 覆盖：模块化内核 → DI/AOP → DDD/仓储/UoW → Web/动态API/多租户 → 开发工作流。

---

## 0. 结论先行

ABP 的本质是一个**模块化 + 约定优于配置**的企业级应用脚手架。它做三件事：

1. **模块系统（Modularity）** 把任意程序集组织成有向依赖图，统一加载顺序与生命周期；
2. **约定式 DI + AOP 拦截** 让 AppService 自动变成 REST API、自动获得事务/授权/审计/校验能力，业务代码零侵入；
3. **DDD 基础设施** 提供实体基类、仓储、工作单元、数据过滤器、多租户，把横切关注点封装进基座。

一句话调用链：`HTTP 请求 → 中间件管道 → 动态生成的 Controller → AppService 代理 → 拦截器链(UoW/授权/审计) → DomainService/Repository → EF Core → UoW.CompleteAsync(提交事务+发事件)`。

| 维度 | 物理位置 | 核心类型 | 规模 |
|---|---|---|---|
| 框架包 | `framework/src/` | `Volo.Abp.Core` 等 140+ 包 | 2137 个 .cs |
| 应用模块 | `modules/` | Identity / SaaS / CMS Kit 等 | 2502 文件 |
| 项目模板 | `templates/` | app / module / maui / wpf | — |
| 命令行 | `framework/src/Volo.Abp.Cli` | `NewCommand` 等 | — |
| 前端包 | `npm/` | `@abp/ng.*` | 193 个 .ts |

---

## 1. 模块系统：框架的心脏

### 1.1 AbpModule 生命周期（函数级）

`framework/src/Volo.Abp.Core/Volo/Abp/Modularity/AbpModule.cs`

抽象基类，实现 6 组生命周期接口。**所有方法均为 `virtual`，且同步版与 Async 版成对出现——Async 版默认调用同步版并返回 `Task.CompletedTask`**（如 `AbpModule.cs:34-38`），因此子类只需重写一个（推荐 Async）。

| 阶段 | 方法签名 | 触发时机 |
|---|---|---|
| PreConfigureServices | `virtual Task PreConfigureServicesAsync(ServiceConfigurationContext)` `:34` | DI 注册前，设置选项默认值 |
| ConfigureServices | `virtual Task ConfigureServicesAsync(ServiceConfigurationContext)` `:45` | **DI 注册主战场** |
| PostConfigureServices | `virtual Task PostConfigureServicesAsync(ServiceConfigurationContext)` `:56` | 注册后修正 |
| OnPreApplicationInitialization | `virtual Task OnPreApplicationInitializationAsync(ApplicationInitializationContext)` `:67` | 管道构建前 |
| OnApplicationInitialization | `virtual Task OnApplicationInitializationAsync(ApplicationInitializationContext)` `:78` | **构建中间件管道** |
| OnPostApplicationInitialization | `virtual Task OnPostApplicationInitializationAsync(ApplicationInitializationContext)` `:89` | 初始化完成 |
| OnApplicationShutdown | `virtual Task OnApplicationShutdownAsync(ApplicationShutdownContext)` `:100` | 优雅关闭 |

关键属性/辅助方法：
- `SkipAutoServiceRegistration` `:18` — 跳过程序集自动注册；
- `ServiceConfigurationContext` `:20` — 仅在三个 Configure 阶段可用，否则抛 `AbpException`；
- `Configure<TOptions>(Action<TOptions>)` `:130`、`PreConfigure` `:160`、`PostConfigure` `:166` — 选项配置语法糖；
- `static IsAbpModule(Type)` `:111` / `CheckAbpModuleType` `:122` — 判定：非抽象、非泛型、实现 `IAbpModule` 的类。

### 1.2 模块装载与拓扑排序（函数级）

`Modularity/ModuleLoader.cs`

- `LoadModules(services, startupModuleType, plugInSources)` `:11` → `GetDescriptors` `:27` → `SortByDependency` `:74`。
- `FillModules` `:40`：`AbpModuleHelper.FindAllModuleTypes(startupModuleType)` 递归收集 `[DependsOn]` 声明的全部模块（去重见 `AbpModuleHelper.cs:69`），再追加插件模块。
- `CreateAndRegisterModule` `:86`：
  ```csharp
  var module = (IAbpModule)Activator.CreateInstance(moduleType)!;
  services.AddSingleton(moduleType, module);   // 模块实例本身注册进 DI
  ```
- `SetDependencies` `:93`：按 `AbpModuleHelper.FindDependedModuleTypes` 把依赖 `AddDependency`，**找不到依赖直接抛 `AbpException`**（`:100`）。
- **拓扑排序** `SortByDependency` `:74`：
  ```csharp
  var sortedModules = modules.SortByDependencies(m => m.Dependencies); // 被依赖者在前
  sortedModules.MoveItem(m => m.Type == startupModuleType, modules.Count - 1); // 启动模块置末
  ```
  这正是"被依赖模块先初始化"的数学保证。

### 1.3 初始化阶段：Contributor × Module 双层循环（函数级）

`Modularity/ModuleManager.cs`（`ISingletonDependency`）

- 构造器 `:18` 从 `AbpModuleLifecycleOptions.Contributors` 解析出 `IModuleLifecycleContributor[]`。
- `InitializeModulesAsync` `:34`：
  ```csharp
  foreach (var contributor in _lifecycleContributors)      // 外层：阶段
      foreach (var module in _moduleContainer.Modules)     // 内层：拓扑序模块
          await contributor.InitializeAsync(context, module.Instance);
  ```
  单模块异常包装为 `AbpInitializationException`（`:46`）。
- `ShutdownModulesAsync` `:74`：`_moduleContainer.Modules.Reverse()` **逆序关闭**。
- 四个 Contributor 定义在 `DefaultModuleLifecycleContributor.cs`：分别触发 Pre / On / Post 初始化与 Shutdown，通过 `module is IOnApplicationInitialization` 类型判断分发。

> 注意：`IModuleManager`（`IModuleManager.cs:6`）**只有 4 个方法**（Initialize/Shutdown 的同步+异步），并**没有 `AddModule`**——装载与 DI 注册发生在构造期的 `ModuleLoader`，不由 ModuleManager 负责。

### 1.4 应用启动：AbpApplicationBase 与工厂（函数级）

`Volo.Abp/AbpApplicationBase.cs`

- 构造器 `:39` 顺序：注册 `IAbpApplication`/`IModuleContainer` → `AddCoreServices`/`AddCoreAbpServices` → `LoadModules` → 若未 `SkipConfigureServices` 则立即 `ConfigureServices()`。
- `ConfigureServicesAsync` `:218`（同步版 `:311`）严格三段：
  1. `CheckMultipleConfigureServices` 防重复，建 `ServiceConfigurationContext` 并 `AddSingleton(context)`；
  2. 给每个模块赋 `ServiceConfigurationContext`（`:322`）→ **PreConfigureServices**（`:327`）→ 遍历模块：`Services.AddAssembly(assembly)` 自动注册程序集（`:346`）+ `module.ConfigureServices(context)`（`:361`）→ **PostConfigureServices**（`:370`）；
  3. 清空 context，置 `_configuredServices = true`。
- `InitializeModulesAsync` `:107`：创建 Scope → `WriteInitLogs` → `IModuleManager.InitializeModulesAsync(new ApplicationInitializationContext(sp))`——**此处才触发各模块的 `OnApplicationInitialization`**。
- `ShutdownAsync` `:86`：Scope 内调 `ShutdownModulesAsync`。

`Volo.Abp/AbpApplicationFactory.cs` 与两种实现：

| 类型 | 文件 | 语义 |
|---|---|---|
| `AbpApplicationWithInternalServiceProvider` | `AbpApplicationWithInternalServiceProvider.cs` | 自带 `new ServiceCollection()`，`CreateServiceProvider()` `:35` 内建 Scope；`Initialize` `:49` = 建 SP → InitializeModules → SetupTelemetry；`Dispose` `:64` 释放 Scope |
| `AbpApplicationWithExternalServiceProvider` | `AbpApplicationWithExternalServiceProvider.cs` | SP 由宿主（ASP.NET Core）提供，`SetServiceProvider` `:22` 重复设不同实例抛异常；`Dispose` `:62` 释放外部 SP |

工厂：`Create<TStartupModule>` 返回 Internal（`:65`），`Create(services)` 返回 External（`:79`）；`CreateAsync` `:11-63` 先以 `SkipConfigureServices=true` 构造实例，再 `await ConfigureServicesAsync()`。

---

## 2. 依赖注入：约定注册（函数级）

`Volo.Abp.Core/Volo/Abp/DependencyInjection/DefaultConventionalRegistrar.cs`

### 2.1 `AddType(Type type)` `:11-57` 逐类型流程

1. `IsConventionalRegistrationDisabled(type)`（基类 `ConventionalRegistrarBase.cs:52`）→ 命中 `[DisableConventionalRegistration]` 直接 return；
2. `GetDependencyAttributeOrNull` `:75` 取 `[Dependency]`；
3. **生命周期判定** `GetLifeTimeOrNull` `:80` = `attr?.Lifetime ?? GetServiceLifetimeFromClassHierarchy(type) ?? GetDefaultLifeTimeOrNull(type)`，返回 null 则跳过；
   `GetServiceLifetimeFromClassHierarchy` `:85`：`ITransientDependency→Transient`、`ISingletonDependency→Singleton`、`IScopedDependency→Scoped`；
4. **暴露服务类型** = `GetExposedKeyedServiceTypes` + `GetExposedServiceTypes`，经 `TriggerServiceExposing` `:57` 触发 `OnExposing` 钩子；
5. 对每个暴露类型 `CreateServiceDescriptor` `:120` 生成 `ServiceDescriptor`，按 `[Dependency]` 的 `ReplaceServices` / `TryRegister` 选择 `services.Replace` / `TryAdd` / `Add`。

### 2.2 默认暴露规则

`ExposedServiceExplorer.GetExposedServices`（`ExposedServiceExplorer.cs:16`）：收集所有 `IExposedServiceTypesProvider`；无则回退默认 `IncludeDefaults=true, IncludeSelf=true`。

`ExposeServicesAttribute.GetDefaultServices`（`ExposeServicesAttribute.cs:46-70`）：遍历接口，去掉 `I` 前缀，若实现类名以接口名结尾则纳入 —— **这就是 `IBookAppService → BookAppService` 自动暴露的算法**。

`AddAssembly`（基类 `:13`）：`AssemblyHelper.GetAllTypes` 过滤非抽象非泛型类 → `AddTypes`。

### 2.3 注册钩子与拦截器织入（函数级）

- `services.OnRegistered(action)`（`ServiceCollectionRegistrationActionExtensions.cs:11`）把回调存入 `ServiceRegistrationActionList`；
- 该列表**不在 Registrar 内消费**，而是在 Autofac 构建时由 `AbpRegistrationBuilderExtensions.InvokeRegistrationActions`（`Volo.Abp.Autofac/.../AbpRegistrationBuilderExtensions.cs:69`）遍历调用，收集 `context.Interceptors`；
- `AddInterceptors` `:125`：接口类型用 `EnableInterfaceInterceptors()`，类类型用 `EnableClassInterceptors()`，再 `InterceptedBy(typeof(AbpAsyncDeterminationInterceptor<>).MakeGenericType(interceptor))`；
- `EnablePropertyInjection` `:100`：对模块程序集内、未标 `[DisablePropertyInjection]` 的类型执行 `PropertiesAutowired(new AbpPropertySelector(false))`。

### 2.4 AOP 拦截器层级（函数级）

```
业务方法调用
  └─ Castle DynamicProxy 代理
       └─ AbpAsyncDeterminationInterceptor<TInterceptor>     (Castle 原生 AsyncDeterminationInterceptor)
            └─ CastleAsyncAbpInterceptorAdapter<TInterceptor>  (AsyncInterceptorBase)
                 └─ AbpInterceptor.InterceptAsync(IAbpMethodInvocation)   ← 你写的拦截器
```

- `IAbpInterceptor`（`DynamicProxy/IAbpInterceptor.cs:5`）：`Task InterceptAsync(IAbpMethodInvocation)`；`AbpInterceptor` 为抽象基类（本版本仅异步版）。
- 适配器用 `CastleAbpMethodInvocationAdapter` 把 Castle 的调用上下文转成 ABP 的 `IAbpMethodInvocation`，调用后回填 `ReturnValue`。
- `ProxyHelper.cs`：`IsProxy`（命名空间 `Castle.Proxies`）、`UnProxy`（读 `__target` 字段）、`GetUnProxiedType`。
- `AbpLazyServiceProvider`（`AbpLazyServiceProvider.cs:11`）：注入 `IServiceProvider`，提供 `LazyGetService/RequiredService` 延迟解析。

---

## 3. DDD 领域层 + 仓储 + 工作单元（函数级）

### 3.1 实体继承层次

`framework/src/Volo.Abp.Ddd.Domain/`

| 类型 | 关键成员 | 说明 |
|---|---|---|
| `IEntity<TKey>` | `Id` | `IEntity.cs:25`；`GetKeys()` `:13` |
| `Entity<TKey>` | `protected set Id` `Entity.cs:45` | 默认引用相等；领域相等走 `EntityHelper.EntityEquals` `:28`（同引用→真；非 IS-A→假；`IMultiTenant` 租户不同→假；两者 key 均为默认→假；逐 key 比） |
| `BasicAggregateRoot<TKey>` | `AddLocalEvent/AddDistributedEvent/ClearLocalEvents` `:88` | 聚合根 + 领域事件 |
| `AggregateRoot<TKey>` | `ExtraProperties`、`ConcurrencyStamp`(构造生成 GUID) `:37` | 完整聚合根 |
| `FullAuditedAggregateRoot` | + `IsDeleted/DeleterId/DeletionTime` | 实现 `IFullAuditedObject` |

`HasDefaultKeys`（`EntityHelper.cs:183`）：int/long 型 Id `<=0` 视为瞬态（EF Core 兼容）。

### 3.2 IRepository 方法族

- `IReadOnlyBasicRepository<TEntity,TKey>`：`GetAsync(TKey, bool, CancellationToken)` `:45`、`FindAsync(TKey, ...)` `:54`、`GetCountAsync()`、`GetPagedListAsync(skip, max, sorting, bool)` `:18`；
- `IReadOnlyRepository`：`WithDetailsAsync` `:24`、`GetQueryableAsync`、`GetListAsync(Expression, bool)`；
- `IBasicRepository`：`InsertAsync/UpdateAsync/DeleteAsync(entity, bool)` + `InsertMany/UpdateMany/DeleteMany` `:22-80`；
- `RepositoryBase`：`GetAsync(predicate)` 内 `FindAsync` 为 null 抛 `EntityNotFoundException` `:54`；`ApplyDataFilters<TQueryable>` `:79` 对 `ISoftDelete`/`IMultiTenant` 注入 `WhereIf(DataFilter.IsEnabled<...>())`；
- `EfCoreRepository`（`Volo.Abp.EntityFrameworkCore/EfCoreRepository.cs`）：`GetDbContextAsync()` `:49`（非多租户实体用 `CurrentTenant.Change(null)` 绕过租户过滤）、`GetDbSetAsync()` `:71`；`InsertAsync` `:108` = `CheckAndSetId`(Id 为 default 时 `GuidGenerator.Create()`) → `AddAsync` → 可选 `SaveChangesAsync`；`DeleteDirectAsync` 用 `ExecuteDeleteAsync` 批量删除。

### 3.3 工作单元 UnitOfWork（函数级）

`framework/src/Volo.Abp.Uow/`

- `IUnitOfWork`（`IUnitOfWork.cs:9`）：`Items`、`Options`、`Outer`、`IsReserved/IsDisposed/IsCompleted`、`SaveChangesAsync`、`CompleteAsync`、`RollbackAsync`、`OnCompleted(Func<Task>)`、`AddOrReplaceLocalEvent/DistributedEvent`、事件 `Failed/Disposed`。
- `UnitOfWork.CompleteAsync`（`UnitOfWork.cs:128`）：
  `PreventMultipleComplete` → `SaveChangesAsync`（`:102` 遍历各 `ISupportsSavingChanges`）→ **循环发布本地/分布式事件** → `CommitTransactionsAsync`（`:387` 提交各 `TransactionApi`）→ `IsCompleted = true` → `OnCompletedAsync`。
  （接口无独立 `CommitAsync`，提交内嵌在 Complete 中。）
- `UnitOfWorkManager.Begin(options, requiresNew=false)`（`UnitOfWorkManager.cs:26`）：`Current != null && !requiresNew` → 返回 `ChildUnitOfWork(current)`；否则 `CreateNewUnitOfWork` + `Initialize`。
- **环境 UoW**：`AmbientUnitOfWork` 用 `AsyncLocal<IUnitOfWork>`（`AmbientUnitOfWork.cs:11`）；`GetCurrentByChecking` `:23` 跳过 reserved/disposed/completed 并沿 `Outer` 回溯。
- **嵌套语义**：`ChildUnitOfWork.CompleteAsync => Task.CompletedTask`（`ChildUnitOfWork.cs:64`）——子 UoW 不提交，全委托父 UoW。
- 事务行为：`AbpUnitOfWorkDefaultOptions.TransactionBehavior = Auto`，`CalculateIsTransactional` `:36` 在 Auto 下由 `IUnitOfWorkTransactionBehaviourProvider` 与"方法名非 Get 开头"推导。

### 3.4 UnitOfWorkInterceptor

`UnitOfWorkInterceptor.InterceptAsync`（`UnitOfWorkInterceptor.cs:19`）：
1. `UnitOfWorkHelper.IsUnitOfWorkMethod(method, out attr)` `UnitOfWorkHelper.cs:26` —— 依次查方法 `[UnitOfWork]` → 类 `[UnitOfWork]` → `IUnitOfWorkEnabled` 标记接口，`IsDisabled` 返回 false；
2. 非 UoW 方法直接 `ProceedAsync` 返回；
3. 否则 `CreateOptions`（合并 `UnitOfWorkAttribute.SetOptions` 与默认 IsTransactional）；
4. 先 `TryBeginReserved(_AbpActionUnitOfWork)`（中间件 `UseUnitOfWork` 已预留则复用并 `SaveChangesAsync`）；
5. 否则 `using(uow = Begin(options)){ ProceedAsync; CompleteAsync }`。

### 3.5 数据过滤器与审计赋值

- `IDataFilter`（`IDataFilter.cs:15`）：`Enable<TFilter>/Disable<TFilter>/IsEnabled<TFilter>`；`DataFilter<TFilter>`（`DataFilter.cs:52`）用 `AsyncLocal<DataFilterState>`，`Enable/Disable` 返回 `DisposeAction` 自动还原；
- 过滤双落地：仓储层 `RepositoryBase.ApplyDataFilters`（`WhereIf`）+ EF Core 全局查询过滤器 `AbpDbContext.CreateFilterExpression → HasAbpQueryFilter`（`AbpDbContext.cs:951`，多租户表达式 `:975`）；
- **审计属性赋值**由 EF Core 的 `AbpDbContext.SaveChangesAsync` 驱动（`AbpDbContext.cs:203`）：`ApplyAbpConceptsForAddedEntity/ModifiedEntity/DeletedEntity`（`:672-732`）→ `SetCreation/Modification/DeletionAuditProperties`（`:808`）→ 委托 `AuditPropertySetter`（`Volo.Abp.Auditing/AuditPropertySetter.cs:24`）：置 `CreationTime=Clock.Now`、`CreatorId=CurrentUser.Id`、`LastModificationTime`、`DeletionTime`、`EntityVersion++`；
- `Entity` 构造时 `EntityHelper.TrySetTenantId` 从 `AsyncLocalCurrentTenantAccessor` 自动注入 `TenantId`（`Entity.cs:12`）。

---

## 4. Web 集成：动态 API / 多租户 / 授权 / 审计（函数级）

### 4.1 自动 API 控制器（约定式）

`Volo.Abp.AspNetCore.Mvc/Conventions/AbpServiceConvention.cs`，经 `AbpMvcOptionsExtensions.AddConventions` `:53` 以 `AbpServiceConventionWrapper` 挂入 MVC。

- `Apply(ApplicationModel)` `:39` → `ApplyForControllers` `:44`：`RemoveDuplicateControllers` `:107` → `RemoveIntegrationControllersIfNotExposed` `:75` → 遍历 `GetControllers` `:102`；
- **资格判定** `ImplementsRemoteServiceInterface` `:438`（是否实现 `IRemoteService`，`IApplicationService` 即继承之）或显式 `[RemoteService]` `:66`；
- 是则 `ControllerName = ...RemovePostFix(ApplicationService.CommonPostfixes)` `:60` 去掉 `AppService` 后缀，进入 `ConfigureRemoteService` `:194`：
  - `ConfigureApiExplorer` `:269`（`IsVisible` 受 `[RemoteService](Disable/Expose)` 与全局选项控制）；
  - `ConfigureParameters` `:201`（非基元参数 `FromBody` 绑定，但 `id` / GET / DELETE 排除 `:229`）；
  - `ConfigureSelector` `:303` → `AddAbpServiceSelector` `:347` → `SelectHttpMethod` `:360`（按方法名用 `HttpMethodHelper` 推断 GET/POST…）→ `CreateAbpServiceAttributeRouteModel` `:414` → `ConventionalRouteBuilder.Build` `:23`；
- 路由生成：`apiPrefix/api` 或 `integration-api`（`GetApiRoutePrefix` `:76`）+ rootPath（默认 `app`）+ 去后缀控制器名 → **`/api/app/book`**。

### 4.2 中间件管道（模板实证）

`templates/app/aspnet-core/src/...HttpApi.Host/MyProjectNameHttpApiHostModule.cs:179`

```csharp
app.UseAbpRequestLocalization();
app.UseCorrelationId();
app.MapAbpStaticAssets();
app.UseRouting();
app.UseCors();
app.UseAuthentication();          // 必须先于多租户
if (MultiTenancyConsts.IsEnabled) app.UseMultiTenancy();
app.UseUnitOfWork();
app.UseDynamicClaims();
app.UseAuthorization();
app.UseSwagger();
app.UseAuditing();
app.UseAbpSerilogEnrichers();
app.UseConfiguredEndpoints();
```

`AbpAspNetCoreModule.ConfigureServices` `:46`（添加授权/审计贡献者/HttpContextAccessor）；`AbpAspNetCoreMvcModule.ConfigureServices` `:71`（`AddControllersAsServices` `:152`、`AbpConventionalControllerFeatureProvider` `:164`、`AbpMvcActionDescriptorProvider` `:170`）。

### 4.3 多租户解析链

- `ICurrentTenant` / `CurrentTenant`：`Id => _currentTenantAccessor.Current?.TenantId` `:10`；`Change(id, name)` `:21` → `SetCurrent` `:26` 覆盖 `ICurrentTenantAccessor.Current`，返回 `DisposeAction` 释放时还原（嵌套安全）；
- `TenantResolver.ResolveTenantIdOrNameAsync` `:26`：按 `Options.TenantResolvers` 顺序遍历 `TenantResolveContext`，命中 `HasResolvedTenantOrHost` `:42` 即停，否则 `FallbackTenant` `:51`；
- `MultiTenancyMiddleware.InvokeAsync` `:42`：`ITenantConfigurationProvider.GetAsync` `:47` 解析 → `CurrentTenant.Change(tenant.Id, tenant.Name)` `:61` 包裹 `next`；QueryString 解析后写 Cookie `:66`；
- 贡献者：`HeaderTenantResolveContributor`（`TenantKey` 头）、`Route` / `QueryString` / `Cookie` / `Domain` / `Form` / `CurrentUser`；
- `IMultiTenant { Guid? TenantId }` `:5`，数据过滤器表达式见 `AbpDbContext.cs:975`。

### 4.4 授权与审计拦截器

- 授权：`AuthorizationInterceptor.InterceptAsync` `:16` → `MethodInvocationAuthorizationService.CheckAsync` `:23`：`AllowAnonymous` 放行 `:43`，否则收集方法 + 声明类型的 `IAuthorizeData` `:48` → `AuthorizationPolicy.CombineAsync` `:30` → `AbpAuthorizationService.CheckAsync`；`AuthorizationInterceptorRegistrar.RegisterIfNeeded` `:12` 在类型/方法带 `[Authorize]` 时自动织入。
- 审计：`AuditingInterceptor.InterceptAsync` `:24` → `ShouldIntercept` `:51` → 已有 `IAuditingManager.Current` 走 `ProceedByLoggingAsync` `:75`，否则 `ProcessWithNewAuditingScopeAsync` `:117`（`BeginScope`）；`ProceedByLoggingAsync` 用 `Stopwatch` `:94` 计时、异常写入 `Log.Exceptions` `:102`、finally 回填 `ExecutionDuration` 并入 `Log.Actions` `:112`；`ShouldWriteAuditLogAsync` `:168` 通过则 `SaveAsync()` `:162` → `IAuditingStore`；实体变更由 `EntityHistoryHelper` 在 UoW 提交阶段收集。

---

## 5. 开发工作流程（端到端）

### 5.1 `abp new` 执行链路

`Volo.Abp.Cli/Program.cs`（dotnet 全局工具）→ `CommandSelector` → `Volo.Abp.Cli.Core/.../Commands/NewCommand.cs`：

1. `NamespaceHelper.NormalizeNamespace(args.Target)` + `ProjectNameValidator.Validate`；
2. 读取 `-t/--template`(默认 app)、`-u/--ui`、`-d/--database-provider`、`--theme`、`--tiered`、`-o/--output-folder`、`--connection-string`；
3. `GetProjectBuildArgsAsync` → `TemplateProjectBuilder.BuildAsync(projectArgs)`；
4. `SourceCodeStore.GetAsync(...)` 下载/解压模板 zip；
5. 构建管线 `TemplateProjectBuildPipelineBuilder.Build(context).Execute()`：
   `FileEntryListReadStep` → `ProjectReferenceReplaceStep`（替换占位符 `MyCompanyName.MyProjectName` 的引用）→ `TemplateCodeDeleteStep`（剔除 `//<TEMPLATE-REMOVE>` 条件块）→ `SolutionRenameStep`（`.slnx`）→ `LicenseCodeReplaceStep` → `DatabaseManagementSystemChangeStep`（按 dbms 写 `UseSqlServer()/UseNpgsql()`）→ `RemoveRootFolderStep` → `CreateProjectResultZipStep`；
6. 解压到 `OutputFolder` 后自动 `CreateInitialMigrationsAsync` → `RunInstallLibsForWebTemplateAsync`(`abp install-libs`) → `RunBundleInternalAsync`(`abp bundle`)。

### 5.2 解决方案分层与依赖

`templates/app/aspnet-core/src/`，依赖方向（`[DependsOn]` + csproj）：

```
Domain.Shared ← Domain ← Application.Contracts ← Application ← HttpApi
                    ↑                                    ↑          ↑
              EntityFrameworkCore ───────────────────────┘          │
                                                          HttpApi.Host (组合根)
```

| 层 | 职责 | 代表文件 |
|---|---|---|
| Domain.Shared | 常量/枚举/本地化/多租户常量 | `MyProjectNameDomainSharedModule.cs`、`MultiTenancy/MultiTenancyConsts.cs`、`Localization/MyProjectNameResource.cs` |
| Domain | 实体/领域服务/仓储接口 | `MyProjectNameDomainModule.cs`、`MyProjectNameConsts.cs`、`Data/MyProjectNameDbMigrationService.cs` |
| Application.Contracts | DTO/服务接口/权限常量 | `MyProjectNameApplicationContractsModule.cs`、`Permissions/MyProjectNamePermissions.cs` |
| Application | 服务实现/对象映射 | `MyProjectNameApplicationModule.cs`、`MyProjectNameApplicationMappers.cs`、`MyProjectNameAppService.cs` |
| EntityFrameworkCore | DbContext/仓储实现/迁移 | `MyProjectNameDbContext.cs`、`Migrations/` |
| HttpApi | 约定式控制器（自动生成） | Host 模块 `ConventionalControllers.Create(...)` |
| HttpApi.Host | 启动/组合根/配置 | `Program.cs`、`MyProjectNameHttpApiHostModule.cs` |

### 5.3 模块类典型写法（真实模板）

```csharp
[DependsOn(typeof(MyProjectNameHttpApiModule), typeof(AbpAutofacModule),
           typeof(MyProjectNameApplicationModule), typeof(MyProjectNameEntityFrameworkCoreModule),
           typeof(AbpAspNetCoreAuthenticationJwtBearerModule), typeof(AbpSwashbuckleModule))]
public class MyProjectNameHttpApiHostModule : AbpModule
{
    public override void ConfigureServices(ServiceConfigurationContext context)
    {
        Configure<AbpAspNetCoreMvcOptions>(o =>
            o.ConventionalControllers.Create(typeof(MyProjectNameApplicationModule).Assembly)); // 自动 API
        Configure<AbpDistributedCacheOptions>(o => o.KeyPrefix = "MyProjectName:");
    }
    public override void OnApplicationInitialization(ApplicationInitializationContext context) { /* 见 4.2 管道 */ }
}
```

EF 层：`context.Services.AddAbpDbContext<MyProjectNameDbContext>(o => o.AddDefaultRepositories(includeAllEntities: true))` + `Configure<AbpDbContextOptions>(o => o.UseSqlServer())`；DbContext 用 `[ConnectionStringName("Default")]`、`[ReplaceDbContext(typeof(IIdentityDbContext))]` 接入模块表。

### 5.4 日常开发循环（新增一个业务功能）

1. **领域**：`Domain/` 新增 `Book : FullAuditedAggregateRoot<Guid>`（自动获得 Id/审计/软删/并发戳/租户）；
2. **持久化**：`MyProjectNameDbContext` 加 `DbSet<Book>`，`OnModelCreating` 里 `b.ToTable("Books", MyProjectNameConsts.DbSchema); b.ConfigureByConvention();`；
3. **迁移**：`abp add-migration Added_Book`（内部 `EfCoreMigrationManager`）或 `dotnet ef migrations add ... -p src/...EntityFrameworkCore -s src/...HttpApi.Host`；
4. **契约**：`Application.Contracts` 写 `IBookAppService` + `BookDto`/`CreateUpdateBookDto`；`Application` 写 `BookAppService : ApplicationService, IBookAppService`（继承即自动获得 `IRepository<Book>`、`ObjectMapper`、`CurrentUser` 等）；
5. **自动 API**：无需写 Controller——`ConventionalControllers.Create` 把 `IBookAppService` 暴露为 `/api/app/book`；
6. **前端**：`abp generate-proxy`（`GenerateProxyCommand.cs`）生成 TS 客户端。
7. **引模块**：`abp add-module Volo.Blogging`（`AddModuleCommand` → `SolutionModuleAdder.AddAsync`，自动加 NuGet + `DependsOn` + 迁移）。

### 5.5 构建与运行命令

| 命令 | 作用 |
|---|---|
| `dotnet build` / `abp build` | 编译（`BuildCommand.cs`） |
| `dotnet run --project src/...HttpApi.Host` | 启动 API 宿主 |
| `abp install-libs` | 还原 `wwwroot/libs` 静态库 |
| `abp bundle` | 打包前端资源 |
| `abp dbmigrator` | 运行 `DbMigrator` 初始化数据库 |
| `abp new -t app -u mvc -d ef --tiered` | 生成分层解决方案 |
| `abp add-module X` / `--new` | 引用模块 / 新建模块 |

---

## 6. 一次请求的完整调用链（端到端串联）

```
① HTTP GET /api/app/book
② 中间件管道：RequestLocalization → Routing → Cors → Authentication
     → MultiTenancyMiddleware(解析租户,CurrentTenant.Change)
     → UseUnitOfWork(预留环境 UoW) → Authorization → Auditing
③ MVC 路由命中 "BookController"（AbpServiceConvention 动态生成，无实体文件）
④ 解析 BookAppService —— Castle 动态代理（EnableInterfaceInterceptors）
⑤ 拦截器链（AbpAsyncDeterminationInterceptor 包裹）：
     UnitOfWorkInterceptor → AuthorizationInterceptor → AuditingInterceptor → 校验
⑥ BookAppService.GetAsync(id)
⑦ Repository(EfCoreRepository).GetAsync(id) → GetDbSetAsync()
     → ToListAsync()（自动附加软删/多租户全局过滤器）
⑧ 返回 DTO → ObjectMapper 映射
⑨ AuditingInterceptor 记录 AuditLogActionInfo(耗时)
⑩ UoW.CompleteAsync：SaveChangesAsync → 发布本地/分布式事件 → CommitTransactionsAsync
⑪ 响应序列化返回
```

**关键设计模式**：
| 模式 | 体现 |
|---|---|
| 模块化 / 依赖倒置 | `AbpModule` + `[DependsOn]` + 拓扑排序 |
| 约定优于配置 | `IBookAppService → /api/app/book`；`DefaultConventionalRegistrar` |
| 装饰器 / 动态代理 | Castle + `IAbpInterceptor` 链 |
| 工作单元 / 环境上下文 | `AmbientUnitOfWork`（AsyncLocal）+ Child UoW |
| 仓储 | `IRepository<T>` 抽象，EF/Mongo 可换 |
| 租户上下文 | `ICurrentTenant.Change` + `IMultiTenant` 全局过滤器 |

**源码阅读推荐路径**：`AbpModule` → `ModuleLoader` → `AbpApplicationBase` → `DefaultConventionalRegistrar` → `AbpInterceptor`/`UnitOfWorkInterceptor` → `AbpServiceConvention` → `UnitOfWork.CompleteAsync` → `AbpDbContext.SaveChangesAsync`。

---

## 7. 备注

- 本报告基于 rel-10.6 源码实测，行号对应该版本文件；
- 版本信息见 `latest-versions.json`（10.5.0 为最新稳定，仓库基线 10.6）；
- 审计/授权/UoW 拦截器的**注册时机**都在"服务被动态代理时"（`OnRegistered` 钩子），而非模块初始化时——这是理解 ABP AOP 的关键。
