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
 * <p><b>保留前缀 {@code __}</b>：AgentScope 的工具调用累积器把以 {@code __} 开头的工具名当作**流式分片占位符**
 * （{@code __fragment__} / {@code __pending__}，见 {@code ToolCallsAccumulator#isPlaceholder}）——真名被忽略、
 * 参数分片各自成为独立调用、聚合出无名工具调用，最终 {@code getTool(null)} 抛 {@code NullPointerException}
 * 整轮失败。故纯中文名替换成的一串下划线（如 {@code ____7__________}）必须改走 ⑤ 别名。</p>
 *
 * <p>⑤ 兜底别名：替换结果以 {@code __} 开头时，改用 {@code mcp_<原名哈希>}（字母开头、确定性、跨装配稳定），
 * 语义仍由工具描述承载。</p>
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
     * 框架保留前缀：AgentScope 视 {@code __} 开头为流式分片占位符，不可用作工具名
     */
    private static final String RESERVED_PREFIX = "__";

    /**
     * 兜底别名的前缀（以字母开头，避开保留前缀）
     */
    private static final String ALIAS_PREFIX = "mcp_";

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
        // 纯中文名替换后是一串下划线（如 ____7__________），会撞上框架的保留前缀 __：
        // 该前缀被累积器当作流式分片占位符，导致一次调用被拆成多个无名分片、最终 NPE。
        // 故改走字母开头的确定性别名，规避保留前缀。
        if (base.startsWith(RESERVED_PREFIX)) {
            base = ALIAS_PREFIX + hashSuffix(rawName);
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