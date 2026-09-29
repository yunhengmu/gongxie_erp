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
 * @author hengmu
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@Slf4j
public class ReversibleBridgeAspect {

    @Around("execution(* *(.., org.springframework.ai.chat.model.ToolContext, ..))")
    public Object bridge(ProceedingJoinPoint joinPoint) throws Throwable {
        ToolContext toolContext = findToolContext(joinPoint.getArgs());
        if (toolContext == null) {
            return joinPoint.proceed();
        }
        try {
            ReversibleContextHolder.set(
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_MESSAGE_ID),
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_CONVERSATION_ID),
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID)
            );
            return joinPoint.proceed();
        } finally {
            ReversibleContextHolder.clear();
        }
    }

    private ToolContext findToolContext(Object[] args) {
        return Stream.of(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .findFirst()
                .orElse(null);
    }
}
