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
         */
        private long routeBudgetExpertTokens = 60000L;

        /**
         * 单轮编排的**墙钟预算**（秒），0 表示不限（REQ-AI-30 Phase 2 第四项）。
         *
         * <p>与 Token 预算同时判定；HITL 暂停-恢复跨两次请求，恢复段按恢复段自身重新计时
         * （不跨请求累计——{@code ai_route_run} 无编排开始时刻列）。</p>
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
    }

}