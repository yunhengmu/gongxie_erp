package org.dromara.ai.service.impl;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
     * 自动合并节流／水位键前缀（按「智能体 × 用户」记录上次合并时刻）
     */
    private static final String CONSOLIDATE_MARK_PREFIX = "bizbuddy:ai:memory:consolidated:";

    /**
     * 自动合并的延迟触发秒数：等待框架「事实抽取」异步落盘后再合并，避免读到空流水
     */
    private static final long AUTO_CONSOLIDATE_DELAY_SECONDS = 30L;

    /**
     * 自动合并调度器（单线程串行，避免同一用户并发合并）
     */
    private final ScheduledExecutorService consolidationScheduler =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-memory-consolidate");
            thread.setDaemon(true);
            return thread;
        });

    private final IAiAgentService agentService;

    private final AgentRegistry agentRegistry;

    private final AiRuntimeConfigStore runtimeConfigStore;

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
        int maxTokens = runtimeConfigStore.memory().getConsolidationMaxTokens();
        String memoryMd = workspace.readMemoryMd(ctx);
        String ledger = readDailyLedger(workspace, ctx);
        if (StringUtils.isBlank(ledger)) {
            log.info("长期记忆合并跳过（无每日流水）：agent={}, userId={}",
                definition.getAgentCode(), userId);
            return;
        }
        Model model = agentRegistry.memoryModelFor(definition);
        long started = System.currentTimeMillis();
        String merged = callConsolidationModel(model, maxTokens, memoryMd, ledger);
        if (StringUtils.isBlank(merged)) {
            throw new ServiceException("记忆合并失败：模型未返回有效内容，请检查记忆模型是否可用");
        }
        workspace.writeUtf8WorkspaceRelative(ctx, MEMORY_MD, merged);
        log.info("长期记忆合并完成：agent={}, userId={}, MEMORY.md {} 字符 → {} 字符, 耗时={}ms",
            definition.getAgentCode(), userId, StringUtils.length(memoryMd), merged.length(),
            System.currentTimeMillis() - started);
    }

    /**
     * 拼接全部每日流水正文（用于合并）。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @return 流水正文，无流水时为空串
     */
    private String readDailyLedger(WorkspaceManager workspace, RuntimeContext ctx) {
        StringBuilder ledger = new StringBuilder();
        for (String path : safeMemoryPaths(workspace, ctx)) {
            if (MEMORY_MD.equals(path)) {
                continue;
            }
            String content = readQuietly(workspace, ctx, path);
            if (StringUtils.isNotBlank(content)) {
                ledger.append("### ").append(fileNameOf(path)).append('\n').append(content).append("\n\n");
            }
        }
        return ledger.toString().trim();
    }

    /**
     * 调用记忆模型执行合并，返回新的 MEMORY.md 全文。
     *
     * <p>说明：框架的 {@code MemoryConsolidator} 通过 filesystem 的 {@code glob} 定位每日流水，
     * 在用户级隔离目录下匹配不到文件（实测始终跳过），故此处按同一提示词自行调用模型完成合并。</p>
     *
     * @param model     记忆模型
     * @param maxTokens 合并结果允许的 Token 上限
     * @param memoryMd  现有 MEMORY.md 内容
     * @param ledger    每日流水正文
     * @return 合并后的 MEMORY.md 全文
     */
    private String callConsolidationModel(Model model, int maxTokens, String memoryMd, String ledger) {
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
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .forEach(block -> merged.append(block.getText()));
        }
        return merged.toString().trim();
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
        return agentRegistry.get(definition).getWorkspaceManager();
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

    @Override
    public void consolidateIfDue(Long agentId, Long userId) {
        if (agentId == null || userId == null) {
            return;
        }
        // 延迟提交：给框架「事实抽取」异步落盘留出时间；只提交不阻塞本轮回答
        consolidationScheduler.schedule(() -> {
            try {
                doConsolidateIfDue(agentId, userId);
            } catch (Exception e) {
                log.warn("长期记忆自动合并失败：agent={}, userId={}, err={}", agentId, userId, e.getMessage());
            }
        }, AUTO_CONSOLIDATE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 自动合并的实际判定与执行：开关 → 节流 → 新增流水，三者满足才合并。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    private void doConsolidateIfDue(Long agentId, Long userId) {
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        if (!policy.isEnabled()) {
            return;
        }
        String markKey = CONSOLIDATE_MARK_PREFIX + agentId + ":" + userId;
        Long lastRun = readConsolidateMark(markKey);
        long now = System.currentTimeMillis();
        long gapMillis = Math.max(1, policy.getConsolidationMinGapMinutes()) * 60_000L;
        if (lastRun != null && now - lastRun < gapMillis) {
            return;
        }
        AiAgent definition = agentService.getEnabledAgent(agentId);
        WorkspaceManager workspace = workspaceOf(definition);
        RuntimeContext ctx = contextOf(userId);
        long newest = latestDailyModified(workspace, ctx);
        if (newest <= 0L || (lastRun != null && newest <= lastRun)) {
            // 无新增流水：不产生额外模型调用
            return;
        }
        // 先落水位：合并可能耗时数分钟，避免期间并发重复触发
        RedisUtils.setCacheObject(markKey, String.valueOf(now));
        try {
            consolidate(agentId, userId);
        } catch (RuntimeException e) {
            // 失败则回退水位，允许后续对话尽快重试
            RedisUtils.deleteObject(markKey);
            throw e;
        }
    }

    /**
     * 读取上次自动合并时刻。
     *
     * @param key 水位键
     * @return 上次合并毫秒时间戳；无记录时返回 null
     */
    private Long readConsolidateMark(String key) {
        Object cached = RedisUtils.getCacheObject(key);
        if (cached == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(cached));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取每日流水目录下最新的修改时间（无流水目录或读取失败时返回 0）。
     *
     * @param workspace 工作区管理器
     * @param ctx       运行上下文
     * @return 最新修改时间毫秒时间戳
     */
    private long latestDailyModified(WorkspaceManager workspace, RuntimeContext ctx) {
        Path dir = workspace.getMemoryDir(ctx);
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .mapToLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .max().orElse(0L);
        } catch (IOException e) {
            log.warn("读取记忆流水目录失败：{}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 应用关闭时等待在跑的合并任务收尾。
     */
    @PreDestroy
    public void shutdownConsolidationScheduler() {
        consolidationScheduler.shutdown();
        try {
            if (!consolidationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                consolidationScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            consolidationScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}