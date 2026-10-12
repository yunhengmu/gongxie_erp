package org.dromara.ai.core.route;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.route.RouteJudgeRequest.Candidate;
import org.dromara.common.core.utils.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TypeSafe System One（百炼决策模型）协议支持：报文构造、地址拼装、宽松解析的**唯一实现处**。
 *
 * <p>被两处复用：</p>
 * <ul>
 *   <li>{@link JevRouteJudge}——运行期的路由判定外呼；</li>
 *   <li>{@code AiModelConfigServiceImpl#testConnection}——「模型配置」页对 {@code decision} 类型模型的连通性测试
 *       （复用 {@link #probeCandidates()} 与 {@link #parseLeniently} 构造最小判定请求并判定可用性）。</li>
 * </ul>
 *
 * <p><b>协议 DTO 复用（序列化 / 反序列化）+ 自建宽松校验</b>：只借用 jev 模块的
 * {@link SystemOneRequest} / {@link SystemOneResult} 等 DTO 承载报文结构（自带 {@code type} 多态标记），
 * 出网与结果校验由调用方自建，<b>不调用框架的 {@code JevClient.systemOneBlocking}</b>——框架对 choice
 * 概率和的校验为 {@code |sum - 1| > 1e-6 × 选项数}，容差极严；而百炼返回两位小数概率，和可能是
 * 0.99 / 1.01，会被框架判为不合法而抛异常，且该异常不带响应体、调用方无法自行兜底解析。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public final class JevSystemOneSupport {

    /**
     * 判断题在 {@code questions} 中的键（答案从 {@code result.answers()} 按同键取回）
     */
    public static final String QUESTION_KEY = "expert";

    /**
     * 判断题题干（决策模型不做长文理解，题干保持简短）
     */
    private static final String CHOICE_INSTRUCTIONS = "该问题应由哪个智能体处理？";

    /**
     * {@code self} 判据：入口智能体可直接回答
     */
    private static final String SELF_CRITERION = "入口智能体可直接回答";

    /**
     * {@code other} 判据：固定兜底项（语义不是错误，而是「无法判断归属」的出口）
     */
    private static final String OTHER_CRITERION = "无法判断归属，或候选专家都不合适";

    /**
     * 概率和容差上限：百炼返回两位小数概率，和可能落在 0.99 / 1.01 附近，
     * 故取 0.05 作为可接受偏差（约 5 倍余量）；超出即视为分布失真而拒绝。
     */
    static final double SUM_TOLERANCE = 0.05;

    /**
     * 概率和静默容差：偏差不超过 1e-6 视为精确为 1，不告警；
     * 落在 (1e-6, 0.05] 之间仅打一条 warn（两位小数舍入），仍在可接受范围。
     */
    static final double SUM_TOLERANCE_QUIET = 1e-6;

    /**
     * 报文序列化 / 反序列化的专用映射器，配置与 jev 模块内部保持一致：
     * 下划线命名策略 + 容忍未知属性（百炼响应顶层多出 {@code request_id} / {@code latency_ms}）。
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JevSystemOneSupport() {
    }

    /**
     * 拼装 System One 服务地址，与 jev 模块 {@code systemOneUrl} 语义一致。
     *
     * @param rawBaseUrl 配置项里的基址（可空）
     * @return 归一化后的服务地址
     */
    public static String endpoint(String rawBaseUrl) {
        String baseUrl = StringUtils.isBlank(rawBaseUrl) ? "" : rawBaseUrl.trim();
        // 归一化末尾斜杠，避免出现 "//" 或多余分隔
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/v1/systemone")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/systemone";
        }
        return baseUrl + "/v1/systemone";
    }

    /**
     * 构造 {@code choice} 的闭集判据：{@code self} + 各候选（键为 {@code agent_code}，值为「擅长 / 描述」）
     * + 固定 {@code other}。
     *
     * @param candidates 候选清单
     * @return 判据
     */
    public static Map<String, Object> buildCriteria(List<Candidate> candidates) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("self", SELF_CRITERION);
        for (Candidate candidate : candidates) {
            if (StringUtils.isBlank(candidate.agentCode())) {
                continue;
            }
            criteria.put(candidate.agentCode(),
                StringUtils.isBlank(candidate.description()) ? candidate.name() : candidate.description());
        }
        criteria.put("other", OTHER_CRITERION);
        return criteria;
    }

    /**
     * 构造请求体 JSON（复用 jev 的 DTO，自带 {@code type: choice} 多态标记，序列化不会丢协议字段）。
     *
     * @param modelCode 模型标识（来自「模型配置」的 {@code model_code}）
     * @param state     判定输入（用户原话 + 候选清单口径）
     * @param criteria  闭集判据（见 {@link #buildCriteria(List)}）
     * @return 请求体 JSON
     * @throws Exception 序列化失败
     */
    public static String buildRequestJson(String modelCode, String state, Map<String, Object> criteria)
        throws Exception {
        SystemOneRequest body = new SystemOneRequest(state, modelCode,
            Map.of(QUESTION_KEY, new ChoiceQuestion(CHOICE_INSTRUCTIONS, criteria)));
        return MAPPER.writeValueAsString(body);
    }

    /**
     * 连通性探测用的固定候选（与运行期判定无关，仅为构造一次合法的最小判定请求）。
     *
     * @return 两个固定候选
     */
    public static List<Candidate> probeCandidates() {
        return List.of(
            new Candidate("probe_expert_a", "探测候选A", "用于连通性探测的固定候选"),
            new Candidate("probe_expert_b", "探测候选B", "用于连通性探测的固定候选"));
    }

    /**
     * 解析响应体并**宽松地**归一化为判定结果。
     *
     * @param body         响应体 JSON
     * @param criteriaKeys 闭集判据的键集合
     * @param latencyMs    本次耗时（毫秒）
     * @return 判定结果（结构不符 / 越界 / 分布失真时带 {@code error}）
     * @throws Exception 报文无法反序列化
     */
    public static RouteJudgeVerdict parseLeniently(String body, Set<String> criteriaKeys, long latencyMs)
        throws Exception {
        SystemOneResult result = MAPPER.readValue(body, SystemOneResult.class);
        return toVerdictLeniently(result, criteriaKeys, latencyMs);
    }

    /**
     * 把决策模型返回<b>宽松地</b>归一化为判定结果（静态纯函数，便于单测）。
     *
     * <p>宽松点：容忍概率键缺失（视为 0）与 {@code confidence} 缺失；
     * 概率和偏差在 (1e-6, 0.05] 内接受并告警（提供方两位小数舍入）。</p>
     *
     * @param result       决策模型返回
     * @param criteriaKeys 闭集判据的键集合（{@code self} + 各候选 {@code agent_code} + {@code other}）
     * @param latencyMs    本次耗时（毫秒）
     * @return 判定结果（结构不符 / 越界 / 分布失真时带 {@code error}）
     */
    static RouteJudgeVerdict toVerdictLeniently(SystemOneResult result, Set<String> criteriaKeys, long latencyMs) {
        // 返回为空＝无判定可用
        if (result == null) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, 0L, "决策模型返回为空");
        }
        long inputTokens = result.usage() == null ? 0L : result.usage().inputTokens();
        // 答案缺失、或无 expert、或类别不是 choice，一律视为未返回 choice 答案
        Answer answer = result.answers() == null ? null : result.answers().get(QUESTION_KEY);
        if (!(answer instanceof ChoiceAnswer choice)) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "决策模型未返回 choice 答案");
        }
        // choice 必须落在闭集内，越界说明模型选了不存在项，不可信
        String selected = choice.choice();
        if (selected == null || !criteriaKeys.contains(selected)) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "choice 越界: " + selected);
        }
        Map<String, Double> probabilities = choice.probabilities();
        // 概率分布整体缺失（无局部信息可兜底）→ 拒绝
        if (probabilities == null || probabilities.isEmpty()) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "概率分布缺失");
        }
        double sum = 0D;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            // 出现闭集外的未知键，说明模型自造选项，分布不可信
            if (!criteriaKeys.contains(entry.getKey())) {
                return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens,
                    "概率分布含未知选项: " + entry.getKey());
            }
            Double value = entry.getValue();
            // NaN / Infinity 会污染求和，直接拒绝（注意 NaN 的比较恒为 false，须用 isFinite 判定）
            if (value == null || !Double.isFinite(value)) {
                return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "概率分布含非法值");
            }
            sum += value;
        }
        // 概率键缺失某些 criteria 选项属「宽松容忍」（缺省视为 0），故此处不再校验键的完备性
        double deviation = Math.abs(sum - 1D);
        if (deviation > SUM_TOLERANCE) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "概率和偏离 1 过大: sum=" + sum);
        }
        if (deviation > SUM_TOLERANCE_QUIET) {
            // 两位小数舍入导致的和偏差：接受但留痕（不打印 state，避免泄漏外呼文本）
            log.warn("决策模型概率和 {} 偏离 1 超过静默阈值（选项数 {}），按提供方两位小数舍入容忍接受",
                sum, probabilities.size());
        }
        Double confidence = choice.confidence();
        // confidence 允许缺失；非空时须为 [0,1] 内有限数（NaN 比较恒 false，须用 isFinite 判定）
        if (confidence != null
            && (!Double.isFinite(confidence) || confidence < 0D || confidence > 1D)) {
            return new RouteJudgeVerdict(null, null, null, latencyMs, inputTokens, "置信度非法: " + confidence);
        }
        return new RouteJudgeVerdict(selected, confidence, probabilities, latencyMs, inputTokens, null);
    }

}