# 一键部署（PostgreSQL 版）

用 Docker Compose 拉起 BizBuddy 全栈：**PostgreSQL 14 + Redis + Milvus standalone（etcd / minio / milvus）+ 后端 + 调度服务端 + Nginx（前端）**。

> 与 `RuoYi-Vue-Plus/script/docker/docker-compose.yml` 的区别：那个是上游 RuoYi 模板（MySQL、无 Milvus、直接用成品镜像）；本目录针对本项目的**真实技术栈**，并把建库脚本按依赖顺序挂进 PostgreSQL 的初始化目录，`up` 完即是「有表、有数据、可登录」的环境。

## 1. 前置：先构建两个产物

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

> 若只调 Redis/PostgreSQL 等中间件，可跳过构建，只 `up` 对应服务（如 `docker compose up -d postgres`）。

## 2. 启动

```bash
cd deploy
cp .env.example .env        # 可选：不复制则用文件内默认值
docker compose up -d --build
```

| 访问 | 地址 |
|---|---|
| 前端 | <http://localhost/> |
| 后端接口文档 | <http://localhost:8080/swagger-ui.html> |
| 调度控制台 | <http://localhost:8800/snail-job> |
| 初始账号 | `admin / admin123`（另：`test / 666666`、`test1 / 666666`） |

## 3. 首次启动后必做：配置模型

建库脚本**不含 `ai_model_config` 种子**（种子里的 `model_id` 是原环境的值）。所以干净库起来后 AI 对话不可用：

1. 登录 →「模型配置」新增一个**对话模型**（OpenAI 兼容地址 + API Key）；
2. 到「小Z 配置」「专家配置」把模型槽位指向它；
3. 要用知识库检索，再配一个 **embedding 模型**。

## 4. 实测结论（2026-10-07，WSL2 + Docker 29.3 / Compose v5.1.1）

`docker compose up -d --build` 后：

| 容器 | 状态 |
|---|---|
| bizbuddy-postgres / redis / milvus / milvus-etcd / milvus-minio | Up (healthy) |
| bizbuddy-admin | Up，日志 `Started DromaraApplication in ~25s`，无 ERROR |
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
| 建库结果 | 94 张基础表（`ai_*` 36 / `sj_*` 23 / `sys_*` 20 / `flow_*` 10 等）；`admin` 等初始账号在位；`ai_tool` 25、入口小Z 1、专家 2；`sj_job` 已有派发任务 |

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
现象：`minio/minio:RELEASE.2024-05-28T17-19-04Z: not found`。MinIO 官方已停止通过 Docker Hub 发布，Milvus 官方编排里钉的旧 tag 拉不到。
处置：本目录已改用仍可拉取的 release，并支持覆盖（`.env`）：`MINIO_IMAGE=…`，必要时用社区 fork `pgsty/minio:…`。

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

## 7. 变量

见 [.env.example](.env.example)（端口、数据库/Redis 口令、Milvus 用户名口令、`MINIO_IMAGE`、`SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`）。
`SPRING_PROFILES_ACTIVE` 须为 `dev` 或 `prod`——建库脚本只创建这两个 SnailJob 命名空间。

## 8. 相关文档

- 后端配置项：[../docs/configuration.md](../docs/configuration.md)
- 架构与二次开发：[../docs/README.md](../docs/README.md)