package org.dromara.ai.core.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiChannelStreamStatus} 单元测试：长连接状态登记、失败原因截断与清除。
 */
@DisplayName("渠道长连接状态登记")
class AiChannelStreamStatusTest {

    private final AiChannelStreamStatus status = new AiChannelStreamStatus();

    @Test
    @DisplayName("未登记过的渠道返回 null")
    void shouldReturnNullForUnknownChannel() {
        assertNull(status.get("unknown"));
    }

    @Test
    @DisplayName("登记运行中 / 已停止")
    void shouldMarkRunningAndStopped() {
        status.markRunning("ding-1");
        assertEquals(AiChannelStreamStatus.RUNNING, status.get("ding-1").status());
        assertNull(status.get("ding-1").detail());
        assertNotNull(status.get("ding-1").updateTime());

        status.markStopped("ding-1");
        assertEquals(AiChannelStreamStatus.STOPPED, status.get("ding-1").status());
        assertNull(status.get("ding-1").detail());
    }

    @Test
    @DisplayName("登记失败并保留原因")
    void shouldMarkFailedWithDetail() {
        status.markFailed("ding-2", "连接超时");
        assertEquals(AiChannelStreamStatus.FAILED, status.get("ding-2").status());
        assertEquals("连接超时", status.get("ding-2").detail());
    }

    @Test
    @DisplayName("失败原因超长截断到 200 字")
    void shouldTruncateLongDetail() {
        String longDetail = "x".repeat(500);
        status.markFailed("ding-3", longDetail);
        assertEquals(200, status.get("ding-3").detail().length());
    }

    @Test
    @DisplayName("失败原因为 null 时保留 null")
    void shouldKeepNullDetail() {
        status.markFailed("ding-4", null);
        assertEquals(AiChannelStreamStatus.FAILED, status.get("ding-4").status());
        assertNull(status.get("ding-4").detail());
    }

    @Test
    @DisplayName("清除状态")
    void shouldRemoveStatus() {
        status.markRunning("ding-5");
        status.remove("ding-5");
        assertNull(status.get("ding-5"));
    }

}