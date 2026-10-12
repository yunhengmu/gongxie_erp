package org.dromara.ai.core.route;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.route.RouteJudgeRequest.Candidate;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 路由判断端口的决策模型实现（百炼决策模型 / TypeSafe System One 协议）。
 *
 * <p><b>协议 DTO 复用（序列化 / 反序列化）+ 自建调用与宽松校验</b>：只借用 jev 模块的
 * {@link SystemOneRequest} / {@link SystemOneResult} 等 DTO 承载报文结构（自带 {@code type} 多态标记），
 * 出网与结果校验则由本类自建，<b>不再调用框架的 {@code JevClient.systemOneBlocking}</b>。</p>
 *
 * <p><b>为何不复用 {@code JevClient} 的调用路径</b>：框架对 choice 概率和的校验为
 * {@code |sum - 1| > 1e-6 × 选项数}，容差极严；而百炼返回两位小数概率，和可能是 0.99 / 1.01，
 * 会被框架判为不合法而抛 {@code JevException}。更关键的是该异常为单参构造、<b>不带响应体</b>，
 * 调用方无法「捕获后自行兜底解析」，导致判定间歇性不可用。故此处自建宽松校验。</p>
 *
 * <p><b>配置每次从 {@link AiRuntimeConfigStore#jev()} 读取</b>（不缓存客户端），保证运行期改配置即时生效。</p>
 *
 * <p><b>异常归一化</b>：超时 / 网络错误 / 非 2xx / 解析失败一律记日志并返回带 {@code error} 的结果，
 * 绝不向上抛异常（口径 P3：不新增失败面）。</p>
 *
 * <p><b>费用口径</b>：只取 {@code input_tokens}；{@code usage.outputTokens} 恒为 0（百炼只回输入），
 * 不参与任何计算。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevRouteJudge implements RouteJudge {

    /**
     * 判断题在 {@code questions} 中的键（答案从 {@code result.answers()} 按同键取回）
     */
    private static final String QUESTION_KEY = "expert";

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
     * 日志中响应体的截断长度（避免日志被整段响应体淹没）
     */
    private static final int ERROR_BODY_MAX_CHARS = 200;

    /**
     * 失败重试的间隔（毫秒）
     */
    private static final long RETRY_BACKOFF_MS = 300L;

    /**
     * 报文序列化 / 反序列化的专用映射器，配置与 jev 模块内部保持一致：
     * 下划线命名策略 + 容忍未知属性（百炼响应顶层多出 {@code request_id} / {@code latency_ms}）。
     *
     * <p>包级可见以便直接单测解析配置。</p>
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AiRuntimeConfigStore runtimeConfigStore;

    @Override
    public Optional<RouteJudgeVerdict> judge(RouteJudgeRequest request) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return Optional.empty();
        }
        long startNanos = System.nanoTime();
        AiJevConfigBo cfg = runtimeConfigStore.jev();
        Map<String, Object> criteria = buildCriteria(request.candidates());

        // 请求体构造：复用 jev 的 DTO（自带 type: choice 多态标记），故序列化不会丢协议字段
        String requestJson;
        try {
            SystemOneRequest body = new SystemOneRequest(request.state(), cfg.getModel(),
                Map.of(QUESTION_KEY, new ChoiceQuestion(CHOICE_INSTRUCTIONS, criteria)));
            requestJson = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return Optional.of(error(startNanos, "决策模型请求构造失败：" + rootMessage(e)));
        }

        HttpAttempt attempt = invokeWithRetry(systemOneUrl(cfg.getBaseUrl()), cfg, requestJson);
        long latencyMs = elapsedMs(startNanos);
        if (!attempt.ok()) {
            log.warn("结构化决策外呼失败（按不可用处理，不影响编排）：{}", attempt.error());
            return Optional.of(new RouteJudgeVerdict(null, null, null, latencyMs, 0L, attempt.error()));
        }
        try {
            SystemOneResult result = MAPPER.readValue(attempt.body(), SystemOneResult.class);
            return Optional.of(toVerdictLeniently(result, criteria.keySet(), latencyMs));
        } catch (Exception e) {
            // 2xx 但报文无法解析：不重试（重试也无法改变报文结构），直接归一化为不可用
            return Optional.of(error(startNanos, "决策模型响应解析失败：" + rootMessage(e)
                + "，响应体=" + truncate(attempt.body())));
        }
    }

    /**
     * 外呼一次并做「失败重试一次」：非 2xx 或传输异常时等待 {@link #RETRY_BACKOFF_MS} 毫秒后重试一次。
     *
     * @param url         已拼装的服务地址
     * @param cfg         当前配置（取 apiKey / timeout）
     * @param requestJson 请求体 JSON
     * @return 最后一次尝试的结果
     */
    private HttpAttempt invokeWithRetry(String url, AiJevConfigBo cfg, String requestJson) {
        HttpAttempt attempt = invokeOnce(url, cfg, requestJson);
        if (attempt.ok()) {
            return attempt;
        }
        log.warn("结构化决策首次调用失败，将重试一次：{}", attempt.error());
        sleepQuietly();
        return invokeOnce(url, cfg, requestJson);
    }

    /**
     * 单次外呼（失败不抛异常，统一封装为 {@link HttpAttempt}）。
     *
     * @param url         服务地址
     * @param cfg         当前配置
     * @param requestJson 请求体 JSON
     * @return 单次外呼结果
     */
    private HttpAttempt invokeOnce(String url, AiJevConfigBo cfg, String requestJson) {
        try (HttpResponse response = HttpRequest.post(url)
            .header("Authorization", "Bearer " + cfg.getApiKey())
            .header("Content-Type", "application/json")
            .body(requestJson)
            .timeout(Math.max(1, cfg.getTimeoutSeconds()) * 1000)
            .execute()) {
            int status = response.getStatus();
            String body = response.body();
            if (status < 200 || status >= 300) {
                return new HttpAttempt(status, body,
                    "决策模型返回非 2xx（HTTP " + status + "）：" + truncate(body));
            }
            return new HttpAttempt(status, body, null);
        } catch (Exception e) {
            // 超时 / 连接失败 / IO 异常：归一化为不可用，不向调用方抛出
            return new HttpAttempt(-1, null, "决策模型调用失败：" + rootMessage(e));
        }
    }

    /**
     * 间隔等待（重试退避）。被中断时恢复中断标记并立即继续，避免吞掉中断信号。
     */
    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把决策模型返回<b>宽松地</b>归一化为判定结果（包级静态纯函数，便于单测）。
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

    /**
     * 拼装 System One 服务地址，与 jev 模块 {@code systemOneUrl} 语义一致。
     *
     * @param rawBaseUrl 配置项里的基址
     * @return 归一化后的服务地址
     */
    private static String systemOneUrl(String rawBaseUrl) {
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
    private Map<String, Object> buildCriteria(List<Candidate> candidates) {
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
     * 构造带 {@code error} 的判定结果（统一记日志口径）。
     *
     * @param startNanos 起始纳秒
     * @param message    失败说明
     * @return 不可用的判定结果
     */
    private RouteJudgeVerdict error(long startNanos, String message) {
        log.warn("结构化决策失败（按不可用处理，不影响编排）：{}", message);
        return new RouteJudgeVerdict(null, null, null, elapsedMs(startNanos), 0L, message);
    }

    /**
     * 计算自起始纳秒起的耗时（毫秒）。
     *
     * @param startNanos 起始纳秒
     * @return 耗时（毫秒）
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 截断过长响应文本（仅用于日志 / 错误信息，不含密钥）。
     *
     * @param text 原文
     * @return 截断结果
     */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > ERROR_BODY_MAX_CHARS
            ? trimmed.substring(0, ERROR_BODY_MAX_CHARS) + "..." : trimmed;
    }

    /**
     * 取异常根因的简短说明（避免日志里塞整条堆栈）。
     *
     * @param error 异常
     * @return 说明文本
     */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return StringUtils.isBlank(message) ? root.getClass().getSimpleName() : message;
    }

    /**
     * 单次外呼的结果。
     *
     * @param status HTTP 状态码（-1 表示传输层异常）
     * @param body   响应体（传输异常时为 null）
     * @param error  非空表示本次尝试失败（含 HTTP 状态与截断响应体 / 异常说明）
     */
    private record HttpAttempt(int status, String body, String error) {

        /**
         * 本次尝试是否成功（2xx 且无错误说明）。
         *
         * @return 成功返回 true
         */
        boolean ok() {
            return error == null && status >= 200 && status < 300;
        }
    }

}