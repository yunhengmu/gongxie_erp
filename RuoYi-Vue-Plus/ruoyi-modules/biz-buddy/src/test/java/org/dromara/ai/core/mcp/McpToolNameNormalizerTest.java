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
    @DisplayName("非法字符（点号 / 空格）替换为下划线")
    void shouldReplaceIllegalChars() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        assertEquals("weather_search_local", normalizer.allocate("weather.search_local"));
        assertEquals("a_b", normalizer.allocate("a b"));
    }

    @Test
    @DisplayName("纯中文名改走字母开头的别名，规避框架保留前缀 __")
    void shouldAliasPureCjkNames() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();

        // 「工具」直接替换会得到 "__"，正是框架的分片占位符前缀，必须改走别名
        assertEquals("mcp_" + sha256Prefix("工具"), normalizer.allocate("工具"));
        // 真实外部 Server 的中文长名：替换后是一串下划线（如 ____7__________）
        String fortyEight = normalizer.allocate("查询未来7日内指定日期天气预报");
        assertEquals("mcp_" + sha256Prefix("查询未来7日内指定日期天气预报"), fortyEight);
        assertFalse(fortyEight.startsWith("__"));
    }

    @Test
    @DisplayName("任何归一化结果都不得以框架保留前缀 __ 开头")
    void shouldNeverStartWithReservedPrefix() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        String[] raws = {"工具", "查询未来7日内指定日期天气预报", "__fragment__", "__pending__", "未来24小时天气预报",
            "weather.search", "a b", "123", "-", "_x"};

        for (String raw : raws) {
            String name = normalizer.allocate(raw);
            assertNotNull(name, "不应被丢弃：" + raw);
            assertFalse(name.startsWith("__"), "命中保留前缀：" + raw + " → " + name);
        }

        // 单个下划线开头是合法的（框架仅把双下划线视为占位符），保持原样
        assertEquals("_x", new McpToolNameNormalizer().allocate("_x"));
    }

    @Test
    @DisplayName("别名与普通名撞名时仍唯一")
    void shouldKeepAliasUnique() {
        McpToolNameNormalizer normalizer = new McpToolNameNormalizer();
        String alias = normalizer.allocate("工具");
        String again = normalizer.allocate("工具");

        assertNotEquals(alias, again);
        assertEquals(alias + "_" + sha256Prefix("工具"), again);
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