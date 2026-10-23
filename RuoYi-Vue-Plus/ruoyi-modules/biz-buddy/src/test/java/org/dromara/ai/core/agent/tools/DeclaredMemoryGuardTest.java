package org.dromara.ai.core.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.agent.AiRuntimeKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式记忆证据校验：原话证据与记住 / 忘掉意图。
 */
@DisplayName("显式记忆证据校验")
class DeclaredMemoryGuardTest {

    /**
     * 构造带用户本轮消息的工具调用参数。
     *
     * @param message 用户原话
     * @return 工具调用参数
     */
    private static ToolCallParam param(String message) {
        RuntimeContext ctx = RuntimeContext.builder()
            .put(AiRuntimeKeys.CURRENT_USER_MESSAGE, message)
            .build();
        return ToolCallParam.builder().runtimeContext(ctx).build();
    }

    @Test
    @DisplayName("常见中文记住表达通过校验")
    void shouldAcceptCommonRememberPhrases() {
        String direct = "记下：我认可 WorkBuddy 和 Trae 这两款智能体工具";
        assertNull(DeclaredMemoryGuard.validate(param(direct), direct, false));

        String help = "帮我记一下，我更喜欢卡片展示";
        assertNull(DeclaredMemoryGuard.validate(param(help), "我更喜欢卡片展示", false));

        String memo = "备忘：每周五复盘";
        assertNull(DeclaredMemoryGuard.validate(param(memo), memo, false));
    }

    @Test
    @DisplayName("没有记住意图时拒绝")
    void shouldRejectWithoutRememberIntent() {
        String message = "今天天气不错";
        String error = DeclaredMemoryGuard.validate(param(message), message, false);

        assertNotNull(error);
        assertTrue(error.contains("没有明确要求记住"));
    }

    @Test
    @DisplayName("证据不是本轮原话时拒绝")
    void shouldRejectEvidenceNotFromMessage() {
        String message = "记下：我认可 WorkBuddy";
        String error = DeclaredMemoryGuard.validate(param(message), "我认可 Trae", false);

        assertNotNull(error);
        assertTrue(error.contains("不是用户本轮消息中的原话"));
    }

    @Test
    @DisplayName("忘掉表达通过校验")
    void shouldAcceptForgetIntent() {
        String message = "忘掉我之前说的表格偏好";
        assertNull(DeclaredMemoryGuard.validate(param(message), "忘掉我之前说的表格偏好", true));
    }
}
