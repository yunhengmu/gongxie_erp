# 04 · 共享包 (packages)

`packages/` 内每个子目录都是一个独立的 npm 包，通过 `pnpm workspace` 关联，使用 `workspace:*` 协议在 `apps/*/package.json` 中引用。包名遵循两类约定：

- `@vben-core/*`：UI 框架无关的核心，未来可直接单独发布。
- `@vben/*`：与 Vue/Vben 强耦合的应用层包。

## 4.1 `@core/*` 核心

### `@core/base` — 基础 UI 与样式

| 子目录 | 作用 |
| --- | --- |
| `base/design` | 全局 CSS（global/nprogress/transition/ui）、Design Tokens（dark/default）、SCSS BEM 工具 |
| `base/icons` | `create-icon` 工具 + lucide 图标集合 |
| `base/shared` | 工具与基础设施（见下） |
| `base/typings` | 全局 TypeScript 类型（app/basic/helper/menu-record/tabs/vue-router） |

#### `@core/base/shared` 关键模块

| 路径 | 作用 |
| --- | --- |
| `cache/storage-manager.ts` | 多驱动（IndexedDB / LocalStorage / Memory）缓存管理器 |
| `cache/indexeddb-driver.ts`、`cache/local-storage-driver.ts`、`cache/memory-storage-driver.ts` | 三种持久化驱动 |
| `cache/types.ts` | 缓存接口定义 |
| `color/` | 颜色转换 / 调色板生成 |
| `constants/globals.ts`、`constants/vben.ts` | 全局常量 |
| `utils/` | `cn`、`date`、`diff`、`dom`、`download`、`encrypt`、`formatNumber`、`inference`、`letter`、`merge`、`nprogress`、`resources`、`stack`、`state-handler`、`time`、`to`、`tree`、`unique`、`update-css-variables`、`upload`、`url`、`util`、`uuid`、`window` 等 |
| `global-state.ts` | 跨应用共享状态 |
| `store.ts` | 通用 reactive store |

> 所有方法都带单元测试（`__tests__/*.test.ts`），通过 `vitest` + `happy-dom` 运行。

### `@core/composables`

| Hook | 作用 |
| --- | --- |
| `useSimpleLocale` | 当前语言 + 简单响应式 |
| `useIsMobile` | 判断当前是否移动端 |
| `useLayoutStyle` | 计算布局相关样式 |
| `useNamespace` | BEM 命名空间 |
| `usePriorityValue` | 多源默认值合并 |
| `useScrollLock` | 滚动锁定 |
| `useSortable` | 拖拽排序（基于 SortableJS） |

### `@core/preferences` — 框架无关偏好

- `preferences.ts`：`PreferenceManager` 单例类，提供 `init / save / clear / update / getCustom / getInitial` 等 API；底层基于 `@vben-core/shared/cache` 的 `StorageManager`，防抖写入 150ms。
- `config.ts`：默认 `Preferences` 结构（app/navigation/sidebar/tabbar/header/footer/theme/transition/widget/copyright/shortcutKeys/preferencesButton）。
- `types.ts`：`Preferences`、`PreferencesExtension`、`CustomPreferencesField`、`InitialOptions` 等。
- `update-css-variables.ts`：偏好变更 → CSS 变量更新（主题色、半径、字号）。
- `use-preferences.ts`：`usePreferences()` Composition API。

### `@core/ui-kit` — 与 Vue 解耦的 UI 核心（基于 reka-ui / shadcn 风格）

| 子包 | 内容 |
| --- | --- |
| `form-ui` | `VbenForm`、`useVbenForm`、`form-api`、`form-render`、`field-name`、`form-actions`、`form-field-array` |
| `layout-ui` | `VbenLayout`、侧边栏/顶栏/底部/内容/标签栏、`useLayout`、`useSidebarDrag` |
| `menu-ui` | `Menu`、`SubMenu`、`MenuItem`、`MenuBadge`、`useMenuContext`、`useMenuScroll` |
| `popup-ui` | `Modal`、`Drawer`、`Alert` 三类弹层封装 + 拖拽 Hook |
| `shadcn-ui` | 基于 reka-ui 的原子组件（Button/Card/Dialog/Dropdown/Form/TableAction 等 40+） |
| `tabs-ui` | `TabsView`、`Tabs`、`useTabsDrag`、`useTabsViewScroll` |

## 4.2 `packages/constants` — 业务常量

| 文件 | 用途 |
| --- | --- |
| `core.ts` | `LOGIN_PATH='/auth/login'`、`SUPPORT_LANGUAGES` 等 |
| `biz-system-enum.ts` | 系统管理枚举 |
| `biz-bpm-enum.ts` | 工作流枚举 |
| `biz-erp-enum.ts` | ERP 枚举 |
| `biz-infra-enum.ts` | 基础设施枚举 |
| `biz-iot-enum.ts` | IoT 枚举 |
| `biz-mall-enum.ts` | 商城枚举 |
| `biz-mes-enum.ts` | MES 枚举 |
| `biz-mp-enum.ts` | 公众号枚举 |
| `biz-pay-enum.ts` | 支付枚举 |
| `biz-wms-enum.ts` | WMS 枚举 |
| `biz-ai-enum.ts` | AI 枚举 |
| `dict-enum.ts` | 字典业务枚举 |

## 4.3 `packages/effects/*` — 应用层能力

### `effects/access` — 权限指令

- `accessible.ts`：`generateAccessible(mode, options)` 是核心；按 `mode` 调用 `generateRoutesByBackend` 或 `generateRoutesByFrontend`；最终把生成的 routes 注入根 `Root` 的 children，并返回 `accessibleMenus`、`accessibleRoutes`。
- `use-access.ts`：`useAccess()` 提供响应式权限判断（基于 `useAccessStore`）。
- `access-control.vue` + `directive.ts`：`v-access`、`v-access:code`、`v-access:role`、`v-access:any`、`v-access:not` 等指令。
- `index.ts`：注册 `registerAccessDirective(app)`。

### `effects/common-ui` — 通用业务组件

按域划分：

- **api-component**：通用 API 选择/级联/树选择组件，支持远程搜索、loadingSlot 等。
- **barcode**：条码渲染（基于 JsBarcode）。
- **captcha**：滑动 / 旋转 / 平移 / 点选 / 拼图验证码（多套实现）。
- **card**：statistic-card、comparison-card、summary-card。
- **col-page**：左右分栏页面容器。
- **content-wrap**：内容包装器。
- **count-to**：数字滚动动画。
- **cropper**：基于 CropperJS 的图片裁剪。
- **doc-alert**：文档跳转提示。
- **ellipsis-text**：省略号文本。
- **icon-picker**：图标选择器（Iconify）。
- **iframe**：内嵌 iframe 页面。
- **json-viewer**：JSON 美化展示。
- **loading**：`v-loading`、`v-spinning` 指令 + Loading 组件。
- **page**：通用列表页骨架。
- **resize**：缩放容器。
- **tippy`**：基于 TippyJS 的 Tooltip 指令。
- **tree**：树形控件（多选 / 拖拽 / 虚拟滚动）。

子模块还包含 **认证模板**（login/code-login/qrcode-login/register/third-party-login）、**Dashboard**（analysis/workbench）、**Fallback**（403/404/500/coming-soon/offline）、**Profile**（base/password/notification/security）。

### `effects/hooks` — Vue 组合式 Hook

| Hook | 作用 |
| --- | --- |
| `useAppConfig` | 读取 `import.meta.env` 中的 `VITE_*` 配置 |
| `useContentMaximize` | 内容区域最大化切换 |
| `useDesignTokens` | Antd/Element 设计令牌（颜色 / 字体） |
| `useDict` | 字典数据读取 |
| `useHoverToggle` | 悬停触发状态切换 |
| `usePagination` | 分页状态 |
| `useRefresh` | 当前页刷新（基于 `useTabbarStore.refresh`） |
| `useTabs` | 多 Tab 操作 |
| `useWatermark` | 页面水印 |

### `effects/layouts` — 通用布局组件

| 子组件 | 说明 |
| --- | --- |
| `basic/` | 主框架（header、sidebar、tabbar、content、footer、copyright、menu） |
| `authentication/` | 登录 / 注册布局 + toolbar |
| `iframe/` | iframe 容器与路由视图 |
| `route-cached/` | 路由级 KeepAlive |
| `widgets/` | 全局偏好 / 锁屏 / 通知 / 全局搜索 / 用户菜单 / 时区 / 主题切换 / 租户切换 / 更新检测等 |

### `effects/plugins` — 第三方插件封装

| 插件 | 能力 |
| --- | --- |
| `code-editor` | 基于 CodeMirror 的代码编辑器（含 css） |
| `echarts` | `EchartsUI` 组件 + `useEcharts` |
| `markmap` | 思维导图 |
| `motion` | 视图切换动画（`MotionPlugin`） |
| `tinyflow` | AI 工作流 |
| `tiptap` | 富文本（toolbar + preview + extensions） |
| `vxe-table` | 表格封装（`useVbenVxeGrid`、`useVxeToolbar`、`useViewedRow`、`createRequiredValidation`、`AsyncVxeTable/AsyncVxeColumn`） |

### `effects/request` — HTTP 客户端

- `request-client.ts`：`RequestClient` 类（基于 axios），封装：
  - `addRequestInterceptor`、`addResponseInterceptor`；
  - `download()`（FileDownloader）；
  - `upload()`（FileUploader）；
  - `requestSSE/postSSE`（SSE）；
  - `bindMethods(this)`。
- `preset-interceptors.ts`：`defaultResponseInterceptor`、`authenticateResponseInterceptor`、`errorMessageResponseInterceptor`。
- `modules/`：`interceptor`、`downloader`、`uploader`、`sse`（均带单测）。
- 默认配置：`{ headers: { 'Content-Type': 'application/json;charset=utf-8' }, responseReturn: 'raw', timeout: 30_000, paramsSerializer: 'repeat' }`。

## 4.4 `packages/stores` — Pinia 状态

- `setup.ts`：`initStores(app, { namespace })` 创建 Pinia、注册 `pinia-plugin-persistedstate`、挂载 SecureLS（生产环境加密持久化）。
- `resetAllStores()`：调用所有 store 的 `$reset`。
- 模块：
  - `access.ts` — `useAccessStore`（accessToken、refreshToken、accessCodes、accessMenus、accessRoutes、isAccessChecked、isLockScreen、loginExpired、tenantId、visitTenantId）。
  - `user.ts` — `useUserStore`（userInfo、userRoles）。
  - `dict.ts` — `useDictStore`（dictCache + 通过 API 填充）。
  - `tabbar.ts` — `useTabbarStore`（标签页增删改/固定/刷新/拖拽）。
  - `timezone.ts` — 时区。
- 每个 store 通过 `acceptHMRUpdate` 支持热更新。

## 4.5 `packages/preferences` — 应用层偏好

> 与 `@core/preferences` 是父子关系：`@core/preferences` 负责数据存储与变更逻辑，`@vben/preferences` 负责在 Vue 中暴露响应式 API。

- `src/index.ts`：导出 `initPreferences`、`usePreferences`、`preferences`、`defineOverridesPreferences`、`definePreferencesExtension`、`updatePreferences`、`clearPreferences` 等。
- `usePreferences()` 提供 `app/navigation/...` 等响应式结构，组件可直接 `preferences.app.xxx`。
- `definePreferencesExtension<T>`：业务自定义字段（带 i18n label / component 渲染）。
- `defineOverridesPreferences`：覆盖默认 `Preferences` 字段。

## 4.6 `packages/utils` — 顶层工具

```ts
export * from './cron';
export * from './helpers';
export * from './validator';
export * from '@vben-core/shared/cache';
export * from '@vben-core/shared/color';
export * from '@vben-core/shared/utils';
```

| 文件 | 内容 |
| --- | --- |
| `cron.ts` | Cron 表达式解析与生成 |
| `helpers.ts` | `findIndex`、`isUrl`、`mergeRouteModules`、`mapTree`、`traverseTreeValues`、`generateRoutesByBackend`、`generateRoutesByFrontend`、`generateMenus`、`convertServerMenuToRouteRecordStringComponent`、`cloneDeep` 等 |
| `validator.ts` | 通用校验函数 |
| `createApiEncrypt.ts` | AES 请求/响应加密封装（基于 `@vben-core/shared/utils/encrypt`） |

## 4.7 `packages/types` — 通用类型

| 路径 | 导出 |
| --- | --- |
| `index.ts` | `Recordable`、`AnyFunction`、`AppRouteRecordRaw`、`ComponentRecordType`、`BasicUserInfo`、`GenerateMenuAndRoutesOptions`、`UserInfo`、`AuthPermissionInfo`、`BasicLayoutProps`、`ThemeModeType`、`ContentCompactType`、`LocaleType` 等 |
| `user.ts` | `UserInfo`、`AuthPermissionInfo` |
| `global.d.ts` | 全局类型扩展（Vite Env、Vue SFC） |

## 4.8 `packages/styles` — 全局样式入口

- `index.ts`：默认样式导出。
- `antd/index.css`：Ant Design Vue 适配样式（覆盖 Tailwind Reset 等）。
- `ele/index.css`：Element Plus 适配样式。

每个 app 通过 `import '@vben/styles'; import '@vben/styles/antd';` 引入。

## 4.9 `packages/locales` — i18n 核心

- `i18n.ts`：基于 `vue-i18n` 的 `setupI18n(app, options)`。
  - 启动时读取 `defaultLocale`。
  - `loadLocalesMapFromDir` 通过 `import.meta.glob` 把 `./langs/**/*.json` 映射为按语言组织的字典。
  - `loadLocaleMessages(lang)`：合并 core + 业务 + 第三方组件库语言。
  - 通过 `useSimpleLocale` 同步 `dayjs.locale` 等。
- `typing.ts`：`Locale`、`ImportLocaleFn`、`LoadMessageFn`、`LocaleSetupOptions`、`SupportedLanguagesType`。
- `langs/`：`zh-CN/`、`en-US/` 下分别存放公共语言包（如 `common.json`、`ui.json`、`authentication.json`、`page.json`、`preferences.json` 等）。

## 4.10 `packages/icons` — 图标

- `index.ts`：导出 `IconifyIcon`（基于 `@iconify/vue`）。
- `svg/index.ts`、`svg/load.ts`：自定义 SVG 图标（`useSvgIcon` 注册）。
- 使用约定：业务图标直接 `<IconifyIcon icon="ant-design:xxx" />` 即可。