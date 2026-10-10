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

前缀绑定到 [`AiProperties`](../RuoYi-Vue-Plus/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/config/AiProperties.java)。
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
| `image-max-bytes` | `524288`（512KB） | 聊天上传**图片**的单张大小上限 |
| `document-max-bytes` | `5242880`（5MB） | 聊天上传**文档**的单文件大小上限 |
| `document-inline-max-chars` | `20000` | 文档解析后**内联进本轮用户消息**的最大字符数，超出保留首尾截断 |
| `max-attachments-per-message` | `5` | 单条消息可携带的附件数上限 |

> 附件（多模态对话）：图片在装配期读回字节转 **Base64 data URI** 经 `ImageBlock` 下发（**需视觉模型**；2026-10-10 起**上传时**按 `ai_model_config.supports_vision` 对「入口智能体 + 本会话指定专家」做前置校验，未声明支持即拒绝并给出提示），文档用 `AiDocumentParser` 解析为**纯文本内联**（不切片、不向量化——那是知识库链路）；两者的内容块随任务片段一并下发给专家。仅放行解析器支持的后缀，扫描件 / 纯图片 PDF 解析不出文本会被拒绝。

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
| 数据库表 `sys_oss_config`（**不在 yaml**） | `endpoint` / `domain_url` | 平台文件存储（OSS）的**两个坐标**：`endpoint` 供**服务端读写**（容器部署写 `minio:9000`，容器网络内可达），`domain_url` 供**前端展示**（返回给浏览器的 URL，必须**浏览器可达**——PG 栈 `http://localhost:9000`、MySQL 8.0 栈 `http://localhost:9002`）。解析 / 向量化走内部地址，故 `domain_url` 配错只表现为「图片 / 文档在线预览加载失败」。容器部署由 `deploy*/sql/50-oss-endpoint.sql` 在首次建库时写入，**存量数据卷不会重跑**，需按部署文档手工补一次 |

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
| 8 | 数据库表 `sys_oss_config.domain_url` | 必须是**浏览器可达**地址；容器部署默认给 `localhost`，从其它机器 / 公网访问需改成实际域名或 IP（各栈端口见部署文档） |

## 5. 相关文档

- [architecture.md](architecture.md) —— 这些配置在链路里作用于哪一环
- [development.md](development.md) —— 新增业务工具
- [../deploy/README.md](../deploy/README.md) / [../deploy-mysql/README.md](../deploy-mysql/README.md) —— 容器化部署、变量覆盖与已知坑（含 OSS 地址与「文档需先解析再向量化」）
- [../e2e/](../e2e) —— 端到端脚本：改完配置后在真实栈上验证（L1~L5）