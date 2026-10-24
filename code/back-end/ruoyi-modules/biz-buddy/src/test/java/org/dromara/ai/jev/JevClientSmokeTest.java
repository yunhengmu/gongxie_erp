package org.dromara.ai.jev;

import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.Question;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策模型（百炼 {@code decision-model-preview}，TypeSafe System One 协议）兼容性冒烟。
 *
 * <p>验证目标：AgentScope 的 {@link JevClient} 能否直接解析百炼决策模型的返回——
 * 即「同一协议、只换 base-url 与 model」这一结论是否成立。重点覆盖两处结构差异：
 * <ul>
 *   <li>百炼返回体多出 {@code request_id} / {@code latency_ms}，不能被 DTO 反序列化拒绝；</li>
 *   <li>百炼的 {@code noul} 答案**不返回 confidence**，且 {@code usage} 只有 input_tokens
 *       （DTO 的 {@code outputTokens} 为基本类型，需容忍缺省）。</li>
 * </ul></p>
 *
 * <p>默认**不执行**（依赖外部服务，不进常规 CI）。启用方式：
 * <pre>
 *   BIZBUDDY_JEV_SMOKE=true
 *   BIZBUDDY_JEV_API_KEY=sk-xxx                # 可回退 BIZBUDDY_LLM_API_KEY
 *   BIZBUDDY_JEV_BASE_URL=https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode
 *   BIZBUDDY_JEV_MODEL=decision-model-preview  # 可选，默认即该值
 * </pre></p>
 *
 * @author BizBuddy
 */
@EnabledIfEnvironmentVariable(named = "BIZBUDDY_JEV_SMOKE", matches = "true")
class JevClientSmokeTest {

    /**
     * 默认模型名（与百炼决策模型市场一致）
     */
    private static final String DEFAULT_MODEL = "decision-model-preview";

    /**
     * 候选专家清单：与项目演示种子同构，并带上官方建议的 other 兜底项。
     */
    private static final Map<String, Object> CRITERIA = new LinkedHashMap<>(Map.of(
        "self", "用户问题不需要专业领域知识，入口智能体可直接回答",
        "notice_admin_expert", "公告的创建、查询与维护等公告管理事务",
        "bizbuddy_intro_expert", "BizBuddy 产品介绍、能力与架构相关问题",
        "other", "无法判断归属，或候选专家都不合适"
    ));

    @Test
    @DisplayName("兼容性冒烟：JevClient 可直接解析百炼决策模型的 choice 与 noul 返回")
    void shouldParseChoiceAndNoulAnswersFromBailian() {
        String apiKey = env("BIZBUDDY_JEV_API_KEY", env("BIZBUDDY_LLM_API_KEY", null));
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "未提供 BIZBUDDY_JEV_API_KEY");
        String baseUrl = env("BIZBUDDY_JEV_BASE_URL", null);
        Assumptions.assumeTrue(baseUrl != null && !baseUrl.isBlank(), "未提供 BIZBUDDY_JEV_BASE_URL");
        String model = env("BIZBUDDY_JEV_MODEL", DEFAULT_MODEL);

        JevClient client = JevClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .model(model)
            .timeout(Duration.ofSeconds(15))
            .build();

        SystemOneResult result = client.systemOneBlocking(buildRoutingRequest(model));

        assertNotNull(result, "返回结果不应为空");
        System.out.printf("[冒烟] model=%s｜answers=%s｜usage=%s%n",
            result.model(), result.answers().keySet(), result.usage());
        assertTrue(result.answers().containsKey("expert"), "缺少 expert 答案");
        assertTrue(result.answers().containsKey("need_human"), "缺少 need_human 答案");
        assertNotNull(result.usage(), "usage 不应为空（费用口径需要）");
        assertTrue(result.usage().inputTokens() > 0, "input_tokens 应大于 0");

        assertChoiceAnswer(result.answers().get("expert"));
        assertNoulAnswer(result.answers().get("need_human"));
    }

    /**
     * 构造「专家路由」同构的请求：一个 choice（选专家）加一个 noul（是否需人工确认）。
     *
     * @param model 模型名
     * @return 请求
     */
    private static SystemOneRequest buildRoutingRequest(String model) {
        Map<String, Question> questions = new LinkedHashMap<>();
        questions.put("expert", new ChoiceQuestion("该问题应由哪个智能体处理？", CRITERIA));
        questions.put("need_human", new NoulQuestion("这是否涉及需要人工确认的写操作？", null));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("user_message", "帮我把上个月发过的那条公告标题改一下，顺便看看现在都有哪些公告？");
        state.put("entry_agent", "小Z");
        state.put("candidates", CRITERIA.keySet());

        return SystemOneRequest.builder()
            .model(model)
            .state(state)
            .questions(questions)
            .build();
    }

    /**
     * 校验 choice 答案：选项须落在闭集内，概率表须归一，置信度须在 [0,1]。
     *
     * @param answer 原始答案
     */
    private static void assertChoiceAnswer(Object answer) {
        ChoiceAnswer choice = assertInstanceOf(ChoiceAnswer.class, answer, "expert 应为 ChoiceAnswer");
        System.out.printf("[冒烟] choice=%s｜confidence=%s｜probabilities=%s%n",
            choice.choice(), choice.confidence(), choice.probabilities());

        assertTrue(CRITERIA.containsKey(choice.choice()), "choice 必须来自闭集：" + choice.choice());
        assertNotNull(choice.confidence(), "choice 必须带 confidence");
        assertTrue(choice.confidence() >= 0 && choice.confidence() <= 1, "confidence 越界：" + choice.confidence());
        assertFalse(choice.probabilities() == null || choice.probabilities().isEmpty(), "概率表不应为空");
        double sum = choice.probabilities().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-4, "概率表应归一");
    }

    /**
     * 校验 noul 答案：百炼不返回 confidence，这里只要求 P(yes) 在 [0,1] 且能正常解析。
     *
     * @param answer 原始答案
     */
    private static void assertNoulAnswer(Object answer) {
        NoulAnswer noul = assertInstanceOf(NoulAnswer.class, answer, "need_human 应为 NoulAnswer");
        System.out.printf("[冒烟] noul P(yes)=%s%n", noul.noul());
        assertNotNull(noul.noul(), "noul 概率不应为空");
        assertTrue(noul.noul() >= 0 && noul.noul() <= 1, "noul 概率越界：" + noul.noul());
    }

    /**
     * 读取环境变量。
     *
     * @param key          变量名
     * @param defaultValue 缺省值
     * @return 变量值
     */
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}