package org.dromara.ai.core.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 长期记忆审计日志（docs/24 §5.7 / §5.9）。
 *
 * <p>开发状态不新增审计表，先按用户写入 {@code memory/audit.jsonl}（一行一个 JSON 事件），
 * 统计接口与事后排障都从这里读取。审计失败只告警，绝不影响记忆主流程。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemoryAuditLogger {

    /**
     * 审计文件（位于用户记忆目录下，不属于每日流水，因此不会被合并/归档扫描到）
     */
    public static final String AUDIT_FILE = "audit.jsonl";

    /**
     * 审计使用的固定会话标识（用户级隔离下与会话无关）
     */
    private static final String AUDIT_SESSION = "memory-audit";

    /**
     * 事件：抽取写入
     */
    public static final String EVENT_FLUSH_WRITE = "FLUSH_WRITE";

    /**
     * 事件：净化
     */
    public static final String EVENT_SANITIZE = "SANITIZE";

    /**
     * 事件：合并
     */
    public static final String EVENT_CONSOLIDATE = "CONSOLIDATE";

    /**
     * 事件：归档
     */
    public static final String EVENT_ARCHIVE = "ARCHIVE";

    /**
     * 事件：删除
     */
    public static final String EVENT_DELETE = "DELETE";

    /**
     * 事件：人工编辑
     */
    public static final String EVENT_MANUAL_EDIT = "MANUAL_EDIT";

    /**
     * 事件：用户明确要求记住（显式记忆）
     */
    public static final String EVENT_USER_REMEMBER = "USER_REMEMBER";

    /**
     * 事件：用户撤销显式记忆
     */
    public static final String EVENT_USER_FORGET = "USER_FORGET";

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    /**
     * 记录一条记忆审计事件。
     *
     * @param agentId 智能体主键（为空时取入口智能体）
     * @param userId  记忆归属用户主键
     * @param event   事件类型
     * @param result  结果（OK / FAIL）
     * @param detail  附加信息（可为空）
     */
    public void record(Long agentId, Long userId, String event, String result,
                       Map<String, Object> detail) {
        if (userId == null || event == null) {
            return;
        }
        try {
            Path memoryDir = resolveMemoryDir(agentId, userId);
            if (memoryDir == null) {
                return;
            }
            Files.createDirectories(memoryDir);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", Instant.now().toString());
            entry.put("event", event);
            entry.put("userId", userId);
            entry.put("result", result == null ? "OK" : result);
            if (detail != null && !detail.isEmpty()) {
                entry.put("detail", detail);
            }
            Files.writeString(memoryDir.resolve(AUDIT_FILE),
                JsonUtils.toJsonString(entry) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("长期记忆审计写入失败：userId={}, event={}, err={}", userId, event, e.getMessage());
        }
    }

    /**
     * 解析用户记忆目录：记忆统一落在入口智能体工作区的用户命名空间下。
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @return 记忆目录；无法解析时返回 null
     */
    private Path resolveMemoryDir(Long agentId, Long userId) {
        AiAgent definition = agentId == null ? agentService.getEntryAgent()
            : agentService.getEnabledAgent(agentId);
        if (definition == null) {
            return null;
        }
        // 专家与入口智能体共享同一份用户长期记忆，统一取入口工作区
        AiAgent memoryAgent = definition;
        if (!definition.isEntry()) {
            AiAgent entry = agentService.getEntryAgent();
            if (entry != null) {
                memoryAgent = entry;
            }
        }
        WorkspaceManager workspace = agentRegistry.get(memoryAgent).getWorkspaceManager();
        RuntimeContext ctx = RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(AUDIT_SESSION)
            .build();
        return workspace.getMemoryDir(ctx);
    }
}
