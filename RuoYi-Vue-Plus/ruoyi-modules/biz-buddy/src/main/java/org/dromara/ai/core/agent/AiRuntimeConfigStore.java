package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.memory.AiMemoryConfigGuard;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * 运行期策略配置的解析与保存（REQ-AI-21 上下文压缩 / REQ-AI-22 计划模式与任务清单）。
 *
 * <p>默认值来自配置 {@code bizbuddy.ai.chat.*}；页面保存后写入 Redis 覆盖默认值。与全局权限模式不同，
 * 这些配置在装配期被 {@link AgentRegistry} 读取并写入智能体定义指纹，因此保存后下一次对话即按新策略
 * 重新装配生效。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRuntimeConfigStore {

    /**
     * 上下文压缩策略（含大工具结果卸载）的 Redis 键
     */
    private static final String COMPACTION_KEY = "bizbuddy:ai:runtime:compaction";

    /**
     * 计划模式与任务清单配置的 Redis 键
     */
    private static final String PLAN_KEY = "bizbuddy:ai:runtime:plan";

    /**
     * 长期记忆配置的 Redis 键
     */
    private static final String MEMORY_KEY = "bizbuddy:ai:runtime:memory";

    /**
     * 工作区上下文配置的 Redis 键
     */
    private static final String WORKSPACE_KEY = "bizbuddy:ai:runtime:workspace";

    /**
     * MCP 出口（服务端）访问配置的 Redis 键
     */
    private static final String MCP_SERVER_KEY = "bizbuddy:ai:runtime:mcp-server";

    /**
     * 编排预算配置的 Redis 键（REQ-AI-30 Phase 2 第四项）
     */
    private static final String ROUTE_BUDGET_KEY = "bizbuddy:ai:runtime:route-budget";

    /**
     * 安全合规配置的 Redis 键（脱敏 + 内容安全）
     */
    private static final String SECURITY_KEY = "bizbuddy:ai:runtime:security";

    /**
     * 结构化决策（专家路由判断）配置的 Redis 键（docs/21 §7）
     */
    private static final String JEV_KEY = "bizbuddy:ai:runtime:jev";

    /**
     * 审计与运行数据保留策略的 Redis 键
     */
    private static final String AUDIT_RETENTION_KEY = "bizbuddy:ai:runtime:audit-retention";

    private final AiProperties aiProperties;

    /**
     * 解析当前生效的上下文压缩策略：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 压缩策略
     */
    public AiCompactionConfigBo compaction() {
        String cached = RedisUtils.getCacheObject(COMPACTION_KEY);
        AiCompactionConfigBo policy = JsonUtils.parseObject(cached, AiCompactionConfigBo.class);
        return policy == null ? defaultCompaction() : policy;
    }

    /**
     * 保存上下文压缩策略（写入 Redis 覆盖值）。
     *
     * @param bo 压缩策略
     */
    public void updateCompaction(AiCompactionConfigBo bo) {
        RedisUtils.setCacheObject(COMPACTION_KEY, JsonUtils.toJsonString(bo));
        log.info("上下文压缩策略已更新：enabled={}, triggerMessages={}, triggerTokens={}, keepMessages={}, "
                + "flush={}, offload={}, eviction={}/{}/{}",
            bo.isEnabled(), bo.getTriggerMessages(), bo.getTriggerTokens(), bo.getKeepMessages(),
            bo.isFlushBeforeCompact(), bo.isOffloadBeforeCompact(), bo.isToolResultEvictionEnabled(),
            bo.getToolResultEvictionMaxChars(), bo.getToolResultEvictionPreviewChars());
    }

    /**
     * 解析当前生效的计划模式配置：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 计划模式配置
     */
    public AiPlanConfigBo plan() {
        String cached = RedisUtils.getCacheObject(PLAN_KEY);
        AiPlanConfigBo stored = JsonUtils.parseObject(cached, AiPlanConfigBo.class);
        AiPlanConfigBo policy = stored == null ? defaultPlan() : stored;
        if (StringUtils.isBlank(policy.getPlanFileDirectory())) {
            policy.setPlanFileDirectory(aiProperties.getChat().getPlanFileDirectory());
        }
        return policy;
    }

    /**
     * 保存计划模式配置（写入 Redis 覆盖值）。
     *
     * @param bo 计划模式配置
     */
    public void updatePlan(AiPlanConfigBo bo) {
        RedisUtils.setCacheObject(PLAN_KEY, JsonUtils.toJsonString(bo));
        log.info("计划模式配置已更新：enabled={}, planFileDirectory={}, taskListEnabled={}",
            bo.isEnabled(), bo.getPlanFileDirectory(), bo.isTaskListEnabled());
    }

    /**
     * 解析当前生效的长期记忆配置：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 长期记忆配置
     */
    public AiMemoryConfigBo memory() {
        String cached = RedisUtils.getCacheObject(MEMORY_KEY);
        AiMemoryConfigBo stored = JsonUtils.parseObject(cached, AiMemoryConfigBo.class);
        AiMemoryConfigBo policy = stored == null ? defaultMemory() : stored;
        if (StringUtils.isBlank(policy.getFlushMode())) {
            policy.setFlushMode(aiProperties.getChat().getMemoryFlushMode());
        }
        // 旧版运行期配置没有管道模式与抽取上下文参数：补默认值，避免切换后读到 null
        if (StringUtils.isBlank(policy.getPipelineMode())) {
            policy.setPipelineMode(aiProperties.getChat().getMemoryPipelineMode());
        }
        // 旧版运行期配置没有归档保留期：补默认值，避免清理时把归档目录当流水一并删除
        if (policy.getArchiveRetentionDays() <= 0) {
            policy.setArchiveRetentionDays(aiProperties.getChat().getMemoryArchiveRetentionDays());
        }
        if (policy.getQuarantineRetentionDays() <= 0) {
            policy.setQuarantineRetentionDays(aiProperties.getChat().getMemoryQuarantineRetentionDays());
        }
        if (policy.getAuditRetentionDays() <= 0) {
            policy.setAuditRetentionDays(aiProperties.getChat().getMemoryAuditRetentionDays());
        }
        if (policy.getShadowRetentionDays() <= 0) {
            policy.setShadowRetentionDays(aiProperties.getChat().getMemoryShadowRetentionDays());
        }
        if (policy.getVersionRetention() <= 0) {
            policy.setVersionRetention(aiProperties.getChat().getMemoryVersionRetention());
        }
        return policy;
    }

    /**
     * 保存长期记忆配置（写入 Redis 覆盖值）。
     *
     * <p>写入前执行跨字段护栏校验，非法组合直接拒绝，避免把「归档即删除」「合并预算挤占注入预算」
     * 这类配置持久化到 Redis（docs/24 §5.8）。</p>
     *
     * @param bo 长期记忆配置
     */
    public void updateMemory(AiMemoryConfigBo bo) {
        // 兼容旧版前端 / 旧报文：未携带管道模式时保留当前生效值，避免保存一次配置把 platform 重置回 framework
        if (StringUtils.isBlank(bo.getPipelineMode())) {
            bo.setPipelineMode(memory().getPipelineMode());
        }
        AiMemoryConfigGuard.validate(bo);
        RedisUtils.setCacheObject(MEMORY_KEY, JsonUtils.toJsonString(bo));
        log.info("长期记忆配置已更新：pipeline={}, enabled={}, maxContextTokens={}, consolidation={} tokens/{} 分钟, "
                + "retention={}/{}/{} 天, flushMode={}({} 分钟), extractContext={}({})",
            bo.getPipelineMode(), bo.isEnabled(), bo.getMaxContextTokens(), bo.getConsolidationMaxTokens(),
            bo.getConsolidationMinGapMinutes(), bo.getDailyFileRetentionDays(),
            bo.getArchiveRetentionDays(), bo.getSessionRetentionDays(),
            bo.getFlushMode(), bo.getFlushThrottleMinutes(),
            bo.getExtractContextMessages(), bo.getExtractContextMaxChars());
    }

    /**
     * 解析当前生效的工作区上下文配置：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 工作区上下文配置
     */
    public AiWorkspaceConfigBo workspace() {
        String cached = RedisUtils.getCacheObject(WORKSPACE_KEY);
        AiWorkspaceConfigBo stored = JsonUtils.parseObject(cached, AiWorkspaceConfigBo.class);
        if (stored == null) {
            return defaultWorkspace();
        }
        if (stored.getAdditionalContextFiles() == null) {
            stored.setAdditionalContextFiles(new ArrayList<>());
        }
        return stored;
    }

    /**
     * 保存工作区上下文配置（写入 Redis 覆盖值）。
     *
     * @param bo 工作区上下文配置
     */
    public void updateWorkspace(AiWorkspaceConfigBo bo) {
        RedisUtils.setCacheObject(WORKSPACE_KEY, JsonUtils.toJsonString(bo));
        log.info("工作区上下文配置已更新：enabled={}, additionalContextFiles={}",
            bo.isEnabled(), bo.getAdditionalContextFiles());
    }

    /**
     * 解析当前生效的 MCP 出口访问配置：Redis 覆盖值优先，其次配置默认值（REQ-AI-15）。
     *
     * @return MCP 出口访问配置
     */
    public AiMcpServerConfigBo mcpServer() {
        String cached = RedisUtils.getCacheObject(MCP_SERVER_KEY);
        AiMcpServerConfigBo stored = JsonUtils.parseObject(cached, AiMcpServerConfigBo.class);
        return stored == null ? defaultMcpServer() : stored;
    }

    /**
     * 保存 MCP 出口访问配置（写入 Redis 覆盖值，立即生效）。
     *
     * @param bo MCP 出口访问配置
     */
    public void updateMcpServer(AiMcpServerConfigBo bo) {
        RedisUtils.setCacheObject(MCP_SERVER_KEY, JsonUtils.toJsonString(bo));
        log.info("MCP 出口配置已更新：authEnabled={}, token={}",
            bo.isAuthEnabled(), StringUtils.isBlank(bo.getToken()) ? "未配置" : "已配置");
    }

    /**
     * 解析当前生效的编排预算配置：Redis 覆盖值优先，其次配置默认值。
     *
     * <p>供编排器在每次专家调用后读取（专家轮 Token 预算与墙钟预算）；0 表示不限。</p>
     *
     * @return 编排预算配置
     */
    public AiRouteBudgetConfigBo routeBudget() {
        String cached = RedisUtils.getCacheObject(ROUTE_BUDGET_KEY);
        AiRouteBudgetConfigBo stored = JsonUtils.parseObject(cached, AiRouteBudgetConfigBo.class);
        return stored == null ? defaultRouteBudget() : stored;
    }

    /**
     * 保存编排预算配置（写入 Redis 覆盖值，下一次编排即生效）。
     *
     * @param bo 编排预算配置
     */
    public void updateRouteBudget(AiRouteBudgetConfigBo bo) {
        RedisUtils.setCacheObject(ROUTE_BUDGET_KEY, JsonUtils.toJsonString(bo));
        log.info("编排预算配置已更新：专家轮 Token={}（0=不限）, 墙钟={}s（0=不限）",
            bo.getExpertTokens(), bo.getWallSeconds());
    }

    /**
     * 由配置默认值构造编排预算配置。
     *
     * @return 默认编排预算配置
     */
    private AiRouteBudgetConfigBo defaultRouteBudget() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiRouteBudgetConfigBo bo = new AiRouteBudgetConfigBo();
        bo.setExpertTokens(chat.getRouteBudgetExpertTokens());
        bo.setWallSeconds(chat.getRouteBudgetSeconds());
        return bo;
    }

    /**
     * 解析当前生效的安全合规配置：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 安全合规配置
     */
    public AiSecurityConfigBo security() {
        String cached = RedisUtils.getCacheObject(SECURITY_KEY);
        AiSecurityConfigBo stored = JsonUtils.parseObject(cached, AiSecurityConfigBo.class);
        return stored == null ? defaultSecurity() : stored;
    }

    /**
     * 保存安全合规配置（写入 Redis 覆盖值，下一次对话 / 编排即生效）。
     *
     * @param bo 安全合规配置
     */
    public void updateSecurity(AiSecurityConfigBo bo) {
        // 密钥留空表示不修改：前端不回显密钥、始终以空值提交，须沿用已存值，
        // 否则会把已配置的外部审核引擎密钥覆盖成空
        if (StringUtils.isBlank(bo.getProviderApiKey())) {
            bo.setProviderApiKey(security().getProviderApiKey());
        }
        RedisUtils.setCacheObject(SECURITY_KEY, JsonUtils.toJsonString(bo));
        log.info("安全合规配置已更新：enabled={}, engine={}, action={}, input/output/channelOut={}/{}/{}, "
                + "maskPii={}, holdBack={}, externalProvider={}",
            bo.isEnabled(), bo.getEngine(), bo.getAction(), bo.isInputCheck(), bo.isOutputCheck(),
            bo.isChannelOutCheck(), bo.isMaskPii(), bo.getHoldBackChars(),
            StringUtils.isBlank(bo.getProviderEndpoint()) ? "未配置（降级本地词库）" : "已配置");
    }

    /**
     * 由配置默认值构造安全合规配置。
     *
     * @return 默认安全合规配置
     */
    private AiSecurityConfigBo defaultSecurity() {
        AiProperties.Security security = aiProperties.getSecurity();
        AiSecurityConfigBo bo = new AiSecurityConfigBo();
        bo.setEnabled(security.isEnabled());
        bo.setEngine(security.getEngine());
        bo.setAction(security.getAction());
        bo.setInputCheck(security.isInputCheck());
        bo.setOutputCheck(security.isOutputCheck());
        bo.setChannelOutCheck(security.isChannelOutCheck());
        bo.setToolResultMask(security.isToolResultMask());
        bo.setKbIngestCheck(security.isKbIngestCheck());
        bo.setMcpOutCheck(security.isMcpOutCheck());
        bo.setJevOutboundCheck(security.isJevOutboundCheck());
        bo.setPromptHardening(security.isPromptHardening());
        bo.setMaskPii(security.isMaskPii());
        bo.setHoldBackChars(security.getHoldBackChars());
        bo.setExcerptChars(security.getExcerptChars());
        bo.setProviderEndpoint(security.getExternal().getEndpoint());
        bo.setProviderApiKey(security.getExternal().getApiKey());
        return bo;
    }

    /**
     * 解析当前生效的结构化决策配置：Redis 覆盖值优先，其次配置默认值。
     *
     * <p>切换 {@code mode} 与阈值可即时生效，避免改配置要重启（docs/21 §7）。</p>
     *
     * @return 结构化决策配置
     */
    public AiJevConfigBo jev() {
        String cached = RedisUtils.getCacheObject(JEV_KEY);
        AiJevConfigBo stored = JsonUtils.parseObject(cached, AiJevConfigBo.class);
        AiJevConfigBo policy = stored == null ? defaultJev() : stored;
        if (StringUtils.isBlank(policy.getMode())) {
            policy.setMode(aiProperties.getJev().getMode());
        }
        return policy;
    }

    /**
     * 保存结构化决策配置（写入 Redis 覆盖值，下一次编排即生效）。
     *
     * @param bo 结构化决策配置
     */
    public void updateJev(AiJevConfigBo bo) {
        RedisUtils.setCacheObject(JEV_KEY, JsonUtils.toJsonString(bo));
        // 决策模型的连接信息（地址 / 模型标识 / 密钥）不在此配置内，故只记录策略字段
        log.info("结构化决策配置已更新：enabled={}, modelConfigId={}, mode={}, timeout={}s, threshold={}, persistProbabilities={}",
            bo.isEnabled(), bo.getModelConfigId(), bo.getMode(), bo.getTimeoutSeconds(),
            bo.getConfidenceThreshold(), bo.isPersistProbabilities());
    }

    /**
     * 由配置默认值构造结构化决策配置。
     *
     * @return 默认结构化决策配置
     */
    private AiJevConfigBo defaultJev() {
        AiProperties.Jev jev = aiProperties.getJev();
        AiJevConfigBo bo = new AiJevConfigBo();
        bo.setEnabled(jev.isEnabled());
        bo.setModelConfigId(jev.getModelConfigId());
        bo.setTimeoutSeconds(jev.getTimeoutSeconds());
        bo.setMode(jev.getMode());
        bo.setConfidenceThreshold(jev.getConfidenceThreshold());
        bo.setConfidenceThresholdMedium(jev.getConfidenceThresholdMedium());
        bo.setConfidenceThresholdLarge(jev.getConfidenceThresholdLarge());
        bo.setMarginRatio(jev.getMarginRatio());
        bo.setStateMaxChars(jev.getStateMaxChars());
        bo.setPersistProbabilities(jev.isPersistProbabilities());
        bo.setCircuitBreakerEnabled(jev.isCircuitBreakerEnabled());
        bo.setCircuitBreakerThreshold(jev.getCircuitBreakerThreshold());
        bo.setCircuitBreakerPauseSeconds(jev.getCircuitBreakerPauseSeconds());
        bo.setStickyRoutingEnabled(jev.isStickyRoutingEnabled());
        bo.setStickyMinConfidence(jev.getStickyMinConfidence());
        bo.setStickyMaxQueryLength(jev.getStickyMaxQueryLength());
        return bo;
    }

    /**
     * 解析当前生效的审计保留策略：Redis 覆盖值优先，其次配置默认值。
     *
     * @return 审计保留策略
     */
    public AiAuditRetentionBo auditRetention() {
        String cached = RedisUtils.getCacheObject(AUDIT_RETENTION_KEY);
        AiAuditRetentionBo stored = JsonUtils.parseObject(cached, AiAuditRetentionBo.class);
        return stored == null ? defaultAuditRetention() : stored;
    }

    /**
     * 保存审计保留策略（写入 Redis 覆盖值，下一次「清理过期数据」即按新天数执行）。
     *
     * @param bo 审计保留策略
     */
    public void updateAuditRetention(AiAuditRetentionBo bo) {
        RedisUtils.setCacheObject(AUDIT_RETENTION_KEY, JsonUtils.toJsonString(bo));
        log.info("审计保留策略已更新：retentionDays={}", bo.getRetentionDays());
    }

    /**
     * 由配置默认值构造审计保留策略。
     *
     * @return 默认审计保留策略
     */
    private AiAuditRetentionBo defaultAuditRetention() {
        AiAuditRetentionBo bo = new AiAuditRetentionBo();
        bo.setRetentionDays(aiProperties.getRetention().getAuditDays());
        return bo;
    }

    /**
     * 由配置默认值构造 MCP 出口访问配置。
     *
     * @return 默认 MCP 出口访问配置
     */
    private AiMcpServerConfigBo defaultMcpServer() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiMcpServerConfigBo bo = new AiMcpServerConfigBo();
        bo.setAuthEnabled(chat.isMcpServerAuthEnabled());
        bo.setToken(chat.getMcpServerToken());
        return bo;
    }

    /**
     * 由配置默认值构造工作区上下文配置。
     *
     * @return 默认工作区上下文配置
     */
    private AiWorkspaceConfigBo defaultWorkspace() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiWorkspaceConfigBo bo = new AiWorkspaceConfigBo();
        bo.setEnabled(chat.isWorkspaceEnabled());
        bo.setAdditionalContextFiles(chat.getWorkspaceAdditionalContextFiles() == null
            ? new ArrayList<>() : new ArrayList<>(chat.getWorkspaceAdditionalContextFiles()));
        return bo;
    }

    /**
     * 由配置默认值构造压缩策略。
     *
     * @return 默认压缩策略
     */
    private AiCompactionConfigBo defaultCompaction() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiCompactionConfigBo bo = new AiCompactionConfigBo();
        bo.setEnabled(chat.isCompactionEnabled());
        bo.setTriggerMessages(chat.getCompactionTriggerMessages());
        bo.setTriggerTokens(chat.getCompactionTriggerTokens());
        bo.setKeepMessages(chat.getCompactionKeepMessages());
        bo.setFlushBeforeCompact(chat.isCompactionFlushBeforeCompact());
        bo.setOffloadBeforeCompact(chat.isCompactionOffloadBeforeCompact());
        bo.setToolResultEvictionEnabled(chat.isToolResultEvictionEnabled());
        bo.setToolResultEvictionMaxChars(chat.getToolResultEvictionMaxChars());
        bo.setToolResultEvictionPreviewChars(chat.getToolResultEvictionPreviewChars());
        return bo;
    }

    /**
     * 由配置默认值构造计划模式配置。
     *
     * @return 默认计划模式配置
     */
    private AiPlanConfigBo defaultPlan() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiPlanConfigBo bo = new AiPlanConfigBo();
        bo.setEnabled(chat.isPlanModeEnabled());
        bo.setPlanFileDirectory(chat.getPlanFileDirectory());
        bo.setTaskListEnabled(chat.isTaskListEnabled());
        return bo;
    }

    /**
     * 由配置默认值构造长期记忆配置。
     *
     * @return 默认长期记忆配置
     */
    private AiMemoryConfigBo defaultMemory() {
        AiProperties.Chat chat = aiProperties.getChat();
        AiMemoryConfigBo bo = new AiMemoryConfigBo();
        bo.setEnabled(chat.isMemoryEnabled());
        bo.setPipelineMode(chat.getMemoryPipelineMode());
        bo.setExtractContextMessages(chat.getMemoryExtractContextMessages());
        bo.setExtractContextMaxChars(chat.getMemoryExtractContextMaxChars());
        bo.setExtractKnownMemoryMaxChars(chat.getMemoryExtractKnownMemoryMaxChars());
        bo.setInjectDeclaredMaxTokens(chat.getMemoryInjectDeclaredMaxTokens());
        bo.setAutoConsolidateMaxBatches(chat.getMemoryAutoConsolidateMaxBatches());
        bo.setExtractShadowSampleRate(chat.getMemoryExtractShadowSampleRate());
        bo.setMaxContextTokens(chat.getMemoryMaxContextTokens());
        bo.setConsolidationMaxTokens(chat.getMemoryConsolidationMaxTokens());
        bo.setConsolidationMinGapMinutes(chat.getMemoryConsolidationMinGapMinutes());
        bo.setDailyFileRetentionDays(chat.getMemoryDailyFileRetentionDays());
        bo.setArchiveRetentionDays(chat.getMemoryArchiveRetentionDays());
        bo.setQuarantineRetentionDays(chat.getMemoryQuarantineRetentionDays());
        bo.setAuditRetentionDays(chat.getMemoryAuditRetentionDays());
        bo.setShadowRetentionDays(chat.getMemoryShadowRetentionDays());
        bo.setVersionRetention(chat.getMemoryVersionRetention());
        bo.setSessionRetentionDays(chat.getMemorySessionRetentionDays());
        bo.setFlushMode(chat.getMemoryFlushMode());
        bo.setFlushThrottleMinutes(chat.getMemoryFlushThrottleMinutes());
        return bo;
    }

}