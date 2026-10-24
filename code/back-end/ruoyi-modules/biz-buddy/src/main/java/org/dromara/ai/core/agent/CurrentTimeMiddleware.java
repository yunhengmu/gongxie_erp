package org.dromara.ai.core.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiProperties;
import reactor.core.publisher.Mono;

/**
 * 当前时间注入中间件：在每次模型调用前把「当前时间」追加到 system prompt。
 *
 * <p>模型自身不知道当前时间。框架的 {@code WorkspaceContextMiddleware} 已经在 system prompt 的
 * 固定段落里注入**英文格式的日期**（{@code Today's date is Saturday Sep 27, 2026}，由
 * {@code LocalDate.now()} 每轮重算），但不含时分秒，也不便于中文提问直接使用。本中间件补一段
 * 中文时间，两者互补、不冲突。</p>
 *
 * <p><b>为什么用中间件而不是工具</b>：</p>
 * <ul>
 *   <li>中间件是**平台级**的——所有智能体自动生效，无需逐智能体绑定工具；</li>
 *   <li>零工具调用（不额外产生模型往返与 Token 开销），也不会命中工具权限规则；</li>
 *   <li>{@code onSystemPrompt} 每次 {@code call()} 执行一次，因此**同一轮内时间一致、跨轮自动刷新**。</li>
 * </ul>
 *
 * <p>需要**精确到秒**或需要调用留痕时，由配套的 {@link GetCurrentTimeTool} 按需提供（两者共用
 * {@link CurrentTimeSupport} 的时区口径）。</p>
 *
 * <p>开关与时区实时读取 {@link AiProperties}，因此在装配层**始终注册**本中间件即可：
 * 开关变化或时区调整不需要重建智能体，也无需纳入装配指纹。</p>
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
public class CurrentTimeMiddleware implements MiddlewareBase {

    /**
     * 注入段落模板（前置两个换行，与既有段落分隔）
     */
    private static final String SECTION_TEMPLATE = "%n%n## 当前时间%n%s";

    private final AiProperties aiProperties;

    /**
     * 在 system prompt 末尾追加当前时间段落；开关关闭时原样返回。
     *
     * @param agent         智能体实例
     * @param ctx           本次调用的运行期上下文
     * @param currentPrompt 前序中间件处理后的 system prompt
     * @return 追加时间段落后的 system prompt
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt == null ? "" : currentPrompt;
        AiProperties.Chat chat = aiProperties.getChat();
        if (!chat.isCurrentTimeEnabled()) {
            return Mono.just(base);
        }
        String section = String.format(SECTION_TEMPLATE, CurrentTimeSupport.render(
            CurrentTimeSupport.resolveZone(chat.getCurrentTimeZone()), CurrentTimeSupport.MINUTE_FORMATTER));
        return Mono.just(base + section);
    }

}