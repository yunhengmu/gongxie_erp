# 01 · 项目概览

## 1.1 仓库定位

- 仓库名：`vben-admin-monorepo`
- 当前版本：`5.7.0`
- 芋道版本标识：`yudao-version: 2026.07.0-snapshot`
- License：MIT（全部开源，商业免费）
- 上游：基于 [vue-vben-admin v5.7.0](https://github.com/vbenjs/vue-vben-admin)
- 后端配套：[芋道 Spring Boot](https://doc.iocoder.cn) / [芋道 Spring Cloud](https://cloud.iocoder.cn)

## 1.2 主要能力

- **5 套 UI 模板**：Ant Design Vue（默认）、Element Plus、Naive UI、TDesign、antdv-next。
- **完整业务模板**：用户/角色/菜单/部门/字典、租户、SSO、短信、邮件、站内信、公众号、Mall、CRM、ERP、WMS、MES、HRM、FMS、IoT、IM、BPM、AI 等。
- **企业级特性**：动态权限路由、Token 自动刷新、多租户、主题/暗色/紧凑模式、表单/字典/图表/工作流/BPMN/IM RTC 等封装组件。
- **可插拔偏好设置**：动态主题、布局、菜单、Tab、过渡、动画一站式开关。

## 1.3 技术栈版本

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 框架 | Vue | 3.5.38 |
| 构建 | Vite | 8.0.10 |
| 语言 | TypeScript | 6.0.3 |
| 状态 | pinia / pinia-plugin-persistedstate | 3.0.4 / 4.7.1 |
| 路由 | vue-router | 5.1.0 |
| 工具集 | VueUse | 14.3.0 |
| 国际化 | vue-i18n | 11.4.5 |
| 样式 | Tailwind CSS | 4.3.1 |
| 图标 | Iconify | 5.0.1 |
| 富文本 | TinyMCE | 7.9.3 |
| 图表 | ECharts | 6.1.0 |
| HTTP | axios | 1.18.0 |
| 时间 | dayjs | 1.11.21 |
| 校验 | vee-validate / zod | 4.15.1 / 3.25.76 |
| UI 库 | ant-design-vue / element-plus / naive-ui / tdesign-vue-next | 4.2.6 / 2.14.2 / 2.44.1 / 1.20.1 |

## 1.4 运行环境

- Node.js：`^22.18.0 || ^24.0.0`（推荐 24）
- pnpm：`>=11.0.0`（强制使用 pnpm；`preinstall` 中 `only-allow pnpm`）
- 包管理器固定版本：`pnpm@11.7.0`（写入 `packageManager` 字段）
- 操作系统：Windows / macOS / Linux 均可，CI 脚本使用 Node 22+

## 1.5 顶层目录结构

```text
yudao-ui-admin-vben-master/
├── apps/                       # 各 UI 模板独立应用（5 套）
│   ├── web-antd/               # Ant Design Vue（默认）
│   ├── web-antdv-next/         # antdv-next
│   ├── web-ele/                # Element Plus
│   ├── web-naive/              # Naive UI
│   └── web-tdesign/            # TDesign
├── docs/                       # VitePress 文档站点（已不维护，仅留存）
├── internal/                   # 内部工具包，仅作 workspace 共享
│   ├── node-utils/
│   ├── tsconfig/
│   └── vite-config/
├── packages/                   # 共享业务包，发布到 npm 也可被 apps 引用
│   ├── @core/                  # 完全与 UI 无关的核心（base、composables、preferences、typings、ui-kit）
│   ├── constants/              # 业务常量
│   ├── effects/                # 跨应用副作用：access/common-ui/hooks/layouts/plugins/request
│   ├── icons/                  # SVG / Iconify 图标封装
│   ├── locales/                # vue-i18n 多语言
│   ├── preferences/            # 应用级偏好
│   ├── stores/                 # Pinia 仓库
│   ├── styles/                 # 全局样式入口（按 UI 库拆 index.css）
│   ├── types/                  # 通用类型定义
│   └── utils/                  # 通用工具方法
├── scripts/                    # 内部 CLI（vsh、turbo-run、clean）
├── playground/                 # （保留目录）
├── docs/                       # 文档站（vitepress）
├── package.json                # 根 package.json（workspace 根）
├── pnpm-workspace.yaml         # workspace 配置 + catalog 协议
├── turbo.json                  # Turborepo 任务管线
├── lefthook.yml                # Git Hooks 配置
├── eslint.config.mjs           # Flat ESLint 配置
├── oxlint.config.ts            # OxLint 配置
├── stylelint.config.mjs        # StyleLint 配置
├── vitest.config.ts            # 单测配置
├── .changeset/                 # Changesets 版本管理
└── scripts/deploy/             # Docker / Nginx 部署文件
```

## 1.6 应用工程内部结构（以 `apps/web-antd/src` 为例）

```text
src/
├── adapter/
│   ├── component/index.ts     # 组件适配器（全局注册 + Placeholder 包装）
│   ├── form.ts                # 表单适配器（vee-validate 规则 + 默认 placeholder）
│   └── vxe-table.ts           # 表格适配器（vxe-table 渲染器注册）
├── api/                        # 与后端对接的接口层
│   ├── core/auth.ts           # 登录、刷新、登出、权限信息
│   ├── system/...             # 系统管理
│   ├── bpm/...                # 工作流
│   ├── crm/erp/mall/...       # 业务模块
│   └── request.ts             # RequestClient 实例与拦截器配置
├── layouts/
│   ├── basic.vue              # 主框架布局
│   ├── auth.vue               # 登录布局
│   └── index.ts               # 异步组件出口
├── locales/                    # 语言包加载 & 第三方 UI 库语言
│   ├── langs/zh-CN/*.json
│   ├── langs/en-US/*.json
│   └── index.ts               # setupI18n
├── router/
│   ├── routes/                # core + modules/*.ts（业务路由）
│   ├── access.ts              # 权限路由生成
│   ├── guard.ts               # 全局守卫
│   ├── tongji.ts              # 百度统计
│   └── index.ts               # 创建 vue-router 实例
├── store/
│   ├── auth.ts                # 登录 store（包含鉴权、退出、刷新）
│   └── index.ts               # 业务 store 聚合（项目级，仅 auth）
├── views/
│   ├── _core/                 # 公共视图（登录、404、个人中心等）
│   └── ai/bpm/crm/...         # 业务页面
├── utils/                      # 项目级工具
├── assets/                     # 图片、SVG 等
├── components/                 # 项目级业务组件（DictTag、Upload、Tinymce 等）
├── app.vue                     # 根组件（外层 AntdProvider）
├── bootstrap.ts                # 启动流程
├── main.ts                     # 入口（注入 preferences 后再 bootstrap）
└── preferences.ts              # 项目偏好 overrides + extension
```