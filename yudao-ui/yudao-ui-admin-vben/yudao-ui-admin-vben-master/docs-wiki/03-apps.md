# 03 · 应用层 (apps)

仓库在 `apps/` 下提供 5 套独立应用，每个应用都是一个完整的 Vite + Vue3 工程，结构与启动流程一致，仅 UI 框架不同。

## 3.1 应用清单

| 应用 | UI 框架 | 端口（开发） | 入口 | 备注 |
| --- | --- | --- | --- | --- |
| `web-antd` | Ant Design Vue 4 | 5666 | `apps/web-antd/src/main.ts` | 芋道默认推荐 |
| `web-antdv-next` | antdv-next | — | `apps/web-antdv-next/src/main.ts` | 新版 antd for Vue 实验性 |
| `web-ele` | Element Plus 2 | 5666 | `apps/web-ele/src/main.ts` | 与 ruoyi-vue-pro 经典版对应 |
| `web-naive` | Naive UI 2 | — | `apps/web-naive/src/main.ts` | TypeScript 体验佳 |
| `web-tdesign` | TDesign 1 | — | `apps/web-tdesign/src/main.ts` | 腾讯出品的轻量 UI |

> 说明：除 `web-antd`/`web-ele` 在 `.env.development` 中显式指定 `VITE_PORT=5666`，其他应用若未配置则走 Vite 默认 5173。

## 3.2 通用目录约定（每个 app）

```text
apps/<web-xxx>/
├── index.html                 # Vite HTML 模板
├── vite.config.ts             # 基于 @vben/vite-config
├── tsconfig.json              # 继承 @vben/tsconfig/web-app
├── package.json
├── .env                       # 全局默认环境变量
├── .env.development           # 开发环境
├── .env.production            # 生产环境
├── .env.analyze               # analyze 模式
└── src/
    ├── adapter/               # 组件/表单/表格适配
    ├── api/                   # 接口层（按业务模块拆）
    ├── assets/                # 图片、SVG
    ├── components/            # 项目级业务组件
    ├── layouts/               # 基础布局
    ├── locales/               # i18n
    ├── router/                # 路由 + 守卫
    ├── store/                 # 项目级 store
    ├── utils/                 # 项目级工具
    ├── views/                 # 页面（_core/* + 业务模块）
    ├── app.vue                # 根组件
    ├── bootstrap.ts           # 装配入口
    ├── main.ts                # 启动入口（preferences → bootstrap）
    └── preferences.ts         # 应用级偏好覆盖
```

## 3.3 启动入口对比

### `apps/web-antd/src/main.ts`

```ts
import { initPreferences } from '@vben/preferences';
import { unmountGlobalLoading } from '@vben/utils';
import { overridesPreferences, preferencesExtension } from './preferences';

async function initApplication() {
  const env = import.meta.env.PROD ? 'prod' : 'dev';
  const appVersion = import.meta.env.VITE_APP_VERSION;
  const namespace = `${import.meta.env.VITE_APP_NAMESPACE}-${appVersion}-${env}`;

  await initPreferences({
    extension: preferencesExtension,
    namespace,
    overrides: overridesPreferences,
  });

  const { bootstrap } = await import('./bootstrap');
  await bootstrap(namespace);
  unmountGlobalLoading();
}
initApplication();
```

### `apps/web-antd/src/bootstrap.ts` 关键点

1. `await initComponentAdapter()` → `adapter/component/index.ts` 全局注册 Antd 组件、`globalShareState.setComponents`、`globalShareState.defineMessage`。
2. `await initSetupVbenForm()` → `adapter/form.ts` 中 `setupVbenForm(...)` 注册表单校验规则、`baseModelPropName`、`modelPropNameMap`。
3. `createApp(App)` → 注册 `VueDOMPurifyHTML`、`registerLoadingDirective`、`setupI18n(app)`、`initStores(app, { namespace })`、`registerAccessDirective`、`initTippy`、`use(router)`、`setupFormCreate`、`MotionPlugin`、`useTitle`。
4. 最后 `app.mount('#app')`。

> 其他 4 个 app 的 `bootstrap.ts` 流程几乎一致，仅在 UI 库专属指令（loading/animation）名称、Tippy 注册、Form 适配器上有所差异。

## 3.4 关键差异

| 维度 | web-antd | web-ele | web-naive | web-tdesign | web-antdv-next |
| --- | --- | --- | --- | --- | --- |
| 组件库 | ant-design-vue | element-plus | naive-ui | tdesign-vue-next | antdv-next |
| 消息提示 | `message` / `notification`（antd） | `ElMessage` / `ElNotification` | `useMessage()` | `MessagePlugin` | antd 系列 |
| 表单 | vee-validate + zod + vben-form | 同上 | 同上 | 同上 | 同上 |
| 表格 | vxe-table 适配（CellImage/Tag/Dict/Switch/Operation） | 同上 | 同上 | 同上 | 同上 |
| 表单设计器 | @form-create/ant-design-vue | @form-create/element-ui | @form-create/naive-ui | （未集成） | @form-create/antdv-next |
| BPMN | bpmn-js 等 | 同上 | — | — | 同上 |
| 富文本 | Tinymce | Tinymce | — | Tinymce | Tinymce |
| IM RTC | livekit-client + benz-amr-recorder | 同上 | — | — | 同上 |
| 视频播放 | videojs | videojs | — | — | videojs |
| 唯一依赖 | + tinymce、tyme4ts、video.js | + unplugin-element-plus | （最精简） | + es-toolkit | 同 web-antd |

> `web-naive` 和 `web-tdesign` 业务页面相对精简（仅保留通用业务），重业务功能（CRM、Mall、BPM、IM 等）只在 `web-antd`、`web-ele` 中完整实现。

## 3.5 环境变量参考（`apps/web-antd/.env*`）

### 公共配置 `.env`

```ini
VITE_APP_TITLE=芋道管理系统
VITE_APP_NAMESPACE=yudao-vben-antd
VITE_APP_STORE_SECURE_KEY=please-replace-me-with-your-own-key
VITE_NITRO_MOCK=false
VITE_APP_TENANT_ENABLE=true
VITE_APP_CAPTCHA_ENABLE=false
VITE_APP_DOCALERT_ENABLE=true
VITE_APP_BAIDU_CODE=e98f2eab6ceb8688bc6d8fc5332ff093
VITE_GOVIEW_URL=http://127.0.0.1:3000
VITE_APP_API_ENCRYPT_ENABLE=true
VITE_APP_API_ENCRYPT_HEADER=X-Api-Encrypt
VITE_APP_API_ENCRYPT_ALGORITHM=AES
VITE_APP_API_ENCRYPT_REQUEST_KEY=52549111389893486934626385991395
VITE_APP_API_ENCRYPT_RESPONSE_KEY=96103715984234343991809655248883
VITE_BAIDU_MAP_KEY=Y2aJXiswwPxy6mwFs1z9c7U5gwX9WfUN
```

### 开发 `.env.development`

```ini
VITE_PORT=5666
VITE_BASE=/
VITE_BASE_URL=http://127.0.0.1:48080
VITE_GLOB_API_URL=/admin-api
VITE_UPLOAD_TYPE=server
VITE_DEVTOOLS=false
VITE_INJECT_APP_LOADING=true
VITE_APP_DEFAULT_USERNAME=admin
VITE_APP_DEFAULT_PASSWORD=admin123
```

### 生产 `.env.production`

```ini
VITE_BASE=/
VITE_BASE_URL=http://127.0.0.1:48080
VITE_GLOB_API_URL=http://127.0.0.1:48080/admin-api
VITE_UPLOAD_TYPE=server
VITE_COMPRESS=none            # none | brotli | gzip
VITE_PWA=false
VITE_ROUTER_HISTORY=hash       # hash | web
VITE_INJECT_APP_LOADING=true
VITE_ARCHIVER=true             # 生成 dist.zip
VITE_APP_CAPTCHA_ENABLE=true
```

## 3.6 Vite 配置（`apps/web-antd/vite.config.ts`）

```ts
import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      server: {
        allowedHosts: true,
        proxy: {
          '/admin-api': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/admin-api/, ''),
            target: 'http://localhost:48080/admin-api',
            ws: true,
          },
        },
      },
    },
  };
});
```

`application: {}` 由 `@vben/vite-config` 在内部基于 `vite-plugin-vue`、vue-i18n unplugin、Tailwind、vxe-table 等扩展插件做统一装配。

## 3.7 路由结构示例（`apps/web-antd/src/router/routes/index.ts`）

```ts
const dynamicRouteFiles = import.meta.glob('./modules/**/*.ts', { eager: true });
const dynamicRoutes: RouteRecordRaw[] = mergeRouteModules(dynamicRouteFiles);

const routes: RouteRecordRaw[] = [
  ...coreRoutes,       // login / 404 / 业务 404 fallback
  ...externalRoutes,
  fallbackNotFoundRoute,
];

const coreRouteNames = traverseTreeValues(coreRoutes, (route) => route.name);
const accessRoutes = [...dynamicRoutes, ...staticRoutes];
```

- `coreRoutes` 写在 `./core.ts`：`/`、`/auth/login`、`/auth/code-login`、`/auth/qrcode-login`、`/auth/forget-password`、`/auth/register`、`/auth/social-login`、`/auth/sso-login`。
- `fallbackNotFoundRoute` 使用 `/:path(.*)*` 兜底。
- `accessRoutes` 为需要权限校验的动态路由，会通过 `@vben/access` 的 `generateAccessible` 转换后注入根路由 `Root` 的 children。