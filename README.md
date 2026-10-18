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
| [`docs/`](docs/README.md) | **开发者文档**：一键运行（Docker Compose）与快速开始（源码手工跑）、运行时架构、二次开发（新增业务工具）、配置项参考 |
| [`RuoYi-Vue-Plus/docs/`](RuoYi-Vue-Plus/docs) | 平台功能说明（含 9 张运行界面截图） |
| [`deploy/deploy-pg/`](deploy/deploy-pg) | **PostgreSQL 全栈容器编排**：PG 14 + Redis + Milvus + 后端 + 调度 + Nginx（`docker compose up -d --build` 即「有表有数据可登录」） |
| [`deploy/deploy-mysql/`](deploy/deploy-mysql) | **MySQL 8.0 全栈容器编排**：组件与 PG 版一致，**项目名与端口整体错开，两栈可同时运行** |
| [`e2e/`](e2e) | **端到端测试脚本（接口层）**（Node.js 零依赖）：对**已运行**的栈发真实 HTTP / SSE 断言，见下文「测试与覆盖率」 |
| [`e2e-ui/`](e2e-ui) | **端到端测试套件（浏览器层）**（Playwright + chromium，独立目录承载重依赖）：登录 / 菜单可达性 / 会话主路径 / 指定专家 / 停止生成 / HITL 等前端关键路径，见下文「测试与覆盖率」 |

## 功能说明

平台完整功能说明见 **[RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md)**，按真实菜单与代码梳理，涵盖：

- **功能地图与权限模型**——6 个分组、34 个功能页与权限标识约定、工具三档授权（含无人值守链路的口径）；
- **智能体与对话**——任务工作台、入口智能体「小Z」配置、专家配置（含提示词 **AI 辅助生成 / 模板套用 / 克隆专家 / 保存前体检**）、任务管理、定时任务、计划模式、技能仓库、长期记忆、工作区文件、子智能体、场景包；
- **模型与推理 / 知识库与检索**——模型配置与连通性测试、用量与配额、上下文压缩策略、编排预算（运行期可调）；知识库、文档与切片、检索测试；**RAG 检索增强**（Rerank 重排默认开启 + 结果去重 / 相邻合并的上下文装配 + Token 预算）；
- **工具与集成**——业务工具注册、MCP 服务端（对外出口）与客户端（接入外部）、飞书 / 企业微信 / 钉钉 / GitHub 渠道接入；**MCP 自演示**（系统信息工具 `get_system_info` + 启动时登记一条指向自身 `/mcp` 的客户端连接）；业务工具与**外部 MCP 回参**一律过脱敏；
- **安全合规**——敏感词库、安全事件留痕、安全合规配置（输入 / 输出 / 渠道出站 / 工具结果 / 知识库入库 / MCP 出口全链路过审 + PII 脱敏 + 提示词注入防护）；审核引擎可选**本地词库 / 外部内容安全 API**（缺端点或密钥自动降级本地词库）；
- **审计与观测**——会话与消息、工具调用、权限决策、链路追踪、编排运行、**平台运行日报**六类留痕；其中「平台运行日报」是**定时任务 × 技能**的样板场景：内置专家「平台运维助手」每天 08:00 生成昨日运行日报（五块：用量 / 任务 / 待办 / 权限 / 登录），**数字由服务端现取、模型只出结论**，由写工具 `write_daily_digest` 按日期 upsert 落库，页面只读并提示「近 7 天缺失日期」；
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
│   └── biz-buddy        # BizBuddy AI 能力模块（AgentScope 智能体宿主）
└── ruoyi-extend         # 扩展服务（monitor-admin 监控端、snailjob-server 调度端）
```

### biz-buddy 模块

`biz-buddy` 是本项目在框架底座之上叠加的 AI 能力主体，包结构为 `org.dromara.ai`：

| 包 | 职责 |
|---|---|
| `core/agent` | 智能体注册与装配、路由编排（入口智能体 → 专家）、权限决策、流式事件桥、工具工厂 |
| `core/agent/tools` | 内置工具集：部门 / 用户 / 公告 / 角色 / 权限的查询与写入、知识库检索、当前时间，以及平台运行日报的只读汇总 / 下钻（`platform_daily_digest` / `platform_daily_drill` / `ai_usage_by_agent`）与写入工具 `write_daily_digest` |
| `core/digest` | 平台运行日报落库（`DailyDigestPublisher`：五块现取 / 级别解析 / 平稳日压缩 / 按 `digest_date` upsert） |
| `core/mcp` | 自建 MCP 出口与 MCP 客户端封装（含访问口令校验、工具名归一化） |
| `core/channel` | 多渠道入站（飞书 / 企业微信 / 钉钉 / GitHub）与运行时注册 |
| `controller` / `service` | 模型配置、智能体、会话、路由、知识库、文档、MCP、渠道、审计、用量、配额、技能、记忆、场景包、平台运行日报等管理接口 |
| `job` | 定时 Agent 任务执行器（由 SnailJob 调度） |

关键机制：

- **入口智能体「小Z」+ 专家路由**——入口智能体判断意图并给出候选专家，随后把任务直接委派给对应专家；
- **专家直答**——专家回复直接交付用户，入口智能体只负责选择专家；
- **写操作人工确认（HITL）**——涉及写库 / 写外部系统的工具调用暂停待确认，确认后才执行；专家链路内置写工具短期去重，避免重复副作用；
- **统一配置**——AI 平台配置前缀 `bizbuddy.ai`，绑定 [`AiProperties`](RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/config/AiProperties.java)。

## 一键运行（Docker Compose）

不想手工建库、装中间件，用容器拉起**开箱可登录**的完整环境（PostgreSQL 栈 + MySQL 8.0 栈，端口错开可同时运行）。

**完整步骤见 [`docs/docker-compose.md`](docs/docker-compose.md)**：构建前置（三个产物）、两栈启动与访问地址、补模型密钥、常用运维（含 `down -v` 的影响）、e2e 验证。

## 快速开始

**从源码手工跑**（自行准备 PostgreSQL / Redis / Milvus 并手工建库，再启动前后端）。

**完整步骤见 [`docs/getting-started.md`](docs/getting-started.md)**：环境依赖、建库脚本执行顺序、后端与前端启动。

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

### 集成测试（真库 schema 与框架装配）

单测全 Mockito、不起 Spring 容器，e2e 只看接口行为，「框架 × 配置」类缺陷两边都抓不到。另有 4 个集成测试类，**默认全部跳过**（设 `BIZBUDDY_IT=true` 才执行），需两栈（PG `5432` / MySQL `3306`）已运行（**任一栈连上即跑该栈**）：

- `AiDbDialectIntegrationTest`（`biz-buddy`）——裸 JDBC 拉取 PostgreSQL / MySQL 两栈的**真实 schema** 逐项对比：AI 表 / 列 / `idx_ai_` 索引 / `sys_menu` 的 `ai:` 权限，并显式断言 6 个 `create_time` 索引两方言都在；
- `AiEntitySchemaIntegrationTest`（`biz-buddy`）——以**实体元数据**为准反查真库：`org.dromara.ai.domain` 下带 `@TableName` 的实体按 MyBatis-Plus 映射规则算出期望列，缺表 / 缺列即失败（补「两方言**同时**漏改」这一方言对比抓不到的盲区）；
- `AiSkillRepositoryIntegrationTest`（`biz-buddy`）——直接调生产装配 `AgentScopeConfig#aiSkillRepository` 建出**方言专属**技能仓库（PG / MySQL 两分支），用临时技能走「裸 JDBC 写 → 框架仓库读」，验证 `ai_skill` / `ai_skill_resource` 列结构兼容与只读契约，收尾删除；
- `AiContextIntegrationTest`（`ruoyi-admin`）——`@SpringBootTest`（随机端口、`snail-job.enabled=false`）验证整套 Bean 能否装配、运行期配置走真 Redis（覆盖优先）、审计超期统计走真库。

```bash
# 在 RuoYi-Vue-Plus 目录下；先设 BIZBUDDY_IT=true（PowerShell：$env:BIZBUDDY_IT='true'）
./mvnw.cmd -o -pl ruoyi-admin -am test
```

坐标可用 `BIZBUDDY_IT_PG_URL` / `BIZBUDDY_IT_MYSQL_URL` 覆盖；不引 Testcontainers，默认 `mvn test` 行为不变。

### 端到端测试（e2e）

单测证明「代码分支正确」，但不证明「跑起来能用」——模型是否真的连通、知识库是否真的检索得到、小Z 是否真的把问题路由给专家、写工具的人工确认闭环是否真的通，都需要在**真实运行**的栈上验证。`e2e/` 为此提供一套**外部驱动脚本**（Node.js 纯 ESM、**零 npm 依赖**），对**已运行**的容器栈发真实 HTTP 请求做断言：

| 层 | 覆盖 |
|---|---|
| L1 | 模型连通性（对话 + 向量） |
| L2 | 专家提示词辅助生成（`POST /ai/agent/experts/prompt/generate`） |
| L3 | 知识库链路：文档解析（按对象键从 OSS 取源文件）→ 向量化 → 检索命中 |
| L4 | 对话与专家编排：建会话（指定专家强制路由）→ SSE 流 → 编排轨迹落库 |
| L5 | HITL：写工具暂停 → 确认 / 拒绝 → 恢复执行至 `done` |
| L6 | 停止生成：SSE 中途取消 → 后端执行中止 |
| L7 | 安全合规：输入侧命中敏感词被拒答 + 安全事件留痕（临时配置，收尾无条件恢复） |
| L8 | 会话治理闭环：重命名 / 置顶 → 关闭后拒绝提问 → 逻辑删除后不可查 |
| L9 | 鉴权与参数校验边界：未登录 / 伪造 token、缺参 / 空消息 / 不存在会话 |
| L10 | MCP 出口：协议 `initialize` / `tools/list` / `tools/call` + 错误口令被拒 |
| L11 | MCP 客户端：连通探测 + 自演示连接登记 |
| L12 | 结构化决策闸门（桩服务四分支）：真实外呼决策模型，需先启动 JEV 桩否则 SKIP |
| L13 | 停止生成（中断专家编排）：SSE 中途取消 → 后端中止专家调用并收尾为「已中止」 |

```bash
# 需要栈已在运行（deploy/deploy-pg/ 或 deploy/deploy-mysql/ 任一）
node e2e/run.mjs                          # 全量；未配置密钥的层自动 SKIP
node e2e/run.mjs --only L1,L3 --verbose    # 指定层级
node e2e/run.mjs --allow-writes            # L5 走「接受」路径（会真建公告，收尾自动清理）
node e2e/run.mjs --base-url http://localhost:8081   # 指向 MySQL 栈（PG 栈为 8080）
```

- 配置：复制 [`e2e/.env.example`](e2e/.env.example) 为 `e2e/.env.local`（已 gitignore）；唯一机密 `BIZBUDDY_LLM_API_KEY` **仅 L1 需要**（作为瞬时入参下发、不落库），L2~L6 走平台「模型配置」里已保存的密钥，L7~L11 走拒答 / 失败路径或只读协议调用、**不触发模型调用**；L12 需 JEV 桩已启动（否则 SKIP），L13 走平台密钥（真跑模型）。
- 退出码：`0` 无失败（含 SKIP）/ `1` 有 FAIL / `2` 前置检查失败 / `3` 参数错误。
- **L5 默认走「拒绝」路径、不产生任何业务数据**；`--allow-writes` 才真写，并在收尾按标题清理公告与会话。
- 它**不进 `mvn test`**、不引 Testcontainers，与上面的单测**互不影响**；两个数据库栈均已实跑通过（PostgreSQL `8080` / MySQL `8081`）。

另附 **RAG 检索质量评测** [`e2e/eval/eval-rag.mjs`](e2e/eval/eval-rag.mjs)（同样零依赖）：自动建评测库、按语料目录入库，跑 `hit@1/3/5`、`recall@5`、`MRR@5` 与平均耗时并按用例类别汇总，用于重排 / 装配等检索改动的 A/B（当前基线 56 用例、10 篇语料：hit@1 **75.0%** / hit@3 89.3% / hit@5 98.2% / MRR@5 0.829）。

```bash
node e2e/eval/eval-rag.mjs --base-url http://localhost:8081 --rebuild
```

### 浏览器端 e2e（`e2e-ui`）

接口层的 `e2e/` 看不到「页面到底渲染没渲染」。`e2e-ui/` 用 **Playwright 驱动真实 chromium** 打在**已部署的容器栈**上，覆盖登录与路由守卫、**菜单可达性巡检（34 个功能页逐个渲染干净）**、会话主路径（SSE 流式）、指定专家、停止生成、HITL 写工具确认与各配置页。

```bash
cd e2e-ui
pnpm install && pnpm run install-browser             # 首次：装依赖 + 下载 chromium
pnpm test                                            # PG 栈（前端 http://localhost）
BIZBUDDY_UI_BASE_URL=http://localhost:81 pnpm test   # MySQL 栈
```

- 判定为**三条同时成立**：`console.error` / 未捕获异常计数为 0、网络层无 4xx·5xx、关键元素可见（静态资源类噪声在白名单里）；
- 栈不可达 / 入口智能体未启用 / 未配模型密钥 → **SKIP**（属环境问题，不是代码缺陷）；
- 因是**重依赖**（`@playwright/test`），**独立目录**承载，刻意不破坏 `e2e/` 的零依赖设计；细节见 [`e2e-ui/README.md`](e2e-ui/README.md)。

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
