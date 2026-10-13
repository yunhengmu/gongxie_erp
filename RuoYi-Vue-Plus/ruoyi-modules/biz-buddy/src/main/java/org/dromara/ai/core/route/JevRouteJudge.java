package org.dromara.ai.core.route;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.route.RouteJudgeRequest.Candidate;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路由判断端口的决策模型实现（百炼决策模型 / TypeSafe System One 协议）。
 *
 * <p><b>决策模型的连接信息全部来自「模型配置」</b>：本类只从运行期配置取「判定策略 + 所选模型主键」
 * （{@link AiRuntimeConfigStore#jev()} → {@link AiJevConfigBo#getModelConfigId()}），随后按该主键从
 * {@code ai_model_config}（类型 {@code decision}）解析服务地址 / 模型标识 / 密钥。配置里没有直填的
 * 地址 / 模型 / 密钥字段，故不存在「两套来源」。协议细节（地址拼装、报文构造、宽松校验）见
 * {@link JevSystemOneSupport}。</p>
 *
 * <p><b>不可用即「无判定」</b>：未启用 / 未选模型 / 模型不存在 / 已停用 / 非 decision 类型 / 地址或标识为空 /
 * 密钥为空 → 返回 {@link Optional#empty()}（不抛异常，等价「无判定」，沿用 {@code failOpen} 语义，
 * 不新增失败面）；仅在首次出现时打一条可读 WARN，避免每轮刷屏。</p>
 *
 * <p><b>异常归一化</b>：超时 / 网络错误 / 非 2xx / 解析失败一律返回带 {@code error} 的结果，绝不向上抛异常。</p>
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
     * 日志中响应体的截断长度（避免日志被整段响应体淹没）
     */
    private static final int ERROR_BODY_MAX_CHARS = 200;

    /**
     * 失败重试的间隔（毫秒）
     */
    private static final long RETRY_BACKOFF_MS = 300L;

    /**
     * 「模型配置不可用」只告警一次（避免每轮判定刷屏）
     */
    private static final AtomicBoolean MODEL_UNAVAILABLE_WARNED = new AtomicBoolean(false);

    /**
     * 熔断期间未判定的留痕口径（写入 {@code ai_route_run.judge_error}，见 docs/22 §5.1）
     */
    private static final String ERROR_CIRCUIT_OPEN = "circuit_breaker_open";

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final IAiModelConfigService modelConfigService;

    private final JevCircuitBreaker circuitBreaker;

    @Override
    public Optional<RouteJudgeVerdict> judge(RouteJudgeRequest request) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return Optional.empty();
        }
        long startNanos = System.nanoTime();
        AiJevConfigBo cfg = runtimeConfigStore.jev();
        DecisionModel model = resolveDecisionModel(cfg);
        if (model == null) {
            // 未启用 / 无可用决策模型：不判定、不外呼（零成本），由工具决策照旧决定路由
            return Optional.empty();
        }
        // 熔断中（或半开且非本次探测）：不判定、不外呼，等价「无判定」并留痕原因
        // （口径见 docs/22 §3.1 B：提供方宕机期间判定环节的额外耗时归零）
        if (!circuitBreaker.allow(cfg.getModelConfigId(), cfg)) {
            return Optional.of(new RouteJudgeVerdict(null, null, null, elapsedMs(startNanos), 0L,
                ERROR_CIRCUIT_OPEN));
        }
        Map<String, Object> criteria = JevSystemOneSupport.buildCriteria(request.candidates());

        String requestJson;
        try {
            requestJson = JevSystemOneSupport.buildRequestJson(model.modelCode(), request.state(), criteria);
        } catch (Exception e) {
            return Optional.of(error(startNanos, "决策模型请求构造失败：" + rootMessage(e)));
        }

        HttpAttempt attempt = invokeWithRetry(JevSystemOneSupport.endpoint(model.baseUrl()), model, cfg, requestJson);
        long latencyMs = elapsedMs(startNanos);
        if (!attempt.ok()) {
            // 记失败：连续失败到阈值即熔断（只统计外呼连通性失败；2xx 但报文不可解析不算提供方故障）
            circuitBreaker.recordFailure(cfg.getModelConfigId(), cfg);
            log.warn("结构化决策外呼失败（按不可用处理，不影响编排）：{}", attempt.error());
            return Optional.of(new RouteJudgeVerdict(null, null, null, latencyMs, 0L, attempt.error()));
        }
        circuitBreaker.recordSuccess(cfg.getModelConfigId(), cfg);
        try {
            return Optional.of(JevSystemOneSupport.parseLeniently(attempt.body(), criteria.keySet(), latencyMs));
        } catch (Exception e) {
            // 2xx 但报文无法解析：不重试（重试也无法改变报文结构），直接归一化为不可用
            return Optional.of(error(startNanos, "决策模型响应解析失败：" + rootMessage(e)
                + "，响应体=" + truncate(attempt.body())));
        }
    }

    /**
     * 解析可用的决策模型连接信息（全部来自「模型配置」）。
     *
     * @param cfg 结构化决策运行期配置（提供开关与所选模型主键）
     * @return 连接信息；任一前置不满足返回 {@code null} 表示「无判定」
     */
    private DecisionModel resolveDecisionModel(AiJevConfigBo cfg) {
        if (cfg == null || !cfg.isEnabled()) {
            return null;
        }
        Long modelConfigId = cfg.getModelConfigId();
        if (modelConfigId == null) {
            warnModelUnavailable("结构化决策已启用但未选择决策模型（请到「安全合规配置 → 结构化决策路由」选择，"
                + "决策模型需先在「模型配置」登记）");
            return null;
        }
        AiModelConfigVo model = modelConfigService.queryById(modelConfigId);
        if (model == null) {
            warnModelUnavailable("结构化决策所选模型已不存在（id=" + modelConfigId + "），按不可用处理");
            return null;
        }
        if (!"0".equals(model.getStatus())) {
            warnModelUnavailable("结构化决策所选模型已停用（" + model.getConfigName() + "），按不可用处理");
            return null;
        }
        if (!AiModelConfig.TYPE_DECISION.equalsIgnoreCase(model.getModelType())) {
            warnModelUnavailable("结构化决策所选模型类型不是 decision（" + model.getConfigName()
                + "，实际 " + model.getModelType() + "），按不可用处理");
            return null;
        }
        if (StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getModelCode())) {
            warnModelUnavailable("结构化决策所选模型缺少服务地址或模型标识（" + model.getConfigName() + "），按不可用处理");
            return null;
        }
        String apiKey = modelConfigService.decryptApiKey(modelConfigId);
        if (StringUtils.isBlank(apiKey)) {
            warnModelUnavailable("结构化决策所选模型未配置 API 密钥（" + model.getConfigName()
                + "），按不可用处理；请到「模型配置」补全后重试");
            return null;
        }
        return new DecisionModel(model.getBaseUrl(), model.getModelCode(), apiKey);
    }

    /**
     * 「模型配置不可用」只告警一次（后续静默按不可用处理）。
     *
     * @param reason 可读原因
     */
    private static void warnModelUnavailable(String reason) {
        if (MODEL_UNAVAILABLE_WARNED.compareAndSet(false, true)) {
            log.warn("结构化决策不可用（按无判定处理，不影响编排）：{}", reason);
        }
    }

    /**
     * 外呼一次并做「失败重试一次」：非 2xx 或传输异常时等待 {@link #RETRY_BACKOFF_MS} 毫秒后重试一次。
     *
     * <p><b>gate 档不重试</b>：闸门判定在用户请求线程上同步阻塞（{@code start} 事件已下发、正文未开始），
     * 重试会直接叠加到首字延迟上，故 gate 档单次快失败即回落工具决策。
     * <b>shadow 档保留重试</b>：它在入口轮收尾的旁路执行（{@code done} 已下发），重试不影响用户可见耗时。</p>
     *
     * @param url         已拼装的服务地址
     * @param model       决策模型连接信息（取 apiKey）
     * @param cfg         判定策略（取超时与执行模式）
     * @param requestJson 请求体 JSON
     * @return 最后一次尝试的结果
     */
    private HttpAttempt invokeWithRetry(String url, DecisionModel model, AiJevConfigBo cfg, String requestJson) {
        HttpAttempt attempt = invokeOnce(url, model, cfg, requestJson);
        if (attempt.ok()) {
            return attempt;
        }
        if (RouteJudgeMode.parse(cfg.getMode()) == RouteJudgeMode.GATE) {
            log.warn("结构化决策首次调用失败（gate 档不重试，直接回落工具决策）：{}", attempt.error());
            return attempt;
        }
        log.warn("结构化决策首次调用失败，将重试一次：{}", attempt.error());
        sleepQuietly();
        return invokeOnce(url, model, cfg, requestJson);
    }

    /**
     * 单次外呼（失败不抛异常，统一封装为 {@link HttpAttempt}）。
     *
     * @param url         服务地址
     * @param model       决策模型连接信息
     * @param cfg         判定策略
     * @param requestJson 请求体 JSON
     * @return 单次外呼结果
     */
    private HttpAttempt invokeOnce(String url, DecisionModel model, AiJevConfigBo cfg, String requestJson) {
        try (HttpResponse response = HttpRequest.post(url)
            .header("Authorization", "Bearer " + model.apiKey())
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
     * 本次判定使用的决策模型连接信息（全部来自「模型配置」）。
     *
     * @param baseUrl   服务地址（{@code ai_model_config.base_url}）
     * @param modelCode 模型标识（{@code ai_model_config.model_code}）
     * @param apiKey    解密后的密钥
     */
    private record DecisionModel(String baseUrl, String modelCode, String apiKey) {
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