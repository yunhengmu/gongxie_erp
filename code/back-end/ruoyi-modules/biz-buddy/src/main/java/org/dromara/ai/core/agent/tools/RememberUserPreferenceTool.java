package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.bo.AiMemoryDeclaredBo;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.service.IAiMemoryService;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 显式记忆写入工具：把用户**明确要求记住**的个人偏好写入长期记忆。
 *
 * <p>只注册给入口智能体：显式记忆由 {@code PlatformMemoryContextMiddleware} 在下一轮注入；
 * 专家只拿到任务片段、看不到用户原话，无法通过 {@link DeclaredMemoryGuard} 的证据校验。</p>
 *
 * <p>写入落在 {@code memory/.declared.jsonl}（权威存储），不参与合并、不会被 MEMORY.md 覆盖。
 * 该工具属低风险写操作（只影响提问用户自己的记忆，且可由用户在页面撤销），故装配期默认放行，
 * 不弹人工确认卡片。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class RememberUserPreferenceTool extends ToolBase {

    /**
     * 工具编码（不属于业务工具注册表，仅平台装配时注册）
     */
    public static final String TOOL_NAME = "remember_user_preference";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION =
        "把用户**明确要求长期记住**的个人偏好、习惯或约束写入长期记忆。"
            + "仅在用户本轮消息里明确说“请记住 / 记下 / 帮我记 / 备忘 / 以后都 / 我习惯 / 我喜欢……”这类要求时才调用；"
            + "普通提问、闲聊或你推测出的偏好一律不要调用。"
            + "fact 用一句简短的第三人称陈述；evidence 必须原样摘录用户本轮要求记住的那句原话。";

    private final IAiMemoryService memoryService;

    /**
     * 记忆归属的智能体主键（入口智能体；专家与入口共享同一份用户记忆）
     */
    private final Long agentId;

    /**
     * @param memoryService 长期记忆服务（净化、去重、条数上限与审计都在服务层统一处理）
     * @param agentId       记忆归属的智能体主键
     */
    public RememberUserPreferenceTool(IAiMemoryService memoryService, Long agentId) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(true));
        this.memoryService = memoryService;
        this.agentId = agentId;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long userId = AiToolSupport.operatorId(param);
        if (userId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前提问用户，已拒绝写入长期记忆");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String fact = AiToolSupport.stringValue(input.get("fact")).trim();
        String evidence = AiToolSupport.stringValue(input.get("evidence")).trim();
        if (fact.isEmpty()) {
            return AiToolSupport.fail(param, TOOL_NAME, "fact 不能为空，请用一句话概括要记住的内容");
        }
        String invalid = DeclaredMemoryGuard.validate(param, evidence, false);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        AiMemoryDeclaredBo bo = new AiMemoryDeclaredBo();
        bo.setAgentId(agentId);
        bo.setUserId(userId);
        bo.setFact(fact);
        bo.setEvidence(evidence);
        try {
            AiMemoryDeclaredVo entry = memoryService.addDeclared(bo);
            return AiToolSupport.ok(param, TOOL_NAME,
                "已记住：" + entry.getFact() + "（从下一轮对话起会带入上下文；用户可在记忆页撤销）");
        } catch (Exception e) {
            log.warn("写入显式记忆失败：userId={}, agentId={}, err={}", userId, agentId, e.getMessage());
            return AiToolSupport.fail(param, TOOL_NAME, "写入长期记忆失败：" + e.getMessage());
        }
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fact", Map.of(
            "type", "string",
            "description", "要长期记住的内容，一句话第三人称陈述，不超过 200 字"));
        properties.put("evidence", Map.of(
            "type", "string",
            "description", "用户本轮消息中要求记住的那句原话，必须原样摘录、不得改写"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("fact", "evidence"));
        return schema;
    }
}
