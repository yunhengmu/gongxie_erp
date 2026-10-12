# 一键运行（Docker Compose）

> 面向**部署与试用**：用容器拉起**开箱可登录**的完整环境（不需手工建库 / 装中间件）。
> **从源码手工跑**（自行准备 PostgreSQL / Redis / Milvus、手工建库）见 [getting-started.md](getting-started.md)。

仓库提供两套编排，**组件一致、仅数据库方言不同**，且**项目名与端口全部错开，可以同时运行**：

| | PostgreSQL 栈 | MySQL 8.0 栈 |
|---|---|---|
| 目录 | [`deploy/`](../deploy) | [`deploy-mysql/`](../deploy-mysql) |
| Compose 项目名 | `bizbuddy` | `bizbuddy-mysql` |
| 前端 / 后端 / 调度控制台 | `80` / `8080` / `8800` | `81` / `8081` / `8801` |
| 数据库 / Redis / Milvus | `5432` / `6379` / `19530` | `3306` / `6380` / `19531` |
| 平台 MinIO（控制台） | `9000`（`9001`） | `9002`（`9003`） |

`up` 起来的是：数据库 + Redis + Milvus（standalone，含 etcd / minio）+ 后端 `ruoyi-admin` + 调度服务端 `snailjob-server` + Nginx（托管前端 `dist`）+ 平台 MinIO（一次性 `minio-init` 建桶并上传演示文档）。

> 下文命令除注明外，均在**仓库根目录（`biz-buddy/`）**执行；`cd deploy` 之类表示进入该子目录。

## 1. 前置：先在宿主机构建三个产物

镜像里**只放 jar / dist、不在镜像内编译**，所以 `up` 之前必须先构建：

```bash
# ① 后端 jar → RuoYi-Vue-Plus/ruoyi-admin/target/ruoyi-admin.jar
cd RuoYi-Vue-Plus
./mvnw clean package -DskipTests                 # Windows: .\mvnw.cmd clean package -DskipTests

# ② 调度服务端 jar → ruoyi-extend/ruoyi-snailjob-server/target/ruoyi-snailjob-server.jar
./mvnw -o -pl ruoyi-extend/ruoyi-snailjob-server -am package -DskipTests

# ③ 前端静态资源 → plus-ui-Vue/dist
cd ../plus-ui-Vue
pnpm install && pnpm build:prod
```

> 只调中间件时可跳过构建，例如只要一个 PostgreSQL：`cd deploy && docker compose up -d postgres`。
> **前端产物 `dist/` 不入库**（见 `plus-ui-Vue/.gitignore`）：改了 `plus-ui-Vue/src` 下任何源码（含页面 / 文案）都必须重新 `pnpm build:prod`，否则 Nginx 托管的仍是旧包。

## 2. 启动 PostgreSQL 栈

```bash
cd deploy
cp .env.example .env          # 可选：不复制则用文件内默认值（端口、口令等，见 .env.example）
docker compose up -d --build
```

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost/> |
| 后端接口文档 | <http://localhost:8080/swagger-ui.html> |
| 调度控制台 | <http://localhost:8800/snail-job> |
| 初始账号 | `admin / admin123`（另有 `test / 666666`、`test1 / 666666`） |

## 3. 启动 MySQL 8.0 栈

```bash
cd deploy-mysql
cp .env.example .env
docker compose up -d --build
```

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost:81/> |
| 后端接口文档 | <http://localhost:8081/swagger-ui.html> |
| 调度控制台 | <http://localhost:8801/snail-job> |
| 初始账号 | `admin / admin123` |

> 两栈可**同时运行**（上表端口已错开），适合并排对照；只跑一个也可以。中间件口令在各目录的 `.env.example`（默认值仅供本地）。

## 4. 首次启动后必做：补模型密钥

建库脚本预置了两个**示例模型**（阿里云百炼 / DashScope，走 OpenAI 兼容协议），但 `api_key` **一律留空**：

| 配置名称 | 类型 | 说明 |
|---|---|---|
| `deepseek-v4-pro-0813` | 对话 | 入口智能体「小Z」与内置专家的模型槽位**已指向它** |
| `qwen3.7-text-embedding-flash` | 向量 | 维度按 `1024` 预置，须与模型实际输出一致 |

登录后进「模型配置」，把这两个模型（或你另行新建的模型）的 API Key 换成自己的密钥即可——**不补则对话与向量化不可用**。**两栈的模型配置在各栈自己的库里、互不相通，需分别补一次。**

> 对话模型另有一项 **「支持视觉」**（`ai_model_config.supports_vision`，默认「是」）：聊天**上传图片**时会按「入口智能体 + 本会话指定专家」的模型校验该声明，显式设为「否」即在上传时拦下并提示。若所用模型其实不支持图片输入，请把它设为「否」，以免走到模型侧才报错。

## 5. 起来就有什么

- 库表与初始数据全部就绪（含 `ai_*` 表与 AI 菜单的 30 个功能页），`admin` 等初始账号在位；
- 内置智能体：入口智能体「小Z」1 个 + 专家 3 个（机构管理、公告管理，以及演示专家「BizBuddy项目介绍专家」）；
- 演示知识库一套：知识库 `bizbuddy_demo_kb`（BizBuddy介绍演示库）+ 文档「BizBuddy简介.txt」+ 已绑定检索工具的演示专家；
- **演示文档需先「重新解析」再「向量化」**：种子只写入正文、不含切片，故初值是「未解析」——按提示点一次「重新解析」即可（这是刻意的状态一致，不是缺陷）。

## 6. 常用运维

```bash
docker compose ps                        # 状态
docker compose logs -f ruoyi-admin       # 跟踪后端日志（服务名两栈通用）
docker compose up -d --build             # 改了代码 / 配置后重建
docker compose restart ruoyi-admin       # 只重启后端（如改完 OSS 配置）
docker compose down                      # 停止（保留数据卷）
docker compose down -v                   # 停止并删数据卷：下次启动会重新建库并重写种子
```

> `down -v` 会清库：**模型 API Key 也随之丢失**（密文存在库里），需要重新补；同时会清掉会话、文档、切片与 Redis 运行期配置。

## 7. 验证「真的跑通了」

栈起来后可跑端到端脚本，对**真实运行**的环境做断言（模型连通 / 知识库检索 / 专家编排 / 写工具确认闭环）：

```bash
node e2e/run.mjs                                   # PostgreSQL 栈（后端默认 8080）
node e2e/run.mjs --base-url http://localhost:8081  # MySQL 栈
```

> 上述命令需在**仓库根目录**执行；脚本说明与前置条件见 [../e2e/](../e2e)（唯一机密 `BIZBUDDY_LLM_API_KEY` 见 `e2e/.env.example`）。
> 部署排障：PostgreSQL 栈见 [`deploy/README.md`](../deploy/README.md)，MySQL 栈见 [`deploy-mysql/README.md`](../deploy-mysql/README.md)（含 OSS 对外地址、MySQL 认证插件、端口改动等已知坑）。