package org.dromara.ai.core.agent;

import org.dromara.ai.core.route.RouteJudge;
import org.dromara.ai.core.route.RouteJudgeRequest;
import org.dromara.ai.core.route.RouteJudgeVerdict;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.bo.AiJevConfigBo;
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
        List<AiExpertCandidate> candidates = List.of(
            new AiExpertCandidate(11L, "notice_admin_expert", "公告专家", null, "公告的创建、查询与维护"));
        return new AiRouteContext(9L, SESSION_ID, USER_ID, 4L, ENTRY_AGENT_ID, candidates, 6L, null);
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

}