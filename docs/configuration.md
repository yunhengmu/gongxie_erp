# 配置参考

## 1. 配置文件位置

| 文件 | 内容 |
|---|---|
| `code/back-end/ruoyi-admin/src/main/resources/application.yml` | 通用配置 + **AI 平台配置 `bizbuddy.ai.*`** + 安全排除路径 |
| `.../application-dev.yml` | 开发环境：数据源、Redis、SnailJob 客户端、SQL 日志 |
| `.../application-prod.yml` | 生产环境：同上（日志更安静、连接池更大） |
| `.env`（可选，见 `deploy/deploy-pg/.env.example`） | 容器化部署的端口与口令变量 |

环境由 Maven profile 决定（`pom.xml` 的 `<profiles>`，默认 `dev`）：

```bash
./mvnw -pl ruoyi-admin spring-boot:run -Pprod
```

## 2. AI 平台配置 `bizbuddy.ai`

前缀绑定到 [`AiProperties`](../code/back-end/ruoyi-modules/biz-buddy/src/main/java/org/dromara/ai/config/AiProperties.java)。
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
| `memory-enabled` | `true` | 长期记忆：开启后每轮自动抽取事实并合并进 `MEMORY.md`（产生额外模型调用成本） |
| `memory-max-context-tokens` | `8000` | 记忆注入 system prompt 的 Token 上限 |
| `memory-consolidation-max-tokens` | `4000` | 合并时可读的每日流水 Token 上限 |
| `memory-consolidation-min-gap-minutes` | `30` | 两次合并的最小间隔 |
| `memory-daily-file-retention-days` | `90` | 每日流水保留天数（超期移入 `memory/archive/`） |
| `memory-archive-retention-days` | `365` | 归档目录保留天数（到期后由自动清理删除） |
| `memory-session-retention-days` | `180` | 会话日志保留天数 |
| `memory-flush-mode` | `throttled` | 事实抽取策略：`always` / `throttled` / `never` |
| `memory-flush-throttle-minutes` | `30` | 节流间隔（`throttled` 时生效） |
| `memory-auto-archive-enabled` | `true` | 是否启用自动归档与清理（每日遍历有会话历史的用户） |
| `memory-maintenance-interval-minutes` | `1440` | 自动归档与清理的执行间隔（分钟） |
| `workspace-enabled` | `true` | 是否注入工作区上下文（`AGENTS.md` 等），与记忆开关解耦 |
| `workspace-additional-context-files` | `[]` | 附加注入 system prompt 的文件清单（工作区相对路径） |

### 2.6 MCP 出口与当前时间

| 配置项 | 默认 | 说明 |
|---|---|---|
| `mcp-server-auth-enabled` | `true` | `/mcp` 是否校验访问口令 |
| `mcp-server-token` | 空 | 访问口令；**生产必须配置**，否则开启校验时任何请求都会被拒 |
| `current-time-enabled` | `true` | 每轮向 system prompt 注入中文当前时间 |
| `current-time-zone` | `Asia/Shanghai` | 注入时区；容器化部署务必显式配置（否则可能退化为 UTC） |

### 2.7 RAG 检索增强（`bizbuddy.ai.rag.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `rerank-top-n` | `30` | 送入重排的候选数上限 |
| `rerank-timeout-ms` | `12000` | 重排调用超时 |
| `rerank-mode` | `score_fusion` | 融合模式：`replace`（纯替换）/ `rank_rrf`（排名 RRF）/ `score_fusion`（分数归一化加权） |
| `rerank-alpha` | `0.5` | `score_fusion` 的重排权重（0~1） |
| `rerank-rrf-k` | `60` | `rank_rrf` 的平滑常数 |
| `rerank-trigger-margin` | `0` | 条件触发：首条与次条向量分差 ≥ 本值时跳过重排（`0`/负值＝总是重排） |
| `assemble-dedup-enabled` | `true` | 上下文装配：确定性去重 |
| `assemble-merge-adjacent-enabled` | `true` | 相邻切片合并 |
| `assemble-merge-window` | `1` | 相邻合并窗口 |
| `assemble-dedup-jaccard` | `0.9` | 去重判据（字符 n-gram Jaccard 阈值） |
| `assemble-max-chars` | `6000` | 装配后正文的绝对字符上限 |
| `assemble-max-tokens` | `6000` | 装配后正文的 Token 预算（与字符上限取小，`CJK≈1 token/字符`） |

> **2026-10-14**：`rerank-enabled` / `rerank-model` / `rerank-endpoint` 已删除——重排的**模型与开关**已迁至「模型配置」（`model_type=rerank`，`baseUrl` 填完整重排端点、密钥为该模型自带）+「知识库」配置（`rerank_enabled` / `rerank_model_id`）；本节只保留**与模型无关的算法参数**。启用且绑定可用重排模型才重排，否则保持向量原序；未配密钥 / 非 2xx / 超时 / 结构异常一律**降级为原顺序**。上下文装配（`assemble-*`）只作用于返回集后处理，**不改排序**。改动后可用 `e2e-http/eval/eval-rag.mjs` 做 A/B。

### 2.8 安全合规（`bizbuddy.ai.security.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 内容审核总开关（默认关以免误杀；开启后按 `action` 处置） |
| `engine` | `local` | 审核引擎：`local`（本地词库）/ `external`（外部内容安全 API；缺端点或密钥自动降级本地词库，调用失败按未命中放行） |
| `external.endpoint` / `external.api-key` / `external.timeout-ms` | 空 / 空 / `3000` | 外部引擎服务地址、密钥与单次超时（`engine=external` 时使用；默认值，页面上写入运行期配置覆盖） |
| `action` | `log` | 命中处置：`log`（只留痕）/ `mask`（脱敏放行）/ `block`（阻断） |
| `input-check` | `true` | 审核用户输入 |
| `output-check` | `true` | 审核模型输出 |
| `channel-out-check` | `true` | 审核渠道出站回复（对外发布，合规红线） |
| `tool-result-mask` | `true` | 审核工具结果 |
| `kb-ingest-check` | `true` | 审核知识库入库内容（切片送向量库前） |
| `mcp-out-check` | `true` | 审核 MCP 出口（对外只读工具的入参 / 出参） |
| `prompt-hardening` | `true` | 提示词注入防护（system prompt 加固 + 外部内容边界标记） |
| `mask-pii` | `true` | PII 脱敏（手机号 / 身份证 / 邮箱 / 银行卡 / IP），**独立于** `enabled` |
| `hold-back-chars` | `32` | 流式输出尾部缓冲字符数（避免敏感片段被切片切断） |
| `excerpt-chars` | `200` | 安全事件留痕的命中片段最大字符数 |

> 以上均为**默认值**，可在运行期「安全合规配置」页覆盖（存 Redis、即时生效）。敏感词与安全事件分别在「敏感词库」「安全事件」页维护 / 查阅。

### 2.9 中断任务回收（`bizbuddy.ai.reaper.*`）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否启用回收 |
| `stale-minutes` | `60` | 判定「已中断」的超时分钟数：最后更新时间早于该阈值才回收，避免多副本误伤；知识库批量向量化按 2 线程排队、队列上限 200，排队可能较久，故不宜取太小 |
| `scan-interval-seconds` | `300` | 周期扫描间隔（秒），最小 30 |

> 回收范围：文档解析 / 向量化、编排运行 `running`、定时任务执行 `running` 等长期停在「进行中」的记录。

## 3. 非 AI 配置（按需）

| 位置 | 项 | 说明 |
|---|---|---|
| `application.yml` | `server.port` | 默认 `8080`；Jetty，`threads.max=20000`（SSE 长连接持续占槽位，按在线量调） |
| `application.yml` | `security.excludes` | 免鉴权路径；渠道入站回调 `/ai/channel/callback/**` 在此放开（安全靠验签/口令） |
| `application.yml` | `api-decrypt` | 接口加解密开关与 RSA 密钥对；**生产必须替换**（环境变量 `API_DECRYPT_PUBLIC_KEY` / `API_DECRYPT_PRIVATE_KEY`），需与前端 `.env.*` 的 `VITE_APP_RSA_*` 成对更换 |
| `application.yml` | `sa-token` | 认证：token 名、并发登录、JWT 秘钥（环境变量 `SA_TOKEN_JWT_SECRET_KEY`，**生产必须替换**） |
| `application.yml` / `application-prod.yml` | `ProductionConfigGuard`（启动自检） | **所有 profile 启动时**逐项比对出厂示例值（JWT 秘钥 / 接口加密密钥对 / `bizbuddy.ai.crypto-key` / 数据库·Redis·Milvus 口令 / SnailJob 令牌），命中即**拒绝启动**并列出命中项。原实现只认 `prod`，而两套编排默认 profile 是 `dev`，于是「按默认方式部署」恰好绕过护栏；现默认对所有 profile 生效，非 `prod` 可用 `bizbuddy.security.guard.mode=warn`（环境变量 `BIZBUDDY_SECURITY_GUARD_MODE`）显式降级为仅告警（本地开发逃生口），`prod` 下该开关无效 |
| `application-dev.yml` | `spring.datasource.dynamic.datasource.master.*` | 主数据源连接（dynamic-datasource）：PostgreSQL 14 或 MySQL 8.0，仅 JDBC URL 不同（驱动由 Hikari 按 URL 自动匹配，不在配置中声明） |
| `application-dev.yml` | `spring.data.redis.*` / `redisson.*` | Redis 连接与 Redisson 连接池；dev 配置**要求必须设置 Redis 密码** |
| `application-dev.yml` | `snail-job.*` | 调度客户端：`enabled`、`group`、`token`、`namespace`、服务端地址 |
| 数据库表 `sys_oss_config`（**不在 yaml**） | `endpoint` / `domain_url` / `access_policy` | 平台文件存储（OSS）坐标：`endpoint` 供**服务端读写**（容器部署写 `minio:9000`，容器网络内可达），`domain_url` 供**前端展示**（返回给浏览器的 URL，必须**浏览器可达**——PG 栈 `http://localhost:9000`、MySQL 8.0 栈 `http://localhost:9002`），`access_policy` 容器部署为 **`0`（私有桶）**。私有桶下文件访问统一由后端**读取时现算预签名 URL**（对外按 `domain_url`、服务端按 `endpoint` 签名），故 `domain_url` 配错会导致前端预览 / 下载失败。容器部署由 `deploy/deploy-*/sql/50-oss-endpoint.sql` 在首次建库时写入，**存量数据卷不会重跑**，需按部署文档手工补一次 |
| `application.yml` | `oss.presign-expire-seconds` | 私有桶预签名 URL 有效期（秒），默认 `120`；过短会导致预览 / 下载中途失效，过长则弱化私有桶的访问控制（改后需重启 `ruoyi-admin`） |

## 4. 生产环境必改清单

> **启动自检**：**任何 profile** 启动时，[`ProductionConfigGuard`](../code/back-end/ruoyi-admin/src/main/java/org/dromara/web/config/ProductionConfigGuard.java) 都会逐项比对下表 1~5 及 7 的**出厂示例值**，命中即**直接拒绝启动**并列出命中项与对应环境变量——即这些项已不是「靠自觉」，漏改会立刻失败。原实现只对 `prod` 生效，导致默认（`dev` profile）部署可带出厂口令跑起来；现默认对所有 profile 生效，非 `prod` 可用 `BIZBUDDY_SECURITY_GUARD_MODE=warn` 显式降级为「仅告警」，`prod` 下该开关被忽略。

| # | 配置项 | 环境变量 | 原因 |
|---|---|---|---|
| 1 | `bizbuddy.ai.crypto-key` | `BIZBUDDY_AI_CRYPTO_KEY` | 模型密钥的加密根，默认值等于公开（长度须 16/24/32） |
| 2 | `bizbuddy.ai.chat.mcp-server-token` | —（运行期配置） | `/mcp` 出口口令，在「MCP 服务端」页配置（存 Redis、即时生效）；**留空且开启校验时一律拒绝请求（fail-closed）**，故不在启动自检内 |
| 3 | `api-decrypt` 的 RSA 公私钥对 + 前端 `VITE_APP_RSA_*` | `API_DECRYPT_PUBLIC_KEY` / `API_DECRYPT_PRIVATE_KEY` | 默认密钥对等于公开，且必须与前端成对更换 |
| 4 | `sa-token.jwt-secret-key` | `SA_TOKEN_JWT_SECRET_KEY` | 默认值为示例 |
| 5 | 数据库 / Redis / Milvus 口令 | `POSTGRES_PASSWORD`（MySQL 栈 `MYSQL_PASSWORD`） / `REDIS_PASSWORD` / `MILVUS_PASSWORD` | 默认口令仅用于本地 |
| 6 | `bizbuddy.ai.chat.current-time-zone` | `BIZBUDDY_AI_CHAT_CURRENT_TIME_ZONE` | 容器未挂 tzdata 时会退化为 UTC（默认 `Asia/Shanghai`，一般无需改） |
| 7 | `snail-job.token` | `SNAILJOB_TOKEN` | 接入令牌默认随源码公开；须与 `sj_group_config` 表一致 |
| 8 | 数据库表 `sys_oss_config.domain_url` | —（数据库配置） | 必须是**浏览器可达**地址；容器部署默认给 `localhost`，从其它机器 / 公网访问需改成实际域名或 IP（各栈端口见部署文档） |

> 环境变量名与部署侧的登记见 `deploy/deploy-pg/.env.example` / `deploy/deploy-mysql/.env.example` 的「出厂默认值收口自检」段。

## 5. 相关文档

- [architecture.md](architecture.md) —— 这些配置在链路里作用于哪一环
- [development.md](development.md) —— 新增业务工具
- [../deploy/deploy-pg/README.md](../deploy/deploy-pg/README.md) / [../deploy/deploy-mysql/README.md](../deploy/deploy-mysql/README.md) —— 容器化部署、变量覆盖与已知坑（含 OSS 地址与「文档需先解析再向量化」）
- [../e2e-http/](../e2e-http) —— 端到端脚本：改完配置后在真实栈上验证（L1~L11 + RAG 检索质量评测 `e2e-http/eval/`）