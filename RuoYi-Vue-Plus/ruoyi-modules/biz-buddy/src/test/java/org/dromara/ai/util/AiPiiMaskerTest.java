package org.dromara.ai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiPiiMasker} 单元测试：各类型 PII 的就地掩码。
 */
@DisplayName("自由文本 PII 脱敏")
class AiPiiMaskerTest {

    @Test
    @DisplayName("手机号 / 身份证 / 邮箱 / 银行卡 / IP 均被掩码")
    void shouldMaskAllKinds() {
        String text = "联系 13812345678，身份证 110101199003074578，邮箱 abc@example.com，"
            + "卡号 6222021234567890123，IP 192.168.1.100";
        AiPiiMasker.Outcome outcome = AiPiiMasker.mask(text);

        assertTrue(outcome.text().contains("138****5678"));
        assertFalse(outcome.text().contains("13812345678"));
        assertFalse(outcome.text().contains("110101199003074578"));
        assertTrue(outcome.text().contains("a***@example.com"));
        assertFalse(outcome.text().contains("abc@example.com"));
        assertFalse(outcome.text().contains("6222021234567890123"));
        assertTrue(outcome.text().contains("192.168.1.x"));
        assertTrue(outcome.types().contains(AiPiiMasker.TYPE_MOBILE));
        assertTrue(outcome.count() >= 5);
    }

    @Test
    @DisplayName("普通文本不变、计数为 0")
    void shouldKeepPlainText() {
        AiPiiMasker.Outcome outcome = AiPiiMasker.mask("今天天气不错，帮我写一段介绍");
        assertEquals("今天天气不错，帮我写一段介绍", outcome.text());
        assertEquals(0, outcome.count());
    }

    @Test
    @DisplayName("空文本安全返回")
    void shouldHandleBlank() {
        assertEquals(0, AiPiiMasker.mask(null).count());
        assertEquals("", AiPiiMasker.maskText(""));
    }

    @Test
    @DisplayName("相邻数字不被误判为手机号/身份证")
    void shouldRespectDigitBoundaries() {
        // 12 位数字（非手机号，非身份证）不应被脱敏
        assertFalse(AiPiiMasker.maskText("订单号 138123456789").contains("****"));
    }

}