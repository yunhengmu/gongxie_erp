package org.dromara.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiSecretManager} 单元测试：密文往返与空值短路。
 */
@DisplayName("模型密钥密文管理器")
class AiSecretManagerTest {

    private final AiSecretManager secretManager = new AiSecretManager(new AiProperties());

    @Test
    @DisplayName("空明文不加密（未配置密钥，如本地 Ollama）")
    void shouldReturnNullForBlankPlainText() {
        assertNull(secretManager.encrypt(null));
        assertNull(secretManager.encrypt(""));
        assertNull(secretManager.encrypt("   "));
    }

    @Test
    @DisplayName("空密文不解密")
    void shouldReturnNullForBlankCipherText() {
        assertNull(secretManager.decrypt(null));
        assertNull(secretManager.decrypt(""));
        assertNull(secretManager.decrypt("   "));
    }

    @Test
    @DisplayName("加密后可原样解密")
    void shouldRoundTrip() {
        String cipher = secretManager.encrypt("sk-abcdef-123456");
        assertNotNull(cipher);
        assertNotEquals("sk-abcdef-123456", cipher);
        assertEquals("sk-abcdef-123456", secretManager.decrypt(cipher));
    }

}