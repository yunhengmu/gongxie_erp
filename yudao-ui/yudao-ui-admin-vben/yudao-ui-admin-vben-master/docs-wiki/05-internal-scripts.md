# 05 · 基础设施 (internal / scripts)

仓库根的非业务工具集：构建配置、TS 配置、内部 CLI、辅助脚本。

## 5.1 `internal/vite-config`

为各 app 提供统一的 Vite 配置入口：`import { defineConfig } from '@vben/vite-config'`。

### 关键文件

- `src/index.ts`：导出 `defineConfig`、`options`、`plugins`、`config`、`loadAndConvertEnv`、`typing`。
- `src/config.ts`：默认 Vite 配置（mode/env 加载、缓存目录、css/postcss、build targets、压缩、PWA、archiver 等）。
- `src/plugins/`：内部插件集合（vue、vue-jsx、unplugin-vue-i18n、unplugin-element-plus、tailwindcss、vite-plugin-pwa、rollup-plugin-visualizer、vite-plugin-compression、vite-plugin-lazy-import 等）。
- `src/options.ts`：插件启用开关、define、alias 等。
- `src/utils/env.ts`：`loadAndConvertEnv(mode)` 合并 `.env`、`vite.config` 内联 env、命令行 env。
- `src/typing.ts`：类型导出。

### 主要能力

- 自动注入 `process.env`、`import.meta.env` 类型声明。
- `application` 字段下子应用相关的 HTML 处理、proxy、server 选项。
- `vite-plugin-vue-devtools` 可按 `VITE_DEVTOOLS` 开关。
- PWA 插件（仅生产环境开启时打包 service-worker）。
- 通过 `visualizer` + `VITE_COMPRESS` 支持 `analyze` 模式。

## 5.2 `internal/tsconfig`

集中维护 TS 配置 base，被根和各包复用：

| 配置 | 作用 |
| --- | --- |
| `base.json` | 基础严格模式配置（`strict`、`noImplicitAny`、`skipLibCheck`） |
| `web.json` | 浏览器端 |
| `web-app.json` | Vben 应用（额外支持 `imports` 别名） |
| `node.json` | Node.js 工具 |
| `library.json` | 包发布（产出 d.ts） |

包内 `tsconfig.json` 一般 `extends: "@vben/tsconfig/xxx"`。

## 5.3 `internal/node-utils`

Node 侧通用工具，被 `scripts/*` 使用：

| 文件 | 功能 |
| --- | --- |
| `date.ts` | 日期格式化 |
| `fs.ts` | 文件/目录读写、globby |
| `git.ts` | Git 信息读取 |
| `hash.ts` | 内容 hash 计算 |
| `path.ts` | 跨平台路径 |
| `spinner.ts` | 终端加载动画 |
| `index.ts` | 统一出口 |

## 5.4 `scripts/vsh`

自研 CLI（`@vben/vsh`），使用 `cac` 框架，集成多子命令。

```text
vsh lint                       # 运行 oxlint + oxfmt（默认）
vsh lint --format              # 格式化模式
vsh check-dep                  # 包依赖依赖关系检查（基于 @manypkg）
vsh check-circular             # 循环依赖扫描（circular-dependency-scanner）
vsh publint                    # 包发布前自检（包名/导出/类型等）
```

- `src/index.ts`：`cac` 注册主命令。
- `src/lint/`、`src/check-dep/`、`src/publint/` 子命令实现。
- `tsdown.config.ts`：使用 `tsdown` 构建成可执行文件 `bin/vsh.mjs`。

## 5.5 `scripts/turbo-run`

`@vben/turbo-run`：扩展 Turbo 行为，提供 `dev`/`preview` 等聚合任务。

```text
pnpm dev                       # 等价 turbo-run dev（开启所有 app dev 服务）
pnpm preview                   # turbo-run preview
```

- `src/index.ts`：解析参数并调用 `@vben/turbo-run/run`。
- `src/run.ts`：聚合任务实现。

## 5.6 `scripts/clean.mjs`

一键清理：

```bash
pnpm clean                       # node ./scripts/clean.mjs
pnpm clean -- --del-lock          # 同时删除 pnpm-lock.yaml
```

## 5.7 `scripts/deploy`

生产部署物料：

- `Dockerfile`：多阶段 Node 构建 → Nginx 提供静态资源。
- `nginx.conf`：默认 Nginx 配置（gzip、SPA history fallback、缓存）。
- `build-local-docker-image.sh`：本地构建镜像（被 `pnpm build:docker` 调用）。

## 5.8 仓库级 CI / Lint 工具

- `eslint.config.mjs`：Flat ESLint 配置（@vben/eslint-config 派生）。
- `oxlint.config.ts`：OxLint 配置（更快 Lint）。
- `oxfmt.config.ts`：OxFMT 格式化。
- `stylelint.config.mjs`：SCSS/Less/CSS 校验。
- `lefthook.yml`：Git Hook（pre-commit、commit-msg、pre-push）。
- `cspell.json`：拼写检查（中文术语不会触发）。
- `vitest.config.ts`：单测全局配置（happy-dom 环境）。
- `playwright.config`（隐式）：E2E 配置由 `playwright` 包提供。

## 5.9 自动化脚本清单

| 命令 | 行为 |
| --- | --- |
| `pnpm bootstrap` | `pnpm install` |
| `pnpm dev` | `turbo-run dev` |
| `pnpm build` | `turbo build`（设置 `NODE_OPTIONS=--max-old-space-size=8192`） |
| `pnpm build:analyze` | analyze 模式构建 |
| `pnpm build:antd` / `:ele` / `:naive` / `:tdesign` / `:antdv-next` / `:docs` | 单 app 构建 |
| `pnpm check` | circular + dep + type + cspell |
| `pnpm lint` / `pnpm format` | Lint / 格式化 |
| `pnpm publint` | 包发布自检 |
| `pnpm test:unit` / `pnpm test:e2e` | 单测 / E2E |
| `pnpm version` | `changeset version` + 重装 |
| `pnpm update:deps` | `taze -r -w`（交互式升级） |
| `pnpm reinstall` | clean + install |