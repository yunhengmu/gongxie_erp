package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 专家写工具「短期去重」登记表（REQ-AI-30 安全护栏）。
 *
 * <p><b>解决什么</b>：契约校验不合格会触发原地重试、HITL 确认后还有恢复段续跑。若模型在这些
 * 后续轮次里再次调用同一个写工具，就会产生**重复副作用**（实测风险：重复发布系统公告）。
 * 本类以「专家会话 × 工具名 × 入参指纹」为键做短期标记，配合 {@link DedupWriteTool} 使
 * 同一写操作在一次编排内只会真正执行一次，后续重复调用直接回填「已跳过」。</p>
 *
 * <p><b>作用域为何是专家会话</b>：{@code scope} 取运行期 {@code sessionId}——
 * 专家链路的它等于 {@code routeRunId#expertAgentId}（一次编排一个，含重试与恢复段），
 * 天然是「任务级」；而入口 / 普通智能体是长期会话，在那里去重会误伤正常诉求，
 * 故 {@link AgentRegistry} 只对专家装配启用包装（见 {@code expertMode}）。</p>
 *
 * <p><b>只登记成功</b>：仅在工具返回**非 ERROR** 结果后登记，失败（业务校验不过、权限拒绝）
 * 不登记，允许模型修正入参后重试。</p>
 *
 * <p><b>失败即放行</b>：Redis 异常一律按「未执行过」处理并告警——去重是保护措施，
 * 不能因为存储故障阻断正常的工具执行。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiWriteDedupStore {

    /**
     * Redis 键前缀
     */
    private static final String KEY_PREFIX = "bizbuddy:ai:write-dedup:";

    /**
     * TTL 上限（秒）：防止配置误填成超大值把「短期」变成永久
     */
    private static final long MAX_TTL_SECONDS = 86_400L;

    private final AiProperties aiProperties;

    /**
     * 去重开关是否开启（配置 {@code bizbuddy.ai.chat.expert-write-dedup-enabled}）。
     *
     * @return 是否开启
     */
    public boolean enabled() {
        return aiProperties.getChat().isExpertWriteDedupEnabled();
    }

    /**
     * 该写调用是否已在本次专家会话中执行成功过。
     *
     * @param scope    专家会话标识（运行期 sessionId）
     * @param toolName 工具名
     * @param input    工具入参
     * @return true 表示已执行过（调用方应跳过执行）
     */
    public boolean seen(String scope, String toolName, Map<String, Object> input) {
        if (!enabled() || StringUtils.isBlank(scope)) {
            return false;
        }
        try {
            return RedisUtils.getCacheObject(key(scope, toolName, input)) != null;
        } catch (Exception e) {
            log.warn("写工具去重查询失败（按未执行处理并放行）：scope={}, tool={}, err={}",
                scope, toolName, e.getMessage());
            return false;
        }
    }

    /**
     * 登记一次「已成功执行的写调用」。
     *
     * @param scope    专家会话标识（运行期 sessionId）
     * @param toolName 工具名
     * @param input    工具入参
     */
    public void remember(String scope, String toolName, Map<String, Object> input) {
        if (!enabled() || StringUtils.isBlank(scope)) {
            return;
        }
        try {
            RedisUtils.setCacheObject(key(scope, toolName, input), "1", ttl());
        } catch (Exception e) {
            log.warn("写工具去重登记失败（不影响本次执行）：scope={}, tool={}, err={}",
                scope, toolName, e.getMessage());
        }
    }

    /**
     * 去重有效期（秒），上限 24 小时。
     *
     * @return 有效期
     */
    private Duration ttl() {
        long configured = aiProperties.getChat().getExpertWriteDedupTtlSeconds();
        long seconds = configured <= 0 ? 1800L : Math.min(configured, MAX_TTL_SECONDS);
        return Duration.ofSeconds(seconds);
    }

    /**
     * 构造去重键：{@code 前缀 + 会话 + 工具名 + 入参指纹}。
     *
     * @param scope    专家会话标识
     * @param toolName 工具名
     * @param input    工具入参
     * @return Redis 键
     */
    private String key(String scope, String toolName, Map<String, Object> input) {
        return KEY_PREFIX + scope + ":" + toolName + ":" + fingerprint(input);
    }

    /**
     * 计算入参指纹：**按 key 排序后**再序列化，避免 Map 迭代顺序不同导致同一入参算出不同指纹。
     *
     * @param input 工具入参
     * @return 十六进制 SHA-256（算法不可用时退化为 hashCode，宁可少去重也不阻断执行）
     */
    private String fingerprint(Map<String, Object> input) {
        TreeMap<String, Object> sorted = new TreeMap<>(input == null ? Map.of() : input);
        String json;
        try {
            json = JsonUtils.toJsonString(sorted);
        } catch (Exception e) {
            json = String.valueOf(sorted);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(json.hashCode());
        }
    }

}
