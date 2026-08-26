# 12 · 常用 Hooks & 工具

## 12.1 `@vben/hooks`

`packages/effects/hooks/src/`

| Hook | 描述 |
| --- | --- |
| `useAppConfig(env, isProd)` | 解析 `VITE_*` 配置，返回 `{ apiURL, uploadType, baseURL, port, defaultPassword, ... }` |
| `useContentMaximize()` | 当前页面内容区域最大化切换（写 `useTabbarStore`） |
| `useDesignTokens()` | 返回响应式 `tokens`（根据 `preferences.theme` 计算）；`useAntdDesignTokens` 额外提供 Antd Token 算法（dark/compact） |
| `useDict(type, immediate?)` | 读取 `useDictStore.dictCache`，未命中时调用 `dictStore.setDictCacheByApi` 加载 |
| `useHoverToggle()` | 封装 hover 进/出状态 |
| `usePagination(init?)` | `current, pageSize, total, setTotal, onChange, ...` |
| `useRefresh(target?)` | 包装 `useTabbarStore.refresh` |
| `useTabs()` | `addTab / closeTab / refresh / pinTab / closeOtherTabs / closeLeftTabs / closeRightTabs / closeAllTabs` |
| `useWatermark(options?)` | 创建页面水印（基于 `watermark-js-plus`） |

## 12.2 `@core/composables`

`packages/@core/composables/src/`

| Hook | 描述 |
| --- | --- |
| `useSimpleLocale()` | 当前语言 + `setSimpleLocale(locale)`；被 `i18n.ts` 用于触发响应式刷新 |
| `useIsMobile(width?)` | 监听媒体查询，返回 `isMobile: Ref<boolean>` |
| `useLayoutStyle()` | 根据 sidebar/header 高度计算 layout content padding |
| `useNamespace(component?)` | BEM 命名空间生成器 |
| `usePriorityValue(default, options)` | 多源默认值合并 |
| `useScrollLock()` | 锁定 body 滚动 |
| `useSortable(el, options)` | SortableJS 封装 |

## 12.3 `@vben/utils` 工具集

`packages/utils/src/`

```ts
export * from './cron';
export * from './helpers';
export * from './validator';
export * from '@vben-core/shared/cache';
export * from '@vben-core/shared/color';
export * from '@vben-core/shared/utils';
```

### helpers.ts 常用方法

| 方法 | 说明 |
| --- | --- |
| `mergeRouteModules(globResult)` | 合并多模块 default 导出的 vue-router 路由 |
| `traverseTreeValues(tree, fn)` | 树形遍历收集值 |
| `mapTree(tree, fn)` | 树形转换 |
| `cloneDeep(obj)` | 深拷贝 |
| `convertServerMenuToRouteRecordStringComponent(menus)` | 后端 → 前端路由 |
| `generateMenus(routes, router)` | 从路由生成菜单树 |
| `generateRoutesByFrontend(options)` / `generateRoutesByBackend(options)` | 路由生成 |
| `createApiEncrypt(env)` | 创建 AES 加解密器 |
| `isUrl(path)` | 判断路径是否为 URL |
| `openWindow(url, opts)` | `window.open` 包装 |

### `@vben-core/shared/utils` 工具

| 分类 | 方法 |
| --- | --- |
| 通用 | `cn`、`bindMethods`、`merge`、`unique`、`to`、`noop`、`assert` |
| 日期 | `formatDate`、`formatDateTime`、`formatPast`、`formatPast2`、`relativeTime` |
| 树 | `treeToMap`、`mapTree`、`traverseTree`、`findTreeNode` |
| 栈 | `createStack(unique, maxSize)` |
| DOM | `copyToClipboard`、`downloadFile`、`formatFileSize`、`imageToBase64` |
| 加密 | `AES encrypt/decrypt`、`MD5`、`SHA1` |
| URL | `qs.stringify`（自动 brakets）、`URL` 解析 |
| 文件 | `uploadFile`、`downloadBlob` |
| 状态机 | `state-handler` |
| 资源 | `loadScript`、`loadStyle`、`loadImage` |
| 校验 | `isMobile`、`isEmail`、`isURL`、`isIdCard`、`isExternal`、`isFunction`、`isString`、`isEmpty` |
| 时间 | `setTimeout`、`debounce`、`throttle`（基于 VueUse） |
| 进度条 | `startProgress`、`stopProgress` |
| UUID | `nanoid`、`uuid` |
| 窗口 | `getViewport`、`isMobileViewport` |

### `@vben-core/shared/cache`

```ts
import { StorageManager } from '@vben-core/shared/cache';
const storage = new StorageManager();
await storage.setItem('k', { foo: 1 }, { expire: 60_000 });
await storage.getItem('k');   // { foo: 1, expire: ... }
await storage.removeItem('k');
```

支持驱动：
- `IndexedDBDriver`（默认）
- `LocalStorageDriver`
- `MemoryStorageDriver`

### `@vben-core/shared/color`

- `convert.ts`：HEX、RGB、HSL 互转。
- `generator.ts`：基于主色生成调色板（lighten/darken）。
- `color.ts`：常用色操作。

### cron.ts

```ts
import { CronExpressionParser, CronExpression } from '@vben/utils';
const interval = CronExpressionParser.parse('0 0 1 * *', { currentDate: new Date() });
interval.next().toDate();
```

> 用于「定时任务」模块展示 cron 下一次执行时间。

### validator.ts

```ts
import { isEmail, isMobile, isURL, isIdCard } from '@vben/utils';
```

## 12.4 应用级 utils 示例

```ts
// apps/web-antd/src/utils/routerHelper.ts
export const findIndex = <T>(ary: Array<T>, fn: (item: T, index: number, array: Array<T>) => boolean) => ...;

// apps/web-antd/src/utils/rangePickerProps.ts
export const rangePickerProps: RangePickerProps = { showTime: true, format: 'YYYY-MM-DD HH:mm:ss' };

// apps/web-antd/src/utils/useUpload.ts
export function useUpload(options) { ... }
```

## 12.5 推荐用法速查

| 场景 | 推荐 |
| --- | --- |
| 字典读取 | `useDict('user_status')` |
| 分页 | `const [page, setPage] = usePagination();` |
| 当前 Tab 刷新 | `const refresh = useRefresh(); refresh();` |
| 路由跳转 | `useTabs().closeAllTabs(router)` |
| 防抖 | `useDebounceFn(fn, 200)`（VueUse） |
| 拷贝 | `copyToClipboard(text)` |
| 时间格式 | `formatDateTime(date, 'YYYY-MM-DD HH:mm:ss')` |
| AES | `createApiEncrypt(import.meta.env).encryptRequest(json)` |