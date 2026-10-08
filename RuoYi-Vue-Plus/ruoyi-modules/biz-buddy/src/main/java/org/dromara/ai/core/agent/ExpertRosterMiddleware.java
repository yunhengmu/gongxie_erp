package org.dromara.ai.core.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 候选专家清单注入中间件（REQ-AI-30）：每次模型调用前把**本轮现算**的候选专家清单追加到 system prompt。
 *
 * <p><b>为什么用中间件而不是装配参数</b>：入口智能体的装配实例是**跨用户共享单例**（缓存键
 * {@code agentId}），而候选清单随提问用户的可见范围与专家启停而变化。清单若进装配指纹会导致频繁重建；
 * 作为**每轮运行期上下文**注入则天然满足「新增/停用专家后无需重启、无需重新装配即刻生效」
 * （与 {@link CurrentTimeMiddleware} 的每轮现算同一思路，见 docs/16 §6.1）。</p>
 *
 * <p>不在编排链路中（上下文没有 {@link AiRouteContext}）时原样返回，因此本中间件对共享实例是安全的。</p>
 *
 * @author BizBuddy
 */
public class ExpertRosterMiddleware implements MiddlewareBase {

    /**
     * 注入段落模板（前置两个换行，与既有段落分隔）
     */
    private static final String SECTION_TEMPLATE = "%n%n## 本轮候选专家清单%n%s%n"
        + "只能从上表选择专家；清单里没有的一律不要编造，清单为空就直接自己回答。";

    /**
     * 已指定专家的路由纪律模板（REQ-AI-31）。
     *
     * <p><b>仅用于减少「收尾兜底」的触发</b>：正确性由服务端保证——{@link RouteDecisionTool}
     * 会忽略模型传入的 {@code action} / {@code expert_agent_key} 强制路由，且模型整轮未调用本工具时
     * 由门面补执行一次强制编排。故此处措辞是「减少往返」，不是「唯一保障」。</p>
     */
    private static final String PINNED_TEMPLATE = "%n%n## 本轮已指定专家%n"
        + "本轮用户已指定由专家「%s」作答。请**立即调用 route_decision**：action=route、expert_agent_key=%s；"
        + "即使你认为自己能回答，也要先交给该专家。";

    /**
     * 指定专家不在候选清单中时的路由纪律（清单里没有 key 可用）。
     */
    private static final String PINNED_UNKNOWN_TEMPLATE = "%n%n## 本轮已指定专家%n"
        + "本轮用户已指定某位专家作答（不在本轮候选清单中）。请**立即调用 route_decision**：action=route，"
        + "expert_agent_key 任意（服务端会按用户指定强制路由）。";

    /**
     * 在 system prompt 末尾追加候选专家清单段落；会话已指定专家时追加「必须选它」的路由纪律。
     *
     * @param agent         智能体实例
     * @param ctx           本次调用的运行期上下文
     * @param currentPrompt 前序中间件处理后的 system prompt
     * @return 追加清单段落后的 system prompt
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt == null ? "" : currentPrompt;
        AiRouteContext route = ctx == null ? null : ctx.get(AiRouteContext.class);
        if (route == null) {
            return Mono.just(base);
        }
        String prompt = base + String.format(SECTION_TEMPLATE, route.renderCandidates());
        AiExpertCandidate pinned = route.pinnedCandidate();
        if (pinned != null) {
            prompt += String.format(PINNED_TEMPLATE, pinned.agentName(), pinned.agentKey());
        } else if (route.pinnedExpertId != null) {
            prompt += PINNED_UNKNOWN_TEMPLATE;
        }
        return Mono.just(prompt);
    }

}
