# 13 · 运行与构建

## 13.1 环境准备

| 工具 | 版本 |
| --- | --- |
| Node.js | `>= 22.18.0` 或 `>= 24.0.0`（推荐 24） |
| pnpm | `>= 11.0.0`（强制） |
| Git | 最新 |
| 操作系统 | Windows / macOS / Linux |

> 仓库根 `package.json` 写入 `packageManager: pnpm@11.7.0`，使用 `corepack` 可自动启用该版本。

```bash
corepack enable
corepack prepare pnpm@11.7.0 --activate
```

## 13.2 安装依赖

```bash
pnpm install
# 或
pnpm bootstrap
```

`preinstall` 钩子会校验必须使用 pnpm（`only-allow pnpm`），否则报错。

`postinstall` 钩子执行 `pnpm -r run --if-present stub`，用于各包 stub 自身（如果有）。

## 13.3 启动开发服务

### 启动某个 app

```bash
pnpm dev:antd                # 等价 pnpm -F @vben/web-antd run dev
pnpm dev:ele
pnpm dev:naive
pnpm dev:tdesign
pnpm dev:antdv-next
pnpm dev:docs                # 文档站
```

> 每个 app 默认端口 5666（`web-antd` / `web-ele`），其他 app 走 Vite 默认 5173（未配置）。

### 启动所有 app

```bash
pnpm dev
```

> 实际是 `turbo-run dev`，Turbo 会按依赖顺序启动多个服务，监听各自端口。

### 后端代理

`apps/web-antd/vite.config.ts` 已配置：

```ts
proxy: {
  '/admin-api': {
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/admin-api/, ''),
    target: 'http://localhost:48080/admin-api',
    ws: true,
  },
},
```

`.env.development`：

```ini
VITE_BASE_URL=http://127.0.0.1:48080
VITE_GLOB_API_URL=/admin-api
```

## 13.4 类型检查 / Lint

```bash
pnpm check:dep               # 校验 workspace 包依赖（vsh check-dep）
pnpm check:circular          # 循环依赖扫描
pnpm check:type              # turbo run typecheck
pnpm check:cspell            # 拼写检查
pnpm check                   # 一键四项
pnpm lint                    # oxlint + oxfmt
pnpm format                  # 格式化
pnpm publint                 # 包发布合规
```

## 13.5 构建

### 单 app 构建

```bash
pnpm build:antd              # 构建 web-antd
pnpm build:ele
pnpm build:naive
pnpm build:tdesign
pnpm build:antdv-next
pnpm build:docs              # 构建 VitePress 文档
```

### analyze 模式

```bash
pnpm build:analyze           # 等价 turbo build:analyze
# 或单 app
pnpm -F @vben/web-antd run build:analyze
```

> 启动 `rollup-plugin-visualizer`，输出 `dist/stats.html`。

### 全部构建

```bash
pnpm build
```

`build` 命令通过 `cross-env NODE_OPTIONS=--max-old-space-size=8192` 提高 Node 内存。

### Docker 镜像

```bash
pnpm build:docker            # 调用 scripts/deploy/build-local-docker-image.sh
```

```text
scripts/deploy/
├── Dockerfile
├── nginx.conf
└── build-local-docker-image.sh
```

Dockerfile 采用多阶段：

1. `node:22-alpine` 构建前端产物。
2. `nginx:stable-alpine` 提供静态资源。
3. 通过 `nginx.conf` 配置 SPA fallback、gzip、缓存。

## 13.6 测试

### 单元测试

```bash
pnpm test:unit               # vitest run --dom
```

`vitest.config.ts` 默认使用 `happy-dom` 环境；测试位于各包的 `__tests__/*.test.ts`。

### E2E

```bash
pnpm test:e2e                # turbo run test:e2e
```

> `playwright` 已在 `devDependencies`（catalog）中。`playwright.config` 由具体 app 提供。

## 13.7 部署

### 静态部署（Nginx）

```nginx
server {
  listen 80;
  server_name your.domain.com;

  root /var/www/yudao;
  index index.html;

  gzip on;
  gzip_types text/css application/javascript application/json image/svg+xml;

  location / {
    try_files $uri $uri/ /index.html;
  }

  location /admin-api {
    proxy_pass http://backend:48080/admin-api;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    client_max_body_size 50m;
  }

  location ~* \.(?:css|js|woff2?|ttf|otf|png|jpg|jpeg|svg|gif|webp)$ {
    expires 30d;
    add_header Cache-Control "public, max-age=2592000";
  }
}
```

> `apps/web-antd/.env.production` 中 `VITE_ROUTER_HISTORY=hash` 时直接 `try_files` 兜底即可；若改成 `web` 模式需要配置 SPA fallback（已配）。

### Docker 部署

```bash
docker build -t yudao-ui-admin-vben:latest -f scripts/deploy/Dockerfile .
docker run -d --name yudao-ui -p 80:80 yudao-ui-admin-vben:latest
```

### 与后端协作

- 后端：[芋道 yudao-cloud](https://cloud.iocoder.cn) / [芋道 yudao](https://doc.iocoder.cn)
- API 前缀：`/admin-api`
- 鉴权：`Authorization: Bearer <token>`
- 跨域：由后端配置 `WebMvcConfigurer` 允许前端域名；Nginx 反向代理无需 CORS。

## 13.8 常用排错

| 现象 | 解决 |
| --- | --- |
| 安装报 `EBADENGINE` | 升级 Node 到 22.18+ |
| `pnpm install` 报错非 pnpm | `corepack enable && corepack prepare pnpm@11 --activate` |
| 启动后空白 | 检查 `VITE_BASE_URL` 是否指向后端；检查 `/admin-api` 代理 |
| 登录 401 | 检查 `VITE_APP_STORE_SECURE_KEY` 是否一致；后端 JWT 密钥是否一致 |
| 主题不生效 | 检查 `useAntdDesignTokens` 是否被调用；清缓存（`preferences` key） |
| 路由 404 | 检查 `accessMode: 'backend'` 时是否成功调用 `get-permission-info` |
| AES 加密失败 | 检查 `VITE_APP_API_ENCRYPT_*` 4 个变量是否完整 |
| 字典不显示 | 检查 `dictStore.setDictCacheByApi` 是否被调用，或后端 `/system/dict/data/simple-list` 返回结构 |

## 13.9 升级与发布

- `pnpm update:deps`：使用 `taze -r -w` 交互式升级 catalog 依赖。
- `pnpm changeset`：新增变更说明。
- `pnpm version`：`pnpm exec changeset version && pnpm install`（生成 CHANGELOG + 更新包版本）。
- 包发布：`pnpm publint` 检查 → `pnpm publish -r`（如果设置了发布）。

## 13.10 端到端脚本示例

```bash
# 1. 安装
corepack enable
pnpm install

# 2. 启动 web-antd（另起一个 shell 启动后端 yudao）
pnpm dev:antd

# 3. 访问
# http://localhost:5666

# 4. 默认账号（来自 .env.development）
# username: admin
# password: admin123
```

> **生产环境请务必修改**：`.env.production` 中 `VITE_APP_STORE_SECURE_KEY`、`VITE_APP_API_ENCRYPT_*_KEY`、`VITE_APP_BAIDU_CODE`、`VITE_BAIDU_MAP_KEY` 等敏感配置；后端 JWT 密钥也要相应调整。