# 08 · 偏好设置 (preferences)

偏好系统分为两层：

| 层 | 包 | 职责 |
| --- | --- | --- |
| 框架无关核心 | `@core/preferences` | 数据存取、变更通知、CSS 变量更新 |
| Vue 应用层 | `@vben/preferences` | 在 Vue 中暴露响应式 API + 启动初始化 |

## 8.1 `@core/preferences` 内部结构

`packages/@core/preferences/src/`

| 文件 | 内容 |
| --- | --- |
| `preferences.ts` | `PreferenceManager` 类（单例 `preferencesManager`） |
| `config.ts` | `defaultPreferences` 默认值 |
| `types.ts` | `Preferences` / `PreferencesExtension` / `CustomPreferencesField` / `InitialOptions` |
| `use-preferences.ts` | `usePreferences()` Vue hook |
| `update-css-variables.ts` | 把主题/半径/字号写到 `:root` CSS 变量 |
| `constants.ts` | 存储 key 等 |

### `PreferenceManager` 关键 API

| 方法 | 说明 |
| --- | --- |
| `init(initialOptions)` | 异步加载持久化偏好 + 合并默认值 + 应用扩展 |
| `getPreferences()` / `setPreferences(p)` | 全量读写 |
| `updatePreferences(path, value)` | 按路径更新，自动持久化（debounce 150ms） |
| `resetPreferences()` | 恢复默认 |
| `clearCache()` | 清空缓存（登出场景） |
| `getCustomPreferences<T>()` | 扩展字段只读视图 |
| `getInitialPreferences()` / `getInitialCustomPreferences()` | 获取首次初始化快照（用于还原） |

### `STORAGE_KEYS`

```ts
{
  MAIN: 'preferences',
  THEME: 'preferences-theme',
  LOCALE: 'preferences-locale',
  CUSTOM: 'preferences-custom',
}
```

均通过 `StorageManager`（`@vben-core/shared/cache`）写入，运行时可在 LocalStorage / IndexedDB / Memory 间切换。

### CSS 变量更新

```ts
// update-css-variables.ts
updateCSSVariables({
  'primary-color': preferences.theme.colorPrimary,
  'radius': preferences.theme.radius,
  'font-size': preferences.theme.fontSize,
  ...
});
```

> 偏好变化 → `updatePreferences()` → 防抖 → 写入缓存 + 通知订阅者 + 触发 CSS 变量更新 → 整个应用实时切换主题。

## 8.2 `@vben/preferences` 暴露的 API

`packages/preferences/src/index.ts`

| API | 说明 |
| --- | --- |
| `initPreferences({ namespace, overrides, extension })` | 启动入口，传入应用名 + overrides + 扩展字段 |
| `preferences` | 默认导出的响应式 `Preferences` 对象（Proxy），组件内可直接 `preferences.app.xxx` |
| `usePreferences()` | Composition API（响应式 + 工具方法） |
| `updatePreferences(path, value)` | 修改（自动持久化） |
| `resetPreferences()` | 恢复 |
| `clearPreferences()` | 清空缓存 |
| `defineOverridesPreferences(obj)` | 类型辅助，包裹 overrides |
| `definePreferencesExtension<T>(obj)` | 类型辅助，包裹扩展字段 |
| `setUpdatePreferencesHandler(fn)` | 高级：自定义字段变更后的回调 |

## 8.3 `Preferences` 默认结构

`packages/@core/preferences/src/config.ts` 涵盖：

| 段 | 关键字段 |
| --- | --- |
| `app` | `name`、`version`、`logo`、`copyright`、`enableRefreshToken`、`loginExpiredMode`、`locale`、`dynamicTitle`、`defaultHomePath`、`accessMode`（`'backend'`/`'frontend'`）、`compact` |
| `navigation` | `style: 'rounded' | 'plain'`、`showIcon`、`enable` |
| `sidebar` | `width`、`collapsedWidth`、`theme: 'dark' | 'light'`、`collapsed`、`enable`、`autoActivateChild` |
| `tabbar` | `enable`、`maxCount`、`showIcon`、`showTitle`、`draggable`、`show`、`visitHistory`、`affixTab` |
| `header` | `enable`、`height`、`theme` |
| `footer` | `enable`、`fixed`、`copyright` |
| `theme` | `mode: 'light' | 'dark' | 'auto'`、`colorPrimary`、`radius`、`fontSize`、`semantic`、`colorFollowPrimary` |
| `transition` | `enable`、`progress`、`loading` |
| `widget` | `lockScreen`、`notification`、`globalSearch`、`themeToggle`、`languageToggle`、`fullscreen`、`sidebarToggle`、`preferences`、`tenant`、`timezone`、`help`、`checkUpdates` |
| `shortcutKeys` | `enable`、`global`、`search`、`logout`、`preferences` |
| `preferencesButton` | `enable`、`position` |
| `copyright` | `enable`、`companyName`、`companySiteLink`、`icp`、`icpLink` |

## 8.4 应用级 overrides（`apps/web-antd/src/preferences.ts`）

```ts
export const overridesPreferences = defineOverridesPreferences({
  app: {
    accessMode: 'backend',
    name: import.meta.env.VITE_APP_TITLE,
    enableRefreshToken: true,
  },
  footer: { enable: false, fixed: false },
  copyright: {
    companyName: import.meta.env.VITE_APP_TITLE,
    companySiteLink: 'https://gitee.com/yudaocode/yudao-ui-admin-vben',
  },
});
```

`overrides` 仅覆盖字段，其他字段保留默认。

## 8.5 应用级 extension（自定义字段）

```ts
interface WebAntdPreferencesExtension {
  defaultTableSize: number;
  enableFormFullscreen: boolean;
  reportTitle: string;
  tenantMode: 'multi' | 'single';
}

export const preferencesExtension = definePreferencesExtension<WebAntdPreferencesExtension>({
  tabLabel: 'preferences.antd.tabLabel',
  title: 'preferences.antd.title',
  fields: [
    { component: 'switch',  defaultValue: true,  key: 'enableFormFullscreen', label: '...' },
    { component: 'select',  defaultValue: 'single', key: 'tenantMode', options: [...] },
    { component: 'number',  defaultValue: 20, key: 'defaultTableSize', componentProps: { min: 10, max: 200, step: 10 } },
    { component: 'input',   defaultValue: '', key: 'reportTitle' },
  ],
});
```

- `tabLabel` / `title`：偏好面板中的标签（i18n key）。
- `fields`：在偏好面板中渲染的字段集合（switch/select/number/input/component 等）。
- `definePreferencesExtension<T>` 提供类型补全，运行期由 `@vben/layouts` 中 `widgets/preferences/blocks/custom/custom.vue` 自动渲染。

## 8.6 偏好面板 UI

`packages/effects/layouts/src/widgets/preferences/`

- `preferences.vue`：抽屉容器。
- `blocks/general/`、`blocks/layout/`、`blocks/theme/`、`blocks/shortcut-keys/`、`blocks/custom/`：分块。
- 通用字段组件：`switch-item.vue`、`select-item.vue`、`input-item.vue`、`number-field-item.vue`、`toggle-item.vue`、`checkbox-item.vue`。
- `use-open-preferences.ts`：抽屉开关。
- `preferences-button.vue`：偏好入口按钮（顶栏右上）。

## 8.7 修改偏好的几种方式

```ts
// 1. 直接修改响应式对象
preferences.app.compact = true;

// 2. 路径式更新（推荐：触发持久化）
updatePreferences('app.compact', true);
updatePreferences('theme.colorPrimary', '#1677ff');

// 3. 调用 usePreferences() 内部封装
const { preferences, updatePreferences, resetPreferences } = usePreferences();
```

## 8.8 与主题的关系

- `theme.mode === 'auto'` 时跟随系统；`usePreferences()` 暴露 `isDark` ref。
- `theme.colorPrimary` 变化 → 调 `updateCSSVariables` + Antd `theme.useToken` 重新生成 token（`apps/web-antd/src/app.vue` 的 `useAntdDesignTokens()`）。
- `theme.radius`、`theme.fontSize` 直接写 CSS 变量。