package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.service.IAiMemoryService;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 显式记忆撤销工具：用户说「忘掉 / 别记 / 以后不用了」时撤销对应的显式记忆。
 *
 * <p>模型拿不到条目主键，故按关键词在**生效中的显式记忆**里匹配后逐条撤销；
 * 与写入工具相同，撤销也必须给出用户本轮原话片段作为证据，避免模型凭上下文擅自删记忆。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class ForgetUserPreferenceTool extends ToolBase {

    /**
     * 工具编码（仅平台装配时注册）
     */
    public static final String TOOL_NAME = "forget_user_preference";

    /**
     * 关键词最短长度（去空白后）
     */
    private static final int MIN_KEYWORD_CHARS = 2;

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION =
        "撤销用户此前要求长期记住的偏好或约束。仅当用户本轮明确说“忘掉 / 别记了 / 以后不用……”"
            + "时才调用；keyword 用要撤销内容里的关键词（如“表格”），evidence 必须原样摘录用户本轮"
            + "表达撤销意愿的那句原话。";

    private final IAiMemoryService memoryService;

    /**
     * 记忆归属的智能体主键
     */
    private final Long agentId;

    /**
     * @param memoryService 长期记忆服务
     * @param agentId       记忆归属的智能体主键
     */
    public ForgetUserPreferenceTool(IAiMemoryService memoryService, Long agentId) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前提问用户，已拒绝撤销长期记忆");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = DeclaredMemoryGuard.normalize(AiToolSupport.stringValue(input.get("keyword")));
        String evidence = AiToolSupport.stringValue(input.get("evidence")).trim();
        if (keyword.length() < MIN_KEYWORD_CHARS) {
            return AiToolSupport.fail(param, TOOL_NAME,
                "keyword 至少 " + MIN_KEYWORD_CHARS + " 个字，请给出要撤销内容中的关键词");
        }
        String invalid = DeclaredMemoryGuard.validate(param, evidence, true);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            List<AiMemoryDeclaredVo> active = memoryService.listDeclared(agentId, userId).stream()
                .filter(item -> !Boolean.TRUE.equals(item.getRevoked()))
                .toList();
            List<AiMemoryDeclaredVo> matched = active.stream()
                .filter(item -> DeclaredMemoryGuard.normalize(item.getFact()).contains(keyword))
                .toList();
            if (matched.isEmpty()) {
                return AiToolSupport.fail(param, TOOL_NAME,
                    "没有与该关键词匹配的显式记忆；当前生效的条目："
                        + renderFacts(active.stream().map(AiMemoryDeclaredVo::getFact).toList()));
            }
            List<String> revoked = new ArrayList<>();
            for (AiMemoryDeclaredVo item : matched) {
                if (memoryService.revokeDeclared(agentId, userId, item.getId())) {
                    revoked.add(item.getFact());
                }
            }
            if (revoked.isEmpty()) {
                return AiToolSupport.fail(param, TOOL_NAME, "撤销未成功，请稍后重试或到记忆页手动撤销");
            }
            return AiToolSupport.ok(param, TOOL_NAME, "已撤销 " + revoked.size() + " 条长期记忆：" + renderFacts(revoked));
        } catch (Exception e) {
            log.warn("撤销显式记忆失败：userId={}, agentId={}, err={}", userId, agentId, e.getMessage());
            return AiToolSupport.fail(param, TOOL_NAME, "撤销长期记忆失败：" + e.getMessage());
        }
    }

    /**
     * 把条目渲染为一行文本（无条目时给出提示）。
     *
     * @param facts 事实正文列表
     * @return 展示文本
     */
    private static String renderFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return "（无）";
        }
        return String.join("；", facts);
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of(
            "type", "string",
            "description", "要撤销内容中的关键词（如“表格”“邮件格式”），至少 2 个字"));
        properties.put("evidence", Map.of(
            "type", "string",
            "description", "用户本轮消息中表达撤销意愿的那句原话，必须原样摘录"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("keyword", "evidence"));
        return schema;
    }
}
