# 01 - 整体架构

## 1. 平台定位

**芋道（Yudao）** 是以开发者为中心打造的中国一流快速开发平台，本项目为其 **Spring Cloud 微服务完整版**（`yudao-cloud`）。相比单体的 `ruoyi-vue-pro`，它在后端采用微服务拆分；相比 `yudao-cloud-mini` 精简版，它额外包含会员、报表、工作流、商城、营销、CRM、ERP、WMS、MES、HRM、FMS、IM、AI、IoT 等业务功能。

- 开源协议：MIT（个人与企业可 100% 免费使用，无需保留版权信息）。
- 当前分支：`master-jdk25`（JDK 25 + Spring Boot 4.x）。
- 演示地址：
  - 管理后台 Vue3 + element-plus：<http://dashboard-vue3.yudao.iocoder.cn>
  - 管理后台 Vue3 + vben(ant-design-vue)：<http://dashboard-vben.yudao.iocoder.cn>

## 2. 微服务架构

架构目标图（仓库 `/.image/common/yudao-cloud-architecture.png`）。

```
                     ┌─────────────────────────────────────────────┐
                     │             前端 (yudao-ui-*)              │
                     │  admin-vue3 / vben / vue2 / mall-uniapp    │
                     └───────────────────┬─────────────────────────┘
                                         │ HTTP / WebSocket
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │            yudao-gateway (网关:48080)       │
                     │   路由 / 鉴权透传 / 聚合文档 / 灰度 lb       │
                     └───┬────────┬────────┬────────┬──────────────┘
                         │        │        │        │  OpenFeign(RPC)
         ┌───────────────┼────────┼────────┼────────┼──────────────┐
         ▼               ▼        ▼        ▼        ▼
  system-server    infra-server   member-server  业务模块(独立服务)…
  (用户/权限/租户)   (代码生成/文件)   (C端会员)     pay/bpm/mall/crm/iot…
         └───────────────┬────────┴────────┴────────┘
                         ▼
      ┌──────────────────────────────────────────────────────┐
      │  Nacos(注册+配置)   Sentinel(限流熔断)   Seata(分布式事务) │
      │  XXL-Job(定时)      MySQL/Druid        Redis/Redisson │
      │  RocketMQ/Kafka/RabbitMQ(可选MQ)  SkyWalking(链路)     │
      └──────────────────────────────────────────────────────┘
```

### 核心设计要点

1. **模块即服务边界**：每个业务域一个 `yudao-module-xxx`，内部再拆 `-api`（对外 Feign 接口与 DTO，被其他模块/框架依赖）与 `-server`（含 Controller/Service/Mapper 的可部署实现）。
2. **RPC 通信**：模块间调用统一走 `yudao-module-xxx-api` 定义的 `@FeignClient` 接口，由 `yudao-spring-boot-starter-rpc` 开启 OpenFeign。
3. **网关单一入口**：`yudao-gateway` 按 Path 前缀（`/admin-api`、`/app-api`）路由到各服务，并聚合 `Knife4j` 文档。
4. **透明化多租户**：`yudao-spring-boot-starter-biz-tenant` 在 MyBatis / Web / Security 层自动加租户条件，实现 SaaS 多租户。
5. **多种运行形态**：微服务模式下每个模块独立进程；也可在 `yudao-server` 聚合全部模块做单体运行（默认仅开启 system + infra）。

## 3. 技术栈（核心）

| 类别 | 技术 | 版本 |
|------|------|------|
| JDK | JDK | 25 |
| 微服务框架 | Spring Cloud Alibaba / Spring Cloud | 2025.1.0.0 / 2025.1.2 |
| 基础框架 | Spring Boot | 4.1.0 |
| 注册/配置中心 | Nacos | 3.1.1 |
| 服务网关 | Spring Cloud Gateway | 5.0.2 |
| 服务保障 | Sentinel | 1.8.9 |
| 分布式事务 | Seata | 2.5.0 |
| 定时任务 | XXL-Job | 2.4.0 |
| Web | Spring MVC / Spring Security | 7.0.8 / 7.1.0 |
| ORM | MyBatis Plus / Dynamic Datasource / Druid | 3.5.16 / 4.5.0 / 1.2.28 |
| 缓存 | Redis + Redisson | 5.0~7.0 / 4.6.1 |
| 消息队列 | Redis Stream / RocketMQ / Kafka / RabbitMQ（可选） | 5.3.1 |
| 工作流 | Flowable | 8.0.0 |
| 接口文档 | Knife4j（Swagger 增强） | 4.5.0 |
| 链路/监控 | SkyWalking / Spring Boot Admin | 9.6.0 / 4.1.1 |
| Bean 转换 | MapStruct + Lombok | 1.6.3 / 1.18.46 |
| 测试 | JUnit + Mockito | 6.0.3 / 5.23.0 |

版本号由根 POM（[pom.xml](../pom.xml)）与 [yudao-dependencies/pom.xml](../yudao-dependencies/pom.xml) 统一管理，通过 `dependencyManagement`（BOM import）下发。

## 4. 请求流转（一次登录 + 业务调用）

1. 前端请求到达 `yudao-gateway`（端口 `48080`），网关根据 Path 路由到对应服务。
2. 服务端经 `yudao-spring-boot-starter-web` 过滤器链（请求缓存、XSS、CORS 等）。
3. `yudao-spring-boot-starter-security` 的 `TokenAuthenticationFilter` 解析 Token，放到 `LoginUser` 线程上下文。
4. 进入各业务 `Controller` → `Service` → `Mapper`；多租户由 Tenant 拦截器自动注入租户条件，数据权限由 DataPermission 拦截器自动过滤。
5. 跨服务调用使用 Feign，通过 `LoginUserRequestInterceptor` 传递登录用户与 Token。

## 5. 顶层目录结构

```
yudao-cloud-master-jdk25/
├── pom.xml                  # 根聚合 POM（Maven 聚合 + 依赖管理 + 插件配置）
├── yudao-dependencies/      # 统一依赖版本 BOM
├── yudao-framework/         # 框架层（yudao-common + 各类 *-starter）
├── yudao-gateway/           # 网关服务
├── yudao-server/            # 单体聚合服务（可选启动方式）
├── yudao-module-*/          # 各业务模块（*-api + *-server）
├── yudao-ui/                # 前端工程（admin-vue3/vben/vue2/mall-uniapp）
├── sql/                     # 各数据库初始化脚本（mysql/oracle/pg/dm/...）
├── script/                  # docker-compose 一键环境、idea http-client 等
└── .github/ .gitee/ .image/ # 构建工作流、Issue 模板、文档图片
```

### 根 POM 要点

- `groupId=cn.iocoder.cloud`，`artifactId=yudao`，版本 `${revision}=2026.07-jdk25-SNAPSHOT`。
- 通过 `flatten-maven-plugin` 统一 `${revision}` 版本号。
- `maven-compiler-plugin` 配置 Lombok + MapStruct + `-parameters` 注解处理器，解决二者的编译协作问题（参考 [pom.xml](../pom.xml#L80-L115)）。
- Maven 镜像源使用华为云 + 阿里云，加速依赖下载。