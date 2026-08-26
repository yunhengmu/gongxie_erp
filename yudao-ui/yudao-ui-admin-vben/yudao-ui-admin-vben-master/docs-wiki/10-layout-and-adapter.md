# 10 · 布局 & 组件适配

## 10.1 应用骨架布局

`apps/web-antd/src/layouts/`

| 文件 | 作用 |
| --- | --- |
| `basic.vue` | 业务主布局：Header / Sidebar / Tabbar / Content / Footer |
| `auth.vue` | 登录 / 注册布局（背景 + Slogan + 表单） |
| `index.ts` | 异步组件出口，供路由与全局使用 |

实际内容基于 `@vben/layouts` 中的通用组件组装：

```text
packages/effects/layouts/src/
├── basic/
│   ├── layout.vue
│   ├── header/
│   ├── sidebar/         # 含 menu / mixed-menu / extra-menu / use-navigation
│   ├── tabbar/          # tabs + use-tabbar
│   ├── content/
│   ├── footer/
│   └── copyright/
├── authentication/
│   ├── authentication.vue
│   ├── form.vue
│   ├── toolbar.vue
│   └── slogan.vue
├── iframe/
│   ├── iframe-view.vue
│   └── iframe-router-view.vue
├── route-cached/
│   ├── route-cached-page.vue
│   └── route-cached-view.vue
└── widgets/
    ├── check-updates/
    ├── global-search/
    ├── help/
    ├── lock-screen/
    ├── notification/
    ├── preferences/
    ├── tenant-dropdown/
    ├── theme-toggle/
    ├── timezone/
    ├── user-dropdown/
    ├── breadcrumb.vue
    ├── color-toggle.vue
    ├── language-toggle.vue
    └── layout-toggle.vue
```

### `basic.vue` 内部组合（基于 `@vben/layouts`）

```vue
<VbenLayout>
  <template #header>
    <LayoutHeader v-if="preferences.header.enable">
      <LayoutLogo />
      <LayoutMenu v-model:mode="navigationStyle" />
      <Breadcrumb />
      <GlobalSearch />
      <Notification />
      <UserDropdown>
        <PreferencesButton />
        <ThemeToggle />
        <TenantDropdown />
        <Timezone />
      </UserDropdown>
    </LayoutHeader>
  </template>
  <template #sidebar>
    <LayoutSidebar v-if="preferences.sidebar.enable">
      <Menu />
    </LayoutSidebar>
  </template>
  <template #tabbar>
    <LayoutTabbar v-if="preferences.tabbar.enable" />
  </template>
  <template #content>
    <LayoutContent>
      <RouteCachedView>
        <RouterView />
      </RouteCachedView>
    </LayoutContent>
  </template>
  <template #footer>
    <LayoutFooter v-if="preferences.footer.enable">
      <Copyright />
    </LayoutFooter>
  </template>
</VbenLayout>
```

### 国际化整合（`app.vue`）

```vue
<script setup lang="ts">
import { computed } from 'vue';
import { useAntdDesignTokens } from '@vben/hooks';
import { preferences, usePreferences } from '@vben/preferences';
import { App, ConfigProvider, theme } from 'ant-design-vue';
import { antdLocale } from '#/locales';

const { isDark } = usePreferences();
const { tokens } = useAntdDesignTokens();

const tokenTheme = computed(() => ({
  algorithm: [
    isDark.value ? theme.darkAlgorithm : theme.defaultAlgorithm,
    ...(preferences.app.compact ? [theme.compactAlgorithm] : []),
  ],
  token: tokens,
}));
</script>

<template>
  <ConfigProvider :locale="antdLocale" :theme="tokenTheme">
    <App>
      <RouterView />
    </App>
  </ConfigProvider>
</template>
```

## 10.2 `adapter/component`

文件：`apps/web-antd/src/adapter/component/index.ts`

### `ComponentType`

注册到 `globalShareState` 的组件集合：

```ts
export type ComponentType =
  | 'ApiCascader' | 'ApiSelect' | 'ApiTreeSelect'
  | 'AutoComplete' | 'Cascader' | 'Checkbox' | 'CheckboxGroup'
  | 'DatePicker' | 'DefaultButton' | 'Divider'
  | 'FileUpload' | 'IconPicker' | 'ImageUpload'
  | 'Input' | 'InputNumber' | 'InputPassword' | 'Mentions'
  | 'PrimaryButton' | 'Radio' | 'RadioGroup' | 'RangePicker'
  | 'Rate' | 'RichTextarea' | 'Select' | 'Space'
  | 'Switch' | 'Textarea' | 'TimePicker' | 'TimeRangePicker'
  | 'TreeSelect' | 'Upload' | BaseFormComponentType;
```

### `ComponentPropsMap`

为每个 `ComponentType` 提供与 Antd Props 一一映射的类型，例如：

```ts
export interface ComponentPropsMap {
  Input: InputProps;
  Select: SelectProps & { ... };
  Upload: AdapterUploadProps;
  RangePicker: RangePickerProps;
  ...
}
```

### `initComponentAdapter()`

注册到 `globalShareState.setComponents({ ... })`：

```ts
ApiCascader: withDefaultPlaceholder(ApiComponent, 'select', { component: Cascader, ... }),
ApiSelect: withDefaultPlaceholder(ApiComponent, 'select', { component: Select, ... }),
Input: withDefaultPlaceholder(Input, 'input'),
PrimaryButton: (props, { attrs, slots }) => h(Button, { ...props, ...attrs, type: 'primary' }, slots),
RichTextarea, FileUpload, ImageUpload, Upload, PreviewUpload: withPreviewUpload(), ...
```

`withDefaultPlaceholder` 包装组件：

- 统一从 `$t('ui.placeholder.input|select|upload')` 取默认占位文本。
- 通过 Proxy 把 `expose` 的内部 ref 暴露给上层（用于 vben-form 的 `setFieldValue`）。

### `withPreviewUpload`

为 Antd `<Upload>` 增加：

- `beforeUpload`：校验文件大小（`maxSize` MB）、图片裁剪（`crop` + `aspectRatio`）。
- `onChange`：同步 `fileList` 并触发 `update:modelValue`。
- `onPreview`：图片文件走 `<ImagePreviewGroup>`；非图片直接 `window.open`。
- 拖拽排序：`useSortable` + 自定义样式注入；`onDragSort` 回调上抛。
- 卸载时清理 sortable 实例与注入样式。

### `globalShareState.defineMessage`

```ts
globalShareState.defineMessage({
  copyPreferencesSuccess: (title, content) => {
    notification.success({ description: content, message: title, placement: 'bottomRight' });
  },
});
```

> 全局通知在偏好面板点击「复制」时使用。

## 10.3 `adapter/form.ts`

```ts
setupVbenForm<ComponentType>({
  config: {
    baseModelPropName: 'value',
    modelPropNameMap: {
      Checkbox: 'checked',
      Radio: 'checked',
      Switch: 'checked',
      Upload: 'fileList',
    },
  },
  defineRules: {
    required: (value, _params, ctx) => $t('ui.formRules.required', [ctx.label]),
    selectRequired: ...,
    mobile: (value, _params, ctx) => isMobile(value) ? true : $t('ui.formRules.mobile', [ctx.label]),
    mobileRequired: ...,
  },
});
```

- `baseModelPropName: 'value'`：适配 Antd Vue 默认 `v-model:value`。
- `modelPropNameMap`：Checkbox / Radio / Switch 用 `checked`，Upload 用 `fileList`。
- `defineRules` 注入国际化校验规则（`required / selectRequired / mobile / mobileRequired`），可被 vben-form 的 schema 通过 `rules: 'required'` 引用。

导出：

```ts
export type VbenFormApi = ReturnType<typeof useForm>[1];
export type VbenFormSchema = FormSchema<ComponentType, ComponentPropsMap>;
export type VbenFormProps = FormProps<ComponentType, ComponentPropsMap>;
```

## 10.4 `adapter/vxe-table.ts`

基于 `@vben/plugins/vxe-table` 的 `setupVbenVxeTable`，注册全局配置与 7 个 cell renderer：

| Renderer | 用法 |
| --- | --- |
| `CellImage` | `{ cellRender: { name: 'CellImage' } }` → 单张图片 |
| `CellImages` | 多图预览（`<ImagePreviewGroup>`） |
| `CellLink` | 渲染为 Ant `<Button type="link">` |
| `CellTag` / `CellTags` | Ant `<Tag>` |
| `CellDict` | 走项目 `DictTag` 组件，传入 `dictType` |
| `CellSwitch` | 自带 loading + `beforeChange` 钩子 |
| `CellOperation` | 内置 `edit/delete` 预设；自定义操作码可传入 |

`vxeUI.formats.add` 注册若干格式化器：

- `formatPast2` — `formatPast2` 时间格式化
- `formatAmount3` / `formatAmount2` — ERP 数量
- `formatFenToYuanAmount` — 分 → 元
- `formatFileSize` — 文件大小

## 10.5 路由 + 布局联动

```ts
// apps/web-antd/src/router/routes/core.ts
const BasicLayout = () => import('#/layouts/basic.vue');
const AuthPageLayout = () => import('#/layouts/auth.vue');

const coreRoutes: RouteRecordRaw[] = [
  { component: BasicLayout, name: 'Root', path: '/', redirect: preferences.app.defaultHomePath, children: [] },
  { component: AuthPageLayout, name: 'Authentication', path: '/auth', redirect: LOGIN_PATH, children: [...] },
];
```

- `Root` 始终使用 `BasicLayout`，所有业务路由都作为其 children。
- `Authentication` 父路由承载 7 种登录方式（普通、验证码、二维码、注册、社交、SSO、忘记密码）。

## 10.6 多 UI 库的适配差异

| 维度 | web-antd | web-ele | web-naive | web-tdesign | web-antdv-next |
| --- | --- | --- | --- | --- | --- |
| 消息 | `message` / `notification` | `ElMessage` / `ElNotification` | `useMessage()` | `MessagePlugin` | antd 同系 |
| 表单规则 | 同 | 同 | 同 | 同 | 同 |
| 表格渲染 | CellImage 等 7 个 | 同（基本共用 vxe-table） | 同 | 同 | 同 |
| 路由 transition | MotionPlugin | 同 | 同 | 同 | 同 |
| Loading 指令名 | `loading` / `spinning` | `loading` | `loading` | `loading` | `loading` |

> `web-ele` 的 `adapter/component` 会把 `message` 替换为 `ElMessage`，把 `notification` 替换为 `ElNotification`，并把 `message`/`notification` 注入到 `globalShareState`。其余 adapter 的接入流程基本相同。