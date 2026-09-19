package cn.iocoder.yudao.module.ai.framework.reversible.core.aop;

import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContextHolder;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/**
 * 上下文桥接切面：把 AI 工具层的 {@link ToolContext} 桥接到 {@link ReversibleContextHolder}。
 *
 * <p>存在的意义：让
 * {@link cn.iocoder.yudao.module.ai.framework.reversible.core.annotation.Reversible @Reversible}
 * 可以标注在<b>业务方法</b>上。
 *
 * <p>不桥接的话，「撤回单元 messageId」只能靠方法参数里的 ToolContext 传递，
 * 于是注解被迫标在 AI 工具对接层 —— 那会导致：
 * <ul>
 *     <li>同一业务操作经不同入口调用时，入账与否不一致（走 AI 入账、走 REST 不入账）</li>
 *     <li>业务方法被 AI 概念污染（要么收 ToolContext，要么不可撤回）</li>
 * </ul>
 *
 * <p>桥接后的链路：
 * <pre>
 *     AI 工具方法（持 ToolContext）
 *         → 本切面把 messageId/conversationId/tenantId 写入 Holder
 *         → 同线程调用业务方法（标了 @Reversible，签名里没有 ToolContext）
 *         → 入账切面从 Holder 读取上下文，写账本帧
 * </pre>
 *
 * <p><b>切点说明</b>：{@code execution(* *(.., ToolContext, ..))} 匹配参数列表任意位置出现
 * {@code ToolContext} 的方法，因此三种工具形态都被覆盖：
 * <ul>
 *     <li>{@code @Component("xxx")} + {@code BiFunction<Request, ToolContext, Response>} 的 {@code apply}</li>
 *     <li>普通类 + {@code @Tool} 方法 + {@code ToolContext} 参数</li>
 * </ul>
 *
 * @author hm
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100) // 在入账切面外层：先设置上下文，再执行业务
@Slf4j
public class ReversibleBridgeAspect {

    @Around("execution(* *(.., org.springframework.ai.chat.model.ToolContext, ..))")
    public Object bridge(ProceedingJoinPoint joinPoint) throws Throwable {
        ToolContext toolContext = findToolContext(joinPoint.getArgs());
        if (toolContext == null) {
            // ToolContext 参数为 null 的情况交给入账切面 fail-fast，这里不插手
            return joinPoint.proceed();
        }
        // 写入上下文 → 执行业务/工具（同线程，业务方法能读到）→ 无论如何清理
        try {
            ReversibleContextHolder.set(
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_MESSAGE_ID),
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_CONVERSATION_ID),
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID));
            return joinPoint.proceed();
        } finally {
            // 必须清理：工具可能在线程池线程（如 Netty event loop）上执行，线程会被复用
            ReversibleContextHolder.clear();
        }
    }

    /**
     * 从方法参数中找出 Spring AI 注入的 {@link ToolContext}
     */
    private ToolContext findToolContext(Object[] args) {
        return Stream.of(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .findFirst()
                .orElse(null);
    }

}
