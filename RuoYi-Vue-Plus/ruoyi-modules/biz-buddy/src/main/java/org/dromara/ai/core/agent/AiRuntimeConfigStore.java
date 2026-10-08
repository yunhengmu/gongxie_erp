package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
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
        return policy;
    }

    /**
     * 保存长期记忆配置（写入 Redis 覆盖值）。
     *
     * @param bo 长期记忆配置
     */
    public void updateMemory(AiMemoryConfigBo bo) {
        RedisUtils.setCacheObject(MEMORY_KEY, JsonUtils.toJsonString(bo));
        log.info("长期记忆配置已更新：enabled={}, maxContextTokens={}, consolidation={} tokens/{} 分钟, "
                + "retention={}/{} 天, flushMode={}({} 分钟)",
            bo.isEnabled(), bo.getMaxContextTokens(), bo.getConsolidationMaxTokens(),
            bo.getConsolidationMinGapMinutes(), bo.getDailyFileRetentionDays(), bo.getSessionRetentionDays(),
            bo.getFlushMode(), bo.getFlushThrottleMinutes());
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
        bo.setMaxContextTokens(chat.getMemoryMaxContextTokens());
        bo.setConsolidationMaxTokens(chat.getMemoryConsolidationMaxTokens());
        bo.setConsolidationMinGapMinutes(chat.getMemoryConsolidationMinGapMinutes());
        bo.setDailyFileRetentionDays(chat.getMemoryDailyFileRetentionDays());
        bo.setSessionRetentionDays(chat.getMemorySessionRetentionDays());
        bo.setFlushMode(chat.getMemoryFlushMode());
        bo.setFlushThrottleMinutes(chat.getMemoryFlushThrottleMinutes());
        return bo;
    }

}