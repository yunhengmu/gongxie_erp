package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.route.RouteJudge;
import org.dromara.ai.core.route.RouteJudgeMode;
import org.dromara.ai.core.route.RouteJudgeRequest;
import org.dromara.ai.core.route.RouteJudgeVerdict;
import org.dromara.ai.core.safety.SafetyAction;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.util.AiPiiMasker;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 结构化决策的判定接入（docs/21 §5.2 / §5.4 / §5.6）。
 *
 * <p><b>关闭态零外呼</b>：{@code enabled=false} 或 {@code apiKey} 为空时<b>直接返回</b>——
 * 不构造 {@code state}、不构造任何请求对象、不发起外呼、不新增耗时（口径 P7、§1.3）。</p>
 *
 * <p>两种判定时机，共用同一套 {@code state} 构造 / PII 脱敏与字段落库逻辑：</p>
 * <ul>
 *   <li>{@link #judgeShadow}：<b>影子档</b>——入口轮正常收尾后旁路判定，结果只写审计字段，
 *       <b>绝不改变</b> {@code route.action} / {@code taskContext} / {@code degraded} 等编排走向；</li>
 *   <li>{@link #judgeGate}：<b>闸门档</b>——入口轮<b>之前</b>前置判定；高置信且选项合法即采信，
 *       由门面短路到专家直答（返回 {@link JudgeGateOutcome#takeOver()}），否则回落入口轮照常执行。</li>
 * </ul>
 *
 * <p><b>出境前强制 PII 脱敏</b>（口径 P9）：对 {@code state} 调 {@link AiPiiMasker}，
 * 命中即以 {@link SafetyScene#JEV_OUTBOUND} 场景留痕；脱敏<b>只作用于外呼通道</b>，
 * 不改 {@code route.taskContext}、不改用户消息。</p>
 *
 * <p><b>不新增失败面</b>（口径 P3）：方法体整体 try/catch，异常只记日志。</p>
 *
 * <p>本类与 {@link AiRouteContext} 同包：判定结果直接写入其包级字段，
 * 避免为跨包访问而放宽整个上下文对象的可见性。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteJudgeService {

    /**
     * 判定来源：本路由由入口智能体的工具决策完成（影子的真实语义——判定只作旁路观测）。
     */
    private static final String SOURCE_TOOL = "L";

    /**
     * 判定来源：闸门档**采信**决策模型（含「判定决定不路由」）。
     */
    private static final String SOURCE_JUDGE = "J";

    /**
     * 判定来源：闸门档**回落**工具决策（未达阈值 / 越界 / 判定不可用）。
     */
    private static final String SOURCE_FALLBACK = "F";

    /**
     * 闭集固定项：入口智能体直接回答
     */
    private static final String CHOICE_SELF = "self";

    /**
     * 闭集固定项：无法判断归属（兜底出口）
     */
    private static final String CHOICE_OTHER = "other";

    /**
     * 安全事件留痕的引擎名（PII 脱敏命中）
     */
    private static final String PII_MASK_ENGINE = "pii-mask";

    /**
     * 审计模式取值：影子
     */
    private static final String MODE_CODE_SHADOW = "S";

    /**
     * 审计模式取值：闸门。
     *
     * <p>包级可见：门面收尾处据此判断「本轮是否已做过前置判定」，避免同一轮双跑判定。</p>
     */
    static final String MODE_CODE_GATE = "G";

    /**
     * 候选预筛模式只告警一次的开关（避免每次判定刷屏）。
     *
     * <p>闸门档已为真实接管、影子档为默认档，二者都不再告警；只有 {@code preselect} 仍待实现。</p>
     */
    private static final AtomicBoolean PRESELECT_WARNED = new AtomicBoolean(false);

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final RouteJudge routeJudge;

    private final IAiSafetyEventService safetyEventService;

    /**
     * 影子判定：并联调一次决策模型，结果只写回审计字段，不改变编排走向。
     *
     * <p>调用时机：入口轮正常收尾（{@code finish} 落库并下发 done）<b>之后</b>、
     * 编排运行收尾（{@code finishRoute}）之前——既不改用户可见耗时，又能在同一次
     * 落库更新里带上判定审计。</p>
     *
     * @param route       编排运行状态（提供候选清单与会话 / 入口智能体上下文）
     * @param userContent 本轮用户原话（已过输入守卫；只用它，不拼历史）
     * @param userId      提问用户（PII 留痕用）
     * @param deptId      提问用户部门（候选清单已按此过滤，此处仅保持接口对称）
     * @param messageId   触发本轮的用户消息主键（出境留痕用）
     */
    public void judgeShadow(AiRouteContext route, String userContent, Long userId, Long deptId, Long messageId) {
        if (route == null) {
            return;
        }
        try {
            AiJevConfigBo cfg = runtimeConfigStore.jev();
            // 关闭态零外呼：未启用 / 未选决策模型 一律短路，连 state 都不构造（口径 P7、§1.3）
            if (!cfg.isEnabled() || cfg.getModelConfigId() == null) {
                return;
            }
            if (route.candidates == null || route.candidates.isEmpty()) {
                return;
            }
            RouteJudgeMode mode = RouteJudgeMode.parse(cfg.getMode());
            warnIfNotShadow(mode);
            RouteJudgeRequest request = new RouteJudgeRequest(
                outboundState(route, userContent, cfg, userId, messageId), toCandidates(route.candidates));
            applyVerdict(route, routeJudge.judge(request), mode, route.candidates, cfg);
        } catch (Exception e) {
            // 口径 P3：判断是增强通道，任何异常都不得影响编排
            log.warn("结构化决策影子判定异常（不影响编排）：sessionId={}, err={}",
                route.sessionId, rootMessage(e));
        }
    }

    /**
     * 闸门判定（docs/21 §5.4 / §5.7，T9）：入口轮**之前**判定，高置信且选项合法即采信并短路到专家直答。
     *
     * <p>调用时机：门面下发 {@code start} 事件<b>之后</b>、入口轮 ReAct <b>之前</b>。
     * 与影子判定的区别只在「是否采信」，两者都调模型、都留痕，便于同口径对比。</p>
     *
     * <p><b>不接管时一律回落现状</b>（照常跑入口轮 ReAct）：关闭 / 无密钥 / 非 gate 档 / 无候选、
     * 判定不可用（异常 / 越界 / 未达阈值）、以及「判定达阈值但决定不路由（{@code self} / {@code other}）」
     * 都返回 {@code takeOver=false}，不改变任何既有行为。</p>
     *
     * <p>本方法<b>只写判定与审计字段</b>（{@code decisionSource} / {@code judgeMode} / 8 个判定字段），
     * <b>不写</b> {@code route.action} / {@code taskContext}——编排动作由门面的短路路径设置。</p>
     *
     * @param route       编排运行状态（提供候选清单与会话 / 入口智能体上下文）
     * @param userContent 本轮用户原话（已过输入守卫；只用它，不拼历史）
     * @param userId      提问用户（PII 留痕用）
     * @param deptId      提问用户部门（候选清单已按此过滤，此处仅保持接口对称）
     * @param messageId   触发本轮的用户消息主键（出境留痕用）
     * @return 采信结果：{@code takeOver=true} 时 {@code expertCode} 为选中的候选专家 {@code agent_code}
     */
    public JudgeGateOutcome judgeGate(AiRouteContext route, String userContent, Long userId, Long deptId,
                                      Long messageId) {
        if (route == null) {
            return JudgeGateOutcome.notTakeOver();
        }
        try {
            AiJevConfigBo cfg = runtimeConfigStore.jev();
            // 关闭态零外呼：未启用 / 未选决策模型 一律短路，连 state 都不构造（口径 P7、§1.3）
            if (!cfg.isEnabled() || cfg.getModelConfigId() == null) {
                return JudgeGateOutcome.notTakeOver();
            }
            // 仅闸门档做前置判定：影子档维持「收尾处判定」，preselect 维持「只解析 + WARN」
            if (RouteJudgeMode.parse(cfg.getMode()) != RouteJudgeMode.GATE) {
                return JudgeGateOutcome.notTakeOver();
            }
            if (route.candidates == null || route.candidates.isEmpty()) {
                return JudgeGateOutcome.notTakeOver();
            }
            RouteJudgeRequest request = new RouteJudgeRequest(
                outboundState(route, userContent, cfg, userId, messageId), toCandidates(route.candidates));
            Optional<RouteJudgeVerdict> verdictOpt = routeJudge.judge(request);
            applyGateVerdict(route, verdictOpt, route.candidates, cfg);
            return decideGate(route, verdictOpt, cfg);
        } catch (Exception e) {
            // 口径 P3：判断是增强通道，异常只记日志并回落入口轮，绝不把整轮打挂
            log.warn("结构化决策闸门判定异常（不接管、回落入口轮）：sessionId={}, err={}",
                route.sessionId, rootMessage(e));
            return JudgeGateOutcome.notTakeOver();
        }
    }

    /**
     * 由判定结果推导闸门处置与审计来源（口径 6）。
     *
     * <ul>
     *   <li>达阈值 且 命中本轮候选 → 采信并短路（{@code takeOver=true}，来源 {@code J}）；</li>
     *   <li>达阈值 且 决定不路由（{@code self} / {@code other}）→ 不短路，判定仍被采信（来源 {@code J}）；</li>
     *   <li>未达阈值 / 越界 / 判定不可用 → 回落工具决策（来源 {@code F}）。</li>
     * </ul>
     *
     * @param route      编排运行状态（已写入判定字段）
     * @param verdictOpt 判定结果（空表示不可用）
     * @param cfg        结构化决策配置（取阈值）
     * @return 采信结果
     */
    private JudgeGateOutcome decideGate(AiRouteContext route, Optional<RouteJudgeVerdict> verdictOpt,
                                        AiJevConfigBo cfg) {
        if (verdictOpt.isEmpty()) {
            route.decisionSource = SOURCE_FALLBACK;
            return JudgeGateOutcome.notTakeOver();
        }
        RouteJudgeVerdict verdict = verdictOpt.get();
        // 「达阈值」要求判定可用（无 error）且置信度达标；越界由下的候选命中判定兜住
        boolean reachedThreshold = StringUtils.isBlank(verdict.error()) && verdict.confidence() != null
            && verdict.confidence() >= cfg.getConfidenceThreshold();
        boolean routedToExpert = reachedThreshold && route.candidateByKey.containsKey(verdict.choice());
        boolean declinedRouting = reachedThreshold
            && (CHOICE_SELF.equals(verdict.choice()) || CHOICE_OTHER.equals(verdict.choice()));
        route.decisionSource = routedToExpert || declinedRouting ? SOURCE_JUDGE : SOURCE_FALLBACK;
        return routedToExpert
            ? new JudgeGateOutcome(true, verdict.choice())
            : JudgeGateOutcome.notTakeOver();
    }

    /**
     * 闸门判定的采信结果（docs/21 §5.7，T9）。
     *
     * @param takeOver   是否采信并短路到专家直答（否则入口轮照常执行）
     * @param expertCode 采信时选中的候选专家 {@code agent_code}；不接管时为 {@code null}
     */
    public record JudgeGateOutcome(boolean takeOver, String expertCode) {

        /**
         * 不接管：关闭 / 非 gate 档 / 未达阈值 / 越界 / 判定不可用 / 判定决定不路由。
         *
         * @return 不接管的采信结果
         */
        static JudgeGateOutcome notTakeOver() {
            return new JudgeGateOutcome(false, null);
        }
    }

    /**
     * 构造出境 {@code state} 并强制 PII 脱敏：命中即留痕，返回脱敏后的外呼文本。
     *
     * @param route       编排运行状态
     * @param userContent 本轮用户原话
     * @param cfg         结构化决策配置
     * @param userId      提问用户
     * @param messageId   触发本轮的用户消息主键
     * @return 脱敏后的外呼 state
     */
    private String outboundState(AiRouteContext route, String userContent, AiJevConfigBo cfg,
                                 Long userId, Long messageId) {
        String raw = buildState(userContent, route.candidates, cfg.getStateMaxChars());
        AiPiiMasker.Outcome masked = AiPiiMasker.mask(raw);
        if (masked.count() > 0) {
            safetyEventService.record(SafetyScene.JEV_OUTBOUND,
                new SafetyDecision(SafetyAction.MASK, masked.text(), true, masked.types()),
                PII_MASK_ENGINE, route.sessionId, messageId, route.entryAgentId, userId, masked.text());
        }
        return masked.text();
    }

    /**
     * 构造 state 文本：本轮用户原话 + 候选清单；超出上限按字符截断（前置保护）。
     *
     * @param userContent 本轮用户原话
     * @param candidates  候选清单
     * @param maxChars    字符上限
     * @return state 文本
     */
    private String buildState(String userContent, List<AiExpertCandidate> candidates, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户本轮问题：\n").append(StringUtils.isBlank(userContent) ? "（无）" : userContent).append('\n');
        sb.append("候选专家：\n");
        for (AiExpertCandidate candidate : candidates) {
            sb.append("- key=").append(candidate.agentKey())
                .append(" | 名称=").append(candidate.agentName())
                .append(" | 擅长=")
                .append(StringUtils.isBlank(candidate.capabilityDesc()) ? "（未填写）" : candidate.capabilityDesc())
                .append('\n');
        }
        String state = sb.toString();
        int limit = Math.max(1, maxChars);
        return state.length() > limit ? state.substring(0, limit) : state;
    }

    /**
     * 把候选专家映射为判断请求的候选（键用 {@code agent_code}，判据用「擅长 / 描述」）。
     *
     * @param candidates 候选清单
     * @return 请求候选
     */
    private List<RouteJudgeRequest.Candidate> toCandidates(List<AiExpertCandidate> candidates) {
        List<RouteJudgeRequest.Candidate> result = new ArrayList<>(candidates.size());
        for (AiExpertCandidate candidate : candidates) {
            result.add(new RouteJudgeRequest.Candidate(
                candidate.agentKey(), candidate.agentName(), candidate.capabilityDesc()));
        }
        return result;
    }

    /**
     * 把**影子档**判定结果写回审计字段（不触碰编排走向）。
     *
     * <p>影子档下判定未参与决策，故来源恒为 {@link #SOURCE_TOOL}；异常与越界只落在 {@code judgeError}。</p>
     *
     * @param route      编排运行状态
     * @param verdictOpt 判定结果（空表示不可用）
     * @param mode       执行模式
     * @param candidates 候选清单（choice 越界校验用）
     * @param cfg        结构化决策配置
     */
    private void applyVerdict(AiRouteContext route, Optional<RouteJudgeVerdict> verdictOpt, RouteJudgeMode mode,
                              List<AiExpertCandidate> candidates, AiJevConfigBo cfg) {
        route.judgeMode = mode == RouteJudgeMode.GATE ? MODE_CODE_GATE : MODE_CODE_SHADOW;
        route.decisionSource = SOURCE_TOOL;
        recordVerdict(route, verdictOpt, candidates, cfg);
    }

    /**
     * 把**闸门档**判定结果写回审计字段（来源由 {@link #decideGate} 定夺）。
     *
     * @param route      编排运行状态
     * @param verdictOpt 判定结果（空表示不可用）
     * @param candidates 候选清单（choice 越界校验用）
     * @param cfg        结构化决策配置（取阈值）
     */
    private void applyGateVerdict(AiRouteContext route, Optional<RouteJudgeVerdict> verdictOpt,
                                  List<AiExpertCandidate> candidates, AiJevConfigBo cfg) {
        route.judgeMode = MODE_CODE_GATE;
        route.judgeThreshold = cfg.getConfidenceThreshold();
        recordVerdict(route, verdictOpt, candidates, cfg);
    }

    /**
     * 写入 8 个判定审计字段（choice / confidence / 概率 / 耗时 / token / error）；不写来源与模式。
     *
     * <p>影子档与闸门档共用：两者都调模型、都落同一批审计列，便于同口径对比（§5.4）。</p>
     *
     * @param route      编排运行状态
     * @param verdictOpt 判定结果（空表示不可用）
     * @param candidates 候选清单（choice 越界校验用）
     * @param cfg        结构化决策配置
     */
    private void recordVerdict(AiRouteContext route, Optional<RouteJudgeVerdict> verdictOpt,
                               List<AiExpertCandidate> candidates, AiJevConfigBo cfg) {
        if (verdictOpt.isEmpty()) {
            route.judgeError = "判定不可用（无返回）";
            return;
        }
        RouteJudgeVerdict verdict = verdictOpt.get();
        route.judgeChoice = verdict.choice();
        route.judgeConfidence = verdict.confidence();
        route.judgeLatencyMs = verdict.latencyMs();
        route.judgeTokens = verdict.inputTokens();
        if (cfg.isPersistProbabilities()) {
            route.judgeProbabilities = serializeProbabilities(verdict.probabilities());
        }
        if (StringUtils.isNotBlank(verdict.error())) {
            route.judgeError = verdict.error();
            return;
        }
        if (!isAllowedChoice(verdict.choice(), candidates)) {
            // 越界：不采纳，只记违规（防决策模型给出清单外的专家）
            route.judgeError = "choice 越界: " + verdict.choice();
        }
    }

    /**
     * choice 是否落在闭集内（{@code self} / {@code other} / 候选 {@code agent_code}）。
     *
     * @param choice     决策模型选择
     * @param candidates 候选清单
     * @return 合法返回 true
     */
    private boolean isAllowedChoice(String choice, List<AiExpertCandidate> candidates) {
        if (StringUtils.isBlank(choice)) {
            return false;
        }
        if (CHOICE_SELF.equals(choice) || CHOICE_OTHER.equals(choice)) {
            return true;
        }
        for (AiExpertCandidate candidate : candidates) {
            if (choice.equals(candidate.agentKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 序列化概率分布（空则不落）。
     *
     * @param probabilities 概率分布
     * @return JSON 文本；为空返回 null
     */
    private String serializeProbabilities(Map<String, Double> probabilities) {
        return probabilities == null || probabilities.isEmpty() ? null : JsonUtils.toJsonString(probabilities);
    }

    /**
     * 候选预筛模式首用时告警一次：该档仍待实现，本批次按影子语义执行，不静默。
     *
     * <p>闸门档（{@link RouteJudgeMode#GATE}）已为真实接管、影子档为默认档，均不再告警。</p>
     *
     * @param mode 执行模式
     */
    private void warnIfNotShadow(RouteJudgeMode mode) {
        if (mode == RouteJudgeMode.PRESELECT && PRESELECT_WARNED.compareAndSet(false, true)) {
            log.warn("结构化决策 mode={}（{}）的候选预筛待实现，本批次按 shadow 语义执行：只留痕、不接管编排",
                mode.wire(), mode.label());
        }
    }

    /**
     * 取异常根因的简短说明。
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

}