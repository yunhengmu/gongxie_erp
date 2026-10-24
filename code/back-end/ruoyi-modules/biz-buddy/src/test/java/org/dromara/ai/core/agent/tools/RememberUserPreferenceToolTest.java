package org.dromara.ai.core.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.agent.AiRuntimeKeys;
import org.dromara.ai.domain.bo.AiMemoryDeclaredBo;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.ai.service.IAiMemoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.dromara.ai.core.agent.ToolTestSupport.isError;
import static org.dromara.ai.core.agent.ToolTestSupport.param;
import static org.dromara.ai.core.agent.ToolTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RememberUserPreferenceTool} 单元测试：身份缺失、证据与意图校验、成功写入与失败包装。
 */
@DisplayName("显式记忆写入工具")
class RememberUserPreferenceToolTest {

    private static final String USER_MESSAGE = "我喜欢按照表格统计数据，请记住";

    private final IAiMemoryService memoryService = mock(IAiMemoryService.class);

    private final RememberUserPreferenceTool tool = new RememberUserPreferenceTool(memoryService, 9L);

    @BeforeAll
    static void initContext() {
        AiTestSupport.initialize();
    }

    /**
     * 构造携带「用户本轮消息」的工具调用参数。
     */
    private static ToolCallParam paramWithMessage(Map<String, Object> input, String message) {
        RuntimeContext ctx = RuntimeContext.builder()
            .userId("100")
            .put(AiRuntimeKeys.CURRENT_USER_MESSAGE, message)
            .build();
        return param(RememberUserPreferenceTool.TOOL_NAME, input, ctx);
    }

    @Test
    @DisplayName("无法识别提问用户时拒绝写入")
    void shouldRejectWhenOperatorMissing() {
        ToolResultBlock result = tool.callAsync(param(RememberUserPreferenceTool.TOOL_NAME, Map.of())).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("无法识别当前提问用户"));
        verify(memoryService, never()).addDeclared(any());
    }

    @Test
    @DisplayName("证据不是本轮原话时拒绝写入")
    void shouldRejectWhenEvidenceNotInMessage() {
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("fact", "用户偏好表格", "evidence", "我从来没说过这句"), USER_MESSAGE)).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("不是用户本轮消息中的原话片段"));
        verify(memoryService, never()).addDeclared(any());
    }

    @Test
    @DisplayName("用户本轮没有明确记忆意图时拒绝写入")
    void shouldRejectWhenNoRememberIntent() {
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("fact", "用户偏好表格", "evidence", "按照表格统计数据"), "按照表格统计数据")).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("没有明确要求记住"));
        verify(memoryService, never()).addDeclared(any());
    }

    @Test
    @DisplayName("证据合法时写入并返回条目")
    void shouldWriteDeclaredMemory() {
        AiMemoryDeclaredVo entry = new AiMemoryDeclaredVo();
        entry.setId("d1");
        entry.setFact("用户偏好用表格统计数据");
        when(memoryService.addDeclared(any())).thenReturn(entry);

        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("fact", "用户偏好用表格统计数据", "evidence", "按照表格统计数据"), USER_MESSAGE)).block();

        assertFalse(isError(result));
        assertTrue(text(result).contains("已记住：用户偏好用表格统计数据"));
        ArgumentCaptor<AiMemoryDeclaredBo> captor = ArgumentCaptor.forClass(AiMemoryDeclaredBo.class);
        verify(memoryService).addDeclared(captor.capture());
        assertEquals(100L, captor.getValue().getUserId());
        assertEquals(9L, captor.getValue().getAgentId());
    }

    @Test
    @DisplayName("服务层异常时包装为写入失败")
    void shouldReturnFailureWhenServiceThrows() {
        when(memoryService.addDeclared(any())).thenThrow(new IllegalStateException("条数已达上限"));
        ToolResultBlock result = tool.callAsync(paramWithMessage(
            Map.of("fact", "用户偏好用表格统计数据", "evidence", "按照表格统计数据"), USER_MESSAGE)).block();
        assertTrue(isError(result));
        assertTrue(text(result).contains("写入长期记忆失败：条数已达上限"));
    }
}
