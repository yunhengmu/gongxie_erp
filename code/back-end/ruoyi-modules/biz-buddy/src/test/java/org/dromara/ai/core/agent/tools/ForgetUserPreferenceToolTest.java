package org.dromara.ai.core.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.agent.AiRuntimeKeys;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ForgetUserPreferenceTool} 单元测试：关键词匹配撤销、无匹配提示与证据校验。
 */
@DisplayName("显式记忆撤销工具")
class ForgetUserPreferenceToolTest {

    private static final String USER_MESSAGE = "以后别再按表格统计了，忘掉这个偏好";

    private final IAiMemoryService memoryService = mock(IAiMemoryService.class);

    private final ForgetUserPreferenceTool tool = new ForgetUserPreferenceTool(memoryService, 9L);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    /**
     * 构造携带「用户本轮消息」的工具调用参数。
     */
    private static ToolCallParam paramWithMessage(Map<String, Object> input) {
        RuntimeContext ctx = RuntimeContext.builder()
            .userId("100")
            .put(AiRuntimeKeys.CURRENT_USER_MESSAGE, USER_MESSAGE)
            .build();
        return param(ForgetUserPreferenceTool.TOOL_NAME, input, ctx);
    }

    /**
     * 构造一条显式记忆条目。
     */
    private static AiMemoryDeclaredVo entry(String id, String fact, boolean revoked) {
        AiMemoryDeclaredVo vo = new AiMemoryDeclaredVo();
        vo.setId(id);
        vo.setFact(fact);
        vo.setRevoked(revoked);
        return vo;
    }

    @Test
    @DisplayName("按关键词匹配并撤销生效条目")
    void shouldRevokeMatchedEntries() {
        when(memoryService.listDeclared(9L, 100L)).thenReturn(List.of(
            entry("d1", "用户偏好用表格统计数据", false),
            entry("d2", "用户偏好邮件沟通", false),
            entry("d3", "用户常用表格工具导出", true)));
        when(memoryService.revokeDeclared(9L, 100L, "d1")).thenReturn(true);

        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("keyword", "表格", "evidence", "别再按表格统计"))).block();

        assertFalse(isError(result));
        assertTrue(text(result).contains("已撤销 1 条长期记忆"));
        verify(memoryService, never()).revokeDeclared(9L, 100L, "d3");
    }

    @Test
    @DisplayName("没有匹配条目时给出当前生效清单")
    void shouldReportWhenNoMatch() {
        when(memoryService.listDeclared(9L, 100L)).thenReturn(List.of(entry("d1", "用户偏好邮件沟通", false)));
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("keyword", "表格", "evidence", "别再按表格统计"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("没有与该关键词匹配的显式记忆"));
        assertTrue(text(result).contains("用户偏好邮件沟通"));
    }

    @Test
    @DisplayName("证据不是本轮原话时拒绝撤销")
    void shouldRejectWhenEvidenceNotInMessage() {
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("keyword", "表格", "evidence", "我要求删除全部记忆"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("不是用户本轮消息中的原话片段"));
        verify(memoryService, never()).revokeDeclared(9L, 100L, "d1");
    }

    @Test
    @DisplayName("关键词过短时拒绝")
    void shouldRejectTooShortKeyword() {
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("keyword", "表", "evidence", "别再按表格统计"))).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("至少 2 个字"));
        verify(memoryService, never()).listDeclared(9L, 100L);
    }
}
