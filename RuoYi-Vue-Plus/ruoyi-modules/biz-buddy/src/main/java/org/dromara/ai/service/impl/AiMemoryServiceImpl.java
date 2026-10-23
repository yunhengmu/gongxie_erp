package org.dromara.ai.service.impl;

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
import org.dromara.ai.core.memory.AiMemoryAuditLogger;
import org.dromara.ai.core.memory.AiMemoryDeclaredStore;
import org.dromara.ai.core.memory.AiMemoryPrompts;
import org.dromara.ai.core.memory.AiMemorySanitizer;
import org.dromara.ai.core.memory.AiMemoryVersionStore;
import org.dromara.ai.core.memory.spi.MemoryBackendStats;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.bo.AiMemoryDeclaredBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.domain.vo.AiMemoryQualityVo;
import org.dromara.ai.domain.vo.AiMemoryVersionVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AI 长期记忆 Service 业务层处理（REQ-AI-09）。
 *
 * <p>长期记忆由 AgentScope 维护在工作区内（{@code MEMORY.md} 与 {@code memory/YYYY-MM-DD.md}），
 * 且按用户隔离（{@code IsolationScope.USER}）。本实现对这两个位置做受管读写，
 * 并复用装配层的模型槽位解析触发合并，保证与自动合并口径一致。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiMemoryServiceImpl implements IAiMemoryService {

    /**
     * 长期记忆文件名
     */
    private static final String MEMORY_MD = "MEMORY.md";

    /**
     * 归档子目录（记忆目录下，用于存放超期的每日流水）
     */
    private static final String ARCHIVE_DIR = "archive";

    /**
     * 净化前原文备份目录（位于记忆目录下）
     */
    private static final String QUARANTINE_DIR = ".quarantine";

    /**
     * 备份文件名时间戳格式
     */
    private static final DateTimeFormatter BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 框架合并水位文件（位于记忆目录下，记录最后一次成功合并时间）
     */
    private static final String CONSOLIDATION_STATE = ".consolidation_state";

    /**
     * 合并水位的工作区相对路径：与框架 {@code MemoryConsolidator} 使用同一文件，保证口径一致
     */
    private static final String CONSOLIDATION_STATE_PATH = "memory/" + CONSOLIDATION_STATE;

    /**
     * Token 与字符的粗略折算比例（1 Token ≈ 4 字符），统计口径与合并预算一致
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 运行健康统计的最大窗口（天），避免一次扫描过多审计历史
     */
    private static final int MAX_QUALITY_DAYS = 30;

    /**
     * 记忆控制台使用的固定会话标识（用户级隔离下与会话无关）
     */
    private static final String CONSOLE_SESSION = "memory-console";

    /**
     * 允许读写的工作区相对路径：仅长期记忆与每日流水，避免越权访问其它工作区文件
     */
    private static final Pattern ALLOWED_PATH =
        Pattern.compile("^(MEMORY\\.md|memory/[A-Za-z0-9._-]{1,64}\\.md)$");

    /**
     * 手动合并的最长等待时间
     */
    private static final Duration CONSOLIDATE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 单轮合并最多执行的批次数：积压较多时分多轮处理，避免一次占用模型过久
     */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /**
     * 每日流水文件名：仅根目录的 YYYY-MM-DD.md 参与合并，排除 history / archive / 保留文件
     */
    private static final Pattern LEDGER_FILE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.md");

    /**
     * 工作区相对路径形式的每日流水：仅 memory 根目录，排除 archive/ 与 history/
     */
    private static final Pattern LEDGER_PATH_PATTERN = Pattern.compile("^memory/\\d{4}-\\d{2}-\\d{2}\\.md$");

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiUsageStatService usageStatService;

    private final AiMemorySanitizer sanitizer;

    private final AiMemoryAuditLogger auditLogger;

    private final AiMemoryDeclaredStore memoryDeclaredStore;

    private final AiMemoryVersionStore memoryVersionStore;

    @Override
    public List<AiMemoryFileVo> listFiles(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        List<AiMemoryFileVo> files = new ArrayList<>();
        files.add(buildFile(MEMORY_MD, "长期记忆（合并产物）", "MEMORY", null, workspace.readMemoryMd(ctx)));
        for (String path : safeMemoryPaths(workspace, ctx)) {
            if (MEMORY_MD.equals(path)) {
                continue;
            }
            files.add(buildFile(path, fileNameOf(path), "DAILY", dateOf(path),
                readQuietly(workspace, ctx, path)));
        }
        return files;
    }

    @Override
    public AiMemoryContentVo readContent(Long agentId, Long userId, String path) {
        String safePath = validatePath(path);
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        String content = readQuietly(workspace, ctx, safePath);
        AiMemoryContentVo vo = new AiMemoryContentVo();
        vo.setPath(safePath);
        vo.setContent(content == null ? "" : content);
        return vo;
    }

    @Override
    public void writeContent(AiMemoryContentBo bo) {
        String safePath = validatePath(bo.getPath());
        AiAgent definition = agentService.getEnabledAgent(bo.getAgentId());
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(bo.getUserId());
        String content = bo.getContent() == null ? "" : bo.getContent();
        // 人工编辑同样走净化：避免运维把 PII 或指令性内容直接写进会被注入的系统提示词
        AiMemorySanitizer.SanitizeResult sanitized = sanitizer.sanitizeLedger(content);
        String saved = sanitized.changed() ? sanitized.content() : content;
        boolean memoryFile = MEMORY_MD.equals(safePath);
        if (memoryFile) {
            // 人工编辑 MEMORY.md：覆盖前必须成功留下旧版本，失败则中止本次写入
            snapshotMemory(workspace.getMemoryDir(ctx), workspace.readMemoryMd(ctx),
                "PRE_MANUAL", bo.getUserId(), "人工编辑前自动快照", null);
        }
        workspace.writeUtf8WorkspaceRelative(ctx, safePath, saved);
        if (memoryFile) {
            snapshotMemory(workspace.getMemoryDir(ctx), saved, "MANUAL",
                bo.getUserId(), "人工编辑", null);
        }
        if (sanitized.changed()) {
            log.info("长期记忆人工编辑已净化：agent={}, userId={}, path={}, 丢弃 {} 条, 去重 {} 条, "
                    + "脱敏 {} 处, 截断 {} 条",
                definition.getAgentCode(), bo.getUserId(), safePath, sanitized.dropped(),
                sanitized.deduped(), sanitized.redacted(), sanitized.truncated());
        }
        auditLogger.record(definition.getId(), bo.getUserId(), AiMemoryAuditLogger.EVENT_MANUAL_EDIT, "OK",
            Map.of("path", safePath, "charsBefore", content.length(), "charsAfter", saved.length(),
                "dropped", sanitized.dropped(), "deduped", sanitized.deduped(),
                "redacted", sanitized.redacted(), "truncated", sanitized.truncated()));
        log.info("长期记忆已更新：agent={}, userId={}, path={}, chars={}",
            definition.getAgentCode(), bo.getUserId(), safePath, saved.length());
    }

    @Override
    public void consolidate(Long agentId, Long userId) {
        consolidate(agentId, userId, MAX_BATCHES_PER_RUN);
    }

    @Override
    public void consolidate(Long agentId, Long userId, int maxBatches) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        int maxTokens = policy.getConsolidationMaxTokens();
        // 单批输入预算与配置的合并 Token 上限对齐（1 Token ≈ 4 字符）
        int maxChars = Math.max(4000, maxTokens * CHARS_PER_TOKEN);

        Path memoryDir = workspace.getMemoryDir(ctx);
        Instant previousWatermark = readConsolidationWatermark(memoryDir);
        List<DailyLedger> pending = loadPendingLedgers(
            workspace, ctx, definition, userId, previousWatermark);
        if (pending.isEmpty()) {
            log.info("长期记忆合并跳过（无待合并流水）：agent={}, userId={}, watermark={}",
                definition.getAgentCode(), userId, formatInstant(previousWatermark));
            return;
        }
        // 在净化回写之后取本轮开始时间：否则回写刷新的 mtime 会晚于水位，
        // 已合并文件会在下一轮被误判为未合并、反复重复合并
        Instant runStart = Instant.now();

        Model model = agentRegistry.memoryModelFor(definition);
        String memoryMd = workspace.readMemoryMd(ctx);
        String memoryBefore = StringUtils.defaultString(memoryMd);
        // 合并是整文件覆盖：先记录合并前版本，快照失败则中止本次合并
        snapshotMemory(memoryDir, memoryMd, "PRE_CONSOLIDATE", userId,
            "合并前自动快照", null);
        long started = System.currentTimeMillis();
        int batches = 0;
        int mergedFiles = 0;
        int mergedChars = 0;
        int totalTokens = 0;
        int index = 0;
        try {
            int batchLimit = Math.max(1, maxBatches);
            while (index < pending.size() && batches < batchLimit) {
                int end = nextBatchEnd(pending, index, maxChars);
                List<DailyLedger> batch = pending.subList(index, end);
                String ledger = renderBatch(batch);
                ConsolidationResult result = callConsolidationModel(model, maxTokens, memoryMd, ledger);
                if (StringUtils.isBlank(result.content())) {
                    throw new ServiceException("记忆合并失败：模型未返回有效内容，请检查记忆模型是否可用");
                }
                // 模型输出同样过一遍净化：防止模型把流水中的 PII / 指令性内容原样带进长期记忆
                AiMemorySanitizer.SanitizeResult merged = sanitizer.sanitizeLedger(result.content());
                String mergedContent = merged.changed() ? merged.content() : result.content();
                if (StringUtils.isBlank(mergedContent)) {
                    // 净化后为空说明输出只剩敏感/指令内容：保留原 MEMORY.md，不推进水位，等待重试
                    throw new ServiceException("记忆合并结果净化后为空，已保留原 MEMORY.md");
                }
                workspace.writeUtf8WorkspaceRelative(ctx, MEMORY_MD, mergedContent);
                memoryMd = mergedContent;
                if (merged.changed()) {
                    auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_SANITIZE, "OK",
                        Map.of("stage", "consolidationOutput", "batch", batches + 1,
                            "dropped", merged.dropped(), "deduped", merged.deduped(),
                            "redacted", merged.redacted(), "truncated", merged.truncated()));
                }
                int tokens = result.usage() == null ? 0 : result.usage().totalTokens();
                recordConsolidationUsage(definition, userId, result.usage());
                index = end;
                batches++;
                mergedFiles += batch.size();
                mergedChars += ledger.length();
                totalTokens += tokens;
                // 每批成功即推进水位到「下一批最早文件之前」：已完成批次不再重读，
                // 未完成文件仍晚于水位，下一轮/下一批继续合并，不会因截断而永久漏掉
                Instant checkpoint = nextWatermark(pending, index, runStart, previousWatermark);
                workspace.writeUtf8WorkspaceRelative(ctx, CONSOLIDATION_STATE_PATH, checkpoint.toString());
                log.info("长期记忆合并批次完成：agent={}, userId={}, batch={}, files={}, chars={}, tokens={}",
                    definition.getAgentCode(), userId, batches, batch.size(), ledger.length(), tokens);
            }

            int remaining = pending.size() - index;
            if (remaining > 0) {
                log.warn("长期记忆合并达到单轮批次上限，剩余 {} 个流水待下一轮：agent={}, userId={}",
                    remaining, definition.getAgentCode(), userId);
            }
            snapshotMemory(memoryDir, memoryMd, "CONSOLIDATE", userId,
                "合并后自动快照", null);
            long elapsed = System.currentTimeMillis() - started;
            auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_CONSOLIDATE, "OK",
                Map.of("batches", batches, "files", mergedFiles, "remaining", remaining,
                    "ledgerChars", mergedChars, "memoryCharsBefore", memoryBefore.length(),
                    "memoryCharsAfter", StringUtils.length(memoryMd), "elapsedMs", elapsed,
                    "tokens", totalTokens));
            log.info("长期记忆合并完成：agent={}, userId={}, 批次 {}, 文件 {}, 流水 {} 字符, "
                    + "MEMORY.md {} 字符 → {} 字符, tokens={}, 耗时={}ms",
                definition.getAgentCode(), userId, batches, mergedFiles, mergedChars,
                memoryBefore.length(), StringUtils.length(memoryMd), totalTokens, elapsed);
        } catch (RuntimeException e) {
            // 部分批次可能已覆盖 MEMORY.md：尽力记录当前状态，便于恢复
            try {
                snapshotMemory(memoryDir, workspace.readMemoryMd(ctx), "CONSOLIDATE_PARTIAL", userId,
                    "合并中断快照", null);
            } catch (Exception snapshotError) {
                log.warn("合并中断版本快照失败：userId={}, err={}", userId, snapshotError.getMessage());
            }
            auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_CONSOLIDATE, "FAIL",
                Map.of("reason", StringUtils.defaultIfBlank(e.getMessage(), "未知错误"),
                    "batches", batches, "files", mergedFiles));
            throw e;
        }
    }

    @Override
    public int archiveDaily(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        int days = Math.max(1, runtimeConfigStore.memory().getDailyFileRetentionDays());
        LocalDate cutoff = LocalDate.now().minusDays(days);
        Path memoryDir = workspace.getMemoryDir(ctx);
        if (!Files.isDirectory(memoryDir)) {
            return 0;
        }
        Path archiveDir = memoryDir.resolve(ARCHIVE_DIR);
        int moved = 0;
        try (Stream<Path> files = Files.list(memoryDir)) {
            List<Path> expired = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(path -> isOnOrBefore(path, cutoff))
                .toList();
            if (!expired.isEmpty()) {
                Files.createDirectories(archiveDir);
            }
            for (Path file : expired) {
                Files.move(file, archiveDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
        } catch (IOException e) {
            throw new ServiceException("记忆归档失败：" + e.getMessage());
        }
        log.info("长期记忆归档完成：agent={}, userId={}, 归档 {} 个流水文件（保留 {} 天）",
            definition.getAgentCode(), userId, moved, days);
        if (moved > 0) {
            // 无文件搬动时不记审计，避免每个用户每天都产生一条空事件
            auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_ARCHIVE, "OK",
                Map.of("moved", moved, "retentionDays", days));
        }
        return moved;
    }

    @Override
    public int cleanup(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        LocalDate dailyCutoff = LocalDate.now().minusDays(Math.max(1, policy.getDailyFileRetentionDays()));
        int removed = 0;
        Path memoryDir = workspace.getMemoryDir(ctx);
        removed += deleteExpiredMemoryFiles(memoryDir, dailyCutoff);
        // 归档目录使用独立的保留期，避免与每日流水保留期相同导致「归档即删除」
        LocalDate archiveCutoff = LocalDate.now()
            .minusDays(Math.max(1, policy.getArchiveRetentionDays()));
        removed += deleteExpiredMemoryFiles(memoryDir.resolve(ARCHIVE_DIR), archiveCutoff);
        // 会话态记忆（工作区 session 日志）：按会话保留天数清理
        LocalDate sessionCutoff = LocalDate.now().minusDays(Math.max(1, policy.getSessionRetentionDays()));
        removed += deleteExpiredSessionLogs(workspace, ctx, sessionCutoff);
        // 净化原文备份与影子样本：按各自保留期清理，避免敏感原文与评测样本无限增长
        removed += deleteExpiredByMtime(memoryDir.resolve(QUARANTINE_DIR),
            LocalDate.now().minusDays(Math.max(1, policy.getQuarantineRetentionDays())));
        removed += deleteExpiredByMtime(memoryDir.resolve(".extract_shadow"),
            LocalDate.now().minusDays(Math.max(1, policy.getShadowRetentionDays())));
        // 审计日志按行时间裁剪，保留近期事件
        int auditPruned = pruneAudit(memoryDir.resolve(AiMemoryAuditLogger.AUDIT_FILE),
            LocalDate.now().minusDays(Math.max(1, policy.getAuditRetentionDays())));
        log.info("长期记忆清理完成：agent={}, userId={}, 删除 {} 个文件（流水 {} 天 / 归档 {} 天 / 会话 {} 天），"
                + "审计裁剪 {} 行（保留 {} 天）",
            definition.getAgentCode(), userId, removed, policy.getDailyFileRetentionDays(),
            policy.getArchiveRetentionDays(), policy.getSessionRetentionDays(),
            auditPruned, policy.getAuditRetentionDays());
        return removed;
    }

    @Override
    public void deleteAll(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        deleteRecursively(memoryDir);
        try {
            Files.deleteIfExists(workspace.resolveRuntimeDataPath(ctx, MEMORY_MD));
        } catch (IOException e) {
            throw new ServiceException("删除长期记忆失败：" + e.getMessage());
        }
        log.info("长期记忆已删除：agent={}, userId={}", definition.getAgentCode(), userId);
        auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_DELETE, "OK", null);
    }

    @Override
    public boolean hasPendingLedger(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        if (!Files.isDirectory(memoryDir)) {
            return false;
        }
        Instant watermark = readConsolidationWatermark(memoryDir);
        try (Stream<Path> files = Files.list(memoryDir)) {
            return files.filter(Files::isRegularFile)
                .filter(path -> LEDGER_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                .anyMatch(path -> lastModified(path).isAfter(watermark));
        } catch (IOException e) {
            log.warn("读取待合并流水失败：userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasUnconsolidatedExpiringLedger(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        if (!Files.isDirectory(memoryDir)) {
            return false;
        }
        LocalDate cutoff = LocalDate.now()
            .minusDays(Math.max(1, runtimeConfigStore.memory().getDailyFileRetentionDays()));
        Instant watermark = readConsolidationWatermark(memoryDir);
        try (Stream<Path> files = Files.list(memoryDir)) {
            // 只看「按文件名已过期」的流水：再过一轮就会被归档；其中任一文件晚于水位，说明尚未合并
            return files.filter(Files::isRegularFile)
                .filter(path -> isOnOrBefore(path, cutoff))
                .anyMatch(path -> modifiedAfter(path, watermark));
        } catch (IOException e) {
            log.warn("读取即将归档流水失败：userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<Long> listMemoryUserIds(Long agentId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        Path root = workspace.getWorkspace();
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString().matches("\\d{1,19}"))
                .filter(this::hasMemoryData)
                .map(dir -> Long.valueOf(dir.getFileName().toString()))
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("扫描记忆用户目录失败：agent={}, err={}", definition.getAgentCode(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public MemoryBackendStats stats(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        String memory = workspace.readMemoryMd(ctx);
        int memoryChars = StringUtils.length(memory);
        AuditSummary audit = summarizeAudit(memoryDir);
        return new MemoryBackendStats(userId == null ? 0L : userId, memoryChars,
            memoryChars / CHARS_PER_TOKEN, countLedgerFiles(memoryDir),
            countLedgerFiles(memoryDir.resolve(ARCHIVE_DIR)),
            formatInstant(readConsolidationWatermark(memoryDir)), audit.lastArchivedAt(),
            audit.failedCount(), audit.lastBatches(), audit.lastMergedFiles(),
            audit.lastRemainingFiles());
    }

    @Override
    public AiMemoryQualityVo quality(Long userId, Integer days) {
        AiMemoryQualityVo quality = new AiMemoryQualityVo();
        int window = days == null ? 1 : Math.max(1, Math.min(days, MAX_QUALITY_DAYS));
        quality.setDays(window);
        AiAgent entry = agentService.getEntryAgent();
        if (entry == null || userId == null) {
            return quality;
        }
        Path memoryDir = workspaceOf(entry).getMemoryDir(contextOf(userId));
        Path auditFile = memoryDir == null ? null : memoryDir.resolve(AiMemoryAuditLogger.AUDIT_FILE);
        if (auditFile == null || !Files.isRegularFile(auditFile)) {
            return quality;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(window));
        try (Stream<String> lines = Files.lines(auditFile, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                Map<String, Object> entryLine = JsonUtils.parseMap(line);
                if (entryLine == null) {
                    continue;
                }
                Instant ts = parseAuditTime(entryLine.get("ts"));
                if (ts == null || ts.isBefore(cutoff)) {
                    continue;
                }
                accumulateQuality(quality, entryLine, ts);
            }
        } catch (Exception e) {
            log.warn("读取记忆审计日志失败：dir={}, err={}", memoryDir, e.getMessage());
        }
        return quality;
    }

    /**
     * 按事件类型累加运行健康指标（只认抽取写流水与合并两类事件）。
     *
     * @param quality   指标对象
     * @param entryLine 审计事件
     * @param ts        事件时间
     */
    private void accumulateQuality(AiMemoryQualityVo quality, Map<String, Object> entryLine, Instant ts) {
        String event = String.valueOf(entryLine.getOrDefault("event", ""));
        String result = String.valueOf(entryLine.getOrDefault("result", ""));
        Map<String, Object> detail = entryLine.get("detail") instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        if (AiMemoryAuditLogger.EVENT_FLUSH_WRITE.equals(event)) {
            if ("FAIL".equals(result)) {
                quality.setExtractFailures(quality.getExtractFailures() + 1);
                return;
            }
            // 影子样本不计入生产健康度
            if (!"OK".equals(result) || "extract_shadow".equals(String.valueOf(detail.get("stage")))) {
                return;
            }
            int kept = auditInt(detail.get("kept"));
            quality.setExtractRuns(quality.getExtractRuns() + 1);
            quality.setExtractKept(quality.getExtractKept() + kept);
            if (kept == 0) {
                quality.setExtractEmpty(quality.getExtractEmpty() + 1);
            }
            quality.setNeedsContext(quality.getNeedsContext() + auditInt(detail.get("needsContext")));
            quality.setLowConfidence(quality.getLowConfidence() + auditInt(detail.get("lowConfidence")));
            quality.setLastExtractAt(formatInstant(ts));
            return;
        }
        if (!AiMemoryAuditLogger.EVENT_CONSOLIDATE.equals(event)) {
            return;
        }
        if ("FAIL".equals(result)) {
            quality.setConsolidateFailures(quality.getConsolidateFailures() + 1);
            return;
        }
        if (!"OK".equals(result)) {
            return;
        }
        quality.setConsolidateRuns(quality.getConsolidateRuns() + 1);
        quality.setLastConsolidateMs(auditLong(detail.get("elapsedMs")));
        quality.setLastConsolidateTokens(auditLong(detail.get("tokens")));
        quality.setLastConsolidateAt(formatInstant(ts));
    }

    /**
     * 解析审计时间；无时区后缀的本地时间按系统时区补全。
     *
     * @param value 原始时间值
     * @return 时间点；无法解析时返回 null
     */
    private Instant parseAuditTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(String.valueOf(value).trim()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /**
     * 读取审计事件 detail 中的长整数值（缺失或格式异常时返回 0）。
     *
     * @param value 原始值
     * @return 长整数值
     */
    private long auditLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public List<AiMemoryDeclaredVo> listDeclared(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        String workspaceCode = workspaceCodeOf(definition);
        return memoryDeclaredStore.listAll(workspaceCode, String.valueOf(userId)).stream()
            .map(this::toDeclaredVo)
            .toList();
    }

    @Override
    public AiMemoryDeclaredVo addDeclared(AiMemoryDeclaredBo bo) {
        AiAgent definition = agentService.getEnabledAgent(bo.getAgentId());
        String workspaceCode = workspaceCodeOf(definition);
        AiMemorySanitizer.SanitizeResult sanitized = sanitizer.sanitizeLedger(bo.getFact());
        String fact = sanitized.changed() ? sanitized.content().trim() : bo.getFact().trim();
        if (StringUtils.isBlank(fact)) {
            throw new ServiceException("记忆内容净化后为空，未写入显式记忆");
        }
        String evidence = bo.getEvidence() == null ? "" : sanitizer.sanitizeLedger(bo.getEvidence()).content();
        try {
            AiMemoryDeclaredStore.DeclaredMemory entry = memoryDeclaredStore.append(
                workspaceCode, String.valueOf(bo.getUserId()), fact, evidence);
            auditLogger.record(definition.getId(), bo.getUserId(),
                AiMemoryAuditLogger.EVENT_USER_REMEMBER, "OK",
                Map.of("entryId", entry.id(), "chars", entry.fact().length(),
                    "redacted", sanitized.redacted()));
            return toDeclaredVo(entry);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    @Override
    public boolean revokeDeclared(Long agentId, Long userId, String entryId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        String workspaceCode = workspaceCodeOf(definition);
        boolean revoked = memoryDeclaredStore.revoke(workspaceCode, String.valueOf(userId), entryId);
        if (revoked) {
            auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_USER_FORGET, "OK",
                Map.of("entryId", entryId));
        }
        return revoked;
    }

    /**
     * 把显式记忆实体转换为展示对象。
     *
     * @param entry 显式记忆实体
     * @return 展示对象
     */
    private AiMemoryDeclaredVo toDeclaredVo(AiMemoryDeclaredStore.DeclaredMemory entry) {
        AiMemoryDeclaredVo vo = new AiMemoryDeclaredVo();
        vo.setId(entry.id());
        vo.setFact(entry.fact());
        vo.setEvidence(entry.evidence());
        vo.setCreatedAt(entry.createdAt());
        vo.setRevoked(entry.revoked());
        return vo;
    }

    @Override
    public List<AiMemoryVersionVo> listVersions(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        String current = workspace.readMemoryMd(ctx);
        return memoryVersionStore.list(memoryDir).stream()
            .map(entry -> {
                AiMemoryVersionVo vo = toVersionVo(entry, null, current);
                vo.setCurrent(memoryVersionStore.matches(entry, current));
                return vo;
            })
            .toList();
    }

    @Override
    public AiMemoryVersionVo readVersion(Long agentId, Long userId, String versionId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        AiMemoryVersionStore.VersionEntry entry = memoryVersionStore.read(memoryDir, versionId);
        String content = memoryVersionStore.readContent(memoryDir, versionId);
        return toVersionVo(entry, content, workspace.readMemoryMd(ctx));
    }

    @Override
    public AiMemoryVersionVo restoreVersion(Long agentId, Long userId, String versionId,
                                            String mode, String note) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        Path memoryDir = workspace.getMemoryDir(ctx);
        AiMemoryVersionStore.VersionEntry target = memoryVersionStore.read(memoryDir, versionId);
        String targetContent = memoryVersionStore.readContent(memoryDir, versionId);
        String current = workspace.readMemoryMd(ctx);
        if (targetContent.equals(current)) {
            return toVersionVo(target, targetContent, current);
        }
        // 恢复前先保存当前版本：即使恢复选错，也能再回退
        snapshotMemory(memoryDir, current, "PRE_RESTORE", userId,
            StringUtils.defaultIfBlank(note, "恢复前自动快照"), versionId);
        workspace.writeUtf8WorkspaceRelative(ctx, MEMORY_MD, targetContent);
        if ("CONTENT_AND_WATERMARK".equals(mode)) {
            if (StringUtils.isBlank(target.watermark())) {
                try {
                    Files.deleteIfExists(memoryDir.resolve(CONSOLIDATION_STATE));
                } catch (IOException e) {
                    throw new ServiceException("恢复合并水位失败：" + e.getMessage());
                }
            } else {
                workspace.writeUtf8WorkspaceRelative(ctx, CONSOLIDATION_STATE_PATH, target.watermark());
            }
        }
        String newVersionId = snapshotMemory(memoryDir, targetContent, "RESTORE", userId,
            StringUtils.defaultIfBlank(note, "从历史版本恢复"), versionId);
        auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_RESTORE, "OK",
            Map.of("versionId", versionId, "mode", mode,
                "newVersionId", StringUtils.defaultString(newVersionId),
                "chars", targetContent.length()));
        log.info("长期记忆已恢复：agent={}, userId={}, version={}, mode={}, newVersion={}",
            definition.getAgentCode(), userId, versionId, mode, newVersionId);
        AiMemoryVersionStore.VersionEntry created = StringUtils.isBlank(newVersionId)
            ? target : memoryVersionStore.read(memoryDir, newVersionId);
        return toVersionVo(created, targetContent, targetContent);
    }

    /**
     * 记录一次 MEMORY.md 版本快照；内容为空时跳过。
     *
     * @param memoryDir    记忆目录
     * @param content      正文
     * @param source       来源
     * @param operatorId   操作人
     * @param note         备注
     * @param restoredFrom 恢复来源版本
     * @return 版本 ID
     */
    private String snapshotMemory(Path memoryDir, String content, String source, Long operatorId,
                                  String note, String restoredFrom) {
        if (StringUtils.isBlank(content) || memoryDir == null) {
            return null;
        }
        String watermark = formatInstant(readConsolidationWatermark(memoryDir));
        return memoryVersionStore.record(memoryDir, content, source, watermark, operatorId,
            note, restoredFrom, runtimeConfigStore.memory().getVersionRetention());
    }

    /**
     * 版本实体转展示对象。
     */
    private AiMemoryVersionVo toVersionVo(AiMemoryVersionStore.VersionEntry entry, String content,
                                          String current) {
        AiMemoryVersionVo vo = new AiMemoryVersionVo();
        vo.setVersionId(entry.versionId());
        vo.setCreatedAt(entry.createdAt());
        vo.setSource(entry.source());
        vo.setOperatorId(entry.operatorId());
        vo.setChars(entry.chars());
        vo.setWatermark(entry.watermark());
        vo.setNote(entry.note());
        vo.setRestoredFrom(entry.restoredFrom());
        vo.setContent(content);
        vo.setCurrent(content != null && content.equals(current));
        return vo;
    }

    /**
     * 判断用户目录下是否已有记忆数据（手工写入但从未对话的用户也要纳入维护）。
     *
     * @param userDir 用户命名空间目录
     * @return 存在记忆目录或长期记忆文件时返回 true
     */
    private boolean hasMemoryData(Path userDir) {
        return Files.isDirectory(userDir.resolve("memory"))
            || Files.isRegularFile(userDir.resolve(MEMORY_MD));
    }

    /**
     * 统计记忆目录下的每日流水数量（{@code YYYY-MM-DD.md}）。
     *
     * @param dir 记忆目录或归档目录
     * @return 文件数
     */
    private int countLedgerFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 读取框架合并水位（{@code memory/.consolidation_state}）。
     *
     * @param memoryDir 记忆目录
     * @return 水位时间；文件缺失或无法解析时返回 {@link Instant#EPOCH}
     */
    private Instant readConsolidationWatermark(Path memoryDir) {
        Path state = memoryDir.resolve(CONSOLIDATION_STATE);
        if (!Files.isRegularFile(state)) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(Files.readString(state, StandardCharsets.UTF_8).strip());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    /**
     * 判断文件最后修改时间是否晚于合并水位。
     */
    private boolean modifiedAfter(Path file, Instant watermark) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isAfter(watermark);
        } catch (IOException e) {
            // 读不到修改时间时按「未合并」处理，宁可多合并一次也不丢事实
            return true;
        }
    }

    /**
     * 汇总审计文件中的归档时间与失败次数。
     *
     * @param memoryDir 记忆目录
     * @return 审计摘要
     */
    @SuppressWarnings("unchecked")
    private AuditSummary summarizeAudit(Path memoryDir) {
        Path auditFile = memoryDir == null ? null : memoryDir.resolve(AiMemoryAuditLogger.AUDIT_FILE);
        if (auditFile == null || !Files.isRegularFile(auditFile)) {
            return new AuditSummary("", 0, 0, 0, 0);
        }
        String lastArchivedAt = "";
        int failed = 0;
        Map<String, Object> lastConsolidation = Map.of();
        try (Stream<String> lines = Files.lines(auditFile, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                Map<String, Object> entry = JsonUtils.parseMap(line);
                if (entry == null) {
                    continue;
                }
                String event = String.valueOf(entry.getOrDefault("event", ""));
                String result = String.valueOf(entry.getOrDefault("result", ""));
                if ("FAIL".equals(result)) {
                    failed++;
                }
                if (AiMemoryAuditLogger.EVENT_ARCHIVE.equals(event) && "OK".equals(result)) {
                    Object ts = entry.get("ts");
                    lastArchivedAt = ts == null ? lastArchivedAt : ts.toString();
                }
                if (AiMemoryAuditLogger.EVENT_CONSOLIDATE.equals(event) && "OK".equals(result)) {
                    Object detail = entry.get("detail");
                    if (detail instanceof Map<?, ?> map) {
                        lastConsolidation = (Map<String, Object>) map;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取记忆审计日志失败：dir={}, err={}", memoryDir, e.getMessage());
        }
        return new AuditSummary(lastArchivedAt, failed,
            auditInt(lastConsolidation.get("batches")),
            auditInt(lastConsolidation.get("files")),
            auditInt(lastConsolidation.get("remaining")));
    }

    /**
     * 读取审计事件 detail 中的整数值（缺失或格式异常时返回 0）。
     *
     * @param value 原始值
     * @return 整数值
     */
    private int auditInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 格式化时间用于接口展示（为空时返回空串）。
     */
    private String formatInstant(Instant instant) {
        if (instant == null || Instant.EPOCH.equals(instant)) {
            return "";
        }
        return instant.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
    }

    /**
     * 审计摘要（最后归档时间 + 失败事件数）。
     *
     * @param lastArchivedAt       最后归档时间（ISO 时间，无记录时为空串）
     * @param failedCount          失败事件数
     * @param lastBatches          最近一次成功合并的批次数
     * @param lastMergedFiles      最近一次成功合并纳入的文件数
     * @param lastRemainingFiles   最近一次成功合并后剩余未处理的文件数
     */
    private record AuditSummary(String lastArchivedAt, int failedCount, int lastBatches,
                                int lastMergedFiles, int lastRemainingFiles) {
    }

    /**
     * 删除记忆目录下超过保留日期的每日流水（文件名形如 {@code YYYY-MM-DD.md}；其余文件跳过）。
     *
     * @param dir    记忆目录或归档目录
     * @param cutoff 保留截止日期（早于或等于该日期的文件被删除）
     * @return 删除的文件数
     */
    private int deleteExpiredMemoryFiles(Path dir, LocalDate cutoff) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> expired = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(path -> isOnOrBefore(path, cutoff))
                .toList();
            for (Path file : expired) {
                Files.deleteIfExists(file);
                removed++;
            }
        } catch (IOException e) {
            log.warn("清理记忆流水失败：dir={}, err={}", dir, e.getMessage());
        }
        return removed;
    }

    /**
     * 按文件修改时间清理目录下的普通文件（隔离原文、影子样本等）。
     *
     * @param dir    目标目录
     * @param cutoff 保留截止日期
     * @return 删除的文件数
     */
    private int deleteExpiredByMtime(Path dir, LocalDate cutoff) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Instant threshold = cutoff.atStartOfDay(ZoneId.systemDefault()).toInstant();
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> expired = files.filter(Files::isRegularFile)
                .filter(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toInstant().isBefore(threshold);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .toList();
            for (Path file : expired) {
                Files.deleteIfExists(file);
                removed++;
            }
        } catch (IOException e) {
            log.warn("清理记忆辅助文件失败：dir={}, err={}", dir, e.getMessage());
        }
        return removed;
    }

    /**
     * 按行时间裁剪审计日志：仅保留 {@code ts >= cutoff} 的事件。
     *
     * @param auditFile 审计文件
     * @param cutoff    保留截止日期
     * @return 裁剪掉的行数
     */
    private int pruneAudit(Path auditFile, LocalDate cutoff) {
        if (!Files.isRegularFile(auditFile)) {
            return 0;
        }
        Instant threshold = cutoff.atStartOfDay(ZoneId.systemDefault()).toInstant();
        try {
            List<String> kept = new ArrayList<>();
            int removed = 0;
            for (String line : Files.readAllLines(auditFile, StandardCharsets.UTF_8)) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                Map<String, Object> entry = JsonUtils.parseMap(line);
                Object ts = entry == null ? null : entry.get("ts");
                if (ts == null) {
                    kept.add(line);
                    continue;
                }
                try {
                    if (Instant.parse(ts.toString()).isBefore(threshold)) {
                        removed++;
                    } else {
                        kept.add(line);
                    }
                } catch (Exception e) {
                    // 无法解析时间的事件保留，避免误删审计证据
                    kept.add(line);
                }
            }
            if (removed > 0) {
                Path temp = auditFile.resolveSibling(auditFile.getFileName() + ".tmp");
                Files.writeString(temp, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
                Files.move(temp, auditFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            return removed;
        } catch (Exception e) {
            log.warn("审计日志裁剪失败：file={}, err={}", auditFile, e.getMessage());
            return 0;
        }
    }

    /**
     * 删除超过保留期的会话日志（工作区 session 日志，按文件修改时间判定）。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param cutoff    保留截止日期
     * @return 删除的文件数
     */
    private int deleteExpiredSessionLogs(WorkspaceManager workspace, RuntimeContext ctx, LocalDate cutoff) {
        int removed = 0;
        for (String relPath : safeSessionLogPaths(workspace, ctx)) {
            try {
                Path file = workspace.resolveRuntimeDataPath(ctx, relPath);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                LocalDate modified = LocalDate.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), java.time.ZoneId.systemDefault());
                if (!modified.isAfter(cutoff)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            } catch (Exception e) {
                log.warn("清理会话日志失败：path={}, err={}", relPath, e.getMessage());
            }
        }
        return removed;
    }

    /**
     * 列出会话日志相对路径（读取失败时返回空列表，不阻断清理）。
     */
    private List<String> safeSessionLogPaths(WorkspaceManager workspace, RuntimeContext ctx) {
        try {
            List<String> paths = workspace.listSessionLogFiles(ctx);
            return paths == null ? List.of() : paths;
        } catch (Exception e) {
            log.warn("读取会话日志清单失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 判断流水文件名（{@code YYYY-MM-DD.md}）是否早于或等于截止日期；无法解析时返回 false（保留）。
     */
    private boolean isOnOrBefore(Path file, LocalDate cutoff) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(name.substring(0, name.length() - 3));
            return !date.isAfter(cutoff);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归删除目录（不存在时静默返回）。
     */
    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new ServiceException("删除记忆目录失败：" + e.getMessage());
        }
    }

    /**
     * 读取「合并水位之后」的每日流水，并在参与合并前逐文件净化。
     *
     * <p>按文件名升序（旧→新）返回，配合 {@link #nextWatermark} 形成可恢复的批处理：
     * 每批成功后水位只推进到下一批之前，未被本批纳入的文件仍晚于水位，不会被永久漏掉。</p>
     *
     * <p>每个流水文件先净化并就地回写（原文备份到 {@code memory/.quarantine/}），
     * 这样无论后续是平台合并还是框架自动合并，读到的都是净化后的内容。</p>
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param definition 智能体定义
     * @param userId    用户主键
     * @param watermark 上一次合并水位
     * @return 待合并流水（可能为空）
     */
    private List<DailyLedger> loadPendingLedgers(WorkspaceManager workspace, RuntimeContext ctx,
                                                 AiAgent definition, Long userId, Instant watermark) {
        List<String> dailyPaths = safeMemoryPaths(workspace, ctx).stream()
            // 只认根目录的 YYYY-MM-DD.md：history/ 版本、archive/、.declared.jsonl 一律不参与合并
            .filter(path -> LEDGER_PATH_PATTERN.matcher(path).matches())
            .sorted(Comparator.comparing(this::fileNameOf))
            .toList();
        Path memoryDir = workspace.getMemoryDir(ctx);
        List<DailyLedger> pending = new ArrayList<>();
        AiMemorySanitizer.SanitizeResult total = AiMemorySanitizer.SanitizeResult.empty("");
        for (String path : dailyPaths) {
            Path file = memoryDir.resolve(fileNameOf(path));
            if (!Files.isRegularFile(file)) {
                continue;
            }
            // 水位只覆盖「已经合并过」的文件：早于或等于水位的流水不再重复读取
            Instant modified = lastModified(file);
            if (!modified.isAfter(watermark)) {
                continue;
            }
            String raw = readQuietly(workspace, ctx, path);
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            AiMemorySanitizer.SanitizeResult sanitized = sanitizer.sanitizeLedger(raw);
            if (sanitized.changed()) {
                rewriteWithQuarantine(workspace, ctx, path, sanitized.content());
                // 回写会刷新 mtime，水位按回写后的时间记录，避免下一轮重复合并
                modified = lastModified(file);
                total = total.plus(sanitized);
            }
            if (StringUtils.isBlank(sanitized.content())) {
                // 全部条目都被净化剔除（例如只剩指令性内容）：文件已回写为空，不再送入模型
                continue;
            }
            pending.add(new DailyLedger(path, sanitized.content(), modified,
                sanitizer.countEntries(sanitized.content())));
        }
        if (total.changed()) {
            auditLogger.record(definition.getId(), userId, AiMemoryAuditLogger.EVENT_SANITIZE, "OK",
                Map.of("dropped", total.dropped(), "deduped", total.deduped(),
                    "redacted", total.redacted(), "truncated", total.truncated(),
                    "kept", total.kept()));
        }
        return pending;
    }

    /**
     * 计算当前批次的结束下标：在字符与条目预算内尽量多纳入文件，且至少包含一个文件。
     *
     * <p>单个文件自身超预算时也整文件纳入，保证事实不被截断；代价是该批 prompt 可能偏大。</p>
     *
     * @param pending  待合并流水（旧→新）
     * @param start    当前批次起始下标
     * @param maxChars 单批字符预算
     * @return 批次结束下标（不含）
     */
    private int nextBatchEnd(List<DailyLedger> pending, int start, int maxChars) {
        int end = start;
        int chars = 0;
        int entries = 0;
        while (end < pending.size()) {
            DailyLedger daily = pending.get(end);
            String block = renderBlock(daily);
            if (end > start && (chars + block.length() > maxChars
                || entries + daily.entries() > AiMemorySanitizer.MAX_ENTRIES_PER_MERGE)) {
                break;
            }
            chars += block.length();
            entries += daily.entries();
            end++;
        }
        return end;
    }

    /**
     * 把一批流水渲染为合并输入正文。
     *
     * @param batch 批次
     * @return 合并输入
     */
    private String renderBatch(List<DailyLedger> batch) {
        StringBuilder ledger = new StringBuilder();
        for (DailyLedger daily : batch) {
            ledger.append(renderBlock(daily)).append("\n\n");
        }
        return ledger.toString().trim();
    }

    /**
     * 单个流水文件的合并输入块。
     *
     * @param daily 流水
     * @return 输入块
     */
    private String renderBlock(DailyLedger daily) {
        return "### " + fileNameOf(daily.path()) + '\n' + daily.content();
    }

    /**
     * 计算本批合并后应写入的水位。
     *
     * <p>水位必须严格小于「下一批最早文件」的修改时间，否则下一轮会把未合并文件误判为已合并。
     * 本批处理完所有文件时使用 {@code runStart}：任何在合并开始后新写入的流水仍晚于水位，下一轮继续。</p>
     *
     * @param pending  待合并流水（旧→新）
     * @param nextIndex 下一批起始下标
     * @param runStart 本轮合并开始时间
     * @param previous 本轮开始前的水位
     * @return 应写入的水位
     */
    private Instant nextWatermark(List<DailyLedger> pending, int nextIndex, Instant runStart,
                                  Instant previous) {
        if (nextIndex >= pending.size()) {
            return runStart;
        }
        Instant nextModified = pending.subList(nextIndex, pending.size()).stream()
            .map(DailyLedger::modifiedAt)
            .min(Comparator.naturalOrder())
            .orElse(runStart);
        if (Instant.EPOCH.equals(nextModified)) {
            // 无法读取下一批文件时间时宁可不推进水位，避免把未合并文件误判为已合并
            return previous;
        }
        Instant candidate = nextModified.isAfter(runStart)
            ? runStart : nextModified.minusNanos(1);
        return candidate.isBefore(previous) ? previous : candidate;
    }

    /**
     * 读取文件最后修改时间；读取失败时返回当前时间，确保该文件仍会被纳入本轮合并。
     *
     * @param file 文件
     * @return 修改时间
     */
    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    /**
     * 就地重写净化后的流水：先复制原文到 {@code memory/.quarantine/} 再覆盖，失败时保留原文件。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param path      工作区相对路径
     * @param content   净化后正文
     */
    private void rewriteWithQuarantine(WorkspaceManager workspace, RuntimeContext ctx,
                                       String path, String content) {
        Path memoryDir = workspace.getMemoryDir(ctx);
        Path file = memoryDir.resolve(fileNameOf(path));
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Path backup = memoryDir.resolve(QUARANTINE_DIR)
                .resolve(fileNameOf(path) + "." + BACKUP_TIME_FORMAT.format(java.time.LocalDateTime.now()) + ".bak");
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("净化后回写流水失败，保留原文件：path={}, err={}", path, e.getMessage());
        }
    }

    /**
     * 调用记忆模型执行合并，返回新的 MEMORY.md 全文与用量。
     *
     * <p>手动入口按框架同一提示词模板完成全量重跑；框架自动合并已按用户水位增量执行，
     * 两者互不重复。</p>
     *
     * @param model     记忆模型
     * @param maxTokens 合并结果允许的 Token 上限
     * @param memoryMd  现有 MEMORY.md 内容
     * @param ledger    每日流水正文
     * @return 合并结果（正文 + 用量）
     */
    private ConsolidationResult callConsolidationModel(Model model, int maxTokens, String memoryMd,
                                                       String ledger) {
        String userContent = "Current MEMORY.md content:\n\n"
            + (StringUtils.isBlank(memoryMd) ? "(empty)" : memoryMd)
            + "\n\nNew daily ledger entries to merge:\n\n" + ledger;
        List<Msg> messages = List.of(
            Msg.builder().role(MsgRole.SYSTEM).textContent(consolidationPrompt(maxTokens)).build(),
            Msg.builder().role(MsgRole.USER).textContent(userContent).build());
        List<ChatResponse> responses = model
            .stream(messages, null, GenerateOptions.builder().stream(true).build())
            .collectList()
            .block(CONSOLIDATE_TIMEOUT);
        if (responses == null) {
            return new ConsolidationResult("", null);
        }
        StringBuilder merged = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        for (ChatResponse response : responses) {
            ChatUsage usage = response.getUsage();
            if (usage != null) {
                promptTokens += usage.getInputTokens();
                completionTokens += usage.getOutputTokens();
            }
            if (response.getContent() == null) {
                continue;
            }
            response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .forEach(block -> merged.append(block.getText()));
        }
        ChatTokenUsage tokens = promptTokens + completionTokens == 0 ? null
            : new ChatTokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        return new ConsolidationResult(merged.toString().trim(), tokens);
    }

    /**
     * 把手动合并的模型调用计入用量统计（与对话同口径：按用户 × 智能体 × 模型 × 日期累加）。
     *
     * <p>用量记录失败只告警，不影响合并结果落盘。</p>
     *
     * @param definition 智能体定义
     * @param userId     记忆归属用户主键
     * @param usage      本次 Token 用量（为空表示提供方未返回）
     */
    private void recordConsolidationUsage(AiAgent definition, Long userId, ChatTokenUsage usage) {
        if (usage == null) {
            return;
        }
        try {
            Long modelId = definition.getMemoryModelId() != null
                ? definition.getMemoryModelId() : definition.getModelId();
            usageStatService.accumulate(userId, definition.getId(), modelId, usage, 0, 1);
        } catch (Exception e) {
            log.warn("长期记忆合并用量记录失败：agent={}, userId={}, err={}",
                definition.getAgentCode(), userId, e.getMessage());
        }
    }

    /**
     * 手动合并结果（正文 + 模型用量）。
     *
     * @param content 合并后的 MEMORY.md 全文
     * @param usage   Token 用量（提供方未返回时为空）
     */
    private record ConsolidationResult(String content, ChatTokenUsage usage) {
    }

    /**
     * 待合并的单个流水文件。
     *
     * @param path       工作区相对路径
     * @param content    净化后的正文
     * @param modifiedAt 文件修改时间（净化回写后的时间）
     * @param entries    条目数
     */
    private record DailyLedger(String path, String content, Instant modifiedAt, int entries) {
    }

    /**
     * 构造合并提示词（使用平台个人信息口径模板，仅填充其两个数字占位符）。
     *
     * @param maxTokens 合并结果允许的 Token 上限
     * @return 提示词
     */
    private String consolidationPrompt(int maxTokens) {
        try {
            return String.format(AiMemoryPrompts.CONSOLIDATION_PROMPT, maxTokens, maxTokens * 4);
        } catch (RuntimeException e) {
            log.warn("合并提示词格式化失败，回退平台模板原文：{}", e.getMessage());
            return AiMemoryPrompts.CONSOLIDATION_PROMPT;
        }
    }

    /**
     * 解析智能体工作区管理器。
     *
     * <p>使用 {@link io.agentscope.harness.agent.HarnessAgent#getWorkspaceManager()} 返回的框架内置实例，
     * 与记忆抽取 / 合并中间件使用同一实例，保证读写路径口径一致（按 {@code IsolationScope.USER} 隔离）。</p>
     *
     * @param definition 智能体定义
     * @return 工作区管理器
     */
    private WorkspaceManager workspaceOf(AiAgent definition) {
        // 专家与入口智能体共享同一份用户长期记忆（REQ-AI-09 扩展）：非入口定义一律取入口的工作区，
        // 保证管理页读到的就是专家实际使用的那份（专家装配同样落在入口命名空间）
        AiAgent memoryAgent = definition;
        if (definition != null && !definition.isEntry()) {
            AiAgent entry = agentService.getEntryAgent();
            if (entry != null) {
                memoryAgent = entry;
            }
        }
        return agentRegistry.get(memoryAgent).getWorkspaceManager();
    }

    /**
     * 解析用户记忆所属的工作区命名空间：专家与入口共享入口智能体编码。
     *
     * @param definition 智能体定义
     * @return 工作区编码
     */
    private String workspaceCodeOf(AiAgent definition) {
        AiAgent memoryAgent = definition;
        if (definition != null && !definition.isEntry()) {
            AiAgent entry = agentService.getEntryAgent();
            if (entry != null) {
                memoryAgent = entry;
            }
        }
        return memoryAgent == null ? "" : memoryAgent.getAgentCode();
    }

    /**
     * 构造记忆控制台使用的运行上下文。
     *
     * @param userId 记忆归属用户主键
     * @return 运行上下文
     */
    private RuntimeContext contextOf(Long userId) {
        return RuntimeContext.builder()
            .userId(String.valueOf(userId))
            .sessionId(CONSOLE_SESSION)
            .build();
    }

    /**
     * 列出记忆文件相对路径（读取失败时返回空列表，不阻断页面）。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @return 相对路径列表
     */
    private List<String> safeMemoryPaths(WorkspaceManager workspace, RuntimeContext ctx) {
        try {
            List<String> paths = workspace.listMemoryFilePaths(ctx);
            return paths == null ? List.of() : paths;
        } catch (Exception e) {
            log.warn("读取记忆文件清单失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 安全读取工作区记忆文件（不存在或读取失败时返回 null）。
     *
     * <p>长期记忆走框架的 {@code readMemoryMd}；每日流水按用户隔离后的记忆目录直接读取，
     * 避免受管文件接口只放行固定文件的限制。</p>
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param path      工作区相对路径
     * @return 文件正文
     */
    private String readQuietly(WorkspaceManager workspace, RuntimeContext ctx, String path) {
        try {
            if (MEMORY_MD.equals(path)) {
                return workspace.readMemoryMd(ctx);
            }
            Path file = workspace.getMemoryDir(ctx).resolve(fileNameOf(path));
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            log.warn("读取记忆文件失败：path={}, {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 构造记忆文件视图对象。
     *
     * @param path    相对路径
     * @param name    文件名
     * @param type    文件类型
     * @param date    日期
     * @param content 文件正文
     * @return 视图对象
     */
    private AiMemoryFileVo buildFile(String path, String name, String type, String date, String content) {
        AiMemoryFileVo vo = new AiMemoryFileVo();
        vo.setPath(path);
        vo.setName(name);
        vo.setType(type);
        vo.setDate(date);
        vo.setChars(content == null ? 0 : content.length());
        return vo;
    }

    /**
     * 取路径中的文件名。
     *
     * @param path 相对路径
     * @return 文件名
     */
    private String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /**
     * 由文件名推断日期（每日流水命名为 YYYY-MM-DD.md）。
     *
     * @param path 相对路径
     * @return 日期字符串
     */
    private String dateOf(String path) {
        String name = fileNameOf(path);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * 校验记忆文件路径，阻止越权访问工作区其它文件。
     *
     * @param path 工作区相对路径
     * @return 校验通过的路径
     */
    private String validatePath(String path) {
        if (StringUtils.isBlank(path) || !ALLOWED_PATH.matcher(path).matches()) {
            throw new ServiceException("非法的记忆文件路径：" + path);
        }
        return path;
    }

}