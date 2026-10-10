# 快速开始（从源码手工跑）

> 本节是**从源码手工跑**的步骤（自行准备 PostgreSQL / Redis / Milvus、手工建库）。
> 想省掉这些，用 [docker-compose.md](docker-compose.md)（容器一键拉起开箱可登录的完整环境）。

## 1. 环境依赖

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 21（推荐 OpenJDK，**不要使用 OracleJDK**） |
| Maven | ≥ 3.8.x（仓库自带 `mvnw` / `mvnw.cmd`） |
| 关系库 | PostgreSQL 14（默认）或 MySQL 8.0，库名均为 `biz_buddy` |
| Redis | ≥ 6.x（缓存、分布式锁、AgentScope 分布式状态） |
| Milvus | v2.6.13，gRPC `19530`（知识库向量检索） |
| Node / pnpm | Node ≥ 20.19.0，pnpm ≥ 10 |

## 2. 初始化数据库

先建库 `biz_buddy`，再按顺序导入对应方言的脚本（**PostgreSQL 为默认，MySQL 8.0 为并列支持**）：

PostgreSQL（`RuoYi-Vue-Plus/script/sql/postgres/`）：

```
postgres_ry_vue.sql        # 框架基础表 + 系统管理数据（含菜单、字典、初始账号）
postgres_ry_workflow.sql   # 工作流（Warm-Flow）表
postgres_ry_job.sql        # 调度任务（SnailJob）表：sj_*
postgres_ry_ai.sql         # AI 平台表（ai_* 表结构 + 种子数据）
```

MySQL 8.0（`RuoYi-Vue-Plus/script/sql/`）：

```
ry_vue.sql        # 框架基础表 + 系统管理数据（含菜单、字典、初始账号）
ry_workflow.sql   # 工作流（Warm-Flow）表
ry_job.sql        # 调度任务（SnailJob）表：sj_*
ry_ai.sql         # AI 平台表（ai_* 表结构 + 种子数据）
```

> 顺序要求：`*_ry_ai.sql` **依赖** `*_ry_job.sql` —— 它末尾要向 `sj_job` 登记「定时 Agent 任务派发」。若先执行 AI 脚本会报 `relation "sj_job" does not exist`（PostgreSQL）/ `Table 'sj_job' doesn't exist`（MySQL）。

> **脚本是「当前状态基线」，只在空库执行**：面向全新数据库一次性建好全部对象（按对象组织，不含历史增量段），重复执行幂等。已运行的存量库若结构落后，需按对应版本的变更说明手工补 DDL（例如新增列）。
> Oracle / SQLServer 方言的历史脚本仍保留在仓库中，但**不维护**。两套编排见 [`../deploy/`](../deploy)（PostgreSQL 版）与 [`../deploy-mysql/`](../deploy-mysql)（MySQL 8.0 版），可按需二选一或同时运行（端口已错开）。
> 数据库差异只影响两处，均已在代码内按数据源自动处理，无需改配置：① JDBC 驱动（`postgresql` / `mysql-connector-j` 均在位）；② 技能持久化仓库（`PostgresSkillRepository` / `MysqlSkillRepository`，见 `AgentScopeConfig#aiSkillRepository`）。

## 3. 启动后端

```bash
cd RuoYi-Vue-Plus

# 构建（Windows；Linux / macOS 用 ./mvnw）
./mvnw.cmd clean install -DskipTests

# 启动 ruoyi-admin（默认端口 8080）
./mvnw.cmd -pl ruoyi-admin spring-boot:run
```

或在 IDE 中直接运行 `ruoyi-admin` 模块的 [`org.dromara.DromaraApplication`](../RuoYi-Vue-Plus/ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java)。

- 默认激活 `dev` 环境（见 `pom.xml` 的 `<profiles>`），接口文档地址：`http://localhost:8080/swagger-ui.html`
- 初始账号：`admin` / `admin123`
- 监控中心 `ruoyi-monitor-admin`（`9090`）、调度服务端 `ruoyi-snailjob-server`（`17888` / 控制台 `8800`）按需单独启动

## 4. 启动前端

```bash
cd plus-ui-Vue

pnpm install
pnpm dev          # 开发模式，默认端口 80
pnpm build:prod   # 生产构建
```

> 连接坐标（后端地址、端口、客户端 id 等）见 [configuration.md](configuration.md) 与 `plus-ui-Vue/.env.*`。