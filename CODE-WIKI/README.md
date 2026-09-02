# 芋道云（yudao-cloud）Code Wiki

> 本 Wiki 由代码仓库分析生成，用于帮助开发者快速理解 `yudao-cloud`（JDK 25 + Spring Boot 4.x / Spring Cloud 2025 微服务完整版）的整体结构、模块职责、关键类、依赖关系与运行方式。

## 文档目录

### 一、基础架构篇（01–06）

| 文档 | 内容 |
|------|------|
| [01 - 整体架构](01-architecture.md) | 平台定位、微服务架构、技术栈、请求流转、目录总览 |
| [02 - 模块职责总览](02-modules.md) | 全部 Maven 模块的职责清单与功能域划分 |
| [03 - 核心框架](03-framework.md) | `yudao-framework` 基础框架、`yudao-common` 通用能力、各 Starter 组件与关键类 |
| [04 - 核心服务与关键类](04-core-modules.md) | 网关、Server 聚合服务、System/Infra 模块的启动类与核心 Controller/Service/Feign API |
| [05 - 依赖关系](05-dependencies.md) | 模块间依赖、中间件依赖、分层依赖关系说明 |
| [06 - 运行方式](06-run.md) | 环境准备、启动步骤、端口与配置、内置基础设施 |

### 二、AI 模块进阶篇（07–14）

| 文档 | 内容 |
|------|------|
| [07 - MCP 工具配置指南](07-mcp-tool-config-guide.md) | 在 module-ai 配 Tool 走 HTTP 版 MCP 供 module-agent 使用（含权限防越权） |
| [08 - AI 顾问后端设计](08-ai-advisor-backend-design.md) | 从「一次对话」到「一个产品」要补的 9 件事 |
| [09 - AI 顾问完整范例](09-ai-advisor-full-example.md) | 从零到能跑的一个完整功能 |
| [10 - 单接口串起全部功能](10-one-endpoint-everything.md) | AI 顾问对话接口，从第一行到最后一行 |
| [11 - 多租户鉴权落地](11-tenant-isolation-in-module-ai.md) | module-ai 租户隔离是怎么「写死」的（tenant_id 鉴权分配） |
| [12 - 多租户 RBAC 工具权限](12-multi-tenant-rbac-agent-tool.md) | Agent 工具权限：从业界共识到芋道落地（四步） |
| [12 附 - 代码目录编排](12-代码目录编排.md) | 文档 12 第四章两段代码的文件落点目录树版 |
| [13 - MCP × LangChain 规范与现状](13-mcp-langchain-multitenant-agent-chat.md) | 多租户 RBAC 工具经 MCP 被 LangChain 使用：规范综述 + 本项目现状 |
| [13 附 - 代码目录编排](13-代码目录编排.md) | 文档 13 两段代码的文件落点目录树版 |
| [14 - 完整落地代码](14-mcp-langchain-production-code.md) | 代码 ① module-ai 工具注册 + 代码 ② `/v2/chat` 生产级会话接口（完整代码） |

## 快速导览

- **项目性质**：芋道（Yudao）**Spring Cloud Alibaba 微服务完整版**，全部代码开源（MIT License），以开发者为中心的一站式快速开发平台。
- **当前分支版本**：`master-jdk25`，JDK 25 + Spring Boot 4.1.0 + Spring Cloud Alibaba 2025.1.0.0 + Spring Cloud 2025.1.2。
- **核心宗旨**：模块化 + 微服务，一个业务领域一个 `yudao-module-xxx`，通过 `*-api` 子模块暴露 RPC（OpenFeign）接口，网关统一路由。
- **两大启动模式**：`yudao-gateway`（微服务模式，多进程独立部署）与 `yudao-server`（单体聚合模式，所有模块打进一个进程）。

---

### 常用路径速查（绝对路径）

后端根目录：`d:\yudao\yudao-cloud-master-jdk25`

- 根聚合 POM：`[pom.xml](../pom.xml)`（`groupId=cn.iocoder.cloud`，版本 `2026.07-jdk25-SNAPSHOT`）
- 依赖版本管理：`[yudao-dependencies/pom.xml](../yudao-dependencies/pom.xml)`
- 框架模块：`[yudao-framework/](../yudao-framework)`
- 网关：`[yudao-gateway/](../yudao-gateway)`
- 单体聚合服务：`[yudao-server/](../yudao-server)`
- 业务模块目录：`[yudao-module-*/](../yudao-module-system)`

---

_说明：本 Wiki 中涉及的关键类均给出仓库内绝对路径，便于跳转。路径根为 `d:\yudao\yudao-cloud-master-jdk25`。_