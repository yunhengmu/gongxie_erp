# 一键部署（PostgreSQL 版）

用 Docker Compose 拉起 BizBuddy 全栈：**PostgreSQL 14 + Redis + Milvus standalone（etcd + 复用平台 MinIO）+ 后端 + 调度服务端 + Nginx（前端）**。

> 与 `code/back-end/script/docker/docker-compose.yml` 的区别：那个是上游 RuoYi 模板（MySQL、无 Milvus、直接用成品镜像）；本目录针对本项目的**真实技术栈**，并把建库脚本按依赖顺序挂进 PostgreSQL 的初始化目录，`up` 完即是「有表、有数据、可登录」的环境。

> 需要 **MySQL 8.0** 时，用并列的 [`deploy-mysql/`](../deploy-mysql)（组件一致，仅数据库 / JDBC 驱动 / 技能仓库实现不同；项目按数据源自动装配，无需改配置）。两套编排的项目名与端口已错开，可同时运行。

## 1. 前置：先构建两个产物

项目自带的 Dockerfile 用的是 `ADD ./target/xxx.jar`，即**镜像里只有 jar，不在镜像内编译**。所以启动前必须先在宿主机构建：

```bash
# 后端 jar（ruoyi-admin/target/ruoyi-admin.jar）
cd code/back-end
./mvnw clean package -DskipTests          # Windows: .\mvnw.cmd clean package -DskipTests

# 调度服务端 jar（ruoyi-extend/ruoyi-snailjob-server/target/ruoyi-snailjob-server.jar）
./mvnw -o -pl ruoyi-extend/ruoyi-snailjob-server -am package -DskipTests

# 前端静态资源（code/front-end/dist，由 Nginx 直接托管）
cd ../../code/front-end
pnpm install && pnpm build:prod
```

> 若只调 Redis/PostgreSQL 等中间件，可跳过构建，只 `up` 对应服务（如 `docker compose up -d postgres`）。

## 2. 启动

```bash
cd deploy/deploy-pg
cp .env.example .env        # 可选；本地开发请把其中的 BIZBUDDY_SECURITY_GUARD_MODE 改为 warn
docker compose up -d --build
```

> **不复制 `.env` 会起不来**：密钥 / 口令类沿用出厂示例值时，启动自检（`ProductionConfigGuard`）
> 会拒绝启动并列出命中项。本地开发在 `.env` 里设 `BIZBUDDY_SECURITY_GUARD_MODE=warn` 即可照旧沿用出厂值；
> `prod` 下该开关无效，恒为拒绝启动。

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost/> |
| 后端接口文档 | <http://localhost:8080/swagger-ui.html> |
| 调度控制台 | <http://localhost:8800/snail-job> |
| 初始账号 | `admin / admin123`（另：`test / 666666`、`test1 / 666666`） |

## 3. 首次启动后必做：配置模型密钥

建库脚本已预置**四个示例模型**（提供商：阿里云百炼 / DashScope；**协议各异**——对话 / 向量走 OpenAI 兼容，决策走 TypeSafe System One，重排走 DashScope 文本重排端点），但 **`api_key` 一律留空**，需自行补齐：

| 配置名称 | 类型 | 说明 |
|---|---|---|
| `deepseek-v4.1-flash` | 对话 | 大语言模型 |
| `qwen3.7-text-embedding-flash` | 向量 | 维度按 `1024` 预置，须与模型实际输出一致 |
| `decision-model-preview` | 决策 | 供「安全合规配置 → 结构化决策路由」引用 |
| `gte-rerank-v2` | 重排 | 供「知识库管理」的检索重排引用 |

1. 登录 →「模型配置」把这四个模型（或你另行新建的模型）的 **API Key** 换成自己的阿里云百炼密钥 —— 换完即可用，无需其它配置；
2. 内置智能体（小Z 与两个专家）的模型槽位**已指向** `deepseek-v4.1-flash`；若要换模型，到「小Z 配置」「专家配置」改指即可；
3. 要用知识库检索，向量模型须选上表中的 embedding 模型（维度与知识库一致）。

> 智能体只绑定了模型，未绑知识库 / 技能；若要换用其它厂商或模型，在「模型配置」页新建即可（提供商选对应项，`base_url` 填其 OpenAI 兼容根地址）。

### 升级须知（2026-10-14 重排改造）

本次改造删除了全局 `bizbuddy.ai.rag.rerank-enabled` / `rerank-model` / `rerank-endpoint` 三项；重排模型改在「模型配置」登记为 `rerank` 类型，由「知识库」开启「检索重排」并选择模型（`ai_knowledge_base` 新增两列）。建表脚本已加这两列与列注释，但**建库脚本只挂进 `docker-entrypoint-initdb.d`、仅在数据卷为空（首次初始化）时执行，不会重跑**——**存量库必须手工迁移**：

```sql
alter table ai_knowledge_base add column rerank_enabled varchar(1) not null default '0';
alter table ai_knowledge_base add column rerank_model_id bigint;
comment on column ai_knowledge_base.rerank_enabled is '检索重排开关（1启用/0未启用，默认0）';
comment on column ai_knowledge_base.rerank_model_id is '检索重排模型（ai_model_config.id，model_type=rerank）';
```

### 升级须知（2026-10-19 渠道长连接）

渠道接入新增**飞书长连接**形态：`ai_channel` 新增 `mode` 列（`webhook` / `ws`，默认 `webhook`；仅飞书区分，须与飞书后台「事件订阅方式」一致）。建表脚本已含该列与列注释，但同样**只在空卷首次初始化时执行**——**存量库需手工迁移**（建表脚本内已有幂等过程，亦可直接复用）：

```sql
alter table ai_channel add column if not exists mode varchar(16) not null default 'webhook';
comment on column ai_channel.mode is '接入形态：webhook Webhook 回调（默认，需平台公网可达） / ws 长连接（应用主动外连、免公网 IP；仅飞书区分）';
```

## 4. 实测结论（2026-10-09，WSL2 + Docker 29.3 / Compose v5.1.1）

`docker compose up -d --build` 后：

| 容器 | 状态 |
|---|---|
| bizbuddy-postgres / redis / milvus / milvus-etcd | Up (healthy) |
| bizbuddy-minio（平台 OSS，Milvus 复用其 `milvus-bucket` 桶） | Up (healthy)；`minio-init` 一次性建桶 / 上传演示文档后退出 |
| bizbuddy-admin | Up，启动成功日志，无 ERROR |
| bizbuddy-snailjob-server | Up，无 ERROR |
| bizbuddy-web | Up |

连通性：

| 检查 | 结果 |
|---|---|
| `GET http://localhost/`（前端） | 200 |
| `GET http://localhost/prod-api/auth/code`（Nginx → 后端代理） | 200 |
| `GET http://localhost:8080/auth/code`（后端直连） | 200 |
| `GET http://localhost:8800/snail-job/`（调度控制台） | 200 |
| 后端数据源 | HikariPool 初始化成功 |
| 后端 Redis | Redisson 已连接 `redis:6379` |
| 建库结果 | 98 张表（`ai_*` **40** / `sj_*` 23 / `sys_*` 20 / `flow_*` 10 / `test_*` 3 / `gen_*` 2）；`admin` 等初始账号在位；`sys_menu` BizBuddy 段 **136** 行（6 分组 / **34** 功能页 / **95** 按钮）；`ai_tool` **30**、入口小Z 1、专家 **4**（机构管理 / 公告管理 / 演示「BizBuddy项目介绍专家」/ **平台运维助手**）、技能 **1**（平台运行日报）、定时任务 **1**、`ai_model_config` **4**（`api_key` 留空）；演示种子一套（知识库 `bizbuddy_demo_kb` + 文档 `BizBuddy简介.txt` + `sys_oss` 行）；`sj_job` 已有派发任务 |

> **可一键自检**：仓库自带端到端测试脚本 [`../../e2e/http/`](../../e2e/http)——对**已运行**的栈发真实 HTTP 断言，覆盖 **L1~L17**（L14 已并入 L3）：「模型连通 → 专家提示词生成 → 知识库生命周期（解析/批量/向量化/检索）→ 对话与专家编排（SSE）→ HITL 写工具确认 → 停止生成 → 安全合规 → 会话治理 → 鉴权边界 → MCP 出口协议 → MCP 客户端 → 结构化决策闸门（JEV 桩）→ 停止生成（中断专家编排）→ 只读轮次重新生成 → 长期记忆生命周期 → 只读记忆检索」；另含 RAG 检索质量评测（`e2e/http/eval/`）。用法：

```bash
cd biz-buddy
node e2e/http/run.mjs                        # 全量；未配密钥的层自动 SKIP
node e2e/http/run.mjs --only L1,L3 --verbose # 指定层级
node e2e/http/run.mjs --allow-writes         # L5 走「接受」路径（会真建公告，收尾自动清理）
```

> 密钥放 `e2e/http/.env.local`（模板见 `e2e/http/.env.example`，已 gitignore）；唯一机密 `BIZBUDDY_LLM_API_KEY` **仅 L1 需要**（作为瞬时入参下发、不落库），L2~L6 走「模型配置」里已保存的密钥，L7~L11 走拒答 / 失败路径或只读协议调用、**不触发模型调用**；L12 需 JEV 桩已启动（否则 SKIP），L13~L17 走平台密钥（真跑模型）。退出码：`0` 无失败（含 SKIP）/ `1` 有 FAIL / `2` 前置检查失败（栈不可达、登录失败、种子缺失）/ `3` 参数错误。详见 [docs/14 §85](../../../docs/14-BizBuddy未覆盖项与已知限制.md#s85) 与 [docs/17 §85](../../../docs/17-BizBuddy实施过程与验证记录.md#s85)。

## 5. 已知坑（都踩过，按此排查）

**① 用 Docker Hub 拉 `bellsoft/liberica-openjdk-rocky` 反复失败**
现象：`short read: expected … bytes but got 0: unexpected EOF`，构建一直卡住（BuildKit 无缓存增长）。
处置：走镜像源拉取后 **tag 回原名**，无需改 daemon 配置：

```bash
docker pull docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds
docker tag  docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds \
            bellsoft/liberica-openjdk-rocky:21.0.12-cds
```

**② MinIO 镜像 tag 已消失**
现象：`minio/minio:RELEASE.2024-05-28T17-19-04Z: not found`。MinIO 官方已停止通过 Docker Hub 发布，旧 tag 拉不到。
处置：本目录默认用仍可拉取的 release（平台 MinIO，Milvus 复用同一实例），并支持覆盖（`.env`）：`MINIO_IMAGE=…`，必要时用社区 fork `pgsty/minio:…`。

**③ 构建上下文必须是模块目录**
现象：`failed to calculate checksum … "/target/ruoyi-admin.jar": not found`。
原因：Dockerfile 里是 `ADD ./target/xxx.jar`，上下文必须是**该模块目录**。若把 `context` 设成仓库根就找不到（上游 compose 直接用成品镜像，没暴露这一点）。本目录已按模块设置。

**④ 初始化脚本顺序：AI 依赖调度**
现象：容器 healthy，但日志里有 `relation "sj_job" does not exist`。
原因：`postgres_ry_ai.sql` 末尾要向 `sj_job` 登记「定时 Agent 任务派发」，依赖 `postgres_ry_job.sql`。
**关键提醒：initdb 期间单条语句失败不会阻断启动，容器照样 healthy —— 故障是静默的**，务必核对日志：

```bash
docker logs bizbuddy-postgres 2>&1 | grep -E 'docker-entrypoint-initdb.d|ERROR'
```

**⑤ 环境变量名撞键：`SNAILJOB_HOST` ≠ `snail-job.server.host`**
现象：后端日志 `Snail-Job 客户端IP为: snailjob-server`，调度服务端刷 `GrpcClientInvokeHandlerV2 / JobExecutorRetryListener` 报错（回连执行器失败）。
原因：Spring 松散绑定下 `SNAILJOB_HOST` 落到 **`snail-job.host`（客户端自己的 IP）**，而服务端地址是 `snail-job.server.host`（对应 `SNAILJOB_SERVER_HOST`）。
处置：本目录已改用 `SNAILJOB_SERVER_HOST`。正确时应看到 `客户端IP为: 172.22.0.x`（容器真实 IP），且调度端 ERROR 归零。

**⑥ 知识库/文件上传报 `Connection refused: /127.0.0.1:9000`**
现象：上传文档失败，后端日志 `S3StorageException: Unable to execute HTTP request: Connection refused: /127.0.0.1:9000`。
原因：**平台文件存储（OSS）的坐标不在 yaml 里，而在数据库表 `sys_oss_config`**（框架种子写的是本地开发机的 `127.0.0.1:9000`）。容器里 `127.0.0.1` 是容器自身，自然连不上。
处置：本目录已备好两件事——① 平台 MinIO 服务 `minio`（9000/9001；**Milvus 复用同一实例**，向量数据放独立桶 `milvus-bucket`，与平台文件桶 `ruoyi` 分开）；② 一次性初始化 `minio-init` 建桶（**私有桶**，以 `mc anonymous set none` 取代 `mc anonymous set download`；对应 `sys_oss_config.access_policy=0`，且**框架不会自动建桶**）。文件访问由后端**读取时现算预签名 URL**（有效期 `oss.presign-expire-seconds`，默认 120s）。建桶复用 `minio` 镜像**自带的 `mc`**——`minio/mc` 独立镜像在部分镜像源上拉不到（403）。
另外 `deploy/deploy-pg/sql/50-oss-endpoint.sql` 会在首次建库时把 `sys_oss_config` 的端点改成 `minio:9000`；**已初始化过的数据卷不会重跑**，需手动补一次：

```bash
docker exec -i bizbuddy-postgres psql -U postgres -d biz_buddy < deploy/deploy-pg/sql/50-oss-endpoint.sql
docker compose restart ruoyi-admin      # 让 OSS 配置重新加载
```

MinIO 控制台：<http://localhost:9001>（`ruoyi` / `ruoyi123`）。
> 上传成功还只是第一步：知识库文档随后要**向量化**，需先在「模型配置」配好 **embedding 模型**，否则会在向量化阶段再报错。
> **预置的演示文档**（`BizBuddy简介.txt`）在种子里标为**未解析**（只写正文、不预生成切片），须先在「文档与切片」页点「**重新解析**」再点「**向量化**」；直接向量化会提示「文档切片为空，请先重新解析文档」。

**⑦ 上传成功，但图片/文档在页面上「加载失败」（聊天里的图片、知识库预览）**

现象：后端能读到文件（模型识图、文档解析都正常），**浏览器却加载不出来**。
原因：`sys_oss_config` 有两个坐标——`endpoint`（后端读写用）与 `domain_url`（**返回给前端的对外地址**）。
上面的 `minio:9000` 是**容器网络内**的服务名，浏览器无法解析；后端在容器里能连通，所以只有前端受影响。
处置：`50-oss-endpoint.sql` 已同时写入 `domain_url = 'http://localhost:9000'`（宿主机可达的 MinIO）与 `access_policy = 0`（**私有桶**，文件访问走后端下发的预签名 URL）；
**存量数据卷不会重跑该脚本**，需手动补一次（见 ⑥ 的命令，脚本本身包含这三条 `update`：`endpoint` / `domain_url` / `access_policy`），随后 `docker compose restart ruoyi-admin`。
> 私有桶下 `domain_url` 是**硬前置**：预签名 URL 按它生成，留空或指向不可达地址会导致前端预览 / 下载失败（后端解析不受影响，它走 `endpoint`）。
若从其它机器 / 公网访问，把 `domain_url` 改成对应可达的域名或 IP 即可（后端返回的 URL 以此为准，无需改数据行）。

> 平台侧对公开桶的 URL 是**读取时按配置重新生成**的，因此改 `domain_url` 后**存量文件也一并生效**，不必迁移 `sys_oss.url`。

**⑧ 重建 `ruoyi-admin` 后前端接口报 502**

现象：`docker compose up -d --build`（或单独重建后端）之后，网页能打开但接口 502。
原因：nginx 在**启动时**解析并缓存了 `ruoyi-admin` 的容器 IP；后端容器被**重建**（换 IP）后，nginx 仍指向旧 IP。
处置：`docker compose restart web`（让 nginx 重新解析）。

## 6. 运维命令

```bash
docker compose ps                          # 状态
docker compose logs -f bizbuddy-admin      # 后端日志
docker compose up -d --build               # 改了代码/配置后重建
docker compose restart ruoyi-admin         # 只重启后端
docker compose down                        # 停止（保留数据卷）
docker compose down -v                     # 停止并删除数据卷（下次启动会重新建库）
```

> 建库脚本**只在数据卷为空时执行**。改了口令/库名等变量要生效，需 `down -v` 重建。

> **重启策略**：只有 `ruoyi-admin` 为 `restart: on-failure:3`——配置不合格时应用会 fail-fast 退出，重试 3 次后容器停在 `exited`，日志里最后一次启动报错即根因（**这是设计如此，不是容器故障**）；`ruoyi-snailjob-server`、基础设施与 nginx 为 `unless-stopped`，依赖抖动可自愈（snailjob-server 不依赖 ruoyi-admin、不加载配置收口护栏，没有 fail-fast 前提）。

> 启动/恢复请用**不带服务名**的 `docker compose up -d`：只 `up` 某一个服务不会补齐其它缺失的容器，容易留下「前端能打开、上传却失败」这类半残状态（典型如平台 MinIO 容器缺失 → 上传报 `UnknownHostException: minio`）。用 `docker compose ps` 对照 compose 文件核对服务是否齐全。

## 7. 变量

见 [.env.example](.env.example)（端口、数据库/Redis 口令、Milvus 用户名口令、`MINIO_IMAGE`、`SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`）。
`SPRING_PROFILES_ACTIVE` 须为 `dev` 或 `prod`——建库脚本只创建这两个 SnailJob 命名空间。

## 8. 相关文档

- 后端配置项：[../../docs/configuration.md](../../docs/configuration.md)
- 架构与二次开发：[../../docs/README.md](../../docs/README.md)