package org.dromara.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * BizBuddy AI 平台配置属性。
 *
 * @author BizBuddy
 */
@Data
@Component
@ConfigurationProperties(prefix = "bizbuddy.ai")
public class AiProperties {

    /**
     * 模型密钥加密用的 AES 秘钥（长度必须为 16 / 24 / 32 位，生产环境必须替换）
     */
    private String cryptoKey = "bizbuddy-ai-2026";

    /**
     * 连通性测试的默认超时时间（秒），未配置模型超时时生效
     */
    private int testTimeoutSeconds = 30;

    /**
     * 向量化时单次请求提交的文本条数（各家 OpenAI 兼容服务上限不同，取兼容值 10）
     */
    private int embeddingBatchSize = 10;

    /**
     * 向量化时单条文本的最大字符数；超出部分截断，避免超过模型单行 Token 上限
     */
    private int embeddingMaxChars = 8000;

    /**
     * 向量检索返回的默认相似度阈值
     */
    private double retrievalScoreThreshold = 0.3;

    /**
     * Milvus 向量库连接配置
     */
    private Milvus milvus = new Milvus();

    /**
     * 对话与 Agent 装配配置
     */
    private Chat chat = new Chat();

    /**
     * 渠道接入配置（REQ-AI-24）：入站幂等 / 限流 / 多媒体下载
     */
    private Channel channel = new Channel();

    /**
     * 安全合规配置（脱敏 + 内容安全）：默认值来源，可被「安全合规配置」页运行期覆盖
     */
    private Security security = new Security();

    /**
     * RAG 检索增强配置（当前仅检索重排 Rerank）：默认关闭，可按需开启并对 A/B
     */
    private Rag rag = new Rag();

    /**
     * 结构化决策（百炼决策模型 / TypeSafe System One 协议）配置：默认值来源，
     * 可被「安全合规配置」页运行期覆盖（Redis 键 {@code bizbuddy:ai:runtime:jev}）
     */
    private Jev jev = new Jev();

    /**
     * Milvus 连接配置。
     */
    @Data
    public static class Milvus {

        /**
         * 服务地址（gRPC）
         */
        private String uri = "http://127.0.0.1:19530";

        /**
         * 用户名（未开启鉴权时留空）
         */
        private String username;

        /**
         * 密码（未开启鉴权时留空）
         */
        private String password;

        /**
         * 连接超时时间（毫秒）
         */
        private long connectTimeoutMs = 10000;
    }

    /**
     * 渠道接入配置（REQ-AI-24）。
     */
    @Data
    public static class Channel {

        /**
         * 单 peer 单窗口允许的入站事件数（超出即进入冷却）
         */
        private int maxEventsPerWindow = 20;

        /**
         * 限流窗口（秒）
         */
        private int windowSeconds = 60;

        /**
         * 超限后的冷却时长（秒）
         */
        private int cooldownSeconds = 60;

        /**
         * 入站事件幂等 TTL（秒）
         */
        private int dedupTtlSeconds = 300;

        /**
         * 入站媒体单文件字节上限（默认 5MB）
         */
        private int mediaMaxBytes = 5 * 1024 * 1024;
    }

    /**
     * 安全合规配置（脱敏 + 内容安全）。
     *
     * <p>默认值口径：内容审核 <b>默认关闭</b>（{@code enabled=false}）以免误杀；开启后默认
     * {@code action=log}（只留痕不阻断）灰度。PII 脱敏 {@code maskPii} 独立开关、默认开启。</p>
     */
    @Data
    public static class Security {

        /**
         * 内容安全审核总开关（默认关闭；开启后按 action 处置）
         */
        private boolean enabled = false;

        /**
         * 审核引擎：local（本地词库）/ external（外部内容安全 API，预留）
         */
        private String engine = "local";

        /**
         * 命中处置动作：log（只记录）/ mask（脱敏放行）/ block（阻断）
         */
        private String action = "log";

        /**
         * 是否审核用户输入
         */
        private boolean inputCheck = true;

        /**
         * 是否审核模型输出
         */
        private boolean outputCheck = true;

        /**
         * 是否审核渠道出站回复（对外发布，合规红线）
         */
        private boolean channelOutCheck = true;

        /**
         * 是否审核工具结果（落库留痕与前端下发；框架内部回填模型的环节平台不可见）
         */
        private boolean toolResultMask = true;

        /**
         * 是否审核知识库入库内容（切片送向量库前；BLOCK 则拒绝入库）
         */
        private boolean kbIngestCheck = true;

        /**
         * 是否审核 MCP 出口（对外暴露给第三方 AI 的只读工具：入参 / 出参）
         */
        private boolean mcpOutCheck = true;

        /**
         * 是否审核「结构化决策出境」（外呼决策模型的 {@code state}；PII 脱敏为强制项、不在此开关内）
         */
        private boolean jevOutboundCheck = true;

        /**
         * 是否启用提示词注入防护（system prompt 加固 + 外部内容边界标记）
         */
        private boolean promptHardening = true;

        /**
         * 是否做 PII 脱敏（手机号 / 身份证 / 邮箱 / 银行卡 / IP），独立于内容审核开关
         */
        private boolean maskPii = true;

        /**
         * 流式输出的尾部缓冲字符数：保留尾部长度的文本待下一次拼接后再扫描，
         * 避免 PII / 敏感词被切片切断（越大越安全、首字延迟略增）
         */
        private int holdBackChars = 32;

        /**
         * 安全事件留痕中的命中片段最大字符数
         */
        private int excerptChars = 200;

        /**
         * 外部审核引擎配置（engine=external 时使用；本轮仅占位）
         */
        private External external = new External();
    }

    /**
     * 外部内容安全引擎配置（预留）。
     */
    @Data
    public static class External {

        /**
         * 服务地址
         */
        private String endpoint = "";

        /**
         * 访问密钥
         */
        private String apiKey = "";
    }

    /**
     * 对话与 Agent 装配配置（默认值写在类中，不依赖 application.yml）。
     */
    @Data
    public static class Chat {

        /**
         * Agent 工作区根目录（相对 jar 运行目录）
         */
        private String workspaceRoot = "./data/agent-workspace";

        /**
         * 单轮对话默认最大迭代次数（智能体定义未配置时生效）
         */
        private int defaultMaxIters = 10;

        /**
         * SSE 连接超时（毫秒），0 表示不超时
         */
        private long streamTimeoutMs = 0L;

        /**
         * 无人值守执行（定时任务）同步等待模型回答的超时（毫秒），0 表示不超时
         */
        private long taskTimeoutMs = 600000L;

        /**
         * 单次专家调用的同步等待上限（秒），0 表示不超时（REQ-AI-30）。
         *
         * <p>专家是「任务片段 + 结构化提交」的同步调用，必须有上限以免一次委派拖垮整轮回答；
         * 但专家循环通常需要两轮模型调用（先按需调工具、再提交结论），故默认值高于框架子智能体的
         * 120 秒上限，为慢模型留出余量。</p>
         */
        private int expertTimeoutSeconds = 180;

        /**
         * 单轮编排的**专家轮 Token 预算**（专家调用输入 + 输出累计），0 表示不限（REQ-AI-30 Phase 2 第四项）。
         *
         * <p>口径：只覆盖**专家轮**（入口轮 Token 不纳入——编排器看不到、也无法在调用前预估）；
         * 且为「**事后累计 + 触顶即停**」：单次调用花费无法预估，故每次专家调用结束后累计，
         * 一旦超限就不再发起重试 / 换专家，直接降级自答并如实声明。</p>
         *
         * <p>本项为**默认值**，可在「编排预算」页运行期覆盖（Redis，{@code AiRuntimeConfigStore#routeBudget}）。</p>
         */
        private long routeBudgetExpertTokens = 60000L;

        /**
         * 单轮编排的**墙钟预算**（秒），0 表示不限（REQ-AI-30 Phase 2 第四项）。
         *
         * <p>与 Token 预算同时判定；HITL 暂停-恢复跨两次请求，恢复段按恢复段自身重新计时
         * （不跨请求累计——{@code ai_route_run} 无编排开始时刻列）。</p>
         *
         * <p>本项为**默认值**，可在「编排预算」页运行期覆盖（Redis）。</p>
         */
        private int routeBudgetSeconds = 180;

        /**
         * 是否启用**专家写工具短期去重**（REQ-AI-30 安全护栏）。
         *
         * <p>作用域＝**专家会话**（{@code routeRunId#expertAgentId}，一次编排一个，
         * 含契约失败后的重试与 HITL 恢复段）：同一作用域内「工具名 + 规范化入参」相同的
         * **写工具**调用在 TTL 内只真正执行一次，命中即回填「已跳过重复执行」给模型。</p>
         *
         * <p><b>为什么需要</b>：契约校验不合格会触发原地重试、HITL 确认后还有恢复段续跑，
         * 若模型在这些后续轮次里再次调用同一个写工具，就会产生**重复副作用**
         * （实测风险：重复发布系统公告）。</p>
         *
         * <p><b>为什么只对专家链路生效</b>：入口 / 普通智能体的会话是**长期会话**，
         * 在其中去重会误伤「同一会话里再执行一次同样操作」的正常诉求
         * （例如用户明确要求再发一条相同的公告）。</p>
         */
        private boolean expertWriteDedupEnabled = true;

        /**
         * 专家写工具去重的**有效期（秒）**：需覆盖一次编排（含 HITL 等待）的全过程
         */
        private long expertWriteDedupTtlSeconds = 1800L;

        /**
         * 工具结果下发给前端时的最大字符数
         */
        private int toolOutputPreviewChars = 2000;

        /**
         * 全局工具权限模式（AgentScope PermissionMode 名称），默认 DEFAULT：
         * 未命中显式规则的工具一律需确认；可选 ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK
         */
        private String permissionMode = "DEFAULT";

        /**
         * 等待人工确认的工具调用有效期（分钟），超时后需重新发起
         */
        private int pendingConfirmTtlMinutes = 30;

        /**
         * 是否启用上下文压缩（超长对话自动摘要，避免超出模型上下文窗口，REQ-AI-21）
         */
        private boolean compactionEnabled = true;

        /**
         * 触发压缩的消息条数阈值
         */
        private int compactionTriggerMessages = 50;

        /**
         * 触发压缩的 Token 数阈值
         */
        private int compactionTriggerTokens = 80000;

        /**
         * 压缩后保留的尾部消息条数
         */
        private int compactionKeepMessages = 20;

        /**
         * 压缩前是否将完整历史落盘留痕
         */
        private boolean compactionFlushBeforeCompact = true;

        /**
         * 压缩前是否把历史卸载为工作区下的 jsonl 文件
         */
        private boolean compactionOffloadBeforeCompact = false;

        /**
         * 是否启用大工具结果卸载（超出阈值的工具结果落盘，仅回填首尾预览）
         */
        private boolean toolResultEvictionEnabled = true;

        /**
         * 大工具结果卸载阈值（字符）
         */
        private int toolResultEvictionMaxChars = 80000;

        /**
         * 大工具结果卸载后保留的预览字符数（首尾各一份）
         */
        private int toolResultEvictionPreviewChars = 2000;

        /**
         * 是否启用计划模式（REQ-AI-22）：进入只读设计阶段，产出计划文件并经人工确认后执行
         */
        private boolean planModeEnabled = false;

        /**
         * 计划文件目录（相对智能体工作区）
         */
        private String planFileDirectory = "plans";

        /**
         * 是否启用任务清单（todo_write，REQ-AI-22）
         */
        private boolean taskListEnabled = false;

        /**
         * 是否启用长期记忆（REQ-AI-09）：开启后每轮对话自动抽取事实写入每日流水，
         * 并按节流策略合并进 MEMORY.md；默认关闭以避免未经确认的额外模型调用成本
         */
        private boolean memoryEnabled = false;

        /**
         * 记忆注入 system prompt 的上下文 Token 上限（超出时截断）
         */
        private int memoryMaxContextTokens = 8000;

        /**
         * 合并（consolidation）时允许读取的每日流水 Token 上限
         */
        private int memoryConsolidationMaxTokens = 4000;

        /**
         * 两次合并之间的最小间隔（分钟），避免频繁触发合并
         */
        private int memoryConsolidationMinGapMinutes = 30;

        /**
         * 每日流水（memory/YYYY-MM-DD.md）归档保留天数
         */
        private int memoryDailyFileRetentionDays = 90;

        /**
         * 会话日志保留天数
         */
        private int memorySessionRetentionDays = 180;

        /**
         * 事实抽取（flush）触发策略：always 每轮抽取 / throttled 按间隔节流 / never 不自动抽取
         */
        private String memoryFlushMode = "always";

        /**
         * 事实抽取节流间隔（分钟），仅 {@code memoryFlushMode=throttled} 时生效
         */
        private int memoryFlushThrottleMinutes = 30;

        /**
         * 是否启用工作区上下文注入（REQ-AI-08）：开启后每轮把工作区的人格与行为约定文件
         * （AGENTS.md）及附加上下文文件全文注入 system prompt，支持按用户覆盖。
         *
         * <p>默认开启，且与长期记忆开关解耦——记忆关闭时人格与行为约定仍然生效；
         * 关闭本开关且记忆也关闭时，才彻底停止 system prompt 中的工作区段落注入。</p>
         */
        private boolean workspaceEnabled = true;

        /**
         * 附加上下文文件清单（工作区相对路径，全文注入 system prompt）。
         * 与 {@code AGENTS.md} 一样支持按用户覆盖（用户目录优先，工作区根为共享兜底）
         */
        private List<String> workspaceAdditionalContextFiles = new ArrayList<>();

        /**
         * MCP 出口（{@code /mcp}）是否启用访问口令校验（REQ-AI-15），默认开启。
         *
         * <p>开启时，外部调用方必须携带与运行期配置一致的口令（{@code Authorization: Bearer <token>}
         * 或 {@code X-MCP-Token: <token>}）；关闭时 {@code /mcp} 完全开放，仅建议在内网调试时临时关闭。
         */
        private boolean mcpServerAuthEnabled = true;

        /**
         * MCP 出口访问口令（REQ-AI-15）。留空表示未配置：若同时开启了口令校验，
         * 则任何请求都会被拒绝并提示先到「MCP 服务端」页配置口令。
         */
        private String mcpServerToken = "";

        /**
         * 是否在每轮对话向 system prompt 注入「当前时间」（日期 + 星期 + 时分 + 时区）。
         *
         * <p>默认开启。模型自身不知道当前时间，而框架仅在 system prompt 里注入**英文格式的日期**
         * （`Today's date is Saturday Sep 27, 2026`，见 `WorkspaceContextMiddleware`），
         * 不含时分秒；本开关开启后额外补一段中文时间，两者互补不冲突。</p>
         *
         * <p>注入由 {@code CurrentTimeMiddleware} 在每次模型调用前现算，因此**同一轮内时间一致、
         * 跨轮自动刷新**，且不受上下文压缩影响。</p>
         */
        private boolean currentTimeEnabled = true;

        /**
         * 当前时间注入所用时区（IANA 标识，如 {@code Asia/Shanghai}）；留空或非法时回退 JVM 默认时区。
         *
         * <p>显式配置的原因：JVM 默认时区取自操作系统，容器化部署若未挂载 tzdata 或未设 {@code TZ}
         * 会退化为 UTC，导致北京时间 08:00 前算错一天。</p>
         */
        private String currentTimeZone = "Asia/Shanghai";

        /**
         * 聊天上传：单张图片大小上限（字节），默认 512KB。
         *
         * <p>图片在调用模型时按 Base64 data URI 下发，体积会膨胀约 4/3，故上限不宜过大。</p>
         */
        private long imageMaxBytes = 512 * 1024L;

        /**
         * 聊天上传：单个文档大小上限（字节），默认 5MB。
         */
        private long documentMaxBytes = 5 * 1024 * 1024L;

        /**
         * 聊天上传：单个文档解析后内联进本轮对话上下文的**最大字符数**，默认 20000；
         * 超出部分保留首尾、中段截断（避免单个大文档直接撑爆模型上下文窗口）。
         */
        private int documentInlineMaxChars = 20000;

        /**
         * 聊天上传：单条用户消息允许携带的附件数量上限，默认 5。
         */
        private int maxAttachmentsPerMessage = 5;
    }

    /**
     * RAG 检索增强配置（检索重排 Rerank 的**算法参数** + 上下文装配）。
     *
     * <p><b>模型与开关已不在本段</b>（2026-10-14 调整）：重排模型登记在「模型配置」（{@code model_type=rerank}）,
     * 「是否启用重排 + 用哪个模型」由**知识库**配置（{@code ai_knowledge_base.rerank_enabled / rerank_model_id}）；
     * 本段只保留与模型无关的算法参数（候选数 / 融合模式 / 权重 / 条件触发 / 超时）。</p>
     *
     * <p>降级：模型未配置 / 密钥缺失 / HTTP 非 2xx / 超时 / 响应结构不符时，<b>一律保持原顺序</b>，绝不阻断检索。</p>
     */
    @Data
    public static class Rag {

        /**
         * 重排前的稠密召回条数（先按 max(topK, rerankTopN) 召回再重排）。
         *
         * <p>默认 30：由「⑧ Rerank 调优」实验矩阵选定——在 {@code score_fusion} 模式下 30 条候选的
         * hit@5/MRR 优于 20（且不损 hit@1），仅略增一次重排的输入长度。</p>
         */
        private int rerankTopN = 30;

        /**
         * 重排调用超时（毫秒），超时即降级为原顺序
         */
        private int rerankTimeoutMs = 12000;

        /**
         * 重排结果与向量召回结果的**融合模式**（默认 {@code score_fusion}）：
         * <ul>
         *   <li>{@code replace}：直接用重排分替换向量分与顺序（早期「纯替换」策略）；</li>
         *   <li>{@code rank_rrf}：对「向量排名」与「重排排名」做倒数排名融合（RRF）；</li>
         *   <li>{@code score_fusion}：两路分数归一化后按 {@link #rerankAlpha} 加权融合。</li>
         * </ul>
         * 未知取值一律回退 {@code replace}。默认值由「⑧ Rerank 调优」实验矩阵选定：
         * {@code score_fusion} 在保持 hit@3/@5/recall 不回退（相对未重排）的同时显著提升 hit@1。
         */
        private String rerankMode = "score_fusion";

        /**
         * {@code score_fusion} 模式下**重排分**的权重（0~1）：越接近 1 越信重排，越接近 0 越信向量分。
         */
        private double rerankAlpha = 0.5;

        /**
         * {@code rank_rrf} 融合的 RRF 平滑常数 k（越大越弱化头部排名差异，常用 60）；≤0 时取 60。
         */
        private int rerankRrfK = 60;

        /**
         * 条件触发阈值：仅当「首条与次条向量分差 &lt; 本阈值」时才真正调用重排（0 或负值=总是重排）。
         */
        private double rerankTriggerMargin = 0.0;

        /**
         * 是否启用**检索结果内容去重**（P0-③ 上下文装配）：内容重复 / 高度重叠的命中只保留首个。
         *
         * <p>确定性纯文本判据（不调模型）：规范化空白后互为子串，或字符 n-gram Jaccard ≥
         * {@link #assembleDedupJaccard}。默认开启，只作用于返回集后处理、不改排序。</p>
         */
        private boolean assembleDedupEnabled = true;

        /**
         * 是否启用**相邻切片合并**（P0-③ 上下文装配）：同一文档且切片号相邻（差 ≤
         * {@link #assembleMergeWindow}）的命中合并为一个片段。
         */
        private boolean assembleMergeAdjacentEnabled = true;

        /**
         * 相邻切片合并的窗口（切片号差值的绝对值上限）：1 表示相邻切片合并。
         */
        private int assembleMergeWindow = 1;

        /**
         * 内容去重的字符 n-gram Jaccard 阈值：达到即视为重复（0~1，越大越严格）。
         */
        private double assembleDedupJaccard = 0.9;

        /**
         * 装配后片段总正文的**字符上限（绝对上限）**（与工具现状一致，默认 6000）：超限时按相关度优先保留。
         *
         * <p>作为不依赖 Token 估算的硬兜底，与 {@link #assembleMaxTokens} 折算出的字符上限取**较小者**生效。</p>
         */
        private int assembleMaxChars = 6000;

        /**
         * 装配后片段总正文的 **Token 预算**（P0-④，默认 6000）：超限时按相关度优先保留。
         *
         * <p>默认值 6000 与「现状 6000 字」等效——按 {@code AiTokenEstimator} 的 CJK 1 token/字符口径，
         * 6000 字≈6000 token，故默认行为与原字符硬截断一致、不会导致覆盖下降；作为**可配预算**，
         * 调小即可更早收敛上下文。有效字符上限 = min({@link #assembleMaxChars}, 本预算折算出的字符上限)。</p>
         */
        private int assembleMaxTokens = 6000;
    }

    /**
     * 结构化决策（专家路由判断）配置。
     *
     * <p>口径（见 docs/21 §1.3 / §7）：<b>默认关闭</b>（{@code enabled=false}）——关闭时零外呼、
     * 不构造 {@code state}、不新增耗时，路由完全由小Z 自己的 {@code route_decision} 工具完成，与现状逐项等价。
     * {@code mode} 仅在启用后有语义（关闭时不校验、不报错）。
     * 出境前的 PII 脱敏为合规强制项，<b>不在此提供开关</b>。</p>
     *
     * <p><b>决策模型的连接信息不在此处</b>：服务地址 / 模型标识 / 密钥统一由「模型配置」
     * （{@code ai_model_config}，类型 {@code decision}）管理，此处只引用 {@code modelConfigId}。</p>
     */
    @Data
    public static class Jev {

        /**
         * 总开关：关闭＝零外呼、路由完全由小Z 负责（默认）
         */
        private boolean enabled = false;

        /**
         * 所选决策模型（「模型配置」主键，类型 {@code decision}）；
         * 为空时等同未启用（没有可用的决策模型，无法外呼）
         */
        private Long modelConfigId;

        /**
         * 判定超时（秒；与「模型配置」的通用超时无关——判定在路由关键路径上，需要更短的超时）。
         *
         * <p>默认 1 秒：健康调用实测中位 ≈200ms，1 秒已很宽裕；而故障时单轮代价由此前的
         * 「5s 超时 + 300ms 退避 + 二次尝试」压到约 1s（口径见 docs/22 §3.1 B）。</p>
         */
        private int timeoutSeconds = 1;

        /**
         * 执行模式：shadow 影子（只留痕不接管）/ gate 闸门（高置信接管）/ preselect 候选预筛（可选）
         */
        private String mode = "shadow";

        /**
         * 闸门模式的**基础**置信度阈值（0~1）：候选数 ≤5 档（docs/22 §3.1 A；需按自有样本校准）
         */
        private double confidenceThreshold = 0.75;

        /**
         * 闸门模式的基础置信度阈值：候选数 6~10 档
         */
        private double confidenceThresholdMedium = 0.65;

        /**
         * 闸门模式的基础置信度阈值：候选数 &gt;10 档
         */
        private double confidenceThresholdLarge = 0.55;

        /**
         * margin 二次过滤比值 r：基础阈值达标后还要求 {@code top1 / top2 ≥ r}（1.0 等价不启用）
         */
        private double marginRatio = 2.0;

        /**
         * 外呼 {@code state} 的字符上限（前置保护，远低于协议 65536 token 上限）
         */
        private int stateMaxChars = 4000;

        /**
         * 是否落库概率分布（影子期开启供校准；闸门稳定后可关以省存储）
         */
        private boolean persistProbabilities = true;

        /**
         * 是否启用熔断（docs/22 §3.1 B）：连续失败达阈值后暂停判定
         */
        private boolean circuitBreakerEnabled = true;

        /**
         * 熔断触发阈值：连续失败次数
         */
        private int circuitBreakerThreshold = 3;

        /**
         * 熔断暂停时长（秒）
         */
        private int circuitBreakerPauseSeconds = 300;

        /**
         * 粘性路由开关（docs/22 §3.3 F）
         */
        private boolean stickyRoutingEnabled = false;

        /**
         * 粘性路由触发的上轮最低置信度
         */
        private double stickyMinConfidence = 0.9;

        /**
         * 粘性路由触发的本轮最大字数
         */
        private int stickyMaxQueryLength = 12;
    }

}