# 02 · 整体架构

## 2.1 分层模型

```text
┌─────────────────────────────────────────────────────────────────────┐
│  apps/* (web-antd / web-ele / web-naive / web-tdesign / web-antdv)  │   业务视图
├─────────────────────────────────────────────────────────────────────┤
│  packages/effects/* (common-ui / hooks / layouts / plugins / request / access)   │  应用层能力
├─────────────────────────────────────────────────────────────────────┤
│  packages/stores  packages/preferences  packages/constants          │  跨应用状态/配置
├─────────────────────────────────────────────────────────────────────┤
│  packages/@core/* (base / composables / preferences / typings / ui-kit)           │  框架无关核心
├─────────────────────────────────────────────────────────────────────┤
│  packages/utils  packages/types  packages/locales  packages/styles  │  纯工具/类型
├─────────────────────────────────────────────────────────────────────┤
│  internal/* (vite-config / tsconfig / node-utils)                   │  工具链
└─────────────────────────────────────────────────────────────────────┘
```

- **`@core/*` 完全 UI 无关**：可以独立于 Vue 单独打包发布（`tsdown.config.ts`），供任何框架使用。
- **`effects/*` 提供应用层横切能力**：封装组件、Hooks、布局、第三方插件、请求、权限指令。
- **`apps/*` 是壳**：选择 UI 库、装配 adapter、写业务页面。
- **`stores` 与 `preferences`** 独立于具体 UI，被多端共用。

## 2.2 Monorepo 工作区（pnpm + Turborepo）

- `pnpm-workspace.yaml` 定义 9 个 glob（`apps/*`、`packages/*`、`packages/@core/*`、`packages/@core/base/*`、`packages/@core/ui-kit/*`、`packages/effects/*`、`internal/*`、`internal/lint-configs/*`、`scripts/*`、`docs`、`playground`）。
- `catalog` 协议集中管理依赖版本：所有第三方包都引用 `catalog:`，实际版本号写在 `pnpm-workspace.yaml` 末尾，避免重复维护。
- `publicHoistPattern` 把 lefthook、eslint、oxfmt、oxlint、stylelint、postcss 等工具统一提升到根 `node_modules`。
- `strictPeerDependencies: false`、`autoInstallPeers: true` 简化了跨包 peer 解析。

### Turbo 任务管线（`turbo.json`）

| 任务 | 行为 | 输出 |
| --- | --- | --- |
| `build` | 依赖前置包 build 完成 | `dist/**`, `dist.zip`, `.vitepress/dist/**` |
| `preview` | 依赖前置包 build 完成 | `dist/**` |
| `build:analyze` | 依赖前置包 build 完成 | `dist/**` |
| `dev` | 无依赖，persistent，禁用缓存 | 实时输出 |
| `typecheck` | 类型检查 | — |
| `test:e2e` | Playwright E2E | — |

`globalDependencies` 中包含 `pnpm-lock.yaml`、`.env.*local`、所有 `tsconfig*.json`、`internal/*`、`scripts/*` 的源码。

## 2.3 应用启动流水线（以 `web-antd` 为例）

```text
index.html → src/main.ts
   └─ initPreferences()                  # 加载偏好
        └─ import('./bootstrap') → bootstrap(namespace)
              ├─ await initComponentAdapter()        # adapter/component
              ├─ await initSetupVbenForm()           # adapter/form
              ├─ createApp(App)
              ├─ app.use(VueDOMPurifyHTML)
              ├─ registerLoadingDirective(app)
              ├─ await setupI18n(app)                # locales/index.ts
              ├─ await initStores(app, { namespace })# stores/setup.ts (Pinia + SecureLS)
              ├─ registerAccessDirective(app)        # effects/access
              ├─ initTippy(app)
              ├─ app.use(router)                     # router/index.ts
              ├─ setupFormCreate(app)                # 表单设计器
              ├─ app.use(MotionPlugin)
              ├─ watchEffect(标题)                   # vueuse/useTitle
              └─ app.mount('#app')
   └─ unmountGlobalLoading()
```

详细说明见 [10 · 布局 & 组件适配](./10-layout-and-adapter.md) 与 [06 · 路由 & 权限系统](./06-router-and-access.md)。

## 2.4 模块依赖图（简化）

```text
                      apps/web-antd (UI=Antd)
                              │
        ┌─────────┬────────────┼─────────────┬──────────┐
        ▼         ▼            ▼             ▼          ▼
    @vben/    @vben/        @vben/        @vben/    业务 store
    access    common-ui     stores        request   (auth)
        │         │            │             │
        │         │            │             │
        └────┬────┴─────┬──────┴─────┬───────┘
             ▼          ▼            ▼
        @vben/     @vben/       @vben/
        layouts    plugins      preferences
             │          │            │
             ▼          ▼            ▼
       @vben-core/  @vben-core/   @vben-core/
       ui-kit       composables   preferences
             │          │            │
             ▼          ▼            ▼
        @vben-core/typings + @vben-core/shared (utils / cache / color)
             │
             ▼
       @vben/utils (顶层包装 + helpers)
```

## 2.5 关键约定

1. **包名规范**：所有业务包 `@vben/*`（npm scope）；核心包 `@vben-core/*`。
2. **版本同步**：所有包版本号与根 `package.json` 一致（5.7.0），通过 `changesets` 管理。
3. **入口别名**：每个 app 在 `package.json` 配置 `"imports": { "#/*": "./src/*" }`，使用 `#/router/...` 等路径 import。
4. **构建产物**：所有 app 使用 `tsdown`/`Vite` 双重构建（包用 tsdown 产出 ESM；应用用 Vite 产出静态资源）。
5. **依赖唯一性**：所有 Vue/Pinia/eslint 等基础库走 `catalog` + `overrides`，避免重复装包。

## 2.6 与后端协作约定

- 默认 API 前缀：`/admin-api`
- 默认端口：开发模式 `5666`，后端 `48080`（可在 `.env.development` 修改）
- 鉴权：`Authorization: Bearer <accessToken>`，附 `Accept-Language`、`tenant-id`、`visit-tenant-id` 头
- 返回结构：`{ code, data, msg }`，`code === 0` 表示成功（见 [09 · 请求层](./09-request.md)）
- 登录响应：`{ accessToken, refreshToken, userId, expiresTime }`
- 权限信息：`get-permission-info` 返回 `{ user, roles, permissions, menus }`