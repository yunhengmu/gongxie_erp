# 02 - 模块职责总览

本项目是一个典型的多模块 Maven 工程（见根 [pom.xml](../pom.yaml) 的 `<modules>`）。模块整体分四层：

1. **依赖管理层**：`yudao-dependencies`
2. **框架层**：`yudao-framework`
3. **服务/网关层**：`yudao-gateway`、`yudao-server`
4. **业务模块层**：`yudao-module-*`

---

## 1. 依赖管理层

| 模块 | 职责 |
|------|------|
| `yudao-dependencies` | Maven 依赖版本统一管理（BOM）。所有第三方依赖版本集中在此，供根 POM `import`，其他模块无需写版本号。关键版本见 [01-architecture](01-architecture.md#3-技术栈核心)。 |

---

## 2. 框架层 `yudao-framework`

> 详细类说明见 [03 - 核心框架](03-framework.md)。

| 子模块 | 职责 |
|--------|------|
| `yudao-common` | 通用基础库：通用 POJO（分页/统一返回）、枚举、异常体系、工具类、框架内部跨模块的 `CommonApi` 定义 |
| `yudao-spring-boot-starter-web` | Web MVC 封装：全局异常/响应处理、XSS 过滤、CORS、Swagger/Knife4j、Jackson 配置 |
| `yudao-spring-boot-starter-security` | 安全认证：Token 认证过滤器、`LoginUser`、权限校验 `@ss.hasPermission`、操作日志切面 |
| `yudao-spring-boot-starter-mybatis` | MyBatis Plus 增强：分页插件、通用 `BaseDO` 字段填充、`QueryWrapperX`、类型转换、简单翻译能力 |
| `yudao-spring-boot-starter-redis` | Redis/Redisson 与 Spring Cache 集成，统一缓存管理 |
| `yudao-spring-boot-starter-mq` | 消息队列抽象：Event（本地）、Redis Stream、RocketMQ、Kafka、RabbitMQ |
| `yudao-spring-boot-starter-job` | 定时任务（XXL-Job）与异步任务（`@Async`）封装 |
| `yudao-spring-boot-starter-protection` | 服务保障：分布式锁（Lock4j）、幂等（Idempotent）、限流（RateLimiter）、API 签名 |
| `yudao-spring-boot-starter-biz-tenant` | 多租户：数据库拦截、Web 上下文、租户安全 |
| `yudao-spring-boot-starter-biz-data-permission` | 数据权限：基于部门/自定义规则的数据过滤 |
| `yudao-spring-boot-starter-biz-ip` | IP 属地解析与地区库（内置 `area.csv`） |
| `yudao-spring-boot-starter-excel` | EasyExcel 导入导出、字典/金额等转换器、本地字典 RPC |
| `yudao-spring-boot-starter-rpc` | 开启 OpenFeign，统一 RPC 能力 |
| `yudao-spring-boot-starter-env` | 环境隔离：多环境灰度负载均衡与请求上下文 |
| `yudao-spring-boot-starter-monitor` | Spring Boot Admin 客户端监控 |
| `yudao-spring-boot-starter-websocket` | WebSocket 支持（多 MQ 广播、Token 鉴权、会话管理） |
| `yudao-spring-boot-starter-test` | 单元测试基础封装 |

---

## 3. 服务/网关层

| 模块 | 职责 |
|------|------|
| `yudao-gateway` | 微服务网关（Spring Cloud Gateway），统一路由、鉴权透传、文档聚合。详见 [04 - 核心服务](04-core-modules.md#1-网关-yudao-gateway) |
| `yudao-server` | 单体聚合服务（“空壳”容器），按需引入 `yudao-module-xxx-server`，可在单进程内提供全部 API。默认仅启 system + infra |

---

## 4. 业务模块层 `yudao-module-*`

每个模块的通用结构：`yudao-module-xxx-api`（对外暴露的 Feign 接口与 DTO）+ `yudao-module-xxx-server`（Controller/Service/Mapper/启动类）。特殊模块：`mall` 拆分出 4 个服务，`iot` 拆分出 `core/gateway/server`。

### 通用/必选模块

| 模块 | 职责（功能域） |
|------|----------------|
| `yudao-module-system` | **系统功能**（核心）：用户、角色、菜单、部门、岗位、租户/租户套餐、字典、短信、邮件、站内信、操作/登录日志、错误码、通知公告、敏感词、OAuth2/SSO、地区 |
| `yudao-module-infra` | **基础设施**（研发/运维工具）：代码生成器、系统接口文档、数据库文档、表单构建、配置管理、定时任务、文件服务、WebSocket 示例、API 日志、消息队列管理、服务保障、日志服务 |

### 通用（可选）模块

| 模块 | 职责 |
|------|------|
| `yudao-module-bpm` | **工作流**：基于 Flowable 8，流程建模、仿钉钉/飞书+ BPMN 双设计器、审批（会签/或签/驳回/转办/委派/加签…）、流程表单、抄送、父子流程等 |
| `yudao-module-pay` | **支付系统**：商户应用、支付/退款订单、回调通知、支付宝/微信等渠道接入 |
| `yudao-module-report` | **数据报表与大屏**：报表设计器、大屏设计器 |
| `yudao-module-member` | **会员中心**：会员管理、等级、分组、标签、积分签到 |

### 业务系统（按需）

| 模块 | 职责 |
|------|------|
| `yudao-module-mall` | **商城系统**：拆分为 `product`（商品：品牌/分类/SPU/SKU）、`trade`（交易：订单/退款/购物车）、`promotion`（营销）、`statistics`（统计）四个子服务 |
| `yudao-module-mp` | **微信公众号**：账号、菜单、粉丝、消息、自动回复、素材、模板消息、图文 |
| `yudao-module-crm` | **CRM** 客户关系管理 |
| `yudao-module-erp` | **ERP** 企业资源计划 |
| `yudao-module-wms` | **WMS** 仓库管理 |
| `yudao-module-mes` | **MES** 制造执行 |
| `yudao-module-hrm` | **HRM** 人力资源 |
| `yudao-module-fms` | **FMS** 财务管理 |
| `yudao-module-im` | **IM** 即时通讯 |
| `yudao-module-ai` | **AI 大模型平台** |
| `yudao-module-iot` | **IoT 物联网**：拆分为 `iot-core`（核心总线/通用 API）、`iot-gateway`（设备网关：接入设备消息、编解码）、`iot-server`（业务：产品/设备/协议管理） |

---

## 5. 前端工程 `yudao-ui`

仓库内为子目录引用（README），实际前端项目独立维护：

| 目录 | 前端版本 |
|------|----------|
| `yudao-ui-admin-vue3` | Vue3 + element-plus 管理后台 |
| `yudao-ui-admin-vben` | Vue3 + vben(ant-design-vue) 管理后台 |
| `yudao-ui-admin-vue2` | Vue2 + element-ui 管理后台 |
| `yudao-ui-admin-uniapp` | uni-app 管理后台移动端 |
| `yudao-ui-mall-uniapp` | uni-app 商城小程序/H5 |