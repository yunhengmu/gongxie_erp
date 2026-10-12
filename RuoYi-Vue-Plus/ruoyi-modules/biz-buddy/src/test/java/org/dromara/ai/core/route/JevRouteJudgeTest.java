package org.dromara.ai.core.route;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiJevConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 结构化决策单测：
 * <ul>
 *   <li>{@link JevSystemOneSupport} 的宽松校验纯函数 {@code toVerdictLeniently} 分支覆盖，
 *       以及报文映射器 {@link JevSystemOneSupport#MAPPER} 对「真实百炼形状」响应的解析能力；</li>
 *   <li>{@link JevRouteJudge} 实例级行为：从运行期配置取「所选模型主键」，连接信息全部回查「模型配置」，
 *       任一前置不满足（未启用 / 未选模型 / 不存在 / 已停用 / 类型不符 / 密钥为空）即返回空判定且不外呼。</li>
 * </ul>
 */
@DisplayName("结构化决策：宽松校验、报文解析与判定实例")
class JevRouteJudgeTest {

    /**
     * 闭集判据：self + 两个候选专家 + other
     */
    private static final Set<String> CRITERIA_KEYS =
        Set.of("self", "probe_expert_a", "probe_expert_b", "other");

    /**
     * 运行期配置中心（提供开关与所选模型主键）
     */
    private final AiRuntimeConfigStore runtimeConfigStore = mock(AiRuntimeConfigStore.class);

    /**
     * 「模型配置」服务（提供连接信息与密钥解密）
     */
    private final IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);

    private final JevRouteJudge judge = new JevRouteJudge(runtimeConfigStore, modelConfigService);

    /**
     * 决策模型基址（不在终点判定，故拼装为 {@code .../v1/systemone}）
     */
    private static final String BASE_URL = "https://ws.cn-beijing.maas.aliyuncs.com/compatible-mode";

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    /**
     * 构造「概率和为 0.99（两位小数舍入）」的真实分布
     */
    private static Map<String, Double> probabilitiesSum099() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.44);
        probabilities.put("probe_expert_b", 0.52);
        probabilities.put("other", 0.03);
        return probabilities;
    }

    /**
     * 以「choice + confidence + 概率分布」构造返回，usage 固定 inputTokens=42。
     */
    private static RouteJudgeVerdict verdictOf(String choice, Double confidence, Map<String, Double> probabilities) {
        ChoiceAnswer answer = new ChoiceAnswer(choice, probabilities, confidence);
        Map<String, Answer> answers = Map.of("expert", answer);
        SystemOneResult result = new SystemOneResult("decision-model-preview", answers, new Usage(42L, 0L));
        return JevSystemOneSupport.toVerdictLeniently(result, CRITERIA_KEYS, 7L);
    }

    @Test
    @DisplayName("概率和 0.99：接受（两位小数舍入），choice/confidence/概率/费用均正确")
    void shouldAcceptSum099() {
        Map<String, Double> probabilities = probabilitiesSum099();

        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 0.88, probabilities);

        assertNull(verdict.error());
        assertEquals("probe_expert_b", verdict.choice());
        assertEquals(0.88, verdict.confidence().doubleValue());
        assertEquals(probabilities, verdict.probabilities());
        assertEquals(42L, verdict.inputTokens());
        assertEquals(7L, verdict.latencyMs());
    }

    @Test
    @DisplayName("概率和 1.00：接受且不告警")
    void shouldAcceptSumExactlyOne() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.45);
        probabilities.put("probe_expert_b", 0.52);
        probabilities.put("other", 0.03);

        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 0.9, probabilities);

        assertNull(verdict.error());
        assertEquals("probe_expert_b", verdict.choice());
    }

    @Test
    @DisplayName("概率和 0.90：拒绝，error 含「偏离」")
    void shouldRejectSum090() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.40);
        probabilities.put("probe_expert_b", 0.47);
        probabilities.put("other", 0.03);

        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 0.8, probabilities);

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("偏离"));
    }

    @Test
    @DisplayName("概率和 1.06：拒绝")
    void shouldRejectSum106() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.45);
        probabilities.put("probe_expert_b", 0.55);
        probabilities.put("other", 0.06);

        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 0.8, probabilities);

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("偏离"));
    }

    @Test
    @DisplayName("choice 不在闭集内：拒绝，error 含「越界」")
    void shouldRejectChoiceOutOfClosedSet() {
        RouteJudgeVerdict verdict = verdictOf("ghost_expert", 0.8, probabilitiesSum099());

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("越界"));
    }

    @Test
    @DisplayName("概率含未知键：拒绝，error 含「未知选项」")
    void shouldRejectUnknownProbabilityKey() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.44);
        probabilities.put("probe_expert_b", 0.52);
        probabilities.put("ghost_expert", 0.03);

        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 0.8, probabilities);

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("未知选项"));
    }

    @Test
    @DisplayName("缺失某个 criteria 键（只给 3 个）：宽松接受，不报错")
    void shouldTolerateMissingCriteriaKey() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("self", 0.0);
        probabilities.put("probe_expert_a", 0.44);
        probabilities.put("other", 0.56);

        RouteJudgeVerdict verdict = verdictOf("probe_expert_a", 0.7, probabilities);

        assertNull(verdict.error());
        assertEquals("probe_expert_a", verdict.choice());
    }

    @Test
    @DisplayName("缺 confidence（null）：接受且 confidence() 为 null")
    void shouldAcceptNullConfidence() {
        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", null, probabilitiesSum099());

        assertNull(verdict.error());
        assertNull(verdict.confidence());
    }

    @Test
    @DisplayName("confidence=1.2 越界：拒绝")
    void shouldRejectInvalidConfidence() {
        RouteJudgeVerdict verdict = verdictOf("probe_expert_b", 1.2, probabilitiesSum099());

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("置信度非法"));
    }

    @Test
    @DisplayName("answers 放 NoulAnswer 而非 ChoiceAnswer：拒绝，error 含「未返回 choice 答案」")
    void shouldRejectNonChoiceAnswer() {
        Map<String, Answer> answers = Map.of("expert", new NoulAnswer(0.5));
        SystemOneResult result = new SystemOneResult("decision-model-preview", answers, new Usage(9L, 0L));

        RouteJudgeVerdict verdict = JevSystemOneSupport.toVerdictLeniently(result, CRITERIA_KEYS, 3L);

        assertNotNull(verdict.error());
        assertEquals(true, verdict.error().contains("未返回 choice 答案"));
        assertEquals(9L, verdict.inputTokens());
    }

    @Test
    @DisplayName("inputTokens 来自 usage；usage 为 null 时取 0")
    void shouldResolveInputTokensFromUsage() {
        ChoiceAnswer answer = new ChoiceAnswer("probe_expert_b", probabilitiesSum099(), 0.9);
        Map<String, Answer> answers = Map.of("expert", answer);

        SystemOneResult withUsage = new SystemOneResult("m", answers, new Usage(123L, 0L));
        assertEquals(123L, JevSystemOneSupport.toVerdictLeniently(withUsage, CRITERIA_KEYS, 1L).inputTokens());

        SystemOneResult withoutUsage = new SystemOneResult("m", answers, null);
        assertEquals(0L, JevSystemOneSupport.toVerdictLeniently(withoutUsage, CRITERIA_KEYS, 1L).inputTokens());
    }

    @Test
    @DisplayName("解析真实百炼形状 JSON：容忍顶层冗余字段与缺失 output_tokens，经宽松校验得到可用 verdict")
    void shouldParseRealDashScopeShapedJson() throws Exception {
        String json = """
            {
              "model": "decision-model-preview",
              "request_id": "req-20261012-abcdef",
              "latency_ms": 321,
              "answers": {
                "expert": {
                  "type": "choice",
                  "choice": "probe_expert_b",
                  "probabilities": {
                    "self": 0.0,
                    "probe_expert_a": 0.44,
                    "probe_expert_b": 0.52,
                    "other": 0.03
                  },
                  "confidence": 0.81
                }
              },
              "usage": {
                "input_tokens": 123
              }
            }
            """;

        SystemOneResult result = JevSystemOneSupport.MAPPER.readValue(json, SystemOneResult.class);

        assertEquals("decision-model-preview", result.model());
        Answer answer = result.answers().get("expert");
        assertEquals(true, answer instanceof ChoiceAnswer);
        assertEquals("probe_expert_b", ((ChoiceAnswer) answer).choice());
        assertEquals(0.03, ((ChoiceAnswer) answer).probabilities().get("other").doubleValue());
        assertEquals(123L, result.usage().inputTokens());
        // 百炼只回 input_tokens：缺失的 output_tokens 由基本类型补 0
        assertEquals(0L, result.usage().outputTokens());

        RouteJudgeVerdict verdict = JevSystemOneSupport.toVerdictLeniently(result, CRITERIA_KEYS, 5L);
        assertNull(verdict.error());
        assertEquals("probe_expert_b", verdict.choice());
        assertEquals(123L, verdict.inputTokens());
    }

    @Test
    @DisplayName("判定实例：按所选模型主键回查连接信息，外呼 .../v1/systemone 并得到可用判定")
    void shouldResolveModelAndInvokeSystemOne() {
        when(runtimeConfigStore.jev()).thenReturn(enabledWith(9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_DECISION));
        when(modelConfigService.decryptApiKey(9L)).thenReturn("sk-decision");
        try (MockedStatic<HttpRequest> http = mockStatic(HttpRequest.class)) {
            stubHttp(http, 200, """
                {"answers":{"expert":{"type":"choice","choice":"expert_a","confidence":0.9,
                "probabilities":{"self":0.1,"expert_a":0.8,"other":0.1}}},
                "usage":{"input_tokens":30}}""");

            Optional<RouteJudgeVerdict> verdict = judge.judge(request());

            assertTrue(verdict.isPresent());
            assertNull(verdict.get().error());
            assertEquals("expert_a", verdict.get().choice());
            assertEquals(30L, verdict.get().inputTokens());
            // 连接信息来自「模型配置」：根地址自动补 /v1/systemone
            http.verify(() -> HttpRequest.post(BASE_URL + "/v1/systemone"));
        }
    }

    @Test
    @DisplayName("判定实例：未启用 → 空判定且不外呼")
    void shouldReturnEmptyWhenDisabled() {
        AiJevConfigBo cfg = new AiJevConfigBo();
        cfg.setEnabled(false);
        cfg.setModelConfigId(9L);
        when(runtimeConfigStore.jev()).thenReturn(cfg);

        assertTrue(judge.judge(request()).isEmpty());
    }

    @Test
    @DisplayName("判定实例：已启用但未选择决策模型 → 空判定且不外呼")
    void shouldReturnEmptyWhenModelNotSelected() {
        AiJevConfigBo cfg = new AiJevConfigBo();
        cfg.setEnabled(true);
        when(runtimeConfigStore.jev()).thenReturn(cfg);

        assertTrue(judge.judge(request()).isEmpty());
    }

    @Test
    @DisplayName("判定实例：所选模型不存在 → 空判定且不外呼")
    void shouldReturnEmptyWhenModelMissing() {
        when(runtimeConfigStore.jev()).thenReturn(enabledWith(9L));
        when(modelConfigService.queryById(9L)).thenReturn(null);

        assertTrue(judge.judge(request()).isEmpty());
    }

    @Test
    @DisplayName("判定实例：所选模型已停用 → 空判定且不外呼")
    void shouldReturnEmptyWhenModelDisabled() {
        when(runtimeConfigStore.jev()).thenReturn(enabledWith(9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("1", AiModelConfig.TYPE_DECISION));

        assertTrue(judge.judge(request()).isEmpty());
    }

    @Test
    @DisplayName("判定实例：所选模型类型不是 decision → 空判定且不外呼")
    void shouldReturnEmptyWhenModelTypeMismatch() {
        when(runtimeConfigStore.jev()).thenReturn(enabledWith(9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_CHAT));

        assertTrue(judge.judge(request()).isEmpty());
    }

    @Test
    @DisplayName("判定实例：所选模型未配置密钥 → 空判定且不外呼")
    void shouldReturnEmptyWhenApiKeyBlank() {
        when(runtimeConfigStore.jev()).thenReturn(enabledWith(9L));
        when(modelConfigService.queryById(9L)).thenReturn(decisionModel("0", AiModelConfig.TYPE_DECISION));
        when(modelConfigService.decryptApiKey(9L)).thenReturn(null);

        assertTrue(judge.judge(request()).isEmpty());
    }

    /**
     * 构造「已启用 + 指定模型主键」的结构化决策配置。
     *
     * @param modelConfigId 所选决策模型主键
     * @return 配置
     */
    private static AiJevConfigBo enabledWith(Long modelConfigId) {
        AiJevConfigBo cfg = new AiJevConfigBo();
        cfg.setEnabled(true);
        cfg.setModelConfigId(modelConfigId);
        return cfg;
    }

    /**
     * 构造「模型配置」中的一条记录。
     *
     * @param status    状态（0 正常 / 1 停用）
     * @param modelType 模型类型
     * @return 模型配置视图
     */
    private static AiModelConfigVo decisionModel(String status, String modelType) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(9L);
        vo.setConfigName("百炼决策模型");
        vo.setModelType(modelType);
        vo.setModelCode("decision-model-preview");
        vo.setBaseUrl(BASE_URL);
        vo.setStatus(status);
        return vo;
    }

    /**
     * 构造一次判定请求（单候选，闭集为 self + expert_a + other）。
     *
     * @return 判定请求
     */
    private static RouteJudgeRequest request() {
        return new RouteJudgeRequest("帮我看看这张报销单能不能过",
            List.of(new RouteJudgeRequest.Candidate("expert_a", "报销专家", "负责差旅报销审核")));
    }

    /**
     * 桩化 {@link HttpRequest} 静态工厂：固定返回一次成功响应。
     *
     * @param http   静态桩
     * @param status HTTP 状态
     * @param body   响应体
     */
    private static void stubHttp(MockedStatic<HttpRequest> http, int status, String body) {
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyInt())).thenReturn(request);
        when(request.body(anyString())).thenReturn(request);
        when(request.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn(body);
        http.when(() -> HttpRequest.post(anyString())).thenReturn(request);
    }

}