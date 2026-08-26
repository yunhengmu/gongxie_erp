# 芋道管理后台 Code Wiki

> 本文档为 `yudao-ui-admin-vben` 仓库（vbenjs/vue-vben-admin v5.7.0 的 yudao 定制版）的结构化代码 Wiki，面向二次开发、运维、新成员上手等场景。

---

## 文档导航

| 序号 | 文档 | 说明 |
| --- | --- | --- |
| 01 | [项目概览](./01-overview.md) | 项目定位、版本、技术栈、运行环境、目录结构总览 |
| 02 | [整体架构](./02-architecture.md) | 分层架构、Monorepo 工作区、应用骨架、模块依赖图 |
| 03 | [应用层 (apps)](./03-apps.md) | `web-antd` / `web-ele` / `web-naive` / `web-tdesign` / `web-antdv-next` 入口与差异 |
| 04 | [共享包 (packages)](./04-packages.md) | `@core/*`、`effects/*`、`stores`、`preferences`、`utils`、`types`、`constants`、`styles`、`locales`、`icons` |
| 05 | [基础设施 (internal / scripts)](./05-internal-scripts.md) | `vite-config`、`tsconfig`、`node-utils`、`vsh`、`turbo-run` 工具 |
| 06 | [路由 & 权限系统](./06-router-and-access.md) | `router`、`guard`、`access.ts`、`generateAccessible` 权限生成 |
| 07 | [状态管理 (stores)](./07-stores.md) | Pinia 仓库：`access`、`user`、`tabbar`、`dict`、`timezone` |
| 08 | [偏好设置 (preferences)](./08-preferences.md) | `PreferenceManager`、`defaultPreferences`、`extension` |
| 09 | [请求层 (request)](./09-request.md) | `RequestClient`、拦截器、上传/下载/SSE、加密、AES |
| 10 | [布局 & 组件适配](./10-layout-and-adapter.md) | `BasicLayout`、`AuthLayout`、`adapter/component`、`adapter/form`、`adapter/vxe-table` |
| 11 | [国际化 (i18n)](./11-locales.md) | `vue-i18n` 集成、语言包加载、Ant Design Vue / Element Plus 联动 |
| 12 | [常用 Hooks & 工具](./12-hooks-and-utils.md) | `useAppConfig`、`useDict`、`useTabs`、`usePagination`、`@vben/utils` 工具集 |
| 13 | [运行与构建](./13-run-and-build.md) | 环境要求、安装、启动、构建、部署、Docker、单测/E2E |

---

## 项目一句话描述

`yudao-ui-admin-vben` 是基于 `vue-vben-admin v5.7.0` 的中后台前端模板，提供 5 套 UI 模板（Ant Design Vue / Element Plus / Naive UI / TDesign / antdv-next），配合芋道后端 (`yudao`) 实现完整的企业级 RBAC 权限、工作流、支付、IM、CRM、ERP、Mall 等业务系统。

---

## 技术栈速览

| 类别 | 选型 |
| --- | --- |
| 前端框架 | Vue 3.5 / TypeScript 6 |
| 构建工具 | Vite 8 |
| 包管理 | pnpm 11+（catalog 协议、workspace） |
| Monorepo | Turborepo 2 |
| 状态管理 | Pinia 3 + pinia-plugin-persistedstate + SecureLS |
| 路由 | vue-router 5（hash / history 可切换） |
| UI 库 | Ant Design Vue 4 / Element Plus 2 / Naive UI 2 / TDesign 1 |
| 样式 | Tailwind CSS 4 / SCSS / BEM |
| 表单 | vee-validate + zod + vben-form |
| 表格 | vxe-table 4 |
| HTTP | axios 1 + 自研 `@vben/request` |
| 国际化 | vue-i18n 11 |
| 工具 | VueUse、dayjs、es-toolkit、ECharts、TinyMCE、bpmn-js |

---

## 核心设计原则

1. **业务无关核心 + 业务层**：所有 UI 无关逻辑收敛到 `@vben-core/*` 与 `@vben/*` 包；业务页面（CRM/ERP/Mall 等）只放在 `apps/*/src/views`。
2. **多 UI 适配**：同一套业务页面在 5 套 UI 库间共享，通过 `adapter/component` + `adapter/form` + `adapter/vxe-table` 三件套实现差异化。
3. **后端路由模式**：菜单与权限统一从后端拉取（`preferences.app.accessMode = 'backend'`），前端只负责渲染与缓存。
4. **数据可加密**：通过 `@vben/utils` 的 `createApiEncrypt` 在请求/响应两端做 AES 加解密。
5. **可插拔偏好**：`preferences` 同时支持全局默认值、应用级 `overrides` 与项目级 `extension` 字段。