package org.dromara.ai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiSseSupport} 错误归一化单元测试。
 *
 * <p>覆盖上游模型 / 传输层错误的「面向用户」映射与兜底截断。</p>
 *
 * @author BizBuddy
 */
@DisplayName("AiSseSupport 错误归一化")
class AiSseSupportTest {

    @Test
    @DisplayName("额度用尽（AllocationQuota.FreeTierOnly）映射为额度提示（真实报文，含嵌套异常）")
    void shouldMapQuotaExhausted() {
        String raw = "HTTP transport error during streaming: HTTP request failed with status 403 | "
            + "{\"error\":{\"message\":\"Free quota exhausted. To continue accessing the model on a paid basis, "
            + "please add funds or disable the \\\"use free tier only\\\" mode in the management console.\","
            + "\"type\":\"AllocationQuota.FreeTierOnly\",\"code\":\"AllocationQuota.FreeTierOnly\"}}";
        RuntimeException error = new RuntimeException("wrapper", new RuntimeException(raw));
        String message = AiSseSupport.rootCauseMessage(error);
        assertTrue(message.contains("额度"), message);
    }

    @Test
    @DisplayName("密钥无效映射为密钥提示")
    void shouldMapInvalidKey() {
        String message = AiSseSupport.rootCauseMessage(new RuntimeException("401 Unauthorized: InvalidApiKey"));
        assertTrue(message.contains("密钥"), message);
    }

    @Test
    @DisplayName("限流映射为限流提示")
    void shouldMapThrottling() {
        String message = AiSseSupport.rootCauseMessage(
            new RuntimeException("Throttling: Requests rate limit exceeded, please try again later"));
        assertTrue(message.contains("频繁"), message);
    }

    @Test
    @DisplayName("模型侧内容审核未通过映射为内容提示")
    void shouldMapDataInspectionFailed() {
        String message = AiSseSupport.rootCauseMessage(new RuntimeException("code=DataInspectionFailed"));
        assertTrue(message.contains("审核"), message);
    }

    @Test
    @DisplayName("传输异常映射为网络提示（非额度 / 鉴权类）")
    void shouldMapTransportError() {
        String message = AiSseSupport.rootCauseMessage(
            new RuntimeException("HTTP transport error: connection reset"));
        assertTrue(message.contains("网络"), message);
    }

    @Test
    @DisplayName("无法识别的错误保留原文（未超长不截断）")
    void shouldKeepUnknownMessage() {
        assertEquals("会话已结束，无法继续提问",
            AiSseSupport.rootCauseMessage(new RuntimeException("会话已结束，无法继续提问")));
    }

    @Test
    @DisplayName("无法识别的超长错误按长度截断")
    void shouldTruncateLongUnknownMessage() {
        String raw = "x".repeat(500);
        String message = AiSseSupport.rootCauseMessage(new RuntimeException(raw));
        assertTrue(message.length() < raw.length(), "应被截断");
        assertTrue(message.endsWith("（已截断）"), message);
    }
}