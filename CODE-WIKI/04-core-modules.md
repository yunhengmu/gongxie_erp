# 04 - 核心服务与关键类

本文档聚焦**网关、单体聚合服务、System/Infra 两大核心模块**的启动类、关键 Controller 与 Feign API。

---

## 1. 网关 yudao-gateway

> 目录：[yudao-gateway/](../yudao-gateway)。基于 **Spring Cloud Gateway**（Reactive）的微服务统一入口。

- **启动类**：[GatewayServerApplication.java](../yudao-gateway/src/main/java/cn/iocoder/yudao/gateway/GatewayServerApplication.java)。
- **端口**：`48080`。
- **配置入口**：[application.yaml](../yudao-gateway/src/main/resources/application.yaml)（默认加载 `application-local.yaml`，并可选加载 Nacos 的 `gateway-server-{profile}.yaml`）。
- **路由规则**（Path 断言 → 服务）：
  - `/admin-api/system/**` → `grayLb://system-server`（`grayLb://` 为灰度负载均衡 scheme，来自 `yudao-spring-boot-starter-env`）。
  - `/app-api/**`、`/admin-api/infra/**`、Spring Boot Admin、WebSocket 等分别路由到对应服务。
- **文档聚合**：网关层聚合各服务的 `/admin-api/*/v3/api-docs`，通过 Knife4j 统一暴露 API 文档。
- **默认过滤器**：`DedupeResponseHeader`（去除重复响应头，解决跨域头重复）。

### 关键要点

- 网关本身**不落地业务**，仅做路由、CORS、文档聚合；鉴权在业务服务内的 Security 层完成。
- 各服务名称以 `-server` 结尾（如 `system-server`），与 `nacos` 注册的服务名一致。

---

## 2. 单体聚合服务 yudao-server

> 目录：[yudao-server/](../yudao-server)。本质是“空壳容器”，通过引入各 `yudao-module-*-server` 依赖，在**单进程**内提供全部 RESTful API（供 `yudao-ui-admin`、`yudao-ui-user` 使用）。

- **启动类**：[YudaoServerApplication.java](../yudao-server/src/main/java/cn/iocoder/yudao/server/YudaoServerApplication.java)（`@SpringBootApplication(scanBasePackages=...)` 扫描 server 与 module 包）。
- **默认开启模块**（见 [pom.xml](../yudao-server/pom.xml#L23-L33)）：仅 `system-server` + `infra-server`，其余模块默认注释，按需启用可提升编译速度。
- **单体模式说明**：`application.yaml` 中显式禁用 Nacos discovery 与 config（组织内不依赖注册/配置中心，服务间直接本地 Bean 注入），并排除 OpenFeign。
- **端口**：`application-local.yaml` 设置为 `48080`（与网关相同，因为二者二选一启动）。

### 两种启动模式对比

| 模式 | 启动程序 | 进程 | 依赖 | 适用 |
|------|----------|------|------|------|
| 微服务模式 | `GatewayServerApplication`（网关）+ 各 `*ServerApplication` | 多进程 | 必须 Nacos | 生产/完整微服务 |
| 单体聚合模式 | `YudaoServerApplication` | 单进程 | 无需 Nacos | 本地快速调试/精简部署 |

---

## 3. 系统模块 yudao-module-system

> 职责：**系统功能**（通用业务底座，被各上层业务模块依赖）。目录：[yudao-module-system/](../yudao-module-system)。

### 3.1 结构

```
yudao-module-system/
├── yudao-module-system-api/     # 对外 Feign 接口 + DTO（供框架/其他模块调用）
└── yudao-module-system-server/  # 实现：Controller/Service/DAL + 启动类
```

### 3.2 启动类与配置

- 启动类：[SystemServerApplication.java](../yudao-module-system/yudao-module-system-server/src/main/java/cn/iocoder/yudao/module/system/SystemServerApplication.java)。
- 配置：[application.yaml](../yudao-module-system/src/main/resources/application.yaml)，加载 `system-server-{profile}.yaml`。
- 依赖：`yudao-module-system-api`、`yudao-module-infra-api`（系统模块通过 Feign 调用基础设施能力）。

### 3.3 关键 Controller（分功能域）

| 功能域 | Controller | 路由前缀 |
|--------|-----------|----------|
| 认证 | [AuthController](../yudao-module-system/yudao-module-system-server/src/main/java/cn/iocoder/yudao/module/system/controller/admin/auth/AuthController.java) | `/system/auth`（登录/登出/刷新 Token） |
| 验证码 | `captcha/CaptchaController` | `/system/captcha` |
| 用户 | `admin/user/UserController` | `/system/user` |
| 个人中心 | `admin/user/UserProfileController` | `/system/user/profile` |
| 权限 | [admin/permission/PermissionController](../yudao-module-system/yudao-module-system-server/src/main/java/cn/iocoder/yudao/module/system/controller/admin/permission/PermissionController.java)、`RoleController`、`MenuController` | `/system/permission` `/role` `/menu` |
| 部门/岗位 | `admin/dept/DeptController`、`PostController` | `/system/dept` `/post` |
| 租户 | [admin/tenant/TenantController](../yudao-module-system/yudao-module-system-server/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantController.java)、`TenantPackageController` | `/system/tenant` `/tenant-package` |
| 字典 | `admin/dict/DictTypeController`、`DictDataController` | `/system/dict-type` `/dict-data` |
| OAuth2/SSO | `admin/oauth2/OAuth2TokenController`、`OAuth2ClientController`、`OAuth2OpenController`、`OAuth2UserController` | `/system/oauth2/...` |
| 三方登录 | `admin/socail/SocialUserController`、`SocialClientController` | `/system/social-...` |
| 短信 | `admin/sms/SmsChannelController`、`SmsTemplateController`、`SmsLogController`、`SmsCallbackController` | `/system/sms-...` |
| 邮件 | `admin/mail/MailAccountController`、`MailTemplateController`、`MailLogController` | `/system/mail-...` |
| 站内信 | `admin/notify/NotifyTemplateController`、`NotifyMessageController` | `/system/notify-...` |
| 审计日志 | `admin/logger/OperateLogController`、`LoginLogController` | `/system/operate-log` `/login-log` |
| 通知公告 | `admin/notice/NoticeController` | `/system/notice` |
| 地区/IP | `admin/ip/AreaController` | `/system/area` |
| C 端 | `app/tenant/AppTenantController`、`app/ip/AppAreaController`、`app/dict/AppDictDataController` | `/app-api/...` |

### 3.4 对外 Feign API（system-api）

路径：[yudao-module-system-api/](../yudao-module-system/yudao-module-system-api)。定义 `@FeignClient` 接口，供其他模块远程调用系统能力，例如：

- [AdminUserApi](../yudao-module-system/yudao-module-system-api/src/main/java/cn/iocoder/yudao/module/system/api/user/AdminUserApi.java)：`@FeignClient`，`getUser(...)` 等用户查询。
- 以及 `dept`、`dict`、`tenant`、`permission`、`oauth2` 等 Api 接口。

---

## 4. 基础设施模块 yudao-module-infra

> 职责：**基础设施**（研发/运维工具）。目录：[yudao-module-infra/](../yudao-module-infra)，结构同 system 模块（`-api` + `-server`）。

### 4.1 启动类与依赖

- 启动类：[InfraServerApplication.java](../yudao-module-infra/yudao-module-infra-server/src/main/java/cn/iocoder/yudao/module/infra/InfraServerApplication.java)。
- 依赖：`yudao-module-infra-api`，并依赖 system 能力（用户上下文等）。

### 4.2 关键 Controller

| 功能域 | Controller | 说明 |
|--------|-----------|------|
| 代码生成 | [infra/codegen/CodegenController](../yudao-module-infra/yudao-module-infra-server/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/codegen/CodegenController.java) | `/infra/codegen` 一键生成 Java/Vue/SQL/文档，支持单表、树表、主子表 |
| 文件服务 | [infra/file/FileController](../yudao-module-infra/yudao-module-infra-server/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/file/FileController.java) | `/infra/file` 上传/管理（S3/MinIO/本地/FTP 等） |
| 定时任务 | `job`、配置 `config`、数据库文档、系统接口(Swagger)、API 日志、消息队列管理等 | 对应基础设施功能域，均位于 `controller/admin/` 下 |

### 4.3 对外 Feign API（infra-api）

路径：[yudao-module-infra-api/](../yudao-module-infra/yudao-module-infra-api)，例如：

- [FileApi](../yudao-module-infra/yudao-module-infra-api/src/main/java/cn/iocoder/yudao/module/infra/api/file/FileApi.java)：`@FeignClient`，`createFile(...)`、`presignGetUrl(...)` 远程文件能力。

---

## 5. 关键业务流示例

**登录流程**：`AuthController`（`/system/auth/login`）→ system 服务校验账号密码/验证码 → 生成 OAuth2 Token → 存入 Redis → 返回 `accessToken`。前端后续请求带 Token，由各服务 Security 过滤器解析为 `LoginUser`。

**权限注解**：业务接口使用 `@PreAuthorize("@ss.hasPermission('permission:xxx:query')")`，`@ss` 指向 `SecurityFrameworkService`（见 <a href="03-framework.md#22-安全组件">03 Framework</a>）。