# 06 · 路由 & 权限系统

Vben 的路由与权限由 `@vben/router` 模式 + `@vben/access` 协同完成。芋道使用 **后端路由模式**（`accessMode: 'backend'`），菜单、权限、后端可访问路由均来自 `/system/auth/get-permission-info`。

## 6.1 文件结构

```text
apps/web-antd/src/router/
├── index.ts            # createRouter + guard
├── guard.ts            # 全局前置/后置守卫
├── access.ts           # generateAccess 适配（pageMap / layoutMap）
├── tongji.ts           # 百度统计
└── routes/
    ├── core.ts         # 登录/注册/404 公共路由
    ├── index.ts        # 聚合 core + 动态路由 + 兜底路由
    └── modules/        # 业务动态路由（按业务模块拆 *.ts）
```

## 6.2 `routes/core.ts`

固定写死，不参与权限：

- `Root`：`/`（BasicLayout），redirect 到 `preferences.app.defaultHomePath`。
- `Authentication`：`/auth` 父路由，包含子页：
  - `Login` `/auth/login`
  - `CodeLogin` `/auth/code-login`
  - `QrCodeLogin` `/auth/qrcode-login`
  - `ForgetPassword` `/auth/forget-password`
  - `Register` `/auth/register`
  - `SocialLogin` `/auth/social-login`
  - `SSOLogin` `/auth/sso-login`
- `fallbackNotFoundRoute`：`/:path(.*)*` 404。

## 6.3 `routes/index.ts`

```ts
const dynamicRouteFiles = import.meta.glob('./modules/**/*.ts', { eager: true });
const dynamicRoutes = mergeRouteModules(dynamicRouteFiles);

const routes = [...coreRoutes, ...externalRoutes, fallbackNotFoundRoute];
const coreRouteNames = traverseTreeValues(coreRoutes, (route) => route.name);
const accessRoutes = [...dynamicRoutes, ...staticRoutes];

// componentKeys 用于动态 import 解析（避免打包丢失）
const componentKeys = Object.keys(import.meta.glob('../../views/**/*.vue'))
  .filter((item) => !item.includes('/modules/'))
  .map((v) => v.replace('../../views/', '/').replace(/\.vue$/, ''));
```

> `mergeRouteModules` 是 `@vben/utils` 提供的方法，自动合并 glob 返回的多模块 default 导出。

## 6.4 `router/index.ts`

```ts
const router = createRouter({
  history: import.meta.env.VITE_ROUTER_HISTORY === 'hash'
    ? createWebHashHistory(import.meta.env.VITE_BASE)
    : createWebHistory(import.meta.env.VITE_BASE),
  routes,
  scrollBehavior: (to, _from, savedPosition) => savedPosition ?? (to.hash ? { behavior: 'smooth', el: to.hash } : { left: 0, top: 0 }),
});

createRouterGuard(router);
setupBaiduTongJi(router);

export { resetRoutes, router };
```

`resetRoutes()` 调用 `resetStaticRoutes(router, routes)` 用于登出后清理。

## 6.5 `router/guard.ts`

### `setupCommonGuard(router)`

- `beforeEach`：记录 `loadedPaths`，按 `preferences.transition.progress` 开关 `startProgress`。
- `afterEach`：把 `to.path` 加入 `loadedPaths`，`stopProgress`。

### `setupAccessGuard(router)` —— 核心权限流程

1. **`coreRouteNames.includes(to.name)`**：直接放行；若是 `LOGIN_PATH` 且已登录则跳到 `userInfo.homePath`。
2. **未登录**：
   - `to.meta.ignoreAccess` 直接放行。
   - 否则重定向到 `LOGIN_PATH`，带 `redirect` 参数（`encodeURIComponent(to.fullPath)`），`replace: true`。
3. **已登录但未生成动态路由**（`accessStore.isAccessChecked === false`）：
   - 调用 `dictStore.setDictCacheByApi(getSimpleDictDataList)` 异步拉字典。
   - 调用 `authStore.fetchUserInfo()` 获取 `user / roles / permissions / menus`。
   - 调用 `generateAccess({ roles, router, routes: accessRoutes })` 生成菜单 + 路由。
   - 把生成的 `accessibleMenus`、`accessibleRoutes` 写入 `accessStore`，置 `isAccessChecked=true`、`userRoles` 写入 `userStore`。
   - 最终 `replace` 到原 `to.fullPath`（或 `userInfo.homePath`）。

### `createRouterGuard(router)`

依次注册 `setupCommonGuard` + `setupAccessGuard`。

## 6.6 `router/access.ts`

```ts
async function generateAccess(options: GenerateMenuAndRoutesOptions) {
  const pageMap: ComponentRecordType = import.meta.glob('../views/**/*.vue');
  const layoutMap: ComponentRecordType = { BasicLayout, IFrameView };

  return generateAccessible(preferences.app.accessMode, {
    ...options,
    fetchMenuListAsync: async () => {
      // 把后端 AppRouteRecordRaw[] 转成前端 component 字符串
      return convertServerMenuToRouteRecordStringComponent(accessStore.accessMenus as AppRouteRecordRaw[]);
    },
    forbiddenComponent: () => import('#/views/_core/fallback/forbidden.vue'),
    layoutMap,
    pageMap,
  });
}
```

## 6.7 `@vben/access` 的 `generateAccessible`

文件：`packages/effects/access/src/accessible.ts`

```ts
async function generateAccessible(mode, options) {
  options.routes = cloneDeep(options.routes);
  const accessibleRoutes = await generateRoutes(mode, options);
  const root = router.getRoutes().find((r) => r.path === '/');

  // 把生成的 routes 注入到 Root 的 children（保留 noBasicLayout 的不动）
  accessibleRoutes.forEach((route) => {
    if (root && !route.meta?.noBasicLayout) {
      if (route.children?.length) delete route.component;
      if (root.children?.some(c => c.name === route.name)) {
        const idx = root.children.findIndex(c => c.name === route.name);
        root.children[idx] = route;     // 修复 homePath 二级目录 404
      } else {
        root.children.push(route);
      }
    } else {
      router.addRoute(route);
    }
  });

  if (root) {
    if (root.name) router.removeRoute(root.name);
    router.addRoute(root);              // 重新挂载 Root
  }

  const accessibleMenus = generateMenus(accessibleRoutes, options.router);
  return { accessibleMenus, accessibleRoutes };
}
```

### `generateRoutes(mode, options)`

| mode | 流程 |
| --- | --- |
| `frontend` | `options.fetchMenuListAsync?.()` → `generateRoutesByFrontend(...)` → `generateMenus` |
| `backend` | 直接使用后端返回的菜单转 routes（`convertServerMenuToRouteRecordStringComponent`），不再走前端过滤 |

### 关键工具

- `convertServerMenuToRouteRecordStringComponent(menus)`：把后端返回的 `AppRouteRecordRaw[]`（含 path/component 字符串）转成 vue-router 结构。
- `generateRoutesByFrontend(...)`：根据角色过滤 + 排序。
- `generateRoutesByBackend(...)`：将后端树直接转换为 vue-router routes。
- `mapTree`、`cloneDeep`、`isFunction`、`isString`：来自 `@vben/utils`。

## 6.8 权限指令 `v-access`

由 `packages/effects/access` 提供，5 种使用方式：

```vue
<!-- 任意权限 -->
<button v-access="['system:user:create']">新建</button>

<!-- 全部满足 -->
<a v-access:code="['system:user:export']">导出</a>

<!-- 任一满足 -->
<a v-access:any="['system:user:edit','system:user:admin']">编辑</a>

<!-- 角色 -->
<a v-access:role="['super_admin']">设置</a>

<!-- 取反 -->
<a v-access:not="['system:user:delete']">隐藏</a>
```

实现：`directive.ts` 注册全局指令；逻辑由 `use-access.ts` 提供响应式的 `hasAccessByCodes / hasAccessByRoles` 等。

## 6.9 菜单与按钮的运行时联动

- `useAccessStore().getMenuByPath(path)`：根据 `accessMenus` 找到菜单元数据（用于面包屑、Tab 标题）。
- 菜单渲染：`@vben/layouts` 内置的 `Menu` 组件遍历 `accessStore.accessMenus`，递归渲染 `SubMenu`/`MenuItem`。
- 按钮：`v-access` 指令读取 `accessStore.accessCodes` 决定 `display:none` 或直接删除节点。

## 6.10 路由级缓存

- `useTabbarStore.updateCacheTabs()` 收集 `meta.keepAlive=true` 的路由名，生成 `cachedTabs`。
- `packages/effects/layouts/route-cached/` 提供 `<route-cached-view />`、`<route-cached-page />`。
- 与 `vue` 的 `<KeepAlive>` 配合实现 Tab 切回时保留滚动位置、表单内容。