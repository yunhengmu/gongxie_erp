package org.dromara.ai.core.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McpToolNameNormalizer} 单元测试。
 *
 * <p>覆盖：空名丢弃、合法名原样保留、非法字符替换、跨 Server 冲突改名（哈希后缀 + 递增序号）。</p>
 */
@DisplayName("MCP 工具名归一化")
class McpToolNameNormalizerTest {

    @Test
    @DisplayName("空名或 null 直接丢弃")
    void shouldRejectBlankNames() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        assertNull(normalizer.allocate(null));
        assertNull(normalizer.allocate(""));
    }

    @Test
    @DisplayName("已合法的名字原样保留（不改既有工具名）")
    void shouldKeepLegalNamesUnchanged() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        assertEquals("weather", normalizer.allocate("weather"));
        assertEquals("search_local_2", normalizer.allocate("search_local_2"));
        assertEquals("123", normalizer.allocate("123"));
        assertEquals("-", normalizer.allocate("-"));
    }

    @Test
    @DisplayName("非法字符（点号 / 空格 / 中文）替换为下划线")
    void shouldReplaceIllegalChars() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        assertEquals("weather_search_local", normalizer.allocate("weather.search_local"));
        assertEquals("a_b", normalizer.allocate("a b"));
        assertEquals("__", normalizer.allocate("工具"));
    }

    @Test
    @DisplayName("同名冲突时追加原名哈希后缀，仍然唯一")
    void shouldAppendHashSuffixOnConflict() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        String first = normalizer.allocate("a.b");
        String second = normalizer.allocate("a.b");
        assertEquals("a_b", first);
        String hash = sha256Prefix("a.b");
        assertEquals("a_b_" + hash, second);
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("不同原名的替换结果撞名时也各自唯一")
    void shouldDisambiguateDifferentRawNames() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        String first = normalizer.allocate("a.b");
        String second = normalizer.allocate("a b");
        assertEquals("a_b", first);
        assertEquals("a_b_" + sha256Prefix("a b"), second);
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("极端重复时再追加递增序号保证唯一")
    void shouldAppendSequenceWhenHashAlsoCollides() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        String first = normalizer.allocate("a.b");
        String second = normalizer.allocate("a.b");
        String third = normalizer.allocate("a.b");
        assertEquals("a_b", first);
        assertEquals("a_b_" + sha256Prefix("a.b"), second);
        assertEquals("a_b_" + sha256Prefix("a.b") + "_2", third);
    }

    /**
     * 复算实现约定的哈希短后缀（SHA-256 前 3 字节的十六进制）。
     */
    private static String sha256Prefix(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}