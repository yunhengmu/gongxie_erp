package org.dromara.ai.core.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部 MCP 工具名归一化（REQ-AI-16）：把远端自声明的工具名收敛为 LLM function name 合法形式。
 *
 * <p>MCP 工具名由外部 Server 自行声明，可能是 {@code weather.search_local} 这类含点号的名字，
 * 也可能是纯数字 ID。LLM 的 function name 只接受 {@code ^[a-zA-Z0-9_-]+$}（DeepSeek 等严格校验），
 * 非法名会导致**整轮请求 400**、对话直接失败，故在注册进工具箱之前把名字归一化；
 * 调用时再由 {@link NormalizingMcpClientWrapper} 还原为远端原始名。</p>
 *
 * <p>归一化规则：① 已合法的名字**原样保留**（对正常 Server 零影响，不改变既有工具名）；
 * ② 非法字符（点号、空格、中文等）替换为下划线；③ 与已分配名冲突时追加原名哈希短后缀，保证确定性；
 * ④ 替换后仍非法（空名等）时返回 {@code null}，由调用方丢弃。</p>
 *
 * <p>本类的「已分配名」作用域是**单次装配的全部 Server**：工具箱是全局命名空间，跨 Server 也需唯一。
 * 但不校验与业务工具编码（{@code ai_tool.tool_code}）是否重名——后者在归一化引入之前就存在同名风险，
 * 不因本次改动而变化。</p>
 *
 * @author BizBuddy
 */
public final class McpToolNameNormalizer {

    /**
     * LLM function name 的合法形式（OpenAI 兼容协议要求 {@code ^[a-zA-Z0-9_-]+$}）
     */
    private static final Pattern LEGAL_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

    /**
     * 非法字符（点号、空格、非 ASCII 等一律替换为下划线）
     */
    private static final Pattern ILLEGAL_CHAR = Pattern.compile("[^a-zA-Z0-9_-]");

    /**
     * 名字冲突时追加的哈希后缀长度（原名 SHA-256 前 3 字节的十六进制）
     */
    private static final int HASH_BYTES = 3;

    /**
     * 已分配的工具名（跨 Server 共享）
     */
    private final Set<String> allocated = new LinkedHashSet<>();

    /**
     * 归一化并分配一个本次装配内唯一的工具名。
     *
     * @param rawName 远端自声明的工具名
     * @return 归一化后的工具名；无法归一化时返回 null
     */
    public String allocate(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }
        String base = ILLEGAL_CHAR.matcher(rawName).replaceAll("_");
        if (!LEGAL_NAME.matcher(base).matches()) {
            return null;
        }
        if (allocated.add(base)) {
            return base;
        }
        // 冲突（不同 Server 暴露同名工具，或替换后撞名）：追加原名哈希后缀；
        // 极端情况下（同一原名出现多次）再追加递增序号，保证仍唯一
        String suffixed = base + "_" + hashSuffix(rawName);
        String candidate = suffixed;
        int seq = 2;
        while (!allocated.add(candidate)) {
            candidate = suffixed + "_" + seq++;
        }
        return candidate;
    }

    /**
     * 取原名的哈希短后缀（SHA-256 前 3 字节的十六进制，共 6 位）。
     *
     * @param rawName 远端原始工具名
     * @return 固定 6 位小写十六进制
     */
    private static String hashSuffix(String rawName) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(rawName.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论不可达；退化为固定后缀，仍保证唯一性
            return "dupsuf";
        }
        StringBuilder hex = new StringBuilder(HASH_BYTES * 2);
        for (int i = 0; i < HASH_BYTES; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }

}