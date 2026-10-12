package org.dromara.ai.core.route;

import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link JevRouteJudge} 单元测试：宽松校验纯函数 {@code toVerdictLeniently} 的分支覆盖，
 * 以及报文映射器 {@link JevRouteJudge#MAPPER} 对「真实百炼形状」响应的解析能力。
 *
 * <p>纯静态函数 / 序列化测试，不依赖 Spring 容器，故不初始化测试支撑上下文。</p>
 */
@DisplayName("结构化决策：宽松校验与报文解析")
class JevRouteJudgeTest {

    /**
     * 闭集判据：self + 两个候选专家 + other
     */
    private static final Set<String> CRITERIA_KEYS =
        Set.of("self", "probe_expert_a", "probe_expert_b", "other");

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
        return JevRouteJudge.toVerdictLeniently(result, CRITERIA_KEYS, 7L);
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

        RouteJudgeVerdict verdict = JevRouteJudge.toVerdictLeniently(result, CRITERIA_KEYS, 3L);

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
        assertEquals(123L, JevRouteJudge.toVerdictLeniently(withUsage, CRITERIA_KEYS, 1L).inputTokens());

        SystemOneResult withoutUsage = new SystemOneResult("m", answers, null);
        assertEquals(0L, JevRouteJudge.toVerdictLeniently(withoutUsage, CRITERIA_KEYS, 1L).inputTokens());
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

        SystemOneResult result = JevRouteJudge.MAPPER.readValue(json, SystemOneResult.class);

        assertEquals("decision-model-preview", result.model());
        Answer answer = result.answers().get("expert");
        assertEquals(true, answer instanceof ChoiceAnswer);
        assertEquals("probe_expert_b", ((ChoiceAnswer) answer).choice());
        assertEquals(0.03, ((ChoiceAnswer) answer).probabilities().get("other").doubleValue());
        assertEquals(123L, result.usage().inputTokens());
        // 百炼只回 input_tokens：缺失的 output_tokens 由基本类型补 0
        assertEquals(0L, result.usage().outputTokens());

        RouteJudgeVerdict verdict = JevRouteJudge.toVerdictLeniently(result, CRITERIA_KEYS, 5L);
        assertNull(verdict.error());
        assertEquals("probe_expert_b", verdict.choice());
        assertEquals(123L, verdict.inputTokens());
    }

}