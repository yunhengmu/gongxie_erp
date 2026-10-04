<div align="center">

# BizBuddy

**企业级智能业务运行平台 · 源码仓库（后端 `RuoYi-Vue-Plus` + 前端 `plus-ui-Vue`）**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-success.svg)]()
[![JDK](https://img.shields.io/badge/JDK-21-green.svg)]()
[![AgentScope](https://img.shields.io/badge/AgentScope-2.0.3-blue.svg)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14-336791.svg)]()
[![Vue](https://img.shields.io/badge/Frontend-Vue%203-42b883.svg)]()
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 平台简介

BizBuddy 是一个**企业级智能业务运行平台**：它在 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) `6.0.0` 的成熟中后台底座（RBAC、工作流、调度、代码生成、文件存储、消息推送等）之上，以 **AgentScope Java 2.0.3** 为智能体内核，叠加了一整套可运营的 AI 能力。

核心运行形态：

- **入口智能体「小Z」统一接待**——所有对话先进入入口智能体，由它判断意图并给出候选专家清单。
- **专家路由**——入口智能体按候选清单把任务**直接委派**给对应的领域专家；专家的回复**直接交付用户**（专家直答，小Z 只负责选谁）。
- **写操作人工确认（HITL）**——涉及写库 / 写外部系统的工具调用会暂停并请求人工确认，确认后才继续执行。
- **多渠道接入**——支持飞书 / 企业微信 / 钉钉 / GitHub Webhook 入站回调。
- **全链路审计留痕**——会话、路由、工具调用、确认与用量均有审计记录，可追溯。

## 仓库结构

| 目录 | 说明 |
|---|---|
| [`RuoYi-Vue-Plus/`](RuoYi-Vue-Plus) | **后端工程**：Spring Boot 4.1.1 + JDK 21，多模块 Maven；AI 能力在 `ruoyi-modules/ruoyi-ai`（AgentScope 2.0.3 内核） |
| [`plus-ui-Vue/`](plus-ui-Vue) | **前端工程**：Vue 3 + TypeScript + Element Plus + Vite，包管理 pnpm |
| [`RuoYi-Vue-Plus/docs/`](RuoYi-Vue-Plus/docs) | 平台功能说明（含 9 张运行界面截图） |

## 功能说明

平台完整功能说明见 **[RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md)**，按真实菜单与代码梳理，涵盖：

- **功能地图与权限模型**——5 个分组、26 个功能页与权限标识约定、工具三档授权；
- **智能体与对话**——任务工作台、入口智能体「小Z」配置、专家配置、任务管理、定时任务、计划模式、技能仓库、长期记忆、工作区文件、子智能体、场景包；
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
| **智能体内核** | **AgentScope Harness** | **2.0.3** |
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
│   └── ruoyi-ai         # AgentScope 智能体宿主（本项目 AI 能力主体）
└── ruoyi-extend         # 扩展服务（monitor-admin 监控端、snailjob-server 调度端）
```

### ruoyi-ai 模块

`ruoyi-ai` 是本项目在框架底座之上叠加的 AI 能力主体，包结构为 `org.dromara.ai`：

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
- **统一配置**——AI 平台配置前缀 `bizbuddy.ai`，绑定 [`AiProperties`](RuoYi-Vue-Plus/ruoyi-modules/ruoyi-ai/src/main/java/org/dromara/ai/config/AiProperties.java)。

## 快速开始

### 1. 环境依赖

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 21（推荐 OpenJDK，**不要使用 OracleJDK**） |
| Maven | ≥ 3.8.x（仓库自带 `mvnw` / `mvnw.cmd`） |
| PostgreSQL | 14，库名 `biz_buddy` |
| Redis | ≥ 6.x（缓存、分布式锁、AgentScope 分布式状态） |
| Milvus | v2.6.13，gRPC `19530`（知识库向量检索） |
| Node / pnpm | Node ≥ 20.19.0，pnpm ≥ 10 |

### 2. 初始化数据库

先在 PostgreSQL 中创建库 `biz_buddy`，再按顺序导入 `RuoYi-Vue-Plus/script/sql/postgres/` 下的脚本：

```
postgres_ry_vue.sql        # 框架基础表 + 系统管理数据（含菜单、字典、初始账号）
postgres_ry_workflow.sql   # 工作流（Warm-Flow）表
postgres_ry_ai.sql         # AI 平台表（ai_* 表结构 + 种子数据）
postgres_ry_job.sql        # 调度任务（SnailJob）表
```

> 仓库同时保留了 MySQL / Oracle / SQLServer 方言的历史脚本，但**本项目当前只维护并使用 PostgreSQL**。

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
          driverClassName: org.postgresql.Driver
          url: jdbc:postgresql://localhost:5432/biz_buddy?...
          username: postgres
          password: <你的密码>
```

AI 平台配置前缀为 `bizbuddy.ai`（见 [`AiProperties`](RuoYi-Vue-Plus/ruoyi-modules/ruoyi-ai/src/main/java/org/dromara/ai/config/AiProperties.java)）：模型密钥加密 Key、Milvus 连接、对话迭代上限、专家调用超时与 Token / 墙钟预算、写工具去重、上下文压缩、工作区与长期记忆开关、MCP 出口口令等。

### 前端（`plus-ui-Vue/.env.*`）

| 变量 | 开发 | 生产 |
|---|---|---|
| `VITE_APP_TITLE` / `VITE_APP_LOGO_TITLE` | `BizBuddy` | `BizBuddy` |
| `VITE_APP_BASE_API` | `/dev-api` | `/prod-api` |
| `VITE_APP_PORT` | `80` | `80` |
| `VITE_APP_ENCRYPT` | `true` | `true` |

## 测试与覆盖率

AI 模块（`ruoyi-ai`）是本项目的测试主力，覆盖工具、智能体装配、路由编排、权限决策、渠道路由、MCP、各 Service 与 Controller：

```bash
# 在 RuoYi-Vue-Plus 目录下，仅跑 AI 模块
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-ai test
```

JaCoCo 覆盖率报告（构建后生成）：

```
RuoYi-Vue-Plus/ruoyi-modules/ruoyi-ai/target/site/jacoco/index.html   # HTML 报告
RuoYi-Vue-Plus/ruoyi-modules/ruoyi-ai/target/site/jacoco/jacoco.csv   # 机器可读
```

## 文档

| 文档 | 位置 |
|---|---|
| 平台功能说明（含界面截图） | [`RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md`](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md) |
| 前端工程说明 | [`plus-ui-Vue/README.md`](plus-ui-Vue/README.md) |

## 版权

Copyright © 2026 AI架构师张磊 All Rights Reserved.

后端底座源自 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)（MIT License），本仓库在其基础上进行 BizBuddy 平台化改造。