# BizBuddy

> BizBuddy 平台**源码仓库**：后端 `RuoYi-Vue-Plus` + 前端 `plus-ui-Vue` 两个工程。

## 仓库定位

本仓库承载 BizBuddy 的**全部代码**，独立管理与迭代。

- 原工作区仓库 `biz_buddy`（含 `docs/`、`agentscope-java-v2.0.3/` 等参考资料与设计文档）已改名为 **`bizbuddy-workspace`**，不再存放代码；
- 本仓库自建立之日起为代码的唯一来源，后续改动一律在此提交。

## 目录结构

| 目录 | 说明 |
|---|---|
| `RuoYi-Vue-Plus/` | 后端工程：Spring Boot 4.1.1 + JDK 21；AI 能力在 `ruoyi-modules/ruoyi-ai`（AgentScope Java 2.0.3 内核） |
| `plus-ui-Vue/` | 前端工程：Vue 3 + TypeScript + Element Plus + Vite |

两个工程保持原目录名，相互之间的相对引用与构建配置无需改动。

## 项目简介

BizBuddy 是一个企业级智能业务运行平台，构建在 RuoYi-Vue-Plus 中后台底座之上，以 AgentScope Java 2.0.3 为智能体内核。

平台入口由智能体「小Z」统一接待，判断意图后路由给对应领域专家，专家回复直接交付用户（专家直答）；写操作经人工确认（HITL）后执行；会话、路由、工具调用与用量全链路审计可追溯，并配套模型配置、知识库、MCP、技能、记忆、用量配额等运营管理能力。

## 文档

| 文档 | 位置 |
|---|---|
| 平台功能说明（含界面截图） | [`RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md`](RuoYi-Vue-Plus/docs/BizBuddy平台功能说明.md) |
| 后端工程说明 | [`RuoYi-Vue-Plus/README.md`](RuoYi-Vue-Plus/README.md) |

## 快速开始

### 后端

```bash
cd RuoYi-Vue-Plus
./mvnw.cmd -o -pl ruoyi-admin -am package -DskipTests    # Windows；Linux/macOS 用 ./mvnw
./mvnw.cmd -pl ruoyi-admin spring-boot:run
```

依赖 PostgreSQL（库 `biz_buddy`，脚本见 `RuoYi-Vue-Plus/script/sql/postgres/`）、Redis、Milvus。

### 前端

```bash
cd plus-ui-Vue
pnpm install
pnpm dev
```

## 版权

Copyright © 2026 AI架构师张磊 All Rights Reserved.

后端底座源自 [Dromara RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)（MIT License）。