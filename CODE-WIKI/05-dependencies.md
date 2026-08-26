# 05 - 依赖关系

## 1. Maven 工程依赖总览

工程由根 POM（[pom.xml](../pom.xml)）聚合管理，上下层级依赖：

```
yudao (根, cn.iocoder.cloud, 版本 ${revision})
├── yudao-dependencies            # 依赖版本 BOM（被根 POM import）
├── yudao-framework
│   ├── yudao-common              # 最底层通用库，被所有 starter/业务模块依赖
│   └── yudao-spring-boot-starter-*   # 各组件依赖 yudao-common
├── yudao-gateway                 # 依赖 framework 各 starter（env、security 等）
├── yudao-server                  # 聚合 yudao-module-*-server + starter（单体模式）
└── yudao-module-*                # 业务模块
    ├── yudao-module-xxx-api      # 轻量 API/DTO，仅依赖 yudao-common / rpc(starter)
    └── yudao-module-xxx-server   # 依赖本模块 api + 其他模块 api + framework starters
```

## 2. 分层依赖规则

1. **`yudao-common` 是最底层**：所有其他框架与业务模块都依赖它，提供通用数据类型（`CommonResult`/`PageResult`）、异常体系、工具类，以及框架内部 `CommonApi`。
2. **Starter 组件**依赖 `yudao-common`，并对外提供“引入即自动配置”的能力。
3. **业务模块遵循“依赖 API 而非实现”**：模块 A 的 `-server` 若要调用模块 B 的能力，只依赖 B 的 `-api`（`@FeignClient`），不依赖 B 的实现。这保证了模块可独立部署、可替换。
4. **`yudao-server` 聚合实现**：唯一例外的“全部依赖”，用于单体聚合模式。

## 3. 核心模块分支依赖关系

```
┌─────────────┐      ┌─────────────┐      ┌──────────────┐
│ infra-server │◄────│ infra-api    │◄─────│ 其他模块      │
└─────────────┘      └─────────────┘      └──────────────┘
┌─────────────┐      ┌─────────────┐
│ system-server│──►  │ system-api   │◄──── 被框架 CommonApi 与本模块实现引用
└─────────────┘      └─────────────┘
```

具体示例（来自各模块 `pom.xml`）：

| 模块(server) | 依赖的 -api / 其他 |
|--------------|---------------------|
| `system-server` | `system-api`、`infra-api`（用系统能力 + 调用文件等基础设施） |
| `infra-server` | `infra-api` |
| `bpm-server` | `bpm-api`、`system-api` |
| `pay-server` | `pay-api`、`system-api` |
| `mp-server` | `mp-api`、`system-api`、`infra-api` |
| `product-server` | `product-api`、`member-api` |
| `trade-server` | `trade-api`、`product-api`、`pay-api`、`promotion-api`、`member-api`、`system-api` |
| `iot-server` | `iot-api`、`iot-core`、`system-api` |
| `iot-gateway` | `iot-core`（设备编解码、消息总线） |
| `iot-core` | `yudao-common` + 消息中间件（跨 `iot-biz`/`iot-gateway` 的核心总线） |

> 结论：**多租户、权限、字典、用户等“共享能力”集中在 `system`；文件、代码生成等研发工具集中在 `infra`**，其余业务模块通过 `-api` 依赖它们，形成以 system/infra 为底座、各业务域横向解耦的依赖关系网。

## 4. 中间件依赖（运行期）

| 中间件 | 用途 | 是否必选 |
|--------|------|----------|
| MySQL | 主数据存储（MyBatis Plus + Druid 连接池） | 必选 |
| Redis | 缓存、Token、分布式锁/幂等/限流、部分 MQ | 必选 |
| Nacos | 注册中心 + 配置中心 | 微服务模式必选（单体模式可关） |
| XXL-Job | 定时任务调度中心 | 用到定时任务时必选 |
| Sentinel | 服务保障（限流熔断降级） | 可选 |
| Seata | 分布式事务 | 跨库事务时可选 |
| RocketMQ/Kafka/RabbitMQ | 消息队列（WebSocket 广播、业务解耦等） | 可选（可切 Redis Stream/本地 Event） |
| SkyWalking | 链路追踪/日志中心 | 可选 |
| Spring Boot Admin | Java 应用监控服务端 | 可选 |

## 5. 关键 Maven 配置说明

- 根 POM `dependencyManagement` 引入 `yudao-dependencies`（BOM `import`），使各模块免写版本号。
- `flatten-maven-plugin` 统一 `${revision}` 版本（所有模块使用同一版本）。
- `maven-compiler-plugin` 配置 `annotationProcessorPaths`（`spring-boot-configuration-processor`、`lombok`、`lombok-mapstruct-binding`、`mapstruct-processor`）与 `-parameters`，解决 Spring Boot 4.x 参数名发现与 Lombok+MapStruct 协同问题（前提是依赖本插件配置，见 [pom.xml](../pom.xml#L80-L115)）。
- 构建产物：业务 `-server` 与 `yudao-server` 使用 `spring-boot-maven-plugin` `repackage` 生成可执行 jar；`yudao-gateway` 与各模块均有独立的 `Dockerfile` 用于容器化部署。