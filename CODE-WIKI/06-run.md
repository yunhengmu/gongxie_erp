# 06 - 运行方式

## 1. 环境准备

| 依赖 | 版本建议 | 说明 |
|------|----------|------|
| JDK | 25 | 本分支（master-jdk25）要求 JDK 25 |
| Maven | 3.6+ | 构建工具 |
| MySQL | 5.7 / 8.0+ | 主数据库 |
| Redis | 5.0 ~ 7.0 | 缓存/Token/锁/MQ |
| Nacos | 3.1.1 | 注册+配置中心（微服务模式必选，单体模式可关） |
| 前端 | Node.js | 用于 `yudao-ui-*` 前端工程 |

## 2. 初始化数据库

仓库提供多数据库初始化脚本，位于 [sql/](../sql)：

- MySQL：执行 [mysql/ruoyi-vue-pro.sql](../sql/mysql/ruoyi-vue-pro.sql)（含 `quartz` 等）——该脚本由官方文档拆分为 `quartz.sql` + `ruoyi-vue-pro.sql`。
- 其他数据库：`sql/` 下另有 `oracle/`、`postgresql/`、`sqlserver/`、`db2/`、`dm/`、`highgo/`、`kingbase`、`opengauss` 等初始化脚本，按需选择。

> 数据库初始化完成后，将初始化数据导入 Nacos 配置中心所需的各 `*-server` 配置（MySQL/Redis 连接等可参考各模块 `application-local.yaml`），或直接在本地配置覆盖。

## 3. 微服务模式启动（推荐生产/完整模式）

1. 启动基础设施：Nacos（注册+配置）、MySQL、Redis、可选 XXL-Job。
2. 修改各服务的 `application-local.yaml` 中的 Nacos 地址、账号密码与 namespace。
3. 依次启动服务（每个 `-server` 模块均有启动类）：
   - `yudao-gateway` → [GatewayServerApplication](../yudao-gateway/src/main/java/cn/iocoder/yudao/gateway/GatewayServerApplication.java)（端口 48080）
   - `yudao-module-system-server` → [SystemServerApplication](../yudao-module-system/yudao-module-system-server/src/main/java/cn/iocoder/yudao/module/system/SystemServerApplication.java)
   - `yudao-module-infra-server` → [InfraServerApplication](../yudao-module-infra/yudao-module-infra-server/src/main/java/cn/iocoder/yudao/module/infra/InfraServerApplication.java)
   - 按需启动其他业务 `-server`（bpm/pay/member/...）
4. 通过网关统一入口访问：`http://localhost:48080/admin-api/...`。

### 各服务端口约定

- 网关：`48080`
- `yudao-server`（单体）：`48080`（与网关二选一）
- 各 `*-server` 端口见对应 `application-local.yaml`（如 `system-server` 等）。

## 4. 单体聚合模式启动（快速开发）

1. 依赖 `yudao-module-system-server`、`yudao-module-infra-server` 已在 [yudao-server/pom](../yudao-server/pom.xml) 默认开启；如需更多模块，取消对应注释。
2. 启动 `YudaoServerApplication`（[YudaoServerApplication.java](../yudao-server/src/main/java/cn/iocoder/yudao/server/YudaoServerApplication.java)）。
3. 该模式已禁用 Nacos 与 OpenFeign，服务间直连 Bean，实现“单进程跑全部功能”，便于本地调试。

## 5. 使用 Docker 一键启动

仓库提供基础设施编排与示例服务：`[script/docker/docker-compose.yml](../script/docker/docker-compose.yml)`。

- 包含 `yudao-gateway`、`yudao-system`（`yudao-module-system-biz` 镜像）等服务容器，并注入 Nacos 配置中心/注册中心地址、`SPRING_PROFILES_ACTIVE=test`、时区与健康检查。
- 使用 `docker compose up -d` 启动；服务通过 `/actuator/health` 做健康检查与启动顺序编排。

## 6. 前端启动

前端工程独立维护（`yudao-ui/`）。以 Vue3 + element-plus 管理后台为例：

1. 进入前端目录，`npm install`。
2. 修改接口地址为网关（微服务模式 `http://localhost:48080` 或单体 `http://localhost:48080`）。
3. `npm run dev` 启动开发环境。

## 7. 端口与 Profile

- 各服务支持多 Profile：`local`（默认，见 `application-local.yaml`）、`dev`、`test`、`prod`（通过 `SPRING_PROFILES_ACTIVE` 指定）。
- 环境隔离组件 `yudao-spring-boot-starter-env` 支持多套灰度环境隔离（请求头/`grayLb://` 灰度负载均衡）。
- API 文档（Knife4j）：微服务模式下由网关聚合，地址形如 `http://localhost:48080/doc.html`。

## 8. 常见问题

- **启动报错依赖版本问题**：确保使用 JDK 25 并启用了本仓库的 `maven-compiler-plugin` 注解处理器配置（Lombok + MapStruct）。
- **微服务模式下服务无法互调**：确认 Nacos 已启动且各服务已注册，`-api` 模块的 `@FeignClient` 服务名与 Nacos 服务名一致。
- **多租户异常**：确认 `yudao.tenant.enable` 与租户数据库表数据正确，访问需带正确的 `tenant-id` 请求头或默认租户。