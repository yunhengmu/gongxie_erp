<div align="center">

# BizBuddy

**企业级智能业务运行平台 · 源码仓库（后端 `RuoYi-Vue-Plus` + 前端 `plus-ui-Vue`）**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-success.svg)]()
[![JDK](https://img.shields.io/badge/JDK-21-green.svg)]()
[![AgentScope](https://img.shields.io/badge/AgentScope-2.0.4-blue.svg)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14-336791.svg)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg)]()
[![Vue](https://img.shields.io/badge/Frontend-Vue%203-42b883.svg)]()
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 平台简介

BizBuddy 是一个**企业级智能业务运行平台**：它在 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) `6.0.0` 的成熟中后台底座（RBAC、工作流、调度、代码生成、文件存储、消息推送等）之上，以 **AgentScope Java 2.0.4** 为智能体内核，叠加了一整套可运营的 AI 能力。

核心运行形态：

- **入口智能体「小Z」统一接待**——所有对话先进入入口智能体，由它判断意图并给出候选专家清单。
- **专家路由**——入口智能体按候选清单把任务**直接委派**给对应的领域专家；专家的回复**直接交付用户**（专家直答，小Z 只负责选谁）。
- **写操作人工确认（HITL）**——涉及写库 / 写外部系统的工具调用会暂停并请求人工确认，确认后才继续执行。
- **多渠道接入**——支持飞书 / 企业微信 / 钉钉 / GitHub Webhook 入站回调。
- **多模态对话**——消息可携带**图片与文档附件**（先上传为暂存件、发送时绑定消息）；图片按 Base64 下发给视觉模型，文档解析为纯文本内联，附件内容块**随任务片段一并交给专家**；
- **全链路审计留痕**——会话、路由、工具调用、确认与用量均有审计记录，可追溯。

## 仓库结构

| 目录 | 说明 |
|---|---|
| [`RuoYi-Vue-Plus/`](RuoYi-Vue-Plus) | **后端工程**：Spring Boot 4.1.1 + JDK 21，多模块 Maven；AI 能力在 `ruoyi-modules/biz-buddy`（AgentScope 2.0.4 内核） |
| [`plus-ui-Vue/`](plus-ui-Vue) | **前端工程**：Vue 3 + TypeScript + Element Plus + Vite，包管理 pnpm |
| [`docs/`](docs/README.md) | **开发者文档**：运行时架构、二次开发（新增业务工具）、配置项参考 |
| [`RuoYi-Vue-Plus/docs/`](RuoYi-Vue-Plus/docs) | 平台功能说明（含 9 张运行界面截图） |
| [`deploy/`](deploy) | **PostgreSQL 全栈容器编排**：PG 14 + Redis + Milvus + 后端 + 调度 + Nginx（`docker compose up -d --build` 即「有表有数据可登录」） |
| [`deploy-mysql/`](deploy-mysql) | **MySQL 8.0 全栈容器编排**：组件与 PG 版一致，**项目名与端口整体错开，两栈可同时运行** |
| [`e2e/`](e2e) | **端到端测试脚本**（Node.js 零依赖）：对**已运行**的栈发真实 HTTP 断言，见下文「测试与覆盖率」 |

## 功能说明

平台完整功能说明见 **[RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md)**，按真实菜单与代码梳理，涵盖：

- **功能地图与权限模型**——5 个分组、26 个功能页与权限标识约定、工具三档授权；
- **智能体与对话**——任务工作台、入口智能体「小Z」配置、专家配置（含提示词 **AI 辅助生成 / 模板套用 / 克隆专家 / 保存前体检**）、任务管理、定时任务、计划模式、技能仓库、长期记忆、工作区文件、子智能体、场景包；
- **模型与推理 / 知识库与检索**——模型配置与连通性测试、用量与配额、上下文压缩策略；知识库、文档与切片、检索测试；
- **工具与集成**——业务工具注册、MCP 服务端（对外出口）与客户端（接入外部）、飞书 / 企业微信 / 钉钉 / GitHub 渠道接入；
- **审计与观测**——会话与消息、工具调用、权限决策、链路追踪、编排运行五类留痕；
- **配置项与运行依赖**——`bizbuddy.ai` 配置清单与依赖组件。

## 技术栈

### 后端

| 分类 | 组件 | 版本 |
|---|---|---|
| 语言 / 运行时 | Java | 21 |
| 框架 | Spring Boot | 4.1.1 |
| Web 容器 | Jetty（基于 Netty） | 随 Boot |
| **智能体内核** | **AgentScope Harness** | **2.0.4** |
| MCP 底座 | 官方 MCP Java SDK | 0.17.2 |
| 向量库 | Milvus（经 AgentScope `MilvusStore` 接入） | v2.6.13 |
| 权限认证 | Sa-Token | 1.46.0 |
| 三方登录 | JustAuth | 3.0.1 |
| ORM | MyBatis-Plus / MyBatis | 3.5.17 / 3.5.19 |
| 多数据源 | dynamic-datasource | 4.5.0 |
| 缓存 / 客户端 | Redisson | 4.7.0 |
| 分布式锁 | Lock4j | 2.2.7 |
| 定时调度 | SnailJob | 2.0.2 |
| 工作流引擎 | Warm-Flow | 1.8.9 |
| 规则编排 | LiteFlow | 2.16.2.1 |
| 接口文档 | SpringDoc + therapi-runtime-javadoc | 3.1.1 / 0.15.0 |
| 对象映射 | MapStruct-Plus | 1.5.3 |
| 工具库 | Hutool / Lombok | 5.8.47 / 1.18.48 |
| 对象存储 | AWS SDK for Java v2 (S3) | 2.55.4 |
| 监控 | Spring Boot Admin | 4.1.2 |

### 前端

Vue 3.5 + TypeScript 6 + Element Plus 2.14 + Vite 8 + Pinia + vue-router，包管理使用 pnpm（Node ≥ 20.19.0）。详见 [`plus-ui-Vue/README.md`](plus-ui-Vue/README.md)。

## 后端模块结构

```
RuoYi-Vue-Plus
├── ruoyi-admin          # Web 服务入口（启动类 org.dromara.DromaraApplication）
├── ruoyi-common         # 通用能力（20 余个子模块，BOM 统一版本）
├── ruoyi-api            # 跨模块调用契约（Service 接口 + DTO）
├── ruoyi-modules        # 业务模块
│   ├── ruoyi-system     # 系统管理（RBAC、字典、日志、OSS、消息）
│   ├── ruoyi-workflow   # 工作流（Warm-Flow + LiteFlow）
│   ├── ruoyi-job        # 调度任务实现（@JobExecutor 处理器）
│   ├── ruoyi-gen        # 代码生成器
│   ├── ruoyi-demo       # 框架能力示例
│   └── biz-buddy        # BizBuddy AI 能力模块（AgentScope 智能体宿主）
└── ruoyi-extend         # 扩展服务（monitor-admin 监控端、snailjob-server 调度端）
```

### biz-buddy 模块

`biz-buddy` 是本项目在框架底座之上叠加的 AI 能力主体，包结构为 `org.dromara.ai`：

| 包 | 职责 |
|---|---|
| `core/agent` | 智能体注册与装配、路由编排（入口智能体 → 专家）、权限决策、流式事件桥、工具工厂 |
| `core/agent/tools` | 内置工具集：部门 / 用户 / 公告 / 角色 / 权限的查询与写入、知识库检索、当前时间等 |
| `core/mcp` | 自建 MCP 出口与 MCP 客户端封装（含访问口令校验、工具名归一化） |
| `core/channel` | 多渠道入站（飞书 / 企业微信 / 钉钉 / GitHub）与运行时注册 |
| `controller` / `service` | 模型配置、智能体、会话、路由、知识库、文档、MCP、渠道、审计、用量、配额、技能、记忆、场景包等管理接口 |
| `job` | 定时 Agent 任务执行器（由 SnailJob 调度） |

关键机制：

- **入口智能体「小Z」+ 专家路由**——入口智能体判断意图并给出候选专家，随后把任务直接委派给对应专家；
- **专家直答**——专家回复直接交付用户，入口智能体只负责选择专家；
- **写操作人工确认（HITL）**——涉及写库 / 写外部系统的工具调用暂停待确认，确认后才执行；专家链路内置写工具短期去重，避免重复副作用；
- **统一配置**——AI 平台配置前缀 `bizbuddy.ai`，绑定 [`AiProperties`](RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/config/AiProperties.java)。

## 一键运行（Docker Compose）

不想手工建库、装中间件，用容器拉起**开箱可登录**的完整环境。仓库提供两套编排，**组件一致、仅数据库方言不同**，且**项目名与端口全部错开，可以同时运行**：

| | PostgreSQL 栈 | MySQL 8.0 栈 |
|---|---|---|
| 目录 | [`deploy/`](deploy) | [`deploy-mysql/`](deploy-mysql) |
| Compose 项目名 | `bizbuddy` | `bizbuddy-mysql` |
| 前端 / 后端 / 调度控制台 | `80` / `8080` / `8800` | `81` / `8081` / `8801` |
| 数据库 / Redis / Milvus | `5432` / `6379` / `19530` | `3306` / `6380` / `19531` |
| 平台 MinIO（控制台） | `9000`（`9001`） | `9002`（`9003`） |

`up` 起来的是：数据库 + Redis + Milvus（standalone，含 etcd / minio）+ 后端 `ruoyi-admin` + 调度服务端 `snailjob-server` + Nginx（托管前端 `dist`）+ 平台 MinIO（一次性 `minio-init` 建桶并上传演示文档）。

### 1. 前置：先在宿主机构建三个产物

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

### 2. 启动 PostgreSQL 栈

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

### 3. 启动 MySQL 8.0 栈

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

### 4. 首次启动后必做：补模型密钥

建库脚本预置了两个**示例模型**（阿里云百炼 / DashScope，走 OpenAI 兼容协议），但 `api_key` **一律留空**：

| 配置名称 | 类型 | 说明 |
|---|---|---|
| `deepseek-v4-flash-0731` | 对话 | 入口智能体「小Z」与内置专家的模型槽位**已指向它** |
| `qwen3.7-text-embedding` | 向量 | 维度按 `1024` 预置，须与模型实际输出一致 |

登录后进「模型配置」，把这两个模型（或你另行新建的模型）的 API Key 换成自己的密钥即可——**不补则对话与向量化不可用**。**两栈的模型配置在各栈自己的库里、互不相通，需分别补一次。**

### 5. 起来就有什么

- 库表与初始数据全部就绪（含 `ai_*` 表与 AI 菜单的 26 个功能页），`admin` 等初始账号在位；
- 内置智能体：入口智能体「小Z」1 个 + 专家 3 个（机构管理、公告管理，以及演示专家「BizBuddy项目介绍专家」）；
- 演示知识库一套：知识库 `bizbuddy_demo_kb`（BizBuddy介绍演示库）+ 文档「BizBuddy简介.txt」+ 已绑定检索工具的演示专家；
- **演示文档需先「重新解析」再「向量化」**：种子只写入正文、不含切片，故初值是「未解析」——按提示点一次「重新解析」即可（这是刻意的状态一致，不是缺陷）。

### 6. 常用运维

```bash
docker compose ps                        # 状态
docker compose logs -f ruoyi-admin       # 跟踪后端日志（服务名两栈通用）
docker compose up -d --build             # 改了代码 / 配置后重建
docker compose restart ruoyi-admin       # 只重启后端（如改完 OSS 配置）
docker compose down                      # 停止（保留数据卷）
docker compose down -v                   # 停止并删数据卷：下次启动会重新建库并重写种子
```

### 7. 验证「真的跑通了」

栈起来后可跑端到端脚本，对**真实运行**的环境做断言（模型连通 / 知识库检索 / 专家编排 / 写工具确认闭环）：

```bash
node e2e/run.mjs                                   # PostgreSQL 栈（后端默认 8080）
node e2e/run.mjs --base-url http://localhost:8081  # MySQL 栈
```

> 前置条件与排障：PostgreSQL 栈见 [`deploy/README.md`](deploy/README.md)，MySQL 栈见 [`deploy-mysql/README.md`](deploy-mysql/README.md)（含 OSS 对外地址、MySQL 认证插件、端口改动等已知坑）。

## 快速开始

> 本节是**从源码手工跑**的步骤（自行准备 PostgreSQL / Redis / Milvus、手工建库）。想省掉这些，用上面的[一键运行（Docker Compose）](#一键运行docker-compose)。

### 1. 环境依赖

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 21（推荐 OpenJDK，**不要使用 OracleJDK**） |
| Maven | ≥ 3.8.x（仓库自带 `mvnw` / `mvnw.cmd`） |
| 关系库 | PostgreSQL 14（默认）或 MySQL 8.0，库名均为 `biz_buddy` |
| Redis | ≥ 6.x（缓存、分布式锁、AgentScope 分布式状态） |
| Milvus | v2.6.13，gRPC `19530`（知识库向量检索） |
| Node / pnpm | Node ≥ 20.19.0，pnpm ≥ 10 |

### 2. 初始化数据库

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

> Oracle / SQLServer 方言的历史脚本仍保留在仓库中，但**不维护**。两套编排见 [`deploy/`](deploy)（PostgreSQL 版）与 [`deploy-mysql/`](deploy-mysql)（MySQL 8.0 版），可按需二选一或同时运行（端口已错开）。
> 数据库差异只影响两处，均已在代码内按数据源自动处理，无需改配置：① JDBC 驱动（`postgresql` / `mysql-connector-j` 均在位）；② 技能持久化仓库（`PostgresSkillRepository` / `MysqlSkillRepository`，见 `AgentScopeConfig#aiSkillRepository`）。

### 3. 启动后端

```bash
cd RuoYi-Vue-Plus

# 构建（Windows；Linux / macOS 用 ./mvnw）
./mvnw.cmd clean install -DskipTests

# 启动 ruoyi-admin（默认端口 8080）
./mvnw.cmd -pl ruoyi-admin spring-boot:run
```

或在 IDE 中直接运行 `ruoyi-admin` 模块的 [`org.dromara.DromaraApplication`](RuoYi-Vue-Plus/ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java)。

- 默认激活 `dev` 环境（见 `pom.xml` 的 `<profiles>`），接口文档地址：`http://localhost:8080/swagger-ui.html`
- 初始账号：`admin` / `admin123`
- 监控中心 `ruoyi-monitor-admin`（`9090`）、调度服务端 `ruoyi-snailjob-server`（`17888` / 控制台 `8800`）按需单独启动

### 4. 启动前端

```bash
cd plus-ui-Vue

pnpm install
pnpm dev          # 开发模式，默认端口 80
pnpm build:prod   # 生产构建
```

## 配置说明

### 后端（`RuoYi-Vue-Plus/ruoyi-admin/src/main/resources/`）

- `application.yml` —— 通用配置：端口 `8080`、Sa-Token、MyBatis-Plus、接口加解密、统一消息推送、Warm-Flow、LiteFlow、MQTT、Easy-Es 等
- `application-dev.yml` / `application-prod.yml` —— 环境配置：数据源、SnailJob 客户端、SQL 日志等

数据源（`application-dev.yml`）：

```yaml
spring:
  datasource:
    dynamic:
      datasource:
        master:
          # 不声明驱动：Hikari 依 JDBC URL 自动匹配 classpath 中的驱动
          url: jdbc:postgresql://localhost:5432/biz_buddy?...
          username: postgres
          password: <你的密码>
```

> 换 MySQL 8.0 只需把 `url` 换成 `jdbc:mysql://localhost:3306/biz_buddy?...`（带 `allowPublicKeyRetrieval=true&useSSL=false`），无需改驱动声明。

AI 平台配置前缀为 `bizbuddy.ai`（见 [`AiProperties`](RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/config/AiProperties.java)）：模型密钥加密 Key、Milvus 连接、对话迭代上限、专家调用超时与 Token / 墙钟预算、写工具去重、上下文压缩、工作区与长期记忆开关、MCP 出口口令等。

### 前端（`plus-ui-Vue/.env.*`）

| 变量 | 开发 | 生产 |
|---|---|---|
| `VITE_APP_TITLE` / `VITE_APP_LOGO_TITLE` | `BizBuddy` | `BizBuddy` |
| `VITE_APP_BASE_API` | `/dev-api` | `/prod-api` |
| `VITE_APP_PORT` | `80` | `80` |
| `VITE_APP_ENCRYPT` | `true` | `true` |

## 测试与覆盖率

AI 模块（`biz-buddy`）是本项目的测试主力，覆盖工具、智能体装配、路由编排、权限决策、渠道路由、MCP、各 Service 与 Controller：

```bash
# 在 RuoYi-Vue-Plus 目录下，仅跑 AI 模块
./mvnw.cmd -o -pl ruoyi-modules/biz-buddy test
```

JaCoCo 覆盖率报告（构建后生成）：

```
RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/target/site/jacoco/index.html   # HTML 报告
RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/target/site/jacoco/jacoco.csv   # 机器可读
```

### 端到端测试（e2e）

单测证明「代码分支正确」，但不证明「跑起来能用」——模型是否真的连通、知识库是否真的检索得到、小Z 是否真的把问题路由给专家、写工具的人工确认闭环是否真的通，都需要在**真实运行**的栈上验证。`e2e/` 为此提供一套**外部驱动脚本**（Node.js 纯 ESM、**零 npm 依赖**），对**已运行**的容器栈发真实 HTTP 请求做断言：

| 层 | 覆盖 |
|---|---|
| L1 | 模型连通性（对话 + 向量） |
| L2 | 专家提示词辅助生成（`POST /ai/agent/experts/prompt/generate`） |
| L3 | 知识库链路：文档解析（按对象键从 OSS 取源文件）→ 向量化 → 检索命中 |
| L4 | 对话与专家编排：建会话（指定专家强制路由）→ SSE 流 → 编排轨迹落库 |
| L5 | HITL：写工具暂停 → 确认 / 拒绝 → 恢复执行至 `done` |

```bash
# 需要栈已在运行（deploy/ 或 deploy-mysql/ 任一）
node e2e/run.mjs                          # 全量；未配置密钥的层自动 SKIP
node e2e/run.mjs --only L1,L3 --verbose    # 指定层级
node e2e/run.mjs --allow-writes            # L5 走「接受」路径（会真建公告，收尾自动清理）
node e2e/run.mjs --base-url http://localhost:8081   # 指向 MySQL 栈（PG 栈为 8080）
```

- 配置：复制 [`e2e/.env.example`](e2e/.env.example) 为 `e2e/.env.local`（已 gitignore）；唯一机密 `BIZBUDDY_LLM_API_KEY` **仅 L1 需要**（作为瞬时入参下发、不落库），L2~L5 走平台「模型配置」里已保存的密钥。
- 退出码：`0` 无失败（含 SKIP）/ `1` 有 FAIL / `2` 前置检查失败 / `3` 参数错误。
- **L5 默认走「拒绝」路径、不产生任何业务数据**；`--allow-writes` 才真写，并在收尾按标题清理公告与会话。
- 它**不进 `mvn test`**、不引 Testcontainers，与上面的单测**互不影响**；两个数据库栈均已实跑通过（PostgreSQL `8080` / MySQL `8081`）。

## 文档

| 文档 | 位置 |
|---|---|
| **开发者文档**（架构 / 二次开发 / 配置） | [`docs/README.md`](docs/README.md) |
| 平台功能说明（含界面截图） | [`RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md`](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md) |
| 前端工程说明 | [`plus-ui-Vue/README.md`](plus-ui-Vue/README.md) |

## 联系我们

微信公众号：**BizBuddy**

![微信公众号 BizBuddy 二维码](docs/weixin.jpg)

## 版权

Copyright © 2026 [AI架构师张磊](https://space.bilibili.com/482393709) All Rights Reserved.

后端底座源自 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)（MIT License），本仓库在其基础上进行 BizBuddy 平台化改造。