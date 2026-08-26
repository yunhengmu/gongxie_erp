# 11 · 国际化 (i18n)

基于 `vue-i18n 11` 实现，由 `@vben/locales` 提供核心能力，由各 app 的 `src/locales/index.ts` 提供应用层加载逻辑。

## 11.1 包结构

```text
packages/locales/
├── src/
│   ├── i18n.ts            # setupI18n + loadLocaleMessages
│   ├── typing.ts          # Locale / LocaleSetupOptions / SupportedLanguagesType
│   └── langs/             # 公共语言包
│       ├── zh-CN/
│       │   ├── common.json
│       │   ├── ui.json
│       │   ├── authentication.json
│       │   ├── page.json
│       │   ├── preferences.json
│       │   └── ...
│       └── en-US/
└── package.json
```

## 11.2 核心 API（`i18n.ts`）

```ts
const i18n = createI18n({
  globalInjection: true,
  legacy: false,
  locale: '',
  messages: {},
});

async function setupI18n(app, options: LocaleSetupOptions = {}) {
  const { defaultLocale = 'zh-CN' } = options;
  loadMessages = options.loadMessages || (async () => ({}));
  app.use(i18n);
  await loadLocaleMessages(defaultLocale);

  i18n.global.setMissingHandler((locale, key) => {
    if (options.missingWarn && key !== 'OAuth 2.0' && key.includes('.')) {
      console.warn(`[intlify] Not found '${key}' key in '${locale}' locale messages.`);
    }
  });
}

async function loadLocaleMessages(lang) {
  if (unref(i18n.global.locale) === lang) return setI18nLanguage(lang);
  setSimpleLocale(lang);

  const message = await localesMap[lang]?.();
  if (message?.default) i18n.global.setLocaleMessage(lang, message.default);

  const mergeMessage = await loadMessages(lang);
  i18n.global.mergeLocaleMessage(lang, mergeMessage);

  return setI18nLanguage(lang);
}
```

要点：
- `loadLocalesMapFromDir(regexp, modules)` 通过 `import.meta.glob('./langs/**/*.json')` 把所有语言包映射为按语言 key 索引的 `ImportLocaleFn`，内部组装为 `{ default: { fileName: {...} } }`。
- 切换语言时：调用 `loadLocaleMessages(lang)` → 先注入公共包（`setLocaleMessage`）→ 再合并应用层自定义包（`mergeLocaleMessage`）→ 调用 `setI18nLanguage(lang)`（同时设置 `<html lang>`）。

## 11.3 应用层加载（`apps/web-antd/src/locales/index.ts`）

```ts
const modules = import.meta.glob('./langs/**/*.json');
const localesMap = loadLocalesMapFromDir(/\.\/langs\/([^/]+)\/(.*)\.json$/, modules);

async function loadMessages(lang) {
  const [appLocaleMessages] = await Promise.all([
    localesMap[lang]?.(),
    loadThirdPartyMessage(lang),
  ]);
  return appLocaleMessages?.default;
}

async function loadThirdPartyMessage(lang) {
  await Promise.all([loadAntdLocale(lang), loadDayjsLocale(lang)]);
}

async function loadDayjsLocale(lang) {
  let locale = await import(`dayjs/locale/${lang === 'zh-CN' ? 'zh-cn' : 'en'}`);
  if (locale) dayjs.locale(locale.default);
}

async function loadAntdLocale(lang) {
  switch (lang) {
    case 'en-US': antdLocale.value = await import('ant-design-vue/es/locale/en_US'); break;
    case 'zh-CN': antdLocale.value = await import('ant-design-vue/es/locale/zh_CN'); break;
  }
}

async function setupI18n(app, options = {}) {
  await coreSetup(app, {
    defaultLocale: preferences.app.locale,
    loadMessages,
    missingWarn: !import.meta.env.PROD,
    ...options,
  });
}

export { $t, antdLocale, setupI18n };
```

## 11.4 业务语言包约定

```text
apps/web-antd/src/locales/langs/
├── zh-CN/
│   ├── common.json          # 公共词条（按钮、状态、消息）
│   ├── ui.json              # UI 组件（placeholder、规则等）
│   ├── authentication.json  # 登录相关
│   ├── page.json            # 页面标题
│   └── preferences.json     # 偏好面板
└── en-US/
    └── ...（同上）
```

调用方式：

```vue
<template>
  <a-button>{{ $t('common.add') }}</a-button>
</template>
```

```ts
import { $t } from '#/locales';
message.success($t('common.saveSuccess'));
```

## 11.5 第三方 UI 库同步

| 库 | 同步方式 |
| --- | --- |
| Ant Design Vue | `ConfigProvider :locale="antdLocale"`（`apps/web-antd/src/app.vue`） |
| Element Plus | `ElConfigProvider :locale="zhCn"`（`apps/web-ele/src/app.vue`） |
| Naive UI | `n-config-provider :locale="zhCN"`（`apps/web-naive/src/app.vue`） |
| TDesign | `ConfigProvider :globalConfig="globalConfig"`（`apps/web-tdesign/src/app.vue`） |
| dayjs | `dayjs.locale(zhCn)` |

## 11.6 切换语言

- 顶栏 `LanguageToggle` 按钮（基于 `@vben/layouts` 的 widget）调用 `loadLocaleMessages('zh-CN' / 'en-US')`。
- 偏好面板也可以通过 `preferences.app.locale` 间接设置。
- `<html lang>` 属性会自动更新，便于浏览器辅助功能（如屏幕阅读器）感知。