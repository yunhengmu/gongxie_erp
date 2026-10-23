package org.dromara.ai.core.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.memory.AiMemoryDeclaredStore;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 平台托管记忆注入中间件（A2）。
 *
 * <p>平台模式会同时调用 {@code disableMemoryHooks()} 与 {@code disableMemoryTools()}，
 * 框架不再注入 {@code MEMORY.md}；本中间件接管注入，顺序固定为：</p>
 *
 * <ol>
 *   <li>用户明确要求记住的条目（权威、优先、独立预算）；</li>
 *   <li>{@code MEMORY.md} 合并产物（按预算截断并显式标记）。</li>
 * </ol>
 *
 * <p>不直接依赖框架 {@code WorkspaceManager}：工作区布局按
 * {@code <workspaceRoot>/<workspaceCode>/<userId>/MEMORY.md} 口径解析，与记忆服务保持一致。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class PlatformMemoryContextMiddleware implements MiddlewareBase {

    /**
     * Token 与字符的粗略折算比例（1 Token ≈ 4 字符）
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * 截断提示（对模型可见，避免“没记住”与“被截断”混淆）
     */
    private static final String TRUNCATION_NOTICE = "\n\n[长期记忆已按预算截断；完整内容可通过记忆检索查看]";

    private final AiProperties aiProperties;

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiMemoryDeclaredStore declaredStore;

    /**
     * 工作区命名空间（专家与入口共享同一份用户记忆，使用入口智能体编码）
     */
    private final String workspaceCode;

    public PlatformMemoryContextMiddleware(AiProperties aiProperties,
                                           AiRuntimeConfigStore runtimeConfigStore,
                                           AiMemoryDeclaredStore declaredStore,
                                           String workspaceCode) {
        this.aiProperties = aiProperties;
        this.runtimeConfigStore = runtimeConfigStore;
        this.declaredStore = declaredStore;
        this.workspaceCode = workspaceCode;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt == null ? "" : currentPrompt;
        AiMemoryConfigBo policy = runtimeConfigStore.memory();
        if (!policy.isEnabled() || !"platform".equals(policy.getPipelineMode())) {
            return Mono.just(base);
        }
        String userId = ctx == null ? null : ctx.getUserId();
        if (userId == null || userId.isBlank()) {
            return Mono.just(base);
        }

        int totalChars = Math.max(500, policy.getMaxContextTokens()) * CHARS_PER_TOKEN;
        int declaredChars = Math.max(100, policy.getInjectDeclaredMaxTokens()) * CHARS_PER_TOKEN;
        String declaredSection = renderDeclared(declaredStore.listActive(workspaceCode, userId), declaredChars);
        int memoryBudget = Math.max(0, totalChars - declaredSection.length());
        String memorySection = renderMemory(userId, memoryBudget);
        if (declaredSection.isEmpty() && memorySection.isEmpty()) {
            return Mono.just(base);
        }
        return Mono.just(base + "\n\n" + declaredSection + memorySection);
    }

    /**
     * 渲染显式记忆段落；独立预算，最多保留预算内的条目。
     *
     * @param entries 生效条目
     * @param budget  字符预算
     * @return 段落（无条目时为空串）
     */
    private String renderDeclared(List<AiMemoryDeclaredStore.DeclaredMemory> entries, int budget) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder("## 用户明确要求记住\n");
        int used = section.length();
        int added = 0;
        for (AiMemoryDeclaredStore.DeclaredMemory entry : entries) {
            String line = "- " + entry.fact() + "\n";
            if (used + line.length() > budget) {
                break;
            }
            section.append(line);
            used += line.length();
            added++;
        }
        if (added == 0) {
            return "";
        }
        return section.toString();
    }

    /**
     * 渲染 MEMORY.md 段落；超预算时保留头部并追加显式截断提示。
     *
     * @param userId 用户主键
     * @param budget 字符预算
     * @return 段落（文件为空或预算为 0 时为空串）
     */
    private String renderMemory(String userId, int budget) {
        if (budget <= 0) {
            return "";
        }
        Path memoryFile = Path.of(aiProperties.getChat().getWorkspaceRoot(), workspaceCode, userId)
            .toAbsolutePath().normalize().resolve("MEMORY.md");
        if (!Files.isRegularFile(memoryFile)) {
            return "";
        }
        String content;
        try {
            content = Files.readString(memoryFile, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            log.warn("读取平台记忆失败：workspace={}, userId={}, err={}",
                workspaceCode, userId, e.getMessage());
            return "";
        }
        if (content.isBlank()) {
            return "";
        }
        boolean truncated = content.length() > budget;
        if (truncated) {
            content = content.substring(0, Math.max(0, budget - TRUNCATION_NOTICE.length()));
        }
        return "## 长期记忆\n<memory_context>\n" + content
            + (truncated ? TRUNCATION_NOTICE : "") + "\n</memory_context>\n";
    }
}
