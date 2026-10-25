# 一键部署（MySQL 8.0 版）

用 Docker Compose 拉起 BizBuddy 全栈：**MySQL 8.0 + Redis + Milvus standalone（etcd + 复用平台 MinIO）+ 后端 + Nginx（前端）**。

> 与 [`deploy-pg/`](../deploy-pg)（PostgreSQL 版）是**并列的两套编排**，技术栈差异只在数据库（及随之为其准备的 JDBC 驱动 / 技能仓库实现），其余组件一致。
> 本项目已同时支持 PostgreSQL 与 MySQL 8.0：**技能仓库按数据源类型自动装配**（`AgentScopeConfig#aiSkillRepository`，PostgreSQL 版 `PostgresSkillRepository` / MySQL 版 `MysqlSkillRepository`），无需改代码。
> 两套编排刻意在**项目名、容器名、对外端口**上区分，因此可以**同时运行、互不干扰**（便于对照验证）。

## 1. 与 PostgreSQL 版的三点差异

| 项 | `deploy-pg/`（PG） | `deploy-mysql/`（MySQL） |
|---|---|---|
| 数据库镜像 | `postgres:14` | `mysql:8.0` |
| 建库脚本 | `script/sql/postgres/*.sql` | `script/sql/*.sql`（MySQL 方言，含 `ry_ai.sql`） |
| compose 项目名 | `bizbuddy` | `bizbuddy-mysql` |
| 默认端口 | 80 / 8080 / 5432 / 6379 / 19530 / 9000 / 9001 | **81 / 8081 / 3306 / 6380 / 19531 / 9002 / 9003** |

> MySQL 8 的部分唯一索引：PostgreSQL 脚本用 `WHERE del_flag='0'` 的部分唯一索引，MySQL 不支持该语法，`ry_ai.sql` 用 MySQL 8.0 的**函数键部分** `(if(del_flag='0', 0, null))` 等价实现（未删除行取 0 参与唯一约束，已删除行取 NULL，而 MySQL 唯一索引允许多个 NULL）。

## 2. 前置：先构建两个产物

项目自带的 Dockerfile 用的是 `ADD ./target/xxx.jar`，即**镜像里只有 jar，不在镜像内编译**。所以启动前必须先在宿主机构建：

```bash
# 后端 jar（ruoyi-admin/target/ruoyi-admin.jar）
cd code/back-end
./mvnw clean package -DskipTests          # Windows: .\mvnw.cmd clean package -DskipTests

# 前端静态资源（code/front-end/dist，由 Nginx 直接托管）
cd code/front-end
pnpm install && pnpm build:prod
```

> 若只调 MySQL/Redis 等中间件，可跳过构建，只 `up` 对应服务（如 `docker compose up -d mysql`）。

## 3. 启动

```bash
cd deploy/deploy-mysql
cp .env.example .env        # 可选；本地开发请把其中的 BIZBUDDY_SECURITY_GUARD_MODE 改为 warn
docker compose up -d --build
```

> **不复制 `.env` 会起不来**：密钥 / 口令类沿用出厂示例值时，启动自检（`ProductionConfigGuard`）
> 会拒绝启动并列出命中项。本地开发在 `.env` 里设 `BIZBUDDY_SECURITY_GUARD_MODE=warn` 即可照旧沿用出厂值；
> `prod` 下该开关无效，恒为拒绝启动。

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost:81/> |
| 后端接口文档 | <http://localhost:8081/swagger-ui.html> |
| 初始账号 | `admin / admin123`（另：`test / 666666`、`test1 / 666666`） |

## 4. 实测结论（2026-10-08，WSL2 + Docker 29.3 / Compose v5.1.1）

`docker compose up -d --build` 后：

| 容器 | 状态 |
|---|---|
| bizbuddy-mysql / redis / milvus / milvus-etcd | Up (healthy) |
| bizbuddy-mysql-minio（平台 OSS，Milvus 复用其 `milvus-bucket` 桶） | Up (healthy)；`minio-init` 一次性建桶 / 上传演示文档后退出 |
| bizbuddy-mysql-admin | Up，日志 `MysqlSkillRepository initialized with database: biz_buddy, skills table: ai_skill, resources table: ai_skill_resource`、`Started DromaraApplication in ~26s`，无 ERROR |
| bizbuddy-mysql-web | Up |

连通性：

| 检查 | 结果 |
|---|---|
| `GET http://localhost:81/`（前端） | 200 |
| `GET http://localhost:81/prod-api/auth/code`（Nginx → 后端代理） | 200 |
| `GET http://localhost:8081/auth/code`（后端直连） | 200 |
| 建库结果 | 75 张表（与 PostgreSQL 版一致）；`ai_*` 40 张；`sys_menu` BizBuddy 段 136 行（6 分组 / 34 功能页 / 95 按钮）；`ai_tool` 30、入口小Z 1、专家 4、专家工具绑定 25 行；`ai_model_config` **4**（`api_key` 留空） |
| 中文数据 | `sys_menu.menu_name='BizBuddy'`、`sys_user.nick_name='本部门及以下 密码666666'` 均完好（库字符集 `utf8mb4` / `utf8mb4_unicode_ci`） |
| 部分唯一索引 | `ai_tool.uk_ai_tool_code` 的表达式落库为 `if((del_flag = '0'),0,NULL)`，即 MySQL 「函数键部分」等价实现 |
| OSS 端点 | `sys_oss_config.endpoint = minio:9000` |
| 技能仓库装配 | 运行期按数据源选中 **MySQL** 实现（见 admin 日志），PostgreSQL 实现未被装配 |

## 5. 首次启动后必做：配置模型密钥

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

本次改造删除了全局 `bizbuddy.ai.rag.rerank-enabled` / `rerank-model` / `rerank-endpoint` 三项；重排模型改在「模型配置」登记为 `rerank` 类型，由「知识库」开启「检索重排」并选择模型（`ai_knowledge_base` 新增两列，列内联 comment 已在建表脚本里）。建表脚本已加这两列，但**建库脚本只挂进 `docker-entrypoint-initdb.d`、仅在数据卷为空（首次初始化）时执行，不会重跑**——**存量库必须手工迁移**：

```sql
alter table ai_knowledge_base add column rerank_enabled char(1) not null default '0';
alter table ai_knowledge_base add column rerank_model_id bigint(20);
```

### 升级须知（2026-10-19 渠道长连接）

渠道接入新增**飞书长连接**形态：`ai_channel` 新增 `mode` 列（`webhook` / `ws`，默认 `webhook`；仅飞书区分，须与飞书后台「事件订阅方式」一致）。建表脚本已含该列，但同样**只在空卷首次初始化时执行**——**存量库需手工迁移**（脚本内 `add_ai_channel_mode_column` 过程为幂等版；手工执行下列语句重复跑会报 `Duplicate column name 'mode'`，可忽略）：

```sql
alter table ai_channel add column mode varchar(16) not null default 'webhook' comment '接入形态：webhook Webhook 回调（默认，需平台公网可达） / ws 长连接（应用主动外连、免公网 IP；仅飞书区分）';
```

### 升级须知（2026-10-25 知识库混合检索）

知识库新增 `retrieval_mode`（`vector` 默认 / `hybrid` 向量 + BM25）与 `bm25_index_status` 两列。BM25 索引存 Milvus 独立集合 `<collection>_bm25`（原生 BM25 Function + 中文分词），**不落关系库**；切换到 `hybrid` 后平台异步重建，也可在知识库列表手动重建。建表脚本已含这两列，存量库需手工迁移（重复执行会报 `Duplicate column name`，可忽略）：

```sql
alter table ai_knowledge_base add column retrieval_mode varchar(16) not null default 'vector' comment '检索方式（vector 向量 / hybrid 向量+BM25）';
alter table ai_knowledge_base add column bm25_index_status varchar(16) not null default 'none' comment 'BM25 索引状态（none/building/ready/failed）';
```

## 6. 已知坑（与 PostgreSQL 版相同，附 MySQL 增量项）

**① 用 Docker Hub 拉 `bellsoft/liberica-openjdk-rocky` 反复失败**
现象：`short read: expected … bytes but got 0: unexpected EOF`。处置：走镜像源拉取后 **tag 回原名**：

```bash
docker pull docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds
docker tag  docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds \
            bellsoft/liberica-openjdk-rocky:21.0.12-cds
```

**② MinIO 镜像 tag 已消失**：`minio/minio:RELEASE.2024-05-28T17-19-04Z: not found`。本目录默认用仍可拉取的 release（平台 MinIO，Milvus 复用同一实例），并支持 `.env` 覆盖 `MINIO_IMAGE`。

**③ 构建上下文必须是模块目录**：Dockerfile 里是 `ADD ./target/xxx.jar`，`context` 必须是**该模块目录**，否则报 `not found`。

**④ 知识库/文件上传报 `Connection refused: /127.0.0.1:9000`**
原因：**平台文件存储（OSS）坐标存在数据库表 `sys_oss_config`**（框架种子写的是本地开发机的 `127.0.0.1:9000`）。本目录已备好两件事——① 平台 MinIO 服务 `minio`（9002/9003；**Milvus 复用同一实例**，向量数据放独立桶 `milvus-bucket`，与平台文件桶 `ruoyi` 分开）；② 一次性初始化 `minio-init` 建桶（**私有桶**，以 `mc anonymous set none` 取代 `mc anonymous set download`）。`deploy/deploy-mysql/sql/50-oss-endpoint.sql` 会在首次建库时把端点改成 `minio:9000`、`domain_url` 写成宿主机可达地址、`access_policy` 置 `0`（私有，文件访问走后端下发的预签名 URL）；**已初始化过的数据卷不会重跑**，需手动补一次：

```bash
docker exec -i bizbuddy-mysql mysql -uroot -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < deploy/deploy-mysql/sql/50-oss-endpoint.sql
docker compose restart ruoyi-admin      # 让 OSS 配置重新加载
```

MinIO 控制台：<http://localhost:9003>（`ruoyi` / `ruoyi123`）。

**⑤ MySQL 8 认证插件**
现象：后端报 `Public Key Retrieval is not allowed` 或 `Unable to load authentication plugin`。
原因：MySQL 8 默认 `caching_sha2_password`，JDBC 在非 SSL 连接下需显式允许取公钥。
处置：本目录已在 JDBC 参数里带 `allowPublicKeyRetrieval=true&useSSL=false`，无需额外处理。

**⑥ 上传成功，但图片 / 文档在页面上「加载失败」**
现象：后端能读到文件（模型识图、文档解析都正常），**浏览器却加载不出来**（聊天里的图片、知识库文档的下载 / 在线预览）。
原因：`sys_oss_config` 有两个坐标——`endpoint`（后端读写用，容器网络内的 `minio:9000`）与 `domain_url`（**返回给前端的对外地址**）。若只改了 `endpoint`，`domain_url` 仍是框架种子的 `127.0.0.1:9000`，浏览器解析不到。
处置：`50-oss-endpoint.sql` 已**同时**写入 `endpoint = minio:9000`、`domain_url = http://localhost:9002`（本栈平台 MinIO 映射在宿主机的 **9002**，与 PG 栈的 9000 错开；**2026-10-09 修正**——此前该脚本漏了 `domain_url`）与 `access_policy = 0`（**私有桶**，文件访问走后端下发的预签名 URL）。**存量数据卷不会重跑该脚本**，需手动补一次（见 ④ 的命令，脚本本身包含这三条 `update`：`endpoint` / `domain_url` / `access_policy`），随后 `docker compose restart ruoyi-admin`。
若改了 `MINIO_API_PORT`，或需从其它机器 / 公网访问，把 `domain_url` 改成实际可达的地址即可（后端返回的 URL 以此为准，无需改数据行）。
> 私有桶下 `domain_url` 是**硬前置**：预签名 URL 按它生成，留空或指向不可达地址会导致前端预览 / 下载失败（后端解析不受影响，它走 `endpoint`）。

## 7. 运维命令

```bash
docker compose ps                          # 状态
docker compose logs -f bizbuddy-mysql-admin # 后端日志
docker compose up -d --build               # 改了代码/配置后重建
docker compose restart ruoyi-admin         # 只重启后端
docker compose down                        # 停止（保留数据卷）
docker compose down -v                     # 停止并删除数据卷（下次启动会重新建库）
```

> 建库脚本**只在数据卷为空时执行**。改了口令/库名等变量要生效，需 `down -v` 重建。

> **重启策略**：只有 `ruoyi-admin` 为 `restart: on-failure:3`——配置不合格时应用会 fail-fast 退出，重试 3 次后容器停在 `exited`，日志里最后一次启动报错即根因（**这是设计如此，不是容器故障**）；基础设施与 nginx 为 `unless-stopped`，依赖抖动可自愈。

> 人工排查时用 `docker exec ... mysql` 查中文，请带 `--default-character-set=utf8mb4`：MySQL 官方镜像不带 locale，客户端会退回 latin1，中文会显示成 `?`（**读到的乱码不代表库里是乱码**，用 `hex()` 可验证）。建库脚本各自自带 `SET NAMES utf8mb4`，初始化不受此影响。
>
> **写入同理**：手工执行增量 SQL（`INSERT` / `UPDATE`，如升级须知里的 ALTER / 补菜单 / 订正数据）**必须先 `SET NAMES utf8mb4`**（或给客户端加 `--default-character-set=utf8mb4`），否则中文会按 latin1 二次编码**真的写坏**（读出为 `å¯¹è¯` 这类乱码，`hex()` 出现 `C3A5…` 之类特征）。排查：`where hex(列名) like '%C3A5%' …`。

## 8. 变量

见 [.env.example](.env.example)（端口、数据库/Redis 口令、Milvus 用户名口令、`MINIO_IMAGE`、`MYSQL_IMAGE`、`SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`）。
`SPRING_PROFILES_ACTIVE` 须为 `dev` 或 `prod`——决定后端加载的配置文件与日志级别。

## 9. 相关文档

- 部署总览（PostgreSQL 版，含更多背景说明）：[../deploy-pg/README.md](../deploy-pg/README.md)
- 后端配置项：[../../docs/configuration.md](../../docs/configuration.md)
- 架构与二次开发：[../../docs/README.md](../../docs/README.md)