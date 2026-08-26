# 07 · 状态管理 (stores)

基于 Pinia 3 + `pinia-plugin-persistedstate` + `SecureLS`，提供 5 个内置 store；所有 store 在 `import.meta.hot` 环境下自动注册 HMR。

## 7.1 初始化：`packages/stores/src/setup.ts`

```ts
export async function initStores(app, options: { namespace: string }) {
  const { createPersistedState } = await import('pinia-plugin-persistedstate');
  pinia = createPinia();

  const ls = new SecureLSConstructor({
    encodingType: 'aes',
    encryptionSecret: import.meta.env.VITE_APP_STORE_SECURE_KEY,
    isCompression: true,
    metaKey: `${namespace}-secure-meta`,
  });

  pinia.use(createPersistedState({
    key: (storeKey) => `${namespace}-${storeKey}`,
    storage: import.meta.env.DEV
      ? localStorage
      : { getItem: (k) => ls.get(k), setItem: (k, v) => ls.set(k, v) },
  }));

  app.use(pinia);
  return pinia;
}

export function resetAllStores() {
  if (!pinia) return;
  for (const [_key, store] of (pinia as any)._s) {
    store.$reset();
  }
}
```

要点：
- 持久化 key 格式：`${namespace}-${storeId}`，例如 `yudao-vben-antd-prod-core-access`。
- 开发环境用 `localStorage`，生产用 `SecureLS`（AES 加密 + 压缩）。
- `resetAllStores()` 在登出时由 `useAuthStore.logout()` 调用。

## 7.2 `useAccessStore` — 权限核心

文件：`packages/stores/src/modules/access.ts`

| State | 类型 | 说明 |
| --- | --- | --- |
| `accessCodes` | `string[]` | 权限码集合（按钮权限） |
| `accessMenus` | `MenuRecordRaw[]` | 菜单元数据（转换后） |
| `accessRoutes` | `RouteRecordRaw[]` | 已生成的动态路由 |
| `accessToken` | `string \| null` | 登录令牌 |
| `refreshToken` | `string \| null` | 刷新令牌 |
| `isAccessChecked` | `boolean` | 是否已生过权限路由 |
| `isLockScreen` / `lockScreenPassword` | `boolean / string?` | 锁屏 |
| `loginExpired` | `boolean` | 登录过期（触发 modal 续登） |
| `tenantId` / `visitTenantId` | `number \| null` | 多租户编号 |

| Action | 作用 |
| --- | --- |
| `setAccessCodes`、`setAccessMenus`、`setAccessRoutes`、`setAccessToken`、`setRefreshToken`、`setIsAccessChecked`、`setLoginExpired`、`setTenantId`、`setVisitTenantId` | 通用 set |
| `getMenuByPath(path)` | 递归查找 menu |
| `lockScreen(password)` / `unlockScreen()` | 锁屏控制 |

**persist.pick**：`accessToken`、`refreshToken`、`accessCodes`、`tenantId`、`visitTenantId`、`isLockScreen`、`lockScreenPassword`。

## 7.3 `useUserStore` — 用户信息

```ts
state: () => ({ userInfo: null, userRoles: [] })
actions: { setUserInfo(info), setUserRoles(roles) }
```

- `userInfo` 类型 `BasicUserInfo`：昵称、头像、邮箱、角色等。
- 不参与持久化（每次启动重新拉 `get-permission-info`）。

## 7.4 `useAuthStore` — 登录/登出（项目级）

文件：`apps/web-antd/src/store/auth.ts`

```ts
export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();
  const loginLoading = ref(false);

  async function authLogin(type, params, onSuccess?) { ... }   // 4 种登录方式
  async function logout(redirect = true) { ... }
  async function fetchUserInfo() {
    const authPermissionInfo = await getAuthPermissionInfoApi();
    userStore.setUserInfo(authPermissionInfo.user);
    userStore.setUserRoles(authPermissionInfo.roles);
    accessStore.setAccessMenus(authPermissionInfo.menus);
    accessStore.setAccessCodes(authPermissionInfo.permissions);
    return authPermissionInfo;
  }

  return { $reset, authLogin, fetchUserInfo, loginLoading, logout };
});
```

支持的登录方式：

| `type` | 接口 | 入口参数 |
| --- | --- | --- |
| `'username'` | `loginApi` | username/password/captchaVerification |
| `'mobile'` | `smsLogin` | mobile/code |
| `'register'` | `register` | username/password/captchaVerification |
| `'social'` | `socialLogin` | type/code/state |

登录成功：`accessStore.setAccessToken` + `setRefreshToken` → `fetchUserInfo()` → 跳转 `homePath` 或 `defaultHomePath` → `notification.success`。

## 7.5 `useDictStore` — 字典缓存

```ts
actions: {
  getDictData(dictType, value),       // 取单个
  getDictOptions(dictType),           // 取整个数组
  setDictCache(dicts),
  setDictCacheByApi(api, params?, labelField?, valueField?), // 一次填充多个类型
}
```

**persist.pick**：`dictCache`。

## 7.6 `useTabbarStore` — 多标签页

文件：`packages/stores/src/modules/tabbar.ts`

State：
- `tabs: TabDefinition[]`
- `cachedTabs: Set<string>` — `updateCacheTabs()` 收集自 `meta.keepAlive`
- `cachedRoutes: Map<string, { component: VNode; key; route }>`
- `visitHistory: Stack<string>`（`@vben-core/shared/utils` 的 `createStack(true, 50)`）
- `excludeCachedTabs: Set<string>`
- `menuList: string[]` — 右键菜单顺序
- `dragEndIndex`、`renderRouteView`、`updateTime`

Getters：`affixTabs`、`getTabs`、`getCachedTabs`、`getExcludeCachedTabs`、`getMenuList`、`getCachedRoutes`。

主要 Actions：

| Action | 说明 |
| --- | --- |
| `addTab(routeTab)` | 添加 Tab，自动 dedup、支持最大数、affix |
| `closeTab(tab, router)` | 关闭 Tab（结合访问历史决定跳转目标） |
| `closeTabByKey(key, router)` | 同上，按 key |
| `closeLeftTabs / closeRightTabs / closeOtherTabs / closeAllTabs` | 批量关闭 |
| `pinTab / unpinTab / toggleTabPin` | 固定 Tab |
| `refresh(router | name)` | 刷新当前 Tab 或指定 Tab |
| `setTabTitle(tab, title)` | 标题支持 `string \| ComputedRef<string>` |
| `updateCacheTabs()` | 重新计算 `cachedTabs` |
| `addCachedRoute / removeCachedRoute` | 配合 `<keep-alive>` 缓存路由组件 |

**persist**：
- 自定义 `serializer.deserialize` 处理 `Stack` 反序列化重建。
- 存到 `sessionStorage`（避免 localStorage 长期占用）。

## 7.7 `useTimezoneStore` — 时区

`packages/stores/src/modules/timezone.ts`：保存当前用户时区，供组件展示。

## 7.8 全局使用约定

```ts
import { useAccessStore, useUserStore, useDictStore, useTabbarStore, useTimezoneStore } from '@vben/stores';

const accessStore = useAccessStore();
accessStore.accessToken;
accessStore.hasAccess('system:user:create');   // 由 useAccessStore 派生
```

跨模块共享请优先使用这 5 个内置 store；业务特有的 store 放在 `apps/<app>/src/store/*.ts` 中，并使用 `defineStore` 注册。