package org.dromara.ai.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.ai.util.AiPiiMasker;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 平台托管记忆抽取（A2 / T13）。
 *
 * <p>框架 {@code MemoryFlushManager} 已由 {@code disableMemoryHooks()} 关闭，本服务负责：</p>
 *
 * <ol>
 *   <li>读取 {@code ai_message} 水位之后的新消息；</li>
 *   <li>附带最近 K 条已完成消息作为“只读上下文”，用于理解指代；</li>
 *   <li>附带已有记忆摘要与显式记忆，减少重复抽取；</li>
 *   <li>调用记忆模型，过滤 {@code needs_context} / 低置信条目，净化后追加流水；</li>
 *   <li>推进会话抽取水位。</li>
 * </ol>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemoryExtractionService {

    /**
     * 抽取水位文件（用户记忆目录下）
     */
    private static final String EXTRACT_STATE_FILE = ".extract_state.json";

    /**
     * 影子抽取水位文件（与正式水位隔离）
     */
    private static final String SHADOW_STATE_FILE = ".extract_shadow_state.json";

    /**
     * 水位文件中的「清空记忆截止消息 ID」：小于等于该值的消息不再抽取
     */
    private static final String CLEARED_AT_ID = "clearedAtId";

    /**
     * 影子抽取记录目录
     */
    private static final String SHADOW_DIR = ".extract_shadow";

    /**
     * 抽取 gate 前缀（按用户节流）
     */
    private static final String GATE_PREFIX = "bizbuddy:ai:memory:extract:gate:";

    /**
     * 抽取执行锁前缀（按会话）：同一会话的轮末抽取串行执行，避免两轮并发读到同一水位后重复抽取
     */
    private static final String LOCK_PREFIX = "bizbuddy:ai:memory:extract:lock:";

    /**
     * 抽取执行锁 TTL：覆盖单次抽取的最长耗时（模型超时 5 分钟），异常退出后自动释放
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(6);

    /**
     * 等待同会话抽取锁的最长时间：覆盖一次常规抽取耗时，避免用户快速追问时消息被漏抽
     */
    private static final long LOCK_WAIT_MILLIS = 15_000L;

    /**
     * 等待锁的重试间隔
     */
    private static final long LOCK_RETRY_MILLIS = 500L;

    /**
     * 单条消息进入抽取输入的最大字符数，避免长文把预算吃满
     */
    private static final int MAX_MESSAGE_CHARS = 4000;

    /**
     * 模型调用超时
     */
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(5);

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiMemorySanitizer sanitizer;

    private final AiMemoryAuditLogger auditLogger;

    private final AiMemoryDeclaredStore declaredStore;

    private final IAiUsageStatService usageStatService;

    private final AiSessionMapper sessionMapper;

    private final AiMessageMapper messageMapper;

    /**
     * 按会话水位抽取一次；无新消息、非平台模式或未到节流间隔时返回 false。
     *
     * @param sessionId 会话主键
     * @return 是否实际执行了抽取
     */
    public boolean extractIfDue(Long sessionId) {
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        boolean platform = "platform".equals(policy.getPipelineMode());
        boolean shadow = "shadow".equals(policy.getPipelineMode());
        if (!policy.isEnabled() || (!platform && !shadow)
            || "never".equals(policy.getFlushMode())) {
            return false;
        }
        if (shadow && ThreadLocalRandom.current().nextDouble() >= policy.getExtractShadowSampleRate()) {
            // 影子抽样：未命中直接跳过，不占用节流窗口，下一轮仍有机会被抽中
            return false;
        }
        // 同会话串行：上一轮抽取未结束时短暂等待，避免用户快速追问的消息被漏抽
        String lockKey = LOCK_PREFIX + sessionId;
        if (!acquireLock(lockKey, LOCK_WAIT_MILLIS)) {
            log.warn("同会话记忆抽取仍在执行，本轮跳过：sessionId={}", sessionId);
            return false;
        }
        try {
            return extractLocked(sessionId, shadow, policy);
        } finally {
            RedisUtils.deleteObject(lockKey);
        }
    }

    /**
     * 获取同会话抽取锁：在给定等待时间内重试，仍被占用则放弃。
     *
     * @param lockKey    锁键
     * @param waitMillis 最长等待毫秒数（0 表示只尝试一次）
     * @return 是否获取成功
     */
    boolean acquireLock(String lockKey, long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            if (RedisUtils.setObjectIfAbsent(lockKey, "1", LOCK_TTL)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.min(LOCK_RETRY_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 持锁执行一次抽取（调用方保证同会话互斥）。
     *
     * @param sessionId 会话主键
     * @param shadow    是否影子模式（只留样、不写流水）
     * @param policy    记忆配置
     * @return 是否实际执行了抽取
     */
    private boolean extractLocked(Long sessionId, boolean shadow, AiMemoryConfigBo policy) {
        AiSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() == null
            || AiSession.SOURCE_TASK.equals(session.getSource())) {
            return false;
        }
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null) {
            return false;
        }
        RuntimeContext ctx = RuntimeContext.builder()
            .sessionId(String.valueOf(sessionId))
            .userId(String.valueOf(session.getUserId()))
            .build();
        WorkspaceManager workspace = agentRegistry.get(entry).getWorkspaceManager();
        Path memoryDir = workspace.getMemoryDir(ctx);
        long watermark = readWatermark(memoryDir, sessionId, shadow);
        long clearedAtId = readClearedAtId(memoryDir);
        List<AiMessage> pending = loadPending(sessionId, watermark, clearedAtId,
            policy.getExtractContextMaxChars());
        if (pending.isEmpty()) {
            return false;
        }
        // 先确认有新消息再抢 gate：避免空轮占用 30 分钟节流窗口
        if ("throttled".equals(policy.getFlushMode())
            && !RedisUtils.setObjectIfAbsent(gateKey(entry.getId(), session.getUserId(), shadow), "1",
                Duration.ofMinutes(Math.max(1, policy.getFlushThrottleMinutes())))) {
            return false;
        }
        try {
            doExtract(entry, session, ctx, memoryDir, policy, pending, shadow);
            return true;
        } catch (Exception e) {
            // 失败保留 gate，避免模型故障时每轮重试打爆下游；每日维护与手动合并可兜底
            log.warn("平台记忆抽取失败：sessionId={}, userId={}, err={}",
                sessionId, session.getUserId(), e.getMessage());
            auditLogger.record(entry.getId(), session.getUserId(),
                AiMemoryAuditLogger.EVENT_FLUSH_WRITE, "FAIL",
                Map.of("stage", "extract", "reason",
                    StringUtils.defaultIfBlank(e.getMessage(), "未知错误")));
            return false;
        }
    }

    /**
     * 构造输入、调用模型、过滤与落盘。
     */
    private void doExtract(AiAgent entry, AiSession session, RuntimeContext ctx, Path memoryDir,
                           AiMemoryConfigBo policy, List<AiMessage> pending, boolean shadow) {
        List<AiMessage> context = loadContext(session.getId(), pending.get(0).getId(),
            policy.getExtractContextMessages());
        String existing = readMemoryMd(memoryDir);
        String known = buildKnownMemory(existing, entry.getAgentCode(), session.getUserId(),
            policy.getExtractKnownMemoryMaxChars());
        String userContent = buildUserContent(context, pending, known);
        Model model = agentRegistry.memoryModelFor(entry);
        List<Msg> messages = List.of(
            Msg.builder().role(MsgRole.SYSTEM).textContent(AiMemoryPrompts.PLATFORM_EXTRACT_PROMPT).build(),
            Msg.builder().role(MsgRole.USER).textContent(userContent).build());
        List<ChatResponse> responses = model
            .stream(messages, null, GenerateOptions.builder().stream(true).build())
            .collectList()
            .block(EXTRACT_TIMEOUT);
        String output = responses == null ? "" : collectText(responses);
        ChatTokenUsage usage = responses == null ? null : usageOf(responses);

        FilterResult filtered = filterContract(output, entry.getAgentCode(), session.getUserId());
        long lastId = pending.get(pending.size() - 1).getId();
        if (shadow) {
            // 影子模式只留样本，不写正式流水，也不影响 framework 的抽取结果
            appendShadowRecord(memoryDir, session, pending, filtered, existing.length());
        } else if (StringUtils.isNotBlank(filtered.content())) {
            appendLedger(memoryDir, filtered.content());
        }
        writeWatermark(memoryDir, session.getId(), lastId, shadow);
        if (usage != null) {
            Long modelId = entry.getMemoryModelId() != null ? entry.getMemoryModelId() : entry.getModelId();
            usageStatService.accumulate(session.getUserId(), entry.getId(), modelId, usage, 0, 1);
        }
        auditLogger.record(entry.getId(), session.getUserId(),
            shadow ? AiMemoryAuditLogger.EVENT_SHADOW_EXTRACT : AiMemoryAuditLogger.EVENT_FLUSH_WRITE, "OK",
            Map.of("stage", shadow ? "extract_shadow" : "extract",
                "sessionId", String.valueOf(session.getId()),
                "messages", pending.size(), "kept", filtered.kept(),
                "needsContext", filtered.needsContext(), "lowConfidence", filtered.lowConfidence(),
                "chars", StringUtils.length(filtered.content())));
        log.info("平台记忆抽取完成：mode={}, sessionId={}, userId={}, messages={}, kept={}, needsContext={}, chars={}",
            shadow ? "shadow" : "platform", session.getId(), session.getUserId(), pending.size(),
            filtered.kept(), filtered.needsContext(), StringUtils.length(filtered.content()));
    }

    /**
     * 读取水位之后的新消息；清空记忆前的消息一律忽略。
     *
     * @param sessionId   会话主键
     * @param watermark   会话抽取水位
     * @param clearedAtId 清空记忆截止消息 ID（0 表示未清空）
     * @param maxChars    字符预算
     * @return 待抽取消息
     */
    private List<AiMessage> loadPending(Long sessionId, long watermark, long clearedAtId, int maxChars) {
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<AiMessage>()
            .eq(AiMessage::getSessionId, sessionId)
            .gt(AiMessage::getId, Math.max(watermark, clearedAtId))
            .in(AiMessage::getRole, AiMessage.ROLE_USER, AiMessage.ROLE_ASSISTANT)
            .eq(AiMessage::getStatus, AiMessage.STATUS_NORMAL)
            .orderByAsc(AiMessage::getId)
            // 首次启用平台模式时避免把整段历史一次性拉入内存；水位会逐轮推进
            .last("limit 200");
        List<AiMessage> all = messageMapper.selectList(wrapper);
        List<AiMessage> pending = new ArrayList<>();
        int chars = 0;
        for (AiMessage message : all) {
            if (StringUtils.isBlank(message.getContent())) {
                continue;
            }
            int length = Math.min(message.getContent().length(), MAX_MESSAGE_CHARS);
            if (!pending.isEmpty() && chars + length > maxChars) {
                break;
            }
            pending.add(message);
            chars += length;
        }
        return pending;
    }

    /**
     * 读取待抽取消息之前的最近 K 条已完成消息（只读上下文）。
     */
    private List<AiMessage> loadContext(Long sessionId, Long firstPendingId, int contextMessages) {
        if (contextMessages <= 0 || firstPendingId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<AiMessage>()
            .eq(AiMessage::getSessionId, sessionId)
            .lt(AiMessage::getId, firstPendingId)
            .in(AiMessage::getRole, AiMessage.ROLE_USER, AiMessage.ROLE_ASSISTANT)
            .eq(AiMessage::getStatus, AiMessage.STATUS_NORMAL)
            .orderByDesc(AiMessage::getId)
            .last("limit " + Math.min(contextMessages, 12));
        List<AiMessage> desc = messageMapper.selectList(wrapper);
        List<AiMessage> result = new ArrayList<>(desc);
        result.sort((left, right) -> left.getId().compareTo(right.getId()));
        return result;
    }

    /**
     * 构造抽取输入：只读上下文 + 待抽取消息 + 已有记忆摘要。
     */
    private String buildUserContent(List<AiMessage> context, List<AiMessage> pending, String known) {
        StringBuilder content = new StringBuilder();
        if (StringUtils.isNotBlank(known)) {
            content.append("【已有记忆摘要（只读，不要重复抽取）】\n").append(known).append("\n\n");
        }
        if (!context.isEmpty()) {
            content.append("【只读上下文（仅用于理解指代与纠正关系，禁止从中抽取新事实）】\n");
            for (AiMessage message : context) {
                content.append('[').append(roleLabel(message)).append("] ")
                    .append(truncate(message.getContent())).append('\n');
            }
            content.append('\n');
        }
        content.append("【待抽取消息（只从这里提取新事实）】\n");
        // pending 已在 loadPending 中按字符预算截断，这里不再二次截断，避免首条消息被误丢弃
        for (AiMessage message : pending) {
            content.append('[').append(roleLabel(message)).append("] ")
                .append(truncate(message.getContent())).append('\n');
        }
        content.append("\n请按系统规则输出条目；无法自包含的事实输出 needs_context，不要猜测。");
        return content.toString();
    }

    /**
     * 过滤 needs_context / 低置信条目，再走净化器。
     */
    FilterResult filterContract(String output, String workspaceCode, Long userId) {
        if (StringUtils.isBlank(output)) {
            return new FilterResult("", 0, 0, 0);
        }
        if ("NO_REPLY".equalsIgnoreCase(output.trim())) {
            return new FilterResult("", 0, 0, 0);
        }
        Set<String> declared = declaredStore.listActive(workspaceCode, String.valueOf(userId)).stream()
            .map(item -> normalizeFact(item.fact()))
            .collect(Collectors.toSet());
        List<String> kept = new ArrayList<>();
        int needsContext = 0;
        int lowConfidence = 0;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase();
            if (lower.contains("needs_context") || lower.contains("needs-context")) {
                needsContext++;
                continue;
            }
            if (lower.contains("[confidence=low]")) {
                lowConfidence++;
                continue;
            }
            if (declared.contains(normalizeFact(trimmed))) {
                continue;
            }
            kept.add(trimmed);
        }
        AiMemorySanitizer.SanitizeResult sanitized = sanitizer.sanitizeLedger(String.join("\n", kept));
        return new FilterResult(sanitized.changed() ? sanitized.content() : String.join("\n", kept),
            kept.size(), needsContext, lowConfidence);
    }

    /**
     * 追加当日流水；文件不存在时写日期标题。
     */
    private void appendLedger(Path memoryDir, String content) {
        String fileName = LocalDate.now() + ".md";
        Path file = memoryDir.resolve(fileName);
        try {
            Files.createDirectories(memoryDir);
            boolean fresh = !Files.isRegularFile(file) || Files.size(file) == 0;
            String block = fresh ? "# " + LocalDate.now() + "\n\n" + content + "\n\n" : content + "\n\n";
            Files.writeString(file, block, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("记忆流水写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 写影子抽取样本：只用于离线评测，不进入正式流水。
     *
     * @param memoryDir   用户记忆目录
     * @param session     会话
     * @param pending     本次抽取消息
     * @param filtered    过滤结果
     * @param memoryChars 当前 MEMORY.md 字符数
     */
    private void appendShadowRecord(Path memoryDir, AiSession session, List<AiMessage> pending,
                                    FilterResult filtered, int memoryChars) {
        Path dir = memoryDir.resolve(SHADOW_DIR);
        Path file = dir.resolve(LocalDate.now() + ".jsonl");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("createdAt", Instant.now().toString());
        record.put("sessionId", String.valueOf(session.getId()));
        record.put("userId", String.valueOf(session.getUserId()));
        record.put("source", session.getSource());
        record.put("fromMessageId", pending.get(0).getId());
        record.put("toMessageId", pending.get(pending.size() - 1).getId());
        record.put("messages", pending.size());
        record.put("kept", filtered.kept());
        record.put("needsContext", filtered.needsContext());
        record.put("lowConfidence", filtered.lowConfidence());
        record.put("memoryChars", memoryChars);
        record.put("extracted", filtered.content());
        try {
            Files.createDirectories(dir);
            Files.writeString(file, JsonUtils.toJsonString(record) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("影子抽取样本写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读取已有 MEMORY.md（用于减少重复抽取）。
     */
    private String readMemoryMd(Path memoryDir) {
        Path file = memoryDir.getParent() == null ? null : memoryDir.getParent().resolve("MEMORY.md");
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 已有记忆摘要：MEMORY.md 截断 + 显式记忆。
     */
    private String buildKnownMemory(String memory, String workspaceCode, Long userId, int maxChars) {
        StringBuilder summary = new StringBuilder();
        if (StringUtils.isNotBlank(memory)) {
            summary.append(memory.length() <= maxChars ? memory : memory.substring(0, maxChars));
        }
        List<AiMemoryDeclaredStore.DeclaredMemory> declared =
            declaredStore.listActive(workspaceCode, String.valueOf(userId));
        if (!declared.isEmpty()) {
            if (!summary.isEmpty()) {
                summary.append("\n\n");
            }
            summary.append("【用户明确要求记住】\n");
            for (AiMemoryDeclaredStore.DeclaredMemory entry : declared) {
                summary.append("- ").append(entry.fact()).append('\n');
            }
        }
        return summary.toString().trim();
    }

    /**
     * 读取会话抽取水位。
     */
    private long readWatermark(Path memoryDir, Long sessionId, boolean shadow) {
        Path state = memoryDir.resolve(shadow ? SHADOW_STATE_FILE : EXTRACT_STATE_FILE);
        if (!Files.isRegularFile(state)) {
            return 0L;
        }
        try {
            Map<String, Object> root = JsonUtils.parseMap(Files.readString(state, StandardCharsets.UTF_8));
            Object sessions = root == null ? null : root.get("sessions");
            if (!(sessions instanceof Map<?, ?> map)) {
                return 0L;
            }
            Object value = map.get(String.valueOf(sessionId));
            if (value instanceof Number number) {
                return number.longValue();
            }
            // 平台的 JSON 序列化会把 Long 写成字符串（避免前端精度丢失），故字符串也要认
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
            return 0L;
        } catch (Exception e) {
            log.warn("读取记忆抽取水位失败：sessionId={}, err={}", sessionId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 读取「清空记忆截止消息 ID」；未清空时返回 0。
     *
     * @param memoryDir 记忆目录
     * @return 截止消息 ID
     */
    private long readClearedAtId(Path memoryDir) {
        Path state = memoryDir.resolve(EXTRACT_STATE_FILE);
        if (!Files.isRegularFile(state)) {
            return 0L;
        }
        try {
            Map<String, Object> root = JsonUtils.parseMap(Files.readString(state, StandardCharsets.UTF_8));
            Object value = root == null ? null : root.get(CLEARED_AT_ID);
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 清空记忆后写入截止消息 ID，避免下一轮把历史会话重新抽回来。
     *
     * @param memoryDir 记忆目录（调用方已删除原内容）
     */
    public void markCleared(Path memoryDir) {
        long maxId = maxMessageId();
        writeClearedState(memoryDir.resolve(EXTRACT_STATE_FILE), maxId);
        writeClearedState(memoryDir.resolve(SHADOW_STATE_FILE), maxId);
    }

    /**
     * 取当前最大消息 ID，作为清空记忆的截止点。
     *
     * @return 最大消息 ID；无消息时为 0
     */
    private long maxMessageId() {
        QueryWrapper<AiMessage> wrapper = new QueryWrapper<>();
        wrapper.select("MAX(id) AS id");
        AiMessage row = messageMapper.selectOne(wrapper);
        return row == null || row.getId() == null ? 0L : row.getId();
    }

    /**
     * 写入只含清空截止点的水位文件。
     *
     * @param state       水位文件
     * @param clearedAtId 截止消息 ID
     */
    private void writeClearedState(Path state, long clearedAtId) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("sessions", new LinkedHashMap<>());
            root.put(CLEARED_AT_ID, clearedAtId);
            root.put("updatedAt", Instant.now().toString());
            Files.createDirectories(state.getParent());
            Files.writeString(state, JsonUtils.toJsonString(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入清空水位失败：" + e.getMessage(), e);
        }
    }

    /**
     * 清理已过期会话的抽取水位条目，避免 {@code sessions} 映射无限增长。
     *
     * @param memoryDir 记忆目录
     * @param userId    用户主键
     * @param cutoff    会话保留截止日期
     * @return 清理的条目数
     */
    public int pruneWatermarks(Path memoryDir, Long userId, LocalDate cutoff) {
        Set<String> alive = sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getUserId, userId)
                .ge(AiSession::getUpdateTime, cutoff.atStartOfDay())
                .select(AiSession::getId))
            .stream()
            .map(session -> String.valueOf(session.getId()))
            .collect(Collectors.toSet());
        return pruneState(memoryDir.resolve(EXTRACT_STATE_FILE), alive)
            + pruneState(memoryDir.resolve(SHADOW_STATE_FILE), alive);
    }

    /**
     * 删除水位文件中不再活跃的会话键。
     *
     * @param state 水位文件
     * @param alive 仍活跃的会话主键集合
     * @return 清理的条目数
     */
    private int pruneState(Path state, Set<String> alive) {
        if (!Files.isRegularFile(state)) {
            return 0;
        }
        try {
            Map<String, Object> root = JsonUtils.parseMap(Files.readString(state, StandardCharsets.UTF_8));
            if (root == null || !(root.get("sessions") instanceof Map<?, ?> sessions)) {
                return 0;
            }
            Map<String, Object> kept = new LinkedHashMap<>();
            int removed = 0;
            for (Map.Entry<?, ?> entry : sessions.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (alive.contains(key)) {
                    kept.put(key, entry.getValue());
                } else {
                    removed++;
                }
            }
            if (removed == 0) {
                return 0;
            }
            root.put("sessions", kept);
            root.put("updatedAt", Instant.now().toString());
            Files.writeString(state, JsonUtils.toJsonString(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return removed;
        } catch (Exception e) {
            log.warn("清理记忆抽取水位失败：file={}, err={}", state, e.getMessage());
            return 0;
        }
    }

    /**
     * 推进会话抽取水位。
     */
    private void writeWatermark(Path memoryDir, Long sessionId, long messageId, boolean shadow) {
        Path state = memoryDir.resolve(shadow ? SHADOW_STATE_FILE : EXTRACT_STATE_FILE);
        try {
            Map<String, Object> root = Files.isRegularFile(state)
                ? JsonUtils.parseMap(Files.readString(state, StandardCharsets.UTF_8))
                : new LinkedHashMap<>();
            if (root == null) {
                root = new LinkedHashMap<>();
            }
            Object existing = root.get("sessions");
            Map<String, Object> sessions = existing instanceof Map<?, ?>
                ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
            sessions.put(String.valueOf(sessionId), messageId);
            root.put("sessions", sessions);
            root.put("updatedAt", Instant.now().toString());
            Files.createDirectories(memoryDir);
            Files.writeString(state, JsonUtils.toJsonString(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("记忆抽取水位写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 收集模型输出文本与用量。
     */
    private String collectText(List<ChatResponse> responses) {
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .forEach(block -> text.append(block.getText()));
        }
        return text.toString().trim();
    }

    /**
     * 汇总模型用量。
     */
    private ChatTokenUsage usageOf(List<ChatResponse> responses) {
        long prompt = 0;
        long completion = 0;
        for (ChatResponse response : responses) {
            ChatUsage usage = response.getUsage();
            if (usage != null) {
                prompt += usage.getInputTokens();
                completion += usage.getOutputTokens();
            }
        }
        return prompt + completion == 0 ? null
            : new ChatTokenUsage((int) prompt, (int) completion, (int) (prompt + completion));
    }

    private String gateKey(Long agentId, Long userId, boolean shadow) {
        return GATE_PREFIX + (shadow ? "shadow:" : "platform:") + agentId + ":" + userId;
    }

    private String roleLabel(AiMessage message) {
        return AiMessage.ROLE_USER.equals(message.getRole()) ? "用户" : "助手";
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        String masked = AiPiiMasker.maskText(content);
        return masked.length() <= MAX_MESSAGE_CHARS ? masked : masked.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    private String normalizeFact(String fact) {
        return fact == null ? "" : fact.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * 抽取过滤结果。
     *
     * @param content      保留正文
     * @param kept         保留条目数
     * @param needsContext 缺少上下文条目数
     * @param lowConfidence 低置信条目数
     */
    record FilterResult(String content, int kept, int needsContext, int lowConfidence) {
    }
}
