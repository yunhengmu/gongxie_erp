package org.dromara.ai.core.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 继承槽位注入中间件（REQ-AI-31）：每次模型调用前把**本轮生效的槽位**追加到 system prompt。
 *
 * <p>槽位＝时区 / 时间窗 / 语言 /（可选）任务线，由 {@link AiChatFacade} 每轮按
 * 「会话级 &gt; 人级 &gt; 平台默认」合并后放入运行期上下文（{@link AiSlotSet} 键），本中间件只渲染，
 * 不在 Reactor 线程做 Redis 读取。**不进装配指纹**（与 {@link CurrentTimeMiddleware} /
 * {@link ExpertRosterMiddleware} 同一思路）。</p>
 *
 * <p>不在编排链路 / 无槽位时原样返回，因此本中间件对共享实例是安全的。</p>
 *
 * @author BizBuddy
 */
public class SlotInheritanceMiddleware implements MiddlewareBase {

    /**
     * 注入段落模板（前置两个换行，与既有段落分隔）
     */
    private static final String SECTION_TEMPLATE = "%n%n## 继承槽位%n%s%n"
        + "以上是本次会话持续生效的既定事实，直接采用、不要向用户重复追问；用户明确改变时以用户最新说法为准。";

    /**
     * 在 system prompt 末尾追加继承槽位段落。
     *
     * @param agent         智能体实例
     * @param ctx           本次调用的运行期上下文
     * @param currentPrompt 前序中间件处理后的 system prompt
     * @return 追加槽位段落后的 system prompt
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt == null ? "" : currentPrompt;
        AiSlotSet slots = ctx == null ? null : ctx.get(AiSlotSet.class);
        if (slots == null || slots.isEmpty()) {
            return Mono.just(base);
        }
        return Mono.just(base + String.format(SECTION_TEMPLATE, slots.render()));
    }

}