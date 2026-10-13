# 一键部署（MySQL 8.0 版）

用 Docker Compose 拉起 BizBuddy 全栈：**MySQL 8.0 + Redis + Milvus standalone（etcd / minio / milvus）+ 后端 + 调度服务端 + Nginx（前端）**。

> 与 [`deploy-pg/`](../deploy-pg)（PostgreSQL 版）是**并列的两套编排**，技术栈差异只在数据库（及随之为其准备的 JDBC 驱动 / 技能仓库实现），其余组件一致。
> 本项目已同时支持 PostgreSQL 与 MySQL 8.0：**技能仓库按数据源类型自动装配**（`AgentScopeConfig#aiSkillRepository`，PostgreSQL 版 `PostgresSkillRepository` / MySQL 版 `MysqlSkillRepository`），无需改代码。
> 两套编排刻意在**项目名、容器名、对外端口**上区分，因此可以**同时运行、互不干扰**（便于对照验证）。

## 1. 与 PostgreSQL 版的三点差异

| 项 | `deploy-pg/`（PG） | `deploy-mysql/`（MySQL） |
|---|---|---|
| 数据库镜像 | `postgres:14` | `mysql:8.0` |
| 建库脚本 | `script/sql/postgres/*.sql` | `script/sql/*.sql`（MySQL 方言，含 `ry_ai.sql`） |
| compose 项目名 | `bizbuddy` | `bizbuddy-mysql` |
| 默认端口 | 80 / 8080 / 8800 / 17888 / 5432 / 6379 / 19530 / 9000 / 9001 | **81 / 8081 / 8801 / 17889 / 3306 / 6380 / 19531 / 9002 / 9003** |

> MySQL 8 的部分唯一索引：PostgreSQL 脚本用 `WHERE del_flag='0'` 的部分唯一索引，MySQL 不支持该语法，`ry_ai.sql` 用 MySQL 8.0 的**函数键部分** `(if(del_flag='0', 0, null))` 等价实现（未删除行取 0 参与唯一约束，已删除行取 NULL，而 MySQL 唯一索引允许多个 NULL）。

## 2. 前置：先构建两个产物

项目自带的 Dockerfile 用的是 `ADD ./target/xxx.jar`，即**镜像里只有 jar，不在镜像内编译**。所以启动前必须先在宿主机构建：

```bash
# 后端 jar（ruoyi-admin/target/ruoyi-admin.jar）
cd RuoYi-Vue-Plus
./mvnw clean package -DskipTests          # Windows: .\mvnw.cmd clean package -DskipTests

# 调度服务端 jar（ruoyi-extend/ruoyi-snailjob-server/target/ruoyi-snailjob-server.jar）
./mvnw -o -pl ruoyi-extend/ruoyi-snailjob-server -am package -DskipTests

# 前端静态资源（plus-ui-Vue/dist，由 Nginx 直接托管）
cd ../plus-ui-Vue
pnpm install && pnpm build:prod
```

> 若只调 MySQL/Redis 等中间件，可跳过构建，只 `up` 对应服务（如 `docker compose up -d mysql`）。

## 3. 启动

```bash
cd deploy-mysql
cp .env.example .env        # 可选：不复制则用文件内默认值
docker compose up -d --build
```

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost:81/> |
| 后端接口文档 | <http://localhost:8081/swagger-ui.html> |
| 调度控制台 | <http://localhost:8801/snail-job> |
| 初始账号 | `admin / admin123`（另：`test / 666666`、`test1 / 666666`） |

## 4. 实测结论（2026-10-08，WSL2 + Docker 29.3 / Compose v5.1.1）

`docker compose up -d --build` 后：

| 容器 | 状态 |
|---|---|
| bizbuddy-mysql / redis / milvus / milvus-etcd / milvus-minio | Up (healthy) |
| bizbuddy-mysql-admin | Up，日志 `MysqlSkillRepository initialized with database: biz_buddy, skills table: ai_skill, resources table: ai_skill_resource`、`Started DromaraApplication in ~26s`，无 ERROR |
| bizbuddy-mysql-snailjob-server | Up，Tomcat 8800、上下文 `/snail-job`，无 ERROR |
| bizbuddy-mysql-web | Up |

连通性：

| 检查 | 结果 |
|---|---|
| `GET http://localhost:81/`（前端） | 200 |
| `GET http://localhost:81/prod-api/auth/code`（Nginx → 后端代理） | 200 |
| `GET http://localhost:8081/auth/code`（后端直连） | 200 |
| `GET http://localhost:8801/snail-job/`（调度控制台） | 200 |
| 建库结果 | 97 张表（与 PostgreSQL 版一致）；`ai_*` 39 张；`sys_menu` BizBuddy 段 123 行（6 分组 / 30 功能页 / 86 按钮）；`ai_tool` 26、入口小Z 1、专家 3、专家工具绑定 21 行；`ai_model_config` 2（`api_key` 留空）；`sj_job` 已有派发任务 |
| 中文数据 | `sys_menu.menu_name='BizBuddy'`、`sys_user.nick_name='本部门及以下 密码666666'` 均完好（库字符集 `utf8mb4` / `utf8mb4_unicode_ci`） |
| 部分唯一索引 | `ai_tool.uk_ai_tool_code` 的表达式落库为 `if((del_flag = '0'),0,NULL)`，即 MySQL 「函数键部分」等价实现 |
| OSS 端点 | `sys_oss_config.endpoint = minio:9000` |
| 技能仓库装配 | 运行期按数据源选中 **MySQL** 实现（见 admin 日志），PostgreSQL 实现未被装配 |

## 5. 首次启动后必做：配置模型密钥

建库脚本已预置两个**示例模型**（提供商：阿里云百炼 / DashScope，走 OpenAI 兼容协议），但 **`api_key` 一律留空**，需自行补齐：

| 配置名称 | 类型 | 说明 |
|---|---|---|
| `deepseek-v4.1-flash` | 对话 | 大语言模型 |
| `qwen3.7-text-embedding-flash` | 向量 | 维度按 `1024` 预置，须与模型实际输出一致 |

1. 登录 →「模型配置」把这两个模型（或你另行新建的模型）的 **API Key** 换成自己的阿里云百炼密钥 —— 换完即可用，无需其它配置；
2. 内置智能体（小Z 与两个专家）的模型槽位**已指向** `deepseek-v4.1-flash`；若要换模型，到「小Z 配置」「专家配置」改指即可；
3. 要用知识库检索，向量模型须选上表中的 embedding 模型（维度与知识库一致）。

> 智能体只绑定了模型，未绑知识库 / 技能；若要换用其它厂商或模型，在「模型配置」页新建即可（提供商选对应项，`base_url` 填其 OpenAI 兼容根地址）。

## 6. 已知坑（与 PostgreSQL 版相同，附 MySQL 增量项）

**① 用 Docker Hub 拉 `bellsoft/liberica-openjdk-rocky` 反复失败**
现象：`short read: expected … bytes but got 0: unexpected EOF`。处置：走镜像源拉取后 **tag 回原名**：

```bash
docker pull docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds
docker tag  docker.m.daocloud.io/bellsoft/liberica-openjdk-rocky:21.0.12-cds \
            bellsoft/liberica-openjdk-rocky:21.0.12-cds
```

**② MinIO 镜像 tag 已消失**：`minio/minio:RELEASE.2024-05-28T17-19-04Z: not found`。本目录已改用仍可拉取的 release，并支持 `.env` 覆盖 `MINIO_IMAGE`。

**③ 构建上下文必须是模块目录**：Dockerfile 里是 `ADD ./target/xxx.jar`，`context` 必须是**该模块目录**，否则报 `not found`。

**④ 初始化脚本顺序：AI 依赖调度**
现象：容器 healthy，但日志里有 `Table 'sj_job' doesn't exist`。
原因：`ry_ai.sql` 末尾要向 `sj_job` 登记「定时 Agent 任务派发」，依赖 `ry_job.sql`。
**关键提醒：初始化期间单条语句失败不会阻断启动，容器照样 healthy —— 故障是静默的**，务必核对日志：

```bash
docker logs bizbuddy-mysql 2>&1 | grep -iE 'docker-entrypoint|ERROR'
```

**⑤ 环境变量名撞键：`SNAILJOB_HOST` ≠ `snail-job.server.host`**
现象：后端日志 `Snail-Job 客户端IP为: snailjob-server`，调度端刷 `GrpcClientInvokeHandlerV2` 报错。
原因：Spring 松散绑定下 `SNAILJOB_HOST` 落到 **`snail-job.host`（客户端自己的 IP）**。本目录已改用 `SNAILJOB_SERVER_HOST`。

**⑥ 知识库/文件上传报 `Connection refused: /127.0.0.1:9000`**
原因：**平台文件存储（OSS）坐标存在数据库表 `sys_oss_config`**（框架种子写的是本地开发机的 `127.0.0.1:9000`）。本目录已备好两件事——① 平台**专用** MinIO 服务 `minio`（9002/9003）；② 一次性初始化 `minio-init` 建桶（**私有桶**，以 `mc anonymous set none` 取代 `mc anonymous set download`）。`deploy-mysql/sql/50-oss-endpoint.sql` 会在首次建库时把端点改成 `minio:9000`、`domain_url` 写成宿主机可达地址、`access_policy` 置 `0`（私有，文件访问走后端下发的预签名 URL）；**已初始化过的数据卷不会重跑**，需手动补一次：

```bash
docker exec -i bizbuddy-mysql mysql -uroot -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < deploy-mysql/sql/50-oss-endpoint.sql
docker compose restart ruoyi-admin      # 让 OSS 配置重新加载
```

MinIO 控制台：<http://localhost:9003>（`ruoyi` / `ruoyi123`）。

**⑦ MySQL 8 认证插件**
现象：后端报 `Public Key Retrieval is not allowed` 或 `Unable to load authentication plugin`。
原因：MySQL 8 默认 `caching_sha2_password`，JDBC 在非 SSL 连接下需显式允许取公钥。
处置：本目录已在 JDBC 参数里带 `allowPublicKeyRetrieval=true&useSSL=false`，无需额外处理。

**⑧ 上传成功，但图片 / 文档在页面上「加载失败」**
现象：后端能读到文件（模型识图、文档解析都正常），**浏览器却加载不出来**（聊天里的图片、知识库文档的下载 / 在线预览）。
原因：`sys_oss_config` 有两个坐标——`endpoint`（后端读写用，容器网络内的 `minio:9000`）与 `domain_url`（**返回给前端的对外地址**）。若只改了 `endpoint`，`domain_url` 仍是框架种子的 `127.0.0.1:9000`，浏览器解析不到。
处置：`50-oss-endpoint.sql` 已**同时**写入 `endpoint = minio:9000`、`domain_url = http://localhost:9002`（本栈平台 MinIO 映射在宿主机的 **9002**，与 PG 栈的 9000 错开；**2026-10-09 修正**——此前该脚本漏了 `domain_url`）与 `access_policy = 0`（**私有桶**，文件访问走后端下发的预签名 URL）。**存量数据卷不会重跑该脚本**，需手动补一次（见 ⑥ 的命令，脚本本身包含这三条 `update`：`endpoint` / `domain_url` / `access_policy`），随后 `docker compose restart ruoyi-admin`。
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

> 人工排查时用 `docker exec ... mysql` 查中文，请带 `--default-character-set=utf8mb4`：MySQL 官方镜像不带 locale，客户端会退回 latin1，中文会显示成 `?`（**数据本身是正确的 utf8mb4**，用 `hex()` 可验证）。建库脚本各自自带 `SET NAMES utf8mb4`，初始化不受此影响。

## 8. 变量

见 [.env.example](.env.example)（端口、数据库/Redis 口令、Milvus 用户名口令、`MINIO_IMAGE`、`MYSQL_IMAGE`、`SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`）。
`SPRING_PROFILES_ACTIVE` 须为 `dev` 或 `prod`——建库脚本只创建这两个 SnailJob 命名空间。

## 9. 相关文档

- 部署总览（PostgreSQL 版，含更多背景说明）：[../deploy-pg/README.md](../deploy-pg/README.md)
- 后端配置项：[../RuoYi-Vue-Plus/docs/configuration.md](../RuoYi-Vue-Plus/docs/configuration.md)
- 架构与二次开发：[../RuoYi-Vue-Plus/docs/README.md](../RuoYi-Vue-Plus/docs/README.md)