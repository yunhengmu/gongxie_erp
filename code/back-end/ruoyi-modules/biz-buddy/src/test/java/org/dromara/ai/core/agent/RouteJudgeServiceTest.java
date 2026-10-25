package org.dromara.ai.core.agent;

import org.dromara.ai.core.route.RouteJudge;
import org.dromara.ai.core.route.RouteJudgeRequest;
import org.dromara.ai.core.route.RouteJudgeVerdict;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RouteJudgeService} 单元测试：关闭态零外呼、影子不接管、PII 脱敏与留痕、越界与异常不新增失败面。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("结构化决策影子判定")
class RouteJudgeServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ENTRY_AGENT_ID = 5L;
    private static final Long MESSAGE_ID = 2L;
    private static final Long MODEL_ID = 9L;

    /**
     * 影子档下判定的实际决策来源恒为工具决策（L）：判定只作旁路观测
     */
    private static final String SOURCE_TOOL = "L";

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    @Mock
    private RouteJudge routeJudge;

    @Mock
    private IAiSafetyEventService safetyEventService;

    @Mock
    private AiRouteRunMapper routeRunMapper;

    @InjectMocks
    private RouteJudgeService service;

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    /**
     * 构造带一个候选专家的编排上下文。
     */
    private AiRouteContext route() {
        return route(1);
    }

    /**
     * 构造带指定候选数（首项恒为 {@code notice_admin_expert}）的编排上下文。
     *
     * @param candidateCount 候选数量
     */
    private AiRouteContext route(int candidateCount) {
        List<AiExpertCandidate> candidates = new ArrayList<>();
        candidates.add(new AiExpertCandidate(11L, "notice_admin_expert", "公告专家", "公告的创建、查询与维护"));
        for (int i = 2; i <= candidateCount; i++) {
            candidates.add(new AiExpertCandidate(10L + i, "expert_" + i, "专家" + i, "候选能力" + i));
        }
        return new AiRouteContext(9L, SESSION_ID, USER_ID, 4L, ENTRY_AGENT_ID, candidates, 6L, null);
    }

    /**
     * 构造一条「上轮判定采信」的编排运行行（粘性路由的取数来源）。
     */
    private static AiRouteRun lastJudge(String choice, double confidence) {
        AiRouteRun run = new AiRouteRun();
        run.setId(8L);
        run.setSessionId(SESSION_ID);
        run.setStatus(AiRouteRun.STATUS_SUCCESS);
        run.setDecisionSource("J");
        run.setJudgeChoice(choice);
        run.setJudgeConfidence(confidence);
        return run;
    }

    /**
     * 构造结构化决策配置（连接信息不回填，只带「是否启用 + 所选模型主键」）。
     *
     * @param enabled       是否启用
     * @param modelConfigId 所选决策模型主键（null 表示未选）
     * @return 配置
     */
    private AiJevConfigBo cfg(boolean enabled, Long modelConfigId) {
        AiJevConfigBo cfg = new AiJevConfigBo();
        cfg.setEnabled(enabled);
        cfg.setModelConfigId(modelConfigId);
        cfg.setMode("shadow");
        return cfg;
    }

    private static RouteJudgeVerdict usableVerdict() {
        return new RouteJudgeVerdict("notice_admin_expert", 0.86,
            Map.of("notice_admin_expert", 0.86, "self", 0.05, "other", 0.09), 62L, 159L, null);
    }

    @Test
    @DisplayName("关闭态：完全不外呼、不构造状态、判定结果为空")
    void shouldNotCallJudgeWhenDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(false, MODEL_ID));
        AiRouteContext route = route();
        route.action = "route";
        route.taskContext = "task";

        service.judgeShadow(route, "帮我看看公告 13800001111", USER_ID, 4L, MESSAGE_ID);

        verify(routeJudge, never()).judge(any());
        verifyNoInteractions(safetyEventService);
        assertEquals("route", route.action);
        assertEquals("task", route.taskContext);
        assertNull(route.judgeMode);
        assertNull(route.decisionSource);
    }

    @Test
    @DisplayName("未选模型：与关闭态等价（零外呼）")
    void shouldNotCallJudgeWhenModelNotSelected() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, null));
        AiRouteContext route = route();
        route.action = "route";

        service.judgeShadow(route, "帮我看看公告", USER_ID, 4L, MESSAGE_ID);

        verify(routeJudge, never()).judge(any());
        assertEquals("route", route.action);
        assertNull(route.judgeMode);
    }

    @Test
    @DisplayName("影子不接管：写入 8 个审计字段但不改变编排走向")
    void shouldRecordVerdictWithoutTakingOver() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();
        route.action = "route";
        route.taskContext = "task";

        service.judgeShadow(route, "帮我看看公告", USER_ID, 4L, MESSAGE_ID);

        // 影子：编排走向不变
        assertEquals("route", route.action);
        assertEquals("task", route.taskContext);
        // 8 个审计字段（来源恒为工具决策 L——判定未参与决策）
        assertEquals(SOURCE_TOOL, route.decisionSource);
        assertEquals("notice_admin_expert", route.judgeChoice);
        assertEquals(0.86, route.judgeConfidence);
        assertNotNull(route.judgeProbabilities);
        assertEquals(62L, route.judgeLatencyMs);
        assertEquals(159L, route.judgeTokens);
        assertNull(route.judgeError);
        assertEquals("S", route.judgeMode);
    }

    @Test
    @DisplayName("出境前强制 PII 脱敏并留痕：送出的 state 已掩码")
    void shouldMaskPiiBeforeOutboundAndRecordEvent() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));

        service.judgeShadow(route(), "我的手机号是13800001111，帮我看看公告", USER_ID, 4L, MESSAGE_ID);

        ArgumentCaptor<RouteJudgeRequest> captor = ArgumentCaptor.forClass(RouteJudgeRequest.class);
        verify(routeJudge).judge(captor.capture());
        String outbound = captor.getValue().state();
        assertFalse(outbound.contains("13800001111"), "出境 state 不应含原始手机号");
        assertTrue(outbound.contains("138****1111"), "出境 state 应含掩码手机号");
        // 脱敏只作用于外呼：候选项仍在
        assertEquals(1, captor.getValue().candidates().size());
        verify(safetyEventService).record(eq(SafetyScene.JEV_OUTBOUND), any(SafetyDecision.class), eq("pii-mask"),
            eq(SESSION_ID), eq(MESSAGE_ID), eq(ENTRY_AGENT_ID), eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("choice 越界：记 judgeError、来源仍是工具决策、编排走向不变")
    void shouldRejectOutOfRangeChoice() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("ghost_expert", 0.9, Map.of(), 10L, 5L, null)));
        AiRouteContext route = route();
        route.action = "route";

        service.judgeShadow(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertNotNull(route.judgeError);
        assertTrue(route.judgeError.contains("越界"));
        assertEquals(SOURCE_TOOL, route.decisionSource);
        assertEquals("route", route.action);
    }

    @Test
    @DisplayName("判定 error 非空：不抛异常、异常落 judgeError")
    void shouldNotThrowWhenVerdictHasError() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict(null, null, null, 1200L, 0L, "决策模型调用失败：timeout")));
        AiRouteContext route = route();

        assertDoesNotThrow(() -> service.judgeShadow(route, "问题", USER_ID, 4L, MESSAGE_ID));

        assertEquals(SOURCE_TOOL, route.decisionSource);
        assertEquals("决策模型调用失败：timeout", route.judgeError);
    }

    @Test
    @DisplayName("判定抛异常：被吞掉、不影响编排且不写判定来源")
    void shouldSwallowJudgeException() {
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        when(routeJudge.judge(any())).thenThrow(new RuntimeException("boom"));
        AiRouteContext route = route();
        route.action = "self";

        assertDoesNotThrow(() -> service.judgeShadow(route, "问题", USER_ID, 4L, MESSAGE_ID));

        assertEquals("self", route.action);
        assertNull(route.decisionSource);
    }

    // ---------------- 闸门判定（T9：真实短路接管） ----------------

    /**
     * 构造闸门档配置（含阈值）。
     */
    private AiJevConfigBo gateCfg(double threshold) {
        AiJevConfigBo cfg = cfg(true, MODEL_ID);
        cfg.setMode("gate");
        cfg.setConfidenceThreshold(threshold);
        return cfg;
    }

    @Test
    @DisplayName("闸门采信：高置信且专家合法 → 接管并记来源 J / 模式 G")
    void shouldTakeOverWhenGateConfidentExpertValid() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "帮我看看公告", USER_ID, 4L, MESSAGE_ID);

        assertTrue(outcome.takeOver());
        assertEquals("notice_admin_expert", outcome.expertCode());
        assertEquals("J", route.decisionSource);
        assertEquals("G", route.judgeMode);
        assertEquals("notice_admin_expert", route.judgeChoice);
        assertEquals(0.86, route.judgeConfidence);
        assertNull(route.judgeError);
    }

    @Test
    @DisplayName("闸门未达阈值：回落工具决策、来源记 F")
    void shouldFallBackWhenGateBelowThreshold() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.9));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("notice_admin_expert", 0.68, Map.of("notice_admin_expert", 0.68), 62L, 159L, null)));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertFalse(outcome.takeOver());
        assertNull(outcome.expertCode());
        assertEquals("F", route.decisionSource);
        assertEquals("G", route.judgeMode);
    }

    @Test
    @DisplayName("闸门判定不路由：choice=self 且高置信 → 不接管、来源仍记 J")
    void shouldNotTakeOverButRecordJudgeWhenGateChoosesSelf() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("self", 0.9, Map.of("self", 0.9), 60L, 150L, null)));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertFalse(outcome.takeOver());
        assertEquals("J", route.decisionSource);
        assertEquals("self", route.judgeChoice);
    }

    @Test
    @DisplayName("闸门 choice 越界：不接管、来源记 F、judgeError 非空")
    void shouldFallBackWhenGateChoiceOutOfRange() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("ghost_expert", 0.9, Map.of(), 10L, 5L, null)));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertFalse(outcome.takeOver());
        assertEquals("F", route.decisionSource);
        assertNotNull(route.judgeError);
        assertTrue(route.judgeError.contains("越界"));
    }

    @Test
    @DisplayName("闸门判定 error：不接管、不抛异常、来源记 F")
    void shouldFallBackWhenGateVerdictHasError() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict(null, null, null, 1200L, 0L, "决策模型调用失败：timeout")));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome = assertDoesNotThrow(
            () -> service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID));

        assertFalse(outcome.takeOver());
        assertEquals("F", route.decisionSource);
        assertEquals("决策模型调用失败：timeout", route.judgeError);
    }

    @Test
    @DisplayName("闸门关闭态零外呼：关闭 / 未选模型 / 影子档 均不调用判定")
    void shouldNotCallJudgeWhenGateDisabled() {
        // 关闭
        when(runtimeConfigStore.jev()).thenReturn(cfg(false, MODEL_ID));
        assertFalse(service.judgeGate(route(), "问题", USER_ID, 4L, MESSAGE_ID).takeOver());
        // 未选模型
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, null));
        assertFalse(service.judgeGate(route(), "问题", USER_ID, 4L, MESSAGE_ID).takeOver());
        // 影子档：走收尾判定，前置判定不接管且不外呼
        when(runtimeConfigStore.jev()).thenReturn(cfg(true, MODEL_ID));
        assertFalse(service.judgeGate(route(), "问题", USER_ID, 4L, MESSAGE_ID).takeOver());

        verify(routeJudge, never()).judge(any());
    }

    @Test
    @DisplayName("闸门判定不改动编排走向：action / taskContext 由门面设置")
    void shouldNotTouchActionOrTaskContextInGateJudge() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();
        route.action = "self";
        route.taskContext = "task";

        service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        // 前置判定只写判定与审计字段，编排动作仍由门面的短路路径决定
        assertEquals("self", route.action);
        assertEquals("task", route.taskContext);
    }

    // ---------------- 措施 A：分桶基础阈值 + margin 二次过滤 ----------------

    @Test
    @DisplayName("分桶阈值：候选 11 个走「大规模」档（0.55），top1=0.6 达标即采信")
    void shouldUseLargeBucketThresholdWhenManyCandidates() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("notice_admin_expert", 0.6,
                Map.of("notice_admin_expert", 0.6, "expert_2", 0.05), 50L, 100L, null)));
        AiRouteContext route = route(11);

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertTrue(outcome.takeOver());
        assertEquals("J", route.decisionSource);
        // 审计记录的是本轮实际使用的分桶阈值（0.55），不是配置里的 0.75
        assertEquals(0.55, route.judgeThreshold);
    }

    @Test
    @DisplayName("分桶阈值：候选 6 个走「中规模」档（0.65）")
    void shouldUseMediumBucketThresholdWhenSixCandidates() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("notice_admin_expert", 0.7,
                Map.of("notice_admin_expert", 0.7, "expert_2", 0.05), 50L, 100L, null)));
        AiRouteContext route = route(6);

        assertTrue(service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID).takeOver());
        assertEquals(0.65, route.judgeThreshold);
    }

    @Test
    @DisplayName("margin 不足：基础阈值达标但 top1/top2 < r → 回落、留痕 margin_rejected")
    void shouldFallBackWhenMarginRejected() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        // 11 个候选 → 基础阈值 0.55；top1=0.6 达标，但 0.6/0.4=1.5 < 2.0 → margin 拒绝
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("notice_admin_expert", 0.6,
                Map.of("notice_admin_expert", 0.6, "self", 0.4), 50L, 100L, null)));
        AiRouteContext route = route(11);

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertFalse(outcome.takeOver());
        assertEquals("F", route.decisionSource);
        assertEquals("margin_rejected", route.judgeError);
        // 判定本身仍被留痕（供校准）
        assertEquals("notice_admin_expert", route.judgeChoice);
        assertEquals(0.6, route.judgeConfidence);
    }

    @Test
    @DisplayName("未达基础阈值时不误记 margin_rejected")
    void shouldNotRecordMarginRejectedWhenBelowThreshold() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(
            new RouteJudgeVerdict("notice_admin_expert", 0.6,
                Map.of("notice_admin_expert", 0.6, "self", 0.4), 50L, 100L, null)));
        AiRouteContext route = route();

        service.judgeGate(route, "问题", USER_ID, 4L, MESSAGE_ID);

        assertEquals("F", route.decisionSource);
        assertNull(route.judgeError);
    }

    // ---------------- 措施 F：粘性路由（跳过判定，来源 K） ----------------

    @Test
    @DisplayName("粘性命中：短句 + 上轮 J 高置信 + 候选有效 → 跳过判定直接接管、来源 K、零外呼")
    void shouldStickyRouteWithoutJudging() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeRunMapper.selectOne(any())).thenReturn(lastJudge("notice_admin_expert", 0.97));
        AiRouteContext route = route();

        RouteJudgeService.JudgeGateOutcome outcome =
            service.judgeGate(route, "继续", USER_ID, 4L, MESSAGE_ID);

        assertTrue(outcome.takeOver());
        assertEquals("notice_admin_expert", outcome.expertCode());
        assertEquals("K", route.decisionSource);
        assertEquals("G", route.judgeMode);
        assertEquals("notice_admin_expert", route.judgeChoice);
        assertEquals(0.97, route.judgeConfidence);
        assertEquals(0.75, route.judgeThreshold);
        // 粘性档不外呼、不构造出境文本，故无 PII 留痕
        verify(routeJudge, never()).judge(any());
        verifyNoInteractions(safetyEventService);
    }

    @Test
    @DisplayName("粘性未命中：本轮原话过长 → 不查上轮、走正常判定")
    void shouldSkipStickyWhenQueryTooLong() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "帮我看看最近的公告有哪些需要处理", USER_ID, 4L, MESSAGE_ID);

        verify(routeRunMapper, never()).selectOne(any());
        verify(routeJudge).judge(any());
        assertEquals("J", route.decisionSource);
    }

    @Test
    @DisplayName("粘性未命中：短句里显式点名了候选专家名 → 不粘、直接走正常判定")
    void shouldSkipStickyWhenCandidateNamed() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "找公告专家", USER_ID, 4L, MESSAGE_ID);

        // 点名即短路在「查上轮」之前：连上轮都不查
        verify(routeRunMapper, never()).selectOne(any());
        verify(routeJudge).judge(any());
        assertEquals("J", route.decisionSource);
    }

    @Test
    @DisplayName("粘性未命中：短句里出现候选 agent_code 字面 → 不粘（字数上限放宽后同样成立）")
    void shouldSkipStickyWhenCandidateKeyNamed() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        // 默认上限 12 字时 agent_code（19 字符）不可能整串出现，故放宽上限以覆盖该分支
        cfg.setStickyMaxQueryLength(30);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "notice_admin_expert 那个", USER_ID, 4L, MESSAGE_ID);

        verify(routeRunMapper, never()).selectOne(any());
        verify(routeJudge).judge(any());
    }

    @Test
    @DisplayName("粘性未命中：上轮来源不是 J → 走正常判定")
    void shouldSkipStickyWhenLastTurnNotJudge() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        AiRouteRun last = lastJudge("notice_admin_expert", 0.97);
        last.setDecisionSource("L");
        when(routeRunMapper.selectOne(any())).thenReturn(last);
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "继续", USER_ID, 4L, MESSAGE_ID);

        verify(routeJudge).judge(any());
        assertEquals("J", route.decisionSource);
    }

    @Test
    @DisplayName("粘性未命中：上轮置信度低于门槛 → 走正常判定")
    void shouldSkipStickyWhenLastConfidenceLow() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeRunMapper.selectOne(any())).thenReturn(lastJudge("notice_admin_expert", 0.6));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "继续", USER_ID, 4L, MESSAGE_ID);

        verify(routeJudge).judge(any());
    }

    @Test
    @DisplayName("粘性未命中：上轮专家已不在本轮候选内（删除 / 停用 / 不再可见）→ 走正常判定")
    void shouldSkipStickyWhenLastExpertNoLongerCandidate() {
        AiJevConfigBo cfg = gateCfg(0.75);
        cfg.setStickyRoutingEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);
        when(routeRunMapper.selectOne(any())).thenReturn(lastJudge("ghost_expert", 0.97));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));
        AiRouteContext route = route();

        service.judgeGate(route, "继续", USER_ID, 4L, MESSAGE_ID);

        verify(routeJudge).judge(any());
        assertEquals("J", route.decisionSource);
    }

    @Test
    @DisplayName("粘性关闭（默认）：不查上轮，行为与现状等价")
    void shouldNotQueryPreviousRunWhenStickyDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(gateCfg(0.75));
        when(routeJudge.judge(any())).thenReturn(Optional.of(usableVerdict()));

        service.judgeGate(route(), "继续", USER_ID, 4L, MESSAGE_ID);

        verifyNoInteractions(routeRunMapper);
        verify(routeJudge).judge(any());
    }

}