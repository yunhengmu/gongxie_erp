# 配置参考

## 1. 配置文件位置

| 文件 | 内容 |
|---|---|
| `RuoYi-Vue-Plus/ruoyi-admin/src/main/resources/application.yml` | 通用配置 + **AI 平台配置 `bizbuddy.ai.*`** + 安全排除路径 |
| `.../application-dev.yml` | 开发环境：数据源、Redis、SnailJob 客户端、SQL 日志 |
| `.../application-prod.yml` | 生产环境：同上（日志更安静、连接池更大） |
| `.env`（可选，见 `deploy/.env.example`） | 容器化部署的端口与口令变量 |

环境由 Maven profile 决定（`pom.xml` 的 `<profiles>`，默认 `dev`）：

```bash
./mvnw -pl ruoyi-admin spring-boot:run -Pprod
```

## 2. AI 平台配置 `bizbuddy.ai`

前缀绑定到 [`AiProperties`](../RuoYi-Vue-Plus/ruoyi-modules/ruoyi-ai/src/main/java/org/dromara/ai/config/AiProperties.java)。
下表只列**需要你关注的**项，其余保持默认即可。

### 2.1 密钥与模型

| 配置项 | 默认 | 说明 |
|---|---|---|
| `crypto-key` | `bizbuddy-ai-2026` | 模型 API Key 的 AES 加密密钥，长度须为 **16/24/32** 位。**生产必须替换** |
| `test-timeout-seconds` | `30` | 模型连通性测试超时（秒） |
| `embedding-batch-size` | `10` | 向量化单批文本条数 |
| `embedding-max-chars` | `8000` | 单条文本最大字符数，超出截断 |
| `retrieval-score-threshold` | `0.3` | 知识库检索默认相似度阈值 |

> 具体模型（对话 / 向量 / 摘要 / 记忆 / 子智能体）不在配置文件里，而在**「模型配置」页**维护，密钥 AES 加密后入库。**干净库启动后必须先配一个对话模型**，否则 AI 对话不可用。

### 2.2 向量库 Milvus

| 配置项 | 默认 | 说明 |
|---|---|---|
| `milvus.uri` | `http://127.0.0.1:19530` | gRPC 地址 |
| `milvus.username` | `${MILVUS_USERNAME:root}` | 支持环境变量覆盖 |
| `milvus.password` | `${MILVUS_PASSWORD:Milvus}` | 同上 |
| `milvus.connect-timeout-ms` | `10000` | 连接超时 |

### 2.3 对话与编排（`bizbuddy.ai.chat.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `workspace-root` | `./data/agent-workspace` | 智能体工作区根目录（相对 jar 运行目录）；记忆与 `AGENTS.md` 落在这里 |
| `default-max-iters` | `10` | 单轮对话最大迭代次数（智能体未配置时生效） |
| `stream-timeout-ms` | `0` | SSE 连接超时，`0` 表示不超时 |
| `task-timeout-ms` | `600000` | 定时任务同步等待上限 |
| `expert-timeout-seconds` | `180` | 单次专家调用同步等待上限 |
| `route-budget-expert-tokens` | `60000` | 单轮编排的专家轮 Token 预算，`0` 不限 |
| `route-budget-seconds` | `180` | 单轮编排墙钟预算，`0` 不限 |
| `expert-write-dedup-enabled` | `true` | 专家链路写工具**短期去重**（防重复副作用） |
| `expert-write-dedup-ttl-seconds` | `1800` | 去重有效期，需覆盖一次编排（含 HITL 等待） |
| `tool-output-preview-chars` | `2000` | 工具结果下发前端的最大字符数 |
| `permission-mode` | `DEFAULT` | 全局工具权限模式兜底：未命中显式规则的工具一律需确认 |
| `pending-confirm-ttl-minutes` | `30` | 待确认工具调用的有效期，超时需重新发起 |

### 2.4 上下文压缩与大结果卸载

| 配置项 | 默认 | 说明 |
|---|---|---|
| `compaction-enabled` | `true` | 超长对话自动摘要 |
| `compaction-trigger-messages` | `50` | 触发压缩的消息条数 |
| `compaction-trigger-tokens` | `80000` | 触发压缩的 Token 数 |
| `compaction-keep-messages` | `20` | 压缩后保留的尾部消息条数 |
| `compaction-flush-before-compact` | `true` | 压缩前是否落盘留痕 |
| `compaction-offload-before-compact` | `false` | 压缩前是否卸载为工作区 jsonl |
| `tool-result-eviction-enabled` | `true` | 超大工具结果落盘，仅回填首尾预览 |
| `tool-result-eviction-max-chars` | `80000` | 卸载阈值 |
| `tool-result-eviction-preview-chars` | `2000` | 预览字符数（首尾各一份） |

### 2.5 计划模式 / 长期记忆 / 工作区

| 配置项 | 默认 | 说明 |
|---|---|---|
| `plan-mode-enabled` | `false` | 进入只读设计阶段，产出计划文件并经确认后执行 |
| `plan-file-directory` | `plans` | 计划文件目录（相对工作区） |
| `task-list-enabled` | `false` | 是否启用任务清单工具 |
| `memory-enabled` | `false` | 长期记忆：开启后每轮自动抽取事实并合并进 `MEMORY.md`（产生额外模型调用成本） |
| `memory-max-context-tokens` | `8000` | 记忆注入 system prompt 的 Token 上限 |
| `memory-consolidation-max-tokens` | `4000` | 合并时可读的每日流水 Token 上限 |
| `memory-consolidation-min-gap-minutes` | `30` | 两次合并的最小间隔 |
| `memory-daily-file-retention-days` | `90` | 每日流水保留天数 |
| `memory-session-retention-days` | `180` | 会话日志保留天数 |
| `memory-flush-mode` | `always` | 事实抽取策略：`always` / `throttled` / `never` |
| `memory-flush-throttle-minutes` | `30` | 节流间隔（`throttled` 时生效） |
| `workspace-enabled` | `true` | 是否注入工作区上下文（`AGENTS.md` 等），与记忆开关解耦 |
| `workspace-additional-context-files` | `[]` | 附加注入 system prompt 的文件清单（工作区相对路径） |

### 2.6 MCP 出口与当前时间

| 配置项 | 默认 | 说明 |
|---|---|---|
| `mcp-server-auth-enabled` | `true` | `/mcp` 是否校验访问口令 |
| `mcp-server-token` | 空 | 访问口令；**生产必须配置**，否则开启校验时任何请求都会被拒 |
| `current-time-enabled` | `true` | 每轮向 system prompt 注入中文当前时间 |
| `current-time-zone` | `Asia/Shanghai` | 注入时区；容器化部署务必显式配置（否则可能退化为 UTC） |

## 3. 非 AI 配置（按需）

| 位置 | 项 | 说明 |
|---|---|---|
| `application.yml` | `server.port` | 默认 `8080`；Jetty，`threads.max=20000`（SSE 长连接持续占槽位，按在线量调） |
| `application.yml` | `security.excludes` | 免鉴权路径；渠道入站回调 `/ai/channel/callback/**` 在此放开（安全靠验签/口令） |
| `application.yml` | `api-decrypt` | 接口加解密开关与 RSA 密钥对；**生产必须替换**，需与前端 `.env.*` 成对更换 |
| `application.yml` | `sa-token` | 认证：token 名、并发登录、JWT 秘钥（**生产必须替换** `jwt-secret-key`） |
| `application-dev.yml` | `spring.datasource.dynamic.datasource.master.*` | 主数据源连接（dynamic-datasource）：PostgreSQL 14 或 MySQL 8.0，仅 JDBC URL 不同（驱动由 Hikari 按 URL 自动匹配，不在配置中声明） |
| `application-dev.yml` | `spring.data.redis.*` / `redisson.*` | Redis 连接与 Redisson 连接池；dev 配置**要求必须设置 Redis 密码** |
| `application-dev.yml` | `snail-job.*` | 调度客户端：`enabled`、`group`、`token`、`namespace`、服务端地址 |

## 4. 生产环境必改清单

| # | 项 | 原因 |
|---|---|---|
| 1 | `bizbuddy.ai.crypto-key` | 模型密钥的加密根，默认值等于公开 |
| 2 | `bizbuddy.ai.mcp-server-token` | 否则 `/mcp` 出口要么不可用要么开放 |
| 3 | `api-decrypt` 的 RSA 公私钥对 + 前端 `VITE_APP_RSA_*` | 默认密钥对等于公开 |
| 4 | `sa-token.jwt-secret-key` | 默认值为示例 |
| 5 | 数据库 / Redis / Milvus 口令 | 默认口令仅用于本地 |
| 6 | `bizbuddy.ai.current-time-zone` | 容器未挂 tzdata 时会退化为 UTC |
| 7 | `spring.data.redis.password` | dev 配置要求必填，勿照抄默认值 |

## 5. 相关文档

- [architecture.md](architecture.md) —— 这些配置在链路里作用于哪一环
- [development.md](development.md) —— 新增业务工具
- [../deploy/docker-compose.yml](../deploy/docker-compose.yml) —— 容器化部署与变量覆盖