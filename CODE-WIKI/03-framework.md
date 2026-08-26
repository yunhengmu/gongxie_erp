# 03 - 核心框架

`yudao-framework` 是整套平台的底层基础，位于 [yudao-framework/](../yudao-framework)。核心目录：

```
yudao-framework/
├── pom.xml
├── yudao-common/                          # 通用基础库（无启动逻辑）
└── yudao-spring-boot-starter-*/           # 各能力 Starter（自动配置）
```

---

## 1. yudao-common 通用基础库

> 路径：[yudao-common/](../yudao-framework/yudao-common)，包根 `cn.iocoder.yudao.framework.common`

### 1.1 通用 POJO

| 类 | 说明 |
|----|------|
| [CommonResult](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/CommonResult.java) | **统一返回结果** `{code, msg, data}`，提供 `success/error/checkError/getCheckedData` 等 API。所有 REST 接口返回值 |
| [PageResult](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/PageResult.java) | 通用分页结果 `{total, list}` |
| [PageParam](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/PageParam.java) | 分页入参（pageNo/pageSize，含安全上限） |
| [SortingField / SortablePageParam](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/pojo/SortingField.java) | 排序字段与可排序分页入参 |

### 1.2 异常体系（错误码驱动）

| 类 | 说明 |
|----|------|
| [ErrorCode](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/ErrorCode.java) | 错误码对象 `{code, msg}` |
| [ServiceException](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/ServiceException.java) | 业务异常（可在线改提示，错误码可管理） |
| [ServerException](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/ServerException.java) | 服务端内部异常 |
| [ServiceExceptionUtil](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/util/ServiceExceptionUtil.java) | 快速抛出 `ServiceException` 的工厂工具 |
| [GlobalErrorCodeConstants](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/enums/GlobalErrorCodeConstants.java) | 全局错误码常量（成功/参数错误/未登录/无权限/系统异常等） |
| [ServiceErrorCodeRange](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/exception/enums/ServiceErrorCodeRange.java) | 各模块错误码段分配（避免冲突，如 bpm/300xxxx） |

### 1.3 通用枚举

[enums/](../yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/enums)：

- `CommonStatusEnum`：启用/禁用状态。
- `UserTypeEnum`：用户类型（管理员/Member），区分 `admin-api` 与 `app-api`。
- `TerminalEnum`：终端类型（如 H5、小程序、APP）。
- `WebFilterOrderEnum`：Web 过滤器执行顺序，各 Spring `Filter` 注册时对齐。
- `RpcConstants`、`DateIntervalEnum`、`DocumentEnum` 等。

### 1.4 通用工具类（util）

按功能分包的静态工具，直接可复用：

- 集合：`collection/`：`CollectionUtils`、`MapUtils`、`SetUtils`、`ArrayUtils`
- 对象：`object/`：`BeanUtils`、`ObjectUtils`、`PageUtils`
- JSON：`json/JsonUtils.java`（Jackson 封装）、`json/databind/`（时间戳 `LocalDateTime` 序列化、大数 `Long` 转换）
- 字符串：`string/StrUtils.java`
- 日期：`date/DateUtils.java`、`LocalDateTimeUtils.java`
- Spring：`spring/SpringUtils.java`、`SpringExpressionUtils.java`（SpEL 表达式工具）
- HTTP/Servlet：`http/HttpUtils.java`、`servlet/ServletUtils.java`
- 校验：`validation/`：`InEnum`（枚举校验）、`Mobile`、`Telephone` 及对应 Validator
- 其他：`cache/CacheUtils.java`、`io`、`monitor/TracerUtils.java`、`number/MoneyUtils.java`

### 1.5 核心对象与框架内部 API（biz/system）

- `core/`：`KeyValue`、`ArrayValuable`（枚举数组接口）
- `biz/system/`：**框架跨模块内部 Common API**，实现类在各 `*-api` 或 framework 内，供框架 / Sentinel / 数据权限等使用：
  - `tenant/TenantCommonApi`：租户信息
  - `permission/PermissionCommonApi`、`dto/DeptDataPermissionRespDTO`：权限与部门数据权限
  - `oauth2/OAuth2TokenCommonApi`、`dto/OAuth2AccessToken*DTO`：Token 校验/创建
  - `dict/DictDataCommonApi`、`dto/DictDataRespDTO`：字典
  - `logger/OperateLogCommonApi`：操作日志

---

## 2. Spring Boot Starter 组件

每个 Starter 是独立自动配置模块，按需在 `pom.xml` 引入后自动生效。以下为关键组件的自动配置类与核心类（包根均为 `<framework.biz>.xxx` 或 `components/`，以下给出仓库内绝对路径）。

### 2.1 Web 组件 `yudao-spring-boot-starter-web`

- 自动配置：[YudaoWebAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/config/YudaoWebAutoConfiguration.java) — 注册全局异常处理器、全局响应包装、CORS、请求体缓存过滤器、演示过滤器。
- 处理/过滤器：
  - [GlobalExceptionHandler](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/core/handler/GlobalExceptionHandler.java)：统一异常 → `CommonResult`。
  - [GlobalResponseBodyHandler](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/core/handler/GlobalResponseBodyHandler.java)：统一包装响应体。
  - [ApiRequestFilter](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/web/core/filter/ApiRequestFilter.java)、`CacheRequestBodyFilter`：请求体缓存（供日志/校验重复读）、`DemoFilter`（演示模式限制）。
- XSS：[config/YudaoXssAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/xss/config/YudaoXssAutoConfiguration.java) + `XssFilter`/`JsoupXssCleaner`。
- Swagger：[swagger/config/YudaoSwaggerAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/swagger/config/YudaoSwaggerAutoConfiguration.java) + `Knife4jOpenApiCustomizer`。
- 配置属性：`WebProperties`、`SwaggerProperties`、`XssProperties`。

### 2.2 安全组件 `yudao-spring-boot-starter-security`

- 自动配置：[YudaoSecurityAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoSecurityAutoConfiguration.java) — 注册认证失败/权限不足处理器、密码加密器、Token 认证过滤器。
- 认证核心：
  - [LoginUser](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/LoginUser.java)：登录用户上下文载体。
  - [TokenAuthenticationFilter](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java)：解析请求 Token 并注入 `LoginUser`。
  - [SecurityFrameworkUtils](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/util/SecurityFrameworkUtils.java)：获取当前登录用户、判断是否登录。
  - [SecurityFrameworkService](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/service/SecurityFrameworkService.java)：权限判定入口（对应 `@ss.hasPermission(...)`、`@ss.hasAnyPermissions`、`@ss.hasRole` 等）。
  - [YudaoWebSecurityConfigurerAdapter](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/config/YudaoWebSecurityConfigurerAdapter.java)：默认安全过滤链，可通过 `AuthorizeRequestsCustomizer` 定制放行。
  - RPC：[LoginUserRequestInterceptor](../yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/rpc/LoginUserRequestInterceptor.java)：Feign 调用透传登录用户。
- 操作日志：`operatelog/`（LogRecord 切面，`LogRecordServiceImpl`），异步落库。

### 2.3 数据访问组件 `yudao-spring-boot-starter-mybatis`

- 自动配置：[YudaoMybatisAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/config/YudaoMybatisAutoConfiguration.java) — 注册 `MybatisPlusInterceptor`、分页插件、公共字段填充。
- 查询构造：[QueryWrapperX](../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/core/query/QueryWrapperX.java)（带安全分页/排序）；[MyBatisUtils](../yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/mybatis/core/util/MyBatisUtils.java)。
- 类型处理器：`type/`：`EncryptTypeHandler`（字段加密）、`StringListTypeHandler`、`LongListTypeHandler`、`IntegerListTypeHandler`、`LongSetTypeHandler`。
- 翻译能力：`translate/`：`TranslateUtils` + 自动配置（查询后按注解翻译外键展示名）。

### 2.4 缓存组件 `yudao-spring-boot-starter-redis`

- [YudaoRedisAutoConfiguration](../yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoRedisAutoConfiguration.java) 与 [YudaoCacheAutoConfiguration](../yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoCacheAutoConfiguration.java)、`TimeoutRedisCacheManager`、`YudaoCacheProperties`：Redis 客户端 + Spring Cache 整合，支持自动超时。

### 2.5 服务保障组件 `yudao-spring-boot-starter-protection`

- 分布式锁：`lock4j/`：`YudaoLock4jConfiguration`（基于 Redisson Lock4j）+ `DefaultLockFailureStrategy`。
- 幂等：`idempotent/`：`@Idempotent` 注解 + [IdempotentAspect](../yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/idempotent/core/aop/IdempotentAspect.java) + Redis DAO / 多种 KeyResolver（默认/用户/表达式）。
- 限流：`ratelimiter/`：`@RateLimiter` 注解 + [RateLimiterAspect](../yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/core/aop/RateLimiterAspect.java) + 多 KeyResolver（客户端 IP/用户/服务器节点/表达式）。
- API 签名：`signature/`：`@ApiSignature` 注解 + `ApiSignatureAspect` + `ApiSignatureRedisDAO`。

### 2.6 多租户组件 `yudao-spring-boot-starter-biz-tenant`

- 自动配置：[YudaoTenantAutoConfiguration.java](../yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/YudaoTenantAutoConfiguration.java)，通过 `yudao.tenant.enable` 开关控制。
  - 注册租户拦截器（MyBatis SQL 追加 `tenant_id` 条件）、Web 上下文过滤器、访问上下文 `TenantContextHolder`、租户安全过滤器、Web MVC 配置。

### 2.7 WebSocket 组件 `yudao-spring-boot-starter-websocket`

- 自动配置：`config/YudaoWebSocketAutoConfiguration.java`、`WebSocketProperties`。
- 会话管理：`core/session/WebSocketSessionManager` + `WebSocketSessionManagerImpl`。
- 安全：`core/security/LoginUserHandshakeInterceptor`（握手 Token 鉴权）+ `WebSocketAuthorizeRequestsCustomizer`。
- 广播发送：`core/sender/` 抽象 `WebSocketMessageSender`，支持 **local / Redis / RocketMQ / Kafka / RabbitMQ** 多实现，实现集群广播。

### 2.8 其他 Starter

| 组件 | 核心内容 |
|------|----------|
| `yudao-spring-boot-starter-rpc` | 引入并开启 OpenFeign，统一注册 `@FeignClient` |
| `yudao-spring-boot-starter-env` | 环境隔离与灰度：`EnvContextHolder`、`EnvUtils`、`EnvLoadBalancerClient`（`grayLb://` 灰度负载均衡）、`EnvRequestInterceptor`、`EnvWebFilter` |
| `yudao-spring-boot-starter-job` | 定时任务（XXL-Job）与异步任务封装 |
| `yudao-spring-boot-starter-mq` | MQ 消息封装（Event/Redis Stream/RocketMQ/Kafka/RabbitMQ） |
| `yudao-spring-boot-starter-excel` | EasyExcel 导入导出、`DictFormat`/`MoneyConvert` 等转换器、字典校验与本地字典 RPC |
| `yudao-spring-boot-starter-biz-ip` | IP 属地解析（内置 `resources/area.csv` 地区库） |
| `yudao-spring-boot-starter-biz-data-permission` | 部门级/自定义数据权限拦截 |
| `yudao-spring-boot-starter-monitor` | Spring Boot Admin 监控客户端 |
| `yudao-spring-boot-starter-test` | 单测基类与工具 |

---

## 3. 分层约定

- 业务模块内的标准分包（`cn.iocoder.yudao.module.<xxx>`）：
  - `controller/admin`、`controller/app`（后台管理端 / C 端接口）
  - `service`、`dal/dataobject`、`dal/mysql`（Mapper）、`convert`（MapStruct）、`enums`、`api`（对外 Feign 实现）
- 控制器返回统一 `CommonResult<T>`；分页查询统一使用 `PageResult<T>`。
- 权限控制统一使用 `@PreAuthorize("@ss.hasPermission('xxx:yyy:query')")`（对应 `SecurityFrameworkService`）。