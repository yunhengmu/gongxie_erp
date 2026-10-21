package org.dromara.ai.service.impl;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.service.IAiUsageStatService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiUsageStatService usageStatService;

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
        workspace.writeUtf8WorkspaceRelative(ctx, safePath, content);
        log.info("长期记忆已更新：agent={}, userId={}, path={}, chars={}",
            definition.getAgentCode(), bo.getUserId(), safePath, content.length());
    }

    @Override
    public void consolidate(Long agentId, Long userId) {
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        int maxTokens = policy.getConsolidationMaxTokens();
        // 输入预算与配置的合并 Token 上限对齐（1 Token ≈ 4 字符），超限只保留最近的流水
        int maxChars = Math.max(4000, maxTokens * 4);
        String memoryMd = workspace.readMemoryMd(ctx);
        String ledger = readDailyLedger(workspace, ctx, maxChars);
        if (StringUtils.isBlank(ledger)) {
            log.info("长期记忆合并跳过（无每日流水）：agent={}, userId={}",
                definition.getAgentCode(), userId);
            return;
        }
        Model model = agentRegistry.memoryModelFor(definition);
        long started = System.currentTimeMillis();
        ConsolidationResult result = callConsolidationModel(model, maxTokens, memoryMd, ledger);
        if (StringUtils.isBlank(result.content())) {
            throw new ServiceException("记忆合并失败：模型未返回有效内容，请检查记忆模型是否可用");
        }
        workspace.writeUtf8WorkspaceRelative(ctx, MEMORY_MD, result.content());
        recordConsolidationUsage(definition, userId, result.usage());
        log.info("长期记忆合并完成：agent={}, userId={}, 流水 {} 字符, MEMORY.md {} 字符 → {} 字符, "
                + "tokens={}, 耗时={}ms",
            definition.getAgentCode(), userId, ledger.length(), StringUtils.length(memoryMd),
            result.content().length(), result.usage() == null ? 0 : result.usage().totalTokens(),
            System.currentTimeMillis() - started);
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
        log.info("长期记忆清理完成：agent={}, userId={}, 删除 {} 个文件（流水 {} 天 / 归档 {} 天 / 会话 {} 天）",
            definition.getAgentCode(), userId, removed, policy.getDailyFileRetentionDays(),
            policy.getArchiveRetentionDays(), policy.getSessionRetentionDays());
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
     * 拼接每日流水正文（用于手动合并），并按字符预算优先保留最近的流水。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @param maxChars  流水正文的字符上限（超限时只保留最近的流水）
     * @return 流水正文，无流水时为空串
     */
    private String readDailyLedger(WorkspaceManager workspace, RuntimeContext ctx, int maxChars) {
        // 按文件名（YYYY-MM-DD.md）倒序，优先纳入最近几天；超限的旧流水跳过，
        // 其内容通常已由框架的增量合并写入 MEMORY.md
        List<String> dailyPaths = safeMemoryPaths(workspace, ctx).stream()
            .filter(path -> !MEMORY_MD.equals(path))
            .sorted(Comparator.comparing(this::fileNameOf).reversed())
            .toList();
        List<String> blocks = new ArrayList<>();
        int chars = 0;
        int skipped = 0;
        for (String path : dailyPaths) {
            String content = readQuietly(workspace, ctx, path);
            if (StringUtils.isBlank(content)) {
                continue;
            }
            String block = "### " + fileNameOf(path) + '\n' + content;
            // 首个（最新）流水即使超限也保留，避免空输入；其余按预算截断
            if (!blocks.isEmpty() && chars + block.length() > maxChars) {
                skipped++;
                continue;
            }
            blocks.add(0, block);
            chars += block.length();
        }
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder ledger = new StringBuilder();
        if (skipped > 0) {
            ledger.append("（仅包含最近的 ").append(blocks.size())
                .append(" 个流水文件，更早内容已省略）\n\n");
        }
        for (String block : blocks) {
            ledger.append(block).append("\n\n");
        }
        return ledger.toString().trim();
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
     * 构造合并提示词（复用框架默认模板，仅填充其两个数字占位符）。
     *
     * @param maxTokens 合并结果允许的 Token 上限
     * @return 提示词
     */
    private String consolidationPrompt(int maxTokens) {
        try {
            return String.format(MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT, maxTokens, maxTokens * 4);
        } catch (RuntimeException e) {
            log.warn("合并提示词格式化失败，回退原始模板：{}", e.getMessage());
            return MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT;
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